# =============================================================================
# verify_dataset.ps1  -  post-hoc checks on a collected MOCCA metrics CSV
#
# Split out of collect_data.ps1. Collection always writes the CSV regardless of
# what these checks say, so running them inline only made the end of a long
# session noisy without changing its outcome. Run this when you want to know
# whether a dataset is usable - before training, after re-collecting one
# session, or on an archived file.
#
# Usage:
#   .\evaluation\verify_dataset.ps1
#   .\evaluation\verify_dataset.ps1 -Path evaluation\data\training.csv
# =============================================================================

param(
    [string]$Path = "evaluation\data\training.csv"
)

function Section($title) {
    Write-Host ""
    Write-Host ("=" * 60) -ForegroundColor Cyan
    Write-Host "  $title" -ForegroundColor Cyan
    Write-Host ("=" * 60) -ForegroundColor Cyan
}

function Warn($msg) {
    Write-Host "  WARN  $msg" -ForegroundColor Red
}

function Ok($msg) {
    Write-Host "  OK  $msg" -ForegroundColor Green
}

if (-not (Test-Path $Path)) {
    Write-Error "No CSV at $Path. Run .\evaluation\collect_data.ps1 first."
    exit 1
}

$outFile = $Path

Section "VERIFICATION"

# Parse with ConvertFrom-Csv rather than splitting on commas: `reasoning` is free
# text that is quoted precisely because it contains commas, and a naive split
# shifts every column after it.
$rows = @(Import-Csv $outFile)
$totalRows = $rows.Count

# No schema check here. The notebook already refuses to load a CSV whose columns
# do not match MetricsCsvFormat.HEADER, and it does so at the point where the
# columns are actually used - duplicating it here only produced a 29-name wall
# of text whenever the file was empty or pointed at the wrong path.
if ($totalRows -eq 0) {
    Warn "$outFile has no rows - nothing to check."
    Warn "Point -Path at a collected CSV, or run .\evaluation\collect_data.ps1."
    exit 1
}

Write-Host "  Total rows      : $totalRows  (target >= 400)" -ForegroundColor $(if ($totalRows -ge 400) { "Green" } else { "Red" })

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

# Coverage of the dimensions Sessions I / J / K exist to create.
Write-Host ""
Write-Host "  Coverage of the new evaluation dimensions:" -ForegroundColor White

