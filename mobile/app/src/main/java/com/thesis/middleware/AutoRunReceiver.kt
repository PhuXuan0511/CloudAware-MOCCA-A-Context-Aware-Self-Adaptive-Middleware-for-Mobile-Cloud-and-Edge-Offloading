package com.thesis.middleware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thesis.middleware.adaptation.ExecutionMode
import com.thesis.middleware.context.MovementState
import com.thesis.middleware.demo.DemoTasks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ADB-triggered receiver for automated data collection (Phase 1 RF training).
 *
 * Five actions, all fired via `adb shell am broadcast`:
 *
 *   RUN_TASK        --es task <name> --ei count <n> --el delay_ms <ms>
 *   SET_MODE        --es mode <ADAPTIVE|LOCAL_ONLY|CLOUD_ONLY>
 *   SET_DEBUG       --ef speedup <f>  --ef network_score <f>  --ef remote_energy_mj <f>
 *   CLEAR_DEBUG     (no extras)
 *   SET_ENDPOINTS   --es edge_url <url>  --es cloud_url <url>
 *
 * Task names: echo | sha256 | image-grayscale | matrix-multiply | video-frame-edges
 * Use -1 for any SET_DEBUG float to clear that specific override.
 *
 * SET_ENDPOINTS exists for collect_data_remote.ps1: when the edge/cloud
 * servers run on a different machine than the one driving the emulator/phone,
 * pointing the app at them by hand through SettingsActivity would mean a
 * manual tap for every fresh install. This mirrors exactly what
 * SettingsActivity.onSave() does — persist to EndpointsRepository, then
 * live-mutate the running ConnectionManager so the change takes effect
 * without restarting the app.
 */
class AutoRunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MiddlewareApp
        when (intent.action) {
            ACTION_RUN_TASK      -> handleRunTask(context, app, intent)
            ACTION_SET_MODE      -> handleSetMode(app, intent)
            ACTION_SET_DEBUG     -> handleSetDebug(app, intent)
            ACTION_CLEAR_DEBUG   -> handleClearDebug(app)
            ACTION_SET_ENDPOINTS -> handleSetEndpoints(app, intent)
        }
    }

    private fun handleRunTask(context: Context, app: MiddlewareApp, intent: Intent) {
        val taskName = intent.getStringExtra("task") ?: return
        val count    = intent.getIntExtra("count", 1)
        val delayMs  = intent.getLongExtra("delay_ms", DEFAULT_DELAY_MS)
        // Optional --ei size: payload bytes for sha256, image edge length for
        // image-grayscale, matrix dimension for matrix-multiply. Omit or pass
        // <= 0 for the task's default. Varying it exercises the transmission
        // term of the cost model, which is otherwise constant per task type.
        val size     = intent.getIntExtra("size", -1)

        app.appScope.launch {
            repeat(count) {
                val task = when (taskName) {
                    "echo"              -> DemoTasks.echo()
                    "sha256"            -> if (size > 0) DemoTasks.sha256(size) else DemoTasks.sha256()
                    "image-grayscale"   -> if (size > 0) DemoTasks.imageGrayscale(size) else DemoTasks.imageGrayscale()
                    "matrix-multiply"   -> if (size > 0) DemoTasks.matrixMultiply(size) else DemoTasks.matrixMultiply()
                    "video-frame-edges" -> DemoTasks.videoFrameEdges(context)
                    else -> return@repeat
                }
                runCatching { app.executionProxy.run(task) }
                delay(delayMs)
            }
        }
    }

    private fun handleSetMode(app: MiddlewareApp, intent: Intent) {
        val modeName = intent.getStringExtra("mode") ?: return
        val mode = runCatching { ExecutionMode.valueOf(modeName) }.getOrNull() ?: return
        app.endpointsRepository.executionMode = mode
    }

    private fun handleSetDebug(app: MiddlewareApp, intent: Intent) {
        if (intent.hasExtra("speedup")) {
            val v = intent.getFloatExtra("speedup", SENTINEL)
            val override = if (v < 0f) null else v
            app.endpointsRepository.debugSpeedup = override
            app.mapeLoop.debugSpeedup = override
        }
        if (intent.hasExtra("network_score")) {
            val v = intent.getFloatExtra("network_score", SENTINEL)
            val override = if (v < 0f) null else v
            app.endpointsRepository.debugNetworkScore = override
            app.contextManager.debugNetworkScore = override
        }
        if (intent.hasExtra("remote_energy_mj")) {
            val v = intent.getFloatExtra("remote_energy_mj", SENTINEL)
            val override = if (v < 0f) null else v
            app.endpointsRepository.debugRemoteEnergyMj = override
            app.mapeLoop.debugRemoteEnergyMj = override
        }
        // --es movement_state STATIONARY|WALKING|VEHICLE, or "NONE" to clear.
        // Lets a session exercise pickRemoteTarget's EDGE/CLOUD branch and the
        // mobility latency penalty without physically moving the phone.
        if (intent.hasExtra("movement_state")) {
            val name = intent.getStringExtra("movement_state")
            app.contextManager.debugMovementState =
                if (name.isNullOrEmpty() || name == "NONE") null
                else runCatching { MovementState.valueOf(name) }.getOrNull()
        }
    }

    private fun handleSetEndpoints(app: MiddlewareApp, intent: Intent) {
        intent.getStringExtra("edge_url")?.let { url ->
            app.endpointsRepository.edgeUrl = url
            app.connectionManager.edgeEndpoint = app.endpointsRepository.edgeUrl
        }
        intent.getStringExtra("cloud_url")?.let { url ->
            app.endpointsRepository.cloudUrl = url
            app.connectionManager.cloudEndpoint = app.endpointsRepository.cloudUrl
        }
    }

    private fun handleClearDebug(app: MiddlewareApp) {
        app.endpointsRepository.debugSpeedup = null
        app.endpointsRepository.debugNetworkScore = null
        app.endpointsRepository.debugRemoteEnergyMj = null
        app.mapeLoop.debugSpeedup = null
        app.mapeLoop.debugRemoteEnergyMj = null
        app.contextManager.debugNetworkScore = null
    }

    companion object {
        const val ACTION_RUN_TASK      = "com.thesis.middleware.RUN_TASK"
        const val ACTION_SET_MODE      = "com.thesis.middleware.SET_MODE"
        const val ACTION_SET_DEBUG     = "com.thesis.middleware.SET_DEBUG"
        const val ACTION_CLEAR_DEBUG   = "com.thesis.middleware.CLEAR_DEBUG"
        const val ACTION_SET_ENDPOINTS = "com.thesis.middleware.SET_ENDPOINTS"

        private const val DEFAULT_DELAY_MS = 3000L
        private const val SENTINEL         = -1f
    }
}
