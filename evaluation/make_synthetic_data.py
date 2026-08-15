#!/usr/bin/env python3
"""
Generate a SYNTHETIC MOCCA metrics CSV for developing the ML pipeline.

    python evaluation/make_synthetic_data.py

Writes `evaluation/data/synthetic-training.csv` in the exact 29-column schema
that `MetricsCsvFormat.HEADER` emits, so the Phase 2 notebook can be run,
debugged, and profiled end to end without a phone, a laptop-side testbed, or a
three-hour collection session.

WHAT THIS IS FOR
----------------
Checking that the pipeline works: that the notebook parses the schema, that
feature engineering produces the 12 columns `RandomForestPolicy.FEATURE_ORDER`
expects, that training converges, that the JSON export round-trips, and that the
plots render. It is a test fixture.

WHAT THIS IS NOT FOR
--------------------
Reporting as an experimental result. These rows are drawn from the middleware's
own cost model plus noise, so they cannot evaluate that cost model — a model
fitted here is measuring the code's self-consistency, not its behaviour on a real
device over a real network. Any accuracy figure obtained from this file describes
the generator, not the system.

Three independent markers make that hard to lose track of:

  1. the filename is `synthetic-training.csv`, not `training.csv`
  2. every `task_id` is prefixed `synth-`
  3. every row's `debug_overrides` column begins with `SYNTHETIC`

Delete the file once real collection has run. If a number from it ever reaches a
results table, the markers above are how you will notice.

FIDELITY
--------
The estimator and policy code below is a line-for-line port of
`LatencyEstimator`, `EnergyEstimator`, `ExecutionTimeEstimator`,
`FeatureExtractor`, and `OffloadingPolicy`. Keeping them in sync matters: if the
port drifts, the generated `rule` column stops matching the context columns and
the notebook trains on relationships the real system does not have. The port is
covered by `tests/test_synthetic_generator.py`, which checks it against the same
rule-ordering the Kotlin tests assert.
"""

from __future__ import annotations

import argparse
import csv
import math
import random
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# ─── Schema ──────────────────────────────────────────────────────────────────
# Must stay identical to MetricsCsvFormat.HEADER.

HEADER = [
    "timestamp_iso", "task_id", "task_name", "target", "fell_back", "actual_ms",
    "result_bytes", "error", "rule", "battery_percent", "is_charging",
    "network_type", "network_score", "rtt_ms", "bandwidth_mbps", "cpu_percent",
    "is_stable", "est_local_ms", "est_remote_ms", "est_local_energy_mj",
    "est_remote_energy_mj", "speedup", "executed_at", "server_exec_ms",
    "measured_power_mw", "measured_energy_mj", "input_size_bytes",
    "debug_overrides", "reasoning",
]

SYNTHETIC_MARKER = "SYNTHETIC"

# ─── Constants ported from the Kotlin estimators ─────────────────────────────

BASELINE_MS = {"LIGHT": 50.0, "MEDIUM": 300.0, "HEAVY": 2000.0}
MIN_HEADROOM = 0.05
SERVER_SPEEDUP = 0.3
SERVER_QUEUE_MS = 30.0
MOBILITY_RETRY_PENALTY_MS = 200.0
MBPS_TO_BYTES_PER_MS = 125.0

CPU_POWER_MW = 800.0
RADIO_TX_POWER_MW = 1500.0
RADIO_IDLE_POWER_MW = 50.0

# The NONE entry is Kotlin's `Float.MAX_VALUE / 4f` offline sentinel. The
# notebook treats est_remote_ms >= 1e9 as "no remote path existed" rather than a
# real estimate, so the exact magnitude matters less than staying above that.
OFFLINE_SENTINEL_MS = 8.507059173023462e37
DEFAULT_RTT_MS = {"FIVE_G": 20.0, "WIFI": 15.0, "LTE": 50.0, "NONE": OFFLINE_SENTINEL_MS}
DEFAULT_BW_MBPS = {"FIVE_G": 200.0, "WIFI": 80.0, "LTE": 30.0, "NONE": 0.001}

