package com.thesis.middleware.decision.policy

import android.content.Context
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.NetworkType
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingPlan
import com.thesis.middleware.decision.SignalSnapshot
import com.thesis.middleware.decision.TaskAnalysis
import org.json.JSONObject

/**
 * ML-based offloading policy backed by a Random Forest model exported from the
 * Phase 2 training notebook (evaluation/outputs/rf-model.json).
 *
 * The model is loaded once from assets on first use and kept in memory.
 * Inference runs entirely on-device without any ML framework — each tree is
 * traversed as a plain array walk, so prediction takes < 1 ms.
 *
 * Feature vector (must match the training notebook's engineer() function):
 *   0  battery_percent   (float)
 *   1  is_charging       (0 / 1)
 *   2  network_type_rank (NONE=0, LTE=1, WIFI=2, FIVE_G=3)
 *   3  network_score     (float, 0–1)
 *   4  rtt_ms            (float)
 *   5  bandwidth_mbps    (float)
 *   6  cpu_percent       (float)
 *   7  is_stable         (0 / 1)
 *   8  task_complexity   (LIGHT=0, MEDIUM=1, HEAVY=2)
 *   9  est_local_ms      (float)
 *  10  est_remote_ms     (float)
 *  11  speedup           (float, localMs / remoteMs)
 */
class RandomForestPolicy(context: Context) {

    private val appContext = context.applicationContext

    // ── Parsed model (loaded once, lazily) ────────────────────────────────────
    private val model: RfModel by lazy { loadModel() }

    // ── Public API ────────────────────────────────────────────────────────────

    fun evaluate(analysis: TaskAnalysis): OffloadingPlan {
        val features = extractFeatures(analysis)
        val predicted = predict(features)          // "CLOUD", "EDGE", or "LOCAL"
        val target = when (predicted) {
            "EDGE"  -> ExecutionTarget.EDGE
            "CLOUD" -> ExecutionTarget.CLOUD
            else    -> ExecutionTarget.LOCAL
        }
        val speedup = analysis.localLatencyMs /
            analysis.remoteLatencyMs.coerceAtLeast(1f)
        return OffloadingPlan(
            target    = target,
            rule      = "ML_PREDICTED",
            reasoning = "Random Forest predicted $predicted " +
                "(speedup=%.2fx, netScore=%.2f)".format(speedup, analysis.features.networkScore),
            signals   = buildSignals(analysis, speedup),
        )
    }

    // ── Feature extraction ────────────────────────────────────────────────────

    private fun extractFeatures(a: TaskAnalysis): FloatArray {
        val raw     = a.features.rawSnapshot
        val speedup = a.localLatencyMs / a.remoteLatencyMs.coerceAtLeast(1f)
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

    private fun networkRank(type: NetworkType): Float = when (type) {
        NetworkType.NONE   -> 0f
        NetworkType.LTE    -> 1f
        NetworkType.WIFI   -> 2f
        NetworkType.FIVE_G -> 3f
    }

    private fun complexityRank(c: TaskComplexity): Float = when (c) {
        TaskComplexity.LIGHT  -> 0f
        TaskComplexity.MEDIUM -> 1f
        TaskComplexity.HEAVY  -> 2f
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    private fun predict(features: FloatArray): String {
        val votes = FloatArray(model.classes.size)
        for (tree in model.trees) {
            var node = 0
            while (tree.feature[node] >= 0) {
                val f = tree.feature[node]
                node = if (features[f] <= tree.threshold[node])
                    tree.left[node] else tree.right[node]
            }
            val leafProbs = tree.value[node]
            for (i in leafProbs.indices) votes[i] += leafProbs[i]
        }
        val maxIdx = votes.indices.maxByOrNull { votes[it] } ?: 0
        return model.classes[maxIdx]
    }

    // ── Signals snapshot (mirrors OffloadingPolicy.buildSignalSnapshot) ───────

    private fun buildSignals(a: TaskAnalysis, speedup: Float): SignalSnapshot {
        val raw = a.features.rawSnapshot
        return SignalSnapshot(
            batteryPercent     = raw.battery.levelPercent,
            isCharging         = raw.battery.isCharging,
            networkType        = raw.network.type.name,
            networkScore       = a.features.networkScore,
            rttMs              = raw.network.rttMs,
            bandwidthMbps      = raw.network.bandwidthMbps,
            signalDbm          = raw.network.signalStrength,
            cpuUsagePercent    = raw.cpu.usagePercent,
            cpuCores           = raw.cpu.availableCores,
            isStable           = raw.mobility.isStable,
            linearAccelMps2    = raw.mobility.linearAccelerationMps2,
            estLocalLatencyMs  = a.localLatencyMs,
            estRemoteLatencyMs = a.remoteLatencyMs,
            estLocalEnergyMj   = a.localEnergyMj,
            estRemoteEnergyMj  = a.remoteEnergyMj,
            computeSpeedup     = speedup,
            taskComplexity     = a.task.complexity.name,
        )
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadModel(): RfModel {
        val json = JSONObject(
            appContext.assets.open(MODEL_ASSET).bufferedReader().use { it.readText() }
        )

        val classesArr = json.getJSONArray("classes")
        val classes = List(classesArr.length()) { classesArr.getString(it) }

        val treesArr = json.getJSONArray("trees")
        val trees = List(treesArr.length()) { i ->
            val t = treesArr.getJSONObject(i)

            fun intArr(key: String): IntArray {
                val a = t.getJSONArray(key)
                return IntArray(a.length()) { j -> a.getInt(j) }
            }
            fun floatArr(key: String): FloatArray {
                val a = t.getJSONArray(key)
                return FloatArray(a.length()) { j -> a.getDouble(j).toFloat() }
            }
            fun float2d(key: String): Array<FloatArray> {
                val outer = t.getJSONArray(key)
                return Array(outer.length()) { j ->
                    val inner = outer.getJSONArray(j)
                    FloatArray(inner.length()) { k -> inner.getDouble(k).toFloat() }
                }
            }

            Tree(
                feature   = intArr("feature"),
                threshold = floatArr("threshold"),
                left      = intArr("left"),
                right     = intArr("right"),
                value     = float2d("value"),
            )
        }

        return RfModel(classes = classes, trees = trees)
    }

    // ── Model data structures ─────────────────────────────────────────────────

    private data class RfModel(
        val classes: List<String>,
        val trees: List<Tree>,
    )

    private data class Tree(
        val feature:   IntArray,
        val threshold: FloatArray,
        val left:      IntArray,
        val right:     IntArray,
        val value:     Array<FloatArray>,
    )

    companion object {
        private const val MODEL_ASSET = "rf-model.json"
    }
}
