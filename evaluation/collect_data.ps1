# =============================================================================
# collect_data.ps1  -  MOCCA Phase 1 automated data collection
#
# Drawback fixes applied vs previous version:
#   FIX-A: Assert-ServersHealthy called before every offloading session
#           (prevents the 52% fallback rate from corrupting the dataset)
#   FIX-B: Session B uses debugRemoteEnergyMj=50 to guarantee energy
#           condition is met for LOW_BATTERY_OFFLOAD (1 row -> target 30+)
#   FIX-C: Session B wait increased to 8s so BatteryCollector registers
#           the simulated discharge state before tasks run
#   FIX-D: Session C counts increased + netem wait increased to 8s so
#           NetworkCollector re-probes before tasks run
#   FIX-E: Session D count increased to 10 per task (OFFLINE: 6 -> 40+)
#   FIX-F: Session E uses matrix-multiply flood instead of screenrecord
#           (more reliable CPU stress, no root required)
#   FIX-G: Task balance improved - video + sha256 counts raised across all
#           sessions to fix matrix-multiply domination (66% -> ~25%)
#   FIX-H: Session H pause message updated for hotspot approach (no ngrok)
#
# Usage:
#   .\evaluation\collect_data.ps1
# =============================================================================

$PKG            = "com.thesis.middleware"
$ACTION_RUN     = "$PKG.RUN_TASK"
$ACTION_MODE    = "$PKG.SET_MODE"
$ACTION_DBG     = "$PKG.SET_DEBUG"
$ACTION_CLR     = "$PKG.CLEAR_DEBUG"

$TASK_DELAY_MS  = 3000
$VIDEO_DELAY_MS = 6000
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