# FeatureExtractor
MAX_SIGNAL_LEVEL = 4
GREAT_BANDWIDTH_MBPS = 100.0
W_TYPE, W_SIGNAL, W_BANDWIDTH = 0.4, 0.3, 0.3
CHARGING_BONUS = 0.2
THERMAL_CLEAN_C = 40.0
THERMAL_RANGE_C = 10.0
GOOD_RTT_MS = 80.0
BAD_RTT_MS = 500.0
TYPE_BASE = {"FIVE_G": 1.0, "WIFI": 0.95, "LTE": 0.6, "NONE": 0.0}
MOBILITY_SCORE = {"STATIONARY": 1.0, "WALKING": 0.6, "VEHICLE": 0.2}

# OffloadingPolicy
LATENCY_WEIGHT, ENERGY_WEIGHT = 0.5, 0.5
UNSTABLE_NETWORK_THRESHOLD = 0.3
GOOD_BANDWIDTH_THRESHOLD = 0.6
LOW_BATTERY_PERCENT = 30
MIN_COMPUTE_SPEEDUP = 1.5
MIN_EXEC_TIME_MS = 1.0
COST_MARGIN = 0.05
NETWORK_OK_THRESHOLD = 0.6

REMOTE_TIMEOUT_MS = 10_000

# Transient remote failures that happen even on a healthy link — a dropped
# connection, a server hiccup, an OkHttp connect timeout. Real collection never
# comes back with a 0% fallback rate, and the notebook's fallback-by-condition
# section needs a non-empty numerator to say anything at all.
BASE_TRANSIENT_FAILURE = 0.02

# ─── Task catalogue (mirrors DemoTasks / shared/tasks/registry.py) ────────────

COMPLEXITY = {
    "echo": "LIGHT",
    "sha256": "LIGHT",
    "image-grayscale": "MEDIUM",
    "matrix-multiply": "HEAVY",
    "video-frame-edges": "HEAVY",
}


def payload_bytes(task: str, size: int | None, rng: random.Random) -> int:
    """Payload size for a task, honouring the size sweep in Session J."""
    if task == "echo":
        return 11
    if task == "sha256":
        return size or 1024
    if task == "image-grayscale":
        side = size or 512
        # JPEG at quality 80 lands near 0.115 B/px for the generated test image.
        return int(side * side * 0.115 * rng.uniform(0.92, 1.08))
    if task == "matrix-multiply":
        n = size or 32
        return n * n * 4
    if task == "video-frame-edges":
        return rng.randint(500_000, 1_000_000)
    raise ValueError(f"unknown task {task}")


def result_bytes(task: str, payload: int, rng: random.Random) -> int:
    if task == "echo":
        return payload
    if task == "sha256":
        return 32
    if task == "image-grayscale":
        return int(payload * rng.uniform(0.55, 0.85))
    if task == "matrix-multiply":
        return payload
    return int(payload * rng.uniform(0.10, 0.25))


# ─── Context ─────────────────────────────────────────────────────────────────


@dataclass
class Context:
    network_type: str
    rtt_ms: float
    bandwidth_mbps: float
    signal_level: int
    cpu_percent: float
    battery_percent: int
    is_charging: bool
    temperature_c: float
    movement: str

    @property
    def is_stable(self) -> bool:
        return self.movement == "STATIONARY"


def link_health(rtt_ms: float) -> float:
    """FeatureExtractor.linkHealth — 1 below GOOD, falling to 0 at BAD."""
    if rtt_ms <= 0:
        return 1.0
    excess = (rtt_ms - GOOD_RTT_MS) / (BAD_RTT_MS - GOOD_RTT_MS)
    return 1.0 - min(max(excess, 0.0), 1.0)


