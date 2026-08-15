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
#   FIX-I: Cloud gets a persistent WAN-distance delay. Both containers run on
#           this laptop, so without it "cloud" is a second process one hop away
#           and measures *closer* than the edge whenever the edge is shaped -
#           which inverts the premise the thesis argues.
#   FIX-J: Network degradation is applied to BOTH tiers. Shaping only the edge
#           emulates "the edge got worse", not "the phone's access link got
#           worse", and would push every decision to the cloud for the wrong
#           reason.
#
# Usage:
#   .\evaluation\collect_data.ps1
#   .\evaluation\collect_data.ps1 -CloudRttMs 0     # co-located cloud (not advised)
# =============================================================================

param(
    # Added one-way delay on the cloud container, in ms, held for the whole run.
    # Stands in for wide-area distance to a datacentre: edge stays on the LAN,
    # cloud sits ~80ms away. Report this number in the thesis - it is an
    # emulated topology, not a measured one. Set to 0 to disable.
    [int]$CloudRttMs = 80
)

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
$EDGE_CONTAINER  = $null
$CLOUD_CONTAINER = $null

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

function Run-Task($task, $count, $delayMs = $TASK_DELAY_MS, $size = 0) {
    $waitSec = [math]::Ceiling($count * $delayMs / 1000) + 6
    $sizeArg = ""
    $label   = "$task x$count"
    if ($size -gt 0) {
        $sizeArg = "--ei size $size"
        $label   = "$task x$count (size=$size)"
    }
    Step "run $label  (waiting ${waitSec}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs $sizeArg"
    Start-Sleep -Seconds $waitSec
}

