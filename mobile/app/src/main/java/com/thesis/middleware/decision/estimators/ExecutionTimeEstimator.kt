package com.thesis.middleware.decision.estimators

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
import com.thesis.middleware.context.ContextFeatures

/**
 * Pure compute time the task takes — *excludes* network round-trip.
 * Used as the time term in the energy model (E = P × t).
 *
 *  - Local  = baseline / cpuLoadScore  (more load → longer)
 *  - Remote = server-side compute + queue wait  (servers are faster than mobile)
 *
 * For the user-perceived wall-clock latency including network, use
 * [LatencyEstimator] instead.
 */
class ExecutionTimeEstimator {

    fun estimate(task: OffloadableTask, features: ContextFeatures, remote: Boolean): Float {
        return if (remote) estimateRemote(task, features) else estimateLocal(task, features)
    }

    private fun estimateLocal(task: OffloadableTask, features: ContextFeatures): Float {
        val baseline = baselineMs(task.complexity)
        val headroom = features.cpuLoadScore.coerceAtLeast(MIN_HEADROOM)
        return baseline / headroom
    }

    private fun estimateRemote(task: OffloadableTask, features: ContextFeatures): Float {
        // Network transmission is excluded on purpose — that's LatencyEstimator's job.
        val serverExecMs = baselineMs(task.complexity) * SERVER_SPEEDUP
        return serverExecMs + SERVER_QUEUE_MS
    }

    private fun baselineMs(c: TaskComplexity): Float = when (c) {
        TaskComplexity.LIGHT -> 50f
        TaskComplexity.MEDIUM -> 300f
        TaskComplexity.HEAVY -> 2000f
    }

    companion object {
        private const val MIN_HEADROOM = 0.05f
        private const val SERVER_SPEEDUP = 0.3f
        private const val SERVER_QUEUE_MS = 30f
    }
}
