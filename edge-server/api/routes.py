from fastapi import APIRouter, HTTPException
from shared.models.offloading_request import OffloadingRequest, OffloadingResponse
from edge_server.executor.task_executor import TaskExecutor
from edge_server.gateway.offloading_broker import OffloadingBroker
from edge_server.resource_manager.resource_monitor import ResourceMonitor

router = APIRouter()
executor = TaskExecutor()
broker = OffloadingBroker()
monitor = ResourceMonitor()


@router.post("/offload", response_model=OffloadingResponse)
async def offload_task(request: OffloadingRequest):
    """Receive an offloading request and decide whether to execute locally or forward to cloud."""
    if monitor.is_overloaded():
        # Forward to cloud if edge is saturated
        return await broker.forward_to_cloud(request)
    return await executor.execute(request)


@router.get("/status")
def get_status():
    """Return current resource utilization of this edge node."""
    return monitor.get_status()


@router.get("/queue")
def get_queue():
    """Return pending task queue length."""
    return {"pending": executor.queue_length()}
