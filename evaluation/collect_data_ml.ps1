# =============================================================================
# collect_data_ml.ps1  -  ADAPTIVE_ML (Random Forest) runtime collection
#
# WHY THIS EXISTS
#   The main dataset (training.csv, 667 rows) contains ZERO rows where the RF
#   actually decided on-device - every adaptive row is a rule-engine decision.
#   The RF is therefore only evaluated OFFLINE (classification accuracy against
#   rule-generated labels), never as a deployed runtime policy. This session
#   runs the app in ExecutionMode.ADAPTIVE_ML so the RF is the live planner,
#   producing rows whose `rule` column reads ML_PREDICTED_{LOCAL,EDGE,CLOUD}.
#   Those rows let the notebook's already-wired "ML (RF)" policy appear in the
#   latency table, the latency-by-policy figure, and the regret table.
#
# WHY THESE CONDITIONS (and NOT the full 11-session sweep)
#   The regret table only compares policies inside "matched-condition" buckets
#   keyed on (task, network, battery, cpu). Recomputing those buckets from the
#   real data shows all 6 that qualify (>=2 tiers, >=3 samples each) sit at
#   network=good, cpu=idle - 5 at battery=high and 1 at battery=low. None are
#   degraded-network or busy-CPU buckets. So to put the RF into the regret
#   table we need healthy + low-battery conditions, NOT the manual netem sweep.
#   Mobility (is_stable) is not a bucket key but drives pickRemoteTarget's
#   EDGE-vs-CLOUD choice, so the mobility sweep is what gives the RF rows across
#   all three tiers inside those healthy buckets.
#
#     ML-A  Healthy baseline (stationary)  -> LOCAL + EDGE, all 5 tasks
#     ML-B  Battery sweep (forced low)     -> the one battery=low bucket
#     ML-I  Mobility sweep                 -> CLOUD (moving) for tier coverage
#
#   -Full additionally runs offline / CPU-stress / manual-netem sessions for a
#   dataset directly comparable to the full rule-based 573 across every
#   condition, not only the regret-qualifying ones.
#
# DATA SAFETY
#   MetricsRecorder only ever appends to ONE on-device file, so these rows land
#   after the existing 667. The pull writes to training_with_ml.csv, NOT
#   training.csv, so the canonical training set is left untouched until the ML
#   rows have been inspected. The notebook already excludes ML_PREDICTED rows
#   from RF *training* (cell 12), so promoting the merged file later cannot
#   cause circular train-on-own-predictions contamination.
#
# Usage:
#   .\evaluation\collect_data_ml.ps1 -EdgeCloudHost 192.168.2.13
#   .\evaluation\collect_data_ml.ps1 -EdgeCloudHost 192.168.2.13 -Full
# =============================================================================

param(
    [Parameter(Mandatory = $true)]
    [string]$EdgeCloudHost,
    [int]$EdgePort = 8001,
    [int]$CloudPort = 8002,

    # Run the extra offline / CPU-stress / manual-netem sessions so the ML
    # dataset spans the same conditions as the full rule-based collection, not
    # only the regret-qualifying healthy buckets. Off by default.
    [switch]$Full,

    # Emulated cloud WAN distance held for the whole session, same rationale as
    # collect_data_remote.ps1's -CloudRttMs. Only used to print the netem
    # commands under -Full; nothing is applied by this script directly.
    [int]$CloudRttMs = 80
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
# HELPERS  (identical semantics to collect_data_remote.ps1)
# =============================================================================

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}
function Step($msg) { Write-Host "  >> $msg" -ForegroundColor DarkYellow }
function Ok($msg)   { Write-Host "  OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  WARN  $msg" -ForegroundColor Red }

function Pause-ForUser($msg) {
    Write-Host ""
    Write-Host "  [ACTION REQUIRED] $msg" -ForegroundColor Magenta
    Read-Host "  Press Enter when ready"
}

# Explicit -n component target: an implicit -a broadcast is silently dropped by
# Android 8+ for a manifest-registered receiver ("Broadcast completed" prints
# regardless), so every task/mode/debug broadcast would be a no-op without it.
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

function Clear-AllDebug {
    Step "clearing all debug overrides"
    Broadcast $ACTION_CLR
    Start-Sleep -Seconds 1
}