def network_score(c: Context) -> float:
    signal_norm = min(max(c.signal_level, 0), MAX_SIGNAL_LEVEL) / MAX_SIGNAL_LEVEL
    bw_norm = min(max(c.bandwidth_mbps / GREAT_BANDWIDTH_MBPS, 0.0), 1.0)
    capability = (
        W_TYPE * TYPE_BASE[c.network_type]
        + W_SIGNAL * signal_norm
        + W_BANDWIDTH * bw_norm
    )
    return capability * link_health(c.rtt_ms)


def cpu_load_score(c: Context) -> float:
    return min(max(1.0 - c.cpu_percent / 100.0, 0.0), 1.0)


def battery_score(c: Context) -> float:
    level = c.battery_percent / 100.0
    bonus = CHARGING_BONUS if c.is_charging else 0.0
    thermal = min(max((c.temperature_c - THERMAL_CLEAN_C) / THERMAL_RANGE_C, 0.0), 1.0)
    return min(max(level + bonus - thermal, 0.0), 1.0)


# ─── Estimators ──────────────────────────────────────────────────────────────


def effective_rtt(c: Context) -> float:
    return c.rtt_ms if c.rtt_ms > 0 else DEFAULT_RTT_MS[c.network_type]


def effective_bw(c: Context) -> float:
    return c.bandwidth_mbps if c.bandwidth_mbps > 0 else DEFAULT_BW_MBPS[c.network_type]


@dataclass
class Analysis:
    local_latency_ms: float
    remote_latency_ms: float
    local_energy_mj: float
    remote_energy_mj: float
    local_exec_ms: float
    remote_exec_ms: float
    speedup: float


def analyse(task: str, payload: int, c: Context,
            debug_remote_energy: float | None = None) -> Analysis:
    complexity = COMPLEXITY[task]
    baseline = BASELINE_MS[complexity]
    headroom = max(cpu_load_score(c), MIN_HEADROOM)
    mobility = MOBILITY_SCORE[c.movement]

    rtt = effective_rtt(c)
    bw = effective_bw(c)
    tx_ms = payload / (bw * MBPS_TO_BYTES_PER_MS)
    server_exec_ms = baseline * SERVER_SPEEDUP

    local_latency = baseline / headroom
    remote_latency = (
        rtt + tx_ms + server_exec_ms + SERVER_QUEUE_MS
        + (1.0 - mobility) * MOBILITY_RETRY_PENALTY_MS
    )

    local_exec = baseline / headroom
    remote_exec = server_exec_ms + SERVER_QUEUE_MS

    local_energy = CPU_POWER_MW * local_exec / 1000.0
    wait_ms = rtt + server_exec_ms + SERVER_QUEUE_MS
    remote_energy = (
        RADIO_TX_POWER_MW * tx_ms / 1000.0 + RADIO_IDLE_POWER_MW * wait_ms / 1000.0
    )
    if debug_remote_energy is not None:
        remote_energy = debug_remote_energy

    speedup = local_latency / max(remote_latency, MIN_EXEC_TIME_MS)
    return Analysis(local_latency, remote_latency, local_energy, remote_energy,
                    local_exec, remote_exec, speedup)


# ─── Policy (port of OffloadingPolicy.evaluate) ───────────────────────────────


def pick_remote_target(c: Context, score: float) -> str:
    return "EDGE" if (c.is_stable and score >= NETWORK_OK_THRESHOLD) else "CLOUD"


