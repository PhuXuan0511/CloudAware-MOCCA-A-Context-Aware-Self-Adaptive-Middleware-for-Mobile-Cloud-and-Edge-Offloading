"""
Tests for the edge and cloud task executors.

The contract that matters for the thesis: a failed offload must come back as a
well-formed ``success=False`` response, never as an exception. ``ExecutionProxy``
on the phone only falls back to local execution when it gets a response it can
inspect — an unhandled server crash surfaces to the user as a failed task and
inflates the fallback rate in the collected CSV.
"""
from __future__ import annotations

import asyncio

import pytest

from cloud_server.executor.task_executor import TaskExecutor as CloudExecutor
from edge_server.executor.task_executor import TaskExecutor as EdgeExecutor

from .conftest import make_request


@pytest.mark.parametrize(
    ("executor_cls", "expected_node"),
    [(EdgeExecutor, "edge"), (CloudExecutor, "cloud")],
)
@pytest.mark.asyncio
async def test_successful_execution_reports_the_executing_node(
    executor_cls, expected_node
):
    response = await executor_cls().execute(make_request(payload=b"hello"))
    assert response.success is True
    assert response.result_payload == b"hello"
    assert response.executed_at == expected_node
    assert response.task_id == "task-1"
    assert response.error_message is None


@pytest.mark.parametrize("executor_cls", [EdgeExecutor, CloudExecutor])
@pytest.mark.asyncio
async def test_unknown_task_returns_a_failure_response_not_an_exception(executor_cls):
    response = await executor_cls().execute(make_request(task_name="no-such-task"))
    assert response.success is False
    assert response.result_payload == b""
    assert "UnknownTaskError" in response.error_message


@pytest.mark.parametrize("executor_cls", [EdgeExecutor, CloudExecutor])
@pytest.mark.asyncio
async def test_handler_exception_is_reported_as_a_failure_response(executor_cls):
    pytest.importorskip("numpy")
    # 6 float32s is not a square matrix, so the handler raises ValueError.
    response = await executor_cls().execute(
        make_request(task_name="matrix-multiply", payload=b"\x00" * 24)
    )
    assert response.success is False
    assert "ValueError" in response.error_message


@pytest.mark.parametrize("executor_cls", [EdgeExecutor, CloudExecutor])
@pytest.mark.asyncio
async def test_execution_time_is_measured_on_the_failure_path_too(executor_cls):
    response = await executor_cls().execute(make_request(task_name="no-such-task"))
    assert response.execution_time_ms >= 0.0


# ── Edge-specific concurrency accounting ─────────────────────────────────────

@pytest.mark.asyncio
async def test_edge_queue_length_starts_and_ends_at_zero():
    executor = EdgeExecutor()
    assert executor.queue_length() == 0
    await executor.execute(make_request())
    assert executor.queue_length() == 0


@pytest.mark.asyncio
async def test_edge_queue_length_returns_to_zero_after_a_failed_task():
    # The decrement lives in a `finally`, but only wraps the semaphore body —
    # a task that fails inside the handler must still release its slot.
    pytest.importorskip("numpy")
    executor = EdgeExecutor()
    await executor.execute(
        make_request(task_name="matrix-multiply", payload=b"\x00" * 24)
    )
    assert executor.queue_length() == 0


@pytest.mark.asyncio
async def test_edge_executor_caps_concurrent_handlers():
    executor = EdgeExecutor(max_workers=2)
    observed_peak = 0

    original = executor._run_task

    async def spy(request):
        nonlocal observed_peak
        result = await original(request)
        observed_peak = max(observed_peak, executor.queue_length())
        return result

    executor._run_task = spy
    await asyncio.gather(*(executor.execute(make_request()) for _ in range(8)))
    assert observed_peak <= 2
    assert executor.queue_length() == 0


@pytest.mark.asyncio
async def test_concurrent_requests_keep_their_own_task_ids():
    executor = EdgeExecutor(max_workers=2)
    responses = await asyncio.gather(
        *(
            executor.execute(make_request(task_id=f"task-{i}", payload=f"p{i}".encode()))
            for i in range(6)
        )
    )
    assert [r.task_id for r in responses] == [f"task-{i}" for i in range(6)]
    assert [r.result_payload for r in responses] == [f"p{i}".encode() for i in range(6)]
