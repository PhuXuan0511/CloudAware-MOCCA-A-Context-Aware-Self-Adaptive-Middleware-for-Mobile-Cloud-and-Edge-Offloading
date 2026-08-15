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
# Discovered in pre-flight via the compose service label rather than hardcoded:
# the generated name depends on the compose project and on the v1/v2 separator
# ("docker_edge-server_1" vs "docker-edge-server-1").
$EDGE_CONTAINER = $null

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

# Resolve the edge container by its compose service label, which is stable across
# compose v1/v2 naming and any project name.
function Resolve-EdgeContainer {
    $name = docker ps --filter "label=com.docker.compose.service=edge-server" `
                      --format "{{.Names}}" | Select-Object -First 1
    if (-not $name) {
        # Fall back to a name match for containers started outside compose.
        $name = docker ps --format "{{.Names}}" |
                Where-Object { $_ -match "edge" } | Select-Object -First 1
    }
    return $name
}

# Verify tc is usable BEFORE any session depends on it.
#
# Two separate failure modes, both previously silent because the tc output was
# piped to Out-Null and the exit code never checked:
#   1. `tc` is absent      - python:3.11-slim ships no iproute2
#   2. "Operation not permitted" - the container lacks NET_ADMIN
# Either one means Session C records normal-network rows while claiming to have
# injected 500ms/20% loss, and UNSTABLE_NETWORK barely fires.
function Assert-NetemUsable {
    Step "verifying tc/netem works inside $EDGE_CONTAINER"

    $probe = docker exec $EDGE_CONTAINER tc qdisc show dev eth0 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $probe -match "not found|executable file") {
        Warn "tc is not available in the edge container."
        Warn "The image needs iproute2 - rebuild with:"
        Warn "  docker compose -f docker/docker-compose.yml up -d --build"
        return $false
    }

    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    $add = docker exec $EDGE_CONTAINER tc qdisc add dev eth0 root netem delay 1ms 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $add -match "not permitted|Error") {
        Warn "tc exists but cannot modify qdiscs: $($add.Trim())"
        Warn "The container needs NET_ADMIN. docker-compose.yml grants it via cap_add;"
        Warn "recreate the containers so the capability takes effect:"
        Warn "  docker compose -f docker/docker-compose.yml up -d --force-recreate"
        return $false
    }

    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    Ok "tc/netem works - Session C will inject real network degradation"
    return $true
}

function Set-Netem($delay, $loss) {
    Step "netem -> delay=${delay}ms loss=${loss}%"
    $null = docker exec $EDGE_CONTAINER tc qdisc del dev eth0 root 2>&1
    $out = docker exec $EDGE_CONTAINER tc qdisc add dev eth0 root netem `
               delay "${delay}ms" loss "${loss}%" 2>&1 | Out-String

    # Confirm the qdisc is actually in place rather than trusting the exit code.
    $shown = docker exec $EDGE_CONTAINER tc qdisc show dev eth0 2>&1 | Out-String
    if ($shown -notmatch "netem") {
        Warn "netem did NOT apply: $($out.Trim())"
        Warn "These rows would be labelled as degraded but collected on a healthy link."
        Pause-ForUser "Fix netem (see Assert-NetemUsable output above) then press Enter, or Ctrl+C to abort."
    } else {
        Write-Host "    active: $($shown.Trim())" -ForegroundColor DarkGray
    }
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
    Write-Error "Edge/cloud containers not running. Run 'docker compose -f docker/docker-compose.yml up -d --build' first."
    exit 1
}
Ok "Containers: $($containers -join ', ')"

$EDGE_CONTAINER = Resolve-EdgeContainer
if (-not $EDGE_CONTAINER) {
    Write-Error "Could not identify the edge container. Is docker compose up?"
    exit 1
}
Ok "Edge container: $EDGE_CONTAINER"

Assert-ServersHealthy

