package com.thesis.middleware.decision.policy

import com.thesis.middleware.Fixtures
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.MovementState
import com.thesis.middleware.context.NetworkType
import com.thesis.middleware.decision.ExecutionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural spec for the 7-rule decision engine.
 *
 * These tests are the executable version of the rule table in the thesis: each
 * one pins a rule's trigger condition, its chosen target, and — critically —
 * its position in the precedence chain. Rule *ordering* was the source of every
 * "wrong rule fired" bug in the git history, and it is invisible from reading
 * any single rule in isolation.
 */
class OffloadingPolicyTest {

    private val policy = OffloadingPolicy()

    // ── Precedence ────────────────────────────────────────────────────────────

    @Test
    fun `offline beats every other rule`() {
        // HEAVY task + low battery + cheap remote energy would normally offload.
        val plan = policy.evaluate(
            Fixtures.analysis(
                networkType = NetworkType.NONE,
                networkScore = 0f,
                complexity = TaskComplexity.HEAVY,
                batteryPercent = 10,
            )
        )
        assertEquals(PolicyRule.OFFLINE.id, plan.rule)
        assertEquals(ExecutionTarget.LOCAL, plan.target)
    }

    @Test
    fun `unstable network beats low battery offload`() {
        val plan = policy.evaluate(
            Fixtures.analysis(networkScore = 0.2f, batteryPercent = 10)
        )
        assertEquals(PolicyRule.UNSTABLE_NETWORK.id, plan.rule)
        assertEquals(ExecutionTarget.LOCAL, plan.target)
    }

