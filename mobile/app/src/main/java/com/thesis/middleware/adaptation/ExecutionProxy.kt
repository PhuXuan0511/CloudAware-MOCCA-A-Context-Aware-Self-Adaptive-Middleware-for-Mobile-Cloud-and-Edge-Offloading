package com.thesis.middleware.adaptation

import android.util.Log
import com.thesis.middleware.communication.OffloadingClient
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.MapeLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Transparent proxy that intercepts task execution and routes it to LOCAL,
 * EDGE, or CLOUD based on the MAPE loop's decision. The calling application
 * code calls one [run] method and never knows where the work actually ran.
 *
 * Resilience:
 *  - Remote calls are wrapped in [withTimeout]; a slow server can't pin the
 *    coroutine indefinitely.
 *  - Any remote failure (timeout, network error, server error) is caught and
 *    falls back to [OffloadableTask.execute] so the user still gets a result.
 *  - Parent-scope cancellation is never swallowed.
 */
class ExecutionProxy(
    private val mapeLoop: MapeLoop,
    private val offloadingClient: OffloadingClient,
    private val remoteTimeoutMs: Long = DEFAULT_REMOTE_TIMEOUT_MS,
) {

    suspend fun run(task: OffloadableTask): ByteArray {
        val decision = mapeLoop.decide(task)
        Log.d(TAG, "task=${task.id} target=${decision.target} reason=${decision.reasoning}")

        if (decision.target == ExecutionTarget.LOCAL) {
            return task.execute()
        }

        return try {
            withTimeout(remoteTimeoutMs) {
                when (decision.target) {
                    ExecutionTarget.EDGE -> offloadingClient.submitToEdge(task)
                    ExecutionTarget.CLOUD -> offloadingClient.submitToCloud(task)
                    ExecutionTarget.LOCAL -> task.execute() // unreachable; satisfies the compiler
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "remote ${decision.target} timed out after ${remoteTimeoutMs}ms — running local")
            task.execute()
        } catch (e: CancellationException) {
            // Parent scope was cancelled — propagate, don't fall back.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "remote ${decision.target} failed: ${e.message} — running local")
            task.execute()
        }
    }

    companion object {
        private const val TAG = "ExecutionProxy"
        private const val DEFAULT_REMOTE_TIMEOUT_MS = 10_000L
    }
}
