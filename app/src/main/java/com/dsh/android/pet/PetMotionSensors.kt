package com.dsh.android.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Android sensor lifecycle for the visible overlay, translated to W3C units. */
internal class PetMotionSensors(
    private val context: Context,
    private val view: WebView,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravity = FloatArray(3)
    private val acceleration = FloatArray(3)
    private val rate = FloatArray(3)
    private val matrix = FloatArray(9)
    private val angles = FloatArray(3)
    private var hasOrientation = false
    private var registered = false
    private var started = false
    private var busy = false
    private var lastSent = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (power.isInteractive) registerSensors() else unregisterSensors()
        }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context, screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (power.isInteractive) registerSensors()
    }

    fun stop() {
        if (!started) return
        started = false
        context.unregisterReceiver(screen)
        unregisterSensors()
    }

    private fun registerSensors() {
        if (registered) return
        registered = true
        listOfNotNull(
            rotation, linear,
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
        ).forEach { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun unregisterSensors() {
        manager.unregisterListener(this)
        registered = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, angles)
                hasOrientation = true
            }
            Sensor.TYPE_GYROSCOPE -> event.values.copyInto(rate, endIndex = 3)
            Sensor.TYPE_LINEAR_ACCELERATION -> event.values.copyInto(acceleration, endIndex = 3)
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) {
                    gravity[i] = gravity[i] * 0.8f + event.values[i] * 0.2f
                    if (linear == null) acceleration[i] = event.values[i] - gravity[i]
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastSent < 16 || !started || !registered) return
        val interval = if (lastSent == 0L) 20L else now - lastSent
        lastSent = now
        val sample = JSONObject().apply {
            // Android pitch has the opposite sign to DeviceOrientation.beta.
            put("alpha", if (hasOrientation) (360.0 - degrees(angles[0])) % 360.0 else JSONObject.NULL)
            put("beta", if (hasOrientation) -degrees(angles[1]) else JSONObject.NULL)
            put("gamma", if (hasOrientation) degrees(angles[2]) else JSONObject.NULL)
            put("acceleration", vector(acceleration))
            put("gravity", vector(gravity))
            put("rotationRate", JSONObject().apply {
                put("alpha", degrees(rate[2]))
                put("beta", degrees(rate[0]))
                put("gamma", degrees(rate[1]))
            })
            put("interval", interval)
        }
        busy = true
        // Keep one evaluation outstanding. The old 33ms gate discarded every
        // other 20ms GAME-sensor sample, limiting reactions to roughly 25Hz.
        view.evaluateJavascript("window.__dshPetOnly?.receiveMotion($sample)") { busy = false }
    }

    private fun degrees(radians: Float): Double = Math.toDegrees(radians.toDouble())

    private fun vector(values: FloatArray): JSONObject = JSONObject().apply {
        put("x", values[0].toDouble())
        put("y", values[1].toDouble())
        put("z", values[2].toDouble())
    }
}
