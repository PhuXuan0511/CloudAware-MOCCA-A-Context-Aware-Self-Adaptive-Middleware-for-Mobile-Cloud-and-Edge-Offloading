# Phase 1 — Data Collection Runbook

Goal: Generate a training CSV with enough diversity for a Random Forest
classifier to learn the offloading policy. Target ≥ 300 rows with all
7 rules represented and a balanced spread of context conditions.

## Output file

```
/storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv
```

Pulled to `evaluation/data/training.csv` after collection.

## CSV schema reminder (29 columns)

Defined by `MetricsCsvFormat.HEADER` in the Android app — that constant is the
single source of truth, and `MetricsCsvFormatTest` pins it.

```
timestamp_iso, task_id, task_name, target, fell_back, actual_ms, result_bytes, error,
rule, battery_percent, is_charging, network_type, network_score,
rtt_ms, bandwidth_mbps, cpu_percent, is_stable,
est_local_ms, est_remote_ms, est_local_energy_mj, est_remote_energy_mj,
speedup, executed_at, server_exec_ms,
measured_power_mw, measured_energy_mj, input_size_bytes,
debug_overrides, reasoning
```

`target` is the label for supervised learning; `rule` identifies which policy
rule produced it. The remaining numeric / categorical columns are features.

Three columns exist purely for evaluation and are **excluded from training**
(they are only known after the decision was made):

| Column | Why it matters |
|--------|----------------|
| `est_local_energy_mj` / `est_remote_energy_mj` | Energy is half of `BALANCED_COST` and gates `LOW_BATTERY_OFFLOAD`. Previously computed and discarded, which made the energy half of the cost model impossible to validate offline. |
| `executed_at` | The server's own account of which tier ran the task (`edge` / `cloud` / `local`). `edge-server` forwards to the cloud when overloaded, so `target` alone does not tell you where the work ran. |
| `server_exec_ms` | Server-measured handler time. `actual_ms - server_exec_ms` isolates network overhead from compute. |
| `debug_overrides` | Which debug overrides were in force, e.g. `remote_energy_mj=50.0`. Session B sets this to force `LOW_BATTERY_OFFLOAD` to fire, which writes a **synthetic** value into `est_remote_energy_mj`. The notebook excludes these rows from cost-model validation; without the marker they are indistinguishable from real estimates. |
| `measured_power_mw` / `measured_energy_mj` | Whole-device power from `BATTERY_PROPERTY_CURRENT_NOW` (`P = V × I`), sampled either side of each run. The **measured** counterpart to `EnergyEstimator`'s hard-coded 800/1500/50 mW coefficients. Empty on devices that do not implement the property. |
| `input_size_bytes` | Payload actually submitted. Session J varies size within a task type, so it can no longer be inferred from `task_name`. |

### Reading the measured energy columns honestly

Three limits apply to every number derived from them, and belong in the thesis
next to the number itself:

1. **Whole-device draw** — screen, radio, and background work are included, so
   it is an upper bound on the task's cost, not an attribution to the task.
2. **~1 Hz sampling** — tasks shorter than ~500 ms are measured poorly; the
   notebook filters them out of the energy analysis.
3. **Not universally supported** — some devices return 0 or `Integer.MIN_VALUE`
   for `CURRENT_NOW`. The collector treats both as "unsupported" and leaves the
   columns empty rather than logging a fabricated zero.

What they support is a claim that the model **tracks** measured energy, and an
empirically implied CPU coefficient. They do not support a claim of calibrated
absolute per-task energy — that needs an external power monitor.

### Schema changes

`MetricsRecorder` compares the header of any existing CSV against the current
schema on startup. If they differ it renames the old file to
`mocca-metrics-<timestamp>.csv` and starts a fresh one, so a build upgrade can
never produce a file with mixed column counts.

**A CSV collected before this schema will not load** — the notebook fails fast
with the list of missing columns rather than silently producing `NaN`s.

## Coverage targets per rule

| Rule | Min samples | Notes |
|------|-------------|-------|
| OFFLINE                       | 20  | Wi-Fi off / airplane mode |
| UNSTABLE_NETWORK              | 30  | netem delay+loss injection |
| NEGLIGIBLE_SPEEDUP            | 40  | Tap Ping/Hash under normal Wi-Fi |
| LATENCY_SENSITIVE             | 20  | Hash Text under high CPU load |
| LOW_BATTERY_OFFLOAD           | 30  | adb battery level 15..29 |
| HEAVY_COMPUTE_GOOD_BANDWIDTH  | 50  | Matrix / Video on good Wi-Fi |
| BALANCED_COST                 | 50  | Grayscale at varied battery / RTT |
| **Total adaptive samples**    | **≥ 240** | |

