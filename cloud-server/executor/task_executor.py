import asyncio
import time
from shared.models.offloading_request import OffloadingRequest, OffloadingResponse


class TaskExecutor:
    """
    Cloud-side task executor with higher resource capacity than the edge node.
    TODO: Integrate with Kubernetes Job API for containerized task execution.
    TODO: Support async long-running tasks with a callback/webhook mechanism.
    """

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
                executed_at="cloud"
            )
        except Exception as e:
            return OffloadingResponse(
                task_id=request.task_id,
                success=False,
                result_payload=b"",
                execution_time_ms=0,
                executed_at="cloud",
                error_message=str(e)
            )

    async def _run_task(self, request: OffloadingRequest) -> bytes:
        # TODO: deserialize, dispatch to appropriate service, return result
        await asyncio.sleep(0)
        return b"cloud-result-stub"
