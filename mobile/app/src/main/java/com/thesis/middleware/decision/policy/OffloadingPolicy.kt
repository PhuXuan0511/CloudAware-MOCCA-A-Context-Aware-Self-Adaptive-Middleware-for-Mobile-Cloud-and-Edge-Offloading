package com.thesis.middleware.decision.policy

import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.ContextFeatures
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingPlan
import com.thesis.middleware.decision.SignalSnapshot
import com.thesis.middleware.decision.TaskAnalysis

/**
 * Rule-based decision engine for the MAPE Plan phase.
 *
 * Applies named rules in priority order; the first match wins. The rules
 * implement the supervisor's Phase 1 specification:
 *
 *   1. Low battery → prefer offloading      ([PolicyRule.LOW_BATTERY_OFFLOAD])
 *   2. Unstable network → local execution   ([PolicyRule.UNSTABLE_NETWORK])
 *   3. Heavy CPU task + good bandwidth → edge/cloud
 *                                           ([PolicyRule.HEAVY_COMPUTE_GOOD_BANDWIDTH])
 *   4. Latency-sensitive task → edge        ([PolicyRule.LATENCY_SENSITIVE])
 *
 * Plus two guardrails that sit above the teacher's rules:
 *
 *   - [PolicyRule.OFFLINE]                  — no network at all
 *   - [PolicyRule.COMPUTE_FLOOR_NOT_MET]    — server barely faster than phone
 *
 * And a default fallback:
 *
 *   - [PolicyRule.BALANCED_COST]            — weighted latency+energy score
 *
 * Each rule populates [OffloadingPlan.rule] (the matched rule name) and
 * [OffloadingPlan.signals] (a snapshot of the context values that triggered
 * the rule) so the UI / CSV logger can show the audience exactly *why*
 * the decision was made — the "explainable" requirement from the supervisor.
 */
