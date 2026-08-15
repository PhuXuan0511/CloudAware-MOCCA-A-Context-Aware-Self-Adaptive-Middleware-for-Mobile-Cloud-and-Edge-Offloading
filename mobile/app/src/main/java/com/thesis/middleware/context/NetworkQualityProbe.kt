package com.thesis.middleware.context

/**
 * Source of a *measured* round-trip time to the offload target.
 *
 * Exists because [com.thesis.middleware.context.collectors.NetworkCollector] can
 * only read what `ConnectivityManager` reports — network transport, cellular
 * signal level, and `linkDownstreamBandwidthKbps`. None of those describe the
 * path to the server: `linkDownstreamBandwidthKbps` is a static per-transport
 * capability estimate, and the Wi-Fi case reports no cellular signal at all. A
 * link that has gone from 10 ms to 1000 ms looks identical through that API, so
 * without an actual probe the middleware is network-*typed*, not network-aware,
 * and `UNSTABLE_NETWORK` can never fire on Wi-Fi.
 *
 * Implemented by [com.thesis.middleware.communication.ConnectionManager], which
 * already issues a timed `/health` GET for reachability — measuring it costs
 * nothing extra. The interface is declared here, in `context`, so the dependency
 * runs communication → context and not the other way round.
 *
 * [refresh] is suspending and driven by `ContextManager`'s collection loop;
 * [lastRttMs] is a cached read so the synchronous snapshot path stays
 * synchronous. The value is therefore up to one probe TTL stale, which is why
 * the collection script waits after changing network conditions.
 */
interface NetworkQualityProbe {

    /** Re-probes if the cached sample has expired. Never throws. */
    suspend fun refresh()

    /**
     * Most recent measured RTT in milliseconds, or `0` when no probe has
     * completed yet. Zero means "unknown", not "instant" — every consumer
     * treats it as "fall back to the per-network-type constant".
     */
    val lastRttMs: Float

    companion object {
        /** No-op probe: RTT stays 0, so scoring behaves exactly as it did before. */
        val NONE: NetworkQualityProbe = object : NetworkQualityProbe {
            override suspend fun refresh() = Unit
            override val lastRttMs: Float get() = 0f
        }
    }
}
