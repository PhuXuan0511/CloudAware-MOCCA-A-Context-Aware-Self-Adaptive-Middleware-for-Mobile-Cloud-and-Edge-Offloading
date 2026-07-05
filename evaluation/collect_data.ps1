# =============================================================================
# collect_data.ps1  —  MOCCA Phase 1 automated data collection
#
# Runs all 7 sessions end-to-end and pulls training.csv off the device.
# Requires: ADB connected, app installed + running, edge/cloud servers up.
#
# Usage:
#   .\evaluation\collect_data.ps1
# =============================================================================

$PKG          = "com.thesis.middleware"
$ACTION_RUN   = "$PKG.RUN_TASK"
$ACTION_MODE  = "$PKG.SET_MODE"
$ACTION_DBG   = "$PKG.SET_DEBUG"
$ACTION_CLR   = "$PKG.CLEAR_DEBUG"

$TASK_DELAY_MS = 3000   # ms between consecutive task runs (most tasks)
$VIDEO_DELAY_MS = 6000  # video tasks are heavier

# =============================================================================
# HELPERS
# =============================================================================

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}

function Step($msg) {
    Write-Host "  >> $msg" -ForegroundColor DarkYellow
}

function Ok($msg) {
    Write-Host "  OK  $msg" -ForegroundColor Green
}

function Broadcast($action, $extras = "") {
    $full = "adb shell am broadcast -a $action $extras"
    Invoke-Expression $full | Out-Null
}

function Run-Task($task, $count, $delayMs = $TASK_DELAY_MS) {
    # Wait long enough for all $count tasks to finish before returning
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    Step "run $task x$count  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs"
    Start-Sleep -Seconds $waitSec
}

function Set-ExecMode($mode) {
    Step "execution mode → $mode"
    Broadcast $ACTION_MODE "--es mode $mode"
    Start-Sleep -Seconds 1
}

function Set-Debug($extras) {
    Step "debug override: $extras"
    Broadcast $ACTION_DBG $extras
    Start-Sleep -Seconds 1
}

