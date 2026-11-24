// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navModal
import com.thorbox.app.input.navTarget

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.STEP_TICKS
import com.thorbox.app.groove.Steps
import com.thorbox.app.synth.SynthId
import com.thorbox.app.synth.SynthParams
import com.thorbox.engine.NativeEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue

private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun noteName(n: Int) = "${NAMES[n % 12]}${n / 12 - 1}"
private val audition = Handler(Looper.getMainLooper())

/** Short preview of a note you just drew (so the grid is never silent while you write). */
private fun previewNote(track: Int, n: Int) {
    NativeEngine.synthNoteOn(track, n, 0.75f)
    audition.postDelayed({ NativeEngine.synthNoteOff(track, n) }, 180)
}

/**
 * Synth NOTE GRID (piano roll). With scale LOCK on, rows fold to in-key notes only (two octaves),
 * so every cell sounds right. Gestures:
 *   tap empty = add a 1-step note (it plays) · tap a note = remove it
 *   drag a note sideways = change its length · drag it up/down = move its pitch
 * Chords: just stack notes on a step.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoteGrid(state: AppState, modifier: Modifier) {
    val g = state.groove
    val clip = g.clips.value[g.synthT]
    val rows = g.gridRows()
    val steps = Steps.count(clip.lengthTicks)
    // the bar on screen (it follows the playhead): read while drawing / handling a touch, not in composition
    fun first() = g.pageStart(g.synthT, 16)
    fun visible() = minOf(16, steps - first())
    val key = state.lockKey()
    val dims = remember { IntArray(2) }
    val gesture = remember { FloatArray(6) }     // downX, downY, mode (0 none, 1 resize, 2 move), tick, note, moved
    val slop = LocalViewConfiguration.current.touchSlop
    val tm = rememberTextMeasurer(cacheSize = 64)
    val label = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    val tel = state.telemetry
    val nameCol = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }   // note-name column
    val rowOf = remember(rows.contentHashCode()) { HashMap<Int, Int>(rows.size * 2).apply { rows.forEachIndexed { i, n -> put(n, i) } } }
    // NAV: A enters the roll, the D-pad walks steps (◀▶) and notes (▲▼), A adds / removes a note, X makes it a step longer
    val navCell = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val rollGrid = com.thorbox.app.input.NavGrid(16, rows.size,
        get = { navCell.intValue.coerceIn(0, rows.size * 16 - 1) }, set = { navCell.intValue = it },
        down = { c ->
            val step = first() + c % 16; val note = rows[(c / 16).coerceIn(0, rows.size - 1)]
            if (c % 16 < visible()) {
                val adding = clip.notes.none { it.note == note && it.tick == step * STEP_TICKS }
                g.gridToggle(step, note); if (adding) previewNote(g.synthT, note)
            }
        },
        alt = { c ->
            val t = (first() + c % 16) * STEP_TICKS; val note = rows[(c / 16).coerceIn(0, rows.size - 1)]
            clip.notes.firstOrNull { it.note == note && t >= it.tick && t < it.tick + maxOf(it.lengthTicks, STEP_TICKS / 2) }
                ?.let { g.gridResize(it.tick, it.note, it.lengthTicks + STEP_TICKS) }
        },
        describe = { c -> "${noteName(rows[(c / 16).coerceIn(0, rows.size - 1)])} · step ${first() + c % 16 + 1}" },
        verb = "note on/off", altVerb = "longer", tag = "pianoroll")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HwButton("OCT−", Modifier.fillMaxHeight(), minHeight = 36.dp, fontSize = 11) { g.gridOctave.intValue = (g.gridOctave.intValue - 1).coerceIn(-3, 3) }
            HwButton("OCT+", Modifier.fillMaxHeight(), minHeight = 36.dp, fontSize = 11) { g.gridOctave.intValue = (g.gridOctave.intValue + 1).coerceIn(-3, 3) }
            Box(Modifier.width(6.dp))
            BarNav(state, g.synthT, 16, Modifier.weight(1f).fillMaxHeight(), height = 36.dp)
            Box(Modifier.width(6.dp))
            Text(if (state.keyLock.value) "${key.label().uppercase()} · in key only" else "chromatic", color = Tb.Muted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically))
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().bezel()
                .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
                .navTarget("PIANO ROLL", grid = rollGrid, corner = 14f)
                .pointerInteropFilter { ev ->
                    val cw = (dims[0] - nameCol) / 16f
                    val rh = dims[1].coerceAtLeast(1) / rows.size.toFloat()
                    fun stepAt(x: Float) = (first() + ((x - nameCol) / cw).toInt().coerceIn(0, visible() - 1))
                    fun rowAt(y: Float) = (y / rh).toInt().coerceIn(0, rows.size - 1)
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            gesture[0] = ev.x; gesture[1] = ev.y; gesture[2] = 0f; gesture[5] = 0f
                            val step = stepAt(ev.x); val note = rows[rowAt(ev.y)]
                            val t = step * STEP_TICKS
                            val hit = clip.notes.firstOrNull { it.note == note && t >= it.tick && t < it.tick + maxOf(it.lengthTicks, STEP_TICKS / 2) }
                            if (hit != null) { gesture[3] = hit.tick.toFloat(); gesture[4] = hit.note.toFloat(); gesture[2] = -1f }   // armed: decide on move
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = ev.x - gesture[0]; val dy = ev.y - gesture[1]
                            if (gesture[2] == -1f && (abs(dx) > slop || abs(dy) > slop)) gesture[2] = if (abs(dx) >= abs(dy)) 1f else 2f
                            if (gesture[2] == 1f) {
                                val endStep = ((ev.x - nameCol) / cw).let { first() + it }
                                val len = ((endStep * STEP_TICKS) - gesture[3]).roundToInt()
                                g.gridResize(gesture[3].toInt(), gesture[4].toInt(), (len / (STEP_TICKS / 4)) * (STEP_TICKS / 4))
                                gesture[5] = 1f
                            } else if (gesture[2] == 2f) {
                                val newNote = rows[rowAt(ev.y)]
                                if (newNote != gesture[4].toInt()) { g.gridMove(gesture[3].toInt(), gesture[4].toInt(), newNote); gesture[4] = newNote.toFloat(); previewNote(g.synthT, newNote) }
                                gesture[5] = 1f
                            }
                        }
                        MotionEvent.ACTION_UP -> if (gesture[5] == 0f && ev.x > nameCol) {
                            val step = stepAt(ev.x); val note = rows[rowAt(ev.y)]
                            val adding = gesture[2] == 0f
                            g.gridToggle(step, note)
                            if (adding) previewNote(g.synthT, note)
                        }
                    }
                    true
                }
        ) {
            tel.frame.longValue
            val f0 = first()
            val cw = (size.width - nameCol) / 16f
            val rh = size.height / rows.size
            // rows: tonic rows tinted, beats marked
            rows.forEachIndexed { r, n ->
                val tonic = state.keyLock.value && Math.floorMod(n - key.root.ordinal, 12) == 0
                drawRect(if (tonic) Tb.EncOrange.copy(alpha = 0.12f) else if (r % 2 == 0) Tb.Panel.copy(alpha = 0.6f) else Tb.Black.copy(alpha = 0f),
                    Offset(nameCol, r * rh), Size(size.width - nameCol, rh))
                // 7 dp in: clear of the frame's rounded corners (the top and bottom names were clipped)
                if (rh > 11.dp.toPx()) drawTextSafe(tm, noteName(n), Offset(7.dp.toPx(), r * rh + (rh - 11.dp.toPx()) / 2), if (tonic) label.copy(color = Tb.EncOrange) else label)
            }
            for (s in 0..16) {
                val x = nameCol + s * cw
                drawLine(if (s % 4 == 0) Tb.Line else Tb.Raised, Offset(x, 0f), Offset(x, size.height), if (s % 4 == 0) 1.5f else 1f)
            }
            // notes
            for (e in clip.notes) {
                val r = rowOf[e.note] ?: continue
                val s0 = e.tick.toFloat() / STEP_TICKS - f0
                val s1 = s0 + e.lengthTicks.toFloat() / STEP_TICKS
                if (s1 <= 0f || s0 >= 16f) continue
                val x0 = nameCol + maxOf(0f, s0) * cw + 1.5f; val x1 = nameCol + minOf(16f, s1) * cw - 1.5f
                val a = 0.45f + 0.55f * e.velocity
                drawRoundRect(Tb.EncOrange.copy(alpha = a), Offset(x0, r * rh + 1.5f), Size(maxOf(4f, x1 - x0), rh - 3f), CornerRadius(4.dp.toPx()))
                if (e.ratchet > 1 || e.condEvery > 1 || e.locks.isNotEmpty())
                    drawCircle(Tb.Text, 2.5f.dp.toPx(), Offset(x0 + 6.dp.toPx(), r * rh + rh / 2))     // has step components
            }
            if (com.thorbox.app.input.Nav.enteredTag() == "pianoroll") {      // NAV cell cursor
                val c = navCell.intValue
                drawRect(Tb.Text, Offset(nameCol + (c % 16) * cw, (c / 16) * rh), Size(cw, rh), style = Stroke(2.dp.toPx()))
            }
            // playhead
            val pos = g.stepPosition(g.synthT)
            if (pos in f0 until f0 + 16) {
                val x = nameCol + (pos - f0) * cw
                drawRect(Tb.Text.copy(alpha = 0.10f), Offset(x, 0f), Size(cw, size.height))
            }
        }
    }
}


/**
 * Deck 3: STEP. Top: 16 in-key notes. Bottom: 16 steps of the bar on screen.
 *   play a key, tap an empty step = that note goes in (the last thing you played: a chord too)
 *   hold keys, press a step = those notes go in · hold a step, press keys = each adds / removes its note
 *   hold a step, press a later step = the note lasts to there (again: it ties over into the next)
 *   tap a lit step = clear it · hold a lit step = its notes light on the keys, its components open on
 *   the top screen (ratchet, condition, nudge, velocity, p-locks); let go = copied, tap an empty step to paste
 *   STEP REC = play keys and they fill the steps one after another (‹ › to move)
 * Keys with no step held play live (and record while REC is on).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StepDeck(state: AppState, modifier: Modifier) {
    val g = state.groove
    val clip = g.clips.value[g.synthT]
    val steps = Steps.count(clip.lengthTicks)
    fun first() = g.pageStart(g.synthT, 16)          // the bar on screen (follows the playhead)
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 64)
    val small = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 10.sp, fontWeight = FontWeight.Black) }
    val keyDims = remember { IntArray(2) }
    val stepDims = remember { IntArray(2) }
    val keyFingers = remember { HashMap<Int, IntArray>() }
    val byStep = remember(clip) { Steps.byStep(clip.notes, clip.lengthTicks) }      // once per edit, not per frame
    // steps a longer note is still sounding over (drawn as its tail)
    val tied = remember(clip) {
        val t = BooleanArray(steps)
        for (e in clip.notes) {
            val s0 = Steps.stepOf(e, clip.lengthTicks)
            val last = (e.tick + e.lengthTicks - 1) / STEP_TICKS
            for (k in s0 + 1..minOf(last, steps - 1)) t[k] = true
        }
        t
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (g.stepRec.value) g.toggleStepRec() } }
    val keyNotes = remember(state.keyOctave.intValue, state.keyLock.value, state.chordMode.value, state.lockKey()) { IntArray(16) { state.notesForKey(it)[0] } }
    val stepFingers = remember { HashMap<Int, Pair<Int, Long>>() }
    // NAV grids: the keys (2 × 8, low row at the bottom) and the steps (2 × 8 for this bar)
    val keyCur = remember { androidx.compose.runtime.mutableIntStateOf(8) }
    val navHeld = remember { IntArray(16) }
    val stepDownAt = remember { LongArray(1) }
    fun keyOf(c: Int) = (1 - c / 8) * 8 + c % 8
    val keysGrid = com.thorbox.app.input.NavGrid(8, 2,
        get = { keyCur.intValue }, set = { keyCur.intValue = it },
        down = { c ->
            val notes = state.notesForKey(keyOf(c))
            if (g.heldStep.intValue >= 0) notes.forEach { if (g.keyIntoHeldStep(it, 0.85f)) previewNote(g.synthT, it) }
            else { notes.copyInto(navHeld, 0, 0, minOf(notes.size, 15)); navHeld[15] = notes.size; state.keyDown(notes, 0.85f) }
        },
        up = { _ -> if (navHeld[15] > 0) { state.keyUp(navHeld.copyOf(navHeld[15])); navHeld[15] = 0 } },
        describe = { c -> noteName(keyNotes[keyOf(c)]) }, tag = "stepkeys")
    val stepsGrid = com.thorbox.app.input.NavGrid(8, 2,
        get = { (g.cursor.intValue - first()).coerceIn(0, 15) },
        set = { c -> g.cursor.intValue = first() + c },
        down = { c -> val st = first() + c; if (st < steps) { stepDownAt[0] = android.os.SystemClock.uptimeMillis(); g.holdStep(st) } },
        up = { c -> val st = first() + c; if (st < steps) g.releaseStep(st, tapped = android.os.SystemClock.uptimeMillis() - stepDownAt[0] < 350) },
        describe = { c -> "step ${first() + c + 1}" }, verb = "toggle · hold edit", tag = "stepdeck", slide = false)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HwButton("OCT−", Modifier.fillMaxHeight(), minHeight = 40.dp, fontSize = 11) { state.shiftOctave(-1) }
            HwButton("OCT+", Modifier.fillMaxHeight(), minHeight = 40.dp, fontSize = 11) { state.shiftOctave(+1) }
            Box(Modifier.width(6.dp))
            PatternDice(state, g.synthT, height = 40.dp, fontSize = 12)
            Box(Modifier.width(6.dp))
            HwButton("STEP REC", Modifier.fillMaxHeight(), minHeight = 40.dp, fontSize = 11, hPad = 8.dp, active = g.stepRec.value, accent = Tb.Warn) { g.toggleStepRec() }
            if (g.stepRec.value) {
                HwButton("‹", Modifier.width(40.dp).fillMaxHeight(), minHeight = 40.dp, fontSize = 16) { g.stepRecMove(-1) }
                HwButton("›", Modifier.width(40.dp).fillMaxHeight(), minHeight = 40.dp, fontSize = 16) { g.stepRecMove(+1) }
            }
            if (g.stepCopy.value != null) HwButton("PASTE ✕", Modifier.fillMaxHeight(), minHeight = 40.dp, fontSize = 11, hPad = 8.dp, active = true) { g.stepCopy.value = null }
            Box(Modifier.width(4.dp))
            BarNav(state, g.synthT, 16, Modifier.weight(1f).fillMaxHeight(), height = 40.dp, lengthControls = false)
        }
        // ---- keys: 2 × 8 in-key notes ----
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { keyDims[0] = it.width; keyDims[1] = it.height }
                .navTarget("KEYS", grid = keysGrid, corner = 8f)
                .pointerInteropFilter { ev ->
                    fun keyAt(x: Float, y: Float): Int {
                        val c = (x / keyDims[0].coerceAtLeast(1) * 8).toInt().coerceIn(0, 7)
                        val r = (y / keyDims[1].coerceAtLeast(1) * 2).toInt().coerceIn(0, 1)
                        return (1 - r) * 8 + c
                    }
                    val i = ev.actionIndex
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val notes = state.notesForKey(keyAt(ev.getX(i), ev.getY(i)))
                            if (g.heldStep.intValue >= 0) notes.forEach { if (g.keyIntoHeldStep(it, 0.85f)) previewNote(g.synthT, it) }
                            else { keyFingers[ev.getPointerId(i)] = notes; state.keyDown(notes, 0.85f) }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> keyFingers.remove(ev.getPointerId(i))?.let(state::keyUp)
                        MotionEvent.ACTION_CANCEL -> { keyFingers.values.forEach(state::keyUp); keyFingers.clear() }
                    }
                    true
                }
        ) {
            tel.frame.longValue
            val cw = size.width / 8; val ch = size.height / 2; val gap = 3.dp.toPx(); val r = CornerRadius(8.dp.toPx())
            val key = state.lockKey()
            val hs = g.heldStep.intValue                       // holding a step: its notes light up
            val inStep = if (hs < 0) null else clip.notes.filter { Steps.stepOf(it, clip.lengthTicks) == hs }
            for (k in 0 until 16) {
                val n = keyNotes[k]
                val o = Offset((k % 8) * cw + gap, (1 - k / 8) * ch + gap); val s = Size(cw - 2 * gap, ch - 2 * gap)
                val tonic = state.keyLock.value && Math.floorMod(n - key.root.ordinal, 12) == 0
                val lit = inStep != null && inStep.any { it.note == n }
                drawRoundRect(if (lit) Tb.EncGreen else if (tel.isNoteOn(n)) Tb.EncOrange else if (tonic) Tb.EncOrange.copy(alpha = 0.35f) else Tb.Raised, o, s, r)
                drawTextSafe(tm, noteName(n), o + Offset(4.dp.toPx(), s.height - 15.dp.toPx()), small)
            }
            if (com.thorbox.app.input.Nav.enteredTag() == "stepkeys") {      // NAV cell cursor
                val c = keyCur.intValue
                drawRoundRect(Tb.Text, Offset((c % 8) * cw + gap, (c / 8) * ch + gap), Size(cw - 2 * gap, ch - 2 * gap), r, style = Stroke(2.5f.dp.toPx()))
            }
        }
        // ---- steps: 2 × 8 for the current bar ----
        Canvas(
            Modifier.height(96.dp).fillMaxWidth()
                .onSizeChanged { stepDims[0] = it.width; stepDims[1] = it.height }
                .navTarget("STEPS", grid = stepsGrid, corner = 6f)
                .pointerInteropFilter { ev ->
                    fun stepAt(x: Float, y: Float): Int {
                        val c = (x / stepDims[0].coerceAtLeast(1) * 8).toInt().coerceIn(0, 7)
                        val r = (y / stepDims[1].coerceAtLeast(1) * 2).toInt().coerceIn(0, 1)
                        return first() + r * 8 + c
                    }
                    val i = ev.actionIndex
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val s = stepAt(ev.getX(i), ev.getY(i))
                            if (s < steps) { stepFingers[ev.getPointerId(i)] = s to ev.eventTime; g.holdStep(s) }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> stepFingers.remove(ev.getPointerId(i))?.let { (s, t0) ->
                            g.releaseStep(s, tapped = ev.eventTime - t0 < 350)
                        }
                        MotionEvent.ACTION_CANCEL -> { stepFingers.values.forEach { (s, _) -> g.releaseStep(s, false, cancelled = true) }; stepFingers.clear() }
                    }
                    true
                }
        ) {
            tel.frame.longValue
            val cw = size.width / 8; val ch = size.height / 2; val gap = 3.dp.toPx(); val r = CornerRadius(6.dp.toPx())
            val play = g.stepPosition(g.synthT)
            val f0 = first()
            for (k in 0 until 16) {
                val s = f0 + k
                val o = Offset((k % 8) * cw + gap, (k / 8) * ch + gap); val sz = Size(cw - 2 * gap, ch - 2 * gap)
                val has = s < steps && byStep.getOrNull(s) != null
                val col = when { s >= steps -> Tb.Panel; has -> Tb.EncGreen; k % 4 == 0 -> Tb.Raised; else -> Tb.Panel }
                drawRoundRect(col, o, sz, r)
                if (!has && s < steps && tied[s])                     // a longer note still sounding here: its tail
                    drawRoundRect(Tb.EncGreen.copy(alpha = 0.45f), o + Offset(-2 * gap, sz.height * 0.35f), Size(sz.width + 2 * gap, sz.height * 0.3f), CornerRadius(3.dp.toPx()))
                if (g.stepRec.value && s == g.stepRecPos.intValue) drawRoundRect(Tb.Warn, o, sz, r, style = Stroke(3.dp.toPx()))
                if (s == play) drawRoundRect(Tb.Text, o, sz, r, style = Stroke(2.5f.dp.toPx()))
                if (s == g.heldStep.intValue) drawRoundRect(Tb.EncOrange, o, sz, r, style = Stroke(3.dp.toPx()))
                val e = byStep.getOrNull(s)
                if (e != null && (e.ratchet > 1 || e.condEvery > 1 || e.locks.isNotEmpty())) drawCircle(Tb.Black, 3.dp.toPx(), o + Offset(sz.width - 7.dp.toPx(), 7.dp.toPx()))
            }
            if (com.thorbox.app.input.Nav.enteredTag() == "stepdeck") {      // NAV cell cursor
                val k = (g.cursor.intValue - f0).coerceIn(0, 15)
                drawRoundRect(Tb.Text, Offset((k % 8) * cw + 1f, (k / 8) * ch + 1f), Size(cw - 2f, ch - 2f), r, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/**
 * Shown on the top screen while a step is held ("hold step, turn knob").
 * Row 1: step components. Row 2: parameter locks: turn to lock that sound to this step only,
 * double-tap to clear. Locks hold until the next step without them.
 */
