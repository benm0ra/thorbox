// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.app.TopView
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Motion as an instrument (opt-in: the TILT toggle on the FX deck).
 *
 *  TILT → FX   While any punch-in effect is held, leaning the Thor steers its X/Y around where
 *              you touched it (roll = X, pitch = Y, ±25° = ±0.5). The neutral pose is captured
 *              when the effect engages, so it works however you're holding the device.
 *  SHAKE → DICE Two sharp shakes within 0.45 s roll the dice for the current view: a new synth
 *              sound (SYN), a new beat (DRM) or a new pattern (SEQ). 1.2 s cooldown.
 *
 * Sensors are registered only while enabled and the app is in the foreground. No permission is
 * needed (the default sampling rate is well under Android's 200 Hz limit).
 */
class TiltController(private val app: AppState, context: Context) : SensorEventListener {
    val enabled = mutableStateOf(false)
    private val sm = context.getSystemService(SensorManager::class.java)
    private val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)

    /** Sensor axes are in the device's natural orientation; turn them into screen left/right, toward/away. */
    private fun screenAxes(x: Float, y: Float): Pair<Float, Float> =
        when (dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0) {
            android.view.Surface.ROTATION_90 -> -y to x
            android.view.Surface.ROTATION_180 -> -x to -y
            android.view.Surface.ROTATION_270 -> y to -x
            else -> x to y
        }
    private val gravity = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available get() = gravity != null || accel != null

    private var runningFlag = false
    private var foreground = false
    private val g = FloatArray(3)            // latest gravity
    private var roll = 0f; private var pitch = 0f
    private var baseRoll = 0f; private var basePitch = 0f
    private var heldBefore = 0
    // shake detection
    private var lastPeak = 0L; private var lastDice = 0L; private var peaks = 0
    private val lowG = FloatArray(3)

    // Live readouts for the diagnostics panel / bubble level (plain fields, read in draw).
    var rollDeg = 0f; private set
    var pitchDeg = 0f; private set
    var events = 0L; private set
    var lastShake = 0f; private set
    val running get() = runningFlag
    fun sensorSummary(): String = "gravity=${gravity?.name ?: "none"} · motion=${accel?.name ?: "none"}"

    /** "x, y" tilt offsets currently applied to held effects (read by the FX deck glyphs). */
    var dx = 0f; private set
    var dy = 0f; private set

    fun setEnabled(on: Boolean) { enabled.value = on; sync() }
    fun onForeground(fg: Boolean) { foreground = fg; sync() }

    private fun sync() {
        val want = enabled.value && foreground && sm != null
        if (want == runningFlag) return
        runningFlag = want
        if (want) {
            val okG = gravity?.let { sm!!.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
            val okA = accel?.let { sm!!.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
            android.util.Log.i("ThorboxTilt", "start: ${sensorSummary()} registered gravity=$okG motion=$okA")
        } else {
            android.util.Log.i("ThorboxTilt", "stop (events so far: $events)")
            sm?.unregisterListener(this)
            dx = 0f; dy = 0f
            app.groove.setTilt(0f, 0f)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        events++
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                g[0] = e.values[0]; g[1] = e.values[1]; g[2] = e.values[2]
                val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(0.1f)
                val (sx, sy) = screenAxes(g[0], g[1])
                roll = asin((sx / n).coerceIn(-1f, 1f)); pitch = asin((sy / n).coerceIn(-1f, 1f))
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> shake(e.values[0], e.values[1], e.values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                // No linear-accel sensor: high-pass the raw accelerometer ourselves.
                for (i in 0..2) lowG[i] += (e.values[i] - lowG[i]) * 0.1f
                if (gravity == null) {
                    val n = sqrt(lowG[0] * lowG[0] + lowG[1] * lowG[1] + lowG[2] * lowG[2]).coerceAtLeast(0.1f)
                    val (sx, sy) = screenAxes(lowG[0], lowG[1])
                    roll = asin((sx / n).coerceIn(-1f, 1f)); pitch = asin((sy / n).coerceIn(-1f, 1f))
                }
                shake(e.values[0] - lowG[0], e.values[1] - lowG[1], e.values[2] - lowG[2])
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun shake(x: Float, y: Float, z: Float) {
        val m = sqrt(x * x + y * y + z * z)
        lastShake = maxOf(m, lastShake * 0.97f)
        val now = SystemClock.uptimeMillis()
        if (m < SHAKE_MS2) return
        if (now - lastPeak < 90) return                    // same jolt
        peaks = if (now - lastPeak < 450) peaks + 1 else 1
        lastPeak = now
        android.util.Log.d("ThorboxTilt", "shake peak %.1f m/s² (#$peaks)".format(m))
        if (peaks >= 2 && now - lastDice > 1200) { lastDice = now; peaks = 0; dice() }   // sensor callbacks arrive on the main looper (registered there)
    }

    private fun dice() {
        when (app.topView.value) {
            TopView.SYNTH -> app.diceSynth()
            TopView.DRUM -> app.groove.diceBeat()
            TopView.SEQ -> app.groove.dice(app.groove.seqTrack.intValue)
            else -> return
        }
        app.lastInput.value = "SHAKE → DICE"
    }

    /** Per UI frame: steer held effects. */
    fun onFrame() {
        if (!runningFlag) return
        rollDeg = Math.toDegrees(roll.toDouble()).toFloat(); pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat()
        val held = app.groove.fxHeld.intValue
        if (held != 0 && heldBefore == 0) { baseRoll = roll; basePitch = pitch }   // neutral = pose at engage
        heldBefore = held
        if (held == 0) { if (dx != 0f || dy != 0f) { dx = 0f; dy = 0f; app.groove.setTilt(0f, 0f) }; return }
        val nx = ((roll - baseRoll) / RANGE_RAD * 0.5f).coerceIn(-0.5f, 0.5f)
        val ny = (-(pitch - basePitch) / RANGE_RAD * 0.5f).coerceIn(-0.5f, 0.5f)
        // Small dead zone so a steady hand stays put.
        val tx = if (abs(nx) < 0.03f) 0f else nx
        val ty = if (abs(ny) < 0.03f) 0f else ny
        if (abs(tx - dx) > 0.005f || abs(ty - dy) > 0.005f) { dx = tx; dy = ty; app.groove.setTilt(tx, ty) }
    }

    companion object {
        private const val RANGE_RAD = 0.436f       // 25°
        private const val SHAKE_MS2 = 13f
    }
}
