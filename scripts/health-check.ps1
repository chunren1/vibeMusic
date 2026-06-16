param(
    [string]$LogDir = "$PSScriptRoot\..\docker-data\health-logs",
    [int]$AlertThreshold = 3
)

if (-not (Test-Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
}

$logFile = "$LogDir\health-$(Get-Date -Format 'yyyyMMdd').log"
$stateFile = "$LogDir\.fail-count.json"

$failCount = @{}
if (Test-Path $stateFile) {
    try { $failCount = Get-Content $stateFile | ConvertFrom-Json -AsHashtable } catch {}
}

function Write-Log($level, $msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [$level] $msg"
    Add-Content -Path $logFile -Value $line
    $color = @{ INFO='DarkGray'; WARN='Yellow'; ERROR='Red' }[$level]
    Write-Host $line -ForegroundColor $color
}

$raw = docker ps -a --filter "name=vibemusic" --format "{{.Names}}" 2>$null
if (-not $raw) {
    Write-Log ERROR "No vibemusic containers found"
    exit 1
}

$names = $raw -split "`n"

foreach ($name in $names) {
    if (-not $name) { continue }

    $inspect = docker inspect $name --format "{{json .State}}" 2>$null | ConvertFrom-Json
    if (-not $inspect) {
        Write-Log ERROR "$name inspect failed"
        continue
    }

    $status = $inspect.Status
    $health = ""
    if ($inspect.Health) { $health = $inspect.Health.Status }

    if ($status -ne "running") {
        Write-Log ERROR "$name not running (status=$status)"
        if (-not $failCount[$name]) { $failCount[$name] = 0 }
        $failCount[$name]++
        continue
    }

    if ($health -eq "healthy") {
        Write-Log INFO "$name healthy"
        $failCount[$name] = 0
    } elseif ($health -eq "starting") {
        Write-Log WARN "$name starting..."
        if (-not $failCount[$name]) { $failCount[$name] = 0 }
        $failCount[$name]++
    } elseif ($health) {
        Write-Log ERROR "$name unhealthy ($health)"
        if (-not $failCount[$name]) { $failCount[$name] = 0 }
        $failCount[$name]++
    } else {
        Write-Log INFO "$name running (no healthcheck)"
    }

    if ($failCount[$name] -ge $AlertThreshold) {
        Write-Log ERROR "ALERT: $name failed $AlertThreshold times - manual intervention required"
    }
}

$failCount | ConvertTo-Json | Set-Content $stateFile