# Forces the accelerometer-derived movement state. MobilityCollector only ever
# reports STATIONARY for a phone on a desk, so without this the WALKING/VEHICLE
# branches of pickRemoteTarget and the mobility latency penalty are dead code
# as far as the collected data is concerned.
function Set-Movement($state) {
    Step "movement state -> $state"
    Broadcast $ACTION_DBG "--es movement_state $state"
    Start-Sleep -Seconds 2
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

# Resolve a container by its compose service label, which is stable across
# compose v1/v2 naming and any project name.
function Resolve-Container($service, $fallbackPattern) {
    $name = docker ps --filter "label=com.docker.compose.service=$service" `
                      --format "{{.Names}}" | Select-Object -First 1
    if (-not $name) {
        # Fall back to a name match for containers started outside compose.
        $name = docker ps --format "{{.Names}}" |
                Where-Object { $_ -match $fallbackPattern } | Select-Object -First 1
    }
    return $name
}

function Resolve-EdgeContainer  { Resolve-Container "edge-server"  "edge" }
function Resolve-CloudContainer { Resolve-Container "cloud-server" "cloud" }

# Verify tc is usable BEFORE any session depends on it.
#
# Two separate failure modes, both previously silent because the tc output was
# piped to Out-Null and the exit code never checked:
#   1. `tc` is absent      - python:3.11-slim ships no iproute2
#   2. "Operation not permitted" - the container lacks NET_ADMIN
# Either one means Session C records normal-network rows while claiming to have
# injected 500ms/20% loss, and UNSTABLE_NETWORK barely fires.
function Assert-NetemUsable($container) {
    Step "verifying tc/netem works inside $container"

    $probe = docker exec $container tc qdisc show dev eth0 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $probe -match "not found|executable file") {
        Warn "tc is not available in $container."
        Warn "The image needs iproute2 - rebuild with:"
        Warn "  docker compose -f docker/docker-compose.yml up -d --build"
        return $false
    }

    $null = docker exec $container tc qdisc del dev eth0 root 2>&1
    $add = docker exec $container tc qdisc add dev eth0 root netem delay 1ms 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $add -match "not permitted|Error") {
        Warn "tc exists but cannot modify qdiscs: $($add.Trim())"
        Warn "The container needs NET_ADMIN. docker-compose.yml grants it via cap_add;"
        Warn "recreate the containers so the capability takes effect:"
        Warn "  docker compose -f docker/docker-compose.yml up -d --force-recreate"
        return $false
    }

    $null = docker exec $container tc qdisc del dev eth0 root 2>&1
    Ok "tc/netem works in $container"
    return $true
}

# Applies a qdisc to one container and confirms it took effect, rather than
# trusting the exit code. `delay 0ms loss 0%` is still a netem qdisc, so the
# verification below holds for the healthy-baseline case too.
function Apply-Netem($container, $delay, $loss) {
    $null = docker exec $container tc qdisc del dev eth0 root 2>&1
    if ($delay -le 0 -and $loss -le 0) { return $true }

    $out = docker exec $container tc qdisc add dev eth0 root netem `
               delay "${delay}ms" loss "${loss}%" 2>&1 | Out-String
    $shown = docker exec $container tc qdisc show dev eth0 2>&1 | Out-String
    if ($shown -notmatch "netem") {
        Warn "netem did NOT apply to ${container}: $($out.Trim())"
        return $false
    }
    Write-Host ("    {0,-28} {1}" -f $container, $shown.Trim()) -ForegroundColor DarkGray
    return $true
}

# Degrade the phone's ACCESS LINK, which means both tiers.
#
# Shaping only the edge would emulate "the edge node got slower" while the cloud
# stayed pristine - so every degraded row would route to the cloud, and the data
# would show the policy fleeing the edge under conditions that in reality affect
# both paths equally. The cloud keeps its baseline WAN delay on top, since
# distance does not disappear when the access link degrades.
function Set-Netem($delay, $loss) {
    Step "netem -> edge: delay=${delay}ms loss=${loss}%   cloud: delay=$($delay + $CloudRttMs)ms loss=${loss}%"
    $edgeOk  = Apply-Netem $EDGE_CONTAINER  $delay $loss
    $cloudOk = Apply-Netem $CLOUD_CONTAINER ($delay + $CloudRttMs) $loss

    if (-not ($edgeOk -and $cloudOk)) {
        Warn "These rows would be labelled as degraded but collected on a healthy link."
        Pause-ForUser "Fix netem (see the pre-flight output above) then press Enter, or Ctrl+C to abort."
    }
    # The phone re-probes RTT on a 5s TTL, so it needs longer than that to see
    # the new conditions. Rows collected before it does would carry the previous
    # network score.
    Start-Sleep -Seconds 10
}

# Returns to "healthy access link", NOT to "no shaping at all": the cloud keeps
# its WAN-distance baseline for the whole run.
function Clear-Netem {
    Step "restoring healthy link (cloud stays at ${CloudRttMs}ms WAN baseline)"
    $null = Apply-Netem $EDGE_CONTAINER 0 0
    $null = Apply-Netem $CLOUD_CONTAINER $CloudRttMs 0
    Start-Sleep -Seconds 8
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
$CLOUD_CONTAINER = Resolve-CloudContainer
if (-not $CLOUD_CONTAINER) {
    Write-Error "Could not identify the cloud container. Is docker compose up?"
    exit 1
}
Ok "Edge container : $EDGE_CONTAINER"
Ok "Cloud container: $CLOUD_CONTAINER"

Assert-ServersHealthy

# Session C is worthless if netem cannot be applied, so check now rather than
# discovering it 20 minutes in. Both tiers are checked because both are shaped.
$netemOk = (Assert-NetemUsable $EDGE_CONTAINER) -and (Assert-NetemUsable $CLOUD_CONTAINER)
if (-not $netemOk) {
    Warn "Session C would collect normal-network rows labelled as degraded,"
    Warn "and the cloud would measure as close as the edge."
    Pause-ForUser "Rebuild/recreate the containers as described above, then press Enter to re-check."
    $netemOk = (Assert-NetemUsable $EDGE_CONTAINER) -and (Assert-NetemUsable $CLOUD_CONTAINER)
    if (-not $netemOk) {
        Pause-ForUser "netem still unavailable. Press Enter to continue anyway (network conditions will NOT be shaped), or Ctrl+C to abort."
    }
}

# ── Emulated topology ────────────────────────────────────────────────────────
# Both servers are containers on this laptop, one hop from the phone. Left as-is,
# "cloud" is not a distant tier - it is a second local process with a bigger CPU
# quota, so cloud latency would come out at or below edge latency and the whole
# edge-versus-cloud argument would invert. A persistent one-way delay on the
# cloud container stands in for the distance to a datacentre.
#
# This is emulation and must be reported as such: the cloud tier's network cost
# is a number chosen here, not one measured against a real provider.
if ($netemOk -and $CloudRttMs -gt 0) {
    Step "installing WAN baseline: cloud +${CloudRttMs}ms one-way, edge unshaped"
    if (Apply-Netem $CLOUD_CONTAINER $CloudRttMs 0) {
        Ok "Cloud is now ~${CloudRttMs}ms further away than the edge for the whole run"
    } else {
        Warn "Could not shape the cloud - edge and cloud will be indistinguishable"
    }
    Start-Sleep -Seconds 5
} elseif ($CloudRttMs -le 0) {
    Warn "-CloudRttMs 0: edge and cloud are co-located and will measure the same."
    Warn "Any edge-vs-cloud latency difference in the results is CPU quota, not distance."
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
# SESSION I - Mobility sweep  (~60 rows)
#
# OffloadingPolicy.pickRemoteTarget picks EDGE only when the device is
# STATIONARY; anything else goes to CLOUD. LatencyEstimator adds up to 200ms of
# mobility penalty on the same signal. Neither was ever exercised: a phone on a
# desk always reports STATIONARY, so every previous dataset had mobilityScore
# pinned at 1.0 and the branch was untestable from the data.
# =============================================================================

Section "SESSION I - Mobility Sweep  (target: ~60 rows)"
Write-Host "  Conditions: movement state forced via debug override, ADAPTIVE mode" -ForegroundColor Gray
Write-Host "  Expected: STATIONARY -> EDGE, WALKING/VEHICLE -> CLOUD + latency penalty" -ForegroundColor Gray

Assert-ServersHealthy
Set-ExecMode "ADAPTIVE"

foreach ($state in @("STATIONARY", "WALKING", "VEHICLE")) {
    Set-Movement $state
    Run-Task "sha256"            4
    Run-Task "image-grayscale"   4
    Run-Task "matrix-multiply"   4
    Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
}

Step "restoring real accelerometer readings"
Broadcast $ACTION_DBG "--es movement_state NONE"
Start-Sleep -Seconds 2
Ok "Session I done"

# =============================================================================
# SESSION J - Payload size sweep  (~72 rows)
#
# Each task previously had exactly one payload size, so the transmission term of
# the remote cost (payload / bandwidth) was a per-task constant and its
# contribution could not be separated from the task's compute cost. Sweeping
# size within a task type makes that term vary independently of complexity.
# =============================================================================

Section "SESSION J - Payload Size Sweep  (target: ~72 rows)"
Write-Host "  Conditions: same task type at varying payload sizes, ADAPTIVE mode" -ForegroundColor Gray

Assert-ServersHealthy

# sha256: 1 KB -> 1 MB. Compute is near-constant, so any latency change is transmission.
foreach ($bytes in @(1024, 16384, 262144, 1048576)) {
    Run-Task "sha256" 5 $TASK_DELAY_MS $bytes
}

# image-grayscale: 128px -> 1024px. Both payload and compute scale with area.
foreach ($side in @(128, 256, 512, 1024)) {
    Run-Task "image-grayscale" 5 $TASK_DELAY_MS $side
}

# matrix-multiply: n=16 -> n=96. Payload grows n^2, compute grows n^3 - the
# case where offloading should win most clearly at the top end.
foreach ($n in @(16, 32, 64, 96)) {
    Run-Task "matrix-multiply" 5 $TASK_DELAY_MS $n
}

Ok "Session J done"

# =============================================================================
# SESSION K - Edge under contention  (~45 rows)
#
# Emulates other tenants. With one phone the edge executor's 4-slot semaphore
# never fills, /queue always reads 0, and is_overloaded() never fires - so every
# latency number so far was measured against an idle server, which is the best
# case rather than the case adaptive offloading exists for.
#
# This is contention EMULATION, not multi-user evaluation: the synthetic clients
# are processes on this machine, not devices with their own radios. It shows the
# policy reacts to server-side load; it does not show the system scales to N users.
# =============================================================================

Section "SESSION K - Edge Under Contention  (target: ~45 rows)"
Write-Host "  Conditions: 8 synthetic clients saturating the edge, ADAPTIVE mode" -ForegroundColor Gray
Write-Host "  Expected: edge queue grows, some tasks forwarded to cloud (executed_at=cloud)" -ForegroundColor Gray

Assert-ServersHealthy
Set-ExecMode "ADAPTIVE"

$python = if (Test-Path ".venv-dev\Scripts\python.exe") { ".venv-dev\Scripts\python.exe" } else { "python" }
Step "starting background load (8 clients, 180s)"
$load = Start-Process -FilePath $python `
    -ArgumentList "evaluation/edge_load_generator.py","--clients","8","--duration","180" `
    -PassThru -NoNewWindow

Start-Sleep -Seconds 10   # let the queue build before measuring

Run-Task "sha256"            8
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 5 $VIDEO_DELAY_MS

Step "waiting for the load generator to finish"
try { $load | Wait-Process -Timeout 120 -ErrorAction Stop } catch { $load | Stop-Process -Force }
Start-Sleep -Seconds 5
Ok "Session K done"

# =============================================================================
# RESTORE + PULL
# =============================================================================

Section "CLEANUP"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug
Ok "Mode restored to ADAPTIVE, all debug overrides cleared"

Step "removing all network shaping from both containers"
$null = docker exec $EDGE_CONTAINER  tc qdisc del dev eth0 root 2>&1
$null = docker exec $CLOUD_CONTAINER tc qdisc del dev eth0 root 2>&1
Ok "Containers back to an unshaped network"

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
Step "adb pull -> $outFile"
adb shell "run-as $PKG cat files/mocca-metrics.csv" > $outFile
Ok "Saved to $outFile"


Write-Host ""
Write-Host "  Collection complete. To check whether the dataset is usable:" -ForegroundColor Cyan
Write-Host "    .\evaluation\verify_dataset.ps1" -ForegroundColor White
Write-Host ""
