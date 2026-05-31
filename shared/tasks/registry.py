"""
Hardcoded registry of offloadable task handlers shared by edge and cloud.

Mobile clients send a `task_name` that maps to one of the [TaskSpec]s below.
Each handler is **synchronous and CPU-bound** — the executors run them in a
thread pool via `loop.run_in_executor`, so handlers must not be `async`.

Both edge and cloud import the same registry so they're interchangeable for
any task in the list. If a real deployment ever needed asymmetric capabilities
(e.g. cloud-only GPU inference), this is the file to split.
"""
from __future__ import annotations

import hashlib
import io
import os
import tempfile
from dataclasses import dataclass
from typing import Callable

from shared.models.offloading_request import TaskComplexity

Handler = Callable[[bytes], bytes]


@dataclass(frozen=True)
class TaskSpec:
    name: str
    complexity: TaskComplexity
    handler: Handler


class UnknownTaskError(KeyError):
    """Raised when an OffloadingRequest names a task not in REGISTRY."""


# ── Handlers ─────────────────────────────────────────────────────────────

def _echo(payload: bytes) -> bytes:
    """Identity — useful for round-trip latency measurement."""
    return payload


def _sha256(payload: bytes) -> bytes:
    """Cryptographic hash — cheap CPU work with deterministic output."""
    return hashlib.sha256(payload).digest()


def _image_grayscale(payload: bytes) -> bytes:
    """Decode an encoded image (JPEG/PNG/...) → grayscale → re-encode as JPEG."""
    import cv2
    import numpy as np

    arr = np.frombuffer(payload, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("image-grayscale: could not decode input as an image")
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    ok, encoded = cv2.imencode(".jpg", gray)
    if not ok:
        raise RuntimeError("image-grayscale: JPEG encoding failed")
    return encoded.tobytes()


def _matrix_multiply(payload: bytes) -> bytes:
    """Multiply a square float32 matrix by itself. Payload = N*N float32s."""
    import numpy as np

    floats = np.frombuffer(payload, dtype=np.float32)
    n = int(len(floats) ** 0.5)
    if n * n != len(floats):
        raise ValueError(
            f"matrix-multiply: payload must encode a square matrix; got {len(floats)} float32s"
        )
    mat = floats.reshape(n, n)
    return (mat @ mat).astype(np.float32).tobytes()


def _video_frame_edges(payload: bytes) -> bytes:
    """
    Decode an MP4 clip, run Canny edge detection on every frame, return the
    first processed frame as a JPEG.

    Realistic mobile-cloud workload: a phone streams a short clip to the
    backend for vision-processing instead of running OpenCV locally.

    OpenCV's VideoCapture needs a file path, so we spool the payload to a
    NamedTemporaryFile and clean it up on the way out.
    """
    import cv2

    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as f:
        f.write(payload)
        tmp_path = f.name

    try:
        cap = cv2.VideoCapture(tmp_path)
        if not cap.isOpened():
            raise ValueError("video-frame-edges: could not open input as video")

        first_edge_frame = None
        frames_processed = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            edges = cv2.Canny(gray, threshold1=100, threshold2=200)
            if first_edge_frame is None:
                first_edge_frame = edges
            frames_processed += 1
        cap.release()

        if first_edge_frame is None:
            raise ValueError("video-frame-edges: no frames decoded from input")

        ok, encoded = cv2.imencode(".jpg", first_edge_frame)
        if not ok:
            raise RuntimeError("video-frame-edges: JPEG encoding of result failed")
        return encoded.tobytes()
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


# ── Registry ────────────────────────────────────────────────────────────

REGISTRY: dict[str, TaskSpec] = {
    spec.name: spec
    for spec in (
        TaskSpec("echo",              TaskComplexity.LIGHT,  _echo),
        TaskSpec("sha256",            TaskComplexity.LIGHT,  _sha256),
        TaskSpec("image-grayscale",   TaskComplexity.MEDIUM, _image_grayscale),
        TaskSpec("matrix-multiply",   TaskComplexity.HEAVY,  _matrix_multiply),
        TaskSpec("video-frame-edges", TaskComplexity.HEAVY,  _video_frame_edges),
    )
}


def lookup(task_name: str) -> TaskSpec:
    spec = REGISTRY.get(task_name)
    if spec is None:
        raise UnknownTaskError(
            f"no handler registered for task_name={task_name!r} "
            f"(known: {sorted(REGISTRY.keys())})"
        )
    return spec
