from fastapi import APIRouter
from shared.models.offloading_request import OffloadingRequest, OffloadingResponse
from cloud_server.executor.task_executor import TaskExecutor
from cloud_server.resource_manager.resource_monitor import ResourceMonitor

router = APIRouter()
executor = TaskExecutor()
monitor = ResourceMonitor()


@router.post("/offload", response_model=OffloadingResponse)
async def offload_task(request: OffloadingRequest):
    """Accept and execute tasks forwarded from edge or directly from mobile."""
    return await executor.execute(request)


@router.get("/status")
def get_status():
    return monitor.get_status()
