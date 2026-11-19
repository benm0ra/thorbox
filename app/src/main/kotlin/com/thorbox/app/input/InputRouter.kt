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
 * NAVIGATION (works everywhere). SELECT is SHIFT: hold it to see this map on screen.
 *   SHIFT + D-pad ←/→  top screen view (SMP · SYN · SEQ · MIX · VIZ)
 *   SHIFT + D-pad ↑/↓  bottom deck (PADS · KEYS · STEP · FX)
 *   SHIFT + A          NAV cursor on/off: D-pad moves a ring over every on-screen control, A presses,
 *                      L1/R1 = top/bottom screen, B leaves NAV. Touch-free access to everything.
 *   SHIFT + L1 / R1    tempo −1 / +1 BPM        SHIFT + X  tap tempo
 *   SHIFT + Y metronome · SHIFT + B diagnostics · SHIFT + L3 theme · SHIFT + R3 glass look
 *   SELECT tapped on its own = record.  B = back (leave the visualizer, close the step editor).
 *
 * PLAYING (depends on the top view):
 *   SAMPLER  D-pad moves the pad cursor on the 4×4 grid · A / R1 hold = play it
 *            L-stick flick ←/→ ±1 semitone, ↑/↓ ±12 · X cycle play mode · Y reset pitch
 *   SYNTH    the sticks are the four encoders (L-stick X = blue, Y = green; R-stick X = white, Y = orange)
 *            D-pad ←/→ page · ↑/↓ keyboard octave · A / R1 hold = play root (or root chord)
 *            X chord mode · Y reset page
 *            L3 next preset · R3 DICE a new sound
 *   DRUM     D-pad ←/→ step · ↑/↓ sound · A toggle step · R1 hold = play sound · sticks = encoders
 *            X dice beat · Y undo · L3 next kit · R3 dice kit
 *   SEQ      D-pad ←/→ step cursor · ↑/↓ edit the current lane · A toggle step · X dice · Y undo
 *   MIX      D-pad ←/→ select strip · ↑/↓ ±1 dB · A mute · Y solo · L-stick Y volume · R-stick X pan
 *   always   START play/stop · L1 hold = note repeat (with A/R1, pads or keys)
 *            L2 analog = filter-sweep punch-in (deeper the harder you press) · R2 = stutter punch-in
 *
 * Some handhelds report the D-pad as a hat axis instead of keys; that's turned into D-pad keys here.
 */
class InputRouter(private val state: AppState) {

    private val axes = FloatArray(4)          // LX, LY, RX, RY after deadzone
    private var heldRoot: IntArray? = null