function Show-NetemCommands($delay, $loss) {
    $cloudDelay = $delay + $CloudRttMs
    Write-Host ""
    Write-Host "  Run these on the DOCKER MACHINE (adjust container names):" -ForegroundColor Magenta
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
    $edgeOk = $false; $cloudOk = $false
    try { $null = Invoke-WebRequest "$EDGE_URL/health"  -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop; $edgeOk  = $true } catch {}
    try { $null = Invoke-WebRequest "$CLOUD_URL/health" -TimeoutSec 4 -UseBasicParsing -ErrorAction Stop; $cloudOk = $true } catch {}
    if (-not $edgeOk)  { Warn "Edge server NOT reachable at $EDGE_URL" }
    if (-not $cloudOk) { Warn "Cloud server NOT reachable at $CLOUD_URL" }
    if (-not ($edgeOk -and $cloudOk)) {
        Pause-ForUser "Check the Docker machine is up and reachable, then press Enter to retry."
        Assert-ServersHealthy
    } else { Ok "Edge + Cloud servers healthy" }
}

# Fail loudly if the app is not actually planning with the RF. Without the
# model in assets, MapeLoop falls back to the rule engine and every row would
# read ML_UNAVAILABLE_FELLBACK_TO_RULES - a whole session of rule decisions
# mislabelled as an ML run. There is no broadcast that reports the active mode,
# so this is a reminder to eyeball the app's decision card, not an assertion.
function Remind-VerifyMlMode {
    Write-Host ""
    Write-Host "  [VERIFY] On the phone, confirm the decision card shows rule ids that" -ForegroundColor Magenta
    Write-Host "  start with ML_PREDICTED_ (not ML_UNAVAILABLE_FELLBACK_TO_RULES and not" -ForegroundColor Magenta
    Write-Host "  the plain rule names). If they don't, assets/rf-model.json is missing" -ForegroundColor Magenta
    Write-Host "  from the installed build - reinstall before continuing." -ForegroundColor Magenta
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

Step "launching app so ContextService starts"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4

Step "pointing the app at $EdgeCloudHost"
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2
Ok "App endpoints set: edge=$EDGE_URL cloud=$CLOUD_URL"

Step "switching to ADAPTIVE_ML (Random Forest is now the live planner)"
Set-ExecMode "ADAPTIVE_ML"
Clear-AllDebug

if ($Full -and $CloudRttMs -gt 0) {
    Show-NetemClear
    Pause-ForUser "Apply the cloud WAN baseline shown above on the Docker machine, then press Enter."
}

# One quick task so the phone renders an ML decision the operator can eyeball.
Run-Task "sha256" 2
Remind-VerifyMlMode
Pause-ForUser "Confirm ML_PREDICTED_* is showing, then press Enter to start collection."

# =============================================================================
# SESSION ML-A - Healthy baseline (stationary)   -> LOCAL + EDGE, all 5 tasks
# =============================================================================

Section "SESSION ML-A - Healthy Baseline  (target: ~42 rows)"
Write-Host "  Conditions: battery 75%+, good Wi-Fi, phone idle+stationary, ADAPTIVE_ML" -ForegroundColor Gray
Write-Host "  Mirrors rule Session A - the RF should reproduce its LOCAL/EDGE mix" -ForegroundColor Gray

Assert-ServersHealthy

Run-Task "echo"              8
Run-Task "sha256"            10
Run-Task "image-grayscale"   8
Run-Task "matrix-multiply"   8
Run-Task "video-frame-edges" 8 $VIDEO_DELAY_MS

Ok "Session ML-A done"

# =============================================================================
# SESSION ML-B - Battery sweep (forced low)   -> the battery=low regret bucket
# =============================================================================

Section "SESSION ML-B - Battery Sweep  (target: ~57 rows)"
Write-Host "  Conditions: forced battery 28->12%, good Wi-Fi, idle CPU, ADAPTIVE_ML" -ForegroundColor Gray
Write-Host "  battery_percent is an RF feature, so low battery shifts its routing;" -ForegroundColor Gray
Write-Host "  no remote_energy_mj override needed - energy is NOT one of the 12 RF features" -ForegroundColor Gray

Assert-ServersHealthy

foreach ($level in @(28, 18, 12)) {
    Step "battery level -> $level%"
    adb shell dumpsys battery unplug           | Out-Null
    adb shell dumpsys battery set level $level | Out-Null
    adb shell dumpsys battery set status 3     | Out-Null   # 3 = discharging
    Start-Sleep -Seconds 8

    Run-Task "echo"              3
    Run-Task "sha256"            5
    Run-Task "image-grayscale"   5
    Run-Task "matrix-multiply"   3
    Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
}

Step "resetting battery"
adb shell dumpsys battery reset | Out-Null
Start-Sleep -Seconds 2
Ok "Session ML-B done"

# =============================================================================
# SESSION ML-I - Mobility sweep   -> CLOUD (moving) for full-tier bucket coverage
# =============================================================================

Section "SESSION ML-I - Mobility Sweep  (target: ~45 rows)"
Write-Host "  Conditions: movement forced STATIONARY/WALKING/VEHICLE, good Wi-Fi, ADAPTIVE_ML" -ForegroundColor Gray
Write-Host "  Expected: STATIONARY -> EDGE, WALKING/VEHICLE -> CLOUD (RF imitating pickRemoteTarget)" -ForegroundColor Gray

Assert-ServersHealthy

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
Ok "Session ML-I done"

# =============================================================================
# -Full only: offline / CPU-stress / manual-netem, for full comparability with
# the rule-based 573 across every condition (not just regret-qualifying ones).
# =============================================================================

if ($Full) {

    Section "SESSION ML-D - Offline  (target: ~50 rows)"
    Write-Host "  Conditions: Wi-Fi disabled via ADB, ADAPTIVE_ML (RF should pick LOCAL)" -ForegroundColor Gray
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
    Ok "Session ML-D done"

    Section "SESSION ML-E - CPU Stress via task flood  (target: ~40 rows)"
    Write-Host "  Conditions: 30 background matrix tasks flood CPU, then light tasks measured" -ForegroundColor Gray
    Assert-ServersHealthy
    Step "flooding CPU with background matrix-multiply tasks (30 x 500ms delay)"
    Broadcast $ACTION_RUN "--es task matrix-multiply --ei count 30 --el delay_ms 500"
    Start-Sleep -Seconds 5
    Run-Task "sha256" 20
    Run-Task "echo"   20
    Step "waiting for background flood to finish"
    Start-Sleep -Seconds 20
    Ok "Session ML-E done"

    Section "SESSION ML-C - Network Degradation (manual netem)  (target: ~96 rows)"
    Write-Host "  Conditions: real delay+loss applied manually on the Docker machine, ADAPTIVE_ML" -ForegroundColor Gray
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
        Start-Sleep -Seconds 10
        Run-Task "echo"              5
        Run-Task "sha256"            5
        Run-Task "image-grayscale"   5
        Run-Task "matrix-multiply"   5
        Run-Task "video-frame-edges" 4 $VIDEO_DELAY_MS
        Show-NetemClear
        Pause-ForUser "Restore the WAN baseline shown above on the Docker machine, then press Enter."
        Start-Sleep -Seconds 8
    }
    Ok "Session ML-C done"
}

