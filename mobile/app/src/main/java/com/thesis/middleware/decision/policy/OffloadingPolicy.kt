package com.thesis.middleware.decision.policy

import com.thesis.middleware.context.BatteryContext
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingPlan
import com.thesis.middleware.decision.TaskAnalysis

/**
 * Rule-based planner for the MAPE loop's Plan phase.
 *
 * Picks one of {LOCAL, EDGE, CLOUD} for a task based on the estimator outputs
 * in [TaskAnalysis] and a few guardrails read directly from [TaskAnalysis.features].
 *
 * The active [PolicyMode] is **derived from the device's battery state** so the
 * system adapts autonomously — no caller-supplied tags required:
 *
 *  - charging                       →  [PolicyMode.LATENCY_FIRST]  (energy is free)
 *  - battery < 15% AND unplugged    →  [PolicyMode.ENERGY_FIRST]   (preserve battery)
 *  - otherwise                      →  [defaultMode]               (BALANCED by default)
 *
 * Once the local-vs-remote choice is made, [pickRemoteTarget] decides EDGE vs CLOUD
 * using the live `ContextFeatures` (a stable, well-connected device prefers EDGE;
 * a moving or weakly-connected one prefers CLOUD for handoff resilience).
 */
class OffloadingPolicy(
    private val defaultMode: PolicyMode = PolicyMode.BALANCED,
    private val latencyWeight: Float = DEFAULT_LATENCY_WEIGHT,
    private val energyWeight: Float = DEFAULT_ENERGY_WEIGHT,
) {

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        // Hard guardrail: no connectivity → offload is impossible.
        if (!analysis.features.rawSnapshot.network.isOnline) {
            return OffloadingPlan(ExecutionTarget.LOCAL, "offline — running local")
        }
        val mode = selectMode(analysis.features.rawSnapshot.battery)
        return when (mode) {
            PolicyMode.ENERGY_FIRST -> evaluateEnergyFirst(analysis)
            PolicyMode.LATENCY_FIRST -> evaluateLatencyFirst(analysis)
            PolicyMode.BALANCED -> evaluateBalanced(analysis)
        }
    }

    private fun selectMode(battery: BatteryContext): PolicyMode = when {
        battery.levelPercent < CRITICAL_BATTERY_PERCENT && !battery.isCharging -> PolicyMode.ENERGY_FIRST
        battery.isCharging -> PolicyMode.LATENCY_FIRST
        else -> defaultMode
    }

    private fun evaluateEnergyFirst(a: TaskAnalysis): OffloadingPlan {
        // Critical battery + not charging: radio TX is the highest-power state on
        // a phone, so we keep the task local rather than risk a brownout mid-send.
        if (a.features.rawSnapshot.battery.isLowPower) {
            return OffloadingPlan(ExecutionTarget.LOCAL, "battery critical — keep local")
        }
        return if (a.localEnergyMj <= a.remoteEnergyMj) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "energy-first: local %.2fmJ ≤ remote %.2fmJ".format(a.localEnergyMj, a.remoteEnergyMj)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "energy-first: remote %.2fmJ < local %.2fmJ → %s".format(a.remoteEnergyMj, a.localEnergyMj, target)
            )
        }
    }

    private fun evaluateLatencyFirst(a: TaskAnalysis): OffloadingPlan {
        return if (a.localLatencyMs <= a.remoteLatencyMs) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "latency-first: local %.1fms ≤ remote %.1fms".format(a.localLatencyMs, a.remoteLatencyMs)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "latency-first: remote %.1fms < local %.1fms → %s".format(a.remoteLatencyMs, a.localLatencyMs, target)
            )
        }
    }

    private fun evaluateBalanced(a: TaskAnalysis): OffloadingPlan {
        val localCost = weightedCost(a.localLatencyMs, a.localEnergyMj)
        val remoteCost = weightedCost(a.remoteLatencyMs, a.remoteEnergyMj)
        return if (localCost <= remoteCost) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "balanced: local cost %.2f ≤ remote %.2f".format(localCost, remoteCost)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "balanced: remote cost %.2f < local %.2f → %s".format(remoteCost, localCost, target)
            )
        }
    }

    private fun weightedCost(latencyMs: Float, energyMj: Float): Float =
        latencyWeight * latencyMs + energyWeight * energyMj

    /**
     * EDGE when the device is stationary and well-connected — low RTT, nearby server.
     * CLOUD when the device is moving or the link is weak — better for handoff and
     * for tolerating intermittent connectivity.
     */
    private fun pickRemoteTarget(a: TaskAnalysis): ExecutionTarget {
        val stable = a.features.rawSnapshot.mobility.isStable
        val networkOk = a.features.networkScore >= NETWORK_OK_THRESHOLD
        return if (stable && networkOk) ExecutionTarget.EDGE else ExecutionTarget.CLOUD
    }

    companion object {
        private const val DEFAULT_LATENCY_WEIGHT = 0.6f
        private const val DEFAULT_ENERGY_WEIGHT = 0.4f
        private const val NETWORK_OK_THRESHOLD = 0.6f
        private const val CRITICAL_BATTERY_PERCENT = 15
    }
}

enum class PolicyMode { ENERGY_FIRST, LATENCY_FIRST, BALANCED }
