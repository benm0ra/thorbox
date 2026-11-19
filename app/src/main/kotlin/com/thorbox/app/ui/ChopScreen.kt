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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.sampler.ChopLogic
import com.thorbox.app.sampler.ChopMode
import com.thorbox.model.PlayMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sampler's top screen: Serato-style chopping.
 *   browser | sample → project match bar | waveform with 16 coloured slices | chop modes + selected pad.
 * Waveform gestures: drag a marker to move it · tap a slice to play and select it.
 */
@Composable
fun ChopScreen(state: AppState) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LibraryPanel(state, Modifier.width(170.dp).fillMaxHeight().glass(state, 14.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MatchBar(state)
            SliceWaveform(state, Modifier.weight(1f).fillMaxWidth())
            ChopControls(state)
        }
    }
}

@Composable
private fun MatchBar(state: AppState) {
    val s by state.sample.collectAsState()
    val loading by state.loadingName.collectAsState()
    val error by state.error.collectAsState()
    val sm = state.sampler
    val project = state.projectKey()
    val bpm = state.groove.groove.value.bpm
    Column(Modifier.fillMaxWidth().glass(state, 12.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(loading?.let { "Loading $it…" } ?: s?.name?.substringBeforeLast('.') ?: "Pick a sample",
                    color = Tb.Text, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(error ?: s?.let { "${metaLine(it)} · ${sm.regions.value.size} slices" } ?: "it lands on 16 pads, already chopped",
                    color = if (error != null) Tb.Warn else Tb.Muted, fontSize = 11.sp, maxLines = 1)
            }
            KeyBpmChip("SAMPLE", s?.key?.label()?.uppercase() ?: "—", s?.bpm?.let { fmtBpm(it) } ?: "—")
            Text("→", color = Tb.Muted, fontSize = 16.sp, fontWeight = FontWeight.Black)
            KeyBpmChip("PROJECT", project.label().uppercase(), fmtBpm(bpm))
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val semis = sm.keyShift()
            HwButton(String.format(Locale.US, "KEY MATCH %+d", semis), Modifier.weight(1f).fillMaxHeight(), active = sm.keyMatch.value,
                enabled = s?.key != null, accent = Tb.Key, fontSize = 12) { sm.toggleKeyMatch() }
            HwButton("BPM SYNC " + (s?.bpm?.let { com.thorbox.app.sampler.ChopLogic.syncLabel(it, bpm) } ?: ""), Modifier.weight(1f).fillMaxHeight(),
                active = sm.bpmSync.value, enabled = s?.bpm != null, accent = Tb.Tempo, fontSize = 12) { sm.toggleBpmSync() }
            HwButton("PROJECT ← SAMPLE", Modifier.fillMaxHeight(), enabled = s != null, fontSize = 11) { state.setProjectFromSample() }
            if (sm.rendering.value) Text("rendering…", color = Tb.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

private fun fmtBpm(b: Float) = if (b % 1f == 0f) "${b.roundToInt()}" else String.format(Locale.US, "%.1f", b)

@Composable
private fun KeyBpmChip(label: String, key: String, bpm: String) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Tb.Void.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = Tb.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(key, color = Tb.Key, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(bpm, color = Tb.Tempo, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SliceWaveform(state: AppState, modifier: Modifier) {
    val peaks by state.peaks.collectAsState()
    val sm = state.sampler
    val regions = sm.regions.value
    val selected = sm.selected.intValue
    val frames = sm.originalFrames
    val dims = remember { IntArray(2) }
    val drag = remember { intArrayOf(-1, 0) }     // [marker index being dragged, moved?]
    val path = remember { Path() }
    val tm = rememberTextMeasurer()
    val tab = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 10.sp, fontWeight = FontWeight.Black) }
    val tel = state.telemetry
    val grabPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }

    fun xToFrame(x: Float) = (x / dims[0].coerceAtLeast(1) * frames).toLong()
    fun nearestMarker(x: Float): Int {
        var best = -1; var bd = grabPx
        regions.forEachIndexed { i, r -> if (i > 0) { val mx = r.start.toFloat() / frames * dims[0]; val d = abs(mx - x); if (d < bd) { bd = d; best = i } } }
        return best
    }

    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Tb.Void.copy(alpha = 0.78f))
            .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                if (frames <= 0) return@pointerInteropFilter true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { drag[0] = nearestMarker(ev.x); drag[1] = 0 }
                    MotionEvent.ACTION_MOVE -> if (drag[0] > 0) { drag[1] = 1; sm.moveMarker(drag[0], xToFrame(ev.x)) }
                    MotionEvent.ACTION_UP -> {
                        if (drag[0] <= 0 || drag[1] == 0) {
                            val f = xToFrame(ev.x)
                            val i = regions.indexOfFirst { f >= it.start && f < it.end }
                            if (i >= 0) { state.padDown(i, 0.85f); state.padUp(i) }   // audition + select
                        }
                        drag[0] = -1
                    }
                }
                true
            }
            .drawWithCache {
                // The waveform path is rebuilt only when the peaks or the size change, not at 120 Hz.
                val w = size.width; val h = size.height
                val p = peaks
                path.reset()
                if (p != null) {
                    val buckets = p.size / 2; val cols = w.toInt().coerceAtLeast(1); val mid = h / 2f; val amp = h * 0.42f
                    for (x in 0 until cols) {
                        val b0 = x * buckets / cols; val b1 = ((x + 1) * buckets / cols).coerceIn(b0 + 1, buckets)
                        var mn = 0f; var mx = 0f
                        for (bk in b0 until b1) { mn = minOf(mn, p[2 * bk]); mx = maxOf(mx, p[2 * bk + 1]) }
                        path.moveTo(x + 0.5f, mid - mx * amp); path.lineTo(x + 0.5f, mid - mn * amp + 1f)
                    }
                }
                val waveStroke = Stroke(1f)
                val tabSize = Size(18.dp.toPx(), 15.dp.toPx()); val tabR = CornerRadius(3.dp.toPx())
                val thin = 2.dp.toPx(); val thick = 3.dp.toPx(); val phW = 2.5f.dp.toPx()
                onDrawBehind {
                    if (frames <= 0 || regions.isEmpty()) return@onDrawBehind
                    regions.forEachIndexed { i, r ->
                        val x0 = r.start.toFloat() / frames * w; val x1 = r.end.toFloat() / frames * w
                        drawRect(Tb.Pads[i % Tb.Pads.size].copy(alpha = if (i == selected) 0.30f else 0.10f), Offset(x0, 0f), Size(x1 - x0, h))
                    }
                    drawPath(path, Tb.Text.copy(alpha = 0.85f), style = waveStroke)
                    regions.forEachIndexed { i, r ->
                        val x = r.start.toFloat() / frames * w
                        val c = Tb.Pads[i % Tb.Pads.size]
                        drawLine(c, Offset(x, 0f), Offset(x, h), if (i == selected) thick else thin)
                        drawRoundRect(c, Offset(x, 0f), tabSize, tabR)
                        drawText(tm, "${i + 1}", Offset(x + 3.dp.toPx(), 1.dp.toPx()), tab)
                    }
                    tel.frame.longValue                    // only the playhead needs every frame
                    val ph = state.playhead.floatValue
                    if (ph in 0f..1f) drawLine(Tb.Text, Offset(ph * w, 0f), Offset(ph * w, h), phW)
                }
            }
    )
}

