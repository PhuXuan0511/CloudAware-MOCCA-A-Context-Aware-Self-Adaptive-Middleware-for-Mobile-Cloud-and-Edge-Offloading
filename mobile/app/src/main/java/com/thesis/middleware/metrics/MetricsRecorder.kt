package com.thesis.middleware.metrics

import android.content.Context
import com.thesis.middleware.adaptation.ExecutionEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends one CSV row per [ExecutionEvent] to a file in the app's external
 * files directory, so the thesis can produce charts (predicted vs. actual
 * latency, fallback rate, target distribution) from real runs.
 *
 * File location:
 *   /storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv
 *
 * Pull from the host with:
 *   adb pull /storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv
 *
 * Subscribing the recorder to [com.thesis.middleware.adaptation.ExecutionProxy.events]
 * is the caller's responsibility — [com.thesis.middleware.context.ContextService]
 * does the wiring on startup.
 */
class MetricsRecorder(context: Context) {

    private val file: File =
        File(context.getExternalFilesDir(null), FILE_NAME).also { f ->
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                f.appendText(HEADER + "\n")
            }
        }

    private val lock = Any()
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    val filePath: String get() = file.absolutePath

    fun record(event: ExecutionEvent) {
        val ts = isoFmt.format(Date())
        val s = event.decision.signals
        val cols = listOf(
            ts,
            event.taskId,
            event.taskName,
            event.decision.target.name,
            event.fellBackToLocal.toString(),
            event.actualMs.toString(),
            event.resultSizeBytes.toString(),
            event.errorMessage ?: "",
            // Rule + context signals — the columns the supervisor asked for
            // so we can build evaluation charts (rule distribution, decision
            // accuracy under varying battery / network conditions, etc.).
            event.decision.rule,
            s.batteryPercent.toString(),
            s.isCharging.toString(),
            s.networkType,
            "%.3f".format(s.networkScore),
            "%.1f".format(s.rttMs),
            "%.1f".format(s.bandwidthMbps),
            "%.1f".format(s.cpuUsagePercent),
            s.isStable.toString(),
            "%.1f".format(s.estLocalLatencyMs),
            "%.1f".format(s.estRemoteLatencyMs),
            "%.3f".format(s.computeSpeedup),
            event.decision.reasoning,
        )
        val line = cols.joinToString(",") { csvEscape(it) } + "\n"
        synchronized(lock) { file.appendText(line) }
    }

    private fun csvEscape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    companion object {
        private const val FILE_NAME = "mocca-metrics.csv"
        private const val HEADER =
            "timestamp_iso,task_id,task_name,target,fell_back,actual_ms,result_bytes,error," +
                "rule,battery_percent,is_charging,network_type,network_score," +
                "rtt_ms,bandwidth_mbps,cpu_percent,is_stable," +
                "est_local_ms,est_remote_ms,speedup,reasoning"
    }
}
