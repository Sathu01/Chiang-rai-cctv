# Simple test script to debug a single RTSP stream
param(
    [string]$VideoPath = "C:\Users\pc\Videos\Captures\VALORANT   2025-04-30 23-27-59.mp4"
)

Write-Host "Testing Single RTSP Stream" -ForegroundColor Cyan
Write-Host "=========================" -ForegroundColor Cyan

# Check if video exists
if (-not (Test-Path $VideoPath)) {
    Write-Host "ERROR: Video file not found: $VideoPath" -ForegroundColor Red
    exit 1
}
Write-Host "Video file found: OK" -ForegroundColor Green

# Check if FFmpeg is available
try {
    $ffmpegVersion = ffmpeg -version 2>&1 | Select-Object -First 1
    Write-Host "FFmpeg found: $ffmpegVersion" -ForegroundColor Green
} catch {
    Write-Host "ERROR: FFmpeg not found in PATH" -ForegroundColor Red
    Write-Host "Please install FFmpeg from: https://www.gyan.dev/ffmpeg/builds/" -ForegroundColor Yellow
    exit 1
}

# Check if MediaMTX is running
Write-Host "`nChecking MediaMTX..." -ForegroundColor Cyan
try {
    $response = Invoke-WebRequest -Uri "http://localhost:9997/v3/config/global/get" -TimeoutSec 3
    Write-Host "MediaMTX is running: OK" -ForegroundColor Green
} catch {
    Write-Host "WARNING: Cannot connect to MediaMTX API on port 9997" -ForegroundColor Yellow
    Write-Host "Make sure MediaMTX is running: docker-compose up -d" -ForegroundColor Yellow
}

# Test RTSP connection
Write-Host "`nTesting RTSP connection..." -ForegroundColor Cyan
$testUrl = "rtsp://127.0.0.1:8554/test_stream"

Write-Host "Starting FFmpeg in foreground (you'll see output)..." -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop`n" -ForegroundColor Red

# Run FFmpeg in foreground so we can see errors
& ffmpeg `
    -re `
    -stream_loop -1 `
    -i $VideoPath `
    -f lavfi `
    -i "anullsrc=channel_layout=stereo:sample_rate=44100" `
    -map 0:v `
    -map 1:a `
    -vf "fps=25,scale=960:540" `
    -pix_fmt yuv420p `
    -c:v libx264 `
    -profile:v baseline `
    -level 3.0 `
    -g 50 `
    -keyint_min 50 `
    -sc_threshold 0 `
    -b:v 800k `
    -maxrate 800k `
    -bufsize 1600k `
    -c:a aac `
    -b:a 128k `
    -ar 44100 `
    -ac 2 `
    -rtsp_transport tcp `
    -f rtsp `
    $testUrl

Write-Host "`nStream stopped." -ForegroundColor Yellow
