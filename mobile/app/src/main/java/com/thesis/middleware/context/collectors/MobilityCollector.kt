package com.thesis.middleware.context.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.thesis.middleware.context.MobilityContext
import com.thesis.middleware.context.MovementState
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Classifies movement state from a smoothed accelerometer magnitude.
 * Registers a listener on construction; call [stop] when the owning
 * scope is cancelled so the sensor can suspend.
 */
class MobilityCollector(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var smoothedMagnitude = SensorManager.GRAVITY_EARTH

    init {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun collect(): MobilityContext {
        val linear = abs(smoothedMagnitude - SensorManager.GRAVITY_EARTH)
        val state = when {
            linear < STATIONARY_THRESHOLD -> MovementState.STATIONARY
            linear < WALKING_THRESHOLD -> MovementState.WALKING
            else -> MovementState.VEHICLE
        }
        return MobilityContext(speedMps = linear, movementState = state)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        smoothedMagnitude = SMOOTHING * smoothedMagnitude + (1f - SMOOTHING) * magnitude
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val SMOOTHING = 0.8f
        private const val STATIONARY_THRESHOLD = 0.3f
        private const val WALKING_THRESHOLD = 2.5f
    }
}
