package com.thesis.middleware.context.collectors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.thesis.middleware.context.BatteryContext

/**
 * Reads battery level, charging state, temperature, and — where the device
 * supports it — instantaneous current and voltage.
 *
 * Current and voltage exist so the modelled energy figures in `EnergyEstimator`
 * (fixed 800 / 1500 / 50 mW coefficients, described in that file as
 * "order-of-magnitude estimates") can be validated against something measured
 * rather than asserted. `BATTERY_PROPERTY_CURRENT_NOW` is the closest thing to
 * a power monitor available without external hardware.
 *
 * Known limits, which belong in the thesis alongside any number derived here:
 *  - Not all devices implement CURRENT_NOW; some return 0 or Integer.MIN_VALUE.
 *  - The sign convention is vendor-specific, so only the magnitude is used.
 *  - Sampling granularity is coarse (often ~1 Hz internally), so short tasks
 *    are measured poorly.
 *  - It reports *whole-device* draw, not per-task attribution.
 */
class BatteryCollector(private val context: Context) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

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
            temperatureCelsius = temperatureCelsius,
            currentNowMicroAmps = readCurrentNow(),
            voltageMilliVolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                .takeIf { it > 0 },
        )
    }

    /** Whole-device power draw in mW, or null when unsupported. */
    fun samplePowerMilliWatts(): Float? = collect().powerMilliWatts

    /**
     * Returns null rather than a bogus number when the device does not
     * implement the property. Devices signal this inconsistently — some return
     * 0, others Integer.MIN_VALUE — so both are rejected.
     */
    private fun readCurrentNow(): Int? {
        val raw = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return null
        return if (raw == 0 || raw == Int.MIN_VALUE) null else raw
    }
}
