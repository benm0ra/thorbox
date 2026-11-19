// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.viz.VizData
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class VizMode(val label: String) { SPECTRA("SPECTRA"), ORBIT("ORBIT"), PLASMA("PLASMA") }

/*
 * VIZ view: full-screen, music-reactive visuals for when the master is playing.
 * Original designs in the spirit of 2000s desktop media-player visualizers: glossy spectrum bars
 * with hanging peak caps, a circular oscilloscope with echo trails, and a flowing plasma field.
 * Tap anywhere to change mode. Track info floats in a glass chip.
 */
@Composable
fun VizScreen(state: AppState) {
    val mode = state.vizMode.value
    Box(
        Modifier.fillMaxSize().background(Tb.Void)
            .pointerInput(Unit) { detectTapGestures(onTap = { state.cycleViz() }) }
    ) {
        when (mode) {
            VizMode.SPECTRA -> Spectra(state, Modifier.fillMaxSize())
            VizMode.ORBIT -> Orbit(state, Modifier.fillMaxSize())
            VizMode.PLASMA -> Plasma(state, Modifier.fillMaxSize())
        }
        // Info chip: what's playing, in glass
        val g = state.groove
        val s by state.sample.collectAsState()
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).height(44.dp).glass(state, 22.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(mode.label, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
            Text(String.format(Locale.US, "%.1f BPM", g.groove.value.bpm), color = Tb.Tempo, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Text(state.lockKey().label().uppercase(), color = Tb.Key, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 13.sp)
            s?.let { Text(it.name.substringBeforeLast('.'), color = Tb.Muted, fontSize = 12.sp, maxLines = 1) }
        }
        // Look settings live here: glass level (FULL / SUBTLE / OFF) and visualizer auto-start.
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HwButton("GLASS ${state.glass.value.name}", Modifier.glass(state, 8.dp), fontSize = 11) { state.cycleGlass() }
            HwButton(if (state.vizAuto.value) "AUTO ON" else "AUTO OFF", Modifier.glass(state, 8.dp), active = state.vizAuto.value, accent = Tb.EncBlue, fontSize = 11) {
                state.vizAuto.value = !state.vizAuto.value
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(14.dp), horizontalAlignment = Alignment.End) {
            Text("tap to change", color = Tb.Dim, fontSize = 10.sp)
        }
    }
}

// Colour along the spectrum: deep blue at the bottom, through teal and green to amber and hot red.
private val HEAT = listOf(Color(0xFF2F7BFF), Color(0xFF2FD1C5), Color(0xFF5BD75B), Color(0xFFFFB000), Color(0xFFFF5A36))
private fun heat(t: Float): Color {
    val x = t.coerceIn(0f, 1f) * (HEAT.size - 1)
    val i = x.toInt().coerceAtMost(HEAT.size - 2)
    return androidx.compose.ui.graphics.lerp(HEAT[i], HEAT[i + 1], x - i)
}

@Composable
private fun Spectra(state: AppState, modifier: Modifier) {
    val v = state.viz
    val tel = state.telemetry
    Box(modifier.drawWithCache {
        // Everything that depends only on size is built here once, not per frame.
        val n = VizData.BANDS
        val floor = size.height * 0.72f
        val maxH = floor - size.height * 0.12f
        val gap = size.width * 0.004f
        val w = (size.width - gap * (n + 1)) / n
        val r = CornerRadius(w * 0.18f)
        val bar = Brush.verticalGradient(listOf(Color(0xFFFF5A36), Color(0xFFFFB000), Color(0xFF5BD75B), Color(0xFF2FD1C5), Color(0xFF2F7BFF)),
            startY = floor - maxH, endY = floor)
        val glow = Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF2F7BFF).copy(alpha = 0.35f)), startY = floor - 80f, endY = floor)
        val fade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = floor + 3f, endY = size.height)
        val gloss = Color.White.copy(alpha = 0.18f)
        val capR = CornerRadius(2f)
        onDrawBehind {
            tel.frame.longValue
            drawRect(glow, Offset(0f, floor - 80f), Size(size.width, 80f), alpha = (0.25f + 0.75f * v.beat).coerceIn(0f, 1f))
            for (i in 0 until n) {
                val x = gap + i * (w + gap)
                val h = max(2f, v.bands[i] * maxH)
                drawRoundRect(bar, Offset(x, floor - h), Size(w, h), r)                   // bar
                drawRoundRect(gloss, Offset(x, floor - h), Size(w * 0.33f, h), r)         // gloss
                drawRoundRect(heat(v.bands[i]).copy(alpha = 0.30f), Offset(x, floor + 3f), Size(w, h * 0.45f), r)   // reflection
                drawRoundRect(Tb.Text, Offset(x, floor - v.peaks[i] * maxH - 6f), Size(w, 4f), capR)            // peak cap
            }
            drawRect(fade, Offset(0f, floor + 3f), Size(size.width, size.height - floor - 3f))
        }
    })
}

