package com.thesis.middleware.decision.estimators

import com.thesis.middleware.Fixtures
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cost-model arithmetic documented in `generate_cost_table.py`.
 *
 * The thesis prints those formulas as the system's cost model, so a silent
 * change to a coefficient here would invalidate a published table. Each test
 * spells out the expected value's derivation.
 */
class EstimatorsTest {

    private val latency = LatencyEstimator()
    private val energy = EnergyEstimator()
    private val execTime = ExecutionTimeEstimator()

    // ── Local: baseline / cpuLoadScore ────────────────────────────────────────

    @Test
    fun `local latency scales inversely with cpu headroom`() {
        // MEDIUM baseline 300ms / headroom 0.8 = 375ms
        val f = Fixtures.features(cpuLoadScore = 0.8f)
        val t = Fixtures.task(complexity = TaskComplexity.MEDIUM)
        assertEquals(375f, latency.estimateLocal(t, f), 0.01f)
    }

    @Test
    fun `local latency clamps headroom at 0_05 so a pinned cpu cannot divide by zero`() {
        // 2000ms HEAVY / 0.05 floor = 40_000ms, not infinity.
        val f = Fixtures.features(cpuLoadScore = 0f)
        val t = Fixtures.task(complexity = TaskComplexity.HEAVY)
        assertEquals(40_000f, latency.estimateLocal(t, f), 0.01f)
    }

    @Test
    fun `local energy is cpu power times local exec time`() {
        // 800mW × (300ms / 0.8) / 1000 = 800 × 375 / 1000 = 300mJ
        val f = Fixtures.features(cpuLoadScore = 0.8f)
        val t = Fixtures.task(complexity = TaskComplexity.MEDIUM)
        assertEquals(300f, energy.estimateLocal(t, f), 0.01f)
    }

    // ── Remote latency: rtt + tx + serverExec + queue + mobility ──────────────

    @Test
    fun `remote latency sums every documented term`() {
        // rtt 15 + tx (64000B / (80Mbps × 125 B/ms) = 6.4ms)
        //        + serverExec (2000 × 0.3 = 600) + queue 30 + mobility 0
        val f = Fixtures.features(mobilityScore = 1f)
        val t = Fixtures.task(complexity = TaskComplexity.HEAVY, inputSizeBytes = 64_000)
        assertEquals(651.4f, latency.estimateRemote(t, f), 0.01f)
    }

    @Test
    fun `remote latency adds a mobility penalty when the device is moving`() {
        val still = Fixtures.features(mobilityScore = 1f)
        val moving = Fixtures.features(mobilityScore = 0.2f)
        val t = Fixtures.task(complexity = TaskComplexity.HEAVY)
        // (1 - 0.2) × 200ms = 160ms extra
        assertEquals(
            160f,
            latency.estimateRemote(t, moving) - latency.estimateRemote(t, still),
            0.01f,
        )
    }

    @Test
    fun `remote latency falls back to per-network-type defaults before the first probe`() {
        // rttMs = 0 means "not measured yet" — LTE must not be treated as 0ms RTT.
        val unmeasured = Fixtures.features(
            snapshot = Fixtures.snapshot(
                networkType = NetworkType.LTE,
                rttMs = 0f,
                bandwidthMbps = 0f,
            )
        )
        val t = Fixtures.task(complexity = TaskComplexity.LIGHT, inputSizeBytes = 1_000)
        // rtt 50 (LTE default) + tx (1000 / (30 × 125) = 0.267) + 15 + 30
        assertEquals(95.27f, latency.estimateRemote(t, unmeasured), 0.02f)
    }

    @Test
    fun `offline remote latency is astronomically large so offloading never wins`() {
        val offline = Fixtures.features(
            snapshot = Fixtures.snapshot(
                networkType = NetworkType.NONE,
                rttMs = 0f,
                bandwidthMbps = 0f,
            )
        )
        val t = Fixtures.task(complexity = TaskComplexity.LIGHT, inputSizeBytes = 1_000)
        assertTrue(latency.estimateRemote(t, offline) > 1e8f)
    }

    // ── Remote energy: radio TX + radio idle ──────────────────────────────────

    @Test
    fun `remote energy is radio tx plus idle wait`() {
        // tx  = 1000B / (80 × 125 B/ms) = 0.1ms → 1500mW × 0.1 / 1000 = 0.15mJ
        // wait = rtt 15 + serverExec 15 + queue 30 = 60ms → 50mW × 60 / 1000 = 3.0mJ
        val f = Fixtures.features()
        val t = Fixtures.task(complexity = TaskComplexity.LIGHT, inputSizeBytes = 1_000)
        assertEquals(3.15f, energy.estimateRemote(t, f), 0.01f)
    }

    @Test
    fun `remote energy grows with payload size`() {
        val f = Fixtures.features()
        val small = Fixtures.task(complexity = TaskComplexity.MEDIUM, inputSizeBytes = 1_000)
        val large = Fixtures.task(complexity = TaskComplexity.MEDIUM, inputSizeBytes = 1_000_000)
        assertTrue(energy.estimateRemote(large, f) > energy.estimateRemote(small, f))
    }

    // ── Execution time: compute only, no network ──────────────────────────────

    @Test
    fun `remote exec time excludes the network entirely`() {
        // 2000 × 0.3 + 30 queue = 630ms, on any link.
        val wifi = Fixtures.features(snapshot = Fixtures.snapshot(networkType = NetworkType.WIFI))
        val lte = Fixtures.features(
            snapshot = Fixtures.snapshot(networkType = NetworkType.LTE, rttMs = 300f)
        )
        val t = Fixtures.task(complexity = TaskComplexity.HEAVY)
        assertEquals(630f, execTime.estimate(t, wifi, remote = true), 0.01f)
        assertEquals(630f, execTime.estimate(t, lte, remote = true), 0.01f)
    }

    @Test
    fun `local exec time matches local latency because neither includes the network`() {
        val f = Fixtures.features(cpuLoadScore = 0.5f)
        val t = Fixtures.task(complexity = TaskComplexity.MEDIUM)
        assertEquals(
            latency.estimateLocal(t, f),
            execTime.estimate(t, f, remote = false),
            0.01f,
        )
    }
}
