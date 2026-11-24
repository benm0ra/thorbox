// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.engine.NativeEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * ANALYZER: the master bus, measured, the way a good metering plugin does it while you mix.
 *   SPECTRUM   20 Hz – 20 kHz curve with grid + peak hold; the dominant note ("A1 · 55 Hz", for
 *              tuning 808s to the key); touch anywhere for that frequency / note / level
 *   LOUDNESS   R128 short-term (big), momentary, integrated + time, sample peaks, a streaming
 *              target mark; RESET restarts integrated
 *   STEREO     goniometer (mid up, side across) + correlation (−1 out of phase … +1 mono) and width
 *   WAVEFORM   the last ~10 s scrolling, coloured by low / mid / high balance
 *   TRACKS     level of every track
 * Black panels, hairlines, mono numbers, colour only where it means something.
 */

private const val NB = 160                      // spectrum resolution (log-spaced bands, 20 Hz–20 kHz)
private const val F_LO = 20f
private const val F_HI = 20000f
private val NOTE = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun xOfHz(hz: Float) = (ln(hz / F_LO) / ln(F_HI / F_LO)).coerceIn(0f, 1f)
private fun hzOfX(x: Float) = F_LO * (F_HI / F_LO).pow(x.coerceIn(0f, 1f))
private fun noteOf(hz: Float): Pair<String, Int> {
    val midi = 69f + 12f * log2(hz / 440f)
    val n = midi.roundToInt()
    val cents = ((midi - n) * 100f).roundToInt()
    return "${NOTE[Math.floorMod(n, 12)]}${n / 12 - 1}" to cents
}
private fun hzLabel(f: Float) = if (f >= 1000f) String.format(Locale.US, "%.1fk", f / 1000f) else "${f.roundToInt()}"
private fun lufsText(v: Float) = if (v <= -99f) "—" else String.format(Locale.US, "%.1f", v)

/** All analyzer state: preallocated, updated once per UI frame by whichever panel draws first. */
private class AnalyzerData {
    val raw = FloatArray(NB); val smooth = FloatArray(NB); val hold = FloatArray(NB); val holdAge = FloatArray(NB)
    val wave = FloatArray(16); val energy = FloatArray(5)
    val loud = FloatArray(6) { -100f }
    val mid = FloatArray(1024); val side = FloatArray(1024)
    var corr = 1f; var width = 0f
    var noteHz = 0f; var noteDb = -100f; private var noteStable = 0f
    val histPk = FloatArray(HIST); val histLo = FloatArray(HIST); val histMd = FloatArray(HIST); val histHi = FloatArray(HIST)
    var histHead = 0
    private var lastFrame = -1L; private var lastT = 0f
    var touchX = -1f

    fun update(frame: Long, t: Float) {
        if (frame == lastFrame) return
        lastFrame = frame
        val dt = (t - lastT).coerceIn(0f, 0.1f); lastT = t
        NativeEngine.visual(raw, wave, energy)
        NativeEngine.loudness(loud)
        val n = NativeEngine.stereo(mid, side)
        val fall = 0.02f.pow(dt)
        for (i in 0 until NB) {
            smooth[i] = if (raw[i] > smooth[i]) smooth[i] + (raw[i] - smooth[i]) * 0.6f else max(raw[i], smooth[i] * fall + raw[i] * (1f - fall))
            if (smooth[i] >= hold[i]) { hold[i] = smooth[i]; holdAge[i] = 0f } else { holdAge[i] += dt; if (holdAge[i] > 1.5f) hold[i] = max(smooth[i], hold[i] - dt * 0.25f) }
        }
        // stereo: correlation + width from mid/side (L = m + s, R = m − s)
        var lr = 0.0; var ll = 0.0; var rr = 0.0; var ms2 = 0.0; var ss2 = 0.0; var pk = 0f
        for (i in 0 until n) {
            val l = mid[i] + side[i]; val r = mid[i] - side[i]
            lr += l * r; ll += l * l; rr += r * r; ms2 += mid[i] * mid[i]; ss2 += side[i] * side[i]
            if (i >= n - 800) pk = max(pk, abs(mid[i]) + abs(side[i]))
        }
        val c = if (ll > 1e-9 && rr > 1e-9) (lr / sqrt(ll * rr)).toFloat() else 1f
        val k = 1f - 0.05f.pow(dt)
        corr += (c - corr) * k
        width += ((if (ms2 > 1e-9) sqrt(ss2 / ms2).toFloat() else 0f).coerceIn(0f, 2f) - width) * k
        // dominant note: hold it steady (no flicker between frames)
        val hz = energy[3]; val db = energy[4]
        if (hz > 0f && (noteHz == 0f || abs(12f * log2(hz / noteHz)) > 0.4f)) { noteStable += dt; if (noteStable > 0.08f) { noteHz = hz; noteStable = 0f } }
        else if (hz > 0f) { noteHz += (hz - noteHz) * 0.3f; noteStable = 0f }
        noteDb = if (hz > 0f) db else max(-100f, noteDb - dt * 30f)
        if (noteDb < -55f) noteHz = 0f
        // waveform history: one column per frame
        histHead = (histHead + 1) % HIST
        histPk[histHead] = pk.coerceAtMost(1.5f); histLo[histHead] = energy[0]; histMd[histHead] = energy[1]; histHi[histHead] = energy[2]
    }

