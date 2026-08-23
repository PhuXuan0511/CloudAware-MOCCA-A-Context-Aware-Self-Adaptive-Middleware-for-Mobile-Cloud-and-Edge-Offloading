# =============================================================================
# collect_data_cloud_topup.ps1  -  targeted CLOUD-class expansion
#
# The adaptive training set currently has only 22 CLOUD rows (3.8% share),
# which is too small for stable per-class metrics. Analysis of existing rows
# shows two reliable triggers:
#   - HEAVY_COMPUTE_GOOD_BANDWIDTH: heavy task (matrix-multiply, video-frame-edges)
#     while MOVING - EDGE is disabled when not stationary, so CLOUD is the only
#     remote option and the rule fires reliably under healthy network.
#   - BALANCED_COST: medium/heavy task under healthy conditions where the cost
#     model favours CLOUD over EDGE.
#
# Sessions:
#   CT1 - WALKING + heavy tasks  -> CLOUD via HEAVY_COMPUTE_GOOD_BANDWIDTH
#   CT2 - VEHICLE + heavy tasks  -> CLOUD via HEAVY_COMPUTE_GOOD_BANDWIDTH
#   CT3 - Extra healthy baseline -> mixed LOCAL/EDGE/CLOUD, grows all classes
#
# Target: +40 CLOUD rows (22 -> ~60), +30 mixed rows for overall balance.
# Appends to the existing mocca-metrics.csv on the phone - nothing is replaced.
#
# Usage:
#   cd <repo-root>
#   .\evaluation\collect_data_cloud_topup.ps1 -EdgeCloudHost 192.168.2.13
# =============================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$EdgeCloudHost,
    [int]$EdgePort  = 8001,
    [int]$CloudPort = 8002
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

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}
function Step($msg) { Write-Host "  >> $msg" -ForegroundColor DarkYellow }
function Ok($msg)   { Write-Host "  OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  WARN  $msg" -ForegroundColor Red }

function Broadcast($action, $extras = "") {
    $full = "adb shell am broadcast -n $PKG/.AutoRunReceiver -a $action $extras"
    Invoke-Expression $full | Out-Null
}

function Run-Task($task, $count, $delayMs = $TASK_DELAY_MS) {
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    Step "run $task x$count  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs"
    Start-Sleep -Seconds $waitSec
}

function Assert-ServersHealthy {
    Step "checking edge + cloud server health"
    $edgeOk  = $false
    $cloudOk = $false
    try { $r = curl.exe -s -o /dev/null -w "%{http_code}" "$EDGE_URL/health";  $edgeOk  = ($r -eq "200") } catch {}
    try { $r = curl.exe -s -o /dev/null -w "%{http_code}" "$CLOUD_URL/health"; $cloudOk = ($r -eq "200") } catch {}
    if (-not $edgeOk)  { Warn "Edge  NOT reachable at $EDGE_URL";  exit 1 }
    if (-not $cloudOk) { Warn "Cloud NOT reachable at $CLOUD_URL"; exit 1 }
    Ok "Edge + Cloud healthy"
}

# =============================================================================
# PRE-FLIGHT
# =============================================================================

Section "PRE-FLIGHT"

$device = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" } | Select-Object -First 1
if (-not $device) { Write-Error "No ADB device found."; exit 1 }
Ok "Device: $device"

Assert-ServersHealthy

$startLines = (adb shell wc -l /storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv 2>$null) -replace '\D.*',''
Write-Host "  START total lines on-device: $startLines" -ForegroundColor Gray

Step "pointing app at $EdgeCloudHost"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2

Step "resetting to clean adaptive state"
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2

# =============================================================================
# SESSION CT1 - WALKING + heavy tasks  (target: ~20 CLOUD rows)
# =============================================================================

Section "SESSION CT1 - WALKING + heavy tasks  (target: ~20 CLOUD rows)"
Write-Host "  EDGE is ineligible when moving -> HEAVY_COMPUTE_GOOD_BANDWIDTH fires CLOUD" -ForegroundColor Gray

Assert-ServersHealthy

Step "forcing movement state -> WALKING"
Broadcast $ACTION_DBG "--es movement_state WALKING"
Start-Sleep -Seconds 3

Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS
Run-Task "matrix-multiply"   6

Step "clearing movement override"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session CT1 done"

# =============================================================================
# SESSION CT2 - VEHICLE + heavy tasks  (target: ~15 CLOUD rows)
# =============================================================================

Section "SESSION CT2 - VEHICLE + heavy tasks  (target: ~15 CLOUD rows)"
Write-Host "  Same trigger as CT1 under a different movement label" -ForegroundColor Gray

Assert-ServersHealthy

Step "forcing movement state -> VEHICLE"
Broadcast $ACTION_DBG "--es movement_state VEHICLE"
Start-Sleep -Seconds 3

Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 5 $VIDEO_DELAY_MS
Run-Task "matrix-multiply"   4

Step "clearing movement override"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session CT2 done"

# =============================================================================
# SESSION CT3 - Extra healthy baseline  (target: ~30 mixed rows)
# =============================================================================

Section "SESSION CT3 - Extra healthy baseline  (target: ~30 mixed rows)"
Write-Host "  Stationary, good Wi-Fi, battery full - grows LOCAL, EDGE, BALANCED_COST" -ForegroundColor Gray

Assert-ServersHealthy

Run-Task "echo"              6
Run-Task "sha256"            8
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 5 $VIDEO_DELAY_MS

Ok "Session CT3 done"

# =============================================================================
# CLEANUP + PULL
# =============================================================================

Section "CLEANUP"
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Mode restored to ADAPTIVE, all overrides cleared"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
$endLines = (adb shell wc -l /storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv 2>$null) -replace '\D.*',''
Write-Host "  END total lines on-device: $endLines  (added ~$(([int]$endLines - [int]$startLines)) rows)" -ForegroundColor Gray
Step "adb pull -> $outFile"
adb pull "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv" $outFile
Ok "Saved to $outFile"
Write-Host ""
Write-Host "  Done. Re-run the notebook or regen_policy_figures.py to update all figures." -ForegroundColor Cyan
