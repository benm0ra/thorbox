// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navTarget
import com.thorbox.app.input.NavGrid
import com.thorbox.app.input.navModal
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.thorbox.app.input.navClickable
import com.thorbox.app.sampler.StretchMode
import com.thorbox.app.sampler.ChopMode
import com.thorbox.model.PlayMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * The sampler's top screen: chopping.
 *   browser | sample → project match bar | waveform with 16 coloured slices | chop modes + selected pad.
 * Waveform gestures: drag a marker to move it · tap a slice to play and select it.
 */
@Composable
fun ChopScreen(state: AppState) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    val libW = if (maxWidth < 640.dp) 132.dp else 170.dp        // narrow screens: the waveform gets the room
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LibraryPanel(state, Modifier.width(libW).fillMaxHeight().glass(state, 14.dp).clip(RoundedCornerShape(14.dp)))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MatchBar(state)
                SliceWaveform(state, Modifier.weight(1f).fillMaxWidth())
                BarNav(state, state.samplerTrack.intValue, CHOP_PAGE, Modifier.fillMaxWidth(), height = 30.dp)
                ChopPattern(state, Modifier.fillMaxWidth().height(40.dp))
                ChopControls(state)
            }
            // a load in progress: the loading card over the sample side (CANCEL is reachable by touch and NAV)
            state.loadJob.value?.let { LoadingCard(state, it, Modifier.align(Alignment.Center)) }
            // PAD EDIT (the selected pad's sound / mute group / play mode), over the chop side
            if (state.showPadEdit.value) PadEditSheet(state, onClose = { state.showPadEdit.value = false }, modifier = Modifier.fillMaxSize())
        }
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
                Text(loading?.let { "Loading ${it.substringBeforeLast('.')}…" } ?: s?.name?.substringBeforeLast('.') ?: "Pick a sample",
                    color = Tb.Text, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(error ?: s?.let { "${metaLine(it)} · ${sm.regions.value.size} slices" } ?: "it lands on 16 pads, already chopped",
                    color = if (error != null) Tb.Warn else Tb.Muted, fontSize = 11.sp, maxLines = 1)
            }
            KeyBpmChip("SAMPLE", s?.key?.label()?.uppercase() ?: "—", s?.bpm?.let { fmtBpm(it) } ?: "—")
            Text("→", color = Tb.Muted, fontSize = 16.sp, fontWeight = FontWeight.Black)
            // the project key: tap it for the key sheet (and KEY LOCK)
            KeyBpmChip(if (state.lockAll.value) "PROJECT 🔒" else "PROJECT", project.label().uppercase(), fmtBpm(bpm)) { state.showKeySheet.value = true }
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
private fun KeyBpmChip(label: String, key: String, bpm: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Tb.Void.copy(alpha = 0.55f))
        .then(if (onClick != null) Modifier.navClickable(label = "PROJECT KEY") { onClick() } else Modifier)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
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
    val ruler = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold) }
    val barStyle = remember(Tb.palette) { TextStyle(color = Tb.Tempo.copy(alpha = 0.8f), fontSize = 8.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black) }
    val infoStyle = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 10.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold) }
    val info by state.sample.collectAsState()
    val tel = state.telemetry
    val grabPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    val slop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

    // ZOOM: the window on screen, as fractions of the sample (two fingers pinch / slide it; double-tap or FIT resets)
    var view by remember(frames) { androidx.compose.runtime.mutableStateOf(WaveView(0.0, 1.0)) }
    val minSpan = if (frames > 0) (160.0 / frames).coerceIn(1e-7, 1.0) else 1.0      // ~160 samples across the screen at most
    // zoomed in: the engine measures just the stretch on screen (a bit either side), so it stays sharp
    var detail by remember(frames) { androidx.compose.runtime.mutableStateOf<WaveDetail?>(null) }
    val sid = info?.engineId
    androidx.compose.runtime.LaunchedEffect(view, sid, frames) {
        if (view.span > 0.97 || sid == null || frames <= 0) { detail = null; return@LaunchedEffect }
        if (detail?.covers(view, frames) == true) return@LaunchedEffect
        kotlinx.coroutines.delay(40)                                   // let a pinch settle
        val a = ((view.start - view.span * 0.5).coerceAtLeast(0.0) * frames).toLong()
        val b = ((view.start + view.span * 1.5).coerceAtMost(1.0) * frames).toLong().coerceAtLeast(a + 1)
        val cols = (dims[0].coerceIn(256, 2048) * 2)
        val arr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.thorbox.engine.NativeEngine.waveRange(sid, a, b, cols) }
        if (arr != null) detail = WaveDetail(a, b, arr)
    }

    fun xToFrame(x: Float) = ((view.start + x / dims[0].coerceAtLeast(1) * view.span) * frames).toLong().coerceIn(0L, (frames - 1).coerceAtLeast(0L))
    fun frameToX(f: Long) = ((f.toDouble() / frames - view.start) / view.span * dims[0]).toFloat()
    fun nearestMarker(x: Float): Int {
        var best = -1; var bd = grabPx
        regions.forEachIndexed { i, r -> if (i > 0) { val mx = frameToX(r.start); val d = abs(mx - x); if (d < bd) { bd = d; best = i } } }
        return best
    }
    // touch: [downX, start at down, pinch distance at start, span at start, focus (sample fraction), mode 0 none / 1 pinch / 2 pan, last tap time]
    val touch = remember { DoubleArray(7) }

    Box(
        modifier.bezel()
            .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                if (frames <= 0) return@pointerInteropFilter false   // empty: let the CHOOSE / RECORD buttons have the touch
                val w = dims[0].coerceAtLeast(1).toDouble()
                fun spread() = if (ev.pointerCount >= 2) kotlin.math.hypot((ev.getX(0) - ev.getX(1)).toDouble(), (ev.getY(0) - ev.getY(1)).toDouble()) else 1.0
                fun focusX() = if (ev.pointerCount >= 2) (ev.getX(0) + ev.getX(1)) / 2.0 else ev.x.toDouble()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { drag[0] = nearestMarker(ev.x); drag[1] = 0; touch[0] = ev.x.toDouble(); touch[1] = view.start; touch[5] = 0.0 }
                    MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) {
                        // two fingers: zoom around the point between them (and slide with them)
                        drag[0] = -1; touch[5] = 1.0
                        touch[2] = spread().coerceAtLeast(1.0); touch[3] = view.span
                        touch[4] = view.start + focusX() / w * view.span
                    }
                    MotionEvent.ACTION_MOVE -> when {
                        touch[5] == 1.0 && ev.pointerCount >= 2 -> {
                            val span = (touch[3] * touch[2] / spread().coerceAtLeast(1.0)).coerceIn(minSpan, 1.0)
                            view = WaveView((touch[4] - focusX() / w * span).coerceIn(0.0, 1.0 - span), span)
                        }
                        touch[5] == 1.0 -> Unit                              // a finger left: wait for the other
                        drag[0] > 0 -> { drag[1] = 1; sm.moveMarker(drag[0], xToFrame(ev.x)) }
                        view.span < 1.0 && (touch[5] == 2.0 || abs(ev.x - touch[0]) > slop) -> {    // zoomed: one finger scrolls
                            touch[5] = 2.0
                            view = WaveView((touch[1] - (ev.x - touch[0]) / w * view.span).coerceIn(0.0, 1.0 - view.span), view.span)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = android.os.SystemClock.uptimeMillis().toDouble()
                        if (touch[5] == 0.0 && (drag[0] <= 0 || drag[1] == 0)) {
                            if (now - touch[6] < 300 && view.span < 1.0) view = WaveView(0.0, 1.0)     // double-tap: the whole sample
                            else {
                                val f = xToFrame(ev.x)
                                val i = regions.indexOfFirst { f >= it.start && f < it.end }
                                if (i >= 0) { state.padDown(i, 0.85f); state.padUp(i) }   // audition + select
                            }
                            touch[6] = now
                        }
                        drag[0] = -1; touch[5] = 0.0
                    }
                    MotionEvent.ACTION_CANCEL -> { drag[0] = -1; touch[5] = 0.0 }
                }
                true
            }
            .drawWithCache {
                // Everything but the playhead is built here, once per (peaks, slices, selection, size, theme):
                // the 120 Hz playhead only re-draws.
                val w = size.width; val h = size.height
                val rulerH = 24.dp.toPx()
                val wh = h - rulerH
                val mid = wh / 2f; val amp = wh * 0.44f
                val p = peaks
                val nReg = regions.size
                val v = view
                val vf0 = v.start * frames; val vfPer = v.span * frames / w          // frame at x = vf0 + x * vfPer
                fun xOf(f: Long) = ((f - vf0) / vfPer).toFloat()
                // the sharpest data that covers the window: the engine's close-up, else the whole-sample peaks
                val det = detail?.takeIf { it.covers(v, frames) }
                val src: FloatArray? = det?.data ?: p
                val s0 = det?.from ?: 0L; val s1 = det?.to ?: frames
                // one outline (peaks) + one body (RMS, what you hear as loudness) per slice, coloured like its pad;
                // index nReg = audio outside every slice
                val outer = Array(nReg + 1) { Path() }; val inner = Array(nReg + 1) { Path() }
                val clipMarks = Path()
                var peakAbs = 0f
                if (src != null && src.size >= 3 && frames > 0) {
                    val buckets = src.size / 3; val cols = w.toInt().coerceAtLeast(1)
                    val bPerFrame = buckets.toDouble() / (s1 - s0).coerceAtLeast(1L)
                    var ri = 0
                    for (x in 0 until cols) {
                        val fa = vf0 + x * vfPer; val fb = fa + vfPer
                        val b0 = ((fa - s0) * bPerFrame).toInt().coerceIn(0, buckets - 1)
                        val b1 = ((fb - s0) * bPerFrame).toInt().coerceIn(b0 + 1, buckets)
                        var mn = 0f; var mx = 0f; var sq = 0f
                        for (bk in b0 until b1) { mn = minOf(mn, src[3 * bk]); mx = maxOf(mx, src[3 * bk + 1]); sq += src[3 * bk + 2] * src[3 * bk + 2] }
                        val rms = kotlin.math.sqrt(sq / (b1 - b0))
                        val f = (fa + vfPer * 0.5).toLong()
                        while (ri < nReg - 1 && f >= regions[ri + 1].start) ri++
                        val idx = if (nReg == 0 || f < regions[ri].start || f >= regions[ri].end) nReg else ri
                        val xx = x + 0.5f
                        outer[idx].moveTo(xx, mid - mx * amp); outer[idx].lineTo(xx, mid - mn * amp + 1f)
                        inner[idx].moveTo(xx, mid - rms * amp); inner[idx].lineTo(xx, mid + rms * amp + 1f)
                        if (mx >= 0.989f || mn <= -0.989f) clipMarks.addRect(androidx.compose.ui.geometry.Rect(x.toFloat(), 0f, x + 1f, 4.dp.toPx()))
                        peakAbs = maxOf(peakAbs, mx, -mn)
                    }
                }
                val waveStroke = Stroke(1f)
                val tabLabels = List(16) { tm.measure("${it + 1}", tab) }      // measured once per size/theme, not per frame
                val tabSize = Size(18.dp.toPx(), 15.dp.toPx()); val tabR = CornerRadius(3.dp.toPx())
                val thin = 2.dp.toPx(); val thick = 3.dp.toPx(); val phW = 2.5f.dp.toPx()
                // time ruler: the largest step that keeps labels ≥ 64 dp apart
                val sr = (info?.sampleRate ?: 44100).coerceAtLeast(1)
                val secs = frames.toDouble() / sr
                val vs0 = v.start * secs; val vSecs = v.span * secs                 // the seconds on screen
                val stepS = doubleArrayOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0)
                    .firstOrNull { vSecs > 0 && it / vSecs * w >= 64.dp.toPx() } ?: 300.0
                val ticks = ArrayList<Pair<Float, androidx.compose.ui.text.TextLayoutResult>>()
                if (vSecs > 0) {
                    var k = kotlin.math.ceil(vs0 / stepS).toLong()
                    while (k * stepS <= vs0 + vSecs && ticks.size < 64) { val t = k * stepS; ticks += ((t - vs0) / vSecs * w).toFloat() to tm.measure(fmtTime(t, stepS), ruler); k++ }
                }
                // beat / bar grid from the detected tempo (bars every 4 beats from the start)
                val bpm = info?.bpm
                val beatFrames = if (bpm != null && bpm > 0f) 60.0 / bpm * sr else 0.0
                val beatPx = if (beatFrames > 0 && frames > 0) (beatFrames / vfPer).toFloat() else 0f
                val barEvery = generateSequence(1) { it * 2 }.first { beatPx * 4 * it >= 30.dp.toPx() || it >= 64 }
                val firstBeat = if (beatFrames > 0) kotlin.math.ceil(vf0 / beatFrames).toLong() else 0L
                val beatX0 = if (beatFrames > 0) ((firstBeat * beatFrames - vf0) / vfPer).toFloat() else 0f
                // bar numbers for the bars on screen: (x, label)
                val barLabels = ArrayList<Pair<Float, androidx.compose.ui.text.TextLayoutResult>>()
                if (beatPx > 0f) {
                    var bar = kotlin.math.ceil(vf0 / (beatFrames * 4)).toLong()
                    while (barLabels.size < 200) {
                        val x = ((bar * 4 * beatFrames - vf0) / vfPer).toFloat(); if (x > w) break
                        if (bar % barEvery == 0L) barLabels += x to tm.measure("${bar + 1}", barStyle)
                        bar++
                    }
                }
                // the readout: length · rate · channels · peak
                val infoText = if (frames > 0) tm.measure(buildString {
                    append(fmtTime(secs, 0.01)); append(" · ").append(String.format(Locale.US, "%.1f kHz", sr / 1000f))
                    append(if ((info?.channels ?: 1) > 1) " stereo" else " mono")
                    if (peakAbs > 0f) append(String.format(Locale.US, " · peak %.1f dB", 20f * kotlin.math.log10(peakAbs)))
                }, infoStyle) else null
                val selR = regions.getOrNull(selected)
                val sliceText = if (selR != null && frames > 0) tm.measure(String.format(Locale.US, "PAD %d · %s → %s · %.2f s", selected + 1,
                    fmtTime(selR.start.toDouble() / sr, 0.01), fmtTime(selR.end.toDouble() / sr, 0.01), (selR.end - selR.start).toDouble() / sr), infoStyle) else null
                val chipPad = 5.dp.toPx(); val chipR = CornerRadius(4.dp.toPx())
                onDrawBehind {
                    if (frames <= 0) return@onDrawBehind
                    // slice tints
                    regions.forEachIndexed { i, r ->
                        val x0 = xOf(r.start).coerceAtLeast(0f); val x1 = xOf(r.end).coerceAtMost(w)
                        if (x1 > x0) drawRect(Tb.Pads[i % Tb.Pads.size].copy(alpha = if (i == selected) 0.16f else 0.05f), Offset(x0, 0f), Size(x1 - x0, wh))
                    }
                    // beat + bar grid
                    if (beatPx >= 7.dp.toPx() || beatPx * 4 >= 7.dp.toPx()) {
                        var k = 0; var x = beatX0
                        while (x <= w && k < 4000) {
                            val bar = (firstBeat + k) % 4 == 0L
                            if (bar || beatPx >= 7.dp.toPx()) drawLine(Tb.Text.copy(alpha = if (bar) 0.13f else 0.05f), Offset(x, 0f), Offset(x, wh), 1f)
                            k++; x = beatX0 + k * beatPx
                        }
                    }
                    drawLine(Tb.Line, Offset(0f, mid), Offset(w, mid), 1f)
                    // the waveform: peaks as a thin outline, RMS as the solid body, each slice in its pad's colour
                    for (i in 0..nReg) {
                        val c = if (i == nReg) Tb.Text else Tb.Pads[i % Tb.Pads.size]
                        val on = i == selected
                        drawPath(outer[i], (if (i == nReg) c.copy(alpha = 0.35f) else lerpC(c, Tb.Text, 0.25f)).copy(alpha = if (on) 0.65f else 0.38f), style = waveStroke)
                        drawPath(inner[i], c.copy(alpha = if (on || i == nReg) 1f else 0.72f), style = waveStroke)
                    }
                    drawPath(clipMarks, Tb.Warn)                                    // clipping: red ticks along the top
                    regions.forEachIndexed { i, r ->
                        val x = xOf(r.start)
                        if (x < -2f || x > w + 2f) return@forEachIndexed               // zoomed: off screen
                        val c = Tb.Pads[i % Tb.Pads.size]
                        drawLine(c, Offset(x, 0f), Offset(x, wh), if (i == selected) thick else thin)
                        // Keep the number tab on screen: a slice starting near the right edge used to
                        // ask drawText for a negative width and crash.
                        val tx = x.coerceAtMost(w - tabSize.width).coerceAtLeast(0f)
                        drawRoundRect(c, Offset(tx, 0f), tabSize, tabR)
                        drawText(tabLabels[i % tabLabels.size], topLeft = Offset(tx + 3.dp.toPx(), 1.dp.toPx()))
                    }
                    // ruler: time ticks + labels, bar numbers on the beat grid
                    drawRect(Tb.Void.copy(alpha = 0.55f), Offset(0f, wh), Size(w, rulerH))
                    drawLine(Tb.Line, Offset(0f, wh), Offset(w, wh), 1f)
                    for ((x, lay) in ticks) {
                        drawLine(Tb.Muted, Offset(x, wh), Offset(x, wh + 5.dp.toPx()), 1f)
                        // clear of the frame's rounded corners at both ends (0:00 was cut in half)
                        val lx = (x + 3.dp.toPx()).coerceAtLeast(9.dp.toPx()).coerceAtMost(w - lay.size.width - 9.dp.toPx())
                        if (lx >= 0f) drawText(lay, topLeft = Offset(lx, wh + rulerH - lay.size.height - 1f))
                    }
                    for ((x, lay) in barLabels) if (x >= 0f && x + lay.size.width < w) drawText(lay, topLeft = Offset(x + 2.dp.toPx(), wh + 1f))
                    // zoomed: where the window is in the whole sample
                    if (v.span < 1.0) {
                        drawRect(Tb.Void.copy(alpha = 0.6f), Offset(0f, wh - 4.dp.toPx()), Size(w, 4.dp.toPx()))
                        drawRect(Tb.Text, Offset((v.start * w).toFloat(), wh - 4.dp.toPx()), Size((v.span * w).toFloat().coerceAtLeast(3f), 4.dp.toPx()))
                    }
                    // readouts: the sample (top-right, under the tabs), the selected slice (bottom-left)
                    if (wh > 120.dp.toPx()) infoText?.let { t ->
                        val o = Offset(w - t.size.width - chipPad * 3, 20.dp.toPx())
                        drawRoundRect(Tb.Void.copy(alpha = 0.7f), o - Offset(chipPad, 2f), Size(t.size.width + 2 * chipPad, t.size.height + 4f), chipR)
                        drawText(t, topLeft = o)
                    }
                    if (wh > 84.dp.toPx()) sliceText?.let { t ->
                        val c = Tb.Pads[selected % Tb.Pads.size]
                        val o = Offset(chipPad * 2, wh - t.size.height - 6.dp.toPx())
                        drawRoundRect(Tb.Void.copy(alpha = 0.7f), o - Offset(chipPad, 2f), Size(t.size.width + 2 * chipPad, t.size.height + 4f), chipR)
                        drawText(t, color = c, topLeft = o)
                    }
                    tel.frame.longValue                    // only the playhead needs every frame
                    val ph = state.playhead.floatValue
                    val phx = ((ph - v.start) / v.span * w).toFloat()
                    if (ph in 0f..1f && phx in 0f..w) {
                        val x = phx
                        drawLine(Tb.Text.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, wh), phW * 4f)     // glow
                        drawLine(Tb.Text, Offset(x, 0f), Offset(x, wh), phW)
                        if (wh > 64.dp.toPx()) {                     // its time, just under the number tabs
                            val t = tm.measure(fmtTime(ph * secs, 0.1), infoStyle)
                            val lx = (x + 4.dp.toPx()).coerceAtMost(w - t.size.width - 4f)
                            drawRoundRect(Tb.Text, Offset(lx - 3f, 19.dp.toPx()), Size(t.size.width + 6f, t.size.height + 2f), chipR)
                            drawText(t, color = Tb.OnText, topLeft = Offset(lx, 20.dp.toPx()))
                        }
                    }
                }
            }
    ) {
        if (view.span < 0.999 && frames > 0)
            HwButton("FIT · ${(1.0 / view.span).let { if (it >= 10) it.toInt().toString() else String.format(Locale.US, "%.1f", it) }}×",
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 30.dp), minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { view = WaveView(0.0, 1.0) }
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
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // narrow (phones / the CI emulator): smaller fixed buttons so ÷16 and TAP-CHOP keep their labels
    val tight = maxWidth < 560.dp
    val fixedW = if (tight) 40.dp else 46.dp
    Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        HwButton("AUTO", Modifier.width(if (tight) 50.dp else 60.dp).fillMaxHeight(), active = mode == ChopMode.AUTO, accent = Tb.Pads[0], fontSize = 12, hPad = 4.dp) { sm.setChopMode(ChopMode.AUTO) }
        if (mode == ChopMode.AUTO)
            DragValue("DETECT", "${(sm.sensitivity.floatValue * 100).roundToInt()}%", Tb.Pads[0], Modifier.width(if (tight) 52.dp else 64.dp).fillMaxHeight(), pxPerUnit = 300f,
                onDelta = { sm.setSensitivity(sm.sensitivity.floatValue + it) }, onReset = { sm.setSensitivity(0.6f) })
        listOf(4, 8, 16).forEach { n ->
            HwButton("÷$n", Modifier.weight(if (n == 16) 1.25f else 1f).fillMaxHeight(), active = mode == ChopMode.EQUAL && sm.equalCount.intValue == n, accent = Tb.EncWhite,
                fontSize = if (tight) 11 else 12, hPad = 1.dp) { sm.setEqualCount(n) }
        }
        // RANDOM: each tap is a new flip (slices cut at random, snapped to hits)
        HwButton(if (tight) "RND" else "RANDOM", Modifier.weight(if (tight) 1.1f else 1.4f).fillMaxHeight(), active = mode == ChopMode.RANDOM, accent = Tb.Tempo,
            fontSize = if (tight) 11 else 12, hPad = 2.dp) {
            sm.randomChop(); Info.toast("RANDOM CHOP", "New slices, cut on the hits. Tap again for another flip.")
        }
        HwButton((if (tight) "TAP" else "TAP-CHOP") + if (sm.lazyRunning.value) " ●" else "", Modifier.weight(if (tight) 1.1f else 1.6f).fillMaxHeight(), active = mode == ChopMode.LAZY, accent = Tb.Warn, fontSize = 12, hPad = 3.dp) {
            sm.setChopMode(if (mode == ChopMode.LAZY) ChopMode.AUTO else ChopMode.LAZY)
        }
        HoldButton("▶", false, Tb.Good, Modifier.width(fixedW).fillMaxHeight()) { sm.togglePreview(it) }
        // STOP: lights while anything on this sampler is sounding (one-shots, loops, the preview); cuts it all
        val sounding by remember { androidx.compose.runtime.derivedStateOf { state.playhead.floatValue >= 0f } }
        HwButton("■", Modifier.width(fixedW).fillMaxHeight(), active = sounding, accent = Tb.Warn, fontSize = 16, hPad = 2.dp) { sm.stopAll() }
    }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Wide (the Thor's top screen): one row. Narrow (single-screen phones / emulator): pitch on one
    // row, WARP · NORMALIZE · PLAY on the next, so nothing is pushed off the edge.
    val narrow = maxWidth < 720.dp
    val padColor = Tb.Pads[sm.selected.intValue % Tb.Pads.size]
    @Composable fun androidx.compose.foundation.layout.RowScope.pitchGroup() {
        // what PITCH − / + moves: the selected pad, or ALL pads (the track's TRANSPOSE, on top of each
        // pad's own pitch). ALL shows its amount whenever it isn't 0, so a transposed track is never a surprise.
        val all = sm.pitchAll.value
        val tr = sm.transpose.intValue
        // narrow: the PAD | ALL switch takes what's left of the row, so − / + always fit
        Row((if (narrow) Modifier.weight(1f) else Modifier.width(146.dp)).fillMaxHeight().clip(RoundedCornerShape(10.dp))
            .border(1.dp, Tb.Line, RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            HwButton("PAD ${sm.selected.intValue + 1}", Modifier.weight(1f).fillMaxHeight(), active = !all, accent = padColor, fontSize = 12, hPad = 2.dp) { sm.pitchAll.value = false }
            HwButton(if (tr == 0) "ALL" else String.format(Locale.US, "ALL %+d", tr), Modifier.weight(1f).fillMaxHeight(), active = all,
                accent = Tb.Key, fontSize = 12, hPad = 2.dp) { sm.pitchAll.value = true }
        }
        HwButton("−", Modifier.width(44.dp).fillMaxHeight(), fontSize = 18) { state.nudgeSemis(-1) }
        Text(String.format(Locale.US, "%+d st", if (all) tr else pad.pitch), color = if (all) Tb.Key else Tb.Text, fontFamily = Tb.Mono,
            fontWeight = FontWeight.Black, fontSize = 15.sp,
            modifier = Modifier.width(58.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HwButton("+", Modifier.width(44.dp).fillMaxHeight(), fontSize = 18) { state.nudgeSemis(+1) }
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
        if (!narrow) Box(Modifier.weight(1f))
        HwButton("PAD EDIT", (if (narrow) Modifier.weight(1.2f) else Modifier.width(104.dp)).fillMaxHeight(), active = state.showPadEdit.value,
            accent = padColor, fontSize = 12) { state.showPadEdit.value = !state.showPadEdit.value }
        HwButton(when (pad.mode) { PlayMode.ONE_SHOT -> "ONE SHOT"; PlayMode.GATE -> "NOTE ON"; PlayMode.LOOP -> "LOOP" }, (if (narrow) Modifier.weight(1.2f) else Modifier.width(120.dp)).fillMaxHeight(),
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
    // same type and placement as the drum pads: label top-left (13 sp black), small detail along the bottom.
    // Text colour follows the pad's fill (dark ink or light), so it reads in every theme.
    val numInk = remember(Tb.palette) { TextStyle(color = Tb.palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Black) }
    val numLight = remember(Tb.palette) { numInk.copy(color = if (Tb.palette.light) androidx.compose.ui.graphics.Color(0xFFFBF8F1) else Tb.palette.text) }
    val smallInk = remember(Tb.palette) { TextStyle(color = Tb.palette.ink.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = Tb.Mono) }
    val smallLight = remember(Tb.palette) { smallInk.copy(color = numLight.color.copy(alpha = 0.75f)) }
    val num = numInk; val hint = smallInk                             // 16 LEVELS keeps its look
    // each pad's slice as 28 bars (peak per bar, 0…1), rebuilt only when the sample or the chops change
    val peaks by state.peaks.collectAsState()
    val regionsNow = sm.regions.value
    val bars = remember(peaks, regionsNow) {
        val pk = peaks; val frames = sm.originalFrames
        Array(16) { k ->
            val out = FloatArray(28)
            val reg = regionsNow.getOrNull(k)
            if (pk != null && reg != null && frames > 0) {
                val cols = pk.size / 3
                val c0 = (reg.start.toDouble() / frames * cols).toInt().coerceIn(0, cols - 1)
                val c1 = (reg.end.toDouble() / frames * cols).toInt().coerceIn(c0 + 1, cols)
                var mx = 1e-4f
                for (b in 0 until 28) {
                    val a0 = c0 + (c1 - c0) * b / 28; val a1 = maxOf(a0 + 1, c0 + (c1 - c0) * (b + 1) / 28)
                    var m = 0f
                    for (c in a0 until minOf(a1, cols)) m = maxOf(m, kotlin.math.abs(pk[3 * c]), kotlin.math.abs(pk[3 * c + 1]))
                    out[b] = m; mx = maxOf(mx, m)
                }
                for (b in 0 until 28) out[b] = (out[b] / mx).coerceIn(0f, 1f)
            }
            out
        }
    }

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
        val frames = sm.originalFrames
        val sr = (sm.info?.sampleRate ?: 48000).toFloat()
        val padsNow = sm.pads.value
        for (k in 0 until 16) {
            val col = k % 4; val rowTop = 3 - k / 4
            val o = Offset(col * cw + gap, rowTop * ch + gap); val s = Size(cw - 2 * gap, ch - 2 * gap)
            val c = Tb.Pads[k % Tb.Pads.size]
            val on = active and (1 shl k) != 0
            val empty = k >= count
            val fill = if (empty) Tb.Raised else if (on) c else c.copy(alpha = 0.62f)
            drawRoundRect(fill, o, s, r)
            val lightText = !empty && Tb.on(c) != Tb.palette.ink
            val nStyle = if (empty) num.copy(color = Tb.Dim) else if (lightText) numLight else numInk
            val dStyle = if (lightText) smallLight else smallInk
            if (!empty) {
                // the slice's waveform along the lower part of the pad, in the text colour, brighter while it sounds
                val ink = nStyle.color.copy(alpha = if (on) 0.55f else 0.28f)
                val b = bars[k]
                val top = o.y + s.height * 0.42f; val bot = o.y + s.height - 18.dp.toPx()
                val mid = (top + bot) / 2f; val half = (bot - top) / 2f
                val bw = (s.width - 16.dp.toPx()) / b.size
                drawLine(ink.copy(alpha = ink.alpha * 0.5f), Offset(o.x + 8.dp.toPx(), mid), Offset(o.x + s.width - 8.dp.toPx(), mid), 1f)
                for (i in b.indices) {
                    if (b[i] < 0.03f) continue                                    // silence: just the centre line
                    val h = b[i] * half
                    drawRoundRect(ink, Offset(o.x + 8.dp.toPx() + i * bw + bw * 0.18f, mid - h), Size(bw * 0.64f, 2 * h), CornerRadius(bw * 0.3f))
                }
                if (on) drawRoundRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f), o, s, r, style = Stroke(2.dp.toPx()))
            }
            if (k == sel) drawRoundRect(Tb.Text, o, s, r, style = Stroke(3.dp.toPx()))
            drawTextSafe(tm, "${k + 1}", o + Offset(9.dp.toPx(), 7.dp.toPx()), nStyle)
            if (empty) continue
            val slot = padsNow[k]
            // top-right: how it plays (→ one shot · ▮ note on · ↻ loop), + a mute group
            val mode = when (slot.mode) { PlayMode.ONE_SHOT -> "→"; PlayMode.GATE -> "▮"; PlayMode.LOOP -> "↻" } +
                (if (slot.muteGroup > 0) " G${slot.muteGroup}" else "")
            val ml = tm.measure(mode, dStyle)
            drawText(ml, topLeft = Offset(o.x + s.width - ml.size.width - 8.dp.toPx(), o.y + 8.dp.toPx()))
            // bottom: pitch (left) and the slice length (right)
            val p = slot.pitch
            if (p != 0) drawTextSafe(tm, String.format(Locale.US, "%+d st", p), o + Offset(8.dp.toPx(), s.height - 15.dp.toPx()), dStyle)
            val reg = sm.regions.value.getOrNull(k)
            if (reg != null && frames > 0 && s.width > 70.dp.toPx()) {
                val secs = (reg.end - reg.start) / sr
                val tl = tm.measure(if (secs < 10f) String.format(Locale.US, "%.2fs", secs) else String.format(Locale.US, "%.0fs", secs), dStyle)
                drawText(tl, topLeft = Offset(o.x + s.width - tl.size.width - 8.dp.toPx(), o.y + s.height - 15.dp.toPx()))
            }
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

/** m:ss.s for a time ruler / readout; [resolution] picks the decimals (0.01 → 2, 0.1 → 1, ≥ 1 → none). */
/** The waveform's zoom window, as fractions of the sample: [start, start + span). */
private data class WaveView(val start: Double, val span: Double)
/** A close-up of frames [from, to) from the engine ([min, max, rms] per bucket). */
private class WaveDetail(val from: Long, val to: Long, val data: FloatArray) {
    fun covers(v: WaveView, frames: Long) = from <= (v.start * frames).toLong() && to >= ((v.start + v.span) * frames).toLong() &&
        data.size / 3 >= 1 && (to - from) / (data.size / 3).toDouble() <= v.span * frames / 600.0      // fine enough for the window
}

private fun fmtTime(t: Double, resolution: Double): String {
    val m = (t / 60).toInt(); val sec = t - m * 60
    val dec = when { resolution < 0.01 -> 3; resolution < 0.1 -> 2; resolution < 1.0 -> 1; else -> 0 }
    val sf = when (dec) { 3 -> String.format(Locale.US, "%06.3f", sec); 2 -> String.format(Locale.US, "%05.2f", sec); 1 -> String.format(Locale.US, "%04.1f", sec); else -> String.format(Locale.US, "%02d", sec.toInt()) }
    return if (m > 0 || dec == 0) "$m:$sf" else String.format(Locale.US, when (dec) { 3 -> "%.3fs"; 2 -> "%.2fs"; else -> "%.1fs" }, sec)
}

private fun lerpC(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, t: Float) =
    androidx.compose.ui.graphics.Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

/**
 * The chop's pattern, right under the waveform: one cell per 1/16 step of this sampler track's
 * pattern, each showing the pad(s) that play there in their colours and numbers (the selected pad's
 * hits outlined). Tap a step: the selected pad goes there (tap again: gone), and you hear it.
 * Long-press a step: clear it. SEQ CHOPS writes the chops in their original order and timing (the
 * loop rebuilt as pad hits) to rearrange from; ● REC records your pad playing live.
 */
/** Steps the chop lane shows at once (4 bars); longer patterns page with BarNav. */
private const val CHOP_PAGE = 64

@Composable
private fun ChopPattern(state: AppState, modifier: Modifier) {
    val g = state.groove
    val sm = state.sampler
    val track = state.samplerTrack.intValue
    val clip = g.clips.value[track]
    val steps = com.thorbox.app.groove.Steps.count(clip.lengthTicks)
    val per = minOf(steps, CHOP_PAGE)                 // up to 4 bars on screen; BarNav turns the page
    fun first() = g.pageStart(track, CHOP_PAGE)
    val tm = rememberTextMeasurer(cacheSize = 96)
    val num = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 9.sp, fontWeight = FontWeight.Black) }
    val tel = state.telemetry
    val cellCount = remember { IntArray(CHOP_PAGE) }                  // reused every frame (no allocation while drawing)
    val padsAt = remember { Array(CHOP_PAGE) { IntArray(3) } }
    fun tapStep(step: Int) {
        val pad = sm.selected.intValue
        val had = clip.notes.any { it.note == pad && com.thorbox.app.groove.Steps.stepOf(it, clip.lengthTicks) == step }
        g.stepTap(track, step)
        if (!had) { sm.padDown(pad, 0.8f); sm.padUp(pad) }          // hear what you placed
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        HwButton("SEQ CHOPS", Modifier.width(78.dp).fillMaxHeight(), enabled = sm.regions.value.isNotEmpty(), accent = Tb.Tempo, minHeight = 36.dp, fontSize = 10, hPad = 3.dp) {
            val (t, d) = sm.sequenceChops(); Info.toast(t, d)
        }
        val navGrid = NavGrid(per, 1,
            get = { (g.cursor.intValue - first()).coerceIn(0, per - 1) }, set = { c -> g.cursor.intValue = first() + c },
            down = { c -> if (first() + c < steps) tapStep(first() + c) }, describe = { c -> "STEP ${first() + c + 1}" }, verb = "place pad ${sm.selected.intValue + 1}", tag = "choppattern")
        Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Tb.Void.copy(alpha = 0.55f))
            .navTarget("PATTERN", grid = navGrid, corner = 6f)
            .pointerInput(steps, track, clip) {
                detectTapGestures(
                    onTap = { p -> tapStep((first() + (p.x / size.width * per).toInt()).coerceIn(0, steps - 1)) },
                    onLongPress = { p -> g.clearStep(track, (first() + (p.x / size.width * per).toInt()).coerceIn(0, steps - 1)) },
                )
            }) {
            tel.frame.longValue
            val f0 = first(); val shown = minOf(per, steps - f0)
            val cw = size.width / per; val h = size.height
            val gap = if (cw > 10.dp.toPx()) 1.5f.dp.toPx() else 0.75f.dp.toPx()
            val r = CornerRadius(3.dp.toPx())
            val sel = sm.selected.intValue
            val play = g.stepPosition(track)
            for (s in 0 until shown) {
                val x = s * cw
                drawRoundRect(if (s / 4 % 2 == 0) Tb.Raised else Tb.Panel, Offset(x + gap, gap), Size(cw - 2 * gap, h - 2 * gap), r)
                if (s % 16 == 0 && s > 0) drawLine(Tb.Text.copy(alpha = 0.5f), Offset(x, 2f), Offset(x, h - 2f), 1.5f.dp.toPx())
            }
            // which pads sound on each step on screen (up to 3 shown); cells are page-relative
            val count = cellCount; java.util.Arrays.fill(count, 0)
            for (e in clip.notes) {
                val s = com.thorbox.app.groove.Steps.stepOf(e, clip.lengthTicks) - f0
                if (s !in 0 until shown || count[s] >= 3) continue
                padsAt[s][count[s]++] = e.note
            }
            for (s in 0 until shown) {
                val n = count[s]; if (n == 0) continue
                val x = s * cw + gap; val w = cw - 2 * gap
                val sh = (h - 2 * gap) / n
                for (k in 0 until n) {
                    val pad = padsAt[s][k]
                    drawRoundRect(Tb.Pads[pad % Tb.Pads.size], Offset(x, gap + k * sh), Size(w, sh - if (k < n - 1) 1f else 0f), r)
                    if (pad == sel) drawRoundRect(Tb.Text, Offset(x, gap + k * sh), Size(w, sh), r, style = Stroke(1.5f.dp.toPx()))
                }
                if (n == 1 && cw >= 14.dp.toPx()) {
                    val t = tm.measure("${padsAt[s][0] + 1}", num)
                    if (t.size.width < w) drawText(t, topLeft = Offset(x + (w - t.size.width) / 2f, (h - t.size.height) / 2f))
                }
            }
            if (play - f0 in 0 until shown) drawRoundRect(Tb.Text.copy(alpha = 0.35f), Offset((play - f0) * cw + gap, gap), Size(cw - 2 * gap, h - 2 * gap), r)
            com.thorbox.app.input.Nav.entered.intValue
            if (com.thorbox.app.input.Nav.enteredTag() == "choppattern") {
                val c = (g.cursor.intValue - f0).coerceIn(0, per - 1)
                drawRoundRect(Tb.Text, Offset(c * cw + 1f, 1f), Size(cw - 2f, h - 2f), r, style = Stroke(2.dp.toPx()))
            }
        }
        HwButton("●", Modifier.width(40.dp).fillMaxHeight(), active = g.recording.value, accent = Tb.Warn, minHeight = 36.dp, fontSize = 13, hPad = 2.dp) { g.toggleRecord() }
        HwButton("✕", Modifier.width(40.dp).fillMaxHeight(), minHeight = 36.dp, fontSize = 12, hPad = 2.dp) { g.clear(track) }
        // swing lives here now (the groove row made way for the waveform): drag it, double-tap = 50 %
        val sw = g.groove.value.swing
        DragValue("SWING", "${(sw * 100).roundToInt()}%", Tb.Tempo, Modifier.width(64.dp).fillMaxHeight(), pxPerUnit = 600f,
            onDelta = { g.setSwing(g.groove.value.swing + it * 0.25f) }, onReset = { g.setSwing(0.5f) }, compact = false)
    }
}