Plus baseline modes for the Phase 2 comparison:

| Mode        | Min samples | How |
|-------------|-------------|-----|
| LOCAL_ONLY  | 30 | Switch mode in Settings, tap each of the 5 tasks ×6 |
| CLOUD_ONLY  | 30 | Same |

### Sessions I–K: closing the gaps a reviewer would find

Three dimensions the earlier runbook never varied, each of which left a claim in
the thesis unsupported by the data:

| Session | Dimension | Why it exists |
|---------|-----------|---------------|
| **I — Mobility sweep** | Forces STATIONARY / WALKING / VEHICLE | `pickRemoteTarget` routes to EDGE only when stationary, and `LatencyEstimator` adds up to 200 ms of mobility penalty. A phone on a desk always reports STATIONARY, so both paths were unobserved and the mobility claim was unfalsifiable. |
| **J — Payload sweep** | Varies payload size *within* a task type | With one fixed size per task, the transmission term (`payload / bandwidth`) is a per-task constant, perfectly confounded with compute cost. Neither can be attributed. |
| **K — Edge contention** | 8 synthetic clients saturating the edge | With one phone the executor's 4-slot semaphore never fills and `is_overloaded()` never fires, so every latency figure was measured against an idle server — the best case, not the case adaptive offloading exists for. |

Session K is **contention emulation, not multi-user evaluation.** The synthetic
clients are processes on the collecting machine, not devices with their own
radios and mobility. It establishes that the policy responds to server-side
load; it does not establish that the system scales to N users. Word the thesis
accordingly — a reviewer will ask.

## Collection sessions

Each session ≈ 15 minutes. Stagger across days if needed to vary natural
context (battery temperature, ambient Wi-Fi load, etc.).

### Session A — Adaptive, healthy baseline (40 samples)

Mode: ADAPTIVE. Battery 75 %+. Wi-Fi normal. Phone idle on a desk.

```
Reset Stats
Tap Ping × 8
Tap Hash Text × 8
Tap Grayscale Photo × 8
Tap Matrix Math × 8
Tap Video Edges × 8
```

Expected rule mix: mostly NEGLIGIBLE_SPEEDUP, HEAVY_COMPUTE_GOOD_BANDWIDTH,
BALANCED_COST.

### Session B — Adaptive, battery sweep (60 samples)

For each battery level `[28, 25, 22, 18, 15, 12]`:

```
adb shell dumpsys battery unplug
adb shell dumpsys battery set level <N>
adb shell dumpsys battery set status 3
# wait 4 seconds for the collector to tick
Tap Grayscale Photo × 5
Tap Matrix Math × 5
```

Then `adb shell dumpsys battery reset`.

Expected: LOW_BATTERY_OFFLOAD fires at <30 %, BALANCED_COST otherwise.

### Session C — Adaptive, network degradation sweep (60 samples)

Shaping is applied to **both** containers, not just the edge. A degraded access
link slows every remote path; shaping only the edge would instead emulate "the
edge node broke" and push each degraded decision to a still-pristine cloud.

For each `delay`/`loss` pair:

```
docker exec <edge>  tc qdisc add dev eth0 root netem delay <DELAY>ms loss <LOSS>%
docker exec <cloud> tc qdisc add dev eth0 root netem delay <DELAY+80>ms loss <LOSS>%
# wait 10 seconds — the phone re-probes RTT on a 5s TTL and must see the change
Tap Grayscale × 5, Matrix × 5, Video Edges × 2
# restore: edge unshaped, cloud back to its 80ms WAN baseline
```

Pairs to walk through:
- `100ms / 0%`   → mild lag, still passes thresholds
- `300ms / 5%`   → near unstable boundary
- `500ms / 20%`  → UNSTABLE_NETWORK fires
- `1000ms / 30%` → guaranteed Rule 2 hit + timeouts

**Why this only works from the measured-RTT build onwards.** `network_score` is
computed from the transport type, the cellular signal level, and
`linkDownstreamBandwidthKbps`. None of those move when the *path* degrades — an
injected second of delay leaves all three identical. Until `NetworkCollector`
started reading a timed `/health` probe, `rtt_ms` was hardcoded to 0, every
Wi-Fi row scored in `[0.68, 0.98]` regardless of conditions, and
`UNSTABLE_NETWORK` (threshold 0.30) was unreachable. This session collected
healthy-context rows labelled as degraded, and the rule's near-zero row count
was the symptom.

