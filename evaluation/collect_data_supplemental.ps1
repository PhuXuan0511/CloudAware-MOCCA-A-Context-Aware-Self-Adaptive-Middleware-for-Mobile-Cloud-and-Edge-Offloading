# =============================================================================
# collect_data_supplemental.ps1  -  top up under-represented rules
#
# Runs after collect_data_remote.ps1 when verify_dataset.ps1 flags specific
# rules as under the minimum count. Appends to the SAME mocca-metrics.csv on
# the phone (MetricsRecorder just keeps appending across broadcasts/sessions),
# so re-pulling afterwards gives the original data plus these rows - nothing
# is replaced or lost.
#
# SESSION L1 - LATENCY_SENSITIVE
#   OffloadingPolicy only reaches this rule for LIGHT tasks (echo/sha256) that
#   already cleared the COMPUTE_FLOOR_NOT_MET guardrail (speedup >= 1.5x). On
#   a real device, echo/sha256 are so cheap locally (~50ms) that a real remote
#   round trip almost never clears that bar, so COMPUTE_FLOOR_NOT_MET absorbs
#   almost everything that would otherwise be LATENCY_SENSITIVE. Forcing
#   debugSpeedup >= 1.5 (same SET_DEBUG mechanism Session B already uses for
#   LOW_BATTERY_OFFLOAD) makes the guardrail pass reliably, so the LIGHT-task
#   check underneath it can actually fire.
#
#   These rows carry a real, genuine EXECUTION and a real POLICY DECISION -
#   only the estimator's remote-latency figure is synthetic. Exactly like
#   Session B's forced rows, they are flagged via debug_overrides so the
#   training notebook already knows to exclude them from cost-model/estimator
#   validation while still using them for RF classification.
#
# SESSION L2 - BALANCED_COST
#   No override needed: a MEDIUM-complexity task (image-grayscale) under
#   healthy network and adequate battery clears every earlier rule (not
#   offline, network fine, floor met, not LIGHT, not low battery, not HEAVY)
#   and falls straight through to BALANCED_COST - exactly what Session A
#   already produces, just more of it.
#
# Usage:
#   .\evaluation\collect_data_supplemental.ps1 -EdgeCloudHost 192.168.2.13
# =============================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$EdgeCloudHost,
    [int]$EdgePort = 8001,
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

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}
function Step($msg) { Write-Host "  >> $msg" -ForegroundColor DarkYellow }
function Ok($msg)   { Write-Host "  OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  WARN  $msg" -ForegroundColor Red }

# Explicit component target - an implicit `-a <action>` broadcast is silently
# dropped by Android 8+ for manifest-registered receivers (confirmed on a real
# Android 14 device: "Broadcast completed" prints regardless of delivery).
function Broadcast($action, $extras = "") {
    $full = "adb shell am broadcast -n $PKG/.AutoRunReceiver -a $action $extras"
    Invoke-Expression $full | Out-Null
}

function Run-Task($task, $count, $delayMs = 3000) {
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    Step "run $task x$count  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs"
    Start-Sleep -Seconds $waitSec
}

function Assert-ServersHealthy {
    Step "checking edge + cloud server health"
    try {
        $null = Invoke-WebRequest "$EDGE_URL/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        $null = Invoke-WebRequest "$CLOUD_URL/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        Ok "Edge + Cloud servers healthy"
    } catch {
        Warn "Server health check failed: $($_.Exception.Message)"
        Write-Error "Fix connectivity and retry."
        exit 1
    }
}

# =============================================================================
# PRE-FLIGHT
# =============================================================================

Section "PRE-FLIGHT"

$device = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "device$" } | Select-Object -First 1
if (-not $device) {
    Write-Error "No ADB device found. Connect the phone and retry."
    exit 1
}
Ok "Device: $device"

Assert-ServersHealthy

Step "pointing the app at $EdgeCloudHost (in case a fresh launch reset it)"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2

Step "resetting to a clean state"
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2

# =============================================================================
# SESSION L1 - LATENCY_SENSITIVE top-up  (target: 30+ new rows)
# =============================================================================

Section "SESSION L1 - LATENCY_SENSITIVE top-up  (target: 30+ rows)"
Write-Host "  Conditions: healthy Wi-Fi, debugSpeedup=3.0 forces the compute-floor" -ForegroundColor Gray
Write-Host "  guardrail to pass so LIGHT tasks reach the latency-sensitive rule" -ForegroundColor Gray

Assert-ServersHealthy

Step "forcing speedup=3.0 (matches Session B's mechanism for LOW_BATTERY_OFFLOAD)"
Broadcast $ACTION_DBG "--ef speedup 3.0"
Start-Sleep -Seconds 2

Run-Task "echo"   20
Run-Task "sha256" 20

Step "clearing debug overrides"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Ok "Session L1 done"

# =============================================================================
# SESSION L2 - BALANCED_COST top-up  (target: 10+ new rows)
# =============================================================================

Section "SESSION L2 - BALANCED_COST top-up  (target: 10+ rows)"
Write-Host "  Conditions: healthy Wi-Fi, no override - MEDIUM task falls through" -ForegroundColor Gray
Write-Host "  every earlier rule naturally, same as Session A" -ForegroundColor Gray

Assert-ServersHealthy

Run-Task "image-grayscale" 15

Ok "Session L2 done"

# =============================================================================
# CLEANUP + PULL
# =============================================================================

Section "CLEANUP"
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Ok "Mode restored to ADAPTIVE, all debug overrides cleared"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
Step "adb pull -> $outFile (original rows + these supplemental rows, appended)"
adb pull "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv" $outFile
Ok "Saved to $outFile"

Write-Host ""
Write-Host "  Supplemental collection complete. Re-run:" -ForegroundColor Cyan
Write-Host "    .\evaluation\verify_dataset.ps1" -ForegroundColor White