    companion object { const val HIST = 600 }   // ~10 s at 60 fps
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Analyzer(state: AppState, modifier: Modifier = Modifier) {
    val d = remember { AnalyzerData() }
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 64)
    val st = remember(Tb.palette) { AnalyzerStyles() }
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- SPECTRUM ----
            Canvas(Modifier.weight(2.2f).fillMaxHeight().pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> d.touchX = ev.x
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> d.touchX = -1f
                }
                true
            }) {
                tel.frame.longValue; d.update(tel.frame.longValue, tel.timeSec)
                spectrum(d, tm, st)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ---- LOUDNESS ----
                Box(Modifier.weight(1.15f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize()) { tel.frame.longValue; d.update(tel.frame.longValue, tel.timeSec); loudness(d, tm, st) }
                    HwButton("RESET", Modifier.align(Alignment.TopEnd).padding(6.dp).height(26.dp), minHeight = 24.dp, fontSize = 10, hPad = 8.dp) { NativeEngine.resetLoudness() }
                }
                // ---- STEREO ----
                Canvas(Modifier.weight(1f).fillMaxWidth()) { tel.frame.longValue; d.update(tel.frame.longValue, tel.timeSec); stereo(d, tm, st) }
            }
        }
        Row(Modifier.height(104.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.weight(2.2f).fillMaxHeight()) { tel.frame.longValue; d.update(tel.frame.longValue, tel.timeSec); history(d, tm, st) }
            Canvas(Modifier.weight(1f).fillMaxHeight()) { tel.frame.longValue; tracks(state, tm, st) }
        }
    }
}

private class AnalyzerStyles {
    val label = TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    val axis = TextStyle(color = Tb.Dim, fontSize = 9.sp, fontFamily = Tb.Mono)
    val value = TextStyle(color = Tb.Text, fontSize = 12.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold)
    val big = TextStyle(color = Tb.Text, fontSize = 34.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black)
    val note = TextStyle(color = Tb.Key, fontSize = 14.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black)
}

private fun DrawScope.panel(title: String, tm: TextMeasurer, st: AnalyzerStyles) {
    val r = CornerRadius(10.dp.toPx())
    drawRoundRect(Tb.Void.copy(alpha = 0.92f), Offset.Zero, size, r)
    drawRoundRect(Tb.Line, Offset.Zero, size, r, style = Stroke(1.dp.toPx()))
    drawTextSafe(tm, title, Offset(10.dp.toPx(), 7.dp.toPx()), st.label)
}

private val specPath = Path()
private val specFill = Path()

