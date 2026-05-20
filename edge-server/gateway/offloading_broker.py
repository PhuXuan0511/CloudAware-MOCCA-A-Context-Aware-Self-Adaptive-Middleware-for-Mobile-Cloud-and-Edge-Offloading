import httpx
from shared.models.offloading_request import OffloadingRequest, OffloadingResponse

CLOUD_SERVER_URL = "http://cloud-server:8002/api/v1/offload"


class OffloadingBroker:
    """
    Forwards tasks from the edge node to the cloud when the edge is overloaded.
    Also handles task migration if execution is taking too long.
    TODO: Add SDN/NFV hooks for network-level steering.
    """

    async def forward_to_cloud(self, request: OffloadingRequest) -> OffloadingResponse:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                CLOUD_SERVER_URL,
                content=request.model_dump_json(),
                headers={"Content-Type": "application/json"},
            )
            resp.raise_for_status()
            return OffloadingResponse.model_validate_json(resp.content)

    async def migrate_task(self, task_id: str) -> bool:
        # TODO: cancel in-progress task and re-submit to cloud mid-execution
        return False
