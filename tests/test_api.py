"""
HTTP-level tests for the edge and cloud FastAPI apps.

`collect_data.ps1` gates every collection session on `GET /health` returning
200, so these endpoints are part of the evaluation harness, not just the app.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from cloud_server.main import app as cloud_app
from edge_server.main import app as edge_app

from .conftest import make_request


@pytest.fixture(autouse=True)
def edge_not_overloaded(monkeypatch):
    """
    Pin the edge's overload check off by default.

    ``ResourceMonitor.is_overloaded()`` reads *host* CPU and memory via psutil
    and trips at 80% memory. Left unpinned, these tests forward to the real
    ``http://cloud-server:8002`` (and fail with a DNS error) on any developer
    machine that happens to be above that threshold — which is also how this
    behaviour shows up during real data collection.
    """
    import edge_server.api.routes as routes

    monkeypatch.setattr(routes.monitor, "is_overloaded", lambda: False)


@pytest.fixture
def edge_client():
    with TestClient(edge_app) as client:
        yield client


@pytest.fixture
def cloud_client():
    with TestClient(cloud_app) as client:
        yield client


# ── Health ───────────────────────────────────────────────────────────────────

def test_edge_health_identifies_the_node(edge_client):
    response = edge_client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "node": "edge"}


def test_cloud_health_identifies_the_node(cloud_client):
    response = cloud_client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "node": "cloud"}


# ── Offload round-trip ───────────────────────────────────────────────────────

def test_edge_offload_round_trips_an_echo_task(edge_client):
    payload = make_request(payload=b"hello").model_dump(mode="json")
    response = edge_client.post("/api/v1/offload", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["executed_at"] == "edge"
    assert body["task_id"] == "task-1"


def test_cloud_offload_round_trips_an_echo_task(cloud_client):
    payload = make_request(payload=b"hello").model_dump(mode="json")
    response = cloud_client.post("/api/v1/offload", json=payload)
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["executed_at"] == "cloud"


def test_offload_rejects_a_malformed_request_body(edge_client):
    response = edge_client.post("/api/v1/offload", json={"task_id": "only-this"})
    assert response.status_code == 422


def test_unknown_task_is_a_200_with_success_false(edge_client):
    # Deliberate: the phone's ExecutionProxy inspects `success`, so a bad task
    # name must not surface as a transport-level error.
    payload = make_request(task_name="no-such-task").model_dump(mode="json")
    response = edge_client.post("/api/v1/offload", json=payload)
    assert response.status_code == 200
    assert response.json()["success"] is False


# ── Resource endpoints ───────────────────────────────────────────────────────

def test_edge_status_reports_the_documented_fields(edge_client):
    body = edge_client.get("/api/v1/status").json()
    assert {
        "cpu_percent",
        "memory_used_percent",
        "memory_available_mb",
        "disk_usage_percent",
        "metrics_source",
        "thresholds",
        "overloaded",
    } <= set(body)
    # Surfaced so a collection run can confirm the edge is measuring its own
    # container budget rather than the host's memory.
    assert set(body["metrics_source"]) == {"cpu", "memory"}


def test_edge_queue_reports_pending_count(edge_client):
    body = edge_client.get("/api/v1/queue").json()
    assert body == {"pending": 0}


def test_cloud_status_reports_the_documented_fields(cloud_client):
    body = cloud_client.get("/api/v1/status").json()
    assert "cpu_percent" in body


# ── Overload forwarding ──────────────────────────────────────────────────────

def test_edge_forwards_to_cloud_when_overloaded(edge_client, monkeypatch):
    import edge_server.api.routes as routes
    from shared.models.offloading_request import OffloadingResponse

    forwarded = {}

    async def fake_forward(request):
        forwarded["task_id"] = request.task_id
        return OffloadingResponse(
            task_id=request.task_id,
            success=True,
            result_payload=b"from-cloud",
            execution_time_ms=1.0,
            executed_at="cloud",
        )

    monkeypatch.setattr(routes.monitor, "is_overloaded", lambda: True)
    monkeypatch.setattr(routes.broker, "forward_to_cloud", fake_forward)

    payload = make_request(payload=b"hello").model_dump(mode="json")
    body = edge_client.post("/api/v1/offload", json=payload).json()

    assert forwarded["task_id"] == "task-1"
    assert body["executed_at"] == "cloud"


def test_edge_executes_locally_when_not_overloaded(edge_client):
    payload = make_request(payload=b"hello").model_dump(mode="json")
    body = edge_client.post("/api/v1/offload", json=payload).json()
    assert body["executed_at"] == "edge"


def test_overload_thresholds_are_env_overridable(monkeypatch):
    # Lets a collection run pin the thresholds without editing code.
    monkeypatch.setenv("MOCCA_OVERLOAD_MEM_PERCENT", "95")
    import importlib

    import edge_server.resource_manager.resource_monitor as rm

    importlib.reload(rm)
    try:
        assert rm.ResourceMonitor.OVERLOAD_MEM_THRESHOLD == 95.0
    finally:
        monkeypatch.undo()
        importlib.reload(rm)

    assert rm.ResourceMonitor.OVERLOAD_MEM_THRESHOLD == 80.0
