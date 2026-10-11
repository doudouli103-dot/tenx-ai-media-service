# tenx-ai-media-service

Windows media service for the Tenx AI stack. It keeps FFmpeg work off the Mac Studio.

## Responsibilities

- Accept image and video generation requests from WebUI or `video-agent`.
- Forward model generation to `tenx-ai-gateway` on the Mac Studio.
- Copy generated images into media-service-owned result storage.
- Receive the Gateway video-frame manifest, download authenticated PNG frames over HTTP, and encode the final MP4 with Windows FFmpeg.
- Persist video job metadata so completed and failed jobs remain visible after restart.
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
$env:TENX_MEDIA_FRAME_CACHE_ROOT = "D:\tenx-ai\frame-cache"
$env:TENX_MEDIA_RESULTS_ROOT = "D:\tenx-ai\adapter-results"
$env:TENX_MEDIA_MAX_FRAME_BYTES = "52428800"
$env:TENX_MEDIA_FFMPEG_PATH = "ffmpeg"
$env:TENX_MEDIA_MAX_CONCURRENT_VIDEO_JOBS = "1"
```

`TENX_MEDIA_RESULTS_ROOT` contains:

```text
adapter-results/
  images/     copied image results
  videos/     final MP4 files
  jobs/       persistent video job JSON
  logs/       FFmpeg logs
```

`TENX_MEDIA_FRAME_CACHE_ROOT` is disposable. Each job downloads its frame sequence into a job-specific directory and removes that directory after FFmpeg succeeds or fails.

## Build and start

```powershell
mvn clean package
.\scripts\start.ps1
```

Or run directly during development:

```powershell
mvn spring-boot:run
```

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
  start_image_result_id = "tenx_0123456789abcdef0123456789abcdef_00001_"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri "http://windows.tentest.cn:8092/api/v1/videos/generations" `
  -Headers @{ Authorization = "Bearer replace-with-media-service-key" } `
  -ContentType "application/json" `
  -Body $body
```

The service returns HTTP `202` with a job whose status is initially `QUEUED`. Poll:

```text
GET /api/v1/videos/jobs/{jobId}
```

Statuses:

```text
QUEUED -> GENERATING_FRAMES -> DOWNLOADING_FRAMES -> ENCODING -> COMPLETED
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
