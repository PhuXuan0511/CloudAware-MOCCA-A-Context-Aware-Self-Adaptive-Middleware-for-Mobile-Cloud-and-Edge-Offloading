# =============================================================================
# collect_data_expand.ps1  -  broad multi-scenario dataset expansion
#
# Adds variety across the conditions that can be driven end-to-end from this
# machine (no manual netem on the Docker host required). Grows the adaptive row
# pool, which automatically expands BOTH the 75% train and 25% test halves that
# random-forest-training.ipynb derives from it.
#
# Sessions (all append to the same on-device mocca-metrics.csv - nothing lost):
#   X1  Battery sweep         28/16/9%   -> LOW_BATTERY_OFFLOAD + battery variety
#   X2  Mobility sweep        STA/WALK/VEH -> CLOUD via HEAVY_COMPUTE_GOOD_BANDWIDTH
#   X3  CPU stress            background flood -> LATENCY_SENSITIVE + cpu variety
#   X4  Payload size sweep    varied bytes -> transmission-cost variety
#   X5  Offline               Wi-Fi off -> OFFLINE rows (adb stays up over USB)
#   X6  Latency-sensitive     debugSpeedup=3.0 -> LATENCY_SENSITIVE top-up
#   X7  Extra healthy         stationary/good net -> LOCAL/EDGE/BALANCED_COST
#
# NOT covered: UNSTABLE_NETWORK (needs netem on the Docker host - use
# collect_data_remote.ps1 Session C with an operator on that machine). It
# already has 113 rows.
#
# Rows flagged with debug_overrides (X1 remote_energy, X2 movement, X6 speedup)
# are excluded from cost-model validation by the notebook but still used for RF
# classification - the established pattern from Sessions B/I/L.
#
# Usage:
#   cd <repo-root>
#   .\evaluation\collect_data_expand.ps1 -EdgeCloudHost 192.168.2.13
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
$CSV_PATH = "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv"

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

function Run-Task($task, $count, $delayMs = $TASK_DELAY_MS, $size = 0) {
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    $sizeArg = ""
    $label   = "$task x$count"
    if ($size -gt 0) { $sizeArg = "--ei size $size"; $label = "$task x$count (size=$size)" }
    Step "run $label  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs $sizeArg"
    Start-Sleep -Seconds $waitSec
}

function Assert-ServersHealthy {
    Step "checking edge + cloud server health"
    $edgeOk = $false; $cloudOk = $false
    try { $edgeOk  = ((curl.exe -s -o /dev/null -w "%{http_code}" "$EDGE_URL/health")  -eq "200") } catch {}
    try { $cloudOk = ((curl.exe -s -o /dev/null -w "%{http_code}" "$CLOUD_URL/health") -eq "200") } catch {}
    if (-not $edgeOk)  { Warn "Edge  NOT reachable at $EDGE_URL";  exit 1 }
    if (-not $cloudOk) { Warn "Cloud NOT reachable at $CLOUD_URL"; exit 1 }
    Ok "Edge + Cloud healthy"
}

function Get-LineCount {
    $raw = adb shell wc -l $CSV_PATH 2>$null
    return [int](($raw -replace '\D.*',''))
}

# =============================================================================
# PRE-FLIGHT
# =============================================================================

Section "PRE-FLIGHT"

$device = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" } | Select-Object -First 1
if (-not $device) { Write-Error "No ADB device found."; exit 1 }
Ok "Device: $device"

Assert-ServersHealthy

$startLines = Get-LineCount
Write-Host "  START total lines on-device: $startLines" -ForegroundColor Gray

Step "pointing app at $EdgeCloudHost"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2

# =============================================================================
# SESSION X1 - Battery sweep  (LOW_BATTERY_OFFLOAD + battery-feature variety)
# =============================================================================

Section "SESSION X1 - Battery sweep (28/16/9%)"
Write-Host "  remote_energy_mj=50 forces the energy condition so LOW_BATTERY_OFFLOAD fires" -ForegroundColor Gray

Assert-ServersHealthy
Broadcast $ACTION_DBG "--ef remote_energy_mj 50"
Start-Sleep -Seconds 2

foreach ($level in @(28, 16, 9)) {
    Step "battery level -> $level%"
    adb shell dumpsys battery unplug           | Out-Null
    adb shell dumpsys battery set level $level  | Out-Null
    adb shell dumpsys battery set status 3      | Out-Null   # discharging
    Start-Sleep -Seconds 6
    Run-Task "echo"              3
    Run-Task "sha256"            4
    Run-Task "image-grayscale"   4
    Run-Task "matrix-multiply"   3
    Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
}

