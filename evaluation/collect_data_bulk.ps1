# =============================================================================
# collect_data_bulk.ps1  -  target-driven bulk dataset expansion
#
# Loops a varied scenario block (healthy / mobility / battery / CPU / payload /
# latency-sensitive) until the on-device CSV reaches -TargetLines, then pulls.
# Used to scale the adaptive pool up to a requested train/test size. Appends to
# the same mocca-metrics.csv - every prior row is preserved, and because it
# appends continuously an interrupted run still keeps everything collected so
# far (just re-run to continue, or pull manually).
#
# Safety: a MAX_ITER cap plus a per-iteration row-delta check abort the loop if
# the device disconnects (broadcasts would otherwise silently no-op forever).
#
# Usage:
#   cd <repo-root>
#   .\evaluation\collect_data_bulk.ps1 -EdgeCloudHost 192.168.2.13 -TargetLines 2100
# =============================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$EdgeCloudHost,
    [int]$EdgePort  = 8001,
    [int]$CloudPort = 8002,
    # Stop once the on-device CSV has this many lines (header + rows). ~2100
    # lines => ~1760 adaptive rows given the current non-adaptive baseline.
    [int]$TargetLines = 2100,
    [int]$MaxIter = 8
)

$PKG              = "com.thesis.middleware"
$ACTION_RUN       = "$PKG.RUN_TASK"
$ACTION_MODE      = "$PKG.SET_MODE"
$ACTION_DBG       = "$PKG.SET_DEBUG"
$ACTION_CLR       = "$PKG.CLEAR_DEBUG"
$ACTION_ENDPOINTS = "$PKG.SET_ENDPOINTS"

$EDGE_URL  = "http://${EdgeCloudHost}:${EdgePort}"
$CLOUD_URL = "http://${EdgeCloudHost}:${CloudPort}"

$TASK_DELAY_MS  = 3000
$VIDEO_DELAY_MS = 6000
$CSV_PATH = "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv"

function Section($t) { Write-Host ""; Write-Host ("=" * 60) -ForegroundColor Cyan; Write-Host "  $t" -ForegroundColor Cyan; Write-Host ("=" * 60) -ForegroundColor Cyan }
function Sub($t)     { Write-Host ""; Write-Host "  -- $t --" -ForegroundColor Magenta }
function Step($m)    { Write-Host "  >> $m" -ForegroundColor DarkYellow }
function Ok($m)      { Write-Host "  OK  $m" -ForegroundColor Green }
function Warn($m)    { Write-Host "  WARN  $m" -ForegroundColor Red }

function Broadcast($action, $extras = "") {
    Invoke-Expression "adb shell am broadcast -n $PKG/.AutoRunReceiver -a $action $extras" | Out-Null
}
function Run-Task($task, $count, $delayMs = $TASK_DELAY_MS, $size = 0) {
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    $sizeArg = ""; $label = "$task x$count"
    if ($size -gt 0) { $sizeArg = "--ei size $size"; $label = "$task x$count (size=$size)" }
    Step "run $label  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs $sizeArg"
    Start-Sleep -Seconds $waitSec
}
function Assert-ServersHealthy {
    $edgeOk = $false; $cloudOk = $false
    try { $edgeOk  = ((curl.exe -s -o /dev/null -w "%{http_code}" "$EDGE_URL/health")  -eq "200") } catch {}
    try { $cloudOk = ((curl.exe -s -o /dev/null -w "%{http_code}" "$CLOUD_URL/health") -eq "200") } catch {}
    if (-not ($edgeOk -and $cloudOk)) { Warn "Servers unhealthy (edge=$edgeOk cloud=$cloudOk)"; return $false }
    return $true
}
function Get-LineCount {
    $raw = adb shell wc -l $CSV_PATH 2>$null
    return [int](($raw -replace '\D.*',''))
}

