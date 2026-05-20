package com.thesis.middleware.context.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.thesis.middleware.context.BatteryContext

class BatteryCollector(private val context: Context) {

    fun collect(): BatteryContext {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryContext(levelPercent = 0, isCharging = false, temperatureCelsius = 0f)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val levelPercent = if (scale > 0) level * 100 / scale else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        // Android reports temperature in tenths of a degree Celsius
        val temperatureCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f

        return BatteryContext(
            levelPercent = levelPercent,
            isCharging = isCharging,
            temperatureCelsius = temperatureCelsius
        )
    }
}