@Composable
fun StepDetail(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val step = g.heldStep.intValue
    if (step < 0) return
    val evs = g.stepEvents(step)
    val e = evs.firstOrNull()
    Column(modifier.navModal { state.groove.heldStep.intValue = -1 }.glass(state, 16.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("STEP ${step + 1}", color = Tb.EncGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
            Text(if (evs.isEmpty()) "empty: play keys to write notes" else evs.joinToString(" + ") { noteName(it.note) },
                color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        if (e == null) return@Column
        Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepKnob("RATCHET", "×${e.ratchet}", (e.ratchet - 1) / 3f, Tb.EncBlue, Modifier.weight(1f)) { v -> g.editHeld { it.copy(ratchet = 1 + (v * 3).roundToInt()) } }
            val ci = g.conditions.indexOf(e.condEvery to e.condPhase).coerceAtLeast(0)
            StepKnob("CONDITION", g.conditionLabel(e.condEvery, e.condPhase), ci / (g.conditions.size - 1f), Tb.EncGreen, Modifier.weight(1f)) { v ->
                val (ev, ph) = g.conditions[(v * (g.conditions.size - 1)).roundToInt()]
                g.editHeld { it.copy(condEvery = ev, condPhase = ph) }
            }
            val nudge = e.tick - step * STEP_TICKS
            StepKnob("NUDGE", String.format(Locale.US, "%+d%%", (nudge * 100f / STEP_TICKS).roundToInt()), (nudge + 110) / 220f, Tb.EncWhite, Modifier.weight(1f)) { v ->
                val n = (v * 220 - 110).roundToInt()
                g.editHeld { it.copy(tick = (step * STEP_TICKS + n).coerceAtLeast(0)) }
            }
            StepKnob("VELOCITY", "${(e.velocity * 100).roundToInt()}", e.velocity, Tb.EncOrange, Modifier.weight(1f)) { v -> g.editHeld { it.copy(velocity = v.coerceIn(0.05f, 1f)) } }
        }
        Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockKnob(state, e, "CUTOFF", SynthId.CUTOFF, Tb.EncBlue, { SynthParams.nFromHz(it) }, { SynthParams.hzFromN(it) }, { SynthParams.fmtHz(it) }, Modifier.weight(1f))
            LockKnob(state, e, "RESO", SynthId.RESO, Tb.EncGreen, { it }, { it }, { "${(it * 100).roundToInt()}%" }, Modifier.weight(1f))
            LockKnob(state, e, "ENV AMT", SynthId.FENV_AMT, Tb.EncWhite, { (it + 1f) / 2f }, { it * 2f - 1f }, { String.format(Locale.US, "%+d%%", (it * 100).roundToInt()) }, Modifier.weight(1f))
            LockKnob(state, e, "DECAY", SynthId.AMP_D, Tb.EncOrange, { SynthParams.nFromMs(it) }, { SynthParams.msFromN(it) }, { SynthParams.fmtMs(it) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepKnob(label: String, display: String, value: Float, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onValue: (Float) -> Unit) {
    val acc = remember { FloatArray(1) }
    Encoder(label = label, display = display, value = value.coerceIn(0f, 1f), color = color, active = false,
        onDragStart = { acc[0] = value }, onDelta = { d -> acc[0] = (acc[0] + d).coerceIn(0f, 1f); onValue(acc[0]) },
        onDragEnd = {}, onReset = { onValue(0f) }, modifier = modifier)
}

@Composable
private fun LockKnob(
    state: AppState, e: com.thorbox.model.NoteEvent, label: String, param: Int, color: androidx.compose.ui.graphics.Color,
    toN: (Float) -> Float, fromN: (Float) -> Float, fmt: (Float) -> String, modifier: Modifier,
) {
    val g = state.groove
    val lock = e.locks.firstOrNull { it.param == param }
    // Unlocked knobs start from the patch's current value, so the first turn is a small change.
    val patch by state.patch.collectAsState()
    val base = remember(patch) { FloatArray(SynthId.COUNT).also { SynthParams.toEngine(patch, it) } }[param]
    val v = lock?.value ?: base
    val acc = remember { FloatArray(1) }
    Encoder(label = if (lock != null) "$label ●" else label, display = if (lock != null) fmt(v) else "—", value = toN(v).coerceIn(0f, 1f),
        color = if (lock != null) color else Tb.Dim, active = lock != null,
        onDragStart = { acc[0] = toN(v) }, onDelta = { d -> acc[0] = (acc[0] + d).coerceIn(0f, 1f); g.setLock(param, fromN(acc[0])) },
        onDragEnd = {}, onReset = { g.clearLock(param) }, modifier = modifier)
}
