param(
    [int]$StreamCount = 75,
    [string]$ApiBaseUrl = "http://localhost:8080",
    [string]$RtspUrl = "rtsp://127.0.0.1:8554/v1"
)

Write-Host "========================================"
Write-Host "$StreamCount Stream Concurrent Test"
Write-Host "========================================"

$results = @{
    Started = 0
    Failed  = 0
    Errors  = @()
}

$batchSize  = 25
$batchCount = [Math]::Ceiling($StreamCount / $batchSize)

for ($batch = 0; $batch -lt $batchCount; $batch++) {
    $start = ($batch * $batchSize) + 1
    $end   = [Math]::Min((($batch + 1) * $batchSize), $StreamCount)

    $jobs = @()
    for ($i = $start; $i -le $end; $i++) {
        $streamName = "cam_$i"

        $job = Start-Job -ScriptBlock {
            param($api, $rtsp, $name)
            try {
                $body = @{
                    rtspUrl    = $rtsp
                    streamName = $name
                } | ConvertTo-Json

                $response = Invoke-RestMethod `
                    -Uri "$api/api/stream/hls/start" `
                    -Method POST `
                    -ContentType "application/json" `
                    -Body $body `
                    -TimeoutSec 10

                return @{ Success = $true; Name = $name; Response = $response }
            }
            catch {
                return @{ Success = $false; Name = $name; Error = $_.Exception.Message }
            }
        } -ArgumentList $ApiBaseUrl, $RtspUrl, $streamName

        $jobs += $job
    }

    $jobs | Wait-Job | Out-Null

    foreach ($job in $jobs) {
        $result = Receive-Job -Job $job
        if ($result.Success) {
            $results.Started++
            Write-Host "V $($result.Name)"
        } else {
            $results.Failed++
            $results.Errors += "$($result.Name): $($result.Error)"
            Write-Host "X $($result.Name): $($result.Error)"
        }
    }

    $jobs | Remove-Job -Force
}

Write-Host "========================================"
Write-Host "FINAL RESULTS"
Write-Host "========================================"
Write-Host "Started: $($results.Started)"
Write-Host "Failed: $($results.Failed)"