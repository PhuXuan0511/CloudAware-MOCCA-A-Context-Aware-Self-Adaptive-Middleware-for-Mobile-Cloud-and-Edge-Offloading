from pydantic import Base64Bytes, BaseModel
from enum import Enum
from shared.models.context_data import ContextSnapshot


class TaskComplexity(str, Enum):
    LIGHT = "LIGHT"
    MEDIUM = "MEDIUM"
    HEAVY = "HEAVY"


# Base64Bytes, not bytes: a bare `bytes` field does NOT base64-decode an
# incoming JSON string, despite that being the natural assumption (and the
# comment this code used to carry). Pydantic v2 treats a bare `bytes` field as
# "UTF-8-encode whatever string arrived" in both directions, so a base64
# string the client encoded correctly gets its own text bytes stored verbatim
# — e.g. a 4096-byte matrix payload survives as its 5464-character base64
# text (5464 is not a multiple of 4, so `_matrix_multiply`'s square-matrix
# check was the only handler that caught this loudly; every other handler
# silently computed on the base64 text itself). `Base64Bytes` is the type that
# actually performs the decode/encode Kotlin's `OffloadingClient` assumes.
#
# Base64Bytes decodes on validation from EITHER a JSON string OR direct Python
# construction — so any code that builds these models directly (not by
# parsing JSON) must pass already-base64-encoded bytes, or the "decode" step
# corrupts them. See task_executor.py's `base64.b64encode(result)`.
class OffloadingRequest(BaseModel):
    task_id: str
    task_name: str
    input_size_bytes: int
    complexity: TaskComplexity
    input_payload: Base64Bytes     # serialized task input
    context: ContextSnapshot       # device context at submission time


class OffloadingResponse(BaseModel):
    task_id: str
    success: bool
    result_payload: Base64Bytes
    execution_time_ms: float
    executed_at: str               # "edge" or "cloud"
    error_message: str | None = None
