package com.thesis.middleware.context.collectors

import android.os.Process
import android.os.SystemClock
import com.thesis.middleware.context.CpuContext
import java.io.File

/**
 * Reports system-wide CPU load as a percentage.
 *
 * Primary: /proc/loadavg — gives the 1-minute system load average. This is
 * accessible on Android (unlike /proc/stat which is blocked by SELinux since
 * API 26). Dividing by core count converts load average to a 0–100% scale,
 * so load=4.0 on an 8-core device → 50%. Running multiple apps genuinely
 * raises this value, enabling the LATENCY_SENSITIVE rule to fire naturally.
 *
 * Fallback: Process.getElapsedCpuTime() diff — measures this process only,
 * used if /proc/loadavg is somehow unavailable.
 */
class CpuCollector {

    private val numCores = Runtime.getRuntime().availableProcessors()
    private var lastCpuMs = 0L
    private var lastRealMs = 0L

    fun collect(): CpuContext = CpuContext(
        usagePercent = readUsagePercent(),
        availableCores = numCores,
        frequencyMhz = readFrequencyMhz()
    )

    private fun readUsagePercent(): Float {
        try {
            val load1min = File("/proc/loadavg").readText().trim().split(" ")[0].toFloat()
            return (load1min / numCores * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) { /* fall through to process-level fallback */ }

        val cpuMs  = Process.getElapsedCpuTime()
        val realMs = SystemClock.elapsedRealtime()
        val diffCpu  = cpuMs  - lastCpuMs
        val diffReal = realMs - lastRealMs
        lastCpuMs  = cpuMs
        lastRealMs = realMs
        return if (diffReal <= 0L) 0f
        else (diffCpu.toFloat() / diffReal.toFloat() * 100f).coerceIn(0f, 100f)
    }

    private fun readFrequencyMhz(): Int = try {
        val khz = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            .readText().trim().toLong()
        (khz / 1000).toInt()
    } catch (_: Exception) {
        0
    }
}
