"""
Background load generator for the edge server.

Purpose: emulate *other tenants*. The study uses a single phone, so the edge
executor's `asyncio.Semaphore(max_workers=4)` never saturates, its `/queue`
endpoint always reads 0, and `ResourceMonitor.is_overloaded()` never fires.
Every latency number is therefore measured against an idle server — the
best case, and not the case that motivates adaptive offloading.

This is **contention emulation, not multi-user evaluation**. The synthetic
clients are processes on the collecting machine, not real devices with their
own radios and mobility. State it that way in the thesis: it establishes that
the policy responds to server-side load, not that the system scales to N users.

Usage
-----
    # Saturate the edge with 8 concurrent clients for 120 seconds
    python evaluation/edge_load_generator.py --clients 8 --duration 120

    # Check what the server thinks its load is
    python evaluation/edge_load_generator.py --status-only
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import random
import sys
import time

try:
    import httpx
except ImportError:  # pragma: no cover
    sys.exit("httpx is required:  pip install httpx")

DEFAULT_EDGE = "http://localhost:8001"

# A matrix-multiply payload is the heaviest handler in the registry, so it is
# the most efficient way to occupy an executor slot.
MATRIX_N = 64


def build_request(task_id: str) -> dict:
    import struct

    floats = [random.random() for _ in range(MATRIX_N * MATRIX_N)]
    payload = struct.pack(f"{len(floats)}f", *floats)
    return {
        "task_id": task_id,
        "task_name": "matrix-multiply",
        "input_size_bytes": len(payload),
        "complexity": "HEAVY",
        # Pydantic v2 accepts base64 for bytes fields over JSON.
        "input_payload": base64.b64encode(payload).decode(),
        "context": {
            "network": {
                "type": "WIFI",
                "rtt_ms": 15.0,
                "bandwidth_mbps": 80.0,
                "signal_strength": 4,
            },
            "cpu": {"usage_percent": 50.0, "available_cores": 8, "frequency_mhz": 2400},
            "battery": {
                "level_percent": 80,
                "is_charging": True,
                "temperature_celsius": 30.0,
            },
            "location": {"latitude": 0.0, "longitude": 0.0, "accuracy": 5.0},
            "mobility": {
                "linear_acceleration_mps2": 0.1,
                "movement_state": "STATIONARY",
            },
            "timestamp": 0,
        },
    }


async def client_loop(name: str, base_url: str, stop_at: float, stats: dict) -> None:
    async with httpx.AsyncClient(timeout=30.0) as http:
        n = 0
        while time.time() < stop_at:
            try:
                r = await http.post(f"{base_url}/api/v1/offload", json=build_request(f"{name}-{n}"))
                if r.status_code == 200 and r.json().get("success"):
                    stats["ok"] += 1
                else:
                    stats["failed"] += 1
            except Exception:
                stats["errors"] += 1
            n += 1


async def monitor_loop(base_url: str, stop_at: float, stats: dict) -> None:
    """Sample /queue and /status so the run reports what load it actually created."""
    async with httpx.AsyncClient(timeout=5.0) as http:
        while time.time() < stop_at:
            try:
                q = (await http.get(f"{base_url}/api/v1/queue")).json()
                s = (await http.get(f"{base_url}/api/v1/status")).json()
                stats["peak_queue"] = max(stats["peak_queue"], int(q.get("pending", 0)))
                stats["peak_cpu"] = max(stats["peak_cpu"], float(s.get("cpu_percent", 0)))
                if s.get("overloaded"):
                    stats["overloaded_samples"] += 1
                stats["samples"] += 1
            except Exception:
                pass
            await asyncio.sleep(1.0)


async def main_async(args: argparse.Namespace) -> int:
    if args.status_only:
        async with httpx.AsyncClient(timeout=5.0) as http:
            print((await http.get(f"{args.url}/api/v1/status")).json())
            print((await http.get(f"{args.url}/api/v1/queue")).json())
        return 0

    stats = {
        "ok": 0, "failed": 0, "errors": 0,
        "peak_queue": 0, "peak_cpu": 0.0,
        "overloaded_samples": 0, "samples": 0,
    }
    stop_at = time.time() + args.duration

    print(f"Loading {args.url} with {args.clients} clients for {args.duration}s...")
    await asyncio.gather(
        *(client_loop(f"load-{i}", args.url, stop_at, stats) for i in range(args.clients)),
        monitor_loop(args.url, stop_at, stats),
    )

    print("\n--- load generator summary ---")
    print(f"completed      : {stats['ok']}")
    print(f"failed         : {stats['failed']}")
    print(f"transport errs : {stats['errors']}")
    print(f"peak queue     : {stats['peak_queue']} (executor cap is 4)")
    print(f"peak cpu       : {stats['peak_cpu']:.1f}%")
    if stats["samples"]:
        pct = 100 * stats["overloaded_samples"] / stats["samples"]
        print(f"overloaded     : {pct:.0f}% of samples")
        if pct == 0:
            print(
                "\nNOTE: the edge never reported itself overloaded, so this run did not\n"
                "exercise the forwarding path. Raise --clients, or lower the container's\n"
                "cpus/mem_limit in docker-compose.yml so the load actually bites."
            )
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default=DEFAULT_EDGE, help=f"edge base URL (default {DEFAULT_EDGE})")
    p.add_argument("--clients", type=int, default=8, help="concurrent synthetic clients")
    p.add_argument("--duration", type=int, default=120, help="seconds to sustain load")
    p.add_argument("--status-only", action="store_true", help="print server load and exit")
    args = p.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
