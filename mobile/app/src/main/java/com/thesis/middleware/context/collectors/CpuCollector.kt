package com.thesis.middleware.context.collectors

import com.thesis.middleware.context.CpuContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads aggregate CPU usage from /proc/stat by diffing idle vs. total ticks
 * between successive calls. Frequency comes from cpufreq sysfs when readable.
 */
class CpuCollector {

    private var lastIdle = 0L
    private var lastTotal = 0L

    fun collect(): CpuContext = CpuContext(
        usagePercent = readUsagePercent(),
        availableCores = Runtime.getRuntime().availableProcessors(),
        frequencyMhz = readFrequencyMhz()
    )

    private fun readUsagePercent(): Float = try {
        RandomAccessFile("/proc/stat", "r").use { f ->
            val parts = f.readLine().split(Regex("\\s+"))
            val nums = parts.drop(1).take(8).map { it.toLong() }
            val idle = nums[3] + nums[4] // idle + iowait
            val total = nums.sum()
            val diffIdle = idle - lastIdle
            val diffTotal = total - lastTotal
            lastIdle = idle
            lastTotal = total
            if (diffTotal <= 0) 0f else (1f - diffIdle.toFloat() / diffTotal) * 100f
        }
    } catch (_: Exception) {
        0f
    }

    private fun readFrequencyMhz(): Int = try {
        val khz = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
            .readText().trim().toLong()
        (khz / 1000).toInt()
    } catch (_: Exception) {
        0
    }
}