    // ================= NAV cursor: reach every on-screen control without touching =================
    /** NAV mode: D-pad moves a focus ring over buttons, A presses, L1/R1 switch screen, B exits. */
    val navMode = mutableStateOf(false)
    /** 0 = top screen (SESSION role), 1 = bottom screen (DECK role). */
    val navScreen = androidx.compose.runtime.mutableIntStateOf(0)
    private val navSinks = arrayOfNulls<(KeyEvent) -> Boolean>(2)
    private val navFocus = arrayOfNulls<() -> Unit>(2)
    /** A window registers where NAV key events for a screen role go (and how to give it focus). */
    fun registerNav(role: Int, sink: (KeyEvent) -> Boolean, focus: () -> Unit) { navSinks[role] = sink; navFocus[role] = focus }
    fun unregisterNav(role: Int) { navSinks[role] = null; navFocus[role] = null }
    fun setNav(on: Boolean) {
        navMode.value = on
        if (on) navFocus[navScreen.intValue]?.invoke()
        state.lastInput.value = if (on) "NAV ON" else "NAV OFF"
    }
    private fun navKey(e: KeyEvent, first: Boolean, up: Boolean): Boolean {
        fun send(code: Int) = navSinks[navScreen.intValue]?.invoke(KeyEvent(e.downTime, e.eventTime, e.action, code, e.repeatCount)) ?: false
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> send(e.keyCode)
            KeyEvent.KEYCODE_BUTTON_A -> send(KeyEvent.KEYCODE_DPAD_CENTER)
            KeyEvent.KEYCODE_BUTTON_B -> if (first) setNav(false)
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> if (first) {
                val target = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) 0 else 1
                if (navSinks[target] != null) { navScreen.intValue = target; navFocus[target]?.invoke() }
            }
            KeyEvent.KEYCODE_BUTTON_START -> if (first) state.groove.togglePlay()
            else -> Unit
        }
        return true
    }

    /** SELECT is held (the on-screen control map shows after a short hold). */
    val shiftHeld = androidx.compose.runtime.mutableStateOf(false)
    private var shiftUsed = false             // a combo fired, so releasing SELECT doesn't toggle record

    private fun <T> cycle(values: List<T>, current: T, d: Int): T =
        values[(values.indexOf(current).coerceAtLeast(0) + d + values.size) % values.size]

    /** SHIFT combos. Returns true when the key was a navigation key (consumed either way). */
    private fun shiftKey(e: KeyEvent, down: Boolean, first: Boolean): Boolean {
        val g = state.groove
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) {
                val v = cycle(TopView.entries, state.topView.value, if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                state.selectTop(v); state.noteTopTouch()
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (first) {
                val decks = listOf(com.thorbox.model.Deck.PADS, com.thorbox.model.Deck.KEYS, com.thorbox.model.Deck.DRUMS, com.thorbox.model.Deck.STEP, com.thorbox.model.Deck.FX)
                state.selectDeck(cycle(decks, state.deck.value, if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1))
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> if (down) g.nudgeBpm(-1f)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (down) g.nudgeBpm(+1f)
            KeyEvent.KEYCODE_BUTTON_A -> if (first) setNav(!navMode.value)
            KeyEvent.KEYCODE_BUTTON_X -> if (first) g.tapTempo(android.os.SystemClock.uptimeMillis())
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.toggleMetronome()
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) state.cycleGlass()
            KeyEvent.KEYCODE_BUTTON_B -> if (first) state.showDiagnostics.value = !state.showDiagnostics.value
            KeyEvent.KEYCODE_BUTTON_START -> if (first) g.togglePlay()
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first) state.cycleTheme()
            else -> return false
        }
        if (down) shiftUsed = true
        return true
    }

    /** B: step back out of whatever is layered on top. Never quits the app mid-set. */
    private fun back() {
        when {
            state.groove.heldStep.intValue >= 0 -> state.groove.heldStep.intValue = -1
            state.topView.value == TopView.VIZ -> state.exitViz()
        }
    }

    /** Sampler pad cursor on the MPC-style grid (pad 1 bottom-left, index = row * 4 + column). */
    private fun movePad(keyCode: Int) {
        val sm = state.sampler
        val i = sm.selected.intValue
        val col = i % 4; val row = i / 4
        sm.selected.intValue = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> row * 4 + (col + 3) % 4
            KeyEvent.KEYCODE_DPAD_RIGHT -> row * 4 + (col + 1) % 4
            KeyEvent.KEYCODE_DPAD_UP -> ((row + 1) % 4) * 4 + col
            else -> ((row + 3) % 4) * 4 + col
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val up = e.action == KeyEvent.ACTION_UP
        if (first) {
            state.lastInput.value = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
            state.noteTopTouch()      // using the buttons counts as activity: don't auto-jump to the visualizer
        }
        val synth = state.topView.value == TopView.SYNTH
        val g = state.groove

        // SELECT = SHIFT (tap alone = record, on release)
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (first) { shiftHeld.value = true; shiftUsed = false }
            else if (up) { shiftHeld.value = false; if (!shiftUsed) g.toggleRecord() }
            return true
        }
        // Only key-downs go through SHIFT, so a key that was already held (a pad, L1 repeat) still
        // gets its release when SELECT is pressed in between.
        if (shiftHeld.value && down && shiftKey(e, down, first)) return true
        if (navMode.value) return navKey(e, first, up)

        // Global keys
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_START -> { if (first) g.togglePlay(); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { if (first) back(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (first) g.setRepeatHeld(true) else if (up) g.setRepeatHeld(false); return true }
        }

        if (state.topView.value == TopView.DRUM) {
            val dc = state.drums
            val track = state.drums.track
            val steps = com.thorbox.app.groove.Steps.count(g.clips.value[track].lengthTicks)
            val slots = com.thorbox.model.DRUM_SLOTS
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) g.cursor.intValue = (g.cursor.intValue - 1 + steps) % steps
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) g.cursor.intValue = (g.cursor.intValue + 1) % steps
                KeyEvent.KEYCODE_DPAD_UP -> if (down) dc.selected.intValue = (dc.selected.intValue - 1 + slots) % slots
                KeyEvent.KEYCODE_DPAD_DOWN -> if (down) dc.selected.intValue = (dc.selected.intValue + 1) % slots
                KeyEvent.KEYCODE_BUTTON_A -> if (first) g.drumToggle(g.cursor.intValue % steps, dc.selected.intValue)
                KeyEvent.KEYCODE_BUTTON_R1 -> if (first) dc.hit(dc.selected.intValue, 0.9f) else if (up) dc.release(dc.selected.intValue)
                KeyEvent.KEYCODE_BUTTON_X -> if (first) g.diceBeat()
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.undo(track)
                KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first) dc.stepKit(+1)
                KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) dc.diceKit()
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> Unit
                else -> return false
            }
            return true
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
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit
                else -> return false
            }
            return true
        }

        if (state.topView.value == TopView.MIX) {
            val sel = g.mixSelected.intValue          // 0 sampler, 1 synth, 2 drums, 3 = master
            val master = com.thorbox.app.ui.MASTER_STRIP
            when (e.keyCode) {
                // Only real tracks + master are selectable (empty slots are skipped).
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) {
                    val order = state.tracks.value.indices.filter { state.trackKind(it) != com.thorbox.model.TrackKind.EMPTY } + master
                    val i = order.indexOf(sel).coerceAtLeast(0)
                    g.mixSelected.intValue = order[(i + (if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1) + order.size) % order.size]
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                    val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1f else -1f
                    if (sel == master) g.setMaster(g.masterDb.value + d) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + d) }
                }
                KeyEvent.KEYCODE_BUTTON_A -> if (first && sel < master) g.setChannel(sel) { it.copy(mute = !it.mute) }
                KeyEvent.KEYCODE_BUTTON_X -> if (first && sel < master) state.selectTrack(sel)            // open the selected track
                KeyEvent.KEYCODE_BUTTON_Y -> if (first && sel < master) g.setChannel(sel) { it.copy(solo = !it.solo) }
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
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
            KeyEvent.KEYCODE_DPAD_LEFT -> if (synth) { if (first) state.cyclePage(-1) } else if (down) movePad(e.keyCode)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (synth) { if (first) state.cyclePage(+1) } else if (down) movePad(e.keyCode)
            KeyEvent.KEYCODE_DPAD_UP -> if (synth) { if (first) state.shiftOctave(+1) } else if (down) movePad(e.keyCode)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (synth) { if (first) state.shiftOctave(-1) } else if (down) movePad(e.keyCode)
            KeyEvent.KEYCODE_BUTTON_X -> if (first) { if (synth) state.chordMode.value = !state.chordMode.value else state.cyclePlayMode() }
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) { if (synth) state.resetPage(state.synthPage.value) else state.resetPitch() }
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            // Stick clicks: in the synth, L3 = next preset, R3 = DICE a new sound. Reserved elsewhere.
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first && synth) state.stepSynthPreset(+1)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first && synth) state.diceSynth()
            else -> return false
        }
        return true
    }

    private var lastL2 = 0f
    private var lastR2 = 0f

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        hat(e)
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
        if (state.topView.value == TopView.SAMPLER) flicks()
        return true
    }

    // ---- D-pad reported as a hat axis → the same D-pad keys ----
    private var hatX = 0; private var hatY = 0
    private fun hat(e: MotionEvent) {
        val x = e.getAxisValue(MotionEvent.AXIS_HAT_X).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (it > 0.5f) 1 else if (it < -0.5f) -1 else 0 }
        if (x != hatX) {
            if (hatX != 0) synthKey(if (hatX < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)
            if (x != 0) synthKey(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
            hatX = x
        }
        if (y != hatY) {
            if (hatY != 0) synthKey(if (hatY < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
            if (y != 0) synthKey(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
            hatY = y
        }
    }
    private fun synthKey(code: Int, action: Int) { onKey(KeyEvent(action, code)) }

    // ---- Left-stick flicks (sampler pitch): fire past 60 % tilt, re-arm below 30 % ----
    private var flickArmedX = true; private var flickArmedY = true
    private fun flicks() {
        val x = axes[0]; val y = axes[1]
        if (flickArmedX && abs(x) > 0.6f) { state.nudgeSemis(if (x > 0) 1 else -1); flickArmedX = false }
        else if (abs(x) < 0.3f) flickArmedX = true
        if (flickArmedY && abs(y) > 0.6f) { state.nudgeSemis(if (y < 0) 12 else -12); flickArmedY = false }
        else if (abs(y) < 0.3f) flickArmedY = true
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
            val master = com.thorbox.app.ui.MASTER_STRIP
            if (vol != 0f) { if (sel == master) g.setMaster(g.masterDb.value + vol) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + vol) } }
            if (pan != 0f && sel < master) g.setChannel(sel) { it.copy(pan = it.pan + pan) }
            return
        }
        if (state.topView.value == TopView.DRUM) {
            val dirs = floatArrayOfAxes()
            for (i in 0 until 4) { val a = dirs[i]; if (a != 0f) state.drums.nudgeKnob(i, a * abs(a) * dt * 0.85f) }
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