def evaluate(task: str, c: Context, a: Analysis) -> tuple[str, str, str]:
    """Returns (target, rule, reasoning), in the same priority order as Kotlin."""
    complexity = COMPLEXITY[task]
    score = network_score(c)

    if c.network_type == "NONE":
        return "LOCAL", "OFFLINE", "offline - no network connection"

    if score < UNSTABLE_NETWORK_THRESHOLD:
        return "LOCAL", "UNSTABLE_NETWORK", (
            f"network score {score:.2f} < {UNSTABLE_NETWORK_THRESHOLD:.2f} - "
            "unstable network, run locally to avoid timeout")

    if a.speedup < MIN_COMPUTE_SPEEDUP:
        return "LOCAL", "COMPUTE_FLOOR_NOT_MET", (
            f"compute speedup {a.speedup:.2f}x < floor {MIN_COMPUTE_SPEEDUP:.2f}x "
            f"(local {a.local_exec_ms:.0f}ms vs remote {a.remote_exec_ms:.0f}ms) - "
            "network overhead not worth paying")

    if complexity == "LIGHT":
        t = pick_remote_target(c, score)
        return t, "LATENCY_SENSITIVE", (
            f"latency-sensitive: LIGHT task, picking {t} for low RTT "
            f"(remote {a.remote_latency_ms:.0f}ms vs local {a.local_latency_ms:.0f}ms)")

    if (not c.is_charging and c.battery_percent < LOW_BATTERY_PERCENT
            and a.remote_energy_mj < a.local_energy_mj):
        t = pick_remote_target(c, score)
        return t, "LOW_BATTERY_OFFLOAD", (
            f"low battery {c.battery_percent}%: offload saves "
            f"{a.local_energy_mj - a.remote_energy_mj:.0f}mJ "
            f"(remote {a.remote_energy_mj:.1f}mJ < local {a.local_energy_mj:.1f}mJ) -> {t}")

    if complexity == "HEAVY" and score >= GOOD_BANDWIDTH_THRESHOLD:
        t = pick_remote_target(c, score)
        return t, "HEAVY_COMPUTE_GOOD_BANDWIDTH", (
            f"HEAVY task + network score {score:.2f} >= {GOOD_BANDWIDTH_THRESHOLD:.2f}: "
            f"offload to {t} (speedup {a.speedup:.1f}x)")

    local_lat = LATENCY_WEIGHT * a.local_latency_ms
    local_eng = ENERGY_WEIGHT * a.local_energy_mj
    local_cost = local_lat + local_eng
    remote_lat = LATENCY_WEIGHT * a.remote_latency_ms
    remote_eng = ENERGY_WEIGHT * a.remote_energy_mj
    remote_cost = remote_lat + remote_eng

    local_wins = local_cost <= remote_cost * (1.0 + COST_MARGIN)
    t = "LOCAL" if local_wins else pick_remote_target(c, score)
    return t, "BALANCED_COST", (
        f"balanced cost: local={local_cost:.1f} (lat {local_lat:.1f} + eng {local_eng:.1f}), "
        f"remote={remote_cost:.1f} (lat {remote_lat:.1f} + eng {remote_eng:.1f}), "
        f"margin {COST_MARGIN * 100:.0f}% (speedup {a.speedup:.1f}x) -> {t}")


# ─── Measurement simulation ──────────────────────────────────────────────────
#
# The estimators above are what the phone *predicts*. What it *records* is a
# noisy, right-skewed realisation of that — real latency distributions have a
# long right tail from scheduler jitter, GC pauses and TCP retransmits, which a
# symmetric error term would not reproduce.


def realise(estimate_ms: float, rng: random.Random, jitter: float = 0.18) -> float:
    return estimate_ms * math.exp(rng.gauss(0.0, jitter)) + rng.uniform(2.0, 14.0)


def sample_power_mw(target: str, cpu_percent: float, rng: random.Random) -> float:
    """Whole-device draw: screen + idle floor, plus CPU or radio on top."""
    base = 420.0 + cpu_percent * 3.2
    extra = 480.0 if target == "LOCAL" else 260.0   # radio costs less than CPU here
    return max(90.0, rng.gauss(base + extra, 55.0))


# ─── Row generation ──────────────────────────────────────────────────────────


