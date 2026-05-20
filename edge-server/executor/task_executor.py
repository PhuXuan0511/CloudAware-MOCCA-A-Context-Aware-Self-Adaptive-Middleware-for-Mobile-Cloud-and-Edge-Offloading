import asyncio
import time
from shared.models.offloading_request import OffloadingRequest, OffloadingResponse


class TaskExecutor:
    """
    Executes offloaded tasks received from mobile clients.
    TODO: Replace stub with actual task dispatching (subprocess, Docker container, etc.).
    TODO: Add a priority queue and worker pool for concurrent task handling.
    """

    def __init__(self, max_workers: int = 4):
        self._queue: asyncio.Queue = asyncio.Queue()
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
                executed_at="edge"
            )
        except Exception as e:
            return OffloadingResponse(
                task_id=request.task_id,
                success=False,
                result_payload=b"",
                execution_time_ms=0,
                executed_at="edge",
                error_message=str(e)
            )

    async def _run_task(self, request: OffloadingRequest) -> bytes:
        # TODO: deserialize input_payload, run actual computation, serialize result
        await asyncio.sleep(0)  # placeholder for async work
        return b"result-stub"

    def queue_length(self) -> int:
        return self._queue.qsize()
