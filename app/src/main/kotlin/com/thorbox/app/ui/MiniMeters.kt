// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.engine.NativeEngine
import java.util.Locale
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A meter strip for the top screen: the master bus at a glance while you work.
 *   PEAK    L / R bars in dBFS with 1.5 s peak-hold ticks (red past −1 dB)
 *   RMS     momentary (400 ms) and short-term (3 s) RMS level of the master, in dBFS
 *   SPEC    48-band spectrum with falling peak caps
 *   WAVE    the last ~5 s of the master, scrolling
 *   SCOPE   the current waveform
 * One canvas, redrawn every frame, nothing allocated per frame. Toggle it in SETTINGS → METERS.
 */
@Composable
fun MiniMeters(state: AppState, modifier: Modifier = Modifier) {
    val tel = state.telemetry
    val g = state.groove
    val viz = state.viz
    val tm = rememberTextMeasurer()
    val label = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val value = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = Tb.Mono) }
    val st = remember { MeterState() }
    val path = remember { Path() }

    Canvas(modifier.height(34.dp)) {
        tel.frame.longValue
        val now = tel.timeSec
        val dt = (now - st.lastT).coerceIn(0f, 0.1f); st.lastT = now

        // ---- measure ----
        val pl = g.meters[2 * NativeEngine.STRIP_MASTER]; val pr = g.meters[2 * NativeEngine.STRIP_MASTER + 1]
        st.hold(0, pl, now); st.hold(1, pr, now)
        var ms = 0f
        for (v in viz.wave) ms += v * v
        ms /= viz.wave.size
        if (dt > 0f) {
            st.mom += (ms - st.mom) * (1f - exp(-dt / 0.4f))
            st.short += (ms - st.short) * (1f - exp(-dt / 3f))
            st.pushWave(viz.wave)
        }

        val pad = 4.dp.toPx()
        val h = size.height
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(Tb.Void.copy(alpha = 0.85f), cornerRadius = CornerRadius(8.dp.toPx()))
        val w = size.width
        // column widths
        val wPeak = w * 0.22f; val wLoud = w * 0.15f; val wSpec = w * 0.29f; val wWave = w * 0.22f
        var x = pad

        // ---- PEAK ----
        drawText(tm.measure("PEAK", label), topLeft = Offset(x, pad - 2f))
        val barX = x + 30.dp.toPx(); val barW = wPeak - 30.dp.toPx() - pad * 2
        val barH = (h - pad * 3) / 2f
        for (c in 0..1) {
            val y = pad + c * (barH + pad)
            val v = if (c == 0) pl else pr
            drawRoundRect(Tb.Raised, Offset(barX, y), Size(barW, barH), r)
            val f = dbFrac(v)
            drawRoundRect(if (v >= 0.891f) Tb.Warn else Tb.Good, Offset(barX, y), Size(barW * f, barH), r)
            val hf = dbFrac(st.peak[c])
            drawRect(if (st.peak[c] >= 0.891f) Tb.Warn else Tb.Text, Offset(barX + barW * hf - 1.5f, y), Size(3f, barH))
        }
        val pk = max(st.peak[0], st.peak[1])
        drawText(tm.measure(dbText(pk), value), topLeft = Offset(x, h - pad - 13.sp.toPx()))
        x += wPeak

        // ---- RMS ----
        divider(x); x += pad
        drawText(tm.measure("RMS", label), topLeft = Offset(x, pad - 2f))
        drawText(tm.measure("${dbText(sqrt(st.mom))} M", value), topLeft = Offset(x + 26.dp.toPx(), pad - 3f))
        drawText(tm.measure("${dbText(sqrt(st.short))} S", value.copy(color = Tb.Muted)), topLeft = Offset(x + 26.dp.toPx(), h / 2f - 1f))
        x += wLoud - pad

        // ---- SPECTRUM ----
        divider(x); x += pad
        val sw = wSpec - pad * 2
        val n = viz.bands.size
        val bw = sw / n
        for (i in 0 until n) {
            val bh = (h - pad * 2) * viz.bands[i].coerceIn(0f, 1f)
            val cx = x + i * bw
            drawRect(Tb.Tempo.copy(alpha = 0.85f), Offset(cx, h - pad - bh), Size(max(1f, bw - 1f), bh))
            val ph = (h - pad * 2) * viz.peaks[i].coerceIn(0f, 1f)
            drawRect(Tb.Text, Offset(cx, h - pad - ph - 1f), Size(max(1f, bw - 1f), 1.5f))
        }
        x += sw + pad

        // ---- WAVE (history) ----
        divider(x); x += pad
        val ww = wWave - pad * 2
        val mid = h / 2f
        val cols = MeterState.HIST
        val stepX = ww / cols
        for (k in 0 until cols) {
            val i = (st.histHead + k) % cols
            val mn = st.histMin[i]; val mx = st.histMax[i]
            val cx = x + k * stepX
            drawLine(if (max(-mn, mx) > 0.95f) Tb.Warn else Tb.Key, Offset(cx, mid - mx * (mid - pad)), Offset(cx, mid - mn * (mid - pad) + 1f), max(1f, stepX))
        }
        x += ww + pad

        // ---- SCOPE ----
        divider(x); x += pad
        val scW = w - x - pad
        path.reset()
        val m = 256
        val step = viz.wave.size / m
        for (i in 0 until m) {
            val px = x + scW * i / (m - 1)
            val py = mid - viz.wave[i * step].coerceIn(-1f, 1f) * (mid - pad)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, Tb.Good, style = Stroke(1.5f))
    }
}

private fun DrawScope.divider(x: Float) = drawLine(com.thorbox.app.ui.Tb.Line, Offset(x, 4f), Offset(x, size.height - 4f), 1f)

/** −60 dB … 0 dB → 0 … 1. */
private fun dbFrac(lin: Float): Float {
    if (lin <= 0.001f) return 0f
    return ((20f * log10(lin) + 60f) / 60f).coerceIn(0f, 1f)
}

private fun dbText(lin: Float): String =
    if (lin <= 0.0001f) "−∞ dB" else String.format(Locale.US, "%.1f dB", 20f * log10(lin))

private class MeterState {
    var lastT = 0f
    var mom = 0f
    var short = 0f
    val peak = FloatArray(2)
    private val peakAt = FloatArray(2)
    fun hold(c: Int, v: Float, now: Float) {
        if (v >= peak[c] || now - peakAt[c] > 1.5f) { peak[c] = v; peakAt[c] = now }
    }
    val histMin = FloatArray(HIST)
    val histMax = FloatArray(HIST)
    var histHead = 0
    private var acc = 0
    /** One column per ~2 frames (~5 s across the strip). */
    fun pushWave(wave: FloatArray) {
        if (++acc < 2) return
        acc = 0
        var mn = 0f; var mx = 0f
        for (v in wave) { if (v < mn) mn = v; if (v > mx) mx = v }
        histMin[histHead] = mn.coerceAtLeast(-1f); histMax[histHead] = mx.coerceAtMost(1f)
        histHead = (histHead + 1) % HIST
    }
    companion object { const val HIST = 150 }
}
