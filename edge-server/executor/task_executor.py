import asyncio
import time

from shared.models.offloading_request import OffloadingRequest, OffloadingResponse
from shared.tasks.registry import lookup


class TaskExecutor:
    """
    Edge-side executor.

    Looks up the requested `task_name` in the shared registry and dispatches
    the CPU-bound handler to the default thread pool via `run_in_executor`,
    so the FastAPI event loop stays responsive while the work runs.

    Concurrency is capped by an `asyncio.Semaphore(max_workers)` — once that
    many handlers are in flight, additional requests await acquisition rather
    than overcommit the host. `queue_length()` reports the count of in-flight
    tasks for the `/queue` endpoint.

    Errors (unknown task, handler exception, decode failure) are caught and
    surfaced as `success=False` so the mobile client can fall back to local
    execution via `ExecutionProxy`.
    """

    def __init__(self, max_workers: int = 4):
        self._semaphore = asyncio.Semaphore(max_workers)
        self._in_flight = 0
        self._max_workers = max_workers

    async def execute(self, request: OffloadingRequest) -> OffloadingResponse:
        start = time.time()
        try:
            result = await self._run_task(request)
            elapsed_ms = (time.time() - start) * 1000
            return OffloadingResponse(
                task_id=request.task_id,
                success=True,
                result_payload=result,
                execution_time_ms=elapsed_ms,
                executed_at="edge",
            )
        except Exception as e:
            elapsed_ms = (time.time() - start) * 1000
            return OffloadingResponse(
                task_id=request.task_id,
                success=False,
                result_payload=b"",
                execution_time_ms=elapsed_ms,
                executed_at="edge",
                error_message=f"{type(e).__name__}: {e}",
            )

    async def _run_task(self, request: OffloadingRequest) -> bytes:
        spec = lookup(request.task_name)  # raises UnknownTaskError
        async with self._semaphore:
            self._in_flight += 1
            try:
                loop = asyncio.get_running_loop()
                return await loop.run_in_executor(
                    None, spec.handler, request.input_payload
                )
            finally:
                self._in_flight -= 1

    def queue_length(self) -> int:
        return self._in_flight
