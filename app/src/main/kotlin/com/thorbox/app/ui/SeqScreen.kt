// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.Lane
import com.thorbox.app.groove.Scales
import com.thorbox.app.groove.Steps
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * SEQ view: transport + groove bar, track/lane/length/scale row, the step grid, and the generator strip.
 * Step grid gestures (no modes, no menus):
 *   tap = toggle the step · drag vertically = set the current lane's value · drag across = paint values
 */
@Composable
fun SeqScreen(state: AppState) {
    val showAuto = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val g = state.groove
    val groove = g.groove.value
    val track = g.seqTrack.intValue
    val accent = kindColor(state.trackKind(track))

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- transport & groove ----
        Row(Modifier.fillMaxWidth().height(56.dp).glass(state, 12.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (LocalWide.current) SectionTag(state, Section.STEPS, Modifier.padding(start = 4.dp))
            HwButton(if (g.playing.value) "■" else "▶", Modifier.width(56.dp).fillMaxHeight(), active = g.playing.value, accent = Tb.Good, fontSize = 18) { g.togglePlay() }
            HwButton("●", Modifier.width(48.dp).fillMaxHeight(), active = g.recording.value, accent = Tb.Warn, fontSize = 18) { g.toggleRecord() }
            HwButton("CLK", Modifier.fillMaxHeight(), active = groove.metronome, accent = Tb.EncWhite, fontSize = 12) { g.toggleMetronome() }
            DragValue("BPM", String.format(Locale.US, "%.1f", groove.bpm), Tb.Tempo, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 4f, navFine = 1f,
                onDelta = { g.nudgeBpm(it) }, onReset = { g.setGroove(groove.copy(bpm = 90f)) })
            HwButton("TAP", Modifier.fillMaxHeight(), fontSize = 12) { g.tapTempo(android.os.SystemClock.uptimeMillis()) }
            DragValue("SWING", "${(groove.swing * 100).roundToInt()}%", Tb.Key, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 900f,
                onDelta = { g.setSwing(g.groove.value.swing + it) }, onReset = { g.setSwing(0.5f) })
            HwButton(if (groove.swing16) "1/16" else "1/8", Modifier.fillMaxHeight(), fontSize = 12) { g.toggleSwingGrid() }
            DragValue("QUANT", "${(groove.quantize * 100).roundToInt()}%", Tb.EncGreen, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                onDelta = { g.setQuantize(g.groove.value.quantize + it) }, onReset = { g.setQuantize(1f) })
        }

        // ---- track / lane / length / scale ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HwButton("AUTO ${g.auto.lanes.value.size}", Modifier.fillMaxHeight(), minHeight = 40.dp, fontSize = 12,
                active = g.auto.lanes.value.isNotEmpty(), accent = Tb.Key) { showAuto.value = true }
            // Track picker: tap to step through the tracks (each one keeps its own pattern).
            val tr = state.tracks.value[track]
            HwButton("${tr.name} ▸", Modifier.fillMaxHeight(), active = true, accent = kindColor(tr.kind), minHeight = 40.dp, fontSize = 12, hPad = 8.dp) {
                val order = state.tracks.value.indices.filter { state.trackKind(it) != com.thorbox.model.TrackKind.EMPTY }
                state.selectTrackForSteps(order[(order.indexOf(track) + 1) % order.size])
            }
            if (g.isPadTrack(track)) Text(if (tr.kind == com.thorbox.model.TrackKind.DRUMS) state.drumsFor(track).kit.value.voices[g.selectedPad(track)].name
                else "PAD ${g.selectedPad(track) + 1}", color = Tb.Muted, fontWeight = FontWeight.Black, fontSize = 11.sp,
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
            if (!g.isPadTrack(track)) {
                val grid = g.synthEdit.value == com.thorbox.app.groove.GrooveController.SynthEdit.GRID
                HwButton(if (grid) "GRID" else "LANES", Modifier.fillMaxHeight(), active = true, accent = Tb.EncGreen, minHeight = 40.dp, fontSize = 12, hPad = 8.dp) {
                    g.synthEdit.value = if (grid) com.thorbox.app.groove.GrooveController.SynthEdit.LANES else com.thorbox.app.groove.GrooveController.SynthEdit.GRID
                }
            }
            Box(Modifier.width(2.dp))
            Lane.entries.forEach { l ->
                if (l == Lane.NOTE && g.isPadTrack(track)) return@forEach
                if (!g.isPadTrack(track) && g.synthEdit.value == com.thorbox.app.groove.GrooveController.SynthEdit.GRID) return@forEach
                HwButton(l.name, Modifier.fillMaxHeight(), active = g.lane.value == l, accent = Tb.EncBlue, minHeight = 40.dp, fontSize = 12, hPad = 8.dp) { g.lane.value = l }
            }
            Box(Modifier.width(2.dp))
            val steps = Steps.count(g.clips.value[track].lengthTicks)
            listOf(8, 16, 32).forEach { n -> HwButton("$n", Modifier.fillMaxHeight(), active = steps == n, accent = Tb.EncWhite, minHeight = 40.dp, fontSize = 12, hPad = 8.dp) { g.setLength(track, n) } }
            Box(Modifier.weight(1f))
            ScaleChips(state)
        }

        // ---- steps (drums, synth lanes) or the note grid (synth) ----
        if (!g.isPadTrack(track) && g.synthEdit.value == com.thorbox.app.groove.GrooveController.SynthEdit.GRID) NoteGrid(state, Modifier.weight(1f).fillMaxWidth())
        else StepGrid(state, track, accent, Modifier.weight(1f).fillMaxWidth())

        // ---- generator ----
        val gp = g.gen.value
        Row(Modifier.fillMaxWidth().height(56.dp).glass(state, 12.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PatternDice(state, track, height = 48.dp, fontSize = 13)
            HwButton("MUTATE", Modifier.fillMaxHeight(), fontSize = 12) { g.mutate(track) }
            HwButton("CLEAR", Modifier.fillMaxHeight(), fontSize = 12) { g.clear(track) }
            DragValue("DENSITY", "${(gp.density * 100).roundToInt()}%", accent, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                onDelta = { g.gen.value = g.gen.value.copy(density = (g.gen.value.density + it).coerceIn(0.05f, 1f)) })
            if (!g.isPadTrack(track)) {
                HwButton("${gp.rangeOctaves} OCT", Modifier.fillMaxHeight(), fontSize = 12) { g.gen.value = gp.copy(rangeOctaves = gp.rangeOctaves % 3 + 1) }
                HwButton("FIX SCALE", Modifier.fillMaxHeight(), fontSize = 12) { g.snapToScale() }
            }
            HwButton("CHANCE", Modifier.fillMaxHeight(), active = gp.probSpread, accent = Tb.EncGreen, fontSize = 12) { g.gen.value = gp.copy(probSpread = !gp.probSpread) }
            Text("#${g.seed.intValue % 10000}", color = Tb.Dim, fontFamily = Tb.Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
        }
    }
    if (showAuto.value) AutoSheet(state, onDone = { showAuto.value = false }, modifier = Modifier.padding(6.dp))
    }
}

