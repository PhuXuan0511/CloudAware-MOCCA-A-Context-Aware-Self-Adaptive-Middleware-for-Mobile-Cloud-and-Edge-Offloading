import os

import psutil

from shared.resources import read_cpu_percent, read_memory


class ResourceMonitor:
    """
    Monitors CPU and memory utilization of this edge node.
    Used by the broker to decide whether to forward tasks to the cloud.

    Readings come from the container's own cgroup accounting, not the host's
    `/proc`. `psutil.virtual_memory()` inside a container reports the **host's**
    memory, so an edge running on a developer laptop above 80% RAM used to
    declare itself permanently overloaded and forward every request to the
    cloud — while the phone still recorded `target=EDGE` in the evaluation CSV.
    See `shared/resources/container_metrics.py`.

    Thresholds are overridable via environment variables so a collection run can
    be pinned without editing code:

        MOCCA_OVERLOAD_CPU_PERCENT   (default 85)
        MOCCA_OVERLOAD_MEM_PERCENT   (default 80)
    """

    OVERLOAD_CPU_THRESHOLD = float(os.getenv("MOCCA_OVERLOAD_CPU_PERCENT", "85"))
    OVERLOAD_MEM_THRESHOLD = float(os.getenv("MOCCA_OVERLOAD_MEM_PERCENT", "80"))

    def is_overloaded(self) -> bool:
        cpu, _ = read_cpu_percent(interval=0.1)
        mem = read_memory()
        return (
            cpu > self.OVERLOAD_CPU_THRESHOLD
            or mem.percent > self.OVERLOAD_MEM_THRESHOLD
        )

    def get_status(self) -> dict:
        cpu, cpu_source = read_cpu_percent(interval=0.1)
        mem = read_memory()
        return {
            "cpu_percent": round(cpu, 1),
            "memory_used_percent": round(mem.percent, 1),
            "memory_available_mb": mem.available_mb,
            "disk_usage_percent": psutil.disk_usage("/").percent,
            # Surfaced so a collection run can verify it is reading the
            # container's budget and not the host's.
            "metrics_source": {"cpu": cpu_source, "memory": mem.source},
            "thresholds": {
                "cpu_percent": self.OVERLOAD_CPU_THRESHOLD,
                "memory_percent": self.OVERLOAD_MEM_THRESHOLD,
            },
            "overloaded": (
                cpu > self.OVERLOAD_CPU_THRESHOLD
                or mem.percent > self.OVERLOAD_MEM_THRESHOLD
            ),
        }
