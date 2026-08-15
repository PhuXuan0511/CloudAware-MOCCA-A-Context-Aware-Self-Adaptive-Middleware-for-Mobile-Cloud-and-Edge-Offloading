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
            ExecutionMode.ADAPTIVE    -> natural
            ExecutionMode.ADAPTIVE_ML -> mapeLoop.runMapeWithMl(task)
            ExecutionMode.LOCAL_ONLY  -> natural.copy(
                shouldOffload = false,
                target = ExecutionTarget.LOCAL,
                rule = "FORCED_LOCAL",
                reasoning = "execution mode = LOCAL_ONLY — MAPE bypassed for baseline comparison",
            )
            ExecutionMode.CLOUD_ONLY  -> natural.copy(
                shouldOffload = true,
                target = ExecutionTarget.CLOUD,
                rule = "FORCED_CLOUD",
                reasoning = "execution mode = CLOUD_ONLY — MAPE bypassed, no fallback (baseline)",
            )
        }
        Log.d(TAG, "task=${task.id} mode=$mode target=${decision.target} rule=${decision.rule}")

        var fellBack = false
        var errorMessage: String? = null
        // Where the work provably ran, per the server's own response, and how
        // long its handler took. Both stay null/`LOCAL_TAG` for local execution.
        var executedAt: String = LOCAL_TAG
        var serverExecMs: Float? = null

        fun accept(remote: com.thesis.middleware.communication.RemoteResult): ByteArray {
            executedAt = remote.executedAt
            serverExecMs = remote.serverExecMs
            return remote.payload
        }

        fun emit(resultSize: Int) {
            if (!fellBack && decision.target != ExecutionTarget.LOCAL &&
                errorMessage == null &&
                !executedAt.equals(decision.target.name, ignoreCase = true)
            ) {
                // Edge forwards to cloud under overload, so the tier that ran the
                // task can differ from the one the policy picked. Surfaced here and
                // in the CSV so the evaluation does not silently attribute a
                // cloud-executed run to the edge.
                Log.i(TAG, "target=${decision.target} but server reported executed_at=$executedAt")
            }
            _events.tryEmit(
                ExecutionEvent(
                    taskId = task.id,
                    taskName = task.name,
                    decision = decision,
                    actualMs = System.currentTimeMillis() - startMs,
                    resultSizeBytes = resultSize,
                    fellBackToLocal = fellBack,
                    errorMessage = errorMessage,
                    executedAt = executedAt,
                    serverExecMs = serverExecMs,
                )
            )
        }

        val result: ByteArray = when {
            // CLOUD_ONLY baseline: no fallback — the exception still propagates to
            // the caller so the audience sees how fragile a cloud-only design is,
            // but the failure is recorded first. Without that, a failed run leaves
            // no CSV row at all and the baseline looks *more* reliable than it is:
            // only its successes survive into the dataset.
            mode == ExecutionMode.CLOUD_ONLY -> {
                try {
                    accept(withTimeout(remoteTimeoutMs) { offloadingClient.submitToCloud(task) })
                } catch (e: TimeoutCancellationException) {
                    // TimeoutCancellationException extends CancellationException,
                    // so it must be caught before the pass-through below.
                    Log.w(TAG, "cloud-only timed out after ${remoteTimeoutMs}ms — no fallback")
                    errorMessage = "timeout after ${remoteTimeoutMs}ms"
                    emit(resultSize = 0)
                    throw e
                } catch (e: CancellationException) {
                    throw e   // parent scope cancelled — not a measurable outcome
                } catch (e: Exception) {
                    Log.w(TAG, "cloud-only failed: ${e.message} — no fallback")
                    errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    emit(resultSize = 0)
                    throw e
                }
            }
            // LOCAL_ONLY baseline: always run on the phone.
            mode == ExecutionMode.LOCAL_ONLY -> task.execute()
            // ADAPTIVE: full behaviour with timeout + fallback to local.
            decision.target == ExecutionTarget.LOCAL -> task.execute()
            else -> {
                try {
                    accept(
                        withTimeout(remoteTimeoutMs) {
                            when (decision.target) {
                                ExecutionTarget.EDGE -> offloadingClient.submitToEdge(task)
                                ExecutionTarget.CLOUD -> offloadingClient.submitToCloud(task)
                                // Unreachable: the branch above already caught LOCAL.
                                ExecutionTarget.LOCAL -> error("LOCAL handled above")
                            }
                        }
                    )
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "remote ${decision.target} timed out after ${remoteTimeoutMs}ms — running local")
                    fellBack = true
                    errorMessage = "timeout after ${remoteTimeoutMs}ms"
                    executedAt = LOCAL_TAG
                    serverExecMs = null
                    task.execute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "remote ${decision.target} failed: ${e.message} — running local")
                    fellBack = true
                    errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                    executedAt = LOCAL_TAG
                    serverExecMs = null
                    task.execute()
                }
            }
        }

        emit(resultSize = result.size)
        return result
    }

    companion object {
        private const val TAG = "ExecutionProxy"
        private const val DEFAULT_REMOTE_TIMEOUT_MS = 10_000L
        private const val EVENT_BUFFER = 64

        /** `executed_at` value for work that ran on the phone. */
        const val LOCAL_TAG = "local"
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
    /**
     * Tier that actually ran the task, as reported by the server
     * (`"edge"` / `"cloud"`), or [ExecutionProxy.LOCAL_TAG] for phone-side
     * execution including fallbacks. Compare against
     * `decision.target` to detect edge→cloud forwarding under overload.
     */
    val executedAt: String = ExecutionProxy.LOCAL_TAG,
    /**
     * Server-measured handler time in ms; null when the task ran locally.
     * `actualMs - serverExecMs` isolates the network overhead.
     */
    val serverExecMs: Float? = null,
)
