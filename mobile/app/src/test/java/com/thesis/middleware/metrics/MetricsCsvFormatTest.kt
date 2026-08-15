package com.thesis.middleware.metrics

import com.thesis.middleware.adaptation.ExecutionEvent
import com.thesis.middleware.decision.ExecutionTarget
import com.thesis.middleware.decision.OffloadingDecision
import com.thesis.middleware.decision.SignalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Guards the CSV contract between the Android runtime and
 * `evaluation/notebooks/random-forest-training.ipynb`.
 *
 * The notebook indexes columns by name and `collect_data.ps1` indexes some by
 * position, so both the header text and the field order are load-bearing.
 */
class MetricsCsvFormatTest {

    private fun signals(
        networkScore: Float = 0.75f,
        estLocalEnergyMj: Float = 1600f,
        estRemoteEnergyMj: Float = 120.5f,
        speedup: Float = 4f,
    ) = SignalSnapshot(
        batteryPercent = 42,
        isCharging = false,
        networkType = "WIFI",
        networkScore = networkScore,
        rttMs = 15f,
        bandwidthMbps = 80f,
        signalDbm = 4,
        cpuUsagePercent = 20f,
        cpuCores = 8,
        isStable = true,
        linearAccelMps2 = 0.1f,
        estLocalLatencyMs = 2000f,
        estRemoteLatencyMs = 500f,
        estLocalEnergyMj = estLocalEnergyMj,
        estRemoteEnergyMj = estRemoteEnergyMj,
        computeSpeedup = speedup,
        taskComplexity = "HEAVY",
    )

    private fun event(
        rule: String = "HEAVY_COMPUTE_GOOD_BANDWIDTH",
        reasoning: String = "heavy compute",
        errorMessage: String? = null,
        signals: SignalSnapshot = signals(),
        executedAt: String = "edge",
        serverExecMs: Float? = 180f,
        debugOverrides: String = "",
        measuredPowerMw: Float? = 2100f,
        measuredEnergyMj: Float? = 1075f,
        inputSizeBytes: Long = 64_000,
    ) = ExecutionEvent(
        taskId = "task-1",
        taskName = "matrix-multiply",
        decision = OffloadingDecision(
            shouldOffload = true,
            target = ExecutionTarget.EDGE,
            rule = rule,
            reasoning = reasoning,
            signals = signals,
            debugOverrides = debugOverrides,
        ),
        actualMs = 512,
        resultSizeBytes = 64_000,
        fellBackToLocal = false,
        errorMessage = errorMessage,
        executedAt = executedAt,
        serverExecMs = serverExecMs,
        measuredPowerMw = measuredPowerMw,
        measuredEnergyMj = measuredEnergyMj,
        inputSizeBytes = inputSizeBytes,
    )

    // ── Schema ────────────────────────────────────────────────────────────────

    @Test
    fun `header lists the 29 columns the notebook reads`() {
        assertEquals(
            listOf(
                "timestamp_iso", "task_id", "task_name", "target", "fell_back",
                "actual_ms", "result_bytes", "error", "rule", "battery_percent",
                "is_charging", "network_type", "network_score", "rtt_ms",
                "bandwidth_mbps", "cpu_percent", "is_stable",
                "est_local_ms", "est_remote_ms",
                "est_local_energy_mj", "est_remote_energy_mj",
                "speedup", "executed_at", "server_exec_ms",
                "measured_power_mw", "measured_energy_mj", "input_size_bytes",
                "debug_overrides", "reasoning",
            ),
            MetricsCsvFormat.HEADER.split(","),
        )
        assertEquals(29, MetricsCsvFormat.COLUMN_COUNT)
    }

    @Test
    fun `measured energy is recorded next to the modelled estimate`() {
        // The whole point: est_local_energy_mj is a model output, measured_energy_mj
        // is an observation, and the notebook regresses one against the other.
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(measuredPowerMw = 2450.5f, measuredEnergyMj = 1254.6f),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("2450.5", fields[header.indexOf("measured_power_mw")])
        assertEquals("1254.6", fields[header.indexOf("measured_energy_mj")])
    }

    @Test
    fun `devices without a current sensor leave the measured columns empty`() {
        // Not every phone implements BATTERY_PROPERTY_CURRENT_NOW. Zero would be
        // read as "this task used no energy" and would poison any mean.
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(measuredPowerMw = null, measuredEnergyMj = null),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("", fields[header.indexOf("measured_power_mw")])
        assertEquals("", fields[header.indexOf("measured_energy_mj")])
    }

    @Test
    fun `payload size is recorded because it now varies within a task type`() {
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(inputSizeBytes = 262_144),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("262144", fields[header.indexOf("input_size_bytes")])
    }

