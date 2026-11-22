// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navTarget
import com.thorbox.app.input.NavGrid
import com.thorbox.app.input.navModal
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.thorbox.app.sampler.StretchMode
import com.thorbox.app.sampler.ChopLogic
import com.thorbox.app.sampler.ChopMode
import com.thorbox.model.PlayMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sampler's top screen: chopping.
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
            GrooveStrip(state, state.samplerTrack.intValue, showLabel = false)
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
            if (LocalWide.current) SectionTag(state, Section.SAMPLE)
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
        var choosing by remember { mutableStateOf(false) }
        if (!choosing) Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val semis = sm.keyShift()
            HwButton(String.format(Locale.US, "KEY MATCH %+d", semis), Modifier.weight(1f).fillMaxHeight(), active = sm.keyMatch.value,
                enabled = s?.key != null, accent = Tb.Key, fontSize = 12) { sm.toggleKeyMatch() }
            // STRETCH: one button that says what it's doing; tap it for the choices (one tap picks and closes).
            val on = sm.stretch.value != com.thorbox.app.sampler.StretchMode.OFF
            HwButton("STRETCH " + (if (sm.rendering.value) "…" else sm.stretchLabel()), Modifier.weight(1.2f).fillMaxHeight(),
                active = on, enabled = s != null, accent = Tb.Tempo, fontSize = 12) { choosing = true }
            HwButton("USE SAMPLE KEY+BPM", Modifier.weight(1.4f).fillMaxHeight(), enabled = s != null, fontSize = 11) { state.setProjectFromSample() }
        } else StretchChooser(state) { choosing = false }
    }
}

/**
 * Pick how the sample is time-stretched (pitch always kept). Big, labelled, one tap each:
 * OFF · SYNC to the project tempo (needs a detected BPM) · HALF-TIME · DOUBLE-TIME · FIT 1/2/4/8 bars
 * (stretches whatever length it is to exactly that many bars: great for field recordings and loops
 * with no clean BPM). ✕ closes without changing anything.
 */
@Composable
private fun StretchChooser(state: AppState, close: () -> Unit) {
    val sm = state.sampler
    val cur = sm.stretch.value
    val hasBpm = state.sample.collectAsState().value?.bpm != null
    fun pick(m: StretchMode, bars: Int = sm.fitBars.intValue) { sm.setStretch(m, bars); close() }
    Row(Modifier.navModal(close).fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("STRETCH", style = Tb.Label, color = Tb.Tempo, modifier = Modifier.padding(horizontal = 4.dp))
        HwButton("OFF", Modifier.fillMaxHeight(), active = cur == StretchMode.OFF, accent = Tb.Tempo, fontSize = 11, hPad = 8.dp) { pick(StretchMode.OFF) }
        HwButton("SYNC", Modifier.fillMaxHeight(), active = cur == StretchMode.SYNC, enabled = hasBpm, accent = Tb.Tempo, fontSize = 11, hPad = 8.dp) { pick(StretchMode.SYNC) }
        HwButton("½×", Modifier.fillMaxHeight(), active = cur == StretchMode.HALF, accent = Tb.Tempo, fontSize = 12, hPad = 8.dp) { pick(StretchMode.HALF) }
        HwButton("2×", Modifier.fillMaxHeight(), active = cur == StretchMode.DOUBLE, accent = Tb.Tempo, fontSize = 12, hPad = 8.dp) { pick(StretchMode.DOUBLE) }
        Text("FIT", style = Tb.Label, color = Tb.Muted, modifier = Modifier.padding(start = 4.dp))
        for (b in intArrayOf(1, 2, 4, 8)) HwButton("$b BAR${if (b == 1) "" else "S"}", Modifier.weight(1f).fillMaxHeight(),
            active = cur == StretchMode.FIT && sm.fitBars.intValue == b, accent = Tb.Tempo, fontSize = 11, hPad = 4.dp) { pick(StretchMode.FIT, b) }
        HwButton("✕", Modifier.width(44.dp).fillMaxHeight(), fontSize = 13) { close() }
    }
}

private fun fmtBpm(b: Float) = if (b % 1f == 0f) "${b.roundToInt()}" else String.format(Locale.US, "%.1f", b)

