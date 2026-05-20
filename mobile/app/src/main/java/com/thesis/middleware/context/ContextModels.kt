package com.thesis.middleware.context

data class ContextSnapshot(
    val network: NetworkContext,
    val cpu: CpuContext,
    val battery: BatteryContext,
    val location: LocationContext,
    val mobility: MobilityContext,
    val timestamp: Long
)

data class NetworkContext(
    val type: NetworkType,         // WIFI, LTE, 5G, NONE
    val rttMs: Float,
    val bandwidthMbps: Float,
    val signalStrength: Int
) {
    val isOnline: Boolean get() = type != NetworkType.NONE
}

data class CpuContext(
    val usagePercent: Float,
    val availableCores: Int,
    val frequencyMhz: Int
)

data class BatteryContext(
    val levelPercent: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Float
) {
    val isLowPower: Boolean get() = levelPercent < 20 && !isCharging
}

data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)

data class MobilityContext(
    val speedMps: Float,
    val movementState: MovementState  // STATIONARY, WALKING, VEHICLE
) {
    val isStable: Boolean get() = movementState == MovementState.STATIONARY
}

data class ContextFeatures(
    val networkScore: Float,      // normalized 0–1
    val cpuLoadScore: Float,
    val batteryScore: Float,
    val mobilityScore: Float,
    val rawSnapshot: ContextSnapshot
)

enum class NetworkType { WIFI, LTE, FIVE_G, NONE }
enum class MovementState { STATIONARY, WALKING, VEHICLE }
