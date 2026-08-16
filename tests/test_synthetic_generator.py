"""
Guards the synthetic-data generator against two failure modes.

The first is drift: `evaluation/make_synthetic_data.py` re-implements
`FeatureExtractor`, the three estimators, and `OffloadingPolicy` in Python. If
that port falls out of step with the Kotlin, the generated `rule` column stops
agreeing with the context columns beside it, and a model trained on the file
learns a decision boundary the real middleware does not have — while still
scoring well, because it is internally consistent nonsense.

The second is provenance: synthetic rows must stay identifiable as synthetic
however the file is copied, renamed, or concatenated.
"""

from __future__ import annotations

import csv
import importlib.util
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

# Loaded by path: `evaluation` is not a package, and the module name has to stay
# importable from the repo root without installing anything.
_spec = importlib.util.spec_from_file_location(
    "make_synthetic_data", ROOT / "evaluation" / "make_synthetic_data.py"
)
gen = importlib.util.module_from_spec(_spec)
sys.modules["make_synthetic_data"] = gen
_spec.loader.exec_module(gen)


def ctx(**kw):
    base = dict(
        network_type="WIFI", rtt_ms=12.0, bandwidth_mbps=80.0, signal_level=4,
        cpu_percent=20.0, battery_percent=80, is_charging=False,
        temperature_c=30.0, movement="STATIONARY",
    )
    base.update(kw)
    return gen.Context(**base)


def decide(task, c, size=None, debug_remote_energy=None):
    import random
    payload = gen.payload_bytes(task, size, random.Random(0))
    a = gen.analyse(task, payload, c, debug_remote_energy)
    return gen.evaluate(task, c, a)


# ── Schema ───────────────────────────────────────────────────────────────────


def test_header_matches_kotlin_metrics_csv_format():
    """The one contract the notebook actually parses against."""
    kt = (ROOT / "mobile/app/src/main/java/com/thesis/middleware/metrics"
          / "MetricsCsvFormat.kt").read_text(encoding="utf-8")
    start = kt.index("const val HEADER")
    literal = kt[start:kt.index("\n\n", start)]
    # The Kotlin header is a multi-part concatenated string literal; rejoin the
    # quoted fragments (odd indices after splitting on the quote character) and
    # split the result on commas to recover the column list.
    joined = "".join(seg for i, seg in enumerate(literal.split('"')) if i % 2 == 1)
    assert joined.split(",") == gen.HEADER


# ── Ported scoring ───────────────────────────────────────────────────────────


def test_link_health_matches_the_kotlin_band():
    assert gen.link_health(0.0) == 1.0        # unmeasured: no penalty
    assert gen.link_health(50.0) == 1.0       # inside the good band
    assert gen.link_health(500.0) == 0.0      # at the bad edge
    assert gen.link_health(9000.0) == 0.0     # clamped, not negative
    assert 0.0 < gen.link_health(300.0) < 1.0


def test_network_score_falls_monotonically_with_rtt():
    scores = [gen.network_score(ctx(rtt_ms=r)) for r in (12, 110, 310, 510, 1500)]
    assert scores == sorted(scores, reverse=True)
    assert scores[0] > 0.6, "healthy Wi-Fi must stay above the good-bandwidth gate"
    assert scores[-1] == 0.0


# ── Ported rule ordering ─────────────────────────────────────────────────────


def test_offline_beats_every_other_rule():
    _, rule, _ = decide("matrix-multiply", ctx(network_type="NONE", rtt_ms=0.0,
                                               bandwidth_mbps=0.0, signal_level=0))
    assert rule == "OFFLINE"


def test_unstable_network_fires_below_the_threshold():
    target, rule, _ = decide("matrix-multiply", ctx(rtt_ms=1500.0))
    assert (target, rule) == ("LOCAL", "UNSTABLE_NETWORK")


def test_compute_floor_keeps_cheap_work_local():
    # A LIGHT task over LTE cannot repay the round trip, so the guardrail must
    # cut in ahead of LATENCY_SENSITIVE.
    target, rule, _ = decide("echo", ctx(network_type="LTE", rtt_ms=50.0,
                                         bandwidth_mbps=30.0, signal_level=3))
    assert (target, rule) == ("LOCAL", "COMPUTE_FLOOR_NOT_MET")


def test_low_battery_offloads_only_when_it_saves_energy():
    low = ctx(battery_percent=15)
    # Remote energy forced below local, as Session B does.
    _, rule, _ = decide("matrix-multiply", low, debug_remote_energy=50.0)
    assert rule == "LOW_BATTERY_OFFLOAD"
    # Remote energy above local: the rule must decline and fall through.
    _, rule, _ = decide("matrix-multiply", low, debug_remote_energy=99_999.0)
    assert rule != "LOW_BATTERY_OFFLOAD"


def test_charging_disables_the_low_battery_rule():
    _, rule, _ = decide("matrix-multiply", ctx(battery_percent=15, is_charging=True),
                        debug_remote_energy=50.0)
    assert rule != "LOW_BATTERY_OFFLOAD"


def test_heavy_task_on_good_network_offloads():
    target, rule, _ = decide("matrix-multiply", ctx())
    assert (target, rule) == ("EDGE", "HEAVY_COMPUTE_GOOD_BANDWIDTH")


