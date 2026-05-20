package com.thesis.middleware.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    private val extractor = FeatureExtractor()

    private fun snapshot(
        network: NetworkContext = NetworkContext(NetworkType.WIFI, 0f, 100f, 4),
        cpu: CpuContext = CpuContext(0f, 8, 0),
        battery: BatteryContext = BatteryContext(100, false, 25f),
        location: LocationContext = LocationContext(0.0, 0.0, 0f),
        mobility: MobilityContext = MobilityContext(0f, MovementState.STATIONARY)
    ) = ContextSnapshot(network, cpu, battery, location, mobility, timestamp = 0L)

    @Test
    fun `all scores stay within [0,1]`() {
        val features = extractor.extract(snapshot())
        listOf(
            features.networkScore,
            features.cpuLoadScore,
            features.batteryScore,
            features.mobilityScore
        ).forEach { assertTrue("score $it out of range", it in 0f..1f) }
    }

    @Test
    fun `network score is zero when offline`() {
        val f = extractor.extract(
            snapshot(network = NetworkContext(NetworkType.NONE, 0f, 0f, 0))
        )
        assertEquals(0f, f.networkScore, 1e-4f)
    }

    @Test
    fun `network score reaches 1 on ideal 5G link`() {
        val f = extractor.extract(
            snapshot(network = NetworkContext(NetworkType.FIVE_G, 0f, 500f, 4))
        )
        assertEquals(1f, f.networkScore, 1e-4f)
    }

    @Test
    fun `network score is mid for solid LTE`() {
        val f = extractor.extract(
            snapshot(network = NetworkContext(NetworkType.LTE, 0f, 50f, 3))
        )
        assertTrue("expected mid-band, got ${f.networkScore}", f.networkScore in 0.45f..0.75f)
    }

    @Test
    fun `cpu score inverts usage`() {
        val idle = extractor.extract(snapshot(cpu = CpuContext(0f, 8, 0))).cpuLoadScore
        val pinned = extractor.extract(snapshot(cpu = CpuContext(100f, 8, 0))).cpuLoadScore
        assertEquals(1f, idle, 1e-4f)
        assertEquals(0f, pinned, 1e-4f)
    }

    @Test
    fun `battery charging adds bonus and heat penalizes`() {
        val cool = extractor.extract(
            snapshot(battery = BatteryContext(80, isCharging = true, temperatureCelsius = 25f))
        ).batteryScore
        val hot = extractor.extract(
            snapshot(battery = BatteryContext(80, isCharging = true, temperatureCelsius = 50f))
        ).batteryScore
        assertTrue("charging cool battery should exceed hot one", cool > hot)
        assertEquals(0f, hot, 1e-4f)
    }

    @Test
    fun `battery low and discharging scores low`() {
        val f = extractor.extract(
            snapshot(battery = BatteryContext(5, isCharging = false, temperatureCelsius = 30f))
        )
        assertTrue("got ${f.batteryScore}", f.batteryScore < 0.1f)
    }

    @Test
    fun `mobility score decreases with movement`() {
        val stationary = extractor.extract(
            snapshot(mobility = MobilityContext(0f, MovementState.STATIONARY))
        ).mobilityScore
        val walking = extractor.extract(
            snapshot(mobility = MobilityContext(1.4f, MovementState.WALKING))
        ).mobilityScore
        val vehicle = extractor.extract(
            snapshot(mobility = MobilityContext(15f, MovementState.VEHICLE))
        ).mobilityScore
        assertTrue(stationary > walking)
        assertTrue(walking > vehicle)
    }

    @Test
    fun `model helpers reflect derived state`() {
        assertTrue(NetworkContext(NetworkType.WIFI, 0f, 0f, 0).isOnline)
        assertTrue(!NetworkContext(NetworkType.NONE, 0f, 0f, 0).isOnline)
        assertTrue(BatteryContext(10, false, 25f).isLowPower)
        assertTrue(!BatteryContext(10, true, 25f).isLowPower)
        assertTrue(MobilityContext(0f, MovementState.STATIONARY).isStable)
        assertTrue(!MobilityContext(5f, MovementState.VEHICLE).isStable)
    }
}