class Generator:
    def __init__(self, seed: int, start: datetime):
        self.rng = random.Random(seed)
        self.clock = start
        self.rows: list[dict] = []
        self.counter = 0

    def _tick(self, seconds: float) -> str:
        self.clock += timedelta(seconds=seconds)
        return self.clock.isoformat(timespec="milliseconds")

    def run(
        self,
        task: str,
        count: int,
        ctx: Context,
        mode: str = "ADAPTIVE",
        size: int | None = None,
        overrides: str = "",
        debug_remote_energy: float | None = None,
        edge_forward_rate: float = 0.0,
        failure_rate: float = 0.0,
    ) -> None:
        rng = self.rng
        for _ in range(count):
            c = self._jitter_context(ctx)
            payload = payload_bytes(task, size, rng)
            a = analyse(task, payload, c, debug_remote_energy)
            score = network_score(c)

            target, rule, reasoning = evaluate(task, c, a)

            # Baseline modes bypass the rule chain but still record the context
            # the rules *would* have seen — same as ExecutionProxy.
            if mode == "LOCAL_ONLY":
                target, rule = "LOCAL", "FORCED_LOCAL"
                reasoning = ("execution mode = LOCAL_ONLY - MAPE bypassed for "
                             "baseline comparison")
            elif mode == "CLOUD_ONLY":
                target, rule = "CLOUD", "FORCED_CLOUD"
                reasoning = ("execution mode = CLOUD_ONLY - MAPE bypassed, no "
                             "fallback (baseline)")

            self.counter += 1
            row = self._execute(
                task, payload, c, a, score, target, rule, reasoning, mode,
                overrides, edge_forward_rate, failure_rate,
            )
            self.rows.append(row)

    def _jitter_context(self, c: Context) -> Context:
        """Per-task variation, so a session is not 20 identical context rows."""
        rng = self.rng
        return Context(
            network_type=c.network_type,
            rtt_ms=max(0.0, c.rtt_ms * math.exp(rng.gauss(0.0, 0.12))) if c.rtt_ms else 0.0,
            bandwidth_mbps=max(0.0, rng.gauss(c.bandwidth_mbps, c.bandwidth_mbps * 0.09))
            if c.bandwidth_mbps else 0.0,
            signal_level=c.signal_level,
            cpu_percent=min(99.0, max(1.0, rng.gauss(c.cpu_percent, 6.0))),
            battery_percent=c.battery_percent,
            is_charging=c.is_charging,
            temperature_c=rng.gauss(c.temperature_c, 1.5),
            movement=c.movement,
        )

    def _execute(self, task, payload, c, a, score, target, rule, reasoning,
                 mode, overrides, edge_forward_rate, failure_rate) -> dict:
        rng = self.rng
        fell_back = False
        error = ""
        executed_at = "local"
        server_exec_ms = ""

        if target == "LOCAL":
            actual_ms = realise(a.local_latency_ms, rng)
        else:
            failed = rng.random() < max(failure_rate, BASE_TRANSIENT_FAILURE)
            if failed and mode == "CLOUD_ONLY":
                # No fallback by design — the row exists precisely so the
                # baseline's failures survive into the dataset.
                actual_ms = float(REMOTE_TIMEOUT_MS) + rng.uniform(5, 90)
                error = f"timeout after {REMOTE_TIMEOUT_MS}ms"
            elif failed:
                actual_ms = float(REMOTE_TIMEOUT_MS) + realise(a.local_latency_ms, rng)
                error = f"timeout after {REMOTE_TIMEOUT_MS}ms"
                fell_back = True
            else:
                actual_ms = realise(a.remote_latency_ms, rng)
                executed_at = target.lower()
                # Edge forwards to cloud under overload, so the tier that ran
                # the task can differ from the one the policy picked.
                if target == "EDGE" and rng.random() < edge_forward_rate:
                    executed_at = "cloud"
                    actual_ms += rng.uniform(60, 190)
                srv = BASELINE_MS[COMPLEXITY[task]] * SERVER_SPEEDUP
                server_exec_ms = f"{max(0.4, rng.gauss(srv, srv * 0.16)):.1f}"

        power = sample_power_mw(target if not fell_back else "LOCAL", c.cpu_percent, rng)
        energy = power * actual_ms / 1000.0
        out_bytes = 0 if error and not fell_back else result_bytes(task, payload, rng)

        marker = SYNTHETIC_MARKER if not overrides else f"{SYNTHETIC_MARKER};{overrides}"

        return {
            "timestamp_iso": self._tick(rng.uniform(2.6, 3.9)),
            "task_id": f"synth-{task}-{self.counter:05d}",
            "task_name": task,
            "target": target,
            "fell_back": "true" if fell_back else "false",
            "actual_ms": str(int(round(actual_ms))),
            "result_bytes": str(out_bytes),
            "error": error,
            "rule": rule,
            "battery_percent": str(c.battery_percent),
            "is_charging": "true" if c.is_charging else "false",
            "network_type": c.network_type,
            "network_score": f"{score:.3f}",
            "rtt_ms": f"{c.rtt_ms:.1f}",
            "bandwidth_mbps": f"{c.bandwidth_mbps:.1f}",
            "cpu_percent": f"{c.cpu_percent:.1f}",
            "is_stable": "true" if c.is_stable else "false",
            "est_local_ms": f"{a.local_latency_ms:.1f}",
            "est_remote_ms": f"{a.remote_latency_ms:.1f}",
            "est_local_energy_mj": f"{a.local_energy_mj:.1f}",
            "est_remote_energy_mj": f"{a.remote_energy_mj:.1f}",
            "speedup": f"{a.speedup:.3f}",
            "executed_at": executed_at,
            "server_exec_ms": server_exec_ms,
            "measured_power_mw": f"{power:.1f}",
            "measured_energy_mj": f"{energy:.1f}",
            "input_size_bytes": str(payload),
            "debug_overrides": marker,
            "reasoning": reasoning,
        }


