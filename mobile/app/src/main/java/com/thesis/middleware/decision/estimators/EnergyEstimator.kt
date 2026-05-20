package com.thesis.middleware.decision.estimators

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.ContextFeatures
import com.thesis.middleware.context.NetworkContext
import com.thesis.middleware.context.NetworkType

/**
 * Predicted energy consumption per task, in millijoules.
 *
 *  - Local  = CPU power × local compute time.
 *  - Remote = radio TX power × transmission time + radio idle power × wait time
 *             (RTT + server compute + queue).
 *
 * Unit math: mW × ms / 1000 = mJ  (since 1 mW = 1 mJ/s).
 *
 * Coefficients are order-of-magnitude estimates and should be re-calibrated
 * from on-device power profiling for production use.
 */
class EnergyEstimator {

    fun estimateLocal(task: OffloadableTask, features: ContextFeatures): Float {
        val execMs = localExecMs(task, features)
        return CPU_POWER_MW * execMs / 1000f
    }

    fun estimateRemote(task: OffloadableTask, features: ContextFeatures): Float {
        val net = features.rawSnapshot.network
        val bw = effectiveBandwidthMbps(net)
        val rtt = effectiveRttMs(net)

        val txMs = task.inputSizeBytes / (bw * MBPS_TO_BYTES_PER_MS)
        val serverExecMs = baselineMs(task.complexity) * SERVER_SPEEDUP
        val waitMs = rtt + serverExecMs + SERVER_QUEUE_MS

        val txEnergyMj = RADIO_TX_POWER_MW * txMs / 1000f
        val idleEnergyMj = RADIO_IDLE_POWER_MW * waitMs / 1000f
        return txEnergyMj + idleEnergyMj
    }

    private fun localExecMs(task: OffloadableTask, features: ContextFeatures): Float {
        val headroom = features.cpuLoadScore.coerceAtLeast(MIN_HEADROOM)
        return baselineMs(task.complexity) / headroom
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
        private const val MBPS_TO_BYTES_PER_MS = 125f

        // Order-of-magnitude power figures — calibrate per device.
        private const val CPU_POWER_MW = 800f         // mid-range phone, CPU pinned
        private const val RADIO_TX_POWER_MW = 1500f   // LTE/5G uplink
        private const val RADIO_IDLE_POWER_MW = 50f   // connected idle
    }
}
