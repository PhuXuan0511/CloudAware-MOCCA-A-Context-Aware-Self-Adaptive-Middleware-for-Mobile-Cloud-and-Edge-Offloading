package com.thesis.middleware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thesis.middleware.adaptation.ExecutionMode
import com.thesis.middleware.demo.DemoTasks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ADB-triggered receiver for automated data collection (Phase 1 RF training).
 *
 * Four actions, all fired via `adb shell am broadcast`:
 *
 *   RUN_TASK        --es task <name> --ei count <n> --el delay_ms <ms>
 *   SET_MODE        --es mode <ADAPTIVE|LOCAL_ONLY|CLOUD_ONLY>
 *   SET_DEBUG       --ef speedup <f>  --ef network_score <f>  --ef remote_energy_mj <f>
 *   CLEAR_DEBUG     (no extras)
 *
 * Task names: echo | sha256 | image-grayscale | matrix-multiply | video-frame-edges
 * Use -1 for any SET_DEBUG float to clear that specific override.
 */
class AutoRunReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MiddlewareApp
        when (intent.action) {
            ACTION_RUN_TASK   -> handleRunTask(context, app, intent)
            ACTION_SET_MODE   -> handleSetMode(app, intent)
            ACTION_SET_DEBUG  -> handleSetDebug(app, intent)
            ACTION_CLEAR_DEBUG -> handleClearDebug(app)
        }
    }

    private fun handleRunTask(context: Context, app: MiddlewareApp, intent: Intent) {
        val taskName = intent.getStringExtra("task") ?: return
        val count    = intent.getIntExtra("count", 1)
        val delayMs  = intent.getLongExtra("delay_ms", DEFAULT_DELAY_MS)

        app.appScope.launch {
            repeat(count) {
                val task = when (taskName) {
                    "echo"              -> DemoTasks.echo()
                    "sha256"            -> DemoTasks.sha256()
                    "image-grayscale"   -> DemoTasks.imageGrayscale()
                    "matrix-multiply"   -> DemoTasks.matrixMultiply()
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
        const val ACTION_RUN_TASK    = "com.thesis.middleware.RUN_TASK"
        const val ACTION_SET_MODE    = "com.thesis.middleware.SET_MODE"
        const val ACTION_SET_DEBUG   = "com.thesis.middleware.SET_DEBUG"
        const val ACTION_CLEAR_DEBUG = "com.thesis.middleware.CLEAR_DEBUG"

        private const val DEFAULT_DELAY_MS = 3000L
        private const val SENTINEL         = -1f
    }
}
