package com.thesis.middleware

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.BatteryContext
import com.thesis.middleware.context.ContextFeatures
import com.thesis.middleware.context.ContextSnapshot
import com.thesis.middleware.context.CpuContext
import com.thesis.middleware.context.LocationContext
import com.thesis.middleware.context.MobilityContext
import com.thesis.middleware.context.MovementState
import com.thesis.middleware.context.NetworkContext
import com.thesis.middleware.context.NetworkType
import com.thesis.middleware.decision.TaskAnalysis

/**
 * Builders for the pure domain objects the decision layer consumes.
 *
 * Every parameter has a "healthy device on good Wi-Fi" default so each test can
 * name only the signals it actually cares about — a test that says
 * `analysis(batteryPercent = 15)` reads as the scenario it describes.
 */
object Fixtures {

    fun task(
        name: String = "matrix-multiply",
        complexity: TaskComplexity = TaskComplexity.HEAVY,
        inputSizeBytes: Long = 64_000,
    ): OffloadableTask = OffloadableTask(
        id = "task-1",
        name = name,
        inputSizeBytes = inputSizeBytes,
        complexity = complexity,
        inputPayload = ByteArray(0),
        execute = { ByteArray(0) },
    )

    fun snapshot(
        networkType: NetworkType = NetworkType.WIFI,
        rttMs: Float = 15f,
        bandwidthMbps: Float = 80f,
        signalDbm: Int = 4,
        cpuPercent: Float = 20f,
        cpuCores: Int = 8,
        batteryPercent: Int = 80,
        isCharging: Boolean = false,
        temperatureCelsius: Float = 30f,
        movement: MovementState = MovementState.STATIONARY,
    ): ContextSnapshot = ContextSnapshot(
        network = NetworkContext(networkType, rttMs, bandwidthMbps, signalDbm),
        cpu = CpuContext(cpuPercent, cpuCores, 2400),
        battery = BatteryContext(batteryPercent, isCharging, temperatureCelsius),
        location = LocationContext(0.0, 0.0, 5f),
        mobility = MobilityContext(0.1f, movement),
        timestamp = 0L,
    )

    fun features(
        networkScore: Float = 0.9f,
        cpuLoadScore: Float = 0.8f,
        batteryScore: Float = 0.8f,
        mobilityScore: Float = 1.0f,
        snapshot: ContextSnapshot = snapshot(),
    ): ContextFeatures = ContextFeatures(
        networkScore = networkScore,
        cpuLoadScore = cpuLoadScore,
        batteryScore = batteryScore,
        mobilityScore = mobilityScore,
        rawSnapshot = snapshot,
    )

    /**
     * Builds a [TaskAnalysis] with estimator outputs supplied directly, so a
     * policy test can pin the exact speedup / energy ratio a rule keys on
     * without reverse-engineering the estimator formulas.
     *
     * Defaults give speedup = 2000/500 = 4.0x (compute floor comfortably met)
     * and remote energy cheaper than local.
     */
    fun analysis(
        complexity: TaskComplexity = TaskComplexity.HEAVY,
        networkType: NetworkType = NetworkType.WIFI,
        networkScore: Float = 0.9f,
        cpuPercent: Float = 20f,
        batteryPercent: Int = 80,
        isCharging: Boolean = false,
        movement: MovementState = MovementState.STATIONARY,
        localLatencyMs: Float = 2000f,
        remoteLatencyMs: Float = 500f,
        localEnergyMj: Float = 1600f,
        remoteEnergyMj: Float = 100f,
        localExecTimeMs: Float = 2000f,
        remoteExecTimeMs: Float = 630f,
        inputSizeBytes: Long = 64_000,
    ): TaskAnalysis {
        val snap = snapshot(
            networkType = networkType,
            cpuPercent = cpuPercent,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            movement = movement,
        )
        return TaskAnalysis(
            task = task(complexity = complexity, inputSizeBytes = inputSizeBytes),
            features = features(
                networkScore = networkScore,
                cpuLoadScore = 1f - cpuPercent / 100f,
                mobilityScore = if (movement == MovementState.STATIONARY) 1f else 0.2f,
                snapshot = snap,
            ),
            localLatencyMs = localLatencyMs,
            remoteLatencyMs = remoteLatencyMs,
            localEnergyMj = localEnergyMj,
            remoteEnergyMj = remoteEnergyMj,
            localExecTimeMs = localExecTimeMs,
            remoteExecTimeMs = remoteExecTimeMs,
        )
    }
}
