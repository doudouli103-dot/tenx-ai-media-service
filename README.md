# tenx-ai-media-service

Windows media service for the Tenx AI stack. It keeps FFmpeg work off the Mac Studio.

## Responsibilities

- Accept image and video generation requests from WebUI or `video-agent`.
- Forward model generation to `tenx-ai-gateway` on the Mac Studio.
- Copy generated images into media-service-owned result storage.
- Receive the Gateway video-frame manifest, download authenticated PNG frames over HTTP, and encode the final MP4 with Windows FFmpeg.
- Persist video job metadata so completed and failed jobs remain visible after restart.
- Generate optional narration/dialogue with the separate Windows CosyVoice runtime and mux AAC audio into the final MP4.
- Provide authenticated result URLs.

The service never runs ComfyUI and never loads model weights.

## Runtime flow

```text
Client
  -> Windows tenx-ai-media-service:8092
      -> Mac Studio tenx-ai-gateway:8088
          -> ComfyUI:8188
              -> /Volumes/LJW/tenx-ai/comfyui/output/tenx-video-frames/<resultId>
      -> Windows downloads frames through the authenticated Gateway API
      -> Optional CosyVoice speech generation -> WAV on the external disk
      -> Windows FFmpeg creates adapter-results/videos/<jobId>.mp4
      -> Client downloads the final MP4 from port 8092
```

## Requirements on Windows

- Java 8 or newer
- Maven 3.8 or newer for building
- FFmpeg available on `PATH`
- DNS or hosts entries for `macstudio.tentest.cn` and `windows.tentest.cn`
- HTTP access from Windows to `http://macstudio.tentest.cn:8088`.
- A local Windows or Docker-mounted writable directory for frame cache and final results.
- No Mac SMB file sharing is required.

## Configuration

```powershell
$env:TENX_MEDIA_API_KEYS = "replace-with-media-service-key"
$env:TENX_AI_GATEWAY_BASE_URL = "http://macstudio.tentest.cn:8088/v1"
$env:TENX_AI_GATEWAY_API_KEY = "replace-with-gateway-key"
$env:TENX_MEDIA_PUBLIC_BASE_URL = "http://windows.tentest.cn:8092"
$env:TENX_MEDIA_FRAME_CACHE_ROOT = "E:\tenx-ai-media\frames"
$env:TENX_MEDIA_RESULTS_ROOT = "D:\tenx-ai\adapter-results"
$env:TENX_MEDIA_MAX_FRAME_BYTES = "52428800"
$env:TENX_MEDIA_FFMPEG_PATH = "ffmpeg"
$env:TENX_MEDIA_MAX_CONCURRENT_VIDEO_JOBS = "1"
$env:TENX_COSYVOICE_BASE_URL = "http://localhost:4030"
$env:TENX_COSYVOICE_API_KEY = ""
$env:TENX_COSYVOICE_TIMEOUT_MILLIS = "3600000"
$env:TENX_MEDIA_AUDIO_ROOT = "E:\tenx-ai-media\audio"
```

`TENX_MEDIA_RESULTS_ROOT` contains:

```text
adapter-results/
  images/     copied image results
  videos/     final MP4 files
  jobs/       persistent video job JSON
  logs/       FFmpeg logs
```

`TENX_MEDIA_FRAME_CACHE_ROOT` stores each successful job's downloaded frame sequence in a job-specific directory. The Docker deployment maps it to the Windows USB drive `E:\tenx-ai-media\frames`; frames remain after FFmpeg completes. Incomplete downloads are removed when a download fails.

## Build and start

```powershell
mvn clean package
.\scripts\start.ps1
```

Or run directly during development:

```powershell
mvn spring-boot:run
```

## Docker deployment

From the parent `tenx-ai-project` directory:

```powershell
Copy-Item .env.media.example .env
docker compose -f docker-compose.media.yml up -d --build
```

The container includes FFmpeg. Host frame and result directories are mounted at
`/data/frames` and `/data/results`; configure their Windows paths in `.env`.
Audio is mounted from `E:\tenx-ai-media\audio` to `/data/audio`. Start the separate
`../cosyvoice/docker-compose.yml` deployment first; both containers share
`tenx-ai-media_media-network`. Inside Docker, `TENX_COSYVOICE_BASE_URL` defaults to
`http://cosyvoice:4030`, not localhost. If CosyVoice has an API key, configure the same
value as `TENX_COSYVOICE_API_KEY` for media-service. Keep one concurrent video job on the 4 GB GPU.