function Warn($msg) {
    Write-Host "  WARN  $msg" -ForegroundColor Red
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

function Set-Netem($delay, $loss) {
    Step "netem -> delay=${delay}ms loss=${loss}%"
    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    docker exec $EDGE_CONTAINER tc qdisc add dev eth0 root netem delay "${delay}ms" loss "${loss}%" | Out-Null
    Start-Sleep -Seconds 8   # FIX-D: increased from 6s so NetworkCollector re-probes
}

function Clear-Netem {
    Step "removing netem"
    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    Start-Sleep -Seconds 3
}

function Pause-ForUser($msg) {
    Write-Host ""
    Write-Host "  [ACTION REQUIRED] $msg" -ForegroundColor Magenta
    Read-Host "  Press Enter when ready"
}

# FIX-A: server health check from laptop side
# Prevents running sessions when servers are unreachable (was root cause of 52% fallback)
function Assert-ServersHealthy {
    Step "checking edge + cloud server health"
    $edgeOk  = $false
    $cloudOk = $false
    try {
        $null = Invoke-WebRequest "http://localhost:8001/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        $edgeOk = $true
    } catch {}
    try {
        $null = Invoke-WebRequest "http://localhost:8002/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        $cloudOk = $true
    } catch {}

    if (-not $edgeOk) { Warn "Edge server NOT reachable at localhost:8001" }
    if (-not $cloudOk) { Warn "Cloud server NOT reachable at localhost:8002" }

    if (-not ($edgeOk -and $cloudOk)) {
        Pause-ForUser "Fix server connectivity (check docker compose up, check app URLs), then press Enter to retry."
        Assert-ServersHealthy
    } else {
        Ok "Edge + Cloud servers healthy"
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

$containers = docker ps --format "{{.Names}}" | Where-Object { $_ -match "edge|cloud" }
if (-not $containers) {
    Write-Error "Edge/cloud containers not running. Run 'docker compose up -d' first."
    exit 1
}
Ok "Containers: $($containers -join ', ')"

Assert-ServersHealthy

Step "launching app so ContextService starts"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

Step "resetting to clean state"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug

# =============================================================================
# SESSION A - Healthy baseline  (~50 rows)
# Expected rules: HEAVY_COMPUTE_GOOD_BANDWIDTH, BALANCED_COST, COMPUTE_FLOOR_NOT_MET
# FIX-G: video + sha256 counts raised to reduce matrix domination
# =============================================================================

Section "SESSION A - Healthy Baseline  (target: ~50 rows)"
Write-Host "  Conditions: battery 75%+, good Wi-Fi, phone idle, ADAPTIVE mode" -ForegroundColor Gray

Assert-ServersHealthy

Run-Task "echo"              8
Run-Task "sha256"            10
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 10 $VIDEO_DELAY_MS

Ok "Session A done"

# =============================================================================
# SESSION B - Battery sweep  (~120 rows)
# Expected rules: LOW_BATTERY_OFFLOAD below 30%, BALANCED_COST otherwise
#
# FIX-B: debugRemoteEnergyMj=50 guarantees remote energy < local energy
#         so LOW_BATTERY_OFFLOAD energy condition is always met.
#         (root cause of 1 row: energy condition was not being satisfied)
# FIX-C: wait increased to 8s so BatteryCollector registers discharge state
# FIX-G: video added per level, sha256 count raised
# =============================================================================

Section "SESSION B - Battery Sweep  (target: ~120 rows)"
Write-Host "  Conditions: forced battery 28->12%, debugRemoteEnergyMj=50 to satisfy energy condition" -ForegroundColor Gray

Assert-ServersHealthy

# Force remote energy to 50mJ - cheaper than any local task execution
# so the LOW_BATTERY_OFFLOAD energy gate is always passed
Set-Debug "--ef remote_energy_mj 50"

foreach ($level in @(28, 25, 22, 18, 15, 12)) {
    Step "battery level -> $level%"
    adb shell dumpsys battery unplug           | Out-Null
    adb shell dumpsys battery set level $level | Out-Null
    adb shell dumpsys battery set status 3     | Out-Null   # 3 = discharging
    Start-Sleep -Seconds 8   # FIX-C: increased from 4s

    Run-Task "echo"              3
    Run-Task "sha256"            5
    Run-Task "image-grayscale"   5
    Run-Task "matrix-multiply"   3
    Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
}

Step "resetting battery + clearing debug"
adb shell dumpsys battery reset | Out-Null
Clear-AllDebug
Start-Sleep -Seconds 2
Ok "Session B done"

# =============================================================================
# SESSION C - Network degradation via docker tc netem  (~96 rows)
# Expected rules: UNSTABLE_NETWORK at high delay/loss, BALANCED_COST at mild
#
# FIX-D: netem wait increased to 8s + counts raised
#         (root cause of 5 rows: NetworkCollector had not re-probed RTT yet)
# FIX-G: echo/sha256/video counts raised for task balance
# =============================================================================

Section "SESSION C - Network Degradation (docker tc netem)  (target: ~96 rows)"
Write-Host "  Conditions: real delay+loss on edge container, all CSV columns consistent" -ForegroundColor Gray

Assert-ServersHealthy

# Step 1: mild lag - BALANCED_COST expected
Set-Netem 100 0
Run-Task "echo"              5
Run-Task "sha256"            5
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
Clear-Netem

# Step 2: near unstable boundary
Set-Netem 300 5
Run-Task "echo"              5
Run-Task "sha256"            5
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
Clear-Netem

# Step 3: unstable - UNSTABLE_NETWORK fires
Set-Netem 500 20
Run-Task "echo"              5
Run-Task "sha256"            5
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
Clear-Netem

# Step 4: severe - guaranteed UNSTABLE_NETWORK
Set-Netem 1000 30
Run-Task "echo"              5
Run-Task "sha256"            5
Run-Task "image-grayscale"   5
Run-Task "matrix-multiply"   5
Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
Clear-Netem

Ok "Session C done"

# =============================================================================
# SESSION D - Offline  (~50 rows)
# Expected rules: OFFLINE for every row
# FIX-E: count increased from 6 to 10 per task (OFFLINE had only 6 rows total)
# =============================================================================

Section "SESSION D - Offline  (target: ~50 rows)"
Write-Host "  Conditions: Wi-Fi disabled via ADB" -ForegroundColor Gray

Step "disabling Wi-Fi"
adb shell svc wifi disable | Out-Null
Start-Sleep -Seconds 4

Run-Task "echo"              10
Run-Task "sha256"            10
Run-Task "image-grayscale"   10
Run-Task "matrix-multiply"   10
Run-Task "video-frame-edges" 10 $VIDEO_DELAY_MS

Step "re-enabling Wi-Fi"
adb shell svc wifi enable | Out-Null
Start-Sleep -Seconds 8   # wait for reconnect
Ok "Session D done"

# =============================================================================
# SESSION E - CPU stress via matrix-multiply flood  (~40 rows)
# Expected rules: LATENCY_SENSITIVE for echo and sha256
#
# FIX-F: replaced screenrecord with matrix-multiply flood
#         screenrecord was not reliably loading CPU on all devices.
#         Firing 30 matrix-multiply tasks at 500ms intervals creates genuine
#         CPU pressure using the app's own computation - no root needed.
#         (root cause of 1 row: screenrecord did not raise cpuLoadScore enough)
# =============================================================================

Section "SESSION E - CPU Stress via task flood  (target: ~40 rows)"
Write-Host "  Conditions: 30 background matrix tasks flood CPU, then light tasks measured" -ForegroundColor Gray
Write-Host "  Expected rules: LATENCY_SENSITIVE for echo and sha256" -ForegroundColor Gray

Assert-ServersHealthy

Step "flooding CPU with background matrix-multiply tasks (30 x 500ms delay)"
Broadcast $ACTION_RUN "--es task matrix-multiply --ei count 30 --el delay_ms 500"
Start-Sleep -Seconds 5   # let CPU load build before measuring

Run-Task "sha256" 20
Run-Task "echo"   20

Step "waiting for background flood to finish"
Start-Sleep -Seconds 20
Ok "Session E done"

# =============================================================================
# SESSION F - LOCAL_ONLY baseline  (~50 rows)
# FIX-G: video + sha256 counts raised
# =============================================================================

Section "SESSION F - LOCAL_ONLY Baseline  (target: ~50 rows)"
Write-Host "  Conditions: execution mode = LOCAL_ONLY, MAPE rule chain bypassed" -ForegroundColor Gray

Set-ExecMode "LOCAL_ONLY"
Run-Task "echo"              8
Run-Task "sha256"            10
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 10 $VIDEO_DELAY_MS
Ok "Session F done"

# =============================================================================
# SESSION G - CLOUD_ONLY baseline  (~50 rows)
# FIX-G: video + sha256 counts raised
# =============================================================================

Section "SESSION G - CLOUD_ONLY Baseline  (target: ~50 rows)"
Write-Host "  Conditions: execution mode = CLOUD_ONLY, no fallback, failures expected" -ForegroundColor Gray

Assert-ServersHealthy

Set-ExecMode "CLOUD_ONLY"
Run-Task "echo"              8
Run-Task "sha256"            10
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 10 $VIDEO_DELAY_MS
Ok "Session G done"

# =============================================================================
# SESSION H - LTE network  (~50 rows)
# FIX-H: pause message updated for phone hotspot approach (no ngrok needed)
#
# Setup steps when paused:
#   1. On phone: Settings -> Hotspot -> enable Mobile Hotspot
#   2. On laptop: disconnect from Wi-Fi, connect to phone hotspot
#   3. On laptop: run ipconfig, find the hotspot adapter IP (192.168.43.x)
#   4. In MOCCA app Settings: update Edge/Cloud URLs to http://192.168.43.x:8001/8002
#   5. Phone uses LTE as upstream -> network_type=LTE in CSV
# =============================================================================

Section "SESSION H - LTE Network via Hotspot  (target: ~50 rows)"
Write-Host "  Conditions: phone hotspot, laptop on mobile data, network_type=LTE" -ForegroundColor Gray

Pause-ForUser "Enable phone hotspot, connect laptop to it, update app server URLs to hotspot IP, then press Enter."

Assert-ServersHealthy

Set-ExecMode "ADAPTIVE"
Run-Task "echo"              8
Run-Task "sha256"            10
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 10 $VIDEO_DELAY_MS

Pause-ForUser "Reconnect laptop to normal Wi-Fi, restore original server URLs in app Settings, then press Enter."
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

$lines     = Get-Content $outFile
$totalRows = $lines.Length - 1

# Count only new-schema rows (21 columns) - old-schema rows lack the rule column
$newSchemaRows = $lines | Select-Object -Skip 1 | Where-Object { ($_ -split ',').Count -ge 21 }
$oldSchemaRows = $totalRows - $newSchemaRows.Count

Write-Host "  Total rows      : $totalRows" -ForegroundColor White
Write-Host "  Usable (21-col) : $($newSchemaRows.Count)  (target >= 400)" -ForegroundColor $(if ($newSchemaRows.Count -ge 400) { "Green" } else { "Red" })
if ($oldSchemaRows -gt 0) {
    Warn "$oldSchemaRows old-schema rows found (no rule column) - filter these out before RF training"
}

Write-Host ""
Write-Host "  Rule distribution (new-schema rows only):" -ForegroundColor White

$dist = $newSchemaRows |
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
    $hint = if ($min) { " (min $min)" } else { " (baseline - filter before training)" }
    Write-Host ("    [{0}]  {1,-35} {2,4}{3}" -f $mark, $g.Name, $g.Count, $hint) -ForegroundColor $clr
}

# Task distribution check
Write-Host ""
Write-Host "  Task distribution (should be roughly balanced):" -ForegroundColor White
$tasks = $newSchemaRows | ForEach-Object { ($_ -split ',')[2].Trim('"') } | Group-Object | Sort-Object Count -Descending
foreach ($t in $tasks) {
    $pct = [math]::Round(100 * $t.Count / [math]::Max($newSchemaRows.Count, 1))
    $clr = if ($pct -gt 40) { "Red" } elseif ($pct -gt 30) { "Yellow" } else { "Green" }
    Write-Host ("    {0,-25} {1,4} ({2}%)" -f $t.Name, $t.Count, $pct) -ForegroundColor $clr
}

# Fallback rate check
Write-Host ""
$fallbacks = ($newSchemaRows | Where-Object { ($_ -split ',')[4].Trim('"').ToLower() -eq "true" }).Count
$fbPct = [math]::Round(100 * $fallbacks / [math]::Max($newSchemaRows.Count, 1))
$fbClr = if ($fbPct -gt 20) { "Red" } elseif ($fbPct -gt 10) { "Yellow" } else { "Green" }
Write-Host ("  Fallback rate: {0}/{1} ({2}%)" -f $fallbacks, $newSchemaRows.Count, $fbPct) -ForegroundColor $fbClr
if ($fbPct -gt 20) {
    Warn "High fallback rate means servers were unreachable during collection - re-run after fixing connectivity"
}

Write-Host ""
$underRep = $dist | Where-Object { $minCounts[$_.Name] -and $_.Count -lt $minCounts[$_.Name] }
$allOk    = ($newSchemaRows.Count -ge 400) -and (-not $underRep) -and ($fbPct -le 20)

if ($allOk) {
    Write-Host "  All checks passed. Proceed to Phase 2 training notebook." -ForegroundColor Cyan
} else {
    Write-Host "  Issues found above. Fix and re-run flagged sessions before training." -ForegroundColor Red
}
