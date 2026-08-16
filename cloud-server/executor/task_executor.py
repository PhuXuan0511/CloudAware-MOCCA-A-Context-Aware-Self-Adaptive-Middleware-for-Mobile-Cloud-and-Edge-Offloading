import asyncio
import base64
import time

from shared.models.offloading_request import OffloadingRequest, OffloadingResponse
from shared.tasks.registry import lookup


class TaskExecutor:
    """
    Cloud-side executor — same handler registry as the edge, but **without**
    a concurrency cap. The cloud is treated as "unlimited" for thesis
    purposes; in a real deployment you would replace this with a Kubernetes
    Job per request or a queue-backed worker pool.

    Errors (unknown task, handler exception, decode failure) are surfaced as
    `success=False` so callers — either the mobile client directly or the
    edge broker forwarding an overload — can react appropriately.
    """

    async def execute(self, request: OffloadingRequest) -> OffloadingResponse:
        start = time.time()
        try:
            result = await self._run_task(request)
            elapsed_ms = (time.time() - start) * 1000
            return OffloadingResponse(
                task_id=request.task_id,
                success=True,
                # result_payload is Base64Bytes, which decodes on construction
                # as much as on JSON parsing - passing the handler's raw bytes
                # directly would try to base64-*decode* them (wrong direction)
                # and usually corrupt to empty. Pre-encoding here is what makes
                # the round trip symmetric with the client's expectation.
                result_payload=base64.b64encode(result),
                execution_time_ms=elapsed_ms,
                executed_at="cloud",
            )
        except Exception as e:
            elapsed_ms = (time.time() - start) * 1000
            return OffloadingResponse(
                task_id=request.task_id,
                success=False,
                result_payload=b"",
                execution_time_ms=elapsed_ms,
                executed_at="cloud",
                error_message=f"{type(e).__name__}: {e}",
            )

    async def _run_task(self, request: OffloadingRequest) -> bytes:
        spec = lookup(request.task_name)  # raises UnknownTaskError
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, spec.handler, request.input_payload)