private fun DrawScope.spectrum(d: AnalyzerData, tm: TextMeasurer, st: AnalyzerStyles) {
    panel("SPECTRUM", tm, st)
    val pl = 34.dp.toPx(); val pr = 10.dp.toPx(); val pt = 26.dp.toPx(); val pb = 18.dp.toPx()
    val w = size.width - pl - pr; val h = size.height - pt - pb
    val hair = 1.dp.toPx()
    fun yOf(v: Float) = pt + (1f - v.coerceIn(0f, 1f)) * h            // v: (dB + 72) / 72
    // grid: dB lines, frequency lines
    for (db in intArrayOf(0, -12, -24, -36, -48, -60)) {
        val y = yOf((db + 72f) / 72f)
        drawLine(Tb.Line.copy(alpha = if (db == 0) 0.9f else 0.45f), Offset(pl, y), Offset(pl + w, y), hair)
        drawTextSafe(tm, "$db", Offset(6.dp.toPx(), y - 6.dp.toPx()), st.axis)
    }
    for (f in floatArrayOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
        val x = pl + xOfHz(f) * w
        drawLine(Tb.Line.copy(alpha = 0.45f), Offset(x, pt), Offset(x, pt + h), hair)
        drawTextSafe(tm, hzLabel(f), Offset(x - 8.dp.toPx(), pt + h + 3.dp.toPx()), st.axis)
    }
    // peak hold (thin), then the live curve filled under
    specPath.reset()
    for (i in 0 until NB) { val x = pl + (i + 0.5f) / NB * w; val y = yOf(d.hold[i]); if (i == 0) specPath.moveTo(x, y) else specPath.lineTo(x, y) }
    drawPath(specPath, Tb.Muted.copy(alpha = 0.7f), style = Stroke(hair))
    specPath.reset(); specFill.reset()
    specFill.moveTo(pl, pt + h)
    for (i in 0 until NB) {
        val x = pl + (i + 0.5f) / NB * w; val y = yOf(d.smooth[i])
        if (i == 0) specPath.moveTo(x, y) else specPath.lineTo(x, y)
        specFill.lineTo(x, y)
    }
    specFill.lineTo(pl + w, pt + h); specFill.close()
    drawPath(specFill, Tb.Tempo.copy(alpha = 0.16f))
    drawPath(specPath, Tb.Tempo, style = Stroke(1.6f * hair))
    // dominant note, top right
    if (d.noteHz > 0f) {
        val (n, c) = noteOf(d.noteHz)
        val t = String.format(Locale.US, "%s %+d¢ · %s Hz", n, c, if (d.noteHz < 1000f) String.format(Locale.US, "%.1f", d.noteHz) else hzLabel(d.noteHz))
        val m = tm.measure(t, st.note)
        drawTextSafe(tm, t, Offset(size.width - pr - m.size.width, 5.dp.toPx()), st.note)
        val x = pl + xOfHz(d.noteHz) * w
        drawLine(Tb.Key.copy(alpha = 0.6f), Offset(x, pt), Offset(x, pt + h), hair)
    }
    // touch readout
    if (d.touchX >= 0f) {
        val fx = ((d.touchX - pl) / w).coerceIn(0f, 1f)
        val hz = hzOfX(fx)
        val bi = (fx * NB).toInt().coerceIn(0, NB - 1)
        val db = d.smooth[bi] * 72f - 72f
        val (n, c) = noteOf(hz)
        val x = pl + fx * w
        drawLine(Tb.Text, Offset(x, pt), Offset(x, pt + h), hair)
        val t = String.format(Locale.US, "%s Hz · %s %+d¢ · %.0f dB", hzLabel(hz), n, c, db)
        val m = tm.measure(t, st.value)
        val tx = (x + 8.dp.toPx()).coerceAtMost(size.width - pr - m.size.width - 8.dp.toPx())
        drawRoundRect(Tb.Void, Offset(tx - 4.dp.toPx(), pt + 4.dp.toPx()), Size(m.size.width + 8.dp.toPx(), m.size.height + 4.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawTextSafe(tm, t, Offset(tx, pt + 6.dp.toPx()), st.value)
    }
}

private fun DrawScope.loudness(d: AnalyzerData, tm: TextMeasurer, st: AnalyzerStyles) {
    panel("LUFS", tm, st)
    val pad = 10.dp.toPx()
    val s = d.loud[1]; val m = d.loud[0]; val i = d.loud[2]
    val peak = max(d.loud[3], d.loud[4])
    // big short-term number
    // the big number steps down a size rather than running under RESET (top-right) on a narrow panel
    var big = st.big
    val room = size.width - pad - 76.dp.toPx()
    while (tm.measure(lufsText(s), big).size.width > room && big.fontSize.value > 16f) big = big.copy(fontSize = (big.fontSize.value - 2f).sp)
    drawTextSafe(tm, lufsText(s), Offset(pad, 22.dp.toPx()), big)
    drawTextSafe(tm, "SHORT-TERM", Offset(pad, 64.dp.toPx()), st.label)
    // bar: −40 … 0 LUFS with a −14 (streaming) mark
    val by = 80.dp.toPx(); val bh = 8.dp.toPx(); val bw = size.width - 2 * pad
    fun xOf(l: Float) = pad + ((l + 40f) / 40f).coerceIn(0f, 1f) * bw
    drawRoundRect(Tb.Raised, Offset(pad, by), Size(bw, bh), CornerRadius(4.dp.toPx()))
    if (s > -99f) drawRoundRect(if (s > -9f) Tb.Warn else Tb.Good, Offset(pad, by), Size(xOf(s) - pad, bh), CornerRadius(4.dp.toPx()))
    if (m > -99f) drawLine(Tb.Text, Offset(xOf(m), by - 2.dp.toPx()), Offset(xOf(m), by + bh + 2.dp.toPx()), 2.dp.toPx())
    drawLine(Tb.Key, Offset(xOf(-14f), by + bh), Offset(xOf(-14f), by + bh + 6.dp.toPx()), 1.5f.dp.toPx())
    drawTextSafe(tm, "−14", Offset(xOf(-14f) - 8.dp.toPx(), by + bh + 6.dp.toPx()), st.axis)
    // the rest: one line each, label left, value right (fits any panel width)
    val secs = d.loud[5].toInt()
    val rows = listOf(
        "MOMENTARY" to lufsText(m),
        "INTEGRATED ${secs / 60}:${String.format(Locale.US, "%02d", secs % 60)}" to lufsText(i),
        "PEAK dBFS" to (if (peak <= -99f) "—" else String.format(Locale.US, "%.1f", peak)),
    )
    val lineH = 16.dp.toPx()
    var y = (size.height - pad - rows.size * lineH).coerceAtLeast(by + bh + 22.dp.toPx())
    for ((lab0, v) in rows) {
        val vs = if (lab0.startsWith("PEAK") && peak > -1f) st.value.copy(color = Tb.Warn) else st.value
        val w = tm.measure(v, vs).size.width
        // short labels when the long ones would run into the value
        val lab = if (tm.measure(lab0, st.label).size.width + w + 8.dp.toPx() > size.width - 2 * pad)
            lab0.replace("MOMENTARY", "MOM").replace("INTEGRATED", "INT").replace("PEAK dBFS", "PEAK") else lab0
        drawTextSafe(tm, lab, Offset(pad, y + 2.dp.toPx()), st.label)
        drawTextSafe(tm, v, Offset(size.width - pad - w, y), vs)
        y += lineH
    }
}

private fun DrawScope.stereo(d: AnalyzerData, tm: TextMeasurer, st: AnalyzerStyles) {
    panel("STEREO", tm, st)
    val pad = 10.dp.toPx(); val hair = 1.dp.toPx()
    // goniometer on the left: mid up, side across, in a diamond
    val gs = (size.height - 30.dp.toPx()).coerceAtMost(size.width * 0.5f)
    val c = Offset(pad + gs / 2f, 24.dp.toPx() + gs / 2f)
    val rad = gs / 2f
    drawLine(Tb.Line, Offset(c.x, c.y - rad), Offset(c.x, c.y + rad), hair)
    drawLine(Tb.Line, Offset(c.x - rad, c.y), Offset(c.x + rad, c.y), hair)
    drawLine(Tb.Line.copy(alpha = 0.5f), Offset(c.x - rad * 0.7f, c.y - rad * 0.7f), Offset(c.x + rad * 0.7f, c.y + rad * 0.7f), hair)   // L
    drawLine(Tb.Line.copy(alpha = 0.5f), Offset(c.x + rad * 0.7f, c.y - rad * 0.7f), Offset(c.x - rad * 0.7f, c.y + rad * 0.7f), hair)   // R
    drawTextSafe(tm, "L", Offset(c.x - rad * 0.75f - 8.dp.toPx(), c.y - rad * 0.75f - 10.dp.toPx()), st.axis)
    drawTextSafe(tm, "R", Offset(c.x + rad * 0.75f + 2.dp.toPx(), c.y - rad * 0.75f - 10.dp.toPx()), st.axis)
    val g = rad * 1.4f
    val col = Tb.EncGreen.copy(alpha = 0.55f)
    var i = 0
    while (i < d.mid.size) {
        val x = (c.x + d.side[i] * g).coerceIn(c.x - rad, c.x + rad)
        val y = (c.y - d.mid[i] * g).coerceIn(c.y - rad, c.y + rad)
        drawRect(col, Offset(x, y), Size(1.5f * hair, 1.5f * hair))
        i += 2
    }
    // correlation + width on the right
    val rx = pad + gs + 14.dp.toPx(); val rw = size.width - rx - pad
    drawTextSafe(tm, "CORR", Offset(rx, 24.dp.toPx()), st.label)
    val by = 40.dp.toPx(); val bh = 8.dp.toPx()
    drawRoundRect(Tb.Raised, Offset(rx, by), Size(rw, bh), CornerRadius(4.dp.toPx()))
    drawLine(Tb.Line, Offset(rx + rw / 2f, by - 3.dp.toPx()), Offset(rx + rw / 2f, by + bh + 3.dp.toPx()), hair)
    val cx = rx + (d.corr + 1f) / 2f * rw
    drawRoundRect(if (d.corr < 0f) Tb.Warn else Tb.Good, Offset(minOf(cx, rx + rw / 2f), by), Size(abs(cx - (rx + rw / 2f)), bh), CornerRadius(4.dp.toPx()))
    drawTextSafe(tm, String.format(Locale.US, "%+.2f", d.corr), Offset(rx, by + bh + 4.dp.toPx()), if (d.corr < 0f) st.value.copy(color = Tb.Warn) else st.value)
    drawTextSafe(tm, "WIDTH", Offset(rx, by + bh + 26.dp.toPx()), st.label)
    drawTextSafe(tm, "${(d.width * 100).roundToInt()}%" + if (d.width < 0.03f) "  MONO" else "", Offset(rx, by + bh + 38.dp.toPx()), st.value)
}

private fun DrawScope.history(d: AnalyzerData, tm: TextMeasurer, st: AnalyzerStyles) {
    panel("WAVEFORM", tm, st)
    val pad = 8.dp.toPx(); val top = 20.dp.toPx()
    val w = size.width - 2 * pad; val h = size.height - top - pad; val mid = top + h / 2f
    val n = AnalyzerData.HIST
    val colW = w / n
    val lo = Tb.EncOrange; val md = Tb.EncGreen; val hi = Tb.EncBlue
    for (k in 0 until n) {
        val idx = (d.histHead + 1 + k) % n
        val a = d.histPk[idx]
        if (a < 0.002f) continue
        // colour from the band balance: bass → orange, mids → green, highs → blue
        val sum = d.histLo[idx] + d.histMd[idx] + d.histHi[idx] + 1e-3f
        val wl = d.histLo[idx] / sum; val wm = d.histMd[idx] / sum; val wh = d.histHi[idx] / sum
        val c = Color(lo.red * wl + md.red * wm + hi.red * wh, lo.green * wl + md.green * wm + hi.green * wh, lo.blue * wl + md.blue * wm + hi.blue * wh)
        val y = (a.coerceAtMost(1f)) * h / 2f
        drawRect(if (a > 0.99f) Tb.Warn else c, Offset(pad + k * colW, mid - y), Size(max(colW, 1f), 2f * y))
    }
    drawLine(Tb.Line, Offset(pad, mid), Offset(pad + w, mid), 1.dp.toPx())
}

private fun DrawScope.tracks(state: AppState, tm: TextMeasurer, st: AnalyzerStyles) {
    panel("TRACKS", tm, st)
    val g = state.groove
    val pad = 8.dp.toPx(); val top = 20.dp.toPx(); val labH = 12.dp.toPx()
    val live = (0 until com.thorbox.model.TRACK_COUNT).filter { state.trackKind(it) != com.thorbox.model.TrackKind.EMPTY } + NativeEngine.STRIP_MASTER
    val cw = (size.width - 2 * pad) / live.size
    val h = size.height - top - pad - labH
    live.forEachIndexed { k, t ->
        val pk = max(g.meters[2 * t], g.meters[2 * t + 1])
        val db = if (pk > 1e-5f) 20f * log10(pk) else -100f
        val v = ((db + 60f) / 60f).coerceIn(0f, 1f)
        val x = pad + k * cw + cw * 0.2f; val bw = cw * 0.6f
        drawRect(Tb.Raised, Offset(x, top), Size(bw, h))
        drawRect(if (db > -1f) Tb.Warn else if (t == NativeEngine.STRIP_MASTER) Tb.Text else Tb.Pads[t % Tb.Pads.size], Offset(x, top + h * (1f - v)), Size(bw, h * v))
        val name = if (t == NativeEngine.STRIP_MASTER) "MST" else g.channels.value.getOrNull(t)?.name?.take(3) ?: "T${t + 1}"
        val m = tm.measure(name, st.axis)
        drawTextSafe(tm, name, Offset(x + bw / 2f - m.size.width / 2f, top + h + 2.dp.toPx()), st.axis)
    }
}
