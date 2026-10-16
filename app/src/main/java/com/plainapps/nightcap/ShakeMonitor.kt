package com.plainapps.nightcap

import android.content.Context
import android.hardware.Sensor
import android.media.AudioAttributes
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Listens to the accelerometer only inside the shake window (DECISIONS D18/D33) and holds its own
 * partial wake lock for exactly that time — non-wake-up sensors stop reporting when the SoC suspends
 * (https://source.android.com/docs/core/interaction/sensors/suspend-mode). Owned by [TimerService];
 * [onShake] runs on the main thread.
 */
class ShakeMonitor(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector = ShakeDetector()
    private var wakeLock: PowerManager.WakeLock? = null
    private var aboveThreshold = false

    @Volatile
    var isListening: Boolean = false
        private set

    /** Starts listening (idempotent); the wake lock times out after [maxHoldMillis] as a safety net. */
    @Synchronized
    fun start(maxHoldMillis: Long) {
        if (isListening) {
            // Deadline moved inside the window: re-arm the safety timeout (non-counted lock resets it).
            wakeLock?.acquire(maxHoldMillis.coerceAtLeast(1_000L))
            return
        }
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            Log.w(TAG, "No accelerometer; shake to extend unavailable")
            return
        }
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nightcap:shake").apply {
            setReferenceCounted(false)
            acquire(maxHoldMillis.coerceAtLeast(1_000L))
        }
        detector.reset()
        isListening = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "Listening: registered=$isListening wakeLockMs=$maxHoldMillis")
        if (!isListening) releaseWakeLock()
    }

    @Synchronized
    fun stop(reason: String) {
        if (!isListening && wakeLock == null) return
        sensorManager.unregisterListener(this)
        isListening = false
        releaseWakeLock()
        Log.i(TAG, "Stopped listening: $reason")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val excess = kotlin.math.sqrt(x * x + y * y + z * z) - ShakeDetector.GRAVITY
        val above = excess > ShakeDetector.PEAK_EXCESS
        if (above && !aboveThreshold) Log.i(TAG, "Peak: ${"%.1f".format(excess)} m/s² above g")
        aboveThreshold = above
        if (detector.onSample(x, y, z, event.timestamp / 1_000_000L)) {
            Log.i(TAG, "Shake detected")
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * One 150 ms confirmation buzz (D18). Needs only the normal VIBRATE permission. Alarm usage, not
     * the default (touch) usage, which phones drop when "touch feedback" is off (D33).
     */
    fun vibrate() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val effect = VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Vibrate refused: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "ShakeMonitor"
        private const val VIBRATION_MS = 150L
    }
}
