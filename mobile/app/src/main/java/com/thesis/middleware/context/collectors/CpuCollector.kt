package com.thesis.middleware.context.collectors

import android.os.Process
import android.os.SystemClock
import com.thesis.middleware.context.CpuContext
import java.io.File

/**
 * Measures CPU usage by diffing Process.getElapsedCpuTime() against wall-clock
 * time between successive calls.
 *
 * /proc/stat is blocked by SELinux on Android 8+ for third-party apps, so we
 * measure this process's own CPU consumption instead. The result is the fraction
 * of one CPU core used by the middleware process, capped at 100%.
 */
class CpuCollector {

    private var lastCpuMs = 0L
    private var lastRealMs = 0L

    fun collect(): CpuContext = CpuContext(
        usagePercent = readUsagePercent(),
        availableCores = Runtime.getRuntime().availableProcessors(),
        frequencyMhz = readFrequencyMhz()
    )

    private fun readUsagePercent(): Float {
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
