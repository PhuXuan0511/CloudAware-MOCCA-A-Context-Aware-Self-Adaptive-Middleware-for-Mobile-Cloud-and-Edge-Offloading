# =============================================================================
# collect_data_remote.ps1  -  MOCCA Phase 1 data collection, split-machine mode
#
# For the topology where the phone/emulator runs on THIS machine but the edge
# and cloud servers (docker compose) run on a DIFFERENT machine on the same
# network. collect_data.ps1 assumes local `docker` CLI access to shape the
# network (Session C, the cloud WAN baseline, pre-flight netem checks) and to
# resolve container names - none of that is possible from here, so this is a
# separate script rather than a heavily-branched version of that one.
#
# What is different from collect_data.ps1:
#   - No docker calls anywhere. Health checks and the overload check hit
#     $EdgeCloudHost over HTTP instead of localhost.
#   - The app is pointed at $EdgeCloudHost via a new SET_ENDPOINTS broadcast
#     (AutoRunReceiver), so there is no manual Settings-screen step per install.
#   - Session C (network degradation) cannot inject netem from here. The script
#     prints the exact `docker exec ... tc qdisc ...` commands for whoever has
#     a shell on the Docker machine to run, and pauses for confirmation before
#     and after each step - same session structure, manual middle step.
#   - The persistent cloud WAN-distance baseline (see collect_data.ps1's
#     -CloudRttMs) is likewise a one-time manual step during pre-flight,
#     printed once rather than re-applied per session.
#   - Session H (LTE via phone hotspot) is dropped. Joining the phone's hotspot
#     would take this machine off the LAN that reaches the Docker machine -
#     see the note above Session H below for how to collect it separately.
#
# Usage:
#   .\evaluation\collect_data_remote.ps1 -EdgeCloudHost 192.168.1.50
#   .\evaluation\collect_data_remote.ps1 -EdgeCloudHost 192.168.1.50 -SkipNetworkDegradation
# =============================================================================

param(
    # LAN IP (or hostname) of the machine running docker compose for edge/cloud.
    [Parameter(Mandatory = $true)]
    [string]$EdgeCloudHost,

    [int]$EdgePort = 8001,
    [int]$CloudPort = 8002,

    # Stands in for wide-area distance to a datacentre, same rationale as
    # collect_data.ps1's -CloudRttMs. Applied manually on the Docker machine
    # during pre-flight here - this value only controls what the script tells
    # the operator to type, not anything it runs itself.
    [int]$CloudRttMs = 80,

    # Skip Session C and the WAN-baseline instructions entirely, for a run
    # where nobody has shell access to the Docker machine right now. The
    # resulting dataset has no shaped-network rows and edge/cloud will measure
    # at the same distance - note that in the thesis if you use this.
    [switch]$SkipNetworkDegradation
)

$PKG              = "com.thesis.middleware"
$ACTION_RUN       = "$PKG.RUN_TASK"
$ACTION_MODE      = "$PKG.SET_MODE"
$ACTION_DBG       = "$PKG.SET_DEBUG"
$ACTION_CLR       = "$PKG.CLEAR_DEBUG"
$ACTION_ENDPOINTS = "$PKG.SET_ENDPOINTS"

$TASK_DELAY_MS  = 3000
$VIDEO_DELAY_MS = 6000

$EDGE_URL  = "http://${EdgeCloudHost}:${EdgePort}"
$CLOUD_URL = "http://${EdgeCloudHost}:${CloudPort}"

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
    # Explicit component target, not just -a <action>. Verified on a real
    # Android 14 device: an implicit broadcast (action only) is silently
    # dropped before it reaches AutoRunReceiver - "Broadcast completed:
    # result=0" prints as if it worked, and every task/mode/debug broadcast in
    # a collection run is a no-op with nothing in the logs to say so. Adding
    # -n makes delivery unconditional regardless of Android version or OEM
    # broadcast policy.
    $full = "adb shell am broadcast -n $PKG/.AutoRunReceiver -a $action $extras"
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