# ─── Session script (mirrors evaluation/collect_data.ps1) ─────────────────────

ALL_TASKS = ["echo", "sha256", "image-grayscale", "matrix-multiply", "video-frame-edges"]


def healthy_wifi(rng: random.Random) -> Context:
    return Context(
        network_type="WIFI", rtt_ms=rng.uniform(9, 17),
        bandwidth_mbps=rng.uniform(70, 92), signal_level=4,
        cpu_percent=18.0, battery_percent=rng.randint(72, 94),
        is_charging=False, temperature_c=31.0, movement="STATIONARY",
    )


def build(seed: int) -> Generator:
    g = Generator(seed, datetime(2026, 8, 16, 9, 0, tzinfo=timezone.utc))
    rng = g.rng

    # A — healthy baseline
    for task, n in zip(ALL_TASKS, [8, 10, 8, 8, 10]):
        g.run(task, n, healthy_wifi(rng))

    # B — battery sweep, with remote energy forced cheap so the LOW_BATTERY
    #     energy gate is actually reachable (collect_data.ps1 FIX-B).
    for level in (28, 25, 22, 18, 15, 12):
        c = healthy_wifi(rng)
        c.battery_percent, c.is_charging = level, False
        for task, n in zip(ALL_TASKS, [3, 5, 5, 3, 4]):
            g.run(task, n, c, overrides=f"battery={level};remote_energy_mj=50.0",
                  debug_remote_energy=50.0)

    # C — network degradation. Delay is what the phone's /health probe observes;
    #     loss shows up as probe timeouts, which is why the severe steps report
    #     RTT at the 1500ms probe ceiling rather than the injected delay.
    for delay, loss in ((100, 0), (300, 5), (500, 20), (1000, 30)):
        c = healthy_wifi(rng)
        c.rtt_ms = delay + rng.uniform(8, 22)
        if loss >= 20:
            c.rtt_ms = 1500.0
        c.bandwidth_mbps *= 1.0 - min(loss / 100.0, 0.6)
        for task, n in zip(ALL_TASKS, [5, 5, 5, 5, 4]):
            g.run(task, n, c, failure_rate=min(loss / 100.0, 0.28))

    # D — offline
    c = healthy_wifi(rng)
    c.network_type, c.rtt_ms, c.bandwidth_mbps, c.signal_level = "NONE", 0.0, 0.0, 0
    for task in ALL_TASKS:
        g.run(task, 10, c)

    # E — CPU stress: 30 background matrix tasks, then light tasks measured
    c = healthy_wifi(rng)
    c.cpu_percent = 78.0
    c.temperature_c = 37.0
    g.run("matrix-multiply", 30, c)
    g.run("sha256", 20, c)
    g.run("echo", 20, c)

    # F / G — baselines
    for task, n in zip(ALL_TASKS, [8, 10, 8, 8, 10]):
        g.run(task, n, healthy_wifi(rng), mode="LOCAL_ONLY")
    for task, n in zip(ALL_TASKS, [8, 10, 8, 8, 10]):
        g.run(task, n, healthy_wifi(rng), mode="CLOUD_ONLY", failure_rate=0.06)

    # H — LTE via hotspot
    c = healthy_wifi(rng)
    c.network_type, c.signal_level = "LTE", 3
    c.rtt_ms, c.bandwidth_mbps = rng.uniform(42, 58), rng.uniform(18, 34)
    for task, n in zip(ALL_TASKS, [8, 10, 8, 8, 10]):
        g.run(task, n, c)

    # I — mobility sweep
    for state in ("STATIONARY", "WALKING", "VEHICLE"):
        c = healthy_wifi(rng)
        c.movement = state
        for task, n in zip(["sha256", "image-grayscale", "matrix-multiply",
                            "video-frame-edges"], [4, 4, 4, 3]):
            g.run(task, n, c, overrides=f"movement_state={state}")

    # J — payload sweep
    for size in (1024, 16384, 262144, 1048576):
        g.run("sha256", 5, healthy_wifi(rng), size=size)
    for side in (128, 256, 512, 1024):
        g.run("image-grayscale", 5, healthy_wifi(rng), size=side)
    for n in (16, 32, 64, 96):
        g.run("matrix-multiply", 5, healthy_wifi(rng), size=n)

    # K — edge under contention: the queue builds, and some EDGE decisions get
    #     forwarded to the cloud by the edge's own overload check.
    c = healthy_wifi(rng)
    c.cpu_percent = 24.0
    for task, n in zip(["sha256", "image-grayscale", "matrix-multiply",
                        "video-frame-edges"], [8, 8, 8, 5]):
        g.run(task, n, c, edge_forward_rate=0.34, failure_rate=0.05)

    return g


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--seed", type=int, default=20260816)
    p.add_argument("--out", type=Path,
                   default=Path("evaluation/data/synthetic-training.csv"))
    args = p.parse_args()

    g = build(args.seed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=HEADER, quoting=csv.QUOTE_MINIMAL)
        w.writeheader()
        w.writerows(g.rows)

    dist: dict[str, int] = {}
    for r in g.rows:
        dist[r["rule"]] = dist.get(r["rule"], 0) + 1

    # ASCII only: the Windows console codepage mangles non-ASCII here, and a
    # warning banner that renders as mojibake is a warning nobody reads.
    print("=" * 68)
    print("  SYNTHETIC DATA - NOT A MEASUREMENT. Do not report as a result.")
    print("=" * 68)
    print(f"  wrote {len(g.rows)} rows -> {args.out}")
    print(f"  seed {args.seed}\n")
    print("  rule distribution:")
    for rule, n in sorted(dist.items(), key=lambda kv: -kv[1]):
        print(f"    {rule:<32} {n:>4}")
    print("\n  Every row carries debug_overrides starting 'SYNTHETIC' and a")
    print("  'synth-' task_id. Delete this file once real collection has run.")


if __name__ == "__main__":
    main()