@Composable
private fun Orbit(state: AppState, modifier: Modifier) {
    val v = state.viz
    val tel = state.telemetry
    val path = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = min(size.width, size.height) * (0.26f + 0.06f * v.bass + 0.05f * v.beat)
        val hueShift = (GlassClock.time * 0.05f) % 1f
        // echo trails, oldest first
        for (k in VizData.TRAIL - 1 downTo 0) {
            val idx = (v.trailHead - k + VizData.TRAIL) % VizData.TRAIL
            val buf = v.trail[idx]
            val grow = 1f + k * 0.07f
            ring(path, buf, c, base * grow, base * 0.55f)
            val col = heat((hueShift + k * 0.11f) % 1f).copy(alpha = (1f - k / VizData.TRAIL.toFloat()) * 0.55f)
            drawPath(path, col, style = Stroke((if (k == 0) 4f else 2f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // live ring
        ring(path, v.wave, c, base, base * 0.6f, step = VizData.WAVE / 256)
        drawPath(path, Tb.Text, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // core: pulses on beats (solid circle with alpha: no per-frame gradient allocation)
        drawCircle(Tb.EncOrange, base * 0.45f, c, alpha = (0.08f + 0.35f * v.beat).coerceIn(0f, 1f))
    }
}

private fun DrawScope.ring(path: Path, buf: FloatArray, c: Offset, radius: Float, amp: Float, step: Int = 1) {
    path.reset()
    val n = buf.size / step
    for (i in 0..n) {
        val s = buf[(i % n) * step]
        val a = 2f * PI.toFloat() * i / n - PI.toFloat() / 2f
        val r = radius + s * amp
        val x = c.x + cos(a) * r; val y = c.y + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

private const val PLASMA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float4 iEnergy;   // bass, mid, high, beat
half4 main(float2 fc) {
    float2 p = (fc / iResolution) * 2.0 - 1.0;
    p.x *= iResolution.x / iResolution.y;
    float t = iTime * (0.6 + 0.8 * iEnergy.y);
    float zoom = 2.2 - 0.6 * iEnergy.x - 0.4 * iEnergy.w;
    float2 q = p * zoom;
    float v = sin(q.x * 1.7 + t)
            + sin(q.y * 1.3 - t * 1.2)
            + sin((q.x + q.y) * 1.1 + t * 0.7)
            + sin(length(q + float2(sin(t * 0.4), cos(t * 0.3))) * 3.0 - t * 1.6);
    v *= 0.25;
    float3 col = 0.5 + 0.5 * cos(6.2831 * (v + float3(0.0, 0.33, 0.67) + iTime * 0.03));
    col = mix(col * 0.25, col, 0.45 + 0.55 * iEnergy.y);   // darker when quiet
    col += iEnergy.w * 0.25;                                // flash on beats
    col += iEnergy.z * 0.15 * smoothstep(0.9, 1.0, sin(q.x * 9.0 + t * 3.0) * sin(q.y * 9.0));  // sparkle on highs
    return half4(half3(col), 1.0);
}
"""

@Composable
private fun Plasma(state: AppState, modifier: Modifier) {
    val v = state.viz
    val tel = state.telemetry
    val shader = remember { RuntimeShader(PLASMA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        tel.frame.longValue
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", GlassClock.time)
        shader.setFloatUniform("iEnergy", v.bass, v.mid, v.high, v.beat)
        drawRect(brush)
    }
}
