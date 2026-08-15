package com.thesis.middleware.decision.policy

import android.content.Context
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.NetworkType
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingPlan
import com.thesis.middleware.decision.SignalSnapshot
import com.thesis.middleware.decision.TaskAnalysis

/**
 * ML-based offloading policy backed by a Random Forest model exported from the
 * Phase 2 training notebook (evaluation/outputs/rf-model.json).
 *
 * The model is loaded once from assets on first use and kept in memory.
 * Inference runs entirely on-device without any ML framework — each tree is
 * traversed as a plain array walk, so prediction takes < 1 ms. Parsing and
 * traversal live in [RandomForestModel]; this class owns only the Android asset
 * load and the mapping from [TaskAnalysis] to a feature vector.
 *
 * Emits the same [OffloadingPlan] shape as [OffloadingPolicy], so the log, CSV
 * recorder, and dashboard cards work unchanged.
 */
class RandomForestPolicy(context: Context) {

    private val appContext = context.applicationContext

    private val model: RandomForestModel by lazy {
        RandomForestModel.fromJson(
            appContext.assets.open(MODEL_ASSET).bufferedReader().use { it.readText() }
        ).also(::assertFeatureOrder)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        val speedup = analysis.localLatencyMs /
            analysis.remoteLatencyMs.coerceAtLeast(MIN_LATENCY_MS)
        val prediction = model.predict(extractFeatures(analysis))
        val target = when (prediction.label) {
            "EDGE" -> ExecutionTarget.EDGE
            "CLOUD" -> ExecutionTarget.CLOUD
            else -> ExecutionTarget.LOCAL
        }
        return OffloadingPlan(
            target = target,
            // Per-target rule id (not a flat "ML_PREDICTED") so the CSV `rule`
            // column stays directly comparable with the rule-based ids during
            // offline analysis, and matches PHASE2_4_ON_DEVICE_DEPLOYMENT.md.
            rule = "ML_PREDICTED_${target.name}",
            reasoning = "Random Forest vote: " + votesText(prediction) +
                " (speedup=%.2fx, netScore=%.2f)".format(speedup, analysis.features.networkScore),
            signals = buildSignals(analysis, speedup),
        )
    }

    private fun votesText(p: RandomForestModel.Prediction): String =
        model.classes.indices
            .sortedByDescending { p.probabilities[it] }
            .joinToString(", ") { "${model.classes[it]}=%.2f".format(p.probabilities[it]) }

    // ── Feature extraction ────────────────────────────────────────────────────

    /**
     * Builds the feature vector in [FEATURE_ORDER] order.
     *
     * `internal` so `RandomForestPolicyFeatureTest` can assert this ordering
     * against the shipped model's `feature_names` — a mismatch here is silent
     * train/serve skew, not a crash.
     */
    internal fun extractFeatures(a: TaskAnalysis): FloatArray {
        val raw = a.features.rawSnapshot
        val speedup = a.localLatencyMs / a.remoteLatencyMs.coerceAtLeast(MIN_LATENCY_MS)
        return floatArrayOf(
            raw.battery.levelPercent.toFloat(),
            if (raw.battery.isCharging) 1f else 0f,
            networkRank(raw.network.type),
            a.features.networkScore,
            raw.network.rttMs,
            raw.network.bandwidthMbps,
            raw.cpu.usagePercent,
            if (raw.mobility.isStable) 1f else 0f,
            complexityRank(a.task.complexity),
            a.localLatencyMs,
            a.remoteLatencyMs,
            speedup,
        )
    }

    /**
     * Fails fast at model-load time if the exported `feature_names` no longer
     * match [FEATURE_ORDER]. Without this, retraining with a reordered or
     * renamed feature set produces a model that loads cleanly and predicts
     * confidently from the wrong columns.
     */
    private fun assertFeatureOrder(m: RandomForestModel) {
        check(m.featureNames == FEATURE_ORDER) {
            "rf-model.json feature order ${m.featureNames} does not match " +
                "RandomForestPolicy.FEATURE_ORDER $FEATURE_ORDER — retrain or update the extractor"
        }
    }

    private fun networkRank(type: NetworkType): Float = when (type) {
        NetworkType.NONE -> 0f
        NetworkType.LTE -> 1f
        NetworkType.WIFI -> 2f
        NetworkType.FIVE_G -> 3f
    }

    private fun complexityRank(c: TaskComplexity): Float = when (c) {
        TaskComplexity.LIGHT -> 0f
        TaskComplexity.MEDIUM -> 1f
        TaskComplexity.HEAVY -> 2f
    }

    // ── Signals snapshot (mirrors OffloadingPolicy.buildSignalSnapshot) ───────

    private fun buildSignals(a: TaskAnalysis, speedup: Float): SignalSnapshot {
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
        private const val MODEL_ASSET = "rf-model.json"
        private const val MIN_LATENCY_MS = 1f

        /**
         * Feature ordering contract with the training notebook's `engineer()`
         * function. Must stay identical to the `feature_names` array in
         * `rf-model.json`; enforced by [assertFeatureOrder] at runtime and by
         * unit test at build time.
         */
        val FEATURE_ORDER: List<String> = listOf(
            "battery_percent",
            "is_charging",
            "network_type_rank",
            "network_score",
            "rtt_ms",
            "bandwidth_mbps",
            "cpu_percent",
            "is_stable",
            "task_complexity",
            "est_local_ms",
            "est_remote_ms",
            "speedup",
        )
    }
}
