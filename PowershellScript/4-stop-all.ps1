# PowerShell Script: Stop All Streams and Clean Up
# Stops all FFmpeg processes and cleans HLS output

param(
    [int]$StreamCount = 50,
    [string]$ApiBaseUrl = "http://localhost:8080"
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Stop All Streams & Cleanup" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Stop FFmpeg processes forcefully
Write-Host "`nForce stopping all FFmpeg processes..." -ForegroundColor Yellow
$ffmpegProcs = Get-Process -Name ffmpeg -ErrorAction SilentlyContinue
if ($ffmpegProcs) {
    Write-Host "Found $($ffmpegProcs.Count) FFmpeg processes" -ForegroundColor Gray
    
    # Try graceful kill first
    $ffmpegProcs | ForEach-Object {
        try {
            $_.Kill()
            Write-Host "  Killed process ID: $($_.Id)" -ForegroundColor Gray
        } catch {
            Write-Host "  Failed to kill process ID: $($_.Id)" -ForegroundColor Red
        }
    }
    
    Start-Sleep -Seconds 2
    
    # Force kill any remaining
    Get-Process ffmpeg -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    
    Start-Sleep -Seconds 1
    
    # Verify
    $remaining = (Get-Process ffmpeg -ErrorAction SilentlyContinue).Count
    if ($remaining -eq 0) {
        Write-Host "Success: All FFmpeg processes stopped" -ForegroundColor Green
    } else {
        Write-Host "Warning: $remaining FFmpeg processes still running" -ForegroundColor Yellow
    }
} else {
    Write-Host "Success: No FFmpeg processes found" -ForegroundColor Green
}

# Stop HLS streams via API (if available)
Write-Host "`nStopping HLS streams via API..." -ForegroundColor Yellow
$stopCount = 0
$failCount = 0

for ($i = 1; $i -le $StreamCount; $i++) {
    $streamName = "cam_$i"
    
    try {
        $null = Invoke-RestMethod `
            -Uri "$ApiBaseUrl/api/stream/hls/stop/$streamName" `
            -Method POST `
            -TimeoutSec 2 `
            -ErrorAction Stop
        
        $stopCount++
        if ($i -le 5 -or $i % 10 -eq 0) {
            Write-Host "  Stopped: $streamName" -ForegroundColor Gray
        }
    } catch {
        $failCount++
    }
}

if ($stopCount -gt 0) {
    Write-Host "Success: Stopped $stopCount streams via API" -ForegroundColor Green
}
if ($failCount -gt 0) {
    Write-Host "Info: $failCount streams were not running" -ForegroundColor Gray
}

# Clean up HLS output directory
Write-Host "`nCleaning HLS output directory..." -ForegroundColor Yellow
$hlsDir = Join-Path -Path $PSScriptRoot -ChildPath "hls"
if (Test-Path $hlsDir) {
    try {
        Remove-Item -Path $hlsDir -Recurse -Force -ErrorAction Stop
        Write-Host "Success: HLS directory cleaned" -ForegroundColor Green
    } catch {
        Write-Host "Warning: Could not delete HLS directory: $($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "Info: HLS directory not found (already clean)" -ForegroundColor Gray
}

# Clean up logs
Write-Host "`nCleaning stream logs..." -ForegroundColor Yellow
$logDir = Join-Path -Path $PSScriptRoot -ChildPath "stream_logs"
if (Test-Path $logDir) {
    try {
        Remove-Item -Path $logDir -Recurse -Force -ErrorAction Stop
        Write-Host "Success: Stream logs cleaned" -ForegroundColor Green
    } catch {
        Write-Host "Warning: Could not delete log directory: $($_.Exception.Message)" -ForegroundColor Yellow
    }
} else {
    Write-Host "Info: No stream logs found" -ForegroundColor Gray
}

# Kill any zombie PowerShell jobs
Write-Host "`nCleaning up PowerShell jobs..." -ForegroundColor Yellow
$jobs = Get-Job -ErrorAction SilentlyContinue
if ($jobs) {
    $jobs | Stop-Job -ErrorAction SilentlyContinue
    $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
    Write-Host "Success: Cleaned $($jobs.Count) PowerShell jobs" -ForegroundColor Green
} else {
    Write-Host "Info: No PowerShell jobs found" -ForegroundColor Gray
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Cleanup Complete!" -ForegroundColor Green
Write-Host "========================================`n" -ForegroundColor Cyan