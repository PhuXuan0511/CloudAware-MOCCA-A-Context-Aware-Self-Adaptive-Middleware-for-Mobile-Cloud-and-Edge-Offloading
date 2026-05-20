package com.thesis.middleware.decision

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.context.ContextFeatures
import com.thesis.middleware.context.ContextManager
import com.thesis.middleware.context.FeatureExtractor
import com.thesis.middleware.decision.estimators.*
import com.thesis.middleware.decision.policy.OffloadingPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

/**
 * Implements the Monitor–Analyze–Plan–Execute autonomic loop.
 *
 *  - **Monitor**:  pulls the current ContextFeatures from ContextManager.
 *  - **Analyze**:  runs every estimator against the task + features.
 *  - **Plan**:     invokes the rule-based OffloadingPolicy.
 *  - **Execute**:  packages the chosen target into an OffloadingDecision.
 *
 * Two ways to use it:
 *  - [decide]: one-shot suspending call (used by ExecutionProxy on the request path).
 *  - [submit] + [start]: enqueue tasks onto a channel and consume them on a
 *    background coroutine — the "task arrival" trigger.
 *
 * Independently, [start] also polls the history store for sustained context
 * shifts and emits them on [contextDrift] — the "context change" trigger.
 */
class MapeLoop(
    private val contextManager: ContextManager,
    private val scope: CoroutineScope,
    private val policy: OffloadingPolicy = OffloadingPolicy(),
    private val driftCheckIntervalMs: Long = DEFAULT_DRIFT_INTERVAL_MS,
    private val driftWindowMs: Long = DEFAULT_DRIFT_WINDOW_MS,
    private val driftThreshold: Float = DEFAULT_DRIFT_THRESHOLD,
) {
    private val latencyEstimator = LatencyEstimator()
    private val energyEstimator = EnergyEstimator()
    private val executionTimeEstimator = ExecutionTimeEstimator()
    private val driftExtractor = FeatureExtractor()

    private val taskChannel = Channel<Submission>(Channel.UNLIMITED)
    private val _contextDrift = MutableSharedFlow<ContextDriftEvent>(extraBufferCapacity = 16)
    val contextDrift: SharedFlow<ContextDriftEvent> = _contextDrift.asSharedFlow()

    private var consumerJob: Job? = null
    private var driftJob: Job? = null

    fun start() {
        if (consumerJob?.isActive != true) {
            consumerJob = scope.launch(Dispatchers.Default) {
                for (sub in taskChannel) {
                    try {
                        sub.deferred.complete(runMape(sub.task))
                    } catch (t: Throwable) {
                        sub.deferred.completeExceptionally(t)
                    }
                }
            }
        }
        if (driftJob?.isActive != true) {
            driftJob = scope.launch(Dispatchers.Default) {
                var lastAverage: ContextFeatures? = null
                while (isActive) {
                    val current = contextManager.history()
                        .averageScoresOver(driftWindowMs, driftExtractor)
                    val prev = lastAverage
                    if (current != null && prev != null) {
                        val delta = maxScoreDelta(prev, current)
                        if (delta >= driftThreshold) {
                            _contextDrift.emit(ContextDriftEvent(prev, current, delta))
                        }
                    }
                    if (current != null) lastAverage = current
                    delay(driftCheckIntervalMs)
                }
            }
        }
    }

    fun stop() {
        consumerJob?.cancel()
        driftJob?.cancel()
        consumerJob = null
        driftJob = null
    }

    /** Synchronous-style entry point. Suspends until the MAPE pipeline finishes. */
    suspend fun decide(task: OffloadableTask): OffloadingDecision =
        withContext(Dispatchers.Default) { runMape(task) }

    /** Fire-and-forget entry point — returns a Deferred completed by the consumer. */
    fun submit(task: OffloadableTask): Deferred<OffloadingDecision> {
        val deferred = CompletableDeferred<OffloadingDecision>()
        val result = taskChannel.trySend(Submission(task, deferred))
        if (result.isFailure) {
            deferred.completeExceptionally(
                IllegalStateException("MapeLoop task channel is closed")
            )
        }
        return deferred
    }

    private fun runMape(task: OffloadableTask): OffloadingDecision {
        val features = contextManager.getLatestFeatures()      // M
        val analysis = analyze(task, features)                  // A
        val plan = plan(analysis)                               // P
        return execute(plan)                                    // E
    }

    private fun analyze(task: OffloadableTask, features: ContextFeatures): TaskAnalysis {
        return TaskAnalysis(
            task = task,
            features = features,
            localLatencyMs = latencyEstimator.estimateLocal(task, features),
            remoteLatencyMs = latencyEstimator.estimateRemote(task, features),
            localEnergyMj = energyEstimator.estimateLocal(task, features),
            remoteEnergyMj = energyEstimator.estimateRemote(task, features),
            localExecTimeMs = executionTimeEstimator.estimate(task, features, remote = false),
            remoteExecTimeMs = executionTimeEstimator.estimate(task, features, remote = true)
        )
    }

    private fun plan(analysis: TaskAnalysis): OffloadingPlan = policy.evaluate(analysis)

    private fun execute(plan: OffloadingPlan): OffloadingDecision = OffloadingDecision(
        shouldOffload = plan.target != ExecutionTarget.LOCAL,
        target = plan.target,
        reasoning = plan.reasoning
    )

    private fun maxScoreDelta(a: ContextFeatures, b: ContextFeatures): Float = maxOf(
        abs(a.networkScore - b.networkScore),
        abs(a.cpuLoadScore - b.cpuLoadScore),
        abs(a.batteryScore - b.batteryScore),
        abs(a.mobilityScore - b.mobilityScore)
    )

    private data class Submission(
        val task: OffloadableTask,
        val deferred: CompletableDeferred<OffloadingDecision>
    )

    companion object {
        private const val DEFAULT_DRIFT_INTERVAL_MS = 3_000L
        private const val DEFAULT_DRIFT_WINDOW_MS = 5_000L
        private const val DEFAULT_DRIFT_THRESHOLD = 0.2f
    }
}

data class ContextDriftEvent(
    val previous: ContextFeatures,
    val current: ContextFeatures,
    val delta: Float
)

data class TaskAnalysis(
    val task: OffloadableTask,
    val features: ContextFeatures,
    val localLatencyMs: Float,
    val remoteLatencyMs: Float,
    val localEnergyMj: Float,
    val remoteEnergyMj: Float,
    val localExecTimeMs: Float = 0f,
    val remoteExecTimeMs: Float = 0f
)

data class OffloadingPlan(
    val target: ExecutionTarget,
    val reasoning: String
)

data class OffloadingDecision(
    val shouldOffload: Boolean,
    val target: ExecutionTarget,
    val reasoning: String
)

enum class ExecutionTarget { LOCAL, EDGE, CLOUD }
