# =============================================================================
# collect_data.ps1  -  MOCCA Phase 1 automated data collection
#
# Runs all 8 sessions end-to-end and pulls training.csv off the device.
# Requires: ADB connected, app installed + running, edge/cloud servers up.
#
# Fixes applied:
#   Fix 1 - Session C uses real docker tc netem (not debugNetworkScore)
#   Fix 2 - Session E uses real screenrecord CPU stress (not debugSpeedup)
#   Fix 3 - Session H added for LTE network data (requires ngrok setup)
#   Fix 4 - echo + sha256 added to Sessions B and C for task-rule diversity
#   Fix 5 - Session D and E counts increased for borderline rules
#
# Usage:
#   .\evaluation\collect_data.ps1
# =============================================================================

$PKG            = "com.thesis.middleware"
$ACTION_RUN     = "$PKG.RUN_TASK"
$ACTION_MODE    = "$PKG.SET_MODE"
$ACTION_CLR     = "$PKG.CLEAR_DEBUG"

$TASK_DELAY_MS  = 3000   # ms between consecutive task runs
$VIDEO_DELAY_MS = 6000   # video tasks are heavier
$EDGE_CONTAINER = "docker-edge-server-1"

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
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    Step "run $task x$count  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs"
    Start-Sleep -Seconds $waitSec
}

function Set-ExecMode($mode) {
    Step "execution mode -> $mode"
    Broadcast $ACTION_MODE "--es mode $mode"
    Start-Sleep -Seconds 1
}

function Clear-AllDebug {
    Step "clearing all debug overrides"
    Broadcast $ACTION_CLR
    Start-Sleep -Seconds 1
}

function Set-Netem($delay, $loss) {
    Step "network condition -> delay=${delay}ms loss=${loss}%"
    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    docker exec $EDGE_CONTAINER tc qdisc add dev eth0 root netem delay "${delay}ms" loss "${loss}%" | Out-Null
    Start-Sleep -Seconds 6   # wait for NetworkCollector to re-probe RTT
}

function Clear-Netem {
    Step "removing network condition"
    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    Start-Sleep -Seconds 2
}

