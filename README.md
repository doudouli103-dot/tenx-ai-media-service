# tenx-ai-media-service

Windows media service for the Tenx AI stack. It keeps FFmpeg work off the Mac Studio.

## Responsibilities

- Accept image and video generation requests from WebUI or `video-agent`.
- Forward model generation to `tenx-ai-gateway` on the Mac Studio.
- Copy generated images into media-service-owned `adapter-results` storage on the external HDD.
- Receive the Gateway video-frame manifest, read PNG frames over SMB, and encode the final MP4 with Windows FFmpeg.
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
      -> Windows reads the SMB frame directory
      -> Windows FFmpeg creates adapter-results/videos/<jobId>.mp4
      -> Client downloads the final MP4 from port 8092
```

## Requirements on Windows

- Java 8 or newer
- Maven 3.8 or newer for building
- FFmpeg available on `PATH`
- DNS or hosts entries for `macstudio.tentest.cn` and `windows.tentest.cn`
- Read access to an SMB share that maps to the Mac frame directory:

```text
/Volumes/LJW/tenx-ai/comfyui/output
```

- Read/write access to a second SMB share that maps to the external-HDD result directory:

```text
/Volumes/LJW/tenx-ai/comfyui/adapter-results
```

The examples assume these SMB paths:

```text
\\macstudio.tentest.cn\comfy-output
\\macstudio.tentest.cn\adapter-results
```

## Configuration

```powershell
$env:TENX_MEDIA_API_KEYS = "replace-with-media-service-key"
$env:TENX_AI_GATEWAY_BASE_URL = "http://macstudio.tentest.cn:8088/v1"
$env:TENX_AI_GATEWAY_API_KEY = "replace-with-gateway-key"
$env:TENX_MEDIA_PUBLIC_BASE_URL = "http://windows.tentest.cn:8092"
$env:TENX_MEDIA_FRAMES_ROOT = "\\macstudio.tentest.cn\comfy-output"
$env:TENX_MEDIA_RESULTS_ROOT = "\\macstudio.tentest.cn\adapter-results"
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
QUEUED -> GENERATING_FRAMES -> ENCODING -> COMPLETED
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

Image generation waits for the Gateway response, copies the image to external-HDD `adapter-results` storage through SMB, and replaces the Gateway URL with the media-service URL.

## Health

`GET /healthz` does not require an API key. It reports whether the SMB frame root and local result root are available.

## Tests

```powershell
mvn test
```

Tests cover API-key enforcement, application configuration, frame-manifest validation, persistent job metadata, Gateway authentication, and the handoff to the FFmpeg runner.