def test_mobility_routes_away_from_the_edge():
    # pickRemoteTarget: EDGE only while stationary; anything else is CLOUD.
    assert decide("matrix-multiply", ctx(movement="STATIONARY"))[0] == "EDGE"
    assert decide("matrix-multiply", ctx(movement="VEHICLE"))[0] == "CLOUD"


# ── Provenance ───────────────────────────────────────────────────────────────


@pytest.fixture(scope="module")
def rows():
    return gen.build(seed=1234).rows


def test_every_row_is_marked_synthetic(rows):
    assert rows, "generator produced nothing"
    assert all(r["reasoning"].startswith(f"[{gen.SYNTHETIC_MARKER}]") for r in rows)
    assert all(r["task_id"].startswith("synth-") for r in rows)


def test_debug_overrides_keeps_its_real_meaning_not_the_provenance_marker(rows):
    # Regression test for the bug this fix addresses: an earlier version put the
    # SYNTHETIC marker in debug_overrides on every row. The notebook treats any
    # non-empty debug_overrides as "estimates were forced, exclude from
    # cost-model validation" (has_debug_override) - so marking every row there
    # made `trusted` empty, which emptied every estimator-accuracy table
    # downstream and crashed the first plot that used it (NaN axis limits).
    #
    # Only genuine overrides may be non-empty, and only for the sessions that
    # generate them.
    overridden = [r for r in rows if r["debug_overrides"]]
    assert overridden, "no genuine overrides at all - Session B/I logic broke"
    assert len(overridden) < len(rows), (
        "debug_overrides is non-empty on every row again - this is the exact "
        "regression that emptied `trusted` and broke every downstream estimator "
        "and energy-validation section in the notebook"
    )
    for r in overridden:
        assert r["debug_overrides"].startswith(("battery=", "movement_state=")), (
            f"unexpected debug_overrides content: {r['debug_overrides']!r}"
        )
    # The vast majority of rows must have NO override - that is what makes them
    # usable for cost-model validation in the first place.
    assert len(overridden) / len(rows) < 0.3


def test_rows_are_writable_as_the_declared_schema(rows, tmp_path):
    out = tmp_path / "synthetic.csv"
    with out.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=gen.HEADER)
        w.writeheader()
        w.writerows(rows)
    parsed = list(csv.DictReader(out.open(encoding="utf-8")))
    assert len(parsed) == len(rows)
    assert list(parsed[0].keys()) == gen.HEADER


# ── Usability as a training fixture ──────────────────────────────────────────


def test_dataset_covers_every_rule_the_collection_script_requires(rows):
    # Same minimums collect_data.ps1 checks for, so a notebook run against this
    # file exercises the same branches a real dataset would.
    minimums = {
        "OFFLINE": 30, "UNSTABLE_NETWORK": 30, "COMPUTE_FLOOR_NOT_MET": 40,
        "LATENCY_SENSITIVE": 30, "LOW_BATTERY_OFFLOAD": 30,
        "HEAVY_COMPUTE_GOOD_BANDWIDTH": 50, "BALANCED_COST": 50,
    }
    counts: dict[str, int] = {}
    for r in rows:
        counts[r["rule"]] = counts.get(r["rule"], 0) + 1
    short = {k: counts.get(k, 0) for k, v in minimums.items() if counts.get(k, 0) < v}
    assert not short, f"under-represented rules: {short}"


def test_all_three_targets_and_both_baselines_are_present(rows):
    assert {r["target"] for r in rows} == {"LOCAL", "EDGE", "CLOUD"}
    assert {"FORCED_LOCAL", "FORCED_CLOUD"} <= {r["rule"] for r in rows}


def test_context_columns_actually_vary(rows):
    # A constant column trains to zero importance and hides a broken generator.
    for column in ("rtt_ms", "network_score", "cpu_percent", "battery_percent",
                   "input_size_bytes", "est_remote_ms"):
        assert len({r[column] for r in rows}) > 5, f"{column} barely varies"


def test_payload_size_varies_within_a_task_type(rows):
    varied = [
        t for t in {r["task_name"] for r in rows}
        if len({r["input_size_bytes"] for r in rows if r["task_name"] == t}) > 1
    ]
    assert len(varied) >= 3, "payload sweep missing; transmission cost stays confounded"


def test_baseline_failures_survive_into_the_dataset(rows):
    # CLOUD_ONLY has no fallback by design, so its failures must be recorded
    # rather than lost — otherwise the baseline looks more reliable than it is.
    assert any(r["error"] and r["rule"] == "FORCED_CLOUD" for r in rows)


def test_some_offloads_fall_back(rows):
    # A 0% fallback rate leaves the notebook's fallback analysis with an empty
    # numerator and is not something real collection produces.
    assert any(r["fell_back"] == "true" for r in rows)


def test_edge_sometimes_reports_executing_on_cloud(rows):
    # The edge forwards under overload; the decision-integrity check in both the
    # collection script and the notebook needs this case to exist.
    assert any(r["target"] == "EDGE" and r["executed_at"] == "cloud" for r in rows)


def test_generation_is_deterministic_for_a_seed():
    a = gen.build(seed=77).rows
    b = gen.build(seed=77).rows
    assert [r["rule"] for r in a] == [r["rule"] for r in b]
    assert [r["actual_ms"] for r in a] == [r["actual_ms"] for r in b]
