package com.thesis.middleware.communication

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against a real [MockWebServer] rather than a mocked `Call`, on purpose:
 * the regression this guards was a `Long` overflow in real
 * `System.currentTimeMillis()` arithmetic (`now - Long.MIN_VALUE` wraps to a
 * huge *negative* number), which a mocked HTTP client would never exercise.
 *
 * The bug: [ConnectionManager]'s never-probed sentinel (`Probe.STALE`) used
 * `Long.MIN_VALUE` as its timestamp so that "now minus this" would read as
 * far outside the freshness TTL, forcing an immediate real probe. Instead the
 * subtraction overflowed and wrapped negative, which reads as *younger* than
 * the TTL — so the sentinel's `reachable = false` was returned forever, and
 * `probeOnce()` was never called even once. Every `isEdgeReachable()` /
 * `isCloudReachable()` call returned false regardless of whether the server
 * was actually up, silently sending every ADAPTIVE-mode remote decision to
 * the local fallback path.
 */
class ConnectionManagerTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun manager(ttlMs: Long = 5_000L) = ConnectionManager(reachabilityTtlMs = ttlMs).apply {
        edgeEndpoint = server.url("/").toString().trimEnd('/')
    }

    @Test
    fun `a never-probed endpoint is checked on the very first call`() = runTest {
        // The regression case: before any probe has ever run, edgeProbe holds
        // Probe.STALE. If the freshness check mishandles that sentinel, this
        // returns false without ever hitting the server below.
        server.enqueue(MockResponse().setResponseCode(200))
        val cm = manager()

        assertTrue(
            "first-ever reachability check must actually probe, not trust a " +
                "never-set cache",
            cm.isEdgeReachable(),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a healthy server is reported reachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(manager().isEdgeReachable())
    }

    @Test
    fun `a server returning an error status is reported unreachable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue("5xx must not count as reachable", !manager().isEdgeReachable())
    }

    @Test
    fun `repeated calls within the TTL do not re-probe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val cm = manager(ttlMs = 60_000L)

        assertTrue(cm.isEdgeReachable())
        assertTrue(cm.isEdgeReachable())
        assertTrue(cm.isEdgeReachable())

        assertEquals(
            "second and third calls within the TTL must reuse the cached result",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `lastRttMs is populated after a successful probe`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val cm = manager()

        assertEquals(0f, cm.lastRttMs, 0.001f)   // unmeasured before any probe
        cm.refresh()
        assertTrue("expected a measured RTT after refresh(), got ${cm.lastRttMs}", cm.lastRttMs > 0f)
    }
}