function Clear-AllDebug {
    Step "clearing all debug overrides"
    Broadcast $ACTION_CLR
    Start-Sleep -Seconds 1
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

Step "launching app so ContextService starts"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

Step "resetting to clean state (ADAPTIVE, no debug overrides)"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug

# =============================================================================
# SESSION A — Healthy baseline
# Expected rules: HEAVY_COMPUTE_GOOD_BANDWIDTH, BALANCED_COST, COMPUTE_FLOOR_NOT_MET
# =============================================================================

Section "SESSION A — Healthy Baseline  (target: 40 rows)"
Write-Host "  Conditions: battery >75%, good Wi-Fi, phone idle, ADAPTIVE mode" -ForegroundColor Gray

Run-Task "echo"              8
Run-Task "sha256"            8
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 8 $VIDEO_DELAY_MS

Ok "Session A done"

# =============================================================================
# SESSION B — Battery sweep
# Expected rules: LOW_BATTERY_OFFLOAD below 30%, BALANCED_COST otherwise
# =============================================================================

Section "SESSION B — Battery Sweep  (target: 60 rows)"
Write-Host "  Conditions: forced battery levels 28→12% via ADB" -ForegroundColor Gray

foreach ($level in @(28, 25, 22, 18, 15, 12)) {
    Step "battery level → $level%"
    adb shell dumpsys battery unplug      | Out-Null
    adb shell dumpsys battery set level $level | Out-Null
    adb shell dumpsys battery set status 3 | Out-Null   # 3 = discharging
    Start-Sleep -Seconds 4   # wait for BatteryCollector to tick

    Run-Task "image-grayscale"  5
    Run-Task "matrix-multiply"  5
}

Step "resetting battery to real level"
adb shell dumpsys battery reset | Out-Null
Start-Sleep -Seconds 2
Ok "Session B done"

# =============================================================================
# SESSION C — Network degradation via debugNetworkScore
# Expected rules: UNSTABLE_NETWORK at score <0.3, BALANCED_COST at 0.3-0.6
# =============================================================================

Section "SESSION C — Network Degradation via Debug  (target: 60 rows)"
Write-Host "  Conditions: debugNetworkScore overrides real network score" -ForegroundColor Gray

# Mild: 0.45 — above unstable (0.30) but below good-bandwidth (0.60)
Step "network score 0.45 — mild, no special rule expected"
Set-Debug "--ef network_score 0.45"
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS

# Near boundary: 0.28 — just below unstable threshold
Step "network score 0.28 — near UNSTABLE_NETWORK boundary"
Set-Debug "--ef network_score 0.28"
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS

# Clearly unstable: 0.10
Step "network score 0.10 — UNSTABLE_NETWORK fires"
Set-Debug "--ef network_score 0.10"
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS

# Very unstable: 0.05
Step "network score 0.05 — guaranteed UNSTABLE_NETWORK"
Set-Debug "--ef network_score 0.05"
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS

Clear-AllDebug
Ok "Session C done"

# =============================================================================
# SESSION D — Offline
# Expected rules: OFFLINE for every row
# =============================================================================

Section "SESSION D — Offline  (target: 20 rows)"
Write-Host "  Conditions: Wi-Fi disabled via ADB" -ForegroundColor Gray

Step "disabling Wi-Fi"
adb shell svc wifi disable | Out-Null
Start-Sleep -Seconds 3

Run-Task "echo"              4
Run-Task "sha256"            4
Run-Task "image-grayscale"   4
Run-Task "matrix-multiply"   4
Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS

Step "re-enabling Wi-Fi"
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 6   # wait for reconnect
Ok "Session D done"

# =============================================================================
# SESSION E — CPU stress simulation via debugSpeedup
# Expected rules: LATENCY_SENSITIVE for LIGHT tasks (echo, sha256)
#
# Why: LATENCY_SENSITIVE sits behind COMPUTE_FLOOR_NOT_MET in the rule chain.
# COMPUTE_FLOOR fires when speedup < 1.5x, blocking LATENCY_SENSITIVE.
# Setting debugSpeedup=3.0 forces speedup above the 1.5x floor so
# COMPUTE_FLOOR is skipped and LATENCY_SENSITIVE fires for LIGHT tasks.
# =============================================================================

Section "SESSION E — CPU Stress (debugSpeedup=3.0)  (target: 20 rows)"
Write-Host "  Conditions: debugSpeedup=3.0, simulates high-CPU device where local is slower" -ForegroundColor Gray
Write-Host "  Expected rules: LATENCY_SENSITIVE for echo and sha256" -ForegroundColor Gray

Set-Debug "--ef speedup 3.0"
Run-Task "sha256" 10
Run-Task "echo"   10
Clear-AllDebug
Ok "Session E done"

# =============================================================================
# SESSION F — LOCAL_ONLY baseline
# Expected rules: FORCED_LOCAL for every row
# =============================================================================

Section "SESSION F — LOCAL_ONLY Baseline  (target: 30 rows)"
Write-Host "  Conditions: execution mode = LOCAL_ONLY, MAPE rule chain bypassed" -ForegroundColor Gray

Set-ExecMode "LOCAL_ONLY"
Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS
Ok "Session F done"

# =============================================================================
# SESSION G — CLOUD_ONLY baseline
# Expected rules: FORCED_CLOUD for every row (some rows will have errors — intentional)
# =============================================================================

Section "SESSION G — CLOUD_ONLY Baseline  (target: 30 rows)"
Write-Host "  Conditions: execution mode = CLOUD_ONLY, no fallback — failures expected" -ForegroundColor Gray

Set-ExecMode "CLOUD_ONLY"
Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS
Ok "Session G done"

# =============================================================================
# RESTORE + PULL
# =============================================================================

Section "CLEANUP"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug
Ok "Mode restored to ADAPTIVE, all debug overrides cleared"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
Step "adb pull → $outFile"
adb shell "run-as $PKG cat files/mocca-metrics.csv" > $outFile
Ok "Saved to $outFile"

# =============================================================================
# VERIFICATION
# =============================================================================

Section "VERIFICATION"

$lines = Get-Content $outFile
$rows  = $lines.Length - 1   # subtract header
$color = if ($rows -ge 300) { "Green" } else { "Red" }
Write-Host "  Total rows: $rows  (target >= 300)" -ForegroundColor $color

Write-Host ""
Write-Host "  Rule distribution:" -ForegroundColor White
$dist = $lines | Select-Object -Skip 1 |
    ForEach-Object { ($_ -split ',')[8].Trim('"') } |
    Group-Object | Sort-Object Count -Descending

$minCounts = @{
    "OFFLINE"                      = 20
    "UNSTABLE_NETWORK"             = 30
    "COMPUTE_FLOOR_NOT_MET"        = 40
    "LATENCY_SENSITIVE"            = 20
    "LOW_BATTERY_OFFLOAD"          = 30
    "HEAVY_COMPUTE_GOOD_BANDWIDTH" = 50
    "BALANCED_COST"                = 50
}

foreach ($g in $dist) {
    $min   = $minCounts[$g.Name]
    $ok    = (-not $min) -or ($g.Count -ge $min)
    $mark  = if ($ok) { "OK " } else { "LOW" }
    $clr   = if ($ok) { "Green" } else { "Red" }
    $hint  = if ($min) { " (min $min)" } else { " (baseline)" }
    Write-Host ("    [{0}]  {1,-35} {2,4}{3}" -f $mark, $g.Name, $g.Count, $hint) -ForegroundColor $clr
}

Write-Host ""
$allOk = ($rows -ge 300) -and (
    $dist | Where-Object { $minCounts[$_.Name] -and $_.Count -lt $minCounts[$_.Name] }
) -eq $null

if ($allOk) {
    Write-Host "  All checks passed. Proceed to Phase 2 training notebook." -ForegroundColor Cyan
} else {
    Write-Host "  Some rules are under-represented. Re-run the flagged sessions." -ForegroundColor Red
}
