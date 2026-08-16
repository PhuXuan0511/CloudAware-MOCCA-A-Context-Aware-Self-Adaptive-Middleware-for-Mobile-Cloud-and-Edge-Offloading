package com.thesis.middleware.metrics

import android.content.Context
import android.util.Log
import com.thesis.middleware.adaptation.ExecutionEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends one CSV row per [ExecutionEvent] to a file in the app's external
 * files directory, so the thesis can produce charts (predicted vs. actual
 * latency, predicted vs. actual energy, fallback rate, target distribution)
 * from real runs.
 *
 * File location:
 *   /storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv
 *
 * Pull from the host with:
 *   adb pull /storage/emulated/0/Android/data/com.thesis.middleware/files/mocca-metrics.csv training.csv
 *
 * Not `run-as ... cat files/...` - that reads the app's *internal* storage,
 * which this class never writes to, and silently returns nothing.
 *
 * The column layout lives in [MetricsCsvFormat]; this class owns only file
 * handling, timestamping, and schema migration.
 *
 * Subscribing the recorder to [com.thesis.middleware.adaptation.ExecutionProxy.events]
 * is the caller's responsibility — [com.thesis.middleware.context.ContextService]
 * does the wiring on startup.
 */
class MetricsRecorder(context: Context) {

    private val dir: File? = context.getExternalFilesDir(null)
    private val file: File = File(dir, FILE_NAME).also { prepare(it) }

    private val lock = Any()
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    val filePath: String get() = file.absolutePath

    fun record(event: ExecutionEvent) {
        val line = MetricsCsvFormat.row(event, isoFmt.format(Date())) + "\n"
        synchronized(lock) { file.appendText(line) }
    }

    /**
     * Ensures [f] exists and its header matches the current schema.
     *
     * If a CSV written by an older build is present, it is archived under a
     * timestamped name instead of being appended to. Mixing column layouts in
     * one file used to force `collect_data.ps1` to detect and discard
     * short rows, and would silently misalign `pandas.read_csv` — rows with
     * fewer fields than the header get NaN-padded on the *right*, so every
     * estimator column would shift.
     */
    private fun prepare(f: File) {
        f.parentFile?.mkdirs()

        if (!f.exists()) {
            f.writeText(MetricsCsvFormat.HEADER + "\n")
            return
        }

        val existingHeader = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()
        if (existingHeader == MetricsCsvFormat.HEADER) return

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = File(f.parentFile, "mocca-metrics-$stamp.csv")
        val moved = runCatching { f.renameTo(archive) }.getOrDefault(false)
        Log.w(
            TAG,
            if (moved) {
                "CSV schema changed — archived previous file to ${archive.name}"
            } else {
                "CSV schema changed but archiving failed — overwriting ${f.name}"
            }
        )
        f.writeText(MetricsCsvFormat.HEADER + "\n")
    }

    companion object {
        private const val TAG = "MetricsRecorder"
        private const val FILE_NAME = "mocca-metrics.csv"
    }
}
