import psutil


class ResourceMonitor:
    """Monitors cloud node resource utilization."""

    def get_status(self) -> dict:
        mem = psutil.virtual_memory()
        return {
            "cpu_percent": psutil.cpu_percent(interval=0.1),
            "memory_used_percent": mem.percent,
            "memory_available_mb": mem.available // (1024 * 1024),
        }