function Pause-ForUser($msg) {
    Write-Host ""
    Write-Host "  [ACTION REQUIRED] $msg" -ForegroundColor Magenta
    Read-Host "  Press Enter when ready"
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

$containers = docker ps --format "{{.Names}}" | Where-Object { $_ -match "edge|cloud" }
if (-not $containers) {
    Write-Error "Edge/cloud containers not running. Run 'docker compose up -d' first."
    exit 1
}
Ok "Containers: $($containers -join ', ')"

Step "launching app so ContextService starts"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

Step "resetting to clean state"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug

# =============================================================================
# SESSION A - Healthy baseline  (~40 rows)
# Expected rules: HEAVY_COMPUTE_GOOD_BANDWIDTH, BALANCED_COST, COMPUTE_FLOOR_NOT_MET
# =============================================================================

Section "SESSION A - Healthy Baseline  (target: ~40 rows)"
Write-Host "  Conditions: battery 75%+, good Wi-Fi, phone idle, ADAPTIVE mode" -ForegroundColor Gray

Run-Task "echo"              8
Run-Task "sha256"            8
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 8 $VIDEO_DELAY_MS

Ok "Session A done"

# =============================================================================
# SESSION B - Battery sweep  (~96 rows)
# Expected rules: LOW_BATTERY_OFFLOAD below 30%, BALANCED_COST otherwise
# Fix 4: echo + sha256 added so LOW_BATTERY fires for LIGHT tasks too
# =============================================================================

Section "SESSION B - Battery Sweep  (target: ~96 rows)"
Write-Host "  Conditions: forced battery levels 28->12% via ADB" -ForegroundColor Gray

foreach ($level in @(28, 25, 22, 18, 15, 12)) {
    Step "battery level -> $level%"
    adb shell dumpsys battery unplug           | Out-Null
    adb shell dumpsys battery set level $level | Out-Null
    adb shell dumpsys battery set status 3     | Out-Null
    Start-Sleep -Seconds 4

    Run-Task "echo"            3
    Run-Task "sha256"          3
    Run-Task "image-grayscale" 5
    Run-Task "matrix-multiply" 5
}

Step "resetting battery to real level"
adb shell dumpsys battery reset | Out-Null
Start-Sleep -Seconds 2
Ok "Session B done"

# =============================================================================
# SESSION C - Real network degradation via docker tc netem  (~72 rows)
# Expected rules: UNSTABLE_NETWORK at high delay/loss, BALANCED_COST at mild
# Fix 1: real RTT/bandwidth changes so all CSV columns stay consistent
# Fix 4: echo + sha256 added so UNSTABLE_NETWORK fires for LIGHT tasks too
# =============================================================================

Section "SESSION C - Network Degradation (docker tc netem)  (target: ~72 rows)"
Write-Host "  Conditions: real delay+loss injected on edge container" -ForegroundColor Gray

# Mild: above all thresholds, BALANCED_COST expected
Set-Netem 100 0
Run-Task "echo"              3
Run-Task "sha256"            3
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
Clear-Netem

# Near unstable boundary
Set-Netem 300 5
Run-Task "echo"              3
Run-Task "sha256"            3
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
Clear-Netem

# Unstable: UNSTABLE_NETWORK fires
Set-Netem 500 20
Run-Task "echo"              3
Run-Task "sha256"            3
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
Clear-Netem

# Severe: guaranteed UNSTABLE_NETWORK
Set-Netem 1000 30
Run-Task "echo"              3
Run-Task "sha256"            3
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 2 $VIDEO_DELAY_MS
Clear-Netem

Ok "Session C done"

# =============================================================================
# SESSION D - Offline  (~30 rows)
# Expected rules: OFFLINE for every row
# Fix 5: count increased from 4 to 6 per task
# =============================================================================

Section "SESSION D - Offline  (target: ~30 rows)"
Write-Host "  Conditions: Wi-Fi disabled via ADB" -ForegroundColor Gray

Step "disabling Wi-Fi"
adb shell svc wifi disable | Out-Null
Start-Sleep -Seconds 3

Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS

Step "re-enabling Wi-Fi"
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 6
Ok "Session D done"

# =============================================================================
# SESSION E - Real CPU stress via screenrecord  (~30 rows)
# Expected rules: LATENCY_SENSITIVE for echo and sha256
# Fix 2: screenrecord drives real CPU load so cpuLoadScore drops organically
# Fix 5: count increased from 10 to 15 per task
# =============================================================================

Section "SESSION E - CPU Stress via screenrecord  (target: ~30 rows)"
Write-Host "  Conditions: screenrecord runs in background to load CPU" -ForegroundColor Gray
Write-Host "  Expected rules: LATENCY_SENSITIVE for echo and sha256" -ForegroundColor Gray

Step "starting background CPU load (screenrecord 120s)"
adb shell "nohup screenrecord --time-limit 120 /sdcard/mocca_stress.mp4 > /dev/null 2>&1 &" | Out-Null
Start-Sleep -Seconds 5   # let CPU load build

Run-Task "sha256" 15
Run-Task "echo"   15

Step "stopping CPU stress"
adb shell "pkill -f screenrecord" | Out-Null
adb shell "rm -f /sdcard/mocca_stress.mp4" | Out-Null
Start-Sleep -Seconds 2
Ok "Session E done"

# =============================================================================
# SESSION F - LOCAL_ONLY baseline  (~30 rows)
# Expected rules: FORCED_LOCAL for every row
# =============================================================================

Section "SESSION F - LOCAL_ONLY Baseline  (target: ~30 rows)"
Write-Host "  Conditions: execution mode = LOCAL_ONLY, MAPE rule chain bypassed" -ForegroundColor Gray

Set-ExecMode "LOCAL_ONLY"
Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS
Ok "Session F done"

# =============================================================================
# SESSION G - CLOUD_ONLY baseline  (~30 rows)
# Expected rules: FORCED_CLOUD for every row (failures expected, intentional)
# =============================================================================

Section "SESSION G - CLOUD_ONLY Baseline  (target: ~30 rows)"
Write-Host "  Conditions: execution mode = CLOUD_ONLY, no fallback, failures expected" -ForegroundColor Gray

Set-ExecMode "CLOUD_ONLY"
Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS
Ok "Session G done"

# =============================================================================
# SESSION H - LTE network  (~30 rows)
# Expected rules: varied - real mobile network RTT affects all decisions
# Fix 3: adds network_type=LTE rows so RF sees mobile network behaviour
#
# Setup required before this session:
#   1. Run: ngrok http 8001  (note the https URL)
#   2. Run: ngrok http 8002  (note the https URL)
#   3. App -> Settings -> update Edge and Cloud URLs to the ngrok URLs
#   4. Turn off Wi-Fi on the phone (uses mobile data automatically)
# =============================================================================

Section "SESSION H - LTE Network  (target: ~30 rows)"
Write-Host "  Conditions: Wi-Fi off, mobile data on, servers via ngrok" -ForegroundColor Gray

Pause-ForUser "Start ngrok tunnels and update server URLs in the app Settings, then disable Wi-Fi on the phone."

Set-ExecMode "ADAPTIVE"
Run-Task "echo"              6
Run-Task "sha256"            6
Run-Task "image-grayscale"   6
Run-Task "matrix-multiply"   6
Run-Task "video-frame-edges" 6 $VIDEO_DELAY_MS

Pause-ForUser "Re-enable Wi-Fi on the phone and restore the local server URLs in app Settings."
Ok "Session H done"

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
Step "adb pull -> $outFile"
adb shell "run-as $PKG cat files/mocca-metrics.csv" > $outFile
Ok "Saved to $outFile"

# =============================================================================
# VERIFICATION
# =============================================================================

Section "VERIFICATION"

$lines = Get-Content $outFile
$rows  = $lines.Length - 1
$color = if ($rows -ge 350) { "Green" } else { "Red" }
Write-Host "  Total rows: $rows  (target >= 350)" -ForegroundColor $color

Write-Host ""
Write-Host "  Rule distribution:" -ForegroundColor White
$dist = $lines | Select-Object -Skip 1 |
    ForEach-Object { ($_ -split ',')[8].Trim('"') } |
    Group-Object | Sort-Object Count -Descending

$minCounts = @{
    "OFFLINE"                      = 30
    "UNSTABLE_NETWORK"             = 30
    "COMPUTE_FLOOR_NOT_MET"        = 40
    "LATENCY_SENSITIVE"            = 30
    "LOW_BATTERY_OFFLOAD"          = 30
    "HEAVY_COMPUTE_GOOD_BANDWIDTH" = 50
    "BALANCED_COST"                = 50
}

foreach ($g in $dist) {
    $min  = $minCounts[$g.Name]
    $ok   = (-not $min) -or ($g.Count -ge $min)
    $mark = if ($ok) { "OK " } else { "LOW" }
    $clr  = if ($ok) { "Green" } else { "Red" }
    $hint = if ($min) { " (min $min)" } else { " (baseline)" }
    Write-Host ("    [{0}]  {1,-35} {2,4}{3}" -f $mark, $g.Name, $g.Count, $hint) -ForegroundColor $clr
}

Write-Host ""
$underRep = $dist | Where-Object { $minCounts[$_.Name] -and $_.Count -lt $minCounts[$_.Name] }
$allOk    = ($rows -ge 350) -and (-not $underRep)

if ($allOk) {
    Write-Host "  All checks passed. Proceed to Phase 2 training notebook." -ForegroundColor Cyan
} else {
    Write-Host "  Some rules are under-represented. Re-run the flagged sessions." -ForegroundColor Red
}