@Composable
private fun ChopControls(state: AppState) {
    val sm = state.sampler
    val mode = sm.chopMode.value
    val pad = sm.pads.value[sm.selected.intValue]
    Row(Modifier.fillMaxWidth().height(52.dp).glass(state, 12.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HwButton("AUTO", Modifier.fillMaxHeight(), active = mode == ChopMode.AUTO, accent = Tb.Pads[0], fontSize = 12) { sm.setChopMode(ChopMode.AUTO) }
        if (mode == ChopMode.AUTO)
            DragValue("SENS", "${(sm.sensitivity.floatValue * 100).roundToInt()}%", Tb.Pads[0], Modifier.width(70.dp).fillMaxHeight(), pxPerUnit = 300f,
                onDelta = { sm.setSensitivity(sm.sensitivity.floatValue + it) }, onReset = { sm.setSensitivity(0.6f) })
        listOf(4, 8, 16).forEach { n ->
            HwButton("÷$n", Modifier.fillMaxHeight(), active = mode == ChopMode.EQUAL && sm.equalCount.intValue == n, accent = Tb.EncWhite, fontSize = 12) { sm.setEqualCount(n) }
        }
        HwButton(if (sm.lazyRunning.value) "LAZY ●" else "LAZY", Modifier.fillMaxHeight(), active = mode == ChopMode.LAZY, accent = Tb.Warn, fontSize = 12) {
            sm.setChopMode(if (mode == ChopMode.LAZY) ChopMode.AUTO else ChopMode.LAZY)
        }
        HoldButton("▶", false, Tb.Good, Modifier.width(44.dp).fillMaxHeight()) { sm.togglePreview(it) }
        Box(Modifier.weight(1f))
        // Selected pad
        Text("PAD ${sm.selected.intValue + 1}", color = Tb.Pads[sm.selected.intValue % Tb.Pads.size], fontWeight = FontWeight.Black, fontSize = 13.sp)
        HwButton("−", Modifier.fillMaxHeight(), fontSize = 18) { state.nudgeSemis(-1) }
        Text(String.format(Locale.US, "%+d", pad.pitch), color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 16.sp)
        HwButton("+", Modifier.fillMaxHeight(), fontSize = 18) { state.nudgeSemis(+1) }
        HwButton(when (pad.mode) { PlayMode.ONE_SHOT -> "ONE"; PlayMode.GATE -> "GATE"; PlayMode.LOOP -> "LOOP" }, Modifier.fillMaxHeight(), fontSize = 11) { state.cyclePlayMode() }
    }
}

/**
 * Deck 1: the 16 chopped pads (MPC layout: pad 1 bottom-left). Each pad has its slice's colour,
 * lights from the engine while it sounds, and the selected pad is outlined. Velocity comes from where
 * you hit it within the pad (top = loud). In LAZY mode, tapping a pad drops a slice marker instead.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Pad16Deck(state: AppState, modifier: Modifier = Modifier) {
    val sm = state.sampler
    val dims = remember { IntArray(2) }
    val fingers = remember { HashMap<Int, Int>() }
    val tel = state.telemetry
    val tm = rememberTextMeasurer()
    val num = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 16.sp, fontWeight = FontWeight.Black) }
    val hint = remember(Tb.palette) { TextStyle(color = Tb.Black.copy(alpha = 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Bold) }

    fun cellAt(x: Float, y: Float): Pair<Int, Float> {
        val cw = dims[0].coerceAtLeast(1) / 4f; val ch = dims[1].coerceAtLeast(1) / 4f
        val col = (x / cw).toInt().coerceIn(0, 3); val rowTop = (y / ch).toInt().coerceIn(0, 3)
        val inCell = ((y % ch) / ch).coerceIn(0f, 1f)
        return ((3 - rowTop) * 4 + col) to (0.35f + 0.65f * (1f - inCell))
    }

    Canvas(
        modifier.onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                val i = ev.actionIndex
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val (pad, vel) = cellAt(ev.getX(i), ev.getY(i))
                        fingers[ev.getPointerId(i)] = pad
                        state.padDown(pad, vel)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingers.remove(ev.getPointerId(i))?.let { state.padUp(it) }
                    MotionEvent.ACTION_CANCEL -> { fingers.values.forEach { state.padUp(it) }; fingers.clear() }
                }
                true
            }
    ) {
        tel.frame.longValue
        val active = state.groove.transport[13].toInt()
        val sel = sm.selected.intValue
        val count = sm.regions.value.size
        val lazy = sm.lazyRunning.value
        val cw = size.width / 4; val ch = size.height / 4; val gap = 4.dp.toPx(); val r = CornerRadius(10.dp.toPx())
        for (k in 0 until 16) {
            val col = k % 4; val rowTop = 3 - k / 4
            val o = Offset(col * cw + gap, rowTop * ch + gap); val s = Size(cw - 2 * gap, ch - 2 * gap)
            val c = Tb.Pads[k % Tb.Pads.size]
            val on = active and (1 shl k) != 0
            val empty = k >= count
            drawRoundRect(if (empty) Tb.Raised else if (on) c else c.copy(alpha = 0.72f), o, s, r)
            if (k == sel) drawRoundRect(Tb.Text, o, s, r, style = Stroke(3.dp.toPx()))
            drawText(tm, "${k + 1}", o + Offset(8.dp.toPx(), 6.dp.toPx()), if (empty) num.copy(color = Tb.Dim) else num)
            val p = sm.pads.value[k].pitch
            if (p != 0) drawText(tm, String.format(Locale.US, "%+d st", p), o + Offset(8.dp.toPx(), s.height - 16.dp.toPx()), hint)
        }
        if (lazy) drawRoundRect(Tb.Warn, Offset(gap, gap), Size(size.width - 2 * gap, size.height - 2 * gap), r, style = Stroke(3.dp.toPx()))
    }
}