$mobilityStates = @($rows | Select-Object -ExpandProperty is_stable -Unique)
$mobOk = $mobilityStates.Count -ge 2
Write-Host ("    {0}  mobility: {1} distinct is_stable value(s)" -f `
    $(if ($mobOk) { "OK " } else { "LOW" }), $mobilityStates.Count) `
    -ForegroundColor $(if ($mobOk) { "Green" } else { "Red" })
if (-not $mobOk) { Warn "Session I did not vary movement state - the mobility branch is unobserved" }

$powerRows = @($rows | Where-Object { $_.measured_energy_mj -ne "" }).Count
$powerPct = [math]::Round(100 * $powerRows / [math]::Max($totalRows, 1))
$pwClr = if ($powerPct -ge 50) { "Green" } elseif ($powerPct -gt 0) { "Yellow" } else { "Red" }
Write-Host ("    {0}  measured energy: {1}/{2} rows ({3}%)" -f `
    $(if ($powerPct -gt 0) { "OK " } else { "N/A" }), $powerRows, $totalRows, $powerPct) `
    -ForegroundColor $pwClr
if ($powerPct -eq 0) {
    Warn "This device does not expose BATTERY_PROPERTY_CURRENT_NOW."
    Warn "The energy model cannot be validated - report it as unvalidated, do not"
    Warn "present modelled energy as if it were measured."
}

# Did the phone actually observe the injected degradation?
#
# rtt_ms used to be hardcoded 0, which made network_score a constant for a given
# transport: netem changed the wall clock but the policy never saw it, so
# UNSTABLE_NETWORK could not fire and every "degraded" row carried the same
# context as a healthy one. If these two collapse to a single value, the network
# sessions are decorative and the RF model has two dead feature columns.
$rttValues   = @($rows | Where-Object { $_.rtt_ms -ne "" } |
                 ForEach-Object { [double]$_.rtt_ms })
$distinctRtt = @($rttValues | Select-Object -Unique).Count
$maxRtt      = if ($rttValues) { ($rttValues | Measure-Object -Maximum).Maximum } else { 0 }
$rttOk       = ($distinctRtt -ge 3) -and ($maxRtt -ge 200)
Write-Host ("    {0}  measured RTT: {1} distinct value(s), max {2}ms" -f `
    $(if ($rttOk) { "OK " } else { "LOW" }), $distinctRtt, [math]::Round($maxRtt)) `
    -ForegroundColor $(if ($rttOk) { "Green" } else { "Red" })
if (-not $rttOk) {
    Warn "The phone did not observe varying RTT. Either netem never applied, or"
    Warn "the build on the device predates the measured-RTT probe - reinstall the app."
}

$scoreValues   = @($rows | Where-Object { $_.network_score -ne "" } |
                   ForEach-Object { [double]$_.network_score })
$minScore      = if ($scoreValues) { ($scoreValues | Measure-Object -Minimum).Minimum } else { 1 }
$scoreOk       = $minScore -lt 0.30
Write-Host ("    {0}  network score reached {1} (needs < 0.30 for UNSTABLE_NETWORK)" -f `
    $(if ($scoreOk) { "OK " } else { "LOW" }), [math]::Round($minScore, 2)) `
    -ForegroundColor $(if ($scoreOk) { "Green" } else { "Red" })

# Is the cloud measurably further away than the edge?
#
# actual_ms - server_exec_ms isolates network cost from compute, so it is
# comparable across tiers even with an unbalanced task mix. Both containers run
# on this laptop, so without the WAN baseline these two come out equal and every
# edge-vs-cloud claim in the thesis rests on nothing.
$netOverhead = @($rows |
    Where-Object { $_.server_exec_ms -ne "" -and $_.fell_back -ne "true" -and $_.error -eq "" } |
    ForEach-Object {
        [pscustomobject]@{
            Tier      = $_.executed_at.ToUpper()
            OverheadMs = [double]$_.actual_ms - [double]$_.server_exec_ms
        }
    })
if ($netOverhead) {
    Write-Host "    network overhead by tier (actual_ms - server_exec_ms):" -ForegroundColor Gray
    $byTier = @{}
    foreach ($g in ($netOverhead | Group-Object Tier)) {
        $median = ($g.Group.OverheadMs | Sort-Object)[[int]($g.Count / 2)]
        $byTier[$g.Name] = $median
        Write-Host ("      {0,-8} median {1,6}ms  (n={2})" -f `
            $g.Name, [math]::Round($median), $g.Count) -ForegroundColor Gray
    }
    if ($byTier.ContainsKey("EDGE") -and $byTier.ContainsKey("CLOUD")) {
        $gap = $byTier["CLOUD"] - $byTier["EDGE"]
        $gapOk = $gap -ge 30
        Write-Host ("    {0}  cloud is {1}ms further than edge" -f `
            $(if ($gapOk) { "OK " } else { "LOW" }), [math]::Round($gap)) `
            -ForegroundColor $(if ($gapOk) { "Green" } else { "Red" })
        if (-not $gapOk) {
            Warn "Edge and cloud are not distinguishable by network cost."
            Warn "Re-run with -CloudRttMs 80 (default) and confirm tc applied to the cloud."
        }
    }
}

$sizeVariety = @($rows | Group-Object task_name | Where-Object {
    ($_.Group | Select-Object -ExpandProperty input_size_bytes -Unique).Count -gt 1
}).Count
$szClr = if ($sizeVariety -ge 3) { "Green" } else { "Yellow" }
Write-Host ("    {0}  payload sweep: {1} task type(s) with >1 size" -f `
    $(if ($sizeVariety -ge 3) { "OK " } else { "LOW" }), $sizeVariety) -ForegroundColor $szClr
if ($sizeVariety -lt 3) { Warn "Session J did not vary payload size - transmission cost stays confounded with compute" }

Write-Host ""
$underRep = $dist | Where-Object { $minCounts[$_.Name] -and $_.Count -lt $minCounts[$_.Name] }
$allOk    = ($totalRows -ge 400) -and (-not $underRep) -and ($fbPct -le 20) -and ($mmPct -le 5)

if ($allOk) {
    Write-Host "  All checks passed. Proceed to Phase 2 training notebook." -ForegroundColor Cyan
} else {
    Write-Host "  Issues found above. Fix and re-run flagged sessions before training." -ForegroundColor Red
}
