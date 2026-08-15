"""
Container-aware CPU and memory readings.

`psutil.virtual_memory()` and `psutil.cpu_percent()` read `/proc`, which inside a
container is the **host's** view unless the runtime masks it (Docker does not by
default). An edge node running on a laptop therefore reports the laptop's memory
pressure as its own — so a developer machine sitting above the 80% threshold makes
the edge declare itself permanently overloaded and forward every request to the
cloud.

That silently corrupted the evaluation: tasks the policy routed to EDGE actually
executed on CLOUD, with an extra hop, while the phone's CSV still recorded
`target=EDGE`.

This module reads the cgroup accounting files instead, which describe the
container's own budget, and falls back to psutil when no cgroup limit is set
(bare-metal runs, or `docker run` without `--memory`).

Supports cgroup v2 (`/sys/fs/cgroup/memory.max`) and v1
(`/sys/fs/cgroup/memory/memory.limit_in_bytes`).
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path

import psutil

# cgroup v2 unified hierarchy
_V2_ROOT = Path("/sys/fs/cgroup")
# cgroup v1 split hierarchy
_V1_MEMORY = Path("/sys/fs/cgroup/memory")
_V1_CPU = Path("/sys/fs/cgroup/cpuacct")

# A cgroup with no limit reports either the literal "max" (v2) or a sentinel
# close to 2**63 (v1). Anything above this is treated as "unlimited".
_UNLIMITED_ABOVE = 2 ** 62


@dataclass(frozen=True)
class MemoryReading:
    used_bytes: int
    limit_bytes: int
    source: str  # "cgroup-v2" | "cgroup-v1" | "psutil"

    @property
    def percent(self) -> float:
        if self.limit_bytes <= 0:
            return 0.0
        return 100.0 * self.used_bytes / self.limit_bytes

    @property
    def available_mb(self) -> int:
        return max(self.limit_bytes - self.used_bytes, 0) // (1024 * 1024)


def _read_int(path: Path) -> int | None:
    try:
        text = path.read_text().strip()
    except (OSError, ValueError):
        return None
    if text == "max":
        return None
    try:
        return int(text)
    except ValueError:
        return None


def read_memory() -> MemoryReading:
    """Memory usage of this container, or of the host if uncapped."""
    # cgroup v2
    limit = _read_int(_V2_ROOT / "memory.max")
    used = _read_int(_V2_ROOT / "memory.current")
    if limit is not None and used is not None and 0 < limit < _UNLIMITED_ABOVE:
        return MemoryReading(used, limit, "cgroup-v2")

    # cgroup v1
    limit = _read_int(_V1_MEMORY / "memory.limit_in_bytes")
    used = _read_int(_V1_MEMORY / "memory.usage_in_bytes")
    if limit is not None and used is not None and 0 < limit < _UNLIMITED_ABOVE:
        return MemoryReading(used, limit, "cgroup-v1")

    # No container limit in force — the host's numbers are the honest answer.
    mem = psutil.virtual_memory()
    return MemoryReading(mem.total - mem.available, mem.total, "psutil")


def _cpu_quota_cores() -> float | None:
    """CPU cores this container may use, or None when uncapped."""
    # cgroup v2: "<quota> <period>", or "max <period>"
    try:
        quota_s, period_s = (_V2_ROOT / "cpu.max").read_text().split()
        if quota_s != "max":
            period = int(period_s)
            if period > 0:
                return int(quota_s) / period
    except (OSError, ValueError):
        pass

    # cgroup v1
    quota = _read_int(Path("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"))
    period = _read_int(Path("/sys/fs/cgroup/cpu/cpu.cfs_period_us"))
    if quota is not None and period and quota > 0:
        return quota / period
    return None


def _cpu_usage_usec() -> int | None:
    """Cumulative CPU time consumed by this cgroup, in microseconds."""
    try:
        for line in (_V2_ROOT / "cpu.stat").read_text().splitlines():
            if line.startswith("usage_usec"):
                return int(line.split()[1])
    except (OSError, ValueError, IndexError):
        pass

    nanos = _read_int(_V1_CPU / "cpuacct.usage")
    return nanos // 1000 if nanos is not None else None


def read_cpu_percent(interval: float = 0.1) -> tuple[float, str]:
    """
    CPU utilisation as a percentage of this container's quota.

    Returns ``(percent, source)``. Samples the cgroup CPU accounting twice over
    ``interval`` seconds; falls back to ``psutil.cpu_percent`` when the container
    has no CPU quota.
    """
    quota = _cpu_quota_cores()
    start = _cpu_usage_usec()
    if quota is None or start is None:
        return psutil.cpu_percent(interval=interval), "psutil"

    time.sleep(interval)
    end = _cpu_usage_usec()
    if end is None:
        return psutil.cpu_percent(interval=0.0), "psutil"

    # Available CPU-microseconds in the window = interval * quota_cores.
    budget_usec = interval * 1_000_000 * quota
    if budget_usec <= 0:
        return 0.0, "cgroup"
    return min(100.0 * (end - start) / budget_usec, 100.0), "cgroup"