Step "resetting battery + clearing overrides"
adb shell dumpsys battery reset | Out-Null
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session X1 done"

# =============================================================================
# SESSION X2 - Mobility sweep  (CLOUD + mobility variety)
# =============================================================================

Section "SESSION X2 - Mobility sweep (STATIONARY/WALKING/VEHICLE)"
Write-Host "  EDGE ineligible when moving -> heavy tasks route CLOUD" -ForegroundColor Gray

Assert-ServersHealthy
foreach ($state in @("STATIONARY", "WALKING", "VEHICLE")) {
    Step "movement state -> $state"
    Broadcast $ACTION_DBG "--es movement_state $state"
    Start-Sleep -Seconds 3
    Run-Task "matrix-multiply"   4
    Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
}
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session X2 done"

# =============================================================================
# SESSION X3 - CPU stress  (LATENCY_SENSITIVE + cpu variety)
# =============================================================================

Section "SESSION X3 - CPU stress via task flood"
Write-Host "  30 background matrix tasks load CPU, then light tasks measured" -ForegroundColor Gray

Assert-ServersHealthy
Step "flooding CPU with background matrix-multiply (30 x 500ms)"
Broadcast $ACTION_RUN "--es task matrix-multiply --ei count 30 --el delay_ms 500"
Start-Sleep -Seconds 5
Run-Task "sha256" 8
Run-Task "echo"   8
Step "waiting for background flood to drain"
Start-Sleep -Seconds 20
Ok "Session X3 done"

# =============================================================================
# SESSION X4 - Payload size sweep  (transmission-cost variety)
# =============================================================================

Section "SESSION X4 - Payload size sweep"
Write-Host "  same task type at varied payload sizes isolates transmission term" -ForegroundColor Gray

Assert-ServersHealthy
foreach ($bytes in @(1024, 16384, 262144, 1048576)) { Run-Task "sha256" 4 $TASK_DELAY_MS $bytes }
foreach ($side  in @(128, 256, 512))                { Run-Task "image-grayscale" 3 $TASK_DELAY_MS $side }
Ok "Session X4 done"

# =============================================================================
# SESSION X5 - Offline  (OFFLINE rows)
# =============================================================================

Section "SESSION X5 - Offline (Wi-Fi off)"
Write-Host "  adb stays up over USB; tasks run LOCAL under OFFLINE rule" -ForegroundColor Gray

Step "disabling Wi-Fi"
adb shell svc wifi disable | Out-Null
Start-Sleep -Seconds 5
Run-Task "echo"              5
Run-Task "sha256"            5
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
Step "re-enabling Wi-Fi"
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 10
Step "re-pointing app at endpoints after network bounce"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2
Ok "Session X5 done"

# =============================================================================
# SESSION X6 - Latency-sensitive top-up  (LATENCY_SENSITIVE)
# =============================================================================

Section "SESSION X6 - Latency-sensitive (debugSpeedup=3.0)"
Write-Host "  forces the 1.5x compute floor to pass so LIGHT tasks reach the rule" -ForegroundColor Gray

Assert-ServersHealthy
Broadcast $ACTION_DBG "--ef speedup 3.0"
Start-Sleep -Seconds 2
Run-Task "echo"   8
Run-Task "sha256" 8
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session X6 done"

# =============================================================================
# SESSION X7 - Extra healthy baseline  (LOCAL/EDGE/BALANCED_COST)
# =============================================================================

Section "SESSION X7 - Extra healthy baseline"
Write-Host "  stationary, good Wi-Fi, battery full - mixed tier outcomes" -ForegroundColor Gray

Assert-ServersHealthy
Run-Task "echo"              4
Run-Task "sha256"            5
Run-Task "image-grayscale"   4
Run-Task "matrix-multiply"   4
Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
Ok "Session X7 done"

# =============================================================================
# CLEANUP + PULL
# =============================================================================

Section "CLEANUP"
adb shell dumpsys battery reset | Out-Null
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Battery reset, mode ADAPTIVE, overrides cleared"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
$endLines = Get-LineCount
Write-Host "  END total lines on-device: $endLines  (added ~$($endLines - $startLines) rows)" -ForegroundColor Gray
Step "adb pull -> $outFile"
adb pull $CSV_PATH $outFile
Ok "Saved to $outFile"
Write-Host ""
Write-Host "  Done. Re-run make_evaluation_figures.py + regen_policy_figures.py," -ForegroundColor Cyan
Write-Host "  and the notebook, to refresh all outputs." -ForegroundColor Cyan
