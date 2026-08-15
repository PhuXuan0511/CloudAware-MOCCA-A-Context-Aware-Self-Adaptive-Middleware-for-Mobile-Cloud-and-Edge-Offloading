"""
Tests for the shared task registry.

The registry is the one component edge and cloud both import, so a change here
changes both servers *and* the task-complexity labels the training notebook maps
in `TASK_COMPLEXITY`.
"""
from __future__ import annotations

import hashlib

import pytest

from shared.models.offloading_request import TaskComplexity
from shared.tasks.registry import REGISTRY, UnknownTaskError, lookup

np = pytest.importorskip("numpy", reason="numpy is needed for the matrix handler")


# ── Registry shape ───────────────────────────────────────────────────────────

def test_registry_exposes_the_five_demo_tasks():
    assert set(REGISTRY) == {
        "echo",
        "sha256",
        "image-grayscale",
        "matrix-multiply",
        "video-frame-edges",
    }


@pytest.mark.parametrize(
    ("task_name", "expected"),
    [
        ("echo", TaskComplexity.LIGHT),
        ("sha256", TaskComplexity.LIGHT),
        ("image-grayscale", TaskComplexity.MEDIUM),
        ("matrix-multiply", TaskComplexity.HEAVY),
        ("video-frame-edges", TaskComplexity.HEAVY),
    ],
)
def test_complexity_labels_match_the_notebook_encoding(task_name, expected):
    # evaluation/notebooks/random-forest-training.ipynb maps these to the
    # ordinal task_complexity feature (LIGHT=0, MEDIUM=1, HEAVY=2). A change
    # here silently shifts a model input.
    assert lookup(task_name).complexity is expected


def test_unknown_task_names_the_known_tasks_in_the_error():
    with pytest.raises(UnknownTaskError) as excinfo:
        lookup("does-not-exist")
    message = str(excinfo.value)
    assert "does-not-exist" in message
    assert "echo" in message


# ── Handlers ─────────────────────────────────────────────────────────────────

def test_echo_round_trips_its_payload():
    payload = b"\x00\x01\x02 arbitrary bytes \xff"
    assert lookup("echo").handler(payload) == payload


def test_sha256_returns_the_raw_digest():
    payload = b"mocca"
    assert lookup("sha256").handler(payload) == hashlib.sha256(payload).digest()


def test_matrix_multiply_squares_the_input_matrix():
    mat = np.array([[1, 2], [3, 4]], dtype=np.float32)
    result = lookup("matrix-multiply").handler(mat.tobytes())
    got = np.frombuffer(result, dtype=np.float32).reshape(2, 2)
    np.testing.assert_allclose(got, mat @ mat)


def test_matrix_multiply_rejects_a_non_square_payload():
    # 6 float32s cannot form a square matrix.
    payload = np.arange(6, dtype=np.float32).tobytes()
    with pytest.raises(ValueError, match="square matrix"):
        lookup("matrix-multiply").handler(payload)


def test_image_grayscale_rejects_undecodable_input():
    pytest.importorskip("cv2", reason="opencv is only installed in the server image")
    with pytest.raises(ValueError, match="could not decode"):
        lookup("image-grayscale").handler(b"definitely not an image")


def test_video_frame_edges_rejects_undecodable_input():
    pytest.importorskip("cv2", reason="opencv is only installed in the server image")
    with pytest.raises(ValueError):
        lookup("video-frame-edges").handler(b"definitely not a video")
