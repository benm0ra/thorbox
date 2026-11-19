// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.thorbox.app.AppState
import com.thorbox.app.TopView
import kotlin.math.abs
import kotlin.math.sign

/**
 * One place that maps the Thor's physical controls to actions. Every window (Activity,
 * Presentation, fallback second Activity) forwards input here: Android sends key events only to
 * the focused display, which is whichever screen you touched last.
 *
 * The map depends on context (what the top screen shows):
 *
 *   SAMPLER  A / R1 hold = play pad · D-pad ←/→ ±1 st · ↑/↓ ±12 · X cycle play mode · Y reset pitch
 *   SYNTH    the sticks are the four encoders (L-stick X = blue, Y = green; R-stick X = white, Y = orange)
 *            D-pad ←/→ page · ↑/↓ keyboard octave · A / R1 hold = play root (or root chord)
 *            X chord mode · Y reset page
 *   SEQ      D-pad ←/→ step cursor · ↑/↓ edit the current lane · A toggle step · X dice · Y undo
 *   MIX      D-pad ←/→ select strip · ↑/↓ ±1 dB · A mute · Y solo · L-stick Y volume · R-stick X pan
 *   always   START play/stop · SELECT record · L1 hold = note repeat (with A/R1, pads or keys)
 *            L2 analog = filter-sweep punch-in (deeper the harder you press) · R2 = stutter punch-in
 *            B is consumed, so it never acts as Back and quits mid-set
 */
class InputRouter(private val state: AppState) {

    private val axes = FloatArray(4)          // LX, LY, RX, RY after deadzone
    private var heldRoot: IntArray? = null

    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val up = e.action == KeyEvent.ACTION_UP
        if (first) state.lastInput.value = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        val synth = state.topView.value == TopView.SYNTH
        val g = state.groove