## Video API

Create an asynchronous video job:

```powershell
$body = @{
  model = "Wan2.2-TI2V-5B"
  prompt = "A robot walks through a futuristic city"
  duration = 1
  size = "640x352"
  project_id = "drama-001"
  scene_id = "scene-001"
  shot_id = "shot-001"
  audio = @{ text = "你好，欢迎来到动漫世界。"; voice = "demo"; speed = 1.0 }
  start_image_result_id = "tenx_0123456789abcdef0123456789abcdef_00001_"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://windows.tentest.cn:8092/api/v1/videos/generations" `
  -Headers @{ Authorization = "Bearer replace-with-media-service-key" } `
  -ContentType "application/json; charset=utf-8" `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

The service returns HTTP `202` with a job whose status is initially `QUEUED`. Poll:

```text
GET /api/v1/videos/jobs/{jobId}
```

Statuses:

```text
QUEUED -> GENERATING_FRAMES -> DOWNLOADING_FRAMES -> [GENERATING_AUDIO] -> ENCODING -> COMPLETED
                                                                                  or FAILED
```

When completed, `resultUrl` points to:

```text
GET /api/v1/videos/results/{jobId}/content
```

List jobs:

```text
GET /api/v1/videos/jobs
```

### CosyVoice audio options

`audio` is optional; omitting it (or setting it to null) preserves silent video generation.
It is consumed by media-service and never forwarded to Gateway/ComfyUI.

- `text`: required, 1-500 characters of narration/dialogue; do not use the visual prompt as dialogue.
- `voice`: defaults to `demo`; must name a profile installed in the CosyVoice runtime.
- `speed`: 0.5-2.0, default 1.0.
- `instructions`: optional emotion/accent instruction, maximum 200 characters.
- `seed`: optional speech seed, 0-2147483647, default 42 (independent of the video seed).

The service validates the returned WAV, saves `<jobId>.wav` on the external disk, then
encodes H.264 video with an AAC audio track. It preserves the longer of video/speech:
long speech extends the final video frame, and short speech is padded with silence.
This is narration/dialogue mixing, not lip synchronization or background music generation.
CosyVoice automatically splits narration into short sequential segments. A CUDA OOM
terminates its GPU worker and retries the failed segment once on a fresh CPU FP32 worker;
subsequent requests stay on CPU until CosyVoice restarts. Completed segments are preserved.
The default speech timeout is 60 minutes to allow slower CPU recovery. Other
speech failures (including exhausted recovery/timeout/429/unknown voice) fail the job rather than silently
returning a silent video; the downloaded frames are retained. Job metadata includes
`audioEnabled`, `audioText`, `audioVoice`, `audioId`, and `audioDurationSeconds`.
Existing completed MP4s are not retroactively modified; submit a new job with `audio`.

The UI provides a CosyVoice toggle, separate dialogue field, voice ID and speed selector.
Click play in the preview to hear the resulting video (no unmuted autoplay).

## Image API

```text
POST /api/v1/images/generations
GET  /api/v1/images/results/{resultId}/content
```

Image generation waits for the Gateway response, downloads the image over authenticated HTTP into media-service-owned storage, and replaces the Gateway URL with the media-service URL.

## Health

`GET /healthz` does not require an API key. It reports authenticated HTTP frame transport plus local frame-cache and result-directory availability.

## Tests

```powershell
mvn test
```

Tests cover API-key enforcement, application configuration, frame-manifest validation, persistent job metadata, Gateway authentication, and the handoff to the FFmpeg runner.
Audio tests also cover request validation, authentication, WAV validation, failure propagation,
audio-option isolation from Gateway, and real FFmpeg mux/duration behavior when FFmpeg/ffprobe are available.

Opt-in smoke test with real local CosyVoice and FFmpeg (uses synthetic Gateway frames, not Mac inference):

```powershell
$env:TENX_COSYVOICE_SMOKE = "true"
mvn -Dtest=CosyVoicePipelineSmokeTest test
Remove-Item Env:TENX_COSYVOICE_SMOKE
```

The smoke-test MP4 is retained under `target/cosyvoice-smoke/videos`.