The score is now `capability × linkHealth(rtt)`, where `linkHealth` is 1 below
80 ms and falls linearly to 0 at 500 ms — so the four steps above straddle the
policy thresholds instead of sitting above them.

### Session D — Adaptive, offline (20 samples)

```
Phone Wi-Fi OFF
# wait 3 seconds
Tap every task × 4
Phone Wi-Fi ON
```

Expected: Rule 1 OFFLINE fires for all.

### Session E — Adaptive, CPU stress (20 samples)

Open Chrome with 10+ tabs OR start a 3D mobile game in background.

```
# wait 5 seconds for cpu tick
Tap Hash Text × 10
Tap Ping × 10
```

Expected: LATENCY_SENSITIVE fires for LIGHT tasks (because cpuLoadScore
drops → T_local rises → speedup ≥ 1.5 so Rule 3 stops catching them).

### Session F — LOCAL_ONLY baseline (30 samples)

```
Settings → Execution mode → Local-only → Save
Reset Stats
Tap every task × 6
```

Every row tagged `rule=FORCED_LOCAL`. Will be filtered out when training
the rule classifier but kept for the latency / energy comparison plots.

### Session G — CLOUD_ONLY baseline (30 samples)

```
Settings → Execution mode → Cloud-only → Save
Reset Stats
Tap every task × 6
```

Will record some failures (no fallback) — useful to show resilience gap
in the thesis chapter.

Restore mode after: `Settings → Adaptive → Save`.

## Pulling the CSV

After all sessions:

```powershell
adb shell "run-as com.thesis.middleware cat files/mocca-metrics.csv" \
  > evaluation/data/training.csv
```

Sanity-check row count and rule distribution:

```powershell
# Total rows (excluding header)
(Get-Content evaluation/data/training.csv).Length - 1

# Rule distribution
Get-Content evaluation/data/training.csv | Select-Object -Skip 1 |
  ForEach-Object { ($_ -split ',')[8] } | Group-Object | Sort-Object Count -Descending
```

Expected: ≥ 240 adaptive rows (rule != FORCED_*) + ≥ 60 baseline rows,
with no single rule below its min sample count.

## Quality checklist before training

- [ ] All 7 adaptive rules present in the rule distribution
- [ ] No rule under-represented (< 20 samples) — re-run sessions if so
- [ ] Battery values cover both < 30 % and ≥ 30 %
- [ ] Network scores span < 0.30, 0.30–0.60, ≥ 0.60
- [ ] Both LOCAL_ONLY and CLOUD_ONLY baseline rows present
- [ ] No malformed rows (CSV escape errors, missing columns)
- [ ] Fallback rate ≤ 10 % (`collect_data.ps1` reports it)
- [ ] `executed_at` matches `target` on ≥ 95 % of remote rows (see below)
- [ ] Baseline sessions F/G run under **similar conditions** to the adaptive
      sessions — the regret analysis in notebook section 15 needs at least two
      tiers observed within the same (task, network, battery, CPU) bucket

Only when this is green: move to Phase 2 training.

## Watch out: the edge forwards to the cloud under overload

`edge-server` forwards a request to the cloud whenever
`ResourceMonitor.is_overloaded()` is true — CPU > 85 % **or** memory > 80 %.

`ResourceMonitor` reads the container's own cgroup budget
(`shared/resources/container_metrics.py`), and `docker-compose.yml` sets
`mem_limit: 2g` / `cpus: 2.0` so that budget exists. Previously it read the
**host's** memory via psutil, so a laptop above 80 % RAM made the edge declare
itself permanently overloaded and forward *every* request to the cloud — while
the phone still recorded `target=EDGE`.

Verify before a run — `metrics_source.memory` must not be `psutil`:

```powershell
Invoke-RestMethod http://localhost:8001/api/v1/status | ConvertTo-Json
```

```jsonc
{
  "cpu_percent": 3.2,
  "memory_used_percent": 18.4,      // of the container's 2 GiB, not the laptop
  "metrics_source": { "cpu": "cgroup", "memory": "cgroup-v2" },
  "overloaded": false
}
```

`collect_data.ps1` checks this in pre-flight and pauses if the edge is already
overloaded. Notebook section 4 cross-tabulates `target` against `executed_at`
afterwards. If a large fraction of EDGE rows report `executed_at=cloud`, the
edge latency numbers are measuring an edge→cloud relay — raise
`MOCCA_OVERLOAD_MEM_PERCENT` or the `mem_limit`, and re-run those sessions.