/** Key + scale: FOLLOW uses the loaded sample's detected key; otherwise pick root and mode. One tap each. */
@Composable
private fun ScaleChips(state: AppState) {
    val g = state.groove
    val s = g.scale.value
    val sample by state.sample.collectAsState()          // subscribe, so the chips update when a new sample loads
    val k = state.lockKey()
    val follow = s.followSample && sample?.key != null
    HwButton("AUTO", Modifier.fillMaxHeight(), active = s.followSample, accent = Tb.Key, minHeight = 40.dp, fontSize = 11, hPad = 8.dp) {
        g.setScale(s.copy(followSample = !s.followSample, root = k.root, mode = k.mode))
    }
    HwButton(NOTE_NAMES[k.root.ordinal], Modifier.fillMaxHeight(), enabled = !follow, minHeight = 40.dp, fontSize = 13, hPad = 8.dp) {
        g.setScale(s.copy(root = PitchClass.entries[(s.root.ordinal + 1) % 12], followSample = false))
    }
    HwButton(Scales.label(k.mode), Modifier.fillMaxHeight(), enabled = !follow, minHeight = 40.dp, fontSize = 12, hPad = 8.dp) {
        g.setScale(s.copy(mode = ScaleMode.entries[(s.mode.ordinal + 1) % ScaleMode.entries.size], followSample = false))
    }
    HwButton(if (state.keyLock.value) "LOCK" else "FREE", Modifier.fillMaxHeight(), active = state.keyLock.value, accent = Tb.Key, minHeight = 40.dp, fontSize = 11, hPad = 8.dp) {
        state.releaseAllKeys(); state.keyLock.value = !state.keyLock.value
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StepGrid(state: AppState, track: Int, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val g = state.groove
    val clip = g.clips.value[track]
    val steps = Steps.count(clip.lengthTicks)
    val byStep = Steps.byStep(g.visibleNotes(track), clip.lengthTicks)
    val lane = g.lane.value
    val laneVals = remember(clip, lane, state.sampler.selected.intValue, state.lockKey(), state.keyLock.value) {
        FloatArray(byStep.size) { i -> byStep[i]?.let { g.laneValue(track, it, lane) } ?: 0f }
    }
    val dims = remember { IntArray(2) }
    val slop = LocalViewConfiguration.current.touchSlop
    val gesture = remember { FloatArray(3) }          // downX, downY, dragging(0/1)
    val tm = rememberTextMeasurer(cacheSize = 64)
    val noteStyle = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 10.sp, fontWeight = FontWeight.Black) }
    val numStyle = remember(Tb.palette) { TextStyle(color = Tb.Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    val tel = state.telemetry

    fun stepAt(x: Float) = (x / dims[0].coerceAtLeast(1) * steps).toInt().coerceIn(0, steps - 1)
    fun valueAt(y: Float) = (1f - y / dims[1].coerceAtLeast(1)).coerceIn(0f, 1f)

    Canvas(
        modifier.bezel()
            .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { gesture[0] = ev.x; gesture[1] = ev.y; gesture[2] = 0f }
                    MotionEvent.ACTION_MOVE -> {
                        if (gesture[2] == 0f && abs(ev.y - gesture[1]) > slop) gesture[2] = 1f
                        if (gesture[2] == 1f) {
                            val s = stepAt(ev.x)
                            if (byStepHas(state, track, s)) g.stepEdit(track, s, lane, valueAt(ev.y))
                        }
                    }
                    MotionEvent.ACTION_UP -> if (gesture[2] == 0f) { val s = stepAt(ev.x); g.stepTap(track, s); g.cursor.intValue = s }
                }
                true
            }
    ) {
        tel.frame.longValue                       // playhead redraws each frame (draw-only)
        val w = size.width / steps
        val pad = minOf(4.dp.toPx(), w * 0.12f)
        val play = g.stepPosition(track)
        for (s in 0 until steps) {
            val x = s * w
            val beat = s % 4 == 0
            val e = byStep[s]
            val cell = Size(w - pad * 2, size.height - pad * 2)
            drawRoundRect(if (beat) Tb.Raised else Tb.Panel, Offset(x + pad, pad), cell, CornerRadius(6.dp.toPx()))
            if (e != null) {
                val v = laneVals[s].coerceIn(0.04f, 1f)
                val h = (size.height - pad * 2) * (if (lane == Lane.NOTE) 0.15f + 0.85f * v else v)
                val alpha = if (lane == Lane.PROB) 1f else 0.35f + 0.65f * e.probability   // uncertain steps look ghosted
                drawRoundRect(accent.copy(alpha = alpha), Offset(x + pad, size.height - pad - h), Size(cell.width, h), CornerRadius(6.dp.toPx()))
                if (!g.isPadTrack(track) && w > 18.dp.toPx())
                    drawTextSafe(tm, "${NOTE_NAMES[e.note % 12]}${e.note / 12 - 1}", Offset(x + pad + 3.dp.toPx(), size.height - pad - 16.dp.toPx()), noteStyle)
            }
            if (s == play) drawRoundRect(Tb.Text, Offset(x + pad, pad), cell, CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
            if (s == g.cursor.intValue) drawRect(Tb.EncBlue, Offset(x + pad, pad), Size(cell.width, 3.dp.toPx()))
            if (beat && w > 14.dp.toPx()) drawTextSafe(tm, "${s / 4 + 1}", Offset(x + pad + 3.dp.toPx(), pad + 2.dp.toPx()), numStyle)
        }
    }
}

private fun byStepHas(state: AppState, track: Int, step: Int): Boolean {
    val c = state.groove.clips.value[track]
    return state.groove.visibleNotes(track).any { Steps.stepOf(it, c.lengthTicks) == step }
}