# Session C is worthless if netem cannot be applied, so check now rather than
# discovering it 20 minutes in.
$netemOk = Assert-NetemUsable
if (-not $netemOk) {
    Warn "Session C would collect normal-network rows labelled as degraded."
    Pause-ForUser "Rebuild/recreate the containers as described above, then press Enter to re-check."
    $netemOk = Assert-NetemUsable
    if (-not $netemOk) {
        Pause-ForUser "netem still unavailable. Press Enter to continue anyway (Session C data will NOT be degraded), or Ctrl+C to abort."
    }
}

# The edge forwards to the cloud when it considers itself overloaded. Since the
# monitor became cgroup-aware this reflects the container's own budget, but a
# genuinely loaded edge still forwards - and that would silently turn every EDGE
# decision into a cloud run. Check before committing to a 45-minute session.
Step "checking edge is not already overloaded"
try {
    $edgeStatus = Invoke-RestMethod "http://localhost:8001/api/v1/status" -TimeoutSec 5
    Write-Host ("    cpu {0}% / mem {1}%   (source: cpu={2}, memory={3})" -f `
        $edgeStatus.cpu_percent, $edgeStatus.memory_used_percent, `
        $edgeStatus.metrics_source.cpu, $edgeStatus.metrics_source.memory) -ForegroundColor Gray
    if ($edgeStatus.metrics_source.memory -eq "psutil") {
        Warn "Edge has no memory cgroup limit - it is reading HOST memory."
        Warn "Set a limit in docker/docker-compose.yml (mem_limit: 2g) or raise"
        Warn "MOCCA_OVERLOAD_MEM_PERCENT, or EDGE rows will really be cloud runs."
    }
    if ($edgeStatus.overloaded) {
        Warn "Edge reports OVERLOADED - it will forward every task to the cloud."
        Pause-ForUser "Free resources (or raise MOCCA_OVERLOAD_MEM_PERCENT), then press Enter."
    } else {
        Ok "Edge has headroom - EDGE decisions will execute on the edge"
    }
} catch {
    Warn "Could not read edge status: $($_.Exception.Message)"
}

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

# Parse with ConvertFrom-Csv rather than splitting on commas: `reasoning` is free
# text that is quoted precisely because it contains commas, and a naive split
# shifts every column after it.
$rows = Import-Csv $outFile
$totalRows = $rows.Count

# Schema guard. MetricsRecorder archives the CSV when the header changes, so a
# mixed-schema file should no longer be possible - this catches a stale file
# pulled from a device still running an older build.
$REQUIRED = @(
    'timestamp_iso','task_id','task_name','target','fell_back','actual_ms',
    'result_bytes','error','rule','battery_percent','is_charging','network_type',
    'network_score','rtt_ms','bandwidth_mbps','cpu_percent','is_stable',
    'est_local_ms','est_remote_ms','est_local_energy_mj','est_remote_energy_mj',
    'speedup','executed_at','server_exec_ms','debug_overrides','reasoning'
)
# Note: do NOT wrap this in @(...) - PSObject on an array reflects the array's
# own members (Count, Length, ...), not the row's columns.
$firstRow = $rows | Select-Object -First 1
$present  = $firstRow.PSObject.Properties.Name
$missing  = $REQUIRED | Where-Object { $_ -notin $present }

Write-Host "  Total rows      : $totalRows  (target >= 400)" -ForegroundColor $(if ($totalRows -ge 400) { "Green" } else { "Red" })
if ($missing) {
    Warn "CSV is missing columns: $($missing -join ', ')"
    Warn "This file came from an older build - reinstall the app and re-collect."
    return
}
Ok "Schema matches MetricsCsvFormat.HEADER ($($REQUIRED.Count) columns)"

Write-Host ""
Write-Host "  Rule distribution:" -ForegroundColor White

