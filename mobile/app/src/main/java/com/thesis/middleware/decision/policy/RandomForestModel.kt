package com.thesis.middleware.decision.policy

import org.json.JSONObject

/**
 * Pure in-memory Random Forest, parsed from the JSON exported by
 * `evaluation/notebooks/random-forest-training.ipynb` (cell 12).
 *
 * Split out of [RandomForestPolicy] so the parser and the tree walker can be
 * unit-tested on the JVM against the *actual shipped* `assets/rf-model.json`,
 * without an Android `Context`. The most important thing those tests protect is
 * feature-order alignment: the notebook's `engineer()` column order and
 * [RandomForestPolicy.FEATURE_ORDER] must agree, or every prediction silently
 * reads the wrong column (train/serve skew) with no crash and no error log.
 *
 * Node encoding (sklearn's `tree_` arrays, exported verbatim):
 *  - `feature[i] < 0` marks a leaf; `value[i]` holds the class probabilities.
 *  - otherwise descend `left[i]` if `x[feature[i]] <= threshold[i]`, else `right[i]`.
 */
class RandomForestModel(
    val featureNames: List<String>,
    val classes: List<String>,
    private val trees: List<Tree>,
    /**
     * Categorical encodings the notebook used when building the training
     * features, e.g. `network_rank = {NONE:0, LTE:1, WIFI:2, FIVE_G:3}`.
     *
     * Exported so the on-device extractor can be checked against them. Without
     * that check a renamed or reordered category silently shifts a feature —
     * `feature_names` is unchanged, so the feature-order assertion passes while
     * every prediction reads a differently-encoded column.
     */
    val encodings: Map<String, Map<String, Int>> = emptyMap(),
) {

    val treeCount: Int get() = trees.size

    /** Predicted class label plus the normalised per-class vote share. */
    data class Prediction(val label: String, val probabilities: FloatArray) {
        /** Probability of [label], i.e. the winning class's vote share. */
        val confidence: Float get() = probabilities.maxOrNull() ?: 0f

        override fun equals(other: Any?): Boolean =
            other is Prediction &&
                label == other.label &&
                probabilities.contentEquals(other.probabilities)

        override fun hashCode(): Int = 31 * label.hashCode() + probabilities.contentHashCode()
    }

    /**
     * Soft-voting prediction: sums leaf probability vectors across all trees and
     * takes the argmax. This matches sklearn's `RandomForestClassifier.predict`,
     * which averages `predict_proba` rather than taking a hard majority vote —
     * a hard vote would diverge from the notebook's reported accuracy.
     */
    fun predict(features: FloatArray): Prediction {
        require(features.size == featureNames.size) {
            "expected ${featureNames.size} features (${featureNames.joinToString()}), " +
                "got ${features.size}"
        }
        val votes = FloatArray(classes.size)
        for (tree in trees) {
            val leaf = tree.leafFor(features)
            for (i in leaf.indices) votes[i] += leaf[i]
        }
        val total = votes.sum()
        val probs = if (total > 0f) FloatArray(votes.size) { votes[it] / total } else votes
        var maxIdx = 0
        for (i in probs.indices) if (probs[i] > probs[maxIdx]) maxIdx = i
        return Prediction(classes[maxIdx], probs)
    }

    class Tree(
        private val feature: IntArray,
        private val threshold: FloatArray,
        private val left: IntArray,
        private val right: IntArray,
        private val value: Array<FloatArray>,
    ) {
        fun leafFor(x: FloatArray): FloatArray {
            var node = 0
            var steps = 0
            while (feature[node] >= 0) {
                node = if (x[feature[node]] <= threshold[node]) left[node] else right[node]
                // A malformed/cyclic export would otherwise hang the UI thread.
                if (++steps > feature.size) {
                    throw IllegalStateException("tree traversal exceeded node count — malformed model")
                }
            }
            return value[node]
        }
    }

    companion object {

        fun fromJson(json: String): RandomForestModel {
            val root = JSONObject(json)

            val featureArr = root.getJSONArray("feature_names")
            val featureNames = List(featureArr.length()) { featureArr.getString(it) }

            val classArr = root.getJSONArray("classes")
            val classes = List(classArr.length()) { classArr.getString(it) }

            val treeArr = root.getJSONArray("trees")
            require(treeArr.length() > 0) { "model contains no trees" }

            val trees = List(treeArr.length()) { i ->
                val t = treeArr.getJSONObject(i)

                fun ints(key: String): IntArray {
                    val a = t.getJSONArray(key)
                    return IntArray(a.length()) { j -> a.getInt(j) }
                }
                fun floats(key: String): FloatArray {
                    val a = t.getJSONArray(key)
                    return FloatArray(a.length()) { j -> a.getDouble(j).toFloat() }
                }
                fun floats2d(key: String): Array<FloatArray> {
                    val outer = t.getJSONArray(key)
                    return Array(outer.length()) { j ->
                        val inner = outer.getJSONArray(j)
                        FloatArray(inner.length()) { k -> inner.getDouble(k).toFloat() }
                    }
                }

                Tree(
                    feature = ints("feature"),
                    threshold = floats("threshold"),
                    left = ints("left"),
                    right = ints("right"),
                    value = floats2d("value"),
                )
            }

            val encodings = ENCODING_KEYS.mapNotNull { key ->
                val obj = root.optJSONObject(key) ?: return@mapNotNull null
                key to obj.keys().asSequence().associateWith { obj.getInt(it) }
            }.toMap()

            return RandomForestModel(featureNames, classes, trees, encodings)
        }

        /** Categorical encoding maps the notebook exports alongside the trees. */
        private val ENCODING_KEYS =
            listOf("network_rank", "task_complexity", "complexity_rank")
    }
}
