package com.thesis.middleware.context

/**
 * Bounded in-memory rolling buffer of context snapshots.
 * Persistence to a database is intentionally out of scope here —
 * this store is meant for short-term trend analysis by the policy layer.
 */
class ContextHistoryStore {

    private val buffer = ArrayDeque<ContextSnapshot>(MAX_HISTORY)

    fun save(snapshot: ContextSnapshot) {
        synchronized(buffer) {
            if (buffer.size >= MAX_HISTORY) buffer.removeFirst()
            buffer.addLast(snapshot)
        }
    }

    fun latest(): ContextSnapshot? = synchronized(buffer) { buffer.lastOrNull() }

    fun getRecent(count: Int): List<ContextSnapshot> =
        synchronized(buffer) { buffer.takeLast(count) }

    fun getAll(): List<ContextSnapshot> = synchronized(buffer) { buffer.toList() }

    fun since(millisAgo: Long): List<ContextSnapshot> {
        val cutoff = System.currentTimeMillis() - millisAgo
        return synchronized(buffer) { buffer.filter { it.timestamp >= cutoff } }
    }

    /**
     * Mean of each per-resource score over the window, with rawSnapshot
     * set to the newest entry. Returns null if the window is empty so
     * callers can fall back to a fresh on-demand extraction.
     */
    fun averageScoresOver(windowMs: Long, extractor: FeatureExtractor): ContextFeatures? {
        val window = since(windowMs)
        if (window.isEmpty()) return null
        val features = window.map(extractor::extract)
        val n = features.size.toFloat()
        return ContextFeatures(
            networkScore = features.sumOf { it.networkScore.toDouble() }.toFloat() / n,
            cpuLoadScore = features.sumOf { it.cpuLoadScore.toDouble() }.toFloat() / n,
            batteryScore = features.sumOf { it.batteryScore.toDouble() }.toFloat() / n,
            mobilityScore = features.sumOf { it.mobilityScore.toDouble() }.toFloat() / n,
            rawSnapshot = window.last()
        )
    }

    companion object {
        private const val MAX_HISTORY = 500
    }
}