function Pause-ForUser($msg) {
    Write-Host ""
    Write-Host "  [ACTION REQUIRED] $msg" -ForegroundColor Magenta
    Read-Host "  Press Enter when ready"
}

# Prints the exact commands for whoever has a shell on the Docker machine, since
# this script has no local `docker` CLI access to that host. Mirrors
# Apply-Netem / Set-Netem in collect_data.ps1, including shaping BOTH tiers -
# shaping only the edge would emulate "the edge node got slower" rather than
# "the phone's access link got worse", and would push every decision to the
# cloud for the wrong reason.
function Show-NetemCommands($delay, $loss) {
    $cloudDelay = $delay + $CloudRttMs
    Write-Host ""
    Write-Host "  Run these on the DOCKER MACHINE (adjust container names for your compose project):" -ForegroundColor Magenta
    Write-Host "    docker exec <edge-container>  tc qdisc del dev eth0 root 2>`$null" -ForegroundColor White
    Write-Host "    docker exec <edge-container>  tc qdisc add dev eth0 root netem delay ${delay}ms loss ${loss}%" -ForegroundColor White
    Write-Host "    docker exec <cloud-container> tc qdisc del dev eth0 root 2>`$null" -ForegroundColor White
    Write-Host "    docker exec <cloud-container> tc qdisc add dev eth0 root netem delay ${cloudDelay}ms loss ${loss}%" -ForegroundColor White
}

function Show-NetemClear {
    Write-Host ""
    Write-Host "  Run these on the DOCKER MACHINE to restore the WAN baseline:" -ForegroundColor Magenta
    Write-Host "    docker exec <edge-container>  tc qdisc del dev eth0 root" -ForegroundColor White
    Write-Host "    docker exec <cloud-container> tc qdisc del dev eth0 root" -ForegroundColor White
    if ($CloudRttMs -gt 0) {
        Write-Host "    docker exec <cloud-container> tc qdisc add dev eth0 root netem delay ${CloudRttMs}ms" -ForegroundColor White
    }
}

