"""Shared fixtures for the server test suite."""
from __future__ import annotations

import base64

import pytest

from shared.models.context_data import (
    BatteryContext,
    ContextSnapshot,
    CpuContext,
    LocationContext,
    MobilityContext,
    MovementState,
    NetworkContext,
    NetworkType,
)
from shared.models.offloading_request import OffloadingRequest, TaskComplexity


def make_context(**overrides) -> ContextSnapshot:
    """A healthy-device context snapshot; override any field by keyword."""
    defaults = dict(
        network=NetworkContext(
            type=NetworkType.WIFI,
            rtt_ms=15.0,
            bandwidth_mbps=80.0,
            signal_strength=4,
        ),
        cpu=CpuContext(usage_percent=20.0, available_cores=8, frequency_mhz=2400),
        battery=BatteryContext(
            level_percent=80, is_charging=False, temperature_celsius=30.0
        ),
        location=LocationContext(latitude=0.0, longitude=0.0, accuracy=5.0),
        mobility=MobilityContext(
            linear_acceleration_mps2=0.1, movement_state=MovementState.STATIONARY
        ),
        timestamp=0,
    )
    defaults.update(overrides)
    return ContextSnapshot(**defaults)


def make_request(
    task_name: str = "echo",
    payload: bytes = b"hello",
    complexity: TaskComplexity = TaskComplexity.LIGHT,
    task_id: str = "task-1",
) -> OffloadingRequest:
    return OffloadingRequest(
        task_id=task_id,
        task_name=task_name,
        input_size_bytes=len(payload),
        complexity=complexity,
        # input_payload is Base64Bytes: it decodes on direct construction the
        # same as it does on JSON parsing, so a fixture handing it raw bytes
        # must pre-encode them - passing `payload` bare would corrupt it the
        # same way the real bug did.
        input_payload=base64.b64encode(payload),
        context=make_context(),
    )


@pytest.fixture
def context_snapshot() -> ContextSnapshot:
    return make_context()


@pytest.fixture
def echo_request() -> OffloadingRequest:
    return make_request()
