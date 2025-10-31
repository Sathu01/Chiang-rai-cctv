# PowerShell Script: Publish 50 Concurrent RTSP Streams to MediaMTX
# This script creates 50 FFmpeg processes publishing to rtsp://127.0.0.1:8554/v1 to v50

param(
    [int]$StreamCount = 50,
    [string]$VideoPath = "C:\Users\pc\Videos\Captures\VALORANT.mp4",
    [string]$MediaMTXHost = "127.0.0.1",
    [int]$MediaMTXPort = 8554
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "RTSP Stream Publisher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Stream Count: $StreamCount" -ForegroundColor Yellow
Write-Host "Video File: $VideoPath" -ForegroundColor Yellow
Write-Host "MediaMTX: rtsp://${MediaMTXHost}:${MediaMTXPort}" -ForegroundColor Yellow
Write-Host "========================================`n" -ForegroundColor Cyan

# Validate video file exists
if (-not (Test-Path $VideoPath)) {
    Write-Host "ERROR: Video file not found: $VideoPath" -ForegroundColor Red
    exit 1
}

# Clean up any existing ffmpeg processes
Write-Host "Cleaning up existing FFmpeg processes..." -ForegroundColor Yellow
Get-Process ffmpeg -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

# Create logs directory
$logDir = Join-Path -Path $PSScriptRoot -ChildPath "stream_logs"
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}

Write-Host "Starting $StreamCount streams..." -ForegroundColor Green
Write-Host "This will take a few moments...`n" -ForegroundColor Gray

# Array to store process objects
$processes = @()

# Start publishing streams
for ($i = 1; $i -le $StreamCount; $i++) {
    $streamName = "v$i"
    $rtspUrl = "rtsp://${MediaMTXHost}:${MediaMTXPort}/${streamName}"
    $logFile = Join-Path -Path $logDir -ChildPath "stream_$i.log"
    
    Write-Host "[$i/$StreamCount] Starting stream: $streamName" -ForegroundColor Cyan
    
    # Start FFmpeg process using Start-Process
    $ffmpegCommand = "ffmpeg"
    $ffmpegArgs = @(
        "-stream_loop", "-1",
        "-re",
        "-i", $VideoPath,
        "-f", "lavfi",
        "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
        "-map", "0:v",
        "-map", "1:a",
        "-vf", "fps=25,scale=960:540",
        "-pix_fmt", "yuv420p",
        "-c:v", "libx264",
        "-profile:v", "baseline",
        "-level", "3.0",
        "-g", "50",
        "-keyint_min", "50",
        "-sc_threshold", "0",
        "-b:v", "800k",
        "-maxrate", "800k",
        "-bufsize", "1600k",
        "-c:a", "aac",
        "-b:a", "128k",
        "-ar", "44100",
        "-ac", "2",
        "-rtsp_transport", "tcp",
        "-fflags", "+genpts",
        "-use_wallclock_as_timestamps", "1",
        "-f", "rtsp",
        $rtspUrl
    )
    
    # Start process in background
    $process = Start-Process -FilePath $ffmpegCommand `
                             -ArgumentList $ffmpegArgs `
                             -WindowStyle Hidden `
                             -PassThru `
                             -RedirectStandardError $logFile
    
    $processes += $process
    
    # Small delay to avoid overwhelming the system
    Start-Sleep -Milliseconds 200
}

Write-Host "`n========================================" -ForegroundColor Green
Write-Host "All $StreamCount streams started!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Stream URLs:" -ForegroundColor Yellow
Write-Host "  rtsp://${MediaMTXHost}:${MediaMTXPort}/v1" -ForegroundColor Gray
Write-Host "  rtsp://${MediaMTXHost}:${MediaMTXPort}/v2" -ForegroundColor Gray
Write-Host "  ..." -ForegroundColor Gray
Write-Host "  rtsp://${MediaMTXHost}:${MediaMTXPort}/v${StreamCount}" -ForegroundColor Gray
Write-Host ""
Write-Host "Logs directory: $logDir" -ForegroundColor Yellow
Write-Host ""
Write-Host "Streams are now running in background." -ForegroundColor Cyan
Write-Host "To stop streams, run: .\4-stop-all.ps1 -StreamCount $StreamCount" -ForegroundColor Yellow
Write-Host ""

# Wait a moment to verify processes started
Start-Sleep -Seconds 3
$activeCount = (Get-Process ffmpeg -ErrorAction SilentlyContinue).Count
Write-Host "Active FFmpeg processes: $activeCount" -ForegroundColor $(if ($activeCount -gt 0) { "Green" } else { "Red" })

if ($activeCount -eq 0) {
    Write-Host ""
    Write-Host "WARNING: No FFmpeg processes are running!" -ForegroundColor Red
    Write-Host "Check the logs in: $logDir" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Common issues:" -ForegroundColor Yellow
    Write-Host "  1. MediaMTX is not running (run: docker-compose up -d)" -ForegroundColor Gray
    Write-Host "  2. Video file path is incorrect" -ForegroundColor Gray
    Write-Host "  3. FFmpeg is not installed or not in PATH" -ForegroundColor Gray
    Write-Host ""
    Write-Host "To debug, check the first log file:" -ForegroundColor Yellow
    Write-Host "  Get-Content '$logDir\stream_1.log'" -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "Success! Streams are running in background." -ForegroundColor Green
}

Write-Host ""