        // Global transport keys
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_START -> { if (first) g.togglePlay(); return true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { if (first) g.toggleRecord(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (first) g.setRepeatHeld(true) else if (up) g.setRepeatHeld(false); return true }
        }

        if (state.topView.value == TopView.SEQ) {
            val tr = g.seqTrack.intValue
            val steps = com.thorbox.app.groove.Steps.count(g.clips.value[tr].lengthTicks)
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) g.cursor.intValue = (g.cursor.intValue - 1 + steps) % steps
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) g.cursor.intValue = (g.cursor.intValue + 1) % steps
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    val c = g.clips.value[tr]
                    val ev = com.thorbox.app.groove.Steps.byStep(c.notes, c.lengthTicks).getOrNull(g.cursor.intValue)
                    if (ev != null) {
                        val stepSize = if (g.lane.value == com.thorbox.app.groove.Lane.NOTE) 1f / 14f else 1f / 16f
                        val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) stepSize else -stepSize
                        g.stepEdit(tr, g.cursor.intValue, g.lane.value, (g.laneValue(tr, ev, g.lane.value) + d).coerceIn(0f, 1f))
                    }
                }
                KeyEvent.KEYCODE_BUTTON_A -> if (first) g.stepTap(tr, g.cursor.intValue)
                KeyEvent.KEYCODE_BUTTON_X -> if (first) g.dice(tr)
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.undo(tr)
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit
                else -> return false
            }
            return true
        }

        if (state.topView.value == TopView.MIX) {
            val sel = g.mixSelected.intValue          // 0, 1 = strips, 2 = master
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (first) g.mixSelected.intValue = (sel + 2) % 3
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) g.mixSelected.intValue = (sel + 1) % 3
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1f else -1f
                    if (sel == 2) g.setMaster(g.masterDb.value + d) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + d) }
                }
                KeyEvent.KEYCODE_BUTTON_A -> if (first && sel < 2) g.setChannel(sel) { it.copy(mute = !it.mute) }
                KeyEvent.KEYCODE_BUTTON_Y -> if (first && sel < 2) g.setChannel(sel) { it.copy(solo = !it.solo) }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit
                else -> return false
            }
            return true
        }

        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 -> when {
                synth && first -> { val n = state.notesForKey(0); heldRoot = n; state.keyDown(n, 0.85f) }
                synth && up -> { heldRoot?.let(state::keyUp); heldRoot = null }
                !synth && first -> state.padDown(0.9f)
                !synth && up -> state.padUp()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (synth) { if (first) state.cyclePage(-1) } else if (down) state.nudgeSemis(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (synth) { if (first) state.cyclePage(+1) } else if (down) state.nudgeSemis(+1)
            KeyEvent.KEYCODE_DPAD_UP -> if (first) { if (synth) state.shiftOctave(+1) else state.nudgeSemis(+12) }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (first) { if (synth) state.shiftOctave(-1) else state.nudgeSemis(-12) }
            KeyEvent.KEYCODE_BUTTON_X -> if (first) { if (synth) state.chordMode.value = !state.chordMode.value else state.cyclePlayMode() }
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) { if (synth) state.resetPage(state.synthPage.value) else state.resetPitch() }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit   // consumed, reserved
            else -> return false
        }
        return true
    }

    private var lastL2 = 0f
    private var lastR2 = 0f

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        axes[0] = deadzone(e.getAxisValue(MotionEvent.AXIS_X))
        axes[1] = deadzone(e.getAxisValue(MotionEvent.AXIS_Y))
        axes[2] = deadzone(e.getAxisValue(MotionEvent.AXIS_Z))
        axes[3] = deadzone(e.getAxisValue(MotionEvent.AXIS_RZ))
        val l2 = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r2 = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        if (abs(l2 - lastL2) > 0.02f || abs(r2 - lastR2) > 0.02f) {
            analogPunch(com.thorbox.engine.NativeEngine.Fx.FILTER, lastL2, l2) { p -> 0.5f - 0.5f * p }   // deeper low-pass
            analogPunch(com.thorbox.engine.NativeEngine.Fx.STUTTER, lastR2, r2) { p -> 0.15f + 0.85f * p } // shorter slices
            lastL2 = l2; lastR2 = r2
            state.lastInput.value = "L2 %.2f  R2 %.2f".format(l2, r2)
        }
        return true
    }

    /** Analog trigger → punch-in: engages past 8% travel, the pressure sets X, and release snaps back. */
    private inline fun analogPunch(id: Int, before: Float, now: Float, x: (Float) -> Float) {
        val g = state.groove
        when {
            before < ENGAGE && now >= ENGAGE -> g.fxDown(id, x(now), 0.5f)
            before >= ENGAGE && now >= ENGAGE -> g.fxMove(id, x(now), 0.5f)
            before >= ENGAGE && now < ENGAGE -> g.fxUp(id)
        }
    }

    /** Called once per frame while the synth view is up. Sticks act like relative encoders:
     *  full deflection sweeps a whole knob in about 1.2 s, and the curve is squared for fine control near centre. */
    fun applySticks(dt: Float) {
        if (dt <= 0f) return
        if (state.topView.value == TopView.MIX) {
            val g = state.groove; val sel = g.mixSelected.intValue
            val vol = -axes[1] * abs(axes[1]) * dt * 30f            // dB per second at full tilt
            val pan = axes[2] * abs(axes[2]) * dt * 1.5f
            if (vol != 0f) { if (sel == 2) g.setMaster(g.masterDb.value + vol) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + vol) } }
            if (pan != 0f && sel < 2) g.setChannel(sel) { it.copy(pan = it.pan + pan) }
            return
        }
        if (state.topView.value != TopView.SYNTH) return
        val page = state.synthPage.value
        // Stick up = increase, so Y axes are inverted.
        val dirs = floatArrayOfAxes()
        for (i in 0 until 4) {
            val a = dirs[i]
            if (a != 0f) state.nudgeKnob(page, i, a * abs(a) * dt * 0.85f)
        }
    }

    private val scratch = FloatArray(4)
    private fun floatArrayOfAxes(): FloatArray {
        scratch[0] = axes[0]; scratch[1] = -axes[1]; scratch[2] = axes[2]; scratch[3] = -axes[3]
        return scratch
    }

    private fun deadzone(v: Float): Float {
        val a = abs(v)
        return if (a < DEADZONE) 0f else sign(v) * (a - DEADZONE) / (1f - DEADZONE)
    }

    companion object { private const val DEADZONE = 0.15f; private const val ENGAGE = 0.08f }
}
