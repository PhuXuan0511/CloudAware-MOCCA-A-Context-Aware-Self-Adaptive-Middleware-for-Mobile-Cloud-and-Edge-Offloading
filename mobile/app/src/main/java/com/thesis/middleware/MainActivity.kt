package com.thesis.middleware

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.thesis.middleware.adaptation.ExecutionEvent
import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.context.ContextService
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.demo.DemoTasks
import com.thesis.middleware.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Launcher Activity. Three responsibilities:
 *
 *  1. Request runtime-dangerous permissions and start [ContextService].
 *  2. Provide five tappable "task dashboard cards" that submit sample
 *     [OffloadableTask]s through [com.thesis.middleware.adaptation.ExecutionProxy].
 *  3. Subscribe to `ExecutionProxy.events` and:
 *       - update each card's live stats (decision counts per target, avg ms,
 *         fallback count, last run) so the audience can see the distribution
 *         shift as the device context changes;
 *       - render each MAPE decision in a scrolling log with plain-English
 *         reasoning so the audience can see *why* LOCAL / EDGE / CLOUD won.
 *
 * UI-scoped coroutines live on [uiScope] (cancelled in [onDestroy]).
 * Background offload calls run on the application scope so they survive
 * orientation changes and the Activity being briefly destroyed.
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null

    private val logBuffer: ArrayDeque<String> = ArrayDeque()
    private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Per-task live aggregations. Keyed by `OffloadableTask.name` (wire name,
    // not the UI label) so it matches what ExecutionEvent carries.
    private val taskStatsMap = mutableMapOf<String, TaskStats>()
    private val taskCardStatViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (hasAllPermissions()) {
            startMiddleware()
        } else {
            statusText.text = getString(R.string.main_status_permission_needed)
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        eventJob = (application as MiddlewareApp).executionProxy.events
            .onEach { event -> uiScope.launch { appendLog(event) } }
            .launchIn(uiScope)
    }

    override fun onStop() {
        super.onStop()
        eventJob?.cancel()
        eventJob = null
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && hasAllPermissions()) {
            startMiddleware()
        }
    }

    private fun startMiddleware() {
        ContextService.start(this)
        statusText.text = getString(R.string.main_status_running)
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all { perm ->
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun runTask(taskFactory: () -> OffloadableTask) {
        val app = application as MiddlewareApp
        val task = taskFactory()
        app.appScope.launch {
            runCatching { app.executionProxy.run(task) }
                .onFailure { uiScope.launch { appendError(task, it) } }
        }
    }

    private fun appendLog(event: ExecutionEvent) {
        updateStats(event)
        val ts = timestampFmt.format(Date())
        val where = describeWhere(event.decision.target, event.fellBackToLocal)
        val took = "${event.actualMs} ms"
        val output = humanBytes(event.resultSizeBytes.toLong())

        val plainWhy = humanizeReason(event.decision.reasoning, event.decision.target)
        val detail = detailReason(event.decision.reasoning, event.decision.target)
        val line = buildString {
            append("[$ts]  ${event.taskName}\n")
            append("   Ran on:  $where\n")
            append("   Took:    $took\n")
            append("   Output:  $output\n")
            append("   Reason:  $plainWhy\n")
            append("   Details: ").append(indentContinuation(detail))
            event.errorMessage?.let { append("\n   Note:    remote failed → $it") }
        }
        pushLine(line)
    }

    /**
     * Translates the policy's structured reasoning into one short plain-English
     * sentence the audience can follow without knowing what "mJ" or "speedup"
     * means. The full technical reasoning is still shown below as "Details:".
     */
    private fun humanizeReason(reasoning: String, target: ExecutionTarget): String {
        val r = reasoning.lowercase(Locale.US)
        return when {
            r.startsWith("offline") ->
                "No network — must run on the phone."
            r.startsWith("battery critical") ->
                "Battery too low to risk a radio transmission — stay on the phone."
            r.startsWith("compute speedup") ->
                "Server is barely faster than the phone — network overhead is not worth paying."
            r.startsWith("user-patience") ->
                "Local would take too long — offload to keep the user from waiting."
            r.startsWith("energy-first") && target == ExecutionTarget.LOCAL ->
                "Saving battery: local uses less energy than sending it over the radio."
            r.startsWith("energy-first") ->
                "Saving battery: offloading actually costs less energy than running locally."
            r.startsWith("latency-first") && target == ExecutionTarget.LOCAL ->
                "Plugged in / fast charge — local is already fast enough, skip the network hop."
            r.startsWith("latency-first") ->
                "Going for speed: the server can finish faster than the phone."
            r.startsWith("balanced") && target == ExecutionTarget.LOCAL ->
                "Local wins on the combined time-and-energy score."
            r.startsWith("balanced") ->
                "Offload wins on the combined time-and-energy score."
            else -> "See details below."
        }
    }

    /**
     * Multi-line educational explanation. Decodes the raw policy reasoning so a
     * non-technical reader can follow:
     *  - which MAPE rule fired and why it exists
     *  - what the numbers in the raw output (mJ, ms, speedup, cost) mean
     *  - the threshold being compared against (e.g. 1.5× speedup floor, 3 s patience)
     *
     * Returned string uses `\n` between lines; the caller indents continuation
     * lines so they align under the "Details:" column on screen.
     */
    private fun detailReason(reasoning: String, target: ExecutionTarget): String {
        val r = reasoning.lowercase(Locale.US)
        return when {
            r.startsWith("offline") -> listOf(
                "Network monitor reports no working connection (Wi-Fi off / no signal).",
                "With no path to a server, offload is impossible — task runs on the phone CPU.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("battery critical") -> listOf(
                "Trigger: battery < 15% AND unplugged → ENERGY_FIRST mode + safety guard.",
                "Radio TX (Wi-Fi/cellular) is the highest-power state on a phone — a burst",
                "can brown out a weak battery. We trade some speed for device reliability.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("compute speedup") -> listOf(
                "Guardrail: compute-benefit floor (minimum 1.5× server speedup required).",
                "The server's CPU is only marginally faster than the phone on pure compute,",
                "so the network round-trip (upload + RTT + download) would erase the gain.",
                "Below the floor → keep local.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("user-patience") -> listOf(
                "Guardrail: user-patience override (local exec > 3000 ms threshold).",
                "Even when ENERGY_FIRST would prefer local, blocking the user for 3+ seconds",
                "hurts UX more than the battery cost. Forced offload (battery is not critical).",
                "Raw policy output: $reasoning",
            )

            r.startsWith("energy-first") && target == ExecutionTarget.LOCAL -> listOf(
                "Mode: ENERGY_FIRST (battery low, not charging).",
                "Estimator compared local CPU energy vs radio TX energy (in millijoules, mJ).",
                "Local won — task is light enough that radio transmission would cost more.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("energy-first") -> listOf(
                "Mode: ENERGY_FIRST (battery low, not charging).",
                "Estimator compared local CPU energy vs radio TX energy (in millijoules, mJ).",
                "Task is heavy enough that running it locally would burn more battery than",
                "transmitting it — so offload actually saves energy overall.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("latency-first") && target == ExecutionTarget.LOCAL -> listOf(
                "Mode: LATENCY_FIRST (phone charging — energy is effectively free).",
                "With energy off the table we optimize purely for wall-clock time.",
                "Local CPU finishes faster than network round-trip + server compute → stay local.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("latency-first") -> listOf(
                "Mode: LATENCY_FIRST (phone charging — energy is effectively free).",
                "With energy off the table we optimize purely for wall-clock time.",
                "Server finishes faster than the phone (incl. network round-trip) → offload.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("balanced") && target == ExecutionTarget.LOCAL -> listOf(
                "Mode: BALANCED (default — battery healthy, not charging).",
                "Formula: cost = 0.6 × latency_ms + 0.4 × energy_mJ (lower wins).",
                "Local's weighted score is lower → keep work on the phone.",
                "Raw policy output: $reasoning",
            )

            r.startsWith("balanced") -> listOf(
                "Mode: BALANCED (default — battery healthy, not charging).",
                "Formula: cost = 0.6 × latency_ms + 0.4 × energy_mJ (lower wins).",
                "Remote's weighted score is lower → send work to the server.",
                "Raw policy output: $reasoning",
            )

            else -> listOf(reasoning)
        }.joinToString("\n")
    }

    /** Prefix every line *after the first* with 12 spaces so it aligns under "Details: ". */
    private fun indentContinuation(text: String): String =
        text.lineSequence().joinToString("\n            ")

    private fun appendError(task: OffloadableTask, t: Throwable) {
        val ts = timestampFmt.format(Date())
        pushLine(
            "[$ts]  ${task.name}\n" +
                "   Failed:  ${t.javaClass.simpleName}\n" +
                "   Details: ${t.message ?: "(no message)"}"
        )
    }

    /**
     * Human-readable label for the chosen execution target. The audience does
     * not know the [ExecutionTarget] enum, so we expand it into plain English
     * and tag fallbacks clearly so a "Phone" outcome is not mistaken for a
     * deliberate LOCAL decision.
     */
    private fun describeWhere(target: ExecutionTarget, fellBack: Boolean): String {
        if (fellBack) return "Phone (fallback — remote unreachable)"
        return when (target) {
            ExecutionTarget.LOCAL -> "Phone (no network call)"
            ExecutionTarget.EDGE  -> "Edge server (laptop on same Wi-Fi, low latency)"
            ExecutionTarget.CLOUD -> "Cloud server (more compute, higher latency)"
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1_024L      -> "$bytes B"
        bytes < 1_048_576L  -> "${bytes / 1_024L} KB"
        else                -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    }

    /**
     * Updates the per-task aggregations and re-renders the matching card's
     * stats TextView. Called on the UI thread from the events subscription.
     */
    private fun updateStats(event: ExecutionEvent) {
        val target = if (event.fellBackToLocal) null else event.decision.target
        val stats = taskStatsMap.getOrPut(event.taskName) { TaskStats() }
        stats.record(event.actualMs, target, event.fellBackToLocal)
        taskCardStatViews[event.taskName]?.text = stats.format()
    }

    private fun resetStats() {
        taskStatsMap.clear()
        val empty = getString(R.string.main_card_empty)
        taskCardStatViews.values.forEach { it.text = empty }
    }

    /**
     * In-memory aggregation for one task type. Tracks decision distribution
     * (LOCAL / EDGE / CLOUD), per-target average latency, fallback count, and
     * the most recent run. Rendered as a compact monospace block in the card.
     */
    private class TaskStats {
        private var totalRuns = 0
        private var fallbacks = 0
        private val runsByTarget = mutableMapOf<ExecutionTarget, Int>()
        private val totalMsByTarget = mutableMapOf<ExecutionTarget, Long>()
        private var lastLabel: String? = null
        private var lastMs: Long = 0

        fun record(elapsedMs: Long, target: ExecutionTarget?, fellBack: Boolean) {
            totalRuns++
            lastMs = elapsedMs
            if (fellBack) {
                fallbacks++
                lastLabel = "fallback"
            } else if (target != null) {
                runsByTarget[target] = (runsByTarget[target] ?: 0) + 1
                totalMsByTarget[target] = (totalMsByTarget[target] ?: 0L) + elapsedMs
                lastLabel = shortLabel(target)
            }
        }

        fun format(): String {
            if (totalRuns == 0) return "No runs yet\nTap to start"
            return buildString {
                append("Runs: $totalRuns")
                if (fallbacks > 0) append("  (fb:$fallbacks)")
                append('\n')
                ORDERED_TARGETS.forEach { t ->
                    val n = runsByTarget[t] ?: 0
                    val avg = if (n > 0) "${(totalMsByTarget[t] ?: 0L) / n}ms" else "—"
                    append("${shortLabel(t)}:$n  $avg\n")
                }
                lastLabel?.let { append("Last: $it ${lastMs}ms") }
            }
        }

        private fun shortLabel(t: ExecutionTarget) = when (t) {
            ExecutionTarget.LOCAL -> "L"
            ExecutionTarget.EDGE  -> "E"
            ExecutionTarget.CLOUD -> "C"
        }

        companion object {
            private val ORDERED_TARGETS = listOf(
                ExecutionTarget.LOCAL, ExecutionTarget.EDGE, ExecutionTarget.CLOUD,
            )
        }
    }

    private fun pushLine(line: String) {
        logBuffer.addFirst(line)
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeLast()
        logText.text = logBuffer.joinToString("\n\n")
    }

    private fun buildLayout(): View {
        val pad = 32
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad, pad, pad / 2)
        }
        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
            movementMethod = ScrollingMovementMethod()
            text = getString(R.string.main_log_placeholder)
        }

        // ── Task dashboard row ──────────────────────────────────────────────
        // Each card is the tap-target for its task AND shows live stats —
        // audience sees the decision distribution change in real time without
        // having to mentally parse log lines.
        val cardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, dp(8f))
            addView(buildTaskCard(R.string.main_task_echo,   "echo")              { DemoTasks.echo() })
            addView(buildTaskCard(R.string.main_task_sha,    "sha256")            { DemoTasks.sha256() })
            addView(buildTaskCard(R.string.main_task_image,  "image-grayscale")   { DemoTasks.imageGrayscale() })
            addView(buildTaskCard(R.string.main_task_matrix, "matrix-multiply")   { DemoTasks.matrixMultiply() })
            addView(buildTaskCard(R.string.main_task_video,  "video-frame-edges") { DemoTasks.videoFrameEdges(applicationContext) })
        }
        val cardScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cardRow)
        }

        val settingsButton = Button(this).apply {
            setText(R.string.main_open_settings)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        val resetButton = Button(this).apply {
            setText(R.string.main_reset_stats)
            setOnClickListener { resetStats() }
        }
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            addView(settingsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(resetButton,    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f, // weight — fills remaining space
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(statusText)
            addView(cardScroll)
            addView(controlRow)
            addView(logScroll)
        }
    }

    /**
     * One dashboard card: bold label on top, monospace stats block below.
     * Tapping anywhere on the card submits [taskFactory] through the proxy.
     * The stats TextView is registered in [taskCardStatViews] keyed by
     * [wireName] so [updateStats] can find it when an event fires.
     */
    private fun buildTaskCard(
        labelRes: Int,
        wireName: String,
        taskFactory: () -> OffloadableTask,
    ): View {
        val cardPad = dp(12f)
        val cardWidth = dp(170f)

        val labelView = TextView(this).apply {
            setText(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        val statsView = TextView(this).apply {
            text = getString(R.string.main_card_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6f), 0, 0)
        }
        taskCardStatViews[wireName] = statsView

        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8f).toFloat()
            setColor(Color.parseColor("#F5F5F5"))
            setStroke(dp(1f), Color.parseColor("#CCCCCC"))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(cardPad, cardPad, cardPad, cardPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { runTask(taskFactory) }
            addView(labelView)
            addView(statsView)
            layoutParams = LinearLayout.LayoutParams(
                cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8f) }
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val MAX_LOG_LINES = 10

        private val REQUIRED_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }.toTypedArray()
    }
}
