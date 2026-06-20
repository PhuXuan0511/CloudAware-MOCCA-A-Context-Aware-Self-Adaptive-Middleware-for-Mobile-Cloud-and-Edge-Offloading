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
import com.thesis.middleware.decision.SignalSnapshot
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
    private lateinit var logContainer: LinearLayout
    private lateinit var logPlaceholder: TextView

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventJob: Job? = null

    // Track entry views so we can prune the oldest when the cap is reached.
    private val logEntryViews: ArrayDeque<View> = ArrayDeque()
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
        val s = event.decision.signals
        val where = describeWhere(event.decision.target, event.fellBackToLocal)
        val output = humanBytes(event.resultSizeBytes.toLong())

        // ── Compact summary (always visible — 4-5 lines) ──
        val summary = buildString {
            append("→ $where\n")
            append("  ${event.actualMs} ms   ·   $output\n")
            append("Rule: ${ruleDisplayName(event.decision.rule)}\n")
            append("Why:  ${ruleExplanation(event.decision.rule, event.decision.target, s)}")
            event.errorMessage?.let { append("\nNote: remote failed → $it") }
        }

        // ── Details (collapsible — context + estimates + cost + chain) ──
        val details = buildDetailsBlock(event, s)

        pushEntryView(buildEntryView(ts, event.taskName, summary, details))
    }

    /**
     * Builds the full details block (context + estimates + cost analysis +
     * rule chain trace) as a monospace string. Shown only when the user
     * taps "Show details" on the entry card.
     */
    private fun buildDetailsBlock(event: ExecutionEvent, s: SignalSnapshot): String =
        buildString {
            // Context
            append("Context:\n")
            append("  Battery:  ${s.batteryPercent}% ")
            append(if (s.isCharging) "(charging)" else "(not charging)")
            append("   ${batteryThresholdNote(s)}\n")
            append("  Network:  ${s.networkType}  RTT ${s.rttMs.toInt()}ms  ")
            append("~${s.bandwidthMbps.toInt()} Mbps  signal ${s.signalDbm} dBm\n")
            append("            score ${"%.2f".format(s.networkScore)}   ")
            append("${networkThresholdNote(s)}\n")
            append("  CPU:      ${s.cpuUsagePercent.toInt()}% used  (${s.cpuCores} cores)\n")
            append("  Mobility: ${if (s.isStable) "Stationary" else "Moving"}  ")
            append("(${"%.2f".format(s.linearAccelMps2)} m/s²)\n")
            append("  Task:     ${s.taskComplexity}   ${complexityThresholdNote(s)}\n\n")

            // Estimates
            append("Estimates:\n")
            append("  Local:   ${s.estLocalLatencyMs.toInt()} ms  /  ")
            append("${"%.1f".format(s.estLocalEnergyMj)} mJ\n")
            append("  Remote:  ${s.estRemoteLatencyMs.toInt()} ms  /  ")
            append("${"%.1f".format(s.estRemoteEnergyMj)} mJ\n")
            append("  Speedup: ${"%.2fx".format(s.computeSpeedup)}   ")
            append("${speedupThresholdNote(s)}\n\n")

            // Cost analysis
            val wLat = 0.5f
            val wEng = 0.5f
            val margin = 0.05f
            val localCost  = wLat * s.estLocalLatencyMs  + wEng * s.estLocalEnergyMj
            val remoteCost = wLat * s.estRemoteLatencyMs + wEng * s.estRemoteEnergyMj
            val localWinsByCost = localCost <= remoteCost * (1f + margin)
            val costWinner = if (localWinsByCost) "LOCAL" else "REMOTE"
            val cheaperSide = if (localCost < remoteCost) "Local" else "Remote"
            val cheaperPct = kotlin.math.abs(localCost - remoteCost) /
                kotlin.math.max(localCost, remoteCost) * 100f

            append("Cost analysis (w_lat=0.5, w_eng=0.5, margin=5%):\n")
            append("  LocalCost  = 0.5 × ${s.estLocalLatencyMs.toInt()} + ")
            append("0.5 × ${"%.1f".format(s.estLocalEnergyMj)} = ")
            append("${"%.1f".format(localCost)}\n")
            append("  RemoteCost = 0.5 × ${s.estRemoteLatencyMs.toInt()} + ")
            append("0.5 × ${"%.1f".format(s.estRemoteEnergyMj)} = ")
            append("${"%.1f".format(remoteCost)}\n")
            append("  Δ: $cheaperSide is ${"%.1f".format(cheaperPct)}% cheaper ")
            append("→ cost-only winner: $costWinner\n")

            val targetIsLocal = event.decision.target == ExecutionTarget.LOCAL
            val agrees = (localWinsByCost && targetIsLocal) ||
                (!localWinsByCost && !targetIsLocal)
            val agreementNote = when {
                event.decision.rule == "BALANCED_COST" ->
                    "[this rule IS the cost analysis]"
                agrees ->
                    "[cost-only winner matches the chosen target]"
                else ->
                    "[OVERRIDDEN by rule '${ruleDisplayName(event.decision.rule)}']"
            }
            append("  $agreementNote\n\n")

            // Rule chain (renderRuleChain returns text ending with \n)
            append(renderRuleChain(s, event.decision.rule).trimStart())
        }

    /**
     * Builds one expandable entry card. Card shows:
     *  - Header (bold timestamp + task name)
     *  - Compact summary (4-5 lines, always visible)
     *  - Toggle TextView ("▶ Show details" / "▼ Hide details")
     *  - Details block (monospace, initially GONE)
     */
    private fun buildEntryView(
        timestamp: String,
        taskName: String,
        summary: String,
        details: String,
    ): View {
        val pad = dp(10f)

        val header = TextView(this).apply {
            text = "[$timestamp]  $taskName"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        }

        val summaryView = TextView(this).apply {
            text = summary
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4f), 0, 0)
        }

        val detailsView = TextView(this).apply {
            text = details
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6f), 0, 0)
            visibility = View.GONE
        }

        val toggle = TextView(this).apply {
            text = "▶  Show details"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#1F4E79"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6f), 0, 0)
            isClickable = true
            isFocusable = true
        }
        toggle.setOnClickListener {
            if (detailsView.visibility == View.GONE) {
                detailsView.visibility = View.VISIBLE
                toggle.text = "▼  Hide details"
            } else {
                detailsView.visibility = View.GONE
                toggle.text = "▶  Show details"
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.parseColor("#F8F8F8"))
                setStroke(dp(1f), Color.parseColor("#DDDDDD"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8f) }
            addView(header)
            addView(summaryView)
            // Only attach toggle + details when there IS something to expand
            // (skipped for error entries which carry summary only).
            if (details.isNotEmpty()) {
                addView(toggle)
                addView(detailsView)
            }
        }
    }

    /**
     * Maps a [com.thesis.middleware.decision.policy.PolicyRule.id] to a short
     * human-readable label rendered as the "Rule matched:" line.
     */
    private fun ruleDisplayName(ruleId: String): String = when (ruleId) {
        "OFFLINE"                      -> "Offline → local execution"
        "BATTERY_CRITICAL_SAFETY"      -> "Battery critical → keep local (safety)"
        "UNSTABLE_NETWORK"             -> "Unstable network → local execution"
        "COMPUTE_FLOOR_NOT_MET"        -> "Network overhead not worth it → keep local"
        "LATENCY_SENSITIVE"            -> "Latency-sensitive task → edge"
        "LOW_BATTERY_OFFLOAD"          -> "Low battery → offload to save CPU energy"
        "HEAVY_COMPUTE_GOOD_BANDWIDTH" -> "Heavy compute + good bandwidth → edge/cloud"
        "BALANCED_COST"                -> "Balanced cost (default)"
        else                           -> ruleId
    }

    /**
     * Plain-English explanation of why this rule fired. References the actual
     * signal values from [s] so the audience sees the threshold being compared.
     */
    private fun ruleExplanation(
        ruleId: String,
        target: ExecutionTarget,
        s: SignalSnapshot,
    ): String = when (ruleId) {
        "OFFLINE" ->
            "No network — task must run on the phone."

        "BATTERY_CRITICAL_SAFETY" ->
            "Battery is ${s.batteryPercent}% and not charging — radio TX would " +
                "be the highest-power state and could brown out the device. " +
                "Keep work local for safety."

        "UNSTABLE_NETWORK" ->
            "Network aggregate score is ${"%.2f".format(s.networkScore)} — too " +
                "weak to commit to a round-trip. Run locally to avoid timeouts."

        "COMPUTE_FLOOR_NOT_MET" ->
            "Server is only ${"%.2fx".format(s.computeSpeedup)} faster than the " +
                "phone — network upload + RTT + download would erase the gain. " +
                "Keep local."

        "LATENCY_SENSITIVE" ->
            "Task is LIGHT (interactive). Edge has the lowest RTT, so the " +
                "round-trip finishes before local compute would — picking edge."

        "LOW_BATTERY_OFFLOAD" -> {
            val saved = (s.estLocalEnergyMj - s.estRemoteEnergyMj).coerceAtLeast(0f)
            "Battery ${s.batteryPercent}% (not charging). Local CPU would burn " +
                "${"%.1f".format(s.estLocalEnergyMj)} mJ vs " +
                "${"%.1f".format(s.estRemoteEnergyMj)} mJ for the network round-trip " +
                "→ offloading saves ~${"%.1f".format(saved)} mJ."
        }

        "HEAVY_COMPUTE_GOOD_BANDWIDTH" ->
            "Task is HEAVY and network score ${"%.2f".format(s.networkScore)} " +
                "means bandwidth is plenty. Server finishes " +
                "${"%.2fx".format(s.computeSpeedup)} faster — offload to $target."

        "BALANCED_COST" -> {
            val local = (0.5f * s.estLocalLatencyMs + 0.5f * s.estLocalEnergyMj)
            val remote = (0.5f * s.estRemoteLatencyMs + 0.5f * s.estRemoteEnergyMj)
            "No named rule fired. Weighted cost = 0.5×latency + 0.5×energy " +
                "(equal priority). Local ${"%.1f".format(local)} vs remote " +
                "${"%.1f".format(remote)} → " +
                if (target == ExecutionTarget.LOCAL) "local wins." else "$target wins."
        }

        else -> "Unknown rule id: $ruleId"
    }

    // ── Threshold-note helpers (inline annotations on the Context block) ──
    //
    // Each helper takes the current SignalSnapshot and returns a bracketed
    // hint like "[15%/30% thresholds: healthy]" so the audience sees the
    // raw signal AND its position relative to the policy thresholds —
    // without needing to mentally remember the rule constants.

    private fun batteryThresholdNote(s: SignalSnapshot): String {
        return when {
            s.isCharging -> "[charging — Rule 6 skipped]"
            s.batteryPercent < 15 -> "[${s.batteryPercent}% < 15% critical → Rule 2 may fire]"
            s.batteryPercent < 30 -> "[${s.batteryPercent}% < 30% low → Rule 6 may fire]"
            else -> "[${s.batteryPercent}% ≥ 30% healthy: above critical 15% & low 30%]"
        }
    }

    private fun networkThresholdNote(s: SignalSnapshot): String {
        return when {
            s.networkType == "NONE" -> "[OFFLINE → Rule 1 fires]"
            s.networkScore < 0.30f ->
                "[< 0.30 unstable → Rule 3 may fire]"
            s.networkScore < 0.60f ->
                "[≥ 0.30 stable, < 0.60 not good-bandwidth]"
            else ->
                "[≥ 0.60 good bandwidth → Rule 7 may fire for HEAVY tasks]"
        }
    }

    private fun complexityThresholdNote(s: SignalSnapshot): String {
        return when (s.taskComplexity) {
            "LIGHT" -> "[LIGHT → may trigger Rule 5 (latency-sensitive → edge)]"
            "MEDIUM" -> "[MEDIUM → no complexity-specific rule, falls to Rule 8]"
            "HEAVY" -> "[HEAVY → may trigger Rule 7 (heavy compute → edge/cloud)]"
            else -> "[unknown complexity]"
        }
    }

    private fun speedupThresholdNote(s: SignalSnapshot): String {
        return if (s.computeSpeedup < 1.50f) {
            "[< 1.50× → Rule 4 (negligible speedup) may fire]"
        } else {
            "[≥ 1.50× compute floor met → offloading is worthwhile]"
        }
    }

    /**
     * Renders the full rule chain trace. For each of the 8 rules in priority
     * order, shows:
     *   - skip   (rule didn't fire because condition was false)
     *   - FIRED  (this rule won — explanation inline)
     *   - —      (not evaluated because an earlier rule already fired)
     *
     * The audience can read top-to-bottom and follow exactly which rules
     * were checked, which conditions held, and which rule won.
     */
    private fun renderRuleChain(s: SignalSnapshot, firedRule: String): String {
        data class RuleCheck(val id: String, val displayName: String, val explanation: String)

        // Derived booleans matching the actual policy logic.
        // (criticalBattery / lowBattery thresholds are checked inline in the
        // RuleCheck explanations below rather than via these locals.)
        val online = s.networkType != "NONE"
        val unstable = s.networkScore < 0.30f
        val belowFloor = s.computeSpeedup < 1.50f
        val light = s.taskComplexity == "LIGHT"
        val heavy = s.taskComplexity == "HEAVY"
        val remoteCheaperEnergy = s.estRemoteEnergyMj < s.estLocalEnergyMj
        val goodBw = s.networkScore >= 0.60f

        val rules = listOf(
            RuleCheck("OFFLINE", "OFFLINE",
                if (online) "network online (${s.networkType})"
                else "no network → OFFLINE"),
            RuleCheck("BATTERY_CRITICAL_SAFETY", "BATTERY_CRITICAL_SAFETY",
                when {
                    s.isCharging -> "charging — guardrail skipped"
                    s.batteryPercent >= 15 -> "${s.batteryPercent}% ≥ 15% threshold"
                    else -> "${s.batteryPercent}% < 15% AND not charging"
                }),
            RuleCheck("UNSTABLE_NETWORK", "UNSTABLE_NETWORK",
                if (unstable) "score ${"%.2f".format(s.networkScore)} < 0.30"
                else "score ${"%.2f".format(s.networkScore)} ≥ 0.30"),
            RuleCheck("NEGLIGIBLE_SPEEDUP", "NEGLIGIBLE_SPEEDUP / COMPUTE_FLOOR_NOT_MET",
                if (belowFloor) "speedup ${"%.2f".format(s.computeSpeedup)}× < 1.50×"
                else "speedup ${"%.2f".format(s.computeSpeedup)}× ≥ 1.50×"),
            RuleCheck("LATENCY_SENSITIVE", "LATENCY_SENSITIVE",
                if (light) "task is LIGHT (interactive)"
                else "task is ${s.taskComplexity} ≠ LIGHT"),
            RuleCheck("LOW_BATTERY_OFFLOAD", "LOW_BATTERY_OFFLOAD",
                when {
                    s.isCharging -> "charging"
                    s.batteryPercent >= 30 -> "${s.batteryPercent}% ≥ 30% threshold"
                    !remoteCheaperEnergy -> "remote energy ≥ local energy"
                    else -> "${s.batteryPercent}% < 30% AND remote cheaper"
                }),
            RuleCheck("HEAVY_COMPUTE_GOOD_BANDWIDTH", "HEAVY_COMPUTE_GOOD_BANDWIDTH",
                when {
                    !heavy -> "task ${s.taskComplexity} ≠ HEAVY"
                    !goodBw -> "score ${"%.2f".format(s.networkScore)} < 0.60"
                    else -> "HEAVY + score ${"%.2f".format(s.networkScore)} ≥ 0.60"
                }),
            RuleCheck("BALANCED_COST", "BALANCED_COST",
                "default fallback (always applicable)"),
        )

        val firedIdx = rules.indexOfFirst { it.id == firedRule }.let {
            if (it == -1) rules.size - 1 else it
        }
        val sb = StringBuilder("   Rule chain trace:\n")
        for ((i, check) in rules.withIndex()) {
            val status: String = when {
                i < firedIdx -> "skip "
                i == firedIdx -> "FIRED"
                else          -> "—    "
            }
            val nameCol = check.displayName.padEnd(32)
            sb.append("     ${i + 1} ").append(nameCol).append("  ")
            sb.append(status).append("  (")
            sb.append(if (i > firedIdx) "not evaluated — rule ${firedIdx + 1} won"
                      else check.explanation)
            sb.append(")\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun appendError(task: OffloadableTask, t: Throwable) {
        val ts = timestampFmt.format(Date())
        val summary = "✗  ${t.javaClass.simpleName}\n${t.message ?: "(no message)"}"
        // No details to expand for errors — pass empty string.
        pushEntryView(buildEntryView(ts, task.name, summary, details = ""))
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

    /**
     * Inserts an entry view at the top of [logContainer] and prunes the
     * oldest entries when the cap is reached. Removes the placeholder TextView
     * on first entry.
     */
    private fun pushEntryView(entryView: View) {
        if (logContainer.indexOfChild(logPlaceholder) >= 0) {
            logContainer.removeView(logPlaceholder)
        }
        logContainer.addView(entryView, 0)
        logEntryViews.addFirst(entryView)
        while (logEntryViews.size > MAX_LOG_LINES) {
            val oldest = logEntryViews.removeLast()
            logContainer.removeView(oldest)
        }
    }

    private fun buildLayout(): View {
        val pad = 32
        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad, pad, pad / 2)
        }
        // Placeholder shown only when no entries logged yet.
        logPlaceholder = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad)
            text = getString(R.string.main_log_placeholder)
        }
        // Vertical container that holds one collapsible card per entry.
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(logPlaceholder)
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
            addView(logContainer)
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
