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
    val temperatureCelsius: Float,
    /**
     * Instantaneous battery current in microamperes, from
     * `BATTERY_PROPERTY_CURRENT_NOW`. Negative while discharging on most
     * devices, though the sign convention is not guaranteed by the platform —
     * [powerMilliWatts] takes the magnitude.
     *
     * `null` when the device does not implement the property.
     */
    val currentNowMicroAmps: Int? = null,
    /** Battery voltage in millivolts, from `EXTRA_VOLTAGE`. `null` if absent. */
    val voltageMilliVolts: Int? = null,
) {
    val isLowPower: Boolean get() = levelPercent < 20 && !isCharging

    /**
     * Whole-device power draw in milliwatts (`P = V × I`), or `null` when the
     * device exposes neither current nor voltage.
     *
     * This is the **total** device draw — screen, radio, and background work
     * included — not the power attributable to one task. Attribution requires
     * differencing against an idle baseline; see `EnergyEstimator` for the
     * modelled per-task figure this is used to validate.
     */
    val powerMilliWatts: Float?
        get() {
            val uA = currentNowMicroAmps ?: return null
            val mV = voltageMilliVolts ?: return null
            if (mV <= 0) return null
            // |µA| / 1000 = mA;  mA × mV / 1000 = mW
            return (kotlin.math.abs(uA) / 1000f) * (mV / 1000f)
        }
}

data class LocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)

data class MobilityContext(
    /**
     * Smoothed linear acceleration magnitude in m/s² (gravity subtracted).
     * Not actual speed — phones don't directly report ground speed from the
     * accelerometer. The field is kept as a movement-intensity signal; the
     * coarse [movementState] classification is what the policy actually reads.
     */
    val linearAccelerationMps2: Float,
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
