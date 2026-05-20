import psutil


class ResourceMonitor:
    """
    Monitors CPU, memory, and network utilization of this edge node.
    Used by the broker to decide whether to forward tasks to the cloud.
    """

    OVERLOAD_CPU_THRESHOLD = 85.0    # percent
    OVERLOAD_MEM_THRESHOLD = 80.0    # percent

    def is_overloaded(self) -> bool:
        return (
            psutil.cpu_percent(interval=0.1) > self.OVERLOAD_CPU_THRESHOLD
            or psutil.virtual_memory().percent > self.OVERLOAD_MEM_THRESHOLD
        )

    def get_status(self) -> dict:
        mem = psutil.virtual_memory()
        return {
            "cpu_percent": psutil.cpu_percent(interval=0.1),
            "memory_used_percent": mem.percent,
            "memory_available_mb": mem.available // (1024 * 1024),
            "disk_usage_percent": psutil.disk_usage("/").percent,
        }