class OffloadingPolicy(
    private val latencyWeight: Float = DEFAULT_LATENCY_WEIGHT,
    private val energyWeight: Float = DEFAULT_ENERGY_WEIGHT,
    private val minComputeSpeedup: Float = DEFAULT_MIN_COMPUTE_SPEEDUP,
    private val lowBatteryPercent: Int = LOW_BATTERY_PERCENT,
    private val unstableNetworkThreshold: Float = UNSTABLE_NETWORK_THRESHOLD,
    private val goodBandwidthThreshold: Float = GOOD_BANDWIDTH_THRESHOLD,
    private val costComparisonMargin: Float = DEFAULT_COST_MARGIN,
) {
    init {
        require(latencyWeight + energyWeight in 0.999f..1.001f) {
            "latencyWeight + energyWeight must sum to 1.0 " +
                "(got ${latencyWeight + energyWeight})"
        }
    }

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        // Use end-to-end latency (RTT + transmission + compute) for the speedup
        // so the guardrail reflects the user-perceived benefit, not raw CPU speedup.
        // localExecTimeMs/remoteExecTimeMs exclude network and would produce the
        // same ratio for every task on every network type — a fixed 1.11× for LIGHT.
        val speedup = analysis.localLatencyMs /
            analysis.remoteLatencyMs.coerceAtLeast(MIN_EXEC_TIME_MS)
        val signals = buildSignalSnapshot(analysis, speedup)

        return applyOffline(analysis, signals)
            ?: applyUnstableNetwork(analysis, signals)
            ?: applyComputeFloorGuardrail(analysis, signals, speedup)
            ?: applyLatencySensitive(analysis, signals)
            ?: applyLowBatteryOffload(analysis, signals)
            ?: applyHeavyComputeGoodBandwidth(analysis, signals)
            ?: applyBalancedCost(analysis, signals, speedup)
    }

    // ── Rule 0: hard guardrails ─────────────────────────────────────────

    private fun applyOffline(a: TaskAnalysis, s: SignalSnapshot): OffloadingPlan? {
        if (a.features.rawSnapshot.network.isOnline) return null
        return OffloadingPlan(
            target = ExecutionTarget.LOCAL,
            rule = PolicyRule.OFFLINE.id,
            reasoning = "offline — no network connection",
            signals = s,
        )
    }

    private fun applyComputeFloorGuardrail(
        a: TaskAnalysis,
        s: SignalSnapshot,
        speedup: Float,
    ): OffloadingPlan? {
        if (speedup >= minComputeSpeedup) return null
        return OffloadingPlan(
            target = ExecutionTarget.LOCAL,
            rule = PolicyRule.COMPUTE_FLOOR_NOT_MET.id,
            reasoning = ("compute speedup %.2fx < floor %.2fx " +
                "(local %.0fms vs remote %.0fms) — network overhead not worth paying")
                .format(speedup, minComputeSpeedup,
                    a.localExecTimeMs, a.remoteExecTimeMs),
            signals = s,
        )
    }

    // ── Rule 1: unstable network → local (teacher rule) ────────────────

    private fun applyUnstableNetwork(a: TaskAnalysis, s: SignalSnapshot): OffloadingPlan? {
        if (a.features.networkScore >= unstableNetworkThreshold) return null
        return OffloadingPlan(
            target = ExecutionTarget.LOCAL,
            rule = PolicyRule.UNSTABLE_NETWORK.id,
            reasoning = "network score %.2f < %.2f — unstable network, run locally to avoid timeout"
                .format(a.features.networkScore, unstableNetworkThreshold),
            signals = s,
        )
    }

    // ── Rule 2: latency-sensitive → edge (teacher rule) ────────────────

    private fun applyLatencySensitive(a: TaskAnalysis, s: SignalSnapshot): OffloadingPlan? {
        if (a.task.complexity != TaskComplexity.LIGHT) return null
        val target = pickRemoteTarget(a)
        return OffloadingPlan(
            target = target,
            rule = PolicyRule.LATENCY_SENSITIVE.id,
            reasoning = ("latency-sensitive: LIGHT task, picking %s for low RTT " +
                "(remote %.0fms vs local %.0fms)")
                .format(target, a.remoteLatencyMs, a.localLatencyMs),
            signals = s,
        )
    }

    // ── Rule 3: low battery → offload to save CPU energy (teacher rule) ─

    private fun applyLowBatteryOffload(a: TaskAnalysis, s: SignalSnapshot): OffloadingPlan? {
        val battery = a.features.rawSnapshot.battery
        if (battery.isCharging || battery.levelPercent >= lowBatteryPercent) return null
        // Only offload when it actually saves energy — otherwise radio TX would
        // cost more than just running the task locally.
        if (a.remoteEnergyMj >= a.localEnergyMj) return null
        val target = pickRemoteTarget(a)
        return OffloadingPlan(
            target = target,
            rule = PolicyRule.LOW_BATTERY_OFFLOAD.id,
            reasoning = ("low battery %d%%: offload saves %.0fmJ " +
                "(remote %.1fmJ < local %.1fmJ) → %s")
                .format(battery.levelPercent,
                    a.localEnergyMj - a.remoteEnergyMj,
                    a.remoteEnergyMj, a.localEnergyMj, target),
            signals = s,
        )
    }

    // ── Rule 4: heavy compute + good bandwidth → edge/cloud (teacher rule) ─

    private fun applyHeavyComputeGoodBandwidth(a: TaskAnalysis, s: SignalSnapshot): OffloadingPlan? {
        if (a.task.complexity != TaskComplexity.HEAVY) return null
        if (a.features.networkScore < goodBandwidthThreshold) return null
        val target = pickRemoteTarget(a)
        return OffloadingPlan(
            target = target,
            rule = PolicyRule.HEAVY_COMPUTE_GOOD_BANDWIDTH.id,
            reasoning = ("HEAVY task + network score %.2f >= %.2f: offload to %s " +
                "(speedup %.1fx)")
                .format(a.features.networkScore, goodBandwidthThreshold,
                    target, s.computeSpeedup),
            signals = s,
        )
    }

    // ── Default: balanced weighted cost ─────────────────────────────────
    //
    // Cost formula (4 measurable components, 2 weights, 1 hysteresis margin):
    //
    //   LocalCost  = w_lat × T_local  + w_eng × E_local
    //   RemoteCost = w_lat × T_remote + w_eng × E_remote
    //
    // where:
    //   T_local      = phone CPU execution time (ms), from ExecutionTimeEstimator
    //   T_remote     = total remote latency (ms), composed of server compute time
    //                  AND network round-trip (upload + RTT + download). Measured
    //                  as wall-clock from the phone — server compute and network
    //                  cannot be separated cleanly from the client side.
    //   E_local      = phone CPU energy (mJ), = P_cpu × T_local
    //   E_remote     = phone radio TX energy (mJ), = P_radio × T_transmission
    //   w_lat / w_eng = weights from constructor (default 0.5 / 0.5 — equal
    //                   priority for UX latency and battery energy; must sum to 1)
    //
    // Decision with hysteresis margin (anti-flapping under estimator noise):
    //   LOCAL  if  LocalCost ≤ RemoteCost × (1 + margin)
    //   REMOTE otherwise  (pickRemoteTarget decides EDGE vs CLOUD)
    //
    // Margin default 5% prevents oscillation when LocalCost and RemoteCost are
    // near-equal — small noise (~few ms) won't flip the decision back and forth.
    //
    // KNOWN LIMITATION: latency (ms) and energy (mJ) have different magnitudes.
    // A 1000ms latency × 0.6 = 600 dominates 100mJ energy × 0.4 = 40 even though
    // the weight ratio is only 1.5:1. Future work: z-score normalization using
    // historical mean/std from MetricsRecorder CSV.

    private fun applyBalancedCost(
        a: TaskAnalysis,
        s: SignalSnapshot,
        speedup: Float,
    ): OffloadingPlan {
        val localLatCost  = latencyWeight * a.localLatencyMs
        val localEngCost  = energyWeight  * a.localEnergyMj
        val localCost     = localLatCost + localEngCost

        val remoteLatCost = latencyWeight * a.remoteLatencyMs
        val remoteEngCost = energyWeight  * a.remoteEnergyMj
        val remoteCost    = remoteLatCost + remoteEngCost

        val threshold = remoteCost * (1f + costComparisonMargin)
        val localWins = localCost <= threshold

        val target = if (localWins) ExecutionTarget.LOCAL else pickRemoteTarget(a)
        val reasoning = ("balanced cost: local=%.1f (lat %.1f + eng %.1f), " +
            "remote=%.1f (lat %.1f + eng %.1f), margin %.0f%% (speedup %.1fx) → %s")
            .format(
                localCost,  localLatCost,  localEngCost,
                remoteCost, remoteLatCost, remoteEngCost,
                costComparisonMargin * 100f, speedup, target,
            )
        return OffloadingPlan(
            target = target,
            rule = PolicyRule.BALANCED_COST.id,
            reasoning = reasoning,
            signals = s,
        )
    }

    private fun weightedCost(latencyMs: Float, energyMj: Float): Float =
        latencyWeight * latencyMs + energyWeight * energyMj

    /**
     * EDGE when device is stationary AND network is solid; CLOUD otherwise
     * (moving / weak link benefits from cloud's wider coverage and handover-
     * tolerant routing).
     */
    private fun pickRemoteTarget(a: TaskAnalysis): ExecutionTarget {
        val stable = a.features.rawSnapshot.mobility.isStable
        val networkOk = a.features.networkScore >= NETWORK_OK_THRESHOLD
        return if (stable && networkOk) ExecutionTarget.EDGE else ExecutionTarget.CLOUD
    }

    private fun buildSignalSnapshot(a: TaskAnalysis, speedup: Float): SignalSnapshot {
        val raw = a.features.rawSnapshot
        return SignalSnapshot(
            batteryPercent = raw.battery.levelPercent,
            isCharging = raw.battery.isCharging,
            networkType = raw.network.type.name,
            networkScore = a.features.networkScore,
            rttMs = raw.network.rttMs,
            bandwidthMbps = raw.network.bandwidthMbps,
            signalDbm = raw.network.signalStrength,
            cpuUsagePercent = raw.cpu.usagePercent,
            cpuCores = raw.cpu.availableCores,
            isStable = raw.mobility.isStable,
            linearAccelMps2 = raw.mobility.linearAccelerationMps2,
            estLocalLatencyMs = a.localLatencyMs,
            estRemoteLatencyMs = a.remoteLatencyMs,
            estLocalEnergyMj = a.localEnergyMj,
            estRemoteEnergyMj = a.remoteEnergyMj,
            computeSpeedup = speedup,
            taskComplexity = a.task.complexity.name,
        )
    }

    companion object {
        // Equal priority: latency (UX) and energy (battery) weighted the same.
        // Defendable as "no inherent preference between UX and battery —
        // let the raw cost magnitudes decide".
        private const val DEFAULT_LATENCY_WEIGHT = 0.5f
        private const val DEFAULT_ENERGY_WEIGHT = 0.5f

        // ── Teacher-rule thresholds ─────────────────────────────────────
        // Below this aggregate network score we consider the link unstable.
        private const val UNSTABLE_NETWORK_THRESHOLD = 0.3f
        // Above this aggregate network score we consider bandwidth "good".
        private const val GOOD_BANDWIDTH_THRESHOLD = 0.6f
        // Below this battery percent (and not charging) we'd prefer to
        // offload heavy work to preserve the phone's CPU energy.
        private const val LOW_BATTERY_PERCENT = 30

        // ── Guardrail thresholds ───────────────────────────────────────
        // Server must be at least this much faster on pure compute work
        // for any network overhead to be worth paying.
        private const val DEFAULT_MIN_COMPUTE_SPEEDUP = 1.5f
        // Floor for the divisor when computing speedup. Avoids div-by-zero
        // and pathological estimator outputs.
        private const val MIN_EXEC_TIME_MS = 1f

        // ── BALANCED_COST hysteresis margin ────────────────────────────
        // LOCAL is preferred unless REMOTE is at least (1 + margin) cheaper.
        // Default 5% prevents decision flapping when costs are near-equal
        // under estimator noise. Tunable via constructor.
        private const val DEFAULT_COST_MARGIN = 0.05f

        // ── pickRemoteTarget helper ────────────────────────────────────
        private const val NETWORK_OK_THRESHOLD = 0.6f
    }
}