$dist = $rows | Group-Object rule | Sort-Object Count -Descending

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
$tasks = $rows | Group-Object task_name | Sort-Object Count -Descending
foreach ($t in $tasks) {
    $pct = [math]::Round(100 * $t.Count / [math]::Max($totalRows, 1))
    $clr = if ($pct -gt 40) { "Red" } elseif ($pct -gt 30) { "Yellow" } else { "Green" }
    Write-Host ("    {0,-25} {1,4} ({2}%)" -f $t.Name, $t.Count, $pct) -ForegroundColor $clr
}

# Fallback rate check
Write-Host ""
$fallbacks = ($rows | Where-Object { $_.fell_back -eq "true" }).Count
$fbPct = [math]::Round(100 * $fallbacks / [math]::Max($totalRows, 1))
$fbClr = if ($fbPct -gt 20) { "Red" } elseif ($fbPct -gt 10) { "Yellow" } else { "Green" }
Write-Host ("  Fallback rate: {0}/{1} ({2}%)" -f $fallbacks, $totalRows, $fbPct) -ForegroundColor $fbClr
if ($fbPct -gt 20) {
    Warn "High fallback rate means servers were unreachable during collection - re-run after fixing connectivity"
}

# Decision-integrity check: did the tier that ran the task match the chosen one?
#
# edge-server forwards to the cloud whenever ResourceMonitor.is_overloaded() is
# true, and psutil reports the HOST's memory from inside the container - so on a
# laptop above 80% RAM every EDGE decision silently executes on the CLOUD, with
# an extra hop. `executed_at` is the server's own account of where it ran.
Write-Host ""
$remoteRows = @($rows | Where-Object {
    $_.target -in @("EDGE", "CLOUD") -and $_.fell_back -ne "true"
})
$mismatched = @($remoteRows | Where-Object { $_.target -ne $_.executed_at.ToUpper() })
$mmPct = [math]::Round(100 * $mismatched.Count / [math]::Max($remoteRows.Count, 1))
$mmClr = if ($mmPct -gt 5) { "Red" } else { "Green" }
Write-Host ("  target != executed_at: {0}/{1} ({2}%)" -f $mismatched.Count, $remoteRows.Count, $mmPct) -ForegroundColor $mmClr
if ($mmPct -gt 5) {
    Warn "Edge is forwarding to cloud - free host RAM below 80% and re-run"
    Warn "Otherwise EDGE latency actually measures an edge->cloud relay"
    $mismatched | Group-Object target, executed_at |
        ForEach-Object { Write-Host ("    {0,-20} {1,4}" -f $_.Name, $_.Count) -ForegroundColor DarkYellow }
}

# Baseline / adaptive condition overlap - needed for the regret analysis in
# notebook section 15, which compares tiers within matched context buckets.
Write-Host ""
$adaptiveTasks = @($rows | Where-Object { $_.rule -notlike "FORCED_*" } | Select-Object -ExpandProperty task_name -Unique)
$baselineTasks = @($rows | Where-Object { $_.rule -like "FORCED_*" }    | Select-Object -ExpandProperty task_name -Unique)
$overlap = @($adaptiveTasks | Where-Object { $_ -in $baselineTasks })
$ovClr = if ($overlap.Count -ge 4) { "Green" } else { "Yellow" }
Write-Host ("  Tasks with both adaptive and baseline rows: {0}/5" -f $overlap.Count) -ForegroundColor $ovClr
if ($overlap.Count -lt 4) {
    Warn "Regret analysis needs the same tasks measured under baseline and adaptive modes"
}

Write-Host ""
$underRep = $dist | Where-Object { $minCounts[$_.Name] -and $_.Count -lt $minCounts[$_.Name] }
$allOk    = ($totalRows -ge 400) -and (-not $underRep) -and ($fbPct -le 20) -and ($mmPct -le 5)

if ($allOk) {
    Write-Host "  All checks passed. Proceed to Phase 2 training notebook." -ForegroundColor Cyan
} else {
    Write-Host "  Issues found above. Fix and re-run flagged sessions before training." -ForegroundColor Red
}
