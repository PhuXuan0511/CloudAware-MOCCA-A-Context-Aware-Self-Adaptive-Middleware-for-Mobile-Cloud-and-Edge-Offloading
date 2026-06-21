package com.thesis.middleware.adaptation

import android.util.Log
import com.thesis.middleware.communication.OffloadingClient
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.MapeLoop
import com.thesis.middleware.decision.OffloadingDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * Observability:
 *  - [events] is a hot SharedFlow that emits one [ExecutionEvent] per [run]
 *    call. UI / telemetry layers subscribe to surface MAPE decisions, actual
 *    wall-clock, and fallback occurrences without coupling to the proxy.
 */
class ExecutionProxy(
    private val mapeLoop: MapeLoop,
    private val offloadingClient: OffloadingClient,
    private val remoteTimeoutMs: Long = DEFAULT_REMOTE_TIMEOUT_MS,
    /**
     * Returns the current execution mode each time a task is submitted.
     * Read on every [run] call so a mode change from the Settings screen
     * takes effect immediately without restarting the service.
     *
     * Default returns [ExecutionMode.ADAPTIVE] — full MAPE behaviour.
     */
    private val modeProvider: () -> ExecutionMode = { ExecutionMode.ADAPTIVE },
) {

    private val _events = MutableSharedFlow<ExecutionEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
    )
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    suspend fun run(task: OffloadableTask): ByteArray {
        val startMs = System.currentTimeMillis()
        val mode = modeProvider()
        // Always run MAPE Analyze (estimator + signals) so the log entry has a
        // populated context snapshot even in baseline modes. The Plan-phase
        // output (target/rule) is overridden below when mode != ADAPTIVE.
        val natural = mapeLoop.decide(task)
        val decision: OffloadingDecision = when (mode) {
            ExecutionMode.ADAPTIVE -> natural
            ExecutionMode.LOCAL_ONLY -> natural.copy(
                shouldOffload = false,
                target = ExecutionTarget.LOCAL,
                rule = "FORCED_LOCAL",
                reasoning = "execution mode = LOCAL_ONLY — MAPE bypassed for baseline comparison",
            )
            ExecutionMode.CLOUD_ONLY -> natural.copy(
                shouldOffload = true,
                target = ExecutionTarget.CLOUD,
                rule = "FORCED_CLOUD",
                reasoning = "execution mode = CLOUD_ONLY — MAPE bypassed, no fallback (baseline)",
            )
        }
        Log.d(TAG, "task=${task.id} mode=$mode target=${decision.target} rule=${decision.rule}")

        var fellBack = false
        var errorMessage: String? = null

        val result: ByteArray = when {
            // CLOUD_ONLY baseline: no fallback — exception propagates to caller
            // so the audience can see how fragile a cloud-only design is when
            // the network goes down.
            mode == ExecutionMode.CLOUD_ONLY -> {
                withTimeout(remoteTimeoutMs) { offloadingClient.submitToCloud(task) }
            }
            // LOCAL_ONLY baseline: always run on the phone.
            mode == ExecutionMode.LOCAL_ONLY -> task.execute()
            // ADAPTIVE: full behaviour with timeout + fallback to local.
            decision.target == ExecutionTarget.LOCAL -> task.execute()
            else -> {
                try {
                    withTimeout(remoteTimeoutMs) {
                        when (decision.target) {
                            ExecutionTarget.EDGE -> offloadingClient.submitToEdge(task)
                            ExecutionTarget.CLOUD -> offloadingClient.submitToCloud(task)
                            ExecutionTarget.LOCAL -> task.execute()
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "remote ${decision.target} timed out after ${remoteTimeoutMs}ms — running local")
                    fellBack = true
                    errorMessage = "timeout after ${remoteTimeoutMs}ms"
                    task.execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "remote ${decision.target} failed: ${e.message} — running local")
                    fellBack = true
                    errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    task.execute()
                }
            }
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        _events.tryEmit(
            ExecutionEvent(
                taskId = task.id,
                taskName = task.name,
                decision = decision,
                actualMs = elapsedMs,
                resultSizeBytes = result.size,
                fellBackToLocal = fellBack,
                errorMessage = errorMessage,
            )
        )
        return result
    }

    companion object {
        private const val TAG = "ExecutionProxy"
        private const val DEFAULT_REMOTE_TIMEOUT_MS = 10_000L
        private const val EVENT_BUFFER = 64
    }
}

/**
 * Telemetry record for a single [ExecutionProxy.run] invocation. Surfaced via
 * [ExecutionProxy.events] so observers can render MAPE decisions in the UI
 * or persist them for offline analysis.
 */
data class ExecutionEvent(
    val taskId: String,
    val taskName: String,
    val decision: OffloadingDecision,
    val actualMs: Long,
    val resultSizeBytes: Int,
    val fellBackToLocal: Boolean,
    val errorMessage: String?,
)
