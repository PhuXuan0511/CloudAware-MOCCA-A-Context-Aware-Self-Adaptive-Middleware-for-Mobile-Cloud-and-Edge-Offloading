# =============================================================================
# collect_data_ml_auto.ps1  -  non-interactive ADAPTIVE_ML runtime collection
#
# Same purpose as collect_data_ml.ps1 (run the deployed Random Forest as the
# LIVE on-device planner, producing ML_PREDICTED_* rows) but with NO Read-Host
# pauses, so it runs unattended in the background. The "is the RF actually
# planning?" check that collect_data_ml.ps1 asks the operator to eyeball is done
# automatically here: after switching to ADAPTIVE_ML it runs one probe task and
# inspects the last on-device CSV row — if its rule is not ML_PREDICTED_* it
# aborts (the model failed to load, e.g. a build without the asset).
#
# Sessions ML-A (healthy) + ML-B (battery) + ML-I (mobility): the three that
# populate the regret oracle's qualifying buckets across all three tiers, so the
# new model's rows are directly comparable to the previous model's ADAPTIVE_ML
# rows already in the dataset.
#
# Pulls to training_with_ml.csv (NOT training.csv) so the canonical set is
# untouched until the new ML rows are inspected.
#
# Usage:
#   .\evaluation\collect_data_ml_auto.ps1 -EdgeCloudHost 192.168.2.13
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
$TASK_DELAY_MS  = 3000
$VIDEO_DELAY_MS = 6000
$EDGE_URL  = "http://${EdgeCloudHost}:${EdgePort}"
$CLOUD_URL = "http://${EdgeCloudHost}:${CloudPort}"
$CSV = "/storage/emulated/0/Android/data/$PKG/files/mocca-metrics.csv"

function Section($t){Write-Host "";Write-Host ("="*60) -ForegroundColor Cyan;Write-Host "  $t" -ForegroundColor Cyan;Write-Host ("="*60) -ForegroundColor Cyan}
function Step($m){Write-Host "  >> $m" -ForegroundColor DarkYellow}
function Ok($m){Write-Host "  OK  $m" -ForegroundColor Green}
function Warn($m){Write-Host "  WARN  $m" -ForegroundColor Red}
function Broadcast($a,$e=""){Invoke-Expression "adb shell am broadcast -n $PKG/.AutoRunReceiver -a $a $e" | Out-Null}
function Run-Task($task,$count,$delayMs=$TASK_DELAY_MS){
    $w=[math]::Ceiling($count*$delayMs/1000)+6
    Step "run $task x$count (waiting ${w}s)"
    Broadcast $ACTION_RUN "--es task $task --ei count $count --el delay_ms $delayMs"
    Start-Sleep -Seconds $w
}
function Assert-Servers{
    $e=$false;$c=$false
    try{$e=((curl.exe -s -o /dev/null -w "%{http_code}" "$EDGE_URL/health") -eq "200")}catch{}
    try{$c=((curl.exe -s -o /dev/null -w "%{http_code}" "$CLOUD_URL/health") -eq "200")}catch{}
    if(-not($e -and $c)){Warn "servers unhealthy (edge=$e cloud=$c)";exit 1}
    Ok "Edge + Cloud healthy"
}
function Last-Rule{
    $line = adb shell "tail -n 1 $CSV" 2>$null
    # rule column position: split CSV line; header index 8 (0-based) per schema
    $cols = $line -split ','
    return $cols[8]
}

Section "PRE-FLIGHT"
$device = adb devices | Select-Object -Skip 1 | Where-Object {$_ -match "device$"} | Select-Object -First 1
if(-not $device){Write-Error "No ADB device";exit 1}
Ok "Device: $device"
Assert-Servers
Step "launching app"
adb shell am start -n "$PKG/.MainActivity" | Out-Null
Start-Sleep -Seconds 4
Broadcast $ACTION_ENDPOINTS "--es edge_url $EDGE_URL --es cloud_url $CLOUD_URL"
Start-Sleep -Seconds 2
$startLines = [int]((adb shell wc -l $CSV 2>$null) -replace '\D.*','')
Write-Host "  START lines: $startLines" -ForegroundColor Gray

Section "VERIFY RF IS LIVE PLANNER"
Step "switching to ADAPTIVE_ML"
Broadcast $ACTION_MODE "--es mode ADAPTIVE_ML"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
Step "probe task to confirm the model is planning"
Broadcast $ACTION_RUN "--es task sha256 --ei count 1 --el delay_ms 1000"
Start-Sleep -Seconds 6
$rule = Last-Rule
Write-Host "  last-row rule = '$rule'" -ForegroundColor Gray
if($rule -notlike "ML_PREDICTED*"){
    Warn "RF is NOT the live planner (rule='$rule'). The installed build is missing"
    Warn "the model asset, or ADAPTIVE_ML did not take. Aborting so we don't collect"
    Warn "rule-engine rows mislabelled as an ML run."
    Broadcast $ACTION_MODE "--es mode ADAPTIVE"
    exit 2
}
Ok "RF is live (rule=$rule)"

Section "SESSION ML-A - Healthy baseline (stationary)"
Assert-Servers
Run-Task "echo" 8
Run-Task "sha256" 10
Run-Task "image-grayscale" 8
Run-Task "matrix-multiply" 8
Run-Task "video-frame-edges" 8 $VIDEO_DELAY_MS
Ok "ML-A done"

Section "SESSION ML-B - Battery sweep (forced low)"
Assert-Servers
foreach($lvl in @(28,18,12)){
    Step "battery -> $lvl%"
    adb shell dumpsys battery unplug | Out-Null
    adb shell dumpsys battery set level $lvl | Out-Null
    adb shell dumpsys battery set status 3 | Out-Null
    Start-Sleep -Seconds 8
    Run-Task "echo" 3
    Run-Task "sha256" 5
    Run-Task "image-grayscale" 5
    Run-Task "matrix-multiply" 3
    Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
}
adb shell dumpsys battery reset | Out-Null
Start-Sleep -Seconds 2
Ok "ML-B done"

Section "SESSION ML-I - Mobility sweep"
Assert-Servers
foreach($state in @("STATIONARY","WALKING","VEHICLE")){
    Step "movement -> $state"
    Broadcast $ACTION_DBG "--es movement_state $state"
    Start-Sleep -Seconds 3
    Run-Task "sha256" 4
    Run-Task "image-grayscale" 4
    Run-Task "matrix-multiply" 4
    Run-Task "video-frame-edges" 3 $VIDEO_DELAY_MS
}
Broadcast $ACTION_DBG "--es movement_state NONE"
Start-Sleep -Seconds 2
Ok "ML-I done"

Section "CLEANUP + PULL"
adb shell dumpsys battery reset | Out-Null
Broadcast $ACTION_MODE "--es mode ADAPTIVE"
Broadcast $ACTION_CLR
Start-Sleep -Seconds 2
$endLines = [int]((adb shell wc -l $CSV 2>$null) -replace '\D.*','')
Write-Host "  END lines: $endLines  (added ~$($endLines-$startLines) rows)" -ForegroundColor Gray
$out = "evaluation\data\training_with_ml_v2.csv"
adb pull $CSV $out
Ok "Saved to $out"
Write-Host ""
Write-Host "  New-model ADAPTIVE_ML session complete." -ForegroundColor Cyan