@Composable
private fun KeyBpmChip(label: String, key: String, bpm: String) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Tb.Void.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = Tb.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(key, color = Tb.Key, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(if (bpm == "—") bpm else "$bpm BPM", color = Tb.Tempo, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp)
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
    val tm = rememberTextMeasurer(cacheSize = 64)
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
        modifier.bezel()
            .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                if (frames <= 0) return@pointerInteropFilter false   // empty: let the CHOOSE / RECORD buttons have the touch
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
                val tabLabels = List(16) { tm.measure("${it + 1}", tab) }      // measured once per size/theme, not per frame
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
                        // Keep the number tab on screen: a slice starting near the right edge used to
                        // ask drawText for a negative width and crash.
                        val tx = x.coerceAtMost(w - tabSize.width).coerceAtLeast(0f)
                        drawRoundRect(c, Offset(tx, 0f), tabSize, tabR)
                        drawText(tabLabels[i % tabLabels.size], topLeft = Offset(tx + 3.dp.toPx(), 1.dp.toPx()))
                    }
                    tel.frame.longValue                    // only the playhead needs every frame
                    val ph = state.playhead.floatValue
                    if (ph in 0f..1f) drawLine(Tb.Text, Offset(ph * w, 0f), Offset(ph * w, h), phW)
                }
            }
    ) {
        // Empty state: the big dark box is where your first sample goes, so the ways to get one live here.
        if (frames <= 0 && state.loadingName.collectAsState().value == null) {
            androidx.compose.foundation.layout.Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No sample yet", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("Pick a folder of loops and breaks, record one with the mic, or try the demo break.", color = Tb.Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HwButton("CHOOSE FOLDER", active = true, minHeight = 48.dp, fontSize = 13) { state.requestFolder() }
                    HwButton("RECORD", accent = Tb.Warn, active = true, minHeight = 48.dp, fontSize = 13) { state.openMic() }
                    HwButton("DEMO BREAK", accent = Tb.EncGreen, active = true, minHeight = 48.dp, fontSize = 13) { state.loadDemo() }
                }
            }
        }
    }
}