    @Test
    fun `compute floor beats latency sensitive`() {
        // LIGHT task would hit LATENCY_SENSITIVE, but remote is barely faster.
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.LIGHT,
                localLatencyMs = 100f,
                remoteLatencyMs = 90f,   // speedup 1.11x < 1.5x floor
            )
        )
        assertEquals(PolicyRule.COMPUTE_FLOOR_NOT_MET.id, plan.rule)
        assertEquals(ExecutionTarget.LOCAL, plan.target)
    }

    @Test
    fun `latency sensitive beats low battery offload for light tasks`() {
        val plan = policy.evaluate(
            Fixtures.analysis(complexity = TaskComplexity.LIGHT, batteryPercent = 10)
        )
        assertEquals(PolicyRule.LATENCY_SENSITIVE.id, plan.rule)
    }

    // ── Individual rules ──────────────────────────────────────────────────────

    @Test
    fun `unstable network fires just below the 0_30 threshold`() {
        assertEquals(
            PolicyRule.UNSTABLE_NETWORK.id,
            policy.evaluate(Fixtures.analysis(networkScore = 0.29f)).rule
        )
    }

    @Test
    fun `unstable network does not fire exactly at the 0_30 threshold`() {
        assertTrue(
            policy.evaluate(Fixtures.analysis(networkScore = 0.30f)).rule
                != PolicyRule.UNSTABLE_NETWORK.id
        )
    }

    @Test
    fun `compute floor does not fire exactly at 1_5x speedup`() {
        val plan = policy.evaluate(
            Fixtures.analysis(localLatencyMs = 150f, remoteLatencyMs = 100f)
        )
        assertTrue(plan.rule != PolicyRule.COMPUTE_FLOOR_NOT_MET.id)
    }

    @Test
    fun `light task goes to edge when stationary on a good link`() {
        val plan = policy.evaluate(Fixtures.analysis(complexity = TaskComplexity.LIGHT))
        assertEquals(PolicyRule.LATENCY_SENSITIVE.id, plan.rule)
        assertEquals(ExecutionTarget.EDGE, plan.target)
    }

    @Test
    fun `low battery offloads when remote energy is cheaper`() {
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.MEDIUM,
                batteryPercent = 25,
                localEnergyMj = 900f,
                remoteEnergyMj = 100f,
            )
        )
        assertEquals(PolicyRule.LOW_BATTERY_OFFLOAD.id, plan.rule)
        assertTrue(plan.target != ExecutionTarget.LOCAL)
    }

    @Test
    fun `low battery does not offload when the radio costs more than the cpu`() {
        // The energy gate is what stops "low battery" from becoming "always
        // offload" on a weak link, where radio TX is the expensive part.
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.MEDIUM,
                batteryPercent = 25,
                localEnergyMj = 100f,
                remoteEnergyMj = 900f,
            )
        )
        assertTrue(plan.rule != PolicyRule.LOW_BATTERY_OFFLOAD.id)
    }

    @Test
    fun `low battery rule is skipped while charging`() {
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.MEDIUM,
                batteryPercent = 25,
                isCharging = true,
            )
        )
        assertTrue(plan.rule != PolicyRule.LOW_BATTERY_OFFLOAD.id)
    }

    @Test
    fun `heavy task with good bandwidth offloads`() {
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.HEAVY,
                networkScore = 0.7f,
                batteryPercent = 80,
            )
        )
        assertEquals(PolicyRule.HEAVY_COMPUTE_GOOD_BANDWIDTH.id, plan.rule)
        assertTrue(plan.target != ExecutionTarget.LOCAL)
    }

    @Test
    fun `heavy task on a mediocre link falls through to balanced cost`() {
        val plan = policy.evaluate(
            Fixtures.analysis(complexity = TaskComplexity.HEAVY, networkScore = 0.5f)
        )
        assertEquals(PolicyRule.BALANCED_COST.id, plan.rule)
    }

    @Test
    fun `medium task on a healthy device falls through to balanced cost`() {
        val plan = policy.evaluate(
            Fixtures.analysis(complexity = TaskComplexity.MEDIUM, networkScore = 0.5f)
        )
        assertEquals(PolicyRule.BALANCED_COST.id, plan.rule)
    }

    // ── BALANCED_COST arithmetic ──────────────────────────────────────────────

    @Test
    fun `balanced cost keeps work local when costs tie within the hysteresis margin`() {
        // Identical local and remote costs: the 5% margin must break the tie
        // toward LOCAL, otherwise estimator noise flaps the decision.
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.MEDIUM,
                networkScore = 0.5f,
                localLatencyMs = 400f,
                remoteLatencyMs = 200f,
                localEnergyMj = 100f,
                remoteEnergyMj = 300f,
            )
        )
        assertEquals(PolicyRule.BALANCED_COST.id, plan.rule)
        assertEquals(ExecutionTarget.LOCAL, plan.target)
    }

    @Test
    fun `balanced cost offloads when remote is clearly cheaper`() {
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.MEDIUM,
                networkScore = 0.5f,
                localLatencyMs = 2000f,
                remoteLatencyMs = 200f,
                localEnergyMj = 1600f,
                remoteEnergyMj = 50f,
            )
        )
        assertEquals(PolicyRule.BALANCED_COST.id, plan.rule)
        assertTrue(plan.target != ExecutionTarget.LOCAL)
    }

    // ── pickRemoteTarget ──────────────────────────────────────────────────────

    @Test
    fun `remote target is cloud when the device is moving`() {
        val plan = policy.evaluate(
            Fixtures.analysis(
                complexity = TaskComplexity.HEAVY,
                networkScore = 0.7f,
                movement = MovementState.VEHICLE,
            )
        )
        assertEquals(ExecutionTarget.CLOUD, plan.target)
    }

    @Test
    fun `remote target is edge when stationary on a good link`() {
        val plan = policy.evaluate(
            Fixtures.analysis(complexity = TaskComplexity.HEAVY, networkScore = 0.7f)
        )
        assertEquals(ExecutionTarget.EDGE, plan.target)
    }

    // ── Invariants ────────────────────────────────────────────────────────────

    @Test
    fun `weights must sum to one`() {
        assertThrows(IllegalArgumentException::class.java) {
            OffloadingPolicy(latencyWeight = 0.7f, energyWeight = 0.7f)
        }
    }

    @Test
    fun `every plan carries a populated signal snapshot`() {
        val plan = policy.evaluate(Fixtures.analysis(batteryPercent = 42))
        assertEquals(42, plan.signals.batteryPercent)
        assertEquals("HEAVY", plan.signals.taskComplexity)
        assertTrue(plan.reasoning.isNotBlank())
    }

    @Test
    fun `speedup in the snapshot is end-to-end latency ratio not exec-time ratio`() {
        // Regression guard: an earlier version divided execution times, which
        // produced a constant 1.11x for every LIGHT task on every network.
        val plan = policy.evaluate(
            Fixtures.analysis(
                localLatencyMs = 1000f,
                remoteLatencyMs = 250f,
                localExecTimeMs = 1000f,
                remoteExecTimeMs = 630f,
            )
        )
        assertEquals(4.0f, plan.signals.computeSpeedup, 0.001f)
    }
}
