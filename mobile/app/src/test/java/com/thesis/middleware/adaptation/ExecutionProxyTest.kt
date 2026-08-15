package com.thesis.middleware.adaptation

import com.thesis.middleware.Fixtures
import com.thesis.middleware.communication.OffloadingClient
import com.thesis.middleware.communication.RemoteResult
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.MapeLoop
import com.thesis.middleware.decision.OffloadingDecision
import com.thesis.middleware.decision.policy.OffloadingPolicy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Behaviour of the execution proxy's telemetry, which is the sole source of the
 * evaluation CSV.
 *
 * The load-bearing case is the CLOUD_ONLY baseline: it deliberately has no
 * fallback, so a failure propagates to the caller. It must still emit an event
 * on the way out. If it does not, failed runs leave no CSV row, `collect_data.ps1`
 * Session G records only its successes, and the cloud-only baseline looks more
 * reliable than it is — inverting the resilience comparison the thesis makes.
 */
class ExecutionProxyTest {

    private val task = Fixtures.task(name = "matrix-multiply")

    private fun decision(target: ExecutionTarget, rule: String) = OffloadingDecision(
        shouldOffload = target != ExecutionTarget.LOCAL,
        target = target,
        rule = rule,
        reasoning = "test",
        signals = OffloadingPolicy().evaluate(Fixtures.analysis()).signals,
    )

    private fun proxyFor(
        mode: ExecutionMode,
        target: ExecutionTarget,
        rule: String,
        client: OffloadingClient,
    ): ExecutionProxy {
        val loop = mockk<MapeLoop>()
        coEvery { loop.decide(any()) } returns decision(target, rule)
        return ExecutionProxy(loop, client, modeProvider = { mode })
    }

    /**
     * Subscribes to [ExecutionProxy.events] *eagerly*.
     *
     * The flow has `replay = 0`, so a collector launched on the default test
     * dispatcher would not be active until the first suspension point and would
     * miss the emission entirely. [UnconfinedTestDispatcher] starts it
     * immediately; [TestScope.backgroundScope] cancels it when the test ends.
     */
    private fun TestScope.recordEvents(px: ExecutionProxy): List<ExecutionEvent> {
        val events = mutableListOf<ExecutionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            px.events.toList(events)
        }
        return events
    }

    // ── CLOUD_ONLY baseline: failures must still be recorded ──────────────────

    @Test
    fun `cloud-only records the failure before rethrowing`() = runTest {
        val client = mockk<OffloadingClient>()
        coEvery { client.submitToCloud(any()) } throws IOException("network down")
        val px = proxyFor(ExecutionMode.CLOUD_ONLY, ExecutionTarget.CLOUD, "FORCED_CLOUD", client)
        val events = recordEvents(px)

        var thrown: Throwable? = null
        try {
            px.run(task)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("exception must still reach the caller", thrown is IOException)
        assertEquals("a failed cloud-only run must produce exactly one row", 1, events.size)

        val event = events.single()
        assertEquals("FORCED_CLOUD", event.decision.rule)
        assertNotNull("error_message must be populated", event.errorMessage)
        assertTrue(event.errorMessage!!.contains("network down"))
        assertEquals("cloud-only never falls back", false, event.fellBackToLocal)
        assertEquals(0, event.resultSizeBytes)
        assertEquals(ExecutionProxy.LOCAL_TAG, event.executedAt)
        assertNull(event.serverExecMs)
    }

    @Test
    fun `cloud-only success records the server-reported tier and exec time`() = runTest {
        val client = mockk<OffloadingClient>()
        coEvery { client.submitToCloud(any()) } returns
            RemoteResult(ByteArray(64), executedAt = "cloud", serverExecMs = 120f)
        val px = proxyFor(ExecutionMode.CLOUD_ONLY, ExecutionTarget.CLOUD, "FORCED_CLOUD", client)
        val events = recordEvents(px)

        px.run(task)

        val event = events.single()
        assertEquals("cloud", event.executedAt)
        assertEquals(120f, event.serverExecMs!!, 0.01f)
        assertNull(event.errorMessage)
        assertEquals(64, event.resultSizeBytes)
    }

    // ── ADAPTIVE: fallback is recorded, not propagated ────────────────────────

    @Test
    fun `adaptive falls back to local and records the reason`() = runTest {
        val client = mockk<OffloadingClient>()
        coEvery { client.submitToEdge(any()) } throws IOException("edge unreachable")
        val px = proxyFor(
            ExecutionMode.ADAPTIVE, ExecutionTarget.EDGE,
            "HEAVY_COMPUTE_GOOD_BANDWIDTH", client,
        )
        val events = recordEvents(px)

        px.run(task)   // must NOT throw

        val event = events.single()
        assertTrue("fell_back must be set", event.fellBackToLocal)
        assertTrue(event.errorMessage!!.contains("edge unreachable"))
        assertEquals(ExecutionProxy.LOCAL_TAG, event.executedAt)
        assertNull("no server time when the server never answered", event.serverExecMs)
    }

    // ── The edge→cloud forwarding case ───────────────────────────────────────

    @Test
    fun `an edge decision executed on cloud is recorded as such`() = runTest {
        // edge-server forwards to the cloud when ResourceMonitor reports overload.
        val client = mockk<OffloadingClient>()
        coEvery { client.submitToEdge(any()) } returns
            RemoteResult(ByteArray(8), executedAt = "cloud", serverExecMs = 90f)
        val px = proxyFor(
            ExecutionMode.ADAPTIVE, ExecutionTarget.EDGE,
            "HEAVY_COMPUTE_GOOD_BANDWIDTH", client,
        )
        val events = recordEvents(px)

        px.run(task)

        val event = events.single()
        assertEquals(ExecutionTarget.EDGE, event.decision.target)
        assertEquals("cloud", event.executedAt)
        assertEquals(false, event.fellBackToLocal)
    }

    // ── Local paths ──────────────────────────────────────────────────────────

    @Test
    fun `local-only records local execution with no server fields`() = runTest {
        val px = proxyFor(
            ExecutionMode.LOCAL_ONLY, ExecutionTarget.LOCAL,
            "FORCED_LOCAL", mockk(relaxed = true),
        )
        val events = recordEvents(px)

        px.run(task)

        val event = events.single()
        assertEquals(ExecutionProxy.LOCAL_TAG, event.executedAt)
        assertNull(event.serverExecMs)
        assertNull(event.errorMessage)
        assertEquals(false, event.fellBackToLocal)
    }

    @Test
    fun `every run emits exactly one event`() = runTest {
        val client = mockk<OffloadingClient>()
        coEvery { client.submitToCloud(any()) } returns
            RemoteResult(ByteArray(4), "cloud", 10f)
        val px = proxyFor(ExecutionMode.CLOUD_ONLY, ExecutionTarget.CLOUD, "FORCED_CLOUD", client)
        val events = recordEvents(px)

        repeat(5) { px.run(task) }

        assertEquals(5, events.size)
    }
}
