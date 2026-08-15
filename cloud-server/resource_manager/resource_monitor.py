from shared.resources import read_cpu_percent, read_memory


class ResourceMonitor:
    """
    Monitors cloud node resource utilization.

    Reads the container's cgroup accounting rather than the host's `/proc` —
    see `shared/resources/container_metrics.py` for why that distinction matters.
    """

    def get_status(self) -> dict:
        cpu, cpu_source = read_cpu_percent(interval=0.1)
        mem = read_memory()
        return {
            "cpu_percent": round(cpu, 1),
            "memory_used_percent": round(mem.percent, 1),
            "memory_available_mb": mem.available_mb,
            "metrics_source": {"cpu": cpu_source, "memory": mem.source},
        }
