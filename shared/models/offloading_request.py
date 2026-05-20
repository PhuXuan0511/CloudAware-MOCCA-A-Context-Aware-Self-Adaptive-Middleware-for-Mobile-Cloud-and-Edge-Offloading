from pydantic import BaseModel
from enum import Enum
from shared.models.context_data import ContextSnapshot


class TaskComplexity(str, Enum):
    LIGHT = "LIGHT"
    MEDIUM = "MEDIUM"
    HEAVY = "HEAVY"


class OffloadingRequest(BaseModel):
    task_id: str
    task_name: str
    input_size_bytes: int
    complexity: TaskComplexity
    input_payload: bytes           # serialized task input
    context: ContextSnapshot       # device context at submission time


class OffloadingResponse(BaseModel):
    task_id: str
    success: bool
    result_payload: bytes
    execution_time_ms: float
    executed_at: str               # "edge" or "cloud"
    error_message: str | None = None