    @Test
    fun `debug overrides are recorded so synthetic estimates can be excluded`() {
        // collect_data.ps1 Session B forces remote_energy_mj=50 to make
        // LOW_BATTERY_OFFLOAD fire. Without this marker those ~120 rows carry a
        // synthetic est_remote_energy_mj indistinguishable from a real estimate.
        val header = MetricsCsvFormat.HEADER.split(",")

        val overridden = MetricsCsvFormat.row(
            event(debugOverrides = "remote_energy_mj=50.0"),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("remote_energy_mj=50.0", overridden[header.indexOf("debug_overrides")])

        val clean = MetricsCsvFormat.row(event(), "2026-08-15T10:00:00.000").split(",")
        assertEquals("", clean[header.indexOf("debug_overrides")])
    }

    @Test
    fun `row has exactly as many fields as the header`() {
        val fields = MetricsCsvFormat.row(event(), "2026-08-15T10:00:00.000").split(",")
        assertEquals(MetricsCsvFormat.COLUMN_COUNT, fields.size)
    }

    @Test
    fun `energy estimates land in their named columns`() {
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(event(), "2026-08-15T10:00:00.000").split(",")
        assertEquals("1600.0", fields[header.indexOf("est_local_energy_mj")])
        assertEquals("120.5", fields[header.indexOf("est_remote_energy_mj")])
    }

    @Test
    fun `server-reported execution tier is recorded alongside the chosen target`() {
        // edge-server forwards to cloud under overload, so a target=EDGE row can
        // legitimately carry executed_at=cloud. The notebook needs both to
        // attribute measured latency to the tier that actually ran the work.
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(executedAt = "cloud", serverExecMs = 240.5f),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("EDGE", fields[header.indexOf("target")])
        assertEquals("cloud", fields[header.indexOf("executed_at")])
        assertEquals("240.5", fields[header.indexOf("server_exec_ms")])
    }

    @Test
    fun `local execution leaves server exec time empty rather than zero`() {
        // Zero would be indistinguishable from an instant server round-trip and
        // would drag down any mean computed over the column.
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(executedAt = "local", serverExecMs = null),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("local", fields[header.indexOf("executed_at")])
        assertEquals("", fields[header.indexOf("server_exec_ms")])
    }

    @Test
    fun `positional columns used by collect_data_ps1 keep their indices`() {
        // The verification block in collect_data.ps1 reads task_name at 2,
        // fell_back at 4, and rule at 8 via a naive comma split.
        val header = MetricsCsvFormat.HEADER.split(",")
        assertEquals("task_name", header[2])
        assertEquals("fell_back", header[4])
        assertEquals("rule", header[8])
    }

    // ── Escaping ──────────────────────────────────────────────────────────────

    @Test
    fun `reasoning containing commas is quoted so the row stays parseable`() {
        val reasoning = "balanced cost: local=250.0 (lat 200.0, eng 50.0), remote=125.0"
        val line = MetricsCsvFormat.row(
            event(reasoning = reasoning),
            "2026-08-15T10:00:00.000",
        )
        assertTrue(line.endsWith("\"$reasoning\""))
    }

    @Test
    fun `embedded quotes are doubled per rfc 4180`() {
        assertEquals("\"he said \"\"hi\"\", loudly\"", MetricsCsvFormat.csvEscape("he said \"hi\", loudly"))
    }

    @Test
    fun `a newline in an error message cannot split the row`() {
        val line = MetricsCsvFormat.row(
            event(errorMessage = "IOException:\nconnection reset"),
            "2026-08-15T10:00:00.000",
        )
        assertTrue(line.contains("\"IOException:\nconnection reset\""))
    }

    @Test
    fun `plain fields are not quoted`() {
        assertEquals("BALANCED_COST", MetricsCsvFormat.csvEscape("BALANCED_COST"))
    }

    // ── Locale independence ───────────────────────────────────────────────────

    @Test
    fun `decimal separator stays a dot under a comma-decimal locale`() {
        // String.format() honours the default Locale: under de-DE / fr-FR / vi-VN
        // a bare "%.3f" emits "0,750", injecting an extra field separator into an
        // unquoted numeric column and shifting every column after it.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val header = MetricsCsvFormat.HEADER.split(",")
            val fields = MetricsCsvFormat.row(event(), "2026-08-15T10:00:00.000").split(",")
            assertEquals(MetricsCsvFormat.COLUMN_COUNT, fields.size)
            assertEquals("0.750", fields[header.indexOf("network_score")])
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the offline latency sentinel survives as a parseable float`() {
        // LatencyEstimator returns Float.MAX_VALUE/4 as remote latency when the
        // device is offline. Naive fixed-point scaling saturates a Long and would
        // write ~9.2e17 — a finite, plausible, and completely wrong number that
        // would silently dominate any mean over est_remote_ms.
        val header = MetricsCsvFormat.HEADER.split(",")
        val sentinel = Float.MAX_VALUE / 4f
        val row = MetricsCsvFormat.row(
            event(
                signals = signals().copy(estRemoteLatencyMs = sentinel),
            ),
            "2026-08-15T10:00:00.000",
        )
        val value = row.split(",")[header.indexOf("est_remote_ms")]
        assertEquals(sentinel, value.toFloat(), sentinel * 1e-6f)
    }

    @Test
    fun `halfway values round away from zero like the percent-f they replaced`() {
        // Both inputs are exactly representable in float32, so ×1000 lands on a
        // precise .5 and the rounding direction is unambiguous.
        val header = MetricsCsvFormat.HEADER.split(",")
        val fields = MetricsCsvFormat.row(
            event(signals = signals(networkScore = -0.0625f, speedup = 2.0625f)),
            "2026-08-15T10:00:00.000",
        ).split(",")
        assertEquals("-0.063", fields[header.indexOf("network_score")])
        assertEquals("2.063", fields[header.indexOf("speedup")])
    }
}
