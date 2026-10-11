$ErrorActionPreference = "Stop"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "java is not available on PATH"
}
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    throw "ffmpeg is not available on PATH"
}

if (-not $env:TENX_MEDIA_API_KEYS) { $env:TENX_MEDIA_API_KEYS = "local-dev-key" }
if (-not $env:TENX_AI_GATEWAY_BASE_URL) { $env:TENX_AI_GATEWAY_BASE_URL = "http://macstudio.tentest.cn:8088/v1" }
if (-not $env:TENX_AI_GATEWAY_API_KEY) { $env:TENX_AI_GATEWAY_API_KEY = "local-dev-key" }
if (-not $env:TENX_MEDIA_PUBLIC_BASE_URL) { $env:TENX_MEDIA_PUBLIC_BASE_URL = "http://windows.tentest.cn:8092" }
if (-not $env:TENX_MEDIA_FRAME_CACHE_ROOT) { $env:TENX_MEDIA_FRAME_CACHE_ROOT = "$PSScriptRoot\..\data\frame-cache" }
if (-not $env:TENX_MEDIA_RESULTS_ROOT) { $env:TENX_MEDIA_RESULTS_ROOT = "$PSScriptRoot\..\data\results" }

New-Item -ItemType Directory -Force -Path $env:TENX_MEDIA_FRAME_CACHE_ROOT | Out-Null
New-Item -ItemType Directory -Force -Path $env:TENX_MEDIA_RESULTS_ROOT | Out-Null

$jar = Get-ChildItem -Path "$PSScriptRoot\..\target\tenx-ai-media-service-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Select-Object -First 1
if (-not $jar) {
    throw "Build the service first with: mvn clean package"
}

java -jar $jar.FullName