function Assert-ServersHealthy {
    Step "checking edge + cloud server health at $EdgeCloudHost"
    $edgeOk  = $false
    $cloudOk = $false
    try {
        $null = Invoke-WebRequest "$EDGE_URL/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        $edgeOk = $true
    } catch {}
    try {
        $null = Invoke-WebRequest "$CLOUD_URL/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop
        $cloudOk = $true
    } catch {}

    if (-not $edgeOk) { Warn "Edge server NOT reachable at $EDGE_URL" }
    if (-not $cloudOk) { Warn "Cloud server NOT reachable at $CLOUD_URL" }

    if (-not ($edgeOk -and $cloudOk)) {
        Pause-ForUser "Check the Docker machine is up and reachable (firewall, same network), then press Enter to retry."
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
    Write-Error "No ADB device/emulator found. Start the emulator (or connect the phone) and retry."
    exit 1
}
Ok "Device: $device"

Assert-ServersHealthy

Step "checking edge is not already overloaded"
try {
    $edgeStatus = Invoke-RestMethod "$EDGE_URL/api/v1/status" -TimeoutSec 5
    Write-Host ("    cpu {0}% / mem {1}%   (source: cpu={2}, memory={3})" -f `
        $edgeStatus.cpu_percent, $edgeStatus.memory_used_percent, `
        $edgeStatus.metrics_source.cpu, $edgeStatus.metrics_source.memory) -ForegroundColor Gray
    if ($edgeStatus.metrics_source.memory -eq "psutil") {
        Warn "Edge has no memory cgroup limit - it is reading HOST memory on the Docker machine."
        Warn "Set mem_limit in docker/docker-compose.yml there, or EDGE rows will really be cloud runs."
    }
    if ($edgeStatus.overloaded) {
        Warn "Edge reports OVERLOADED - it will forward every task to the cloud."
        Pause-ForUser "Free resources on the Docker machine, then press Enter."
    } else {
        Ok "Edge has headroom - EDGE decisions will execute on the edge"
    }
} catch {
    Warn "Could not read edge status: $($_.Exception.Message)"
}

if (-not $SkipNetworkDegradation -and $CloudRttMs -gt 0) {
    Write-Host ""
    Write-Host "  Emulated topology: this run assumes the cloud tier is shaped to be" -ForegroundColor Gray
    Write-Host "  ${CloudRttMs}ms further away than the edge, for the whole session." -ForegroundColor Gray
    Show-NetemClear
    Pause-ForUser "Apply the cloud WAN baseline shown above on the Docker machine, then press Enter."
} elseif ($SkipNetworkDegradation) {
    Warn "-SkipNetworkDegradation: edge and cloud will measure at the same distance."
    Warn "Report this in the thesis - any edge-vs-cloud latency gap is CPU quota, not distance."
}

Step "launching app so ContextService starts"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

Step "pointing the app at $EdgeCloudHost"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2
Ok "App endpoints set: edge=$EDGE_URL cloud=$CLOUD_URL"

Step "resetting to clean state"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug

# =============================================================================
# SESSION A - Healthy baseline  (~50 rows)
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
# =============================================================================

Section "SESSION B - Battery Sweep  (target: ~120 rows)"
Write-Host "  Conditions: forced battery 28->12%, debugRemoteEnergyMj=50 to satisfy energy condition" -ForegroundColor Gray

Assert-ServersHealthy

Set-Debug "--ef remote_energy_mj 50"

foreach ($level in @(28, 25, 22, 18, 15, 12)) {
    Step "battery level -> $level%"
    adb shell dumpsys battery unplug           | Out-Null
    adb shell dumpsys battery set level $level | Out-Null
    adb shell dumpsys battery set status 3     | Out-Null   # 3 = discharging
    Start-Sleep -Seconds 8

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
# SESSION C - Network degradation (manual netem on the Docker machine)
# =============================================================================

if (-not $SkipNetworkDegradation) {
    Section "SESSION C - Network Degradation  (target: ~96 rows)"
    Write-Host "  Conditions: real delay+loss applied manually on the Docker machine" -ForegroundColor Gray

    Assert-ServersHealthy

    $steps = @(
        @{ Delay = 100;  Loss = 0 },
        @{ Delay = 300;  Loss = 5 },
        @{ Delay = 500;  Loss = 20 },
        @{ Delay = 1000; Loss = 30 }
    )
    foreach ($s in $steps) {
        Show-NetemCommands $s.Delay $s.Loss
        Pause-ForUser "Apply the commands above on the Docker machine, then press Enter."
        Start-Sleep -Seconds 10   # phone re-probes RTT on a 5s TTL

        Run-Task "echo"              5
        Run-Task "sha256"            5
        Run-Task "image-grayscale"   5
        Run-Task "matrix-multiply"   5
        Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS

        Show-NetemClear
        Pause-ForUser "Restore the WAN baseline shown above on the Docker machine, then press Enter."
        Start-Sleep -Seconds 8
    }
    Ok "Session C done"
} else {
    Warn "Skipping Session C (-SkipNetworkDegradation) - no shaped-network rows in this dataset."
}

# =============================================================================
# SESSION D - Offline  (~50 rows)
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
Start-Sleep -Seconds 8
Ok "Session D done"

# =============================================================================
# SESSION E - CPU stress via matrix-multiply flood  (~40 rows)
# =============================================================================

Section "SESSION E - CPU Stress via task flood  (target: ~40 rows)"
Write-Host "  Conditions: 30 background matrix tasks flood CPU, then light tasks measured" -ForegroundColor Gray
Write-Host "  Expected rules: LATENCY_SENSITIVE for echo and sha256" -ForegroundColor Gray

Assert-ServersHealthy

Step "flooding CPU with background matrix-multiply tasks (30 x 500ms delay)"
Broadcast $ACTION_RUN "--es task matrix-multiply --ei count 30 --el delay_ms 500"
Start-Sleep -Seconds 5

Run-Task "sha256" 20
Run-Task "echo"   20

Step "waiting for background flood to finish"
Start-Sleep -Seconds 20
Ok "Session E done"

# =============================================================================
# SESSION F - LOCAL_ONLY baseline  (~50 rows)
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
# SESSION H - dropped in this topology
#
# collect_data.ps1's version has this laptop join the PHONE's hotspot so the
# phone's uplink becomes LTE while the laptop (running docker) stays reachable
# at the hotspot's local IP. Here the Docker machine is a THIRD party on the
# original LAN: joining the phone's hotspot would take this machine off that
# LAN and onto an isolated one behind the phone's NAT, with no route back to
# $EdgeCloudHost. Making that work needs a VPN or a second network path into
# wherever the Docker machine lives, which is out of scope for this script.
#
# Collect Session H separately: either run collect_data.ps1 (the co-located
# version) end to end on the Docker machine with the phone plugged into IT
# over USB, or set up routing back to $EdgeCloudHost from the phone's hotspot
# subnet first.
# =============================================================================

# =============================================================================
# SESSION I - Mobility sweep  (~60 rows)
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
# =============================================================================

Section "SESSION J - Payload Size Sweep  (target: ~72 rows)"
Write-Host "  Conditions: same task type at varying payload sizes, ADAPTIVE mode" -ForegroundColor Gray

Assert-ServersHealthy

foreach ($bytes in @(1024, 16384, 262144, 1048576)) {
    Run-Task "sha256" 5 $TASK_DELAY_MS $bytes
}

foreach ($side in @(128, 256, 512, 1024)) {
    Run-Task "image-grayscale" 5 $TASK_DELAY_MS $side
}

foreach ($n in @(16, 32, 64, 96)) {
    Run-Task "matrix-multiply" 5 $TASK_DELAY_MS $n
}

Ok "Session J done"

# =============================================================================
# SESSION K - Edge under contention  (~45 rows)
# =============================================================================

Section "SESSION K - Edge Under Contention  (target: ~45 rows)"
Write-Host "  Conditions: 8 synthetic clients saturating the edge, ADAPTIVE mode" -ForegroundColor Gray
Write-Host "  Expected: edge queue grows, some tasks forwarded to cloud (executed_at=cloud)" -ForegroundColor Gray

Assert-ServersHealthy
Set-ExecMode "ADAPTIVE"

$python = if (Test-Path ".venv-dev\Scripts\python.exe") { ".venv-dev\Scripts\python.exe" } else { "python" }
Step "starting background load (8 clients, 180s) against $EDGE_URL"
$load = Start-Process -FilePath $python `
    -ArgumentList "evaluation/edge_load_generator.py","--url",$EDGE_URL,"--clients","8","--duration","180" `
    -PassThru -NoNewWindow

Start-Sleep -Seconds 10

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

if (-not $SkipNetworkDegradation) {
    Show-NetemClear
    Write-Host "  (Should already be clear from the last Session C step above - this is a" -ForegroundColor Gray
    Write-Host "   final check, not a new requirement.)" -ForegroundColor Gray
}

Section "PULLING CSV"
$outFile = "evaluation\data\training.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
Step "adb pull -> $outFile"
# MetricsRecorder writes via getExternalFilesDir, not the app's internal
# storage - `run-as $PKG cat files/...` reads the wrong directory and always
# returns "No such file or directory", silently producing an empty/missing
# pull no matter how successful the session was. Confirmed on a real device:
# the file only ever existed at this external path.
adb pull "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv" $outFile
Ok "Saved to $outFile"

Write-Host ""
Write-Host "  Collection complete. To check whether the dataset is usable:" -ForegroundColor Cyan
Write-Host "    .\evaluation\verify_dataset.ps1" -ForegroundColor White
