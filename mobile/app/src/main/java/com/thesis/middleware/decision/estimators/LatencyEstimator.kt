package com.thesis.middleware.decision.estimators

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.ContextFeatures
import com.thesis.middleware.context.NetworkContext
import com.thesis.middleware.context.NetworkType

/**
 * End-to-end perceived response time for a task, in milliseconds.
 *
 *  - Local  = pure compute time scaled by available CPU headroom.
 *  - Remote = uplink transmission + RTT + server compute + queue + mobility penalty.
 *
 * Network signals fall back to per-network-type defaults when the collector
 * hasn't measured them yet (RTT requires an async probe; bandwidth depends on
 * NetworkCapabilities being populated).
 */
class LatencyEstimator {

    fun estimateLocal(task: OffloadableTask, features: ContextFeatures): Float {
        val baseline = baselineMs(task.complexity)
        val headroom = features.cpuLoadScore.coerceAtLeast(MIN_HEADROOM)
        return baseline / headroom
    }

    fun estimateRemote(task: OffloadableTask, features: ContextFeatures): Float {
        val net = features.rawSnapshot.network
        val rtt = effectiveRttMs(net)
        val bw = effectiveBandwidthMbps(net)
        val txMs = task.inputSizeBytes / (bw * MBPS_TO_BYTES_PER_MS)
        val serverExecMs = baselineMs(task.complexity) * SERVER_SPEEDUP
        val mobilityPenaltyMs = (1f - features.mobilityScore) * MOBILITY_RETRY_PENALTY_MS
        return rtt + txMs + serverExecMs + SERVER_QUEUE_MS + mobilityPenaltyMs
    }

    private fun baselineMs(c: TaskComplexity): Float = when (c) {
        TaskComplexity.LIGHT -> 50f
        TaskComplexity.MEDIUM -> 300f
        TaskComplexity.HEAVY -> 2000f
    }

    private fun effectiveRttMs(net: NetworkContext): Float =
        if (net.rttMs > 0f) net.rttMs else when (net.type) {
            NetworkType.FIVE_G -> 20f
            NetworkType.WIFI -> 15f
            NetworkType.LTE -> 50f
            NetworkType.NONE -> Float.MAX_VALUE / 4f
        }

    private fun effectiveBandwidthMbps(net: NetworkContext): Float =
        if (net.bandwidthMbps > 0f) net.bandwidthMbps else when (net.type) {
            NetworkType.FIVE_G -> 200f
            NetworkType.WIFI -> 80f
            NetworkType.LTE -> 30f
            NetworkType.NONE -> 0.001f
        }

    companion object {
        private const val MIN_HEADROOM = 0.05f
        private const val SERVER_SPEEDUP = 0.3f
        private const val SERVER_QUEUE_MS = 30f
        private const val MOBILITY_RETRY_PENALTY_MS = 200f
        // 1 Mbps = 1_000_000 bits/s = 125_000 B/s = 125 B/ms
        private const val MBPS_TO_BYTES_PER_MS = 125f
    }
}