# =============================================================================
# RESTORE + PULL
# =============================================================================

Section "CLEANUP"
Set-ExecMode "ADAPTIVE"
Clear-AllDebug
Ok "Mode restored to ADAPTIVE, all debug overrides cleared"

Section "PULLING CSV"
# Deliberately NOT training.csv. This pull is the full on-device file (the
# original 667 rows PLUS the ML_PREDICTED rows just collected). It goes to a
# separate name so the canonical training set stays untouched until the ML
# rows have been verified sane; promotion to training.csv is a later, manual
# step once the tier distribution and ML_PREDICTED counts look correct.
$outFile = "evaluation\data\training_with_ml.csv"
New-Item -ItemType Directory -Force "evaluation\data" | Out-Null
Step "adb pull -> $outFile"
adb pull "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv" $outFile
Ok "Saved to $outFile"

Write-Host ""
Write-Host "  ADAPTIVE_ML collection complete." -ForegroundColor Cyan
Write-Host "  Next: verify the ML rows landed and look sane, e.g." -ForegroundColor Cyan
Write-Host "    python -c ""import pandas as pd; d=pd.read_csv('evaluation/data/training_with_ml.csv'); m=d[d['rule'].astype(str).str.startswith('ML_PREDICTED')]; print('ML rows:',len(m)); print(m['rule'].value_counts()); print(m['target'].value_counts())""" -ForegroundColor White
