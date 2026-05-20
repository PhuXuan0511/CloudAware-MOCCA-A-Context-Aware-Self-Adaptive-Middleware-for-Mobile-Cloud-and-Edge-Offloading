package com.thesis.middleware.context

/**
 * Normalizes raw context snapshots into feature vectors
 * suitable for the offloading decision engine.
 *
 * Convention: every score is in [0, 1] where higher means better current
 * conditions for that resource. Bottleneck = low score.
 */
class FeatureExtractor {

    fun extract(snapshot: ContextSnapshot): ContextFeatures {
        return ContextFeatures(
            networkScore = computeNetworkScore(snapshot.network),
            cpuLoadScore = computeCpuScore(snapshot.cpu),
            batteryScore = computeBatteryScore(snapshot.battery),
            mobilityScore = computeMobilityScore(snapshot.mobility),
            rawSnapshot = snapshot
        )
    }

    private fun computeNetworkScore(ctx: NetworkContext): Float {
        val typeBase = when (ctx.type) {
            NetworkType.FIVE_G -> 1.0f
            NetworkType.WIFI -> 0.95f
            NetworkType.LTE -> 0.6f
            NetworkType.NONE -> 0.0f
        }
        val signalNorm = ctx.signalStrength.coerceIn(0, MAX_SIGNAL_LEVEL) / MAX_SIGNAL_LEVEL.toFloat()
        val bwNorm = (ctx.bandwidthMbps / GREAT_BANDWIDTH_MBPS).coerceIn(0f, 1f)
        return WEIGHT_TYPE * typeBase + WEIGHT_SIGNAL * signalNorm + WEIGHT_BANDWIDTH * bwNorm
    }

    private fun computeCpuScore(ctx: CpuContext): Float =
        (1f - ctx.usagePercent / 100f).coerceIn(0f, 1f)

    private fun computeBatteryScore(ctx: BatteryContext): Float {
        val level = ctx.levelPercent / 100f
        val chargeBonus = if (ctx.isCharging) CHARGING_BONUS else 0f
        val thermal = ((ctx.temperatureCelsius - THERMAL_CLEAN_C) / THERMAL_RANGE_C)
            .coerceIn(0f, 1f)
        return (level + chargeBonus - thermal).coerceIn(0f, 1f)
    }

    private fun computeMobilityScore(ctx: MobilityContext): Float = when (ctx.movementState) {
        MovementState.STATIONARY -> 1.0f
        MovementState.WALKING -> 0.6f
        MovementState.VEHICLE -> 0.2f
    }

    companion object {
        private const val MAX_SIGNAL_LEVEL = 4
        private const val GREAT_BANDWIDTH_MBPS = 100f

        private const val WEIGHT_TYPE = 0.4f
        private const val WEIGHT_SIGNAL = 0.3f
        private const val WEIGHT_BANDWIDTH = 0.3f

        private const val CHARGING_BONUS = 0.2f
        private const val THERMAL_CLEAN_C = 40f
        private const val THERMAL_RANGE_C = 10f
    }
}