@Composable
private fun ChopControls(state: AppState) {
    val sm = state.sampler
    val mode = sm.chopMode.value
    val pad = sm.pads.value[sm.selected.intValue]
    // Two rows on the Thor's top screen: how to chop (top), then the selected pad (bottom). One row
    // squeezed ÷4 / ÷8 / ÷16 to blank buttons and pushed + and the play mode off the edge.
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().glass(state, 12.dp).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HwButton("AUTO", Modifier.fillMaxHeight(), active = mode == ChopMode.AUTO, accent = Tb.Pads[0], fontSize = 12) { sm.setChopMode(ChopMode.AUTO) }
        if (mode == ChopMode.AUTO)
            DragValue("DETECT", "${(sm.sensitivity.floatValue * 100).roundToInt()}%", Tb.Pads[0], Modifier.width(70.dp).fillMaxHeight(), pxPerUnit = 300f,
                onDelta = { sm.setSensitivity(sm.sensitivity.floatValue + it) }, onReset = { sm.setSensitivity(0.6f) })
        listOf(4, 8, 16).forEach { n ->
            HwButton("÷$n", Modifier.weight(1f).fillMaxHeight(), active = mode == ChopMode.EQUAL && sm.equalCount.intValue == n, accent = Tb.EncWhite, fontSize = 12) { sm.setEqualCount(n) }
        }
        HwButton(if (sm.lazyRunning.value) "TAP-CHOP ●" else "TAP-CHOP", Modifier.weight(1.6f).fillMaxHeight(), active = mode == ChopMode.LAZY, accent = Tb.Warn, fontSize = 12) {
            sm.setChopMode(if (mode == ChopMode.LAZY) ChopMode.AUTO else ChopMode.LAZY)
        }
        HoldButton("▶", false, Tb.Good, Modifier.width(52.dp).fillMaxHeight()) { sm.togglePreview(it) }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Wide (the Thor's top screen): one row. Narrow (single-screen phones / emulator): pitch on one
    // row, WARP · NORMALIZE · PLAY on the next, so nothing is pushed off the edge.
    val narrow = maxWidth < 720.dp
    val padColor = Tb.Pads[sm.selected.intValue % Tb.Pads.size]
    @Composable fun pitchGroup() {
        Text("PAD ${sm.selected.intValue + 1}", color = padColor, fontWeight = FontWeight.Black, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
        Text("PITCH", style = Tb.Label, color = Tb.Muted)
        HwButton("−", Modifier.width(52.dp).fillMaxHeight(), fontSize = 18) { state.nudgeSemis(-1) }
        Text(String.format(Locale.US, "%+d st", pad.pitch), color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp,
            modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HwButton("+", Modifier.width(52.dp).fillMaxHeight(), fontSize = 18) { state.nudgeSemis(+1) }
    }
    @Composable fun RowScopeTools(scope: androidx.compose.foundation.layout.RowScope) = with(scope) {
        // WARP: pitch this chop without it speeding up (MPC-style); off = turntable pitch (faster + higher).
        // narrow: the three share the row (no fixed widths, no PLAY label) so nothing runs off the edge
        HwButton(if (pad.warp) "WARP ON" else "WARP", (if (narrow) Modifier.weight(1f) else Modifier.width(96.dp)).fillMaxHeight(), active = pad.warp, accent = Tb.Tempo, fontSize = 12) {
            state.sampler.toggleWarp(state.sampler.selected.intValue)
        }
        // NORMALIZE: every chop to the same loudness (tap again to undo). The pad's gain shows beside it.
        HwButton("NORMALIZE", (if (narrow) Modifier.weight(1.2f) else Modifier.width(118.dp)).fillMaxHeight(), active = sm.normalized.value, accent = Tb.Good, fontSize = 12) {
            state.lastInput.value = sm.toggleNormalize()
        }
        if (pad.gainDb != 0f) Text(String.format(Locale.US, "%+.1f dB", pad.gainDb), color = Tb.Good, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (!narrow) { Box(Modifier.weight(1f)); Text("PLAY", style = Tb.Label, color = Tb.Muted) }
        HwButton(when (pad.mode) { PlayMode.ONE_SHOT -> "ONE-SHOT"; PlayMode.GATE -> "HOLD"; PlayMode.LOOP -> "LOOP" }, (if (narrow) Modifier.weight(1.2f) else Modifier.width(120.dp)).fillMaxHeight(),
            active = true, accent = padColor, fontSize = 12) { state.cyclePlayMode() }
    }
    if (narrow) androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { pitchGroup() }
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { RowScopeTools(this) }
    } else Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) { pitchGroup(); RowScopeTools(this) }
    }
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
    val tm = rememberTextMeasurer(cacheSize = 64)
    val num = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 16.sp, fontWeight = FontWeight.Black) }
    val hint = remember(Tb.palette) { TextStyle(color = Tb.Black.copy(alpha = 0.6f), fontSize = 9.sp, fontWeight = FontWeight.Bold) }

    fun cellAt(x: Float, y: Float): Pair<Int, Float> {
        val cw = dims[0].coerceAtLeast(1) / 4f; val ch = dims[1].coerceAtLeast(1) / 4f
        val col = (x / cw).toInt().coerceIn(0, 3); val rowTop = (y / ch).toInt().coerceIn(0, 3)
        val inCell = ((y % ch) / ch).coerceIn(0f, 1f)
        return ((3 - rowTop) * 4 + col) to (0.35f + 0.65f * (1f - inCell))
    }

    // NAV: A enters the pads, the D-pad picks one (MPC layout), A plays it for as long as it's held.
    val navGrid = NavGrid(4, 4,
        get = { val p = sm.selected.intValue; (3 - p / 4) * 4 + p % 4 },
        set = { c -> sm.selected.intValue = (3 - c / 4) * 4 + c % 4 },
        down = { c -> state.padDown((3 - c / 4) * 4 + c % 4, 0.85f) },
        up = { c -> state.padUp((3 - c / 4) * 4 + c % 4) },
        describe = { c -> "PAD ${(3 - c / 4) * 4 + c % 4 + 1}" })
    Canvas(
        modifier.onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .navTarget("PADS", grid = navGrid, corner = 10f)
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
            drawTextSafe(tm, "${k + 1}", o + Offset(8.dp.toPx(), 6.dp.toPx()), if (empty) num.copy(color = Tb.Dim) else num)
            val p = sm.pads.value[k].pitch
            if (p != 0) drawTextSafe(tm, String.format(Locale.US, "%+d st", p), o + Offset(8.dp.toPx(), s.height - 16.dp.toPx()), hint)
        }
        val lm = state.levelMode.value
        if (lm != com.thorbox.app.levels.LevelMode.OFF) {
            // 16 LEVELS: repaint every pad as the source pad, shaded low → high, with its level
            val src = state.levelSrc.intValue
            val c = Tb.Pads[src % Tb.Pads.size]
            val on = active and (1 shl src) != 0
            for (k in 0 until 16) {
                val col = k % 4; val rowTop = 3 - k / 4
                val o = Offset(col * cw + gap, rowTop * ch + gap); val s = Size(cw - 2 * gap, ch - 2 * gap)
                drawRoundRect(Tb.Void, o, s, r)
                drawRoundRect(c.copy(alpha = 0.25f + 0.6f * k / 15f + if (on) 0.12f else 0f), o, s, r)
                if (k == 8 && lm == com.thorbox.app.levels.LevelMode.TUNE) drawRoundRect(Tb.Text, o, s, r, style = Stroke(2.dp.toPx()))
                drawTextSafe(tm, com.thorbox.app.levels.Levels.label(lm, k), o + Offset(8.dp.toPx(), 6.dp.toPx()), num)
                drawTextSafe(tm, "L${k + 1}", o + Offset(8.dp.toPx(), s.height - 16.dp.toPx()), hint)
            }
        }
        if (lazy) drawRoundRect(Tb.Warn, Offset(gap, gap), Size(size.width - 2 * gap, size.height - 2 * gap), r, style = Stroke(3.dp.toPx()))
    }
}
