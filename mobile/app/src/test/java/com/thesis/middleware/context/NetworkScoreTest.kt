package com.thesis.middleware.context

import com.thesis.middleware.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the property the whole network-degradation session depends on: the
 * network score has to *move* when the link degrades.
 *
 * Before RTT was measured, `computeNetworkScore` read only the transport type,
 * the cellular signal level, and `linkDownstreamBandwidthKbps` — none of which
 * change when the path to the server does. Every Wi-Fi row therefore scored in
 * [0.68, 0.98] whether the link was healthy or carrying a second of injected
 * delay, `UNSTABLE_NETWORK` (threshold 0.30) was unreachable on Wi-Fi, and the
 * `rtt_ms` column was a constant 0. These tests fail if that regresses.
 */
class NetworkScoreTest {

    private val extractor = FeatureExtractor()

    private fun scoreAt(rttMs: Float, signalDbm: Int = 4): Float =
        extractor.extract(Fixtures.snapshot(rttMs = rttMs, signalDbm = signalDbm)).networkScore

    // ── The property that was missing ─────────────────────────────────────────

    @Test
    fun `score falls monotonically as measured RTT rises`() {
        val ladder = listOf(15f, 110f, 310f, 510f, 1010f).map { scoreAt(it) }
        ladder.zipWithNext { better, worse ->
            assertTrue(
                "score must not increase as RTT rises: $ladder",
                worse <= better,
            )
        }
        assertTrue("a healthy link must still score well: ${ladder.first()}", ladder.first() > 0.6f)
        assertTrue("a 1s link must score zero: ${ladder.last()}", ladder.last() == 0f)
    }

    @Test
    fun `an unmeasured link scores exactly as it did before RTT existed`() {
        // rtt = 0 means "no probe has completed", not "instant". A cold start
        // must not be penalised, and must reproduce the capability-only score:
        // 0.4*0.95 + 0.3*(4/4) + 0.3*(80/100).
        assertEquals(0.92f, scoreAt(0f), 0.0001f)
    }

    // ── The policy thresholds these scores have to straddle ───────────────────

    @Test
    fun `a severely degraded link drops below the unstable-network threshold`() {
        // Session C steps 3 and 4 inject 500ms and 1000ms. Both must land under
        // 0.30, or UNSTABLE_NETWORK collects zero rows again.
        assertTrue("500ms scored ${scoreAt(510f)}", scoreAt(510f) < 0.30f)
        assertTrue("1000ms scored ${scoreAt(1010f)}", scoreAt(1010f) < 0.30f)
    }

    @Test
    fun `a healthy link stays above the good-bandwidth threshold`() {
        // If ordinary Wi-Fi dipped under 0.60, HEAVY_COMPUTE_GOOD_BANDWIDTH
        // would stop firing in the baseline session and every heavy task would
        // fall through to BALANCED_COST.
        assertTrue("15ms scored ${scoreAt(15f)}", scoreAt(15f) >= 0.60f)
        assertTrue("80ms scored ${scoreAt(80f)}", scoreAt(80f) >= 0.60f)
    }

    @Test
    fun `a mildly degraded link sits between the two thresholds`() {
        // Session C step 2 is labelled "near the unstable boundary" — it is only
        // an informative condition if it actually lands between the thresholds
        // rather than with the healthy rows.
        val mild = scoreAt(310f)
        assertTrue("310ms scored $mild, expected below the good-bandwidth threshold", mild < 0.60f)
    }

    // ── Interaction with the capability term ──────────────────────────────────

    @Test
    fun `RTT scales the capability term rather than replacing it`() {
        // A phone with no cellular bars on the same degraded link must score no
        // better than one with full bars — the measurement multiplies what the
        // transport claims, it does not override it.
        assertTrue(scoreAt(310f, signalDbm = 0) <= scoreAt(310f, signalDbm = 4))
    }

    @Test
    fun `an offline snapshot scores zero`() {
        // Mirrors what NetworkCollector emits with no active network:
        // NetworkContext(NONE, 0f, 0f, 0).
        val offline = Fixtures.snapshot(
            networkType = NetworkType.NONE,
            rttMs = 0f,
            bandwidthMbps = 0f,
            signalDbm = 0,
        )
        assertEquals(0f, extractor.extract(offline).networkScore, 0.0001f)
    }
}
