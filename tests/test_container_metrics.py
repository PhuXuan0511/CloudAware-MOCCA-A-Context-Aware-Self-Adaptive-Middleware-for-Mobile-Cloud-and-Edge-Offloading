"""
Tests for container-aware resource readings.

The behaviour under test is the reason EDGE decisions were silently executing on
the CLOUD: `psutil.virtual_memory()` inside a container reports the *host's*
memory, so an edge on a laptop above 80% RAM declared itself permanently
overloaded. These tests pin that the cgroup limit wins when one is present, and
that psutil remains the fallback when it is not.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from shared.resources import container_metrics as cm


def write(root: Path, name: str, text: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


@pytest.fixture
def isolated_cgroups(tmp_path, monkeypatch):
    """Point every cgroup path at an empty temp dir so nothing real is read."""
    v2 = tmp_path / "v2"
    v1_mem = tmp_path / "v1-memory"
    v1_cpu = tmp_path / "v1-cpuacct"
    for d in (v2, v1_mem, v1_cpu):
        d.mkdir()
    monkeypatch.setattr(cm, "_V2_ROOT", v2)
    monkeypatch.setattr(cm, "_V1_MEMORY", v1_mem)
    monkeypatch.setattr(cm, "_V1_CPU", v1_cpu)
    return v2, v1_mem, v1_cpu


# ── Memory ───────────────────────────────────────────────────────────────────

def test_cgroup_v2_limit_takes_priority_over_the_host(isolated_cgroups):
    v2, _, _ = isolated_cgroups
    write(v2, "memory.max", str(2 * 1024**3))       # 2 GiB limit
    write(v2, "memory.current", str(512 * 1024**2))  # 512 MiB used

    reading = cm.read_memory()

    assert reading.source == "cgroup-v2"
    assert reading.percent == pytest.approx(25.0)
    assert reading.available_mb == 1536


def test_cgroup_v1_is_used_when_v2_is_absent(isolated_cgroups):
    _, v1_mem, _ = isolated_cgroups
    write(v1_mem, "memory.limit_in_bytes", str(1024**3))       # 1 GiB
    write(v1_mem, "memory.usage_in_bytes", str(768 * 1024**2))  # 768 MiB

    reading = cm.read_memory()

    assert reading.source == "cgroup-v1"
    assert reading.percent == pytest.approx(75.0)


def test_unlimited_cgroup_falls_back_to_the_host(isolated_cgroups):
    # Docker writes the literal "max" when no --memory flag is given.
    v2, _, _ = isolated_cgroups
    write(v2, "memory.max", "max")
    write(v2, "memory.current", "12345")

    assert cm.read_memory().source == "psutil"


def test_sentinel_limit_is_treated_as_unlimited(isolated_cgroups):
    # cgroup v1 uses a near-2**63 sentinel rather than a keyword.
    _, v1_mem, _ = isolated_cgroups
    write(v1_mem, "memory.limit_in_bytes", str(2**63 - 1))
    write(v1_mem, "memory.usage_in_bytes", "12345")

    assert cm.read_memory().source == "psutil"


def test_no_cgroup_files_at_all_falls_back_to_the_host(isolated_cgroups):
    reading = cm.read_memory()
    assert reading.source == "psutil"
    assert 0 <= reading.percent <= 100


def test_malformed_cgroup_file_does_not_raise(isolated_cgroups):
    v2, _, _ = isolated_cgroups
    write(v2, "memory.max", "not-a-number")
    write(v2, "memory.current", "")

    assert cm.read_memory().source == "psutil"


def test_percent_is_zero_rather_than_dividing_by_zero():
    assert cm.MemoryReading(used_bytes=10, limit_bytes=0, source="test").percent == 0.0


# ── CPU ──────────────────────────────────────────────────────────────────────

def test_cpu_percent_is_measured_against_the_container_quota(isolated_cgroups, monkeypatch):
    v2, _, _ = isolated_cgroups
    write(v2, "cpu.max", "50000 100000")   # 0.5 cores

    # Two samples 0.1s apart; consume 25_000us of a 50_000us budget => 50%.
    usages = iter([1_000_000, 1_025_000])
    monkeypatch.setattr(cm, "_cpu_usage_usec", lambda: next(usages))
    monkeypatch.setattr(cm.time, "sleep", lambda _: None)

    percent, source = cm.read_cpu_percent(interval=0.1)

    assert source == "cgroup"
    assert percent == pytest.approx(50.0)


def test_cpu_percent_is_capped_at_100(isolated_cgroups, monkeypatch):
    v2, _, _ = isolated_cgroups
    write(v2, "cpu.max", "50000 100000")
    usages = iter([0, 10_000_000])
    monkeypatch.setattr(cm, "_cpu_usage_usec", lambda: next(usages))
    monkeypatch.setattr(cm.time, "sleep", lambda _: None)

    percent, _ = cm.read_cpu_percent(interval=0.1)

    assert percent == 100.0


def test_uncapped_cpu_falls_back_to_psutil(isolated_cgroups):
    percent, source = cm.read_cpu_percent(interval=0.01)
    assert source == "psutil"
    assert 0 <= percent <= 100


# ── Integration with the edge monitor ────────────────────────────────────────

def test_edge_monitor_reports_its_metrics_source(isolated_cgroups):
    from edge_server.resource_manager.resource_monitor import ResourceMonitor

    status = ResourceMonitor().get_status()

    assert {"cpu", "memory"} == set(status["metrics_source"])
    assert status["thresholds"]["memory_percent"] == 80.0
    assert isinstance(status["overloaded"], bool)


def test_edge_monitor_is_not_overloaded_when_the_container_has_headroom(
    isolated_cgroups, monkeypatch
):
    from edge_server.resource_manager.resource_monitor import ResourceMonitor

    v2, _, _ = isolated_cgroups
    write(v2, "memory.max", str(4 * 1024**3))
    write(v2, "memory.current", str(1024**3))   # 25% used
    monkeypatch.setattr(cm, "read_cpu_percent", lambda interval=0.1: (10.0, "cgroup"))
    monkeypatch.setattr(
        "edge_server.resource_manager.resource_monitor.read_cpu_percent",
        lambda interval=0.1: (10.0, "cgroup"),
    )

    # The host may well be above 80% RAM; the container's 25% is what counts.
    assert ResourceMonitor().is_overloaded() is False


def test_edge_monitor_is_overloaded_when_the_container_itself_is_full(
    isolated_cgroups, monkeypatch
):
    from edge_server.resource_manager.resource_monitor import ResourceMonitor

    v2, _, _ = isolated_cgroups
    write(v2, "memory.max", str(1024**3))
    write(v2, "memory.current", str(int(0.95 * 1024**3)))   # 95% used
    monkeypatch.setattr(
        "edge_server.resource_manager.resource_monitor.read_cpu_percent",
        lambda interval=0.1: (10.0, "cgroup"),
    )

    assert ResourceMonitor().is_overloaded() is True
