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
 * The map itself (what the SELECT-hold cheat sheet shows) lives in [Bindings]; keep the two in step.
 *   SELECT = SHIFT. Tapped on its own (released within [TAP_MS]) = REC. Held = the cheat sheet.
 *   NAV (SHIFT + A): a cursor over every control on both screens, see [Nav].
 *   Playing: the face buttons drive [playContext] (the instrument on top, or the deck under your thumbs).
 *   L2 / R2 analog = filter / stutter punch-in, except in NAV and with SELECT held (those use them as buttons).
 *
 * Some handhelds report the D-pad as a hat axis instead of keys; that's turned into D-pad keys here.
 */
class InputRouter(private val state: AppState) {

    private val axes = FloatArray(4)          // LX, LY, RX, RY after deadzone
    private var l1DownAt = 0L
    private var heldRoot: IntArray? = null

    // ================= NAV cursor: reach every on-screen control without touching =================
    /** NAV mode: D-pad moves a ring over every control on both screens, A presses, B exits. See [Nav]. */
    val navMode get() = Nav.on
    /** 0 = top screen, 1 = bottom screen. */
    val navScreen get() = Nav.screen

    fun setNav(on: Boolean) {
        if (!on) Nav.reset()
        val was = Nav.on.value
        Nav.on.value = on
        if (on && Nav.cur() == null) {
            land()
            // at launch nothing is laid out yet: land on the top screen once it is
            if (Nav.cur() == null) handler.postDelayed({ if (Nav.on.value && Nav.cur() == null) Nav.land(0) }, 700)
        }
        state.lastInput.value = if (on) "NAV ON" else "NAV OFF"
        if (was != on) com.thorbox.app.ui.Info.toast(if (on) "NAV" else "PLAY",
            if (on) "the buttons move the cursor · SELECT + A to play" else "the buttons play the instrument · SELECT + A for the cursor")
    }
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Make sure something on the current screen has the cursor. */
    private fun land() { Nav.land(Nav.screen.intValue) }
    private fun navKey(e: KeyEvent, first: Boolean, up: Boolean): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val dir = when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> NavDir.UP; KeyEvent.KEYCODE_DPAD_DOWN -> NavDir.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> NavDir.LEFT; else -> NavDir.RIGHT
                }
                // grabbed knob: auto-repeat turns it; grid / cursor: one step per press (repeats too, past the first)
                if (!Nav.dpad(dir) && Nav.cur() == null) land()
            }
            // A: press / hold / grab a knob / enter a grid (see Nav.aDown)
            KeyEvent.KEYCODE_BUTTON_A -> if (first) Nav.aDown() else if (up) Nav.aUp()
            // B: let go of a knob / leave a grid → close the top sheet → leave NAV.
            KeyEvent.KEYCODE_BUTTON_B -> if (first) {
                if (!Nav.back() && !Nav.closeTop() && !backOverlay()) setNav(false)
            }
            // L1 hold: note repeat while you play pads / keys with A. L1 tap: jump to the top screen
            // (on release, and only if nothing repeated). R1: jump to the bottom screen.
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (first) { l1DownAt = e.eventTime; state.groove.repeatUsed = false; state.groove.setRepeatHeld(true) }
                else if (up) {
                    state.groove.setRepeatHeld(false)
                    if (!state.groove.repeatUsed && e.eventTime - l1DownAt < 400) {
                        Nav.back(); Nav.back()
                        Nav.land(0, androidx.compose.ui.geometry.Offset(0f, Nav.screenSize[0].height))
                    }
                }
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> if (first) {
                Nav.back(); Nav.back()
                Nav.land(1, androidx.compose.ui.geometry.Offset.Zero)
            }
            // L2 / R2: previous / next top view (SAMPLE · SYNTH · DRUMS · STEPS · MIXER · VISUAL).
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> if (first) {
                state.selectTop(cycle(TopView.entries, state.topView.value, if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R2) 1 else -1))
                if (navScreen.intValue == 0) land()
            }
            // X: reset the grabbed knob / the grid's second action (latch); otherwise X / Y = previous / next deck.
            KeyEvent.KEYCODE_BUTTON_X -> if (first && !Nav.x()) switchDeck(-1)
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) switchDeck(+1)
            KeyEvent.KEYCODE_BUTTON_START -> if (first) state.groove.togglePlay()
            else -> Unit
        }
        return true
    }

    private fun switchDeck(d: Int) {
        Nav.back(); Nav.back()
        state.selectDeck(cycle(DECKS, state.deck.value, d))
        if (navScreen.intValue == 1) land()
    }

    // Left stick in NAV: moves the cursor like the D-pad, with auto-repeat. Right stick: turns the focused control.
    private var stickDir = 0
    private var stickNext = 0L
    private fun navSticks(dt: Float) {
        val x = axes[0]; val y = axes[1]
        val dir = when {
            kotlin.math.max(abs(x), abs(y)) < 0.55f -> 0
            abs(x) > abs(y) -> if (x > 0) 4 else 3
            else -> if (y > 0) 2 else 1
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (dir != 0 && (dir != stickDir || now >= stickNext)) {
            if (!Nav.dpad(when (dir) { 1 -> NavDir.UP; 2 -> NavDir.DOWN; 3 -> NavDir.LEFT; else -> NavDir.RIGHT }) && Nav.cur() == null) land()
            stickNext = now + if (dir != stickDir) 280L else 120L
        }
        stickDir = dir
        if (!Nav.canAdjust()) return
        val d = (axes[2] - axes[3])                       // right stick: right / up = more (also while grabbed)
        if (d != 0f) Nav.adjust(d * abs(d) * dt * 0.8f)
    }

    /** SELECT is held (the on-screen control map shows after a short hold). */
    val shiftHeld = androidx.compose.runtime.mutableStateOf(false)
    private var shiftUsed = false             // a combo fired, so releasing SELECT doesn't toggle record
    private var shiftDownAt = 0L

    private fun <T> cycle(values: List<T>, current: T, d: Int): T =
        values[(values.indexOf(current).coerceAtLeast(0) + d + values.size) % values.size]

    /** SHIFT combos. Returns true when the key was a navigation key (consumed either way). */
    private fun shiftKey(e: KeyEvent, down: Boolean, first: Boolean): Boolean {
        val g = state.groove
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) {
                val v = cycle(TopView.entries, state.topView.value, if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                state.selectTop(v)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (first)
                state.selectDeck(cycle(DECKS, state.deck.value, if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1))
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> if (first) state.stepTrack(if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> if (down) {
                g.nudgeBpm(if (e.keyCode == KeyEvent.KEYCODE_BUTTON_R2) 1f else -1f)
                com.thorbox.app.ui.Info.toast("TEMPO", String.format(java.util.Locale.US, "%.1f BPM", g.groove.value.bpm))
            }
            KeyEvent.KEYCODE_BUTTON_A -> if (first) setNav(!navMode.value)
            KeyEvent.KEYCODE_BUTTON_B -> if (first) state.undoLast()
            KeyEvent.KEYCODE_BUTTON_X -> if (first) g.tapTempo(android.os.SystemClock.uptimeMillis())
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.toggleMetronome()
            KeyEvent.KEYCODE_BUTTON_START -> if (first) {
                // PLAY START: from bar 1 whether or not it's running (play always starts at the top)
                if (g.playing.value) g.togglePlay()
                g.togglePlay()
                com.thorbox.app.ui.Info.toast("PLAY", "from bar 1")
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first) state.cycleTheme()
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) state.showDiagnostics.value = !state.showDiagnostics.value
            else -> return false
        }
        if (down) shiftUsed = true
        return true
    }

    /** B: step back out of whatever is layered on top (a sheet first, then the visualizer). Never quits the app mid-set. */
    private fun back() { if (!Nav.closeTop()) backOverlay() }

    /** Layers that aren't sheets: the pinned control map, the visualizer. */
    private fun backOverlay(): Boolean = when {
        state.showControllerMap.value -> { state.showControllerMap.value = false; true }
        state.groove.heldStep.intValue >= 0 -> { state.groove.heldStep.intValue = -1; true }
        state.topView.value == TopView.VIZ && !navMode.value -> { state.exitViz(); true }
        else -> false
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
        }
        val synth = playContext(state) == PlayCtx.SYNTH
        val g = state.groove

        // SELECT = SHIFT (tap alone = record, on release)
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (first) { shiftHeld.value = true; shiftUsed = false; shiftDownAt = e.eventTime }
            else if (up) {
                shiftHeld.value = false
                // only a real tap records: holding to read the cheat sheet and letting go must not
                if (!shiftUsed && e.eventTime - shiftDownAt < TAP_MS) g.toggleRecord()
            }
            return true
        }
        // Only key-downs go through SHIFT, so a key that was already held (a pad, L1 repeat) still
        // gets its release when SELECT is pressed in between.
        if (shiftHeld.value && down && shiftKey(e, down, first)) return true
        // a punch-in held from the FX deck always lets go with its button, even if the deck or NAV changed meanwhile
        if (up && fxHeld >= 0 && e.keyCode == fxHeldBy) { state.groove.fxUp(fxHeld); fxHeld = -1; return true }
        if (navMode.value) return navKey(e, first, up)

        // Global keys
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_START -> { if (first) g.togglePlay(); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { if (first) back(); return true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (first) g.setRepeatHeld(true) else if (up) g.setRepeatHeld(false); return true }
        }

        val ctx = playContext(state)
        if (ctx == PlayCtx.FX) return fxKey(e, first, up)

        if (ctx == PlayCtx.DRUM) {
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
                KeyEvent.KEYCODE_BUTTON_X -> if (first) g.diceNotes(g.drumT)
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.undo(track)
                KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first) dc.stepKit(+1)
                KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) dc.diceKit()
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> Unit
                else -> return false
            }
            return true
        }

        if (ctx == PlayCtx.SEQ) {
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
                KeyEvent.KEYCODE_BUTTON_X -> if (first) g.diceNotes(tr)
                KeyEvent.KEYCODE_BUTTON_Y -> if (first) g.undo(tr)
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit
                else -> return false
            }
            return true
        }

        if (ctx == PlayCtx.MIX) {
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
                    if (sel == master) g.setMaster(g.masterDb.floatValue + d) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + d) }
                }
                KeyEvent.KEYCODE_BUTTON_A -> if (first && sel < master) g.setChannel(sel) { it.copy(mute = !it.mute) }
                KeyEvent.KEYCODE_BUTTON_X -> if (first && sel < master) state.selectTrack(sel)            // open the selected track
                KeyEvent.KEYCODE_BUTTON_Y -> if (first && sel < master) g.setChannel(sel) { it.copy(solo = !it.solo) }
                KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
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
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> Unit
            // Stick clicks: synth L3 = next preset, R3 = DICE a new sound; sampler L3 = PAD EDIT, R3 = stop the sound.
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (first) { if (synth) state.stepSynthPreset(+1) else state.showPadEdit.value = !state.showPadEdit.value }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) { if (synth) state.diceSynth() else state.sampler.stopAll() }
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
            // In NAV and with SELECT held the triggers are buttons (view / tempo): no new punch-ins then,
            // but one already engaged still follows the trigger and releases with it.
            val engage = !navMode.value && !shiftHeld.value
            analogPunch(com.thorbox.engine.NativeEngine.Fx.FILTER, lastL2, l2, engage) { p -> 0.5f - 0.5f * p }   // deeper low-pass
            analogPunch(com.thorbox.engine.NativeEngine.Fx.LOOPER, lastR2, r2, engage) { p -> 0.15f + 0.85f * p } // shorter slices
            lastL2 = l2; lastR2 = r2
            state.lastInput.value = "L2 %.2f  R2 %.2f".format(l2, r2)
        }
        if (!navMode.value && playContext(state) == PlayCtx.SAMPLER) flicks()
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
    private val punching = BooleanArray(com.thorbox.engine.NativeEngine.Fx.COUNT)
    private inline fun analogPunch(id: Int, before: Float, now: Float, engage: Boolean, x: (Float) -> Float) {
        val g = state.groove
        when {
            before < ENGAGE && now >= ENGAGE -> if (engage) { punching[id] = true; g.fxDown(id, x(now), 0.5f) }
            before >= ENGAGE && now >= ENGAGE -> if (punching[id]) g.fxMove(id, x(now), 0.5f)
            before >= ENGAGE && now < ENGAGE -> if (punching[id]) { punching[id] = false; g.fxUp(id) }
        }
    }

    /** Called once per frame while the synth view is up. Sticks act like relative encoders:
     *  full deflection sweeps a whole knob in about 1.2 s, and the curve is squared for fine control near centre. */
    fun applySticks(dt: Float) {
        if (dt <= 0f) return
        if (navMode.value) { navSticks(dt); return }
        if (playContext(state) == PlayCtx.FX) { fxSticks(dt); return }
        if (state.topView.value == TopView.MIX) {
            val g = state.groove; val sel = g.mixSelected.intValue
            val vol = -axes[1] * abs(axes[1]) * dt * 30f            // dB per second at full tilt
            val pan = axes[2] * abs(axes[2]) * dt * 1.5f
            val master = com.thorbox.app.ui.MASTER_STRIP
            if (vol != 0f) { if (sel == master) g.setMaster(g.masterDb.floatValue + vol) else g.setChannel(sel) { it.copy(volumeDb = it.volumeDb + vol) } }
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

    // ================= FX deck from the buttons =================
    // D-pad picks a tile on the 3 × 4 grid, A / R1 held = punch it in, X = latch, Y = ALL DRY.
    // Sticks: with a punch held they sweep its X/Y; otherwise they steer every latched effect at once.
    private val fxXY = FloatArray(com.thorbox.engine.NativeEngine.Fx.COUNT * 2) { 0.5f }
    private var fxHeld = -1
    private var fxHeldBy = 0
    private val latchXY = floatArrayOf(0.5f, 0.5f)

    private fun fxKey(e: KeyEvent, first: Boolean, up: Boolean): Boolean {
        val g = state.groove
        val grid = com.thorbox.app.ui.FX_GRID
        val cols = 3; val rows = grid.size / cols
        val k = state.fxCursor.intValue.coerceIn(0, grid.size - 1)
        val id = grid[k]
        fun moveTo(n: Int) {
            state.fxCursor.intValue = n; state.fxCursorShown.value = true
            com.thorbox.app.groove.FX_INFO[grid[n]]?.let { com.thorbox.app.ui.Info.toast(it.name, "↔ ${it.xLabel}  ↕ ${it.yLabel}") }
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (e.action == KeyEvent.ACTION_DOWN) moveTo(k / cols * cols + (k % cols + cols - 1) % cols)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (e.action == KeyEvent.ACTION_DOWN) moveTo(k / cols * cols + (k % cols + 1) % cols)
            KeyEvent.KEYCODE_DPAD_UP -> if (e.action == KeyEvent.ACTION_DOWN) moveTo(((k / cols + rows - 1) % rows) * cols + k % cols)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (e.action == KeyEvent.ACTION_DOWN) moveTo(((k / cols + 1) % rows) * cols + k % cols)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1 -> when {
                first && fxHeld < 0 -> { fxHeld = id; fxHeldBy = e.keyCode; state.fxCursorShown.value = true; g.fxDown(id, fxXY[2 * id], fxXY[2 * id + 1]) }
                up && fxHeld >= 0 && e.keyCode == fxHeldBy -> { g.fxUp(fxHeld); fxHeld = -1 }
            }
            KeyEvent.KEYCODE_BUTTON_X -> if (first) { g.toggleLatch(id, fxXY[2 * id], fxXY[2 * id + 1]); state.fxCursorShown.value = true }
            KeyEvent.KEYCODE_BUTTON_Y -> if (first) { if (fxHeld >= 0) fxHeld = -1; g.stopAll() }
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> Unit
            else -> return false
        }
        return true
    }

    private fun fxSticks(dt: Float) {
        val g = state.groove
        val dx = (axes[0] + axes[2]).coerceIn(-1f, 1f)
        val dy = -(axes[1] + axes[3]).coerceIn(-1f, 1f)
        if (dx == 0f && dy == 0f) return
        val rate = dt * 0.9f                                  // full tilt crosses the pad in ~1.1 s
        val h = fxHeld
        if (h >= 0) {
            fxXY[2 * h] = (fxXY[2 * h] + dx * abs(dx) * rate).coerceIn(0f, 1f)
            fxXY[2 * h + 1] = (fxXY[2 * h + 1] + dy * abs(dy) * rate).coerceIn(0f, 1f)
            g.fxMove(h, fxXY[2 * h], fxXY[2 * h + 1])
        } else if (g.fxLatched.intValue != 0) {
            latchXY[0] = (latchXY[0] + dx * abs(dx) * rate).coerceIn(0f, 1f)
            latchXY[1] = (latchXY[1] + dy * abs(dy) * rate).coerceIn(0f, 1f)
            g.fxMoveLatched(latchXY[0], latchXY[1])
        }
    }

    companion object {
        private const val DEADZONE = 0.15f; private const val ENGAGE = 0.08f
        private val DECKS = listOf(com.thorbox.model.Deck.PADS, com.thorbox.model.Deck.KEYS, com.thorbox.model.Deck.DRUMS, com.thorbox.model.Deck.STEP, com.thorbox.model.Deck.FX)
        /** SELECT released sooner than this (and with no combo) is a tap = REC. Matches the cheat sheet's delay. */
        const val TAP_MS = 280L
    }
}
