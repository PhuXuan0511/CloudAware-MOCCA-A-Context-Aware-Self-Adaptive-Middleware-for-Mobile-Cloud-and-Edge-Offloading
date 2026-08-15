package com.thesis.middleware.communication

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.thesis.middleware.context.NetworkQualityProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tracks the edge and cloud base URLs and answers "is this tier currently
 * reachable?" for the rest of the middleware.
 *
 *  - Reachability is probed with a short-timeout GET to `<endpoint>/health`.
 *  - The probe is *timed*, and the elapsed time is published as [lastRttMs] —
 *    this is the middleware's only real measurement of the path to the server.
 *    See [NetworkQualityProbe] for why `ConnectivityManager` cannot supply it.
 *  - Probe results are cached for [reachabilityTtlMs] so concurrent decision
 *    paths don't spam the network. The cache is read outside the [mutex] and
 *    only the refresh path serializes — losers of the race just re-read.
 *  - If a [ConnectivityManager] is supplied, the probe is short-circuited
 *    when the device has no validated network.
 */
class ConnectionManager(
    private val httpClient: OkHttpClient = defaultHealthClient(),
    private val connectivityManager: ConnectivityManager? = null,
    private val reachabilityTtlMs: Long = DEFAULT_REACHABILITY_TTL_MS,
) : NetworkQualityProbe {

    var edgeEndpoint: String = "http://edge-server:8001"
    var cloudEndpoint: String = "http://cloud-server:8002"

    private val mutex = Mutex()

    @Volatile private var edgeProbe: Probe = Probe.STALE
    @Volatile private var cloudProbe: Probe = Probe.STALE

    suspend fun isEdgeReachable(): Boolean = reachable(Tier.EDGE)
    suspend fun isCloudReachable(): Boolean = reachable(Tier.CLOUD)

    /** Edge first if it answers, cloud as fallback, edge URL as pessimistic default. */
    suspend fun getBestEndpoint(): String = when {
        isEdgeReachable() -> edgeEndpoint
        isCloudReachable() -> cloudEndpoint
        else -> edgeEndpoint
    }

    // ── NetworkQualityProbe ───────────────────────────────────────────────────

    /**
     * Measured against the *edge*, not the cloud: it is the nearer tier, so it
     * isolates the phone's access link from wide-area distance, and it is the
     * endpoint whose degradation the evaluation actually shapes.
     *
     * TTL-gated like any other reachability read, so driving this from the
     * context loop adds no traffic beyond one `/health` GET per
     * [reachabilityTtlMs].
     */
    override suspend fun refresh() {
        runCatching { reachable(Tier.EDGE) }
    }

    override val lastRttMs: Float get() = edgeProbe.rttMs

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun reachable(tier: Tier): Boolean {
        if (!hasNetwork()) {
            // Record the outage rather than leaving the last healthy sample
            // cached — otherwise a phone that just lost Wi-Fi keeps reporting
            // the RTT it had while connected, and that value lands in the CSV.
            mutex.withLock { tier.write(Probe.OFFLINE) }
            return false
        }
        val now = System.currentTimeMillis()
        val cached = tier.read()
        if (now - cached.timestamp < reachabilityTtlMs) return cached.reachable

        val fresh = probeOnce(tier.url())
        mutex.withLock { tier.write(fresh) }
        return fresh.reachable
    }

    private suspend fun probeOnce(endpoint: String): Probe = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$endpoint/health").get().build()
        val startNs = System.nanoTime()
        val ok = runCatching {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        Probe(
            reachable = ok,
            // A failed probe is the strongest available evidence that the link is
            // bad, so it is published as the timeout rather than dropped. Packet
            // loss shows up here: with netem loss the GET either takes several
            // retransmits or never completes, and both raise the reported RTT.
            rttMs = if (ok) elapsedMs else maxOf(elapsedMs, HEALTH_TIMEOUT_MS.toFloat()),
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun hasNetwork(): Boolean {
        val cm = connectivityManager ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private enum class Tier { EDGE, CLOUD }

    private fun Tier.url(): String = if (this == Tier.EDGE) edgeEndpoint else cloudEndpoint
    private fun Tier.read(): Probe = if (this == Tier.EDGE) edgeProbe else cloudProbe
    private fun Tier.write(p: Probe) { if (this == Tier.EDGE) edgeProbe = p else cloudProbe = p }

    private data class Probe(val reachable: Boolean, val rttMs: Float, val timestamp: Long) {
        companion object {
            /** Never probed: RTT 0 means "unknown", and consumers fall back to defaults. */
            val STALE = Probe(reachable = false, rttMs = 0f, timestamp = Long.MIN_VALUE)

            /**
             * No validated network. Timestamped so it expires like any other
             * sample, and carries the timeout as its RTT so the scoring path
             * sees "worst possible link" rather than "unknown".
             */
            val OFFLINE get() = Probe(
                reachable = false,
                rttMs = HEALTH_TIMEOUT_MS.toFloat(),
                timestamp = System.currentTimeMillis(),
            )
        }
    }

    companion object {
        private const val DEFAULT_REACHABILITY_TTL_MS = 5_000L
        private const val HEALTH_TIMEOUT_MS = 1_500L

        fun defaultHealthClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HEALTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
