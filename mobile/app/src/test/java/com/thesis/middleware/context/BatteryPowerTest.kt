package com.thesis.middleware.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Power derivation from the battery current sensor.
 *
 * This is the measured counterpart to `EnergyEstimator`'s hard-coded
 * 800 / 1500 / 50 mW coefficients, which that file itself describes as
 * "order-of-magnitude estimates". Getting the arithmetic or the null-handling
 * wrong would not crash — it would just produce a plausible number that
 * silently invalidates the energy chapter.
 */
class BatteryPowerTest {

    private fun battery(current: Int?, voltage: Int?) = BatteryContext(
        levelPercent = 80,
        isCharging = false,
        temperatureCelsius = 30f,
        currentNowMicroAmps = current,
        voltageMilliVolts = voltage,
    )

    @Test
    fun `power is voltage times current`() {
        // 3.9 V x 600 mA = 2340 mW
        assertEquals(2340f, battery(600_000, 3900)!!.powerMilliWatts!!, 0.5f)
    }

    @Test
    fun `discharge current is negative on most devices but power is positive`() {
        // The sign convention is vendor-specific and the platform does not
        // guarantee it, so only the magnitude is meaningful.
        val discharging = battery(-600_000, 3900).powerMilliWatts!!
        val charging = battery(600_000, 3900).powerMilliWatts!!
        assertEquals(charging, discharging, 0.01f)
    }

    @Test
    fun `power is null when the device does not report current`() {
        assertNull(battery(null, 3900).powerMilliWatts)
    }

    @Test
    fun `power is null when voltage is missing or nonsensical`() {
        assertNull(battery(600_000, null).powerMilliWatts)
        assertNull(battery(600_000, 0).powerMilliWatts)
        assertNull(battery(600_000, -1).powerMilliWatts)
    }

    @Test
    fun `a realistic idle draw lands in a plausible range`() {
        // Sanity anchor: a phone idling at ~150 mA on a 3.85 V cell is ~580 mW.
        // If a unit conversion slips by 1000x this test catches it.
        val mw = battery(150_000, 3850).powerMilliWatts!!
        assertEquals(577.5f, mw, 1f)
    }
}
