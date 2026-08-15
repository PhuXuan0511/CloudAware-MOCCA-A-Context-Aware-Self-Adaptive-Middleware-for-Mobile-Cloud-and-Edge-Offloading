package com.thesis.middleware.metrics

import com.thesis.middleware.adaptation.ExecutionEvent

/**
 * Pure CSV serialisation for [ExecutionEvent] — the schema contract between the
 * Android runtime and the offline evaluation notebook.
 *
 * Deliberately free of Android dependencies so the schema can be unit-tested on
 * the JVM ([com.thesis.middleware.metrics.MetricsCsvFormatTest]). [MetricsRecorder]
 * owns the file handling; this object owns the column layout.
 *
 * ## Schema versioning
 *
 * [HEADER] is the single source of truth for the column layout. When it changes,
 * [MetricsRecorder] archives any existing CSV rather than appending rows with a
 * different arity — mixed-schema files previously had to be filtered out by hand
 * in `collect_data.ps1`.
 *
 * New columns are appended at the *end of the estimator block* (after
 * `est_remote_ms`) rather than at the end of the row, because `reasoning` is
 * free text that may contain commas and is quoted. Positional readers in
 * `collect_data.ps1` only index columns 2, 4 and 8, all of which sit before the
 * estimator block and are therefore unaffected.
 */
object MetricsCsvFormat {

    /**
     * Column layout, v2 — adds the two energy estimates plus the server's own
     * account of the run (`executed_at`, `server_exec_ms`).
     */
    const val HEADER: String =
        "timestamp_iso,task_id,task_name,target,fell_back,actual_ms,result_bytes,error," +
            "rule,battery_percent,is_charging,network_type,network_score," +
            "rtt_ms,bandwidth_mbps,cpu_percent,is_stable," +
            "est_local_ms,est_remote_ms,est_local_energy_mj,est_remote_energy_mj," +
            "speedup,executed_at,server_exec_ms,debug_overrides,reasoning"

    /** Number of columns a well-formed row must have. */
    val COLUMN_COUNT: Int = HEADER.split(",").size

    /**
     * Renders one event as a CSV line (no trailing newline).
     *
     * [timestampIso] is passed in rather than read from the clock so the output
     * is deterministic and the caller controls the format.
     */
    fun row(event: ExecutionEvent, timestampIso: String): String {
        val s = event.decision.signals
        val cols = listOf(
            timestampIso,
            event.taskId,
            event.taskName,
            event.decision.target.name,
            event.fellBackToLocal.toString(),
            event.actualMs.toString(),
            event.resultSizeBytes.toString(),
            event.errorMessage ?: "",
            // Rule + context signals — the columns the supervisor asked for so we
            // can build evaluation charts (rule distribution, decision accuracy
            // under varying battery / network conditions, etc.).
            event.decision.rule,
            s.batteryPercent.toString(),
            s.isCharging.toString(),
            s.networkType,
            fmt3(s.networkScore),
            fmt1(s.rttMs),
            fmt1(s.bandwidthMbps),
            fmt1(s.cpuUsagePercent),
            s.isStable.toString(),
            fmt1(s.estLocalLatencyMs),
            fmt1(s.estRemoteLatencyMs),
            // Energy is half of the BALANCED_COST formula and gates
            // LOW_BATTERY_OFFLOAD, but was previously computed and discarded —
            // making the energy half of the cost model impossible to evaluate
            // offline. Logged so the notebook can validate it.
            fmt1(s.estLocalEnergyMj),
            fmt1(s.estRemoteEnergyMj),
            fmt3(s.computeSpeedup),
            // Ground truth from the server, not the phone's intent: edge-server
            // forwards to cloud under overload, so `target` alone cannot be
            // trusted when attributing measured latency to a tier.
            event.executedAt,
            event.serverExecMs?.let { fmt1(it) } ?: "",
            // Non-empty when a debug override replaced a real estimator output
            // (collect_data.ps1 Session B does this). Those rows must be excluded
            // from cost-model validation.
            event.decision.debugOverrides,
            event.decision.reasoning,
        )
        return cols.joinToString(",") { csvEscape(it) }
    }

    /**
     * Quotes a field if it contains a comma, quote, or newline, doubling any
     * embedded quotes — RFC 4180 minimal escaping, which `pandas.read_csv`
     * parses without extra options.
     */
    fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    // Locale-independent formatting: String.format() would emit "0,750" under a
    // comma-decimal locale (de-DE, fr-FR, vi-VN …) and silently corrupt the CSV
    // by injecting extra field separators into unquoted numeric columns.
    private fun fmt1(v: Float): String = fixed(v, 1)
    private fun fmt3(v: Float): String = fixed(v, 3)

    private fun fixed(v: Float, decimals: Int): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        // LatencyEstimator uses Float.MAX_VALUE/4 as its "offline" RTT sentinel,
        // so est_remote_ms can reach ~8.5e37. Scaling that into a Long saturates
        // at Long.MAX_VALUE and would emit a plausible-looking 9.2e17 instead.
        // Fall back to Float's own notation, which pandas parses as-is.
        if (kotlin.math.abs(v) >= Long.MAX_VALUE / scale.toFloat()) return v.toString()
        // Round the magnitude and re-apply the sign afterwards. Math.round is
        // half-up toward +infinity, so rounding the signed value directly would
        // turn -62.5 into -62 ("-0.062") where "%.3f" yields "-0.063".
        val scaled = Math.round(kotlin.math.abs(v.toDouble()) * scale)
        val whole = scaled / scale
        val frac = scaled % scale
        val sign = if (v < 0 && scaled != 0L) "-" else ""
        return "$sign$whole." + frac.toString().padStart(decimals, '0')
    }
}
