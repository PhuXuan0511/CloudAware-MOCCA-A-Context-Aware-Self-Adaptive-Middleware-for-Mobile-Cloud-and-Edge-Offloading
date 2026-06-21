# Phase 1 — Data Collection Runbook

Goal: Generate a training CSV with enough diversity for a Random Forest
classifier to learn the offloading policy. Target ≥ 300 rows with all
7 rules represented and a balanced spread of context conditions.

## Output file

```
/storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv
```

Pulled to `evaluation/data/training.csv` after collection.

## CSV schema reminder (20 columns)

```
timestamp_iso, task_id, task_name, target, fell_back, actual_ms, result_bytes, error,
rule, battery_percent, is_charging, network_type, network_score,
rtt_ms, bandwidth_mbps, cpu_percent, is_stable,
est_local_ms, est_remote_ms, speedup, reasoning
```

`rule` and `target` are the labels for supervised learning.
The remaining numeric / categorical columns are features.

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

For each `delay`/`loss` pair, on the edge container:

```
docker exec docker-edge-server-1 tc qdisc add dev eth0 root \
    netem delay <DELAY>ms loss <LOSS>%
# wait 6 seconds for ConnectionManager TTL
Tap Grayscale × 5, Matrix × 5, Video Edges × 2
docker exec docker-edge-server-1 tc qdisc del dev eth0 root
```

Pairs to walk through:
- `100ms / 0%`   → mild lag, still passes thresholds
- `300ms / 5%`   → near unstable boundary
- `500ms / 20%`  → UNSTABLE_NETWORK fires
- `1000ms / 30%` → guaranteed Rule 2 hit + timeouts

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

Only when this is green: move to Phase 2 training.
