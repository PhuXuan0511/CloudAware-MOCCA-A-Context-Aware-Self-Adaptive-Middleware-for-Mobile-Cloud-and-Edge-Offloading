package com.thesis.middleware.context

import android.content.Context
import com.thesis.middleware.context.collectors.*
import kotlinx.coroutines.*

/**
 * Central coordinator for all context collectors.
 * Aggregates context snapshots and forwards them to the FeatureExtractor.
 */
class ContextManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val collectionIntervalMs: Long = DEFAULT_COLLECTION_INTERVAL_MS
) {

    private val networkCollector = NetworkCollector(context)
    private val cpuCollector = CpuCollector()
    private val batteryCollector = BatteryCollector(context)
    private val locationCollector = LocationCollector(context)
    private val mobilityCollector = MobilityCollector(context)

    private val historyStore = ContextHistoryStore()
    private val featureExtractor = FeatureExtractor()

    private var collectionJob: Job? = null

    fun start() {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch {
            while (isActive) {
                historyStore.save(collectSnapshot())
                delay(collectionIntervalMs)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        mobilityCollector.stop()
    }

    /**
     * Debug-only override for demo scenarios where triggering a specific rule
     * (e.g. UNSTABLE_NETWORK) requires a controlled network score that is
     * otherwise impossible to reach on Wi-Fi (min Wi-Fi score ≈ 0.38).
     * Set to null to restore real sensor readings.
     */
    var debugNetworkScore: Float? = null

    fun getLatestFeatures(): ContextFeatures {
        val snapshot = collectSnapshot()
        historyStore.save(snapshot)
        val features = featureExtractor.extract(snapshot)
        return debugNetworkScore?.let { features.copy(networkScore = it) } ?: features
    }

    fun history(): ContextHistoryStore = historyStore

    private fun collectSnapshot(): ContextSnapshot {
        return ContextSnapshot(
            network = networkCollector.collect(),
            cpu = cpuCollector.collect(),
            battery = batteryCollector.collect(),
            location = locationCollector.collect(),
            mobility = mobilityCollector.collect(),
            timestamp = System.currentTimeMillis()
        )
    }

    companion object {
        private const val DEFAULT_COLLECTION_INTERVAL_MS = 2000L
    }
}