# One varied block ~= 200 adaptive rows across all conditions.
function Invoke-ScenarioBlock($iter) {
    Section "ITERATION $iter"

    if (-not (Assert-ServersHealthy)) { Warn "skipping remote-heavy sub-sessions this iteration"; }

    Sub "healthy baseline"
    Broadcast $ACTION_MODE "--es mode ADAPTIVE"; Broadcast $ACTION_CLR; Start-Sleep 2
    Run-Task "echo"              8
    Run-Task "sha256"            10
    Run-Task "image-grayscale"   8
    Run-Task "matrix-multiply"   8
    Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS

    Sub "mobility sweep (CLOUD)"
    foreach ($state in @("STATIONARY", "WALKING", "VEHICLE")) {
        Broadcast $ACTION_DBG "--es movement_state $state"; Start-Sleep 3
        Run-Task "matrix-multiply"   5
        Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
    }
    Broadcast $ACTION_CLR; Start-Sleep 2

    Sub "battery sweep (LOW_BATTERY_OFFLOAD)"
    Broadcast $ACTION_DBG "--ef remote_energy_mj 50"; Start-Sleep 2
    foreach ($lvl in @(28, 14, 8)) {
        adb shell dumpsys battery unplug | Out-Null
        adb shell dumpsys battery set level $lvl | Out-Null
        adb shell dumpsys battery set status 3 | Out-Null
        Start-Sleep 5
        Run-Task "echo"              3
        Run-Task "sha256"            4
        Run-Task "image-grayscale"   4
        Run-Task "matrix-multiply"   3
        Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
    }
    adb shell dumpsys battery reset | Out-Null
    Broadcast $ACTION_CLR; Start-Sleep 2

    Sub "CPU stress (LATENCY_SENSITIVE)"
    Broadcast $ACTION_RUN "--es task matrix-multiply --ei count 30 --el delay_ms 500"; Start-Sleep 5
    Run-Task "sha256" 10
    Run-Task "echo"   10
    Start-Sleep 18

    Sub "payload sweep"
    foreach ($b in @(1024, 16384, 262144, 1048576)) { Run-Task "sha256" 5 $TASK_DELAY_MS $b }
    foreach ($s in @(128, 256, 512))                { Run-Task "image-grayscale" 4 $TASK_DELAY_MS $s }
    foreach ($n in @(16, 32, 64, 96))               { Run-Task "matrix-multiply" 4 $TASK_DELAY_MS $n }

    Sub "latency-sensitive (forced speedup)"
    Broadcast $ACTION_DBG "--ef speedup 3.0"; Start-Sleep 2
    Run-Task "echo"   10
    Run-Task "sha256" 10
    Broadcast $ACTION_CLR; Start-Sleep 2
}

# =============================================================================
# PRE-FLIGHT
# =============================================================================

Section "PRE-FLIGHT"
$device = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" } | Select-Object -First 1
if (-not $device) { Write-Error "No ADB device found."; exit 1 }
Ok "Device: $device"
if (-not (Assert-ServersHealthy)) { Write-Error "Servers not reachable."; exit 1 }
Ok "Edge + Cloud healthy"

Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"; Start-Sleep 2

$startLines = Get-LineCount
Write-Host "  START lines: $startLines   TARGET: $TargetLines" -ForegroundColor Gray

# =============================================================================
# MAIN LOOP
# =============================================================================
$iter = 0
while ((Get-LineCount) -lt $TargetLines -and $iter -lt $MaxIter) {
    $iter++
    $before = Get-LineCount
    Invoke-ScenarioBlock $iter
    $after = Get-LineCount
    $delta = $after - $before
    Write-Host ""
    Ok "Iteration $iter added $delta rows (now $after / $TargetLines)"
    if ($delta -lt 10) {
        Warn "Iteration added <10 rows - device likely disconnected. Aborting loop."
        break
    }
}

# =============================================================================
# CLEANUP + PULL
# =============================================================================
Section "CLEANUP"
adb shell dumpsys battery reset | Out-Null
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep 2
Ok "Battery reset, mode ADAPTIVE, overrides cleared"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
$endLines = Get-LineCount
Write-Host "  END lines: $endLines  (added ~$($endLines - $startLines) rows over $iter iteration(s))" -ForegroundColor Gray
adb pull $CSV_PATH $outFile
Ok "Saved to $outFile"
Write-Host ""
Write-Host "  Done. Re-run make_evaluation_figures.py + regen_policy_figures.py + notebook." -ForegroundColor Cyan
