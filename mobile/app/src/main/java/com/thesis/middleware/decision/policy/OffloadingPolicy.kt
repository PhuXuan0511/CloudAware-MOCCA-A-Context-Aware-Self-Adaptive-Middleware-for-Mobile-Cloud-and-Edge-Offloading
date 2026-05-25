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
 * Two ExecTime-aware guardrails sit *above* the mode-specific logic and use the
 * outputs of `ExecutionTimeEstimator` (`localExecTimeMs`, `remoteExecTimeMs`):
 *
 *  1. **Compute-benefit floor** — if the server is not enough faster than the
 *     phone on the pure work, network overhead can't be amortized, so we
 *     force LOCAL. Naturally protects LIGHT tasks from being offloaded.
 *  2. **User-patience override** — if local execution would block the user
 *     for longer than [userPatienceMs] *and* offloading is actually faster
 *     *and* the battery is not in the low-power band, we force offload even
 *     when the active mode (e.g. ENERGY_FIRST) would otherwise keep it local.
 *
 * Once the local-vs-remote choice is made, [pickRemoteTarget] decides EDGE vs CLOUD
 * using the live `ContextFeatures` (a stable, well-connected device prefers EDGE;
 * a moving or weakly-connected one prefers CLOUD for handoff resilience).
 */
class OffloadingPolicy(
    private val defaultMode: PolicyMode = PolicyMode.BALANCED,
    private val latencyWeight: Float = DEFAULT_LATENCY_WEIGHT,
    private val energyWeight: Float = DEFAULT_ENERGY_WEIGHT,
    private val minComputeSpeedup: Float = DEFAULT_MIN_COMPUTE_SPEEDUP,
    private val userPatienceMs: Float = DEFAULT_USER_PATIENCE_MS,
) {

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        // Hard guardrail: no connectivity → offload is impossible.
        if (!analysis.features.rawSnapshot.network.isOnline) {
            return OffloadingPlan(ExecutionTarget.LOCAL, "offline — running local")
        }

        // ── ExecTime-aware guardrails ──────────────────────────────────────
        val computeSpeedup = analysis.localExecTimeMs /
            analysis.remoteExecTimeMs.coerceAtLeast(MIN_EXEC_TIME_MS)

        // (1) Compute-benefit floor: if the server is barely faster than the
        // phone on the actual work, no network overhead is justifiable.
        // For LIGHT tasks this typically fires; for HEAVY tasks it almost
        // never does — exactly the behaviour we want.
        if (computeSpeedup < minComputeSpeedup) {
            return OffloadingPlan(
                ExecutionTarget.LOCAL,
                "compute speedup %.2fx < floor %.2fx (local %.0fms vs remote %.0fms) — keep local"
                    .format(computeSpeedup, minComputeSpeedup,
                            analysis.localExecTimeMs, analysis.remoteExecTimeMs)
            )
        }

        val battery = analysis.features.rawSnapshot.battery

        // (2) User-patience override: even in ENERGY_FIRST / BALANCED, don't
        // make the user wait > userPatienceMs on local if offload is faster
        // and the battery isn't in the low-power band.
        if (analysis.localExecTimeMs > userPatienceMs &&
            analysis.remoteLatencyMs < analysis.localLatencyMs &&
            !battery.isLowPower
        ) {
            val target = pickRemoteTarget(analysis)
            return OffloadingPlan(
                target,
                "user-patience: local exec %.0fms > %.0fms (speedup %.1fx) → %s"
                    .format(analysis.localExecTimeMs, userPatienceMs, computeSpeedup, target)
            )
        }

        // ── Mode-specific logic ────────────────────────────────────────────
        val mode = selectMode(battery)
        return when (mode) {
            PolicyMode.ENERGY_FIRST -> evaluateEnergyFirst(analysis, computeSpeedup)
            PolicyMode.LATENCY_FIRST -> evaluateLatencyFirst(analysis, computeSpeedup)
            PolicyMode.BALANCED -> evaluateBalanced(analysis, computeSpeedup)
        }
    }

    private fun selectMode(battery: BatteryContext): PolicyMode = when {
        battery.levelPercent < CRITICAL_BATTERY_PERCENT && !battery.isCharging -> PolicyMode.ENERGY_FIRST
        battery.isCharging -> PolicyMode.LATENCY_FIRST
        else -> defaultMode
    }

    private fun evaluateEnergyFirst(a: TaskAnalysis, speedup: Float): OffloadingPlan {
        // Critical battery + not charging: radio TX is the highest-power state on
        // a phone, so we keep the task local rather than risk a brownout mid-send.
        if (a.features.rawSnapshot.battery.isLowPower) {
            return OffloadingPlan(ExecutionTarget.LOCAL, "battery critical — keep local")
        }
        return if (a.localEnergyMj <= a.remoteEnergyMj) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "energy-first: local %.2fmJ ≤ remote %.2fmJ (speedup %.1fx)"
                    .format(a.localEnergyMj, a.remoteEnergyMj, speedup)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "energy-first: remote %.2fmJ < local %.2fmJ (speedup %.1fx) → %s"
                    .format(a.remoteEnergyMj, a.localEnergyMj, speedup, target)
            )
        }
    }

    private fun evaluateLatencyFirst(a: TaskAnalysis, speedup: Float): OffloadingPlan {
        return if (a.localLatencyMs <= a.remoteLatencyMs) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "latency-first: local %.1fms ≤ remote %.1fms (speedup %.1fx)"
                    .format(a.localLatencyMs, a.remoteLatencyMs, speedup)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "latency-first: remote %.1fms < local %.1fms (speedup %.1fx) → %s"
                    .format(a.remoteLatencyMs, a.localLatencyMs, speedup, target)
            )
        }
    }

    private fun evaluateBalanced(a: TaskAnalysis, speedup: Float): OffloadingPlan {
        val localCost = weightedCost(a.localLatencyMs, a.localEnergyMj)
        val remoteCost = weightedCost(a.remoteLatencyMs, a.remoteEnergyMj)
        return if (localCost <= remoteCost) {
            OffloadingPlan(
                ExecutionTarget.LOCAL,
                "balanced: local cost %.2f ≤ remote %.2f (speedup %.1fx)"
                    .format(localCost, remoteCost, speedup)
            )
        } else {
            val target = pickRemoteTarget(a)
            OffloadingPlan(
                target,
                "balanced: remote cost %.2f < local %.2f (speedup %.1fx) → %s"
                    .format(remoteCost, localCost, speedup, target)
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

        // Server must be at least this much faster than the phone on the
        // pure work for any network overhead to be worth paying.
        private const val DEFAULT_MIN_COMPUTE_SPEEDUP = 1.5f

        // Wall-clock budget beyond which we consider local execution
        // "user-painful" and start preferring offload even in ENERGY_FIRST.
        private const val DEFAULT_USER_PATIENCE_MS = 3_000f

        // Floor for the divisor when computing speedup, avoids div-by-zero
        // and guards against pathological estimator outputs.
        private const val MIN_EXEC_TIME_MS = 1f
    }
}

enum class PolicyMode { ENERGY_FIRST, LATENCY_FIRST, BALANCED }
