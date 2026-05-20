from pydantic import BaseModel
from enum import Enum


class NetworkType(str, Enum):
    WIFI = "WIFI"
    LTE = "LTE"
    FIVE_G = "5G"
    NONE = "NONE"


class MovementState(str, Enum):
    STATIONARY = "STATIONARY"
    WALKING = "WALKING"
    VEHICLE = "VEHICLE"


class NetworkContext(BaseModel):
    type: NetworkType
    rtt_ms: float
    bandwidth_mbps: float
    signal_strength: int


class CpuContext(BaseModel):
    usage_percent: float
    available_cores: int
    frequency_mhz: int


class BatteryContext(BaseModel):
    level_percent: int
    is_charging: bool
    temperature_celsius: float


class LocationContext(BaseModel):
    latitude: float
    longitude: float
    accuracy: float


class MobilityContext(BaseModel):
    speed_mps: float
    movement_state: MovementState


class ContextSnapshot(BaseModel):
    network: NetworkContext
    cpu: CpuContext
    battery: BatteryContext
    location: LocationContext
    mobility: MobilityContext
    timestamp: int