/**
 * Named rules emitted by [OffloadingPolicy]. Each rule has a stable [id]
 * used by the CSV logger and a [displayName] shown in the UI.
 *
 * Order in the enum matches the priority order in [OffloadingPolicy.evaluate]
 * so a reader can scan top-to-bottom to follow the decision flow.
 */
enum class PolicyRule(val id: String, val displayName: String) {
    OFFLINE(
        id = "OFFLINE",
        displayName = "Offline → local execution",
    ),
    UNSTABLE_NETWORK(
        id = "UNSTABLE_NETWORK",
        displayName = "Unstable network → local execution",
    ),
    COMPUTE_FLOOR_NOT_MET(
        id = "COMPUTE_FLOOR_NOT_MET",
        displayName = "Network overhead not worth it → keep local",
    ),
    LATENCY_SENSITIVE(
        id = "LATENCY_SENSITIVE",
        displayName = "Latency-sensitive task → edge",
    ),
    LOW_BATTERY_OFFLOAD(
        id = "LOW_BATTERY_OFFLOAD",
        displayName = "Low battery → offload to save CPU energy",
    ),
    HEAVY_COMPUTE_GOOD_BANDWIDTH(
        id = "HEAVY_COMPUTE_GOOD_BANDWIDTH",
        displayName = "Heavy compute + good bandwidth → edge/cloud",
    ),
    BALANCED_COST(
        id = "BALANCED_COST",
        displayName = "Balanced weighted cost (default)",
    );
}
