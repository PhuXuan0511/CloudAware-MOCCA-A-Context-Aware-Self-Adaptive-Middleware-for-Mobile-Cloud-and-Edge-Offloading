package com.thesis.middleware

import android.app.Application
import com.thesis.middleware.adaptation.ExecutionProxy
import com.thesis.middleware.adaptation.TaskPartitioner
import com.thesis.middleware.communication.ConnectionManager
import com.thesis.middleware.communication.OffloadingClient
import com.thesis.middleware.context.ContextManager
import com.thesis.middleware.decision.MapeLoop
import com.thesis.middleware.decision.policy.OffloadingPolicy
import com.thesis.middleware.decision.policy.RandomForestPolicy
import com.thesis.middleware.metrics.MetricsRecorder
import com.thesis.middleware.settings.EndpointsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Composition root for the MOCCA middleware.
 *
 * Owns the application-scoped [CoroutineScope] and the singleton graph of every
 * middleware component. Long-lived components (`ContextManager`, `MapeLoop`)
 * are constructed here but **not** started — `ContextService` is responsible
 * for starting/stopping them so their lifecycle matches a foreground service,
 * not the process.
 *
 * Anything in the app that needs middleware access casts
 * `applicationContext as MiddlewareApp` and reads from the public vals.
 */
class MiddlewareApp : Application() {

    /** Application-scoped scope: survives Activities, dies when the process dies. */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    // ── Context layer ─────────────────────────────────────────────────────
    val contextManager: ContextManager by lazy {
        ContextManager(context = this, scope = appScope).also { cm ->
            // Restore any persisted debug overrides so they survive process restarts.
            cm.debugNetworkScore = endpointsRepository.debugNetworkScore
            cm.debugBatteryPercent = endpointsRepository.debugBatteryPercent
        }
    }

    // ── Decision layer ────────────────────────────────────────────────────
    private val policy: OffloadingPolicy by lazy { OffloadingPolicy() }

    private val rfPolicy: RandomForestPolicy by lazy { RandomForestPolicy(this) }

    val mapeLoop: MapeLoop by lazy {
        MapeLoop(contextManager = contextManager, scope = appScope, policy = policy).also { ml ->
            ml.debugSpeedup = endpointsRepository.debugSpeedup
            ml.debugRemoteEnergyMj = endpointsRepository.debugRemoteEnergyMj
            ml.rfPolicy = rfPolicy
        }
    }

    // ── Adaptation layer ──────────────────────────────────────────────────
    val taskPartitioner: TaskPartitioner by lazy { TaskPartitioner() }

    // ── Settings ──────────────────────────────────────────────────────────
    val endpointsRepository: EndpointsRepository by lazy { EndpointsRepository(this) }

    // ── Metrics / telemetry ───────────────────────────────────────────────
    val metricsRecorder: MetricsRecorder by lazy { MetricsRecorder(this) }

    // ── Communication layer ───────────────────────────────────────────────
    val connectionManager: ConnectionManager by lazy {
        ConnectionManager().apply {
            edgeEndpoint = endpointsRepository.edgeUrl
            cloudEndpoint = endpointsRepository.cloudUrl
        }
    }

    val offloadingClient: OffloadingClient by lazy {
        // Dev / demo mode: skip OAuth so the backend doesn't need an /auth/token
        // endpoint. Replace `securityManager = null` with a real SecurityManager
        // instance before any deployment that requires authenticated calls.
        OffloadingClient(
            connectionManager = connectionManager,
            contextManager = contextManager,
            securityManager = null,
        )
    }

    /**
     * Reads whole-device power from the battery current sensor. Constructed
     * once — `registerReceiver(null, ...)` for a sticky broadcast is cheap, but
     * the BatteryManager service lookup is not worth repeating per task.
     */
    private val powerCollector by lazy {
        com.thesis.middleware.context.collectors.BatteryCollector(this)
    }

    val executionProxy: ExecutionProxy by lazy {
        ExecutionProxy(
            mapeLoop = mapeLoop,
            offloadingClient = offloadingClient,
            // Read the persisted mode on every task — Settings changes take
            // effect immediately for the next tap, no service restart needed.
            modeProvider = { endpointsRepository.executionMode },
            // Measured energy, to validate EnergyEstimator's hard-coded
            // 800 / 1500 / 50 mW coefficients against something observed.
            powerSampler = { powerCollector.samplePowerMilliWatts() },
        )
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }
}
