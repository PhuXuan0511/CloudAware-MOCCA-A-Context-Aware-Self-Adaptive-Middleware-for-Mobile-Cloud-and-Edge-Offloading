package com.thesis.middleware.decision.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the on-device forest against the *actual shipped* `rf-model.json`.
 *
 * The alignment test below is the one that matters: the notebook's `engineer()`
 * column order and [RandomForestPolicy.FEATURE_ORDER] are two independent lists
 * that must stay identical. If they drift, nothing crashes — the walker happily
 * compares `battery_percent` against an `rtt_ms` threshold and returns a
 * confident, wrong answer. That is exactly the failure mode that would make the
 * ML mode quietly underperform in the thesis demo with no error to debug.
 */
class RandomForestModelTest {

    private val modelFile: File = listOf(
        File("src/main/assets/rf-model.json"),
        File("app/src/main/assets/rf-model.json"),
        File("mobile/app/src/main/assets/rf-model.json"),
    ).firstOrNull { it.exists() }
        ?: error("rf-model.json not found; run the Phase 2 notebook and copy it into assets/")

    private val shipped by lazy { RandomForestModel.fromJson(modelFile.readText()) }

    // ── Contract with the training notebook ───────────────────────────────────

    @Test
    fun `shipped model feature order matches the on-device extractor`() {
        assertEquals(
            "rf-model.json feature_names drifted from RandomForestPolicy.FEATURE_ORDER — " +
                "retrain or update extractFeatures()",
            RandomForestPolicy.FEATURE_ORDER,
            shipped.featureNames,
        )
    }

    @Test
    fun `shipped model predicts the three execution targets`() {
        assertEquals(listOf("CLOUD", "EDGE", "LOCAL"), shipped.classes)
    }

    @Test
    fun `shipped model has the 50 trees the notebook exports`() {
        assertEquals(50, shipped.treeCount)
    }

    @Test
    fun `shipped model stays small enough to bundle in the apk`() {
        // PHASE2_4 verification checklist: < 100 KB.
        assertTrue(
            "model is ${modelFile.length() / 1024} KB",
            modelFile.length() < 100 * 1024,
        )
    }

    // ── Inference behaviour ───────────────────────────────────────────────────

    @Test
    fun `prediction returns a known class with probabilities summing to one`() {
        // Healthy Wi-Fi, heavy task — any label is acceptable here; we are
        // asserting the output shape, not the policy's opinion.
        val features = floatArrayOf(
            80f,    // battery_percent
            0f,     // is_charging
            2f,     // network_type_rank (WIFI)
            0.9f,   // network_score
            15f,    // rtt_ms
            80f,    // bandwidth_mbps
            20f,    // cpu_percent
            1f,     // is_stable
            2f,     // task_complexity (HEAVY)
            2000f,  // est_local_ms
            500f,   // est_remote_ms
            4f,     // speedup
        )
        val p = shipped.predict(features)
        assertTrue(p.label in shipped.classes)
        assertEquals(1.0f, p.probabilities.sum(), 0.001f)
        assertEquals(p.probabilities.maxOrNull()!!, p.confidence, 0.0001f)
    }

    @Test
    fun `prediction is deterministic for identical inputs`() {
        val features = FloatArray(RandomForestPolicy.FEATURE_ORDER.size) { it.toFloat() }
        assertEquals(shipped.predict(features).label, shipped.predict(features).label)
    }

    @Test
    fun `prediction rejects a feature vector of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            shipped.predict(floatArrayOf(1f, 2f, 3f))
        }
    }

    // ── Tree walker semantics (hand-built model) ──────────────────────────────

    private val stumpJson = """
        {
          "feature_names": ["x"],
          "classes": ["A", "B"],
          "trees": [{
            "feature":   [0, -2, -2],
            "threshold": [10.0, -2.0, -2.0],
            "left":      [1, -1, -1],
            "right":     [2, -1, -1],
            "value":     [[0.5, 0.5], [1.0, 0.0], [0.0, 1.0]]
          }]
        }
    """.trimIndent()

    @Test
    fun `values at or below the threshold go left`() {
        val m = RandomForestModel.fromJson(stumpJson)
        assertEquals("A", m.predict(floatArrayOf(5f)).label)
        // sklearn's split is `x <= threshold`, so the boundary itself goes left.
        assertEquals("A", m.predict(floatArrayOf(10f)).label)
    }

    @Test
    fun `values above the threshold go right`() {
        val m = RandomForestModel.fromJson(stumpJson)
        assertEquals("B", m.predict(floatArrayOf(10.001f)).label)
        assertEquals("B", m.predict(floatArrayOf(99f)).label)
    }

    @Test
    fun `a cyclic tree raises instead of hanging the caller`() {
        // A malformed export must not spin forever on the UI thread.
        val cyclic = """
            {
              "feature_names": ["x"],
              "classes": ["A"],
              "trees": [{
                "feature":   [0],
                "threshold": [10.0],
                "left":      [0],
                "right":     [0],
                "value":     [[1.0]]
              }]
            }
        """.trimIndent()
        val m = RandomForestModel.fromJson(cyclic)
        assertThrows(IllegalStateException::class.java) { m.predict(floatArrayOf(1f)) }
    }

    @Test
    fun `a model with no trees is rejected at parse time`() {
        val empty = """{"feature_names":["x"],"classes":["A"],"trees":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            RandomForestModel.fromJson(empty)
        }
    }

    @Test
    fun `soft voting averages leaf probabilities across trees`() {
        // Two trees disagreeing 1.0/0.0 and 0.0/1.0 must tie at 0.5 each, which
        // a hard majority vote would instead resolve arbitrarily.
        val twoTrees = """
            {
              "feature_names": ["x"],
              "classes": ["A", "B"],
              "trees": [
                {"feature":[-2],"threshold":[-2.0],"left":[-1],"right":[-1],"value":[[1.0,0.0]]},
                {"feature":[-2],"threshold":[-2.0],"left":[-1],"right":[-1],"value":[[0.0,1.0]]}
              ]
            }
        """.trimIndent()
        val p = RandomForestModel.fromJson(twoTrees).predict(floatArrayOf(0f))
        assertEquals(0.5f, p.probabilities[0], 0.0001f)
        assertEquals(0.5f, p.probabilities[1], 0.0001f)
    }
}
