# tenx-ai-media-service

Windows media service for the Tenx AI stack. It keeps FFmpeg work off the Mac Studio.

## Multi-shot dramas (2–12 shots, up to 60 seconds)

`POST /api/v1/dramas/generations` accepts a persisted plan; all drama endpoints require the existing media API key:

```json
{
  "model": "Wan2.2-TI2V-5B",
  "size": "640x352",
  "character_prompt": "Red armored robot with a blue blade; black opponent with shoulder cannons. Consistent anime style.",
  "shots": [
    {"prompt": "Red robot punches; black robot blocks, sparks at contact.", "duration": 5, "continue_previous": false},
    {"prompt": "Black robot counterattacks; red robot dodges sideways.", "duration": 5, "continue_previous": true,
     "audio": {"dialogues": [{"speaker": "Guard", "text": "Too slow!", "voice": "demo", "pause_ms": 0}]}}
  ]
}
```

- Each shot is 1–5 seconds. Only Wan is enabled for the first drama implementation. Repeat/add shots to reach 20/30/60 seconds; no long single inference is requested.
- Optional plan-level `start_image_result_id` applies to independent shots; a shot-level image overrides it. Tail-continuation shots use `start_video_result_id` instead and may not specify their own image.
- Deploy the updated **Mac gateway first**: previous versions cannot resolve video-tail references. ComfyUI workflow/model installation does not change; no SMB/file sharing is needed. Gateway output PNGs must remain available for dependent shots.
- The media worker checks all voice IDs first, then serially generates each shot's audio, rejects overlong speech **before generating that shot's frames**, downloads frames, and encodes H.264 + mono 24kHz AAC. Silent shots receive silent audio for compatible concatenation. No video freeze padding is used in drama mode. Legacy single-shot behavior is unchanged.
- Status/list/download reuse `/api/v1/videos/jobs/{id}`, `/api/v1/videos/jobs` and `/api/v1/videos/results/{id}/content`. Parents have `type: "drama"`, `dramaRequest`, `dramaShots`, `currentShotIndex` (zero-based), and `dynamicDurationSeconds` (planned duration until completion). Internal children have `parentId`; the UI hides them from the top-level list.
- `POST /api/v1/dramas/jobs/{id}/retry` with `{}` resumes missing/failed shots and keeps completed shots. With `{"shot_index": 1}` it regenerates that shot and following consecutive tail-dependent shots, stopping invalidation at the next independent shot. Both modes reassemble the final video. Concurrent retries are rejected.
- To fix an overlong line or change a visual prompt, send `{"shot_index": 1, "shot": {"prompt": "...", "duration": 5, "continue_previous": true, "audio": {"dialogues": [...]}}}`. `shot` is a full replacement, validated before altering the existing plan. Only that shot and its tail-dependent chain are invalidated.
- Jobs and child results are checkpointed in `results-root/jobs`; after a service restart unfinished parents become failed and can be manually resumed. This does not automatically restart work. Intermediate frames/audio and MP4s use the existing configured storage roots/mounts; no files are automatically removed.
- Tail references help composition continuity, not guaranteed character identity, seamless motion, or lip sync. Changing camera angle should normally use an independent shot.

Verification: `mvn test`; optional real FFmpeg fixture test: `mvn test "-Dtenx.ffmpeg=ffmpeg"` (also requires `ffprobe`). The latter writes a 30-second moving-shape fixture under `target/drama-smoke`, not AI-generated robot footage.

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

### Multi-role dialogue and speech preview

Send `audio.dialogues` instead of `audio.text` (never both):

```json
{
  "dialogues": [
    {"speaker":"小雨","text":"地图说，最后一颗星就在这里。","voice":"xiaoyu","instructions":"轻声说，带着期待。","speed":1.0,"pause_ms":400},
    {"speaker":"阿零","text":"可这里……只有我们。","voice":"aling","instructions":"有些犹豫。","speed":1.0,"pause_ms":600}
  ]
}
```

Speaker labels (up to 20 characters) are not spoken. Matching leading labels such as
`小雨：` and outer quotation marks are stripped; actual spoken names such as
`阿零，你胸口为什么在发光？` remain. Emotion instructions are passed separately to
CosyVoice instruct2; their effectiveness requires auditioning, not a guarantee.

Limits: 1-30 lines, total spoken text at most 500 characters, pause 0-5000 ms
(default 300); voice/speed/instructions are configured per line. Referenced voices
are checked before requesting Gateway frames. Missing voices fail early, with no
silent substitution. `audioDialogues` preserves dialogue metadata in saved video jobs.

Authenticated endpoints using the same media-service bearer key:

- `GET /api/v1/audio/voices`: installed CosyVoice voice profiles.
- `POST /api/v1/audio/speech`: accepts the audio object above (or legacy text/voice)
  and returns a WAV for audition without generating Gateway video frames.

The runtime generates lines serially, gently aligns per-line RMS with bounded gain
and peak protection, and inserts sample-accurate silence before the existing AAC/MP4
pipeline. This is not professional LUFS mastering or lip synchronization. Segment-level
CPU fallback remains active. Legacy single-text clients retain their existing behavior.

Only `demo` is initially installed. Two labels assigned to `demo` still have one timbre.
For distinct voices, install authorized reference WAV/transcript pairs in CosyVoice,
restart that container, and refresh the UI voice list. WAV previews remain in audio storage.

### CosyVoice single-text audio options

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
