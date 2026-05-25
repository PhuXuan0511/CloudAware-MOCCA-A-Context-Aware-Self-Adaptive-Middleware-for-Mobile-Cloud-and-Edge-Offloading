package com.thesis.middleware.communication

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
 *  - Reachability is probed with a short-timeout GET to `<endpoint>/status`.
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
) {

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

    private suspend fun reachable(tier: Tier): Boolean {
        if (!hasNetwork()) return false
        val now = System.currentTimeMillis()
        val cached = tier.read()
        if (now - cached.timestamp < reachabilityTtlMs) return cached.reachable

        val fresh = probeOnce(tier.url())
        mutex.withLock { tier.write(fresh) }
        return fresh.reachable
    }

    private suspend fun probeOnce(endpoint: String): Probe = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$endpoint/status").get().build()
        val ok = runCatching {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        Probe(reachable = ok, timestamp = System.currentTimeMillis())
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

    private data class Probe(val reachable: Boolean, val timestamp: Long) {
        companion object { val STALE = Probe(reachable = false, timestamp = Long.MIN_VALUE) }
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
