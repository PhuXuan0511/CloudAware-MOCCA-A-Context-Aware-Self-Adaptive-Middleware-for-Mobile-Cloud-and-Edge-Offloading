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

    /**
     * Network score = declared capability × measured link health.
     *
     * The capability term (type, signal, link bandwidth) is what
     * `ConnectivityManager` reports, and it is *static*: a Wi-Fi link whose
     * path to the server has gone from 10 ms to 1000 ms still reports the same
     * transport and the same `linkDownstreamBandwidthKbps`. Scoring on
     * capability alone put every Wi-Fi row in [0.68, 0.98] regardless of actual
     * conditions, which made `UNSTABLE_NETWORK` (threshold 0.30) unreachable
     * and left injected network degradation invisible to the policy.
     *
     * [linkHealth] scales that capability by what was actually measured, so the
     * same formula now spans the full range: healthy Wi-Fi still scores ~0.7+,
     * while a 500 ms path scores 0 whatever the transport claims.
     */
    private fun computeNetworkScore(ctx: NetworkContext): Float {
        val typeBase = when (ctx.type) {
            NetworkType.FIVE_G -> 1.0f
            NetworkType.WIFI -> 0.95f
            NetworkType.LTE -> 0.6f
            NetworkType.NONE -> 0.0f
        }
        val signalNorm = ctx.signalStrength.coerceIn(0, MAX_SIGNAL_LEVEL) / MAX_SIGNAL_LEVEL.toFloat()
        val bwNorm = (ctx.bandwidthMbps / GREAT_BANDWIDTH_MBPS).coerceIn(0f, 1f)
        val capability =
            WEIGHT_TYPE * typeBase + WEIGHT_SIGNAL * signalNorm + WEIGHT_BANDWIDTH * bwNorm
        return capability * linkHealth(ctx.rttMs)
    }

    /**
     * Measured degradation factor in [0, 1]: 1 while RTT stays under
     * [GOOD_RTT_MS], falling linearly to 0 at [BAD_RTT_MS].
     *
     * `rttMs <= 0` means no probe has completed yet, and returns 1 — an
     * unmeasured link is scored exactly as it was before RTT existed, so a
     * cold start or a device with no reachable endpoint degrades to the old
     * capability-only behaviour instead of being penalised for missing data.
     *
     * [GOOD_RTT_MS] is deliberately well above a healthy LAN round trip so
     * ordinary Wi-Fi jitter does not push scores across the policy thresholds;
     * the band between the two constants is what makes a moderately degraded
     * link distinguishable from a broken one.
     */
    private fun linkHealth(rttMs: Float): Float {
        if (rttMs <= 0f) return 1f
        val excess = (rttMs - GOOD_RTT_MS) / (BAD_RTT_MS - GOOD_RTT_MS)
        return 1f - excess.coerceIn(0f, 1f)
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

        // ── Measured link-health band ───────────────────────────────────
        // Below GOOD: no penalty. Above BAD: the link scores zero however good
        // the transport claims to be. Chosen so the four steps of the network
        // degradation session land either side of the policy thresholds
        // (UNSTABLE_NETWORK 0.30, GOOD_BANDWIDTH 0.60) rather than all above
        // them, and so a healthy but unremarkable Wi-Fi AP is not penalised.
        private const val GOOD_RTT_MS = 80f
        private const val BAD_RTT_MS = 500f

        private const val WEIGHT_TYPE = 0.4f
        private const val WEIGHT_SIGNAL = 0.3f
        private const val WEIGHT_BANDWIDTH = 0.3f

        private const val CHARGING_BONUS = 0.2f
        private const val THERMAL_CLEAN_C = 40f
        private const val THERMAL_RANGE_C = 10f
    }
}
