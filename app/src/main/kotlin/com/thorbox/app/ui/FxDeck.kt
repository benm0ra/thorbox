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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.RepeatRate
import com.thorbox.engine.NativeEngine

/** [slot] picks the pad colour from the current theme, so FX follow the palette too. */
private data class FxPad(val id: Int, val name: String, val x: String, val y: String, val slot: Int) { val color: Color get() = Tb.Pads[slot] }
private val FX_PADS = listOf(
    FxPad(NativeEngine.Fx.STUTTER, "STUTTER", "rate", "—", 0),
    FxPad(NativeEngine.Fx.FILTER, "FILTER", "LP ◂ ▸ HP", "reso", 5),
    FxPad(NativeEngine.Fx.TAPE_STOP, "TAPE STOP", "—", "time", 1),
    FxPad(NativeEngine.Fx.CRUSH, "CRUSH", "bits", "rate", 6),
    FxPad(NativeEngine.Fx.VINYL, "VINYL", "wow", "dust", 2),
    FxPad(NativeEngine.Fx.REVERSE, "REVERSE", "length", "—", 4),
    FxPad(NativeEngine.Fx.GATE, "GATE", "rate", "duty", 3),
    FxPad(NativeEngine.Fx.HAZE, "HAZE", "size", "tone", 7),
)

/**
 * Deck 4: punch-in master FX (OP-XY / SP-404 style). Hold a pad to engage it, and release to snap back dry.
 * Where your finger sits on the pad is the effect's XY. Slide while holding to sweep.
 * Several fingers = stacked effects. LATCH, then tap a pad, keeps that effect on hands-free.
 * Pads glow from the engine's live FX state, so latched and fading effects are visible.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FxDeck(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val latchMode = remember { mutableStateOf(false) }
    val dims = remember { IntArray(2) }
    val fingers = remember { HashMap<Int, Int>() }        // pointerId → fx id
    val tm = rememberTextMeasurer()
    val nameStyle = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val hintStyle = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    val tel = state.telemetry
    val cols = 3; val rows = 3                            // 8 effects + PANIC
    val xy = remember { FloatArray(NativeEngine.Fx.COUNT * 2) { 0.5f } }      // last XY per effect (drives its glyph)
    val since = remember { FloatArray(NativeEngine.Fx.COUNT) { -10f } }       // clock time each effect engaged
    val wasOn = remember { BooleanArray(NativeEngine.Fx.COUNT) }
    val offAt = remember { FloatArray(NativeEngine.Fx.COUNT) { -1000f } }

    fun cell(x: Float, y: Float): Int {
        val c = (x / dims[0].coerceAtLeast(1) * cols).toInt().coerceIn(0, cols - 1)
        val r = (y / dims[1].coerceAtLeast(1) * rows).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }
    fun local(x: Float, y: Float): Pair<Float, Float> {
        val cw = dims[0].toFloat() / cols; val ch = dims[1].toFloat() / rows
        return ((x % cw) / cw).coerceIn(0f, 1f) to (1f - (y % ch) / ch).coerceIn(0f, 1f)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HwButton("LATCH", Modifier.fillMaxHeight(), active = latchMode.value, accent = Tb.Key, minHeight = 40.dp, fontSize = 12) { latchMode.value = !latchMode.value }
            if (state.tilt.available)
                HwButton("TILT", Modifier.fillMaxHeight(), active = state.tilt.enabled.value, accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) {
                    state.tilt.setEnabled(!state.tilt.enabled.value) }
            Box(Modifier.weight(1f))
            Text(if (latchMode.value) "tap a pad to latch it" else if (state.tilt.enabled.value) "hold + lean to steer · shake = dice" else "hold = on · release = dry",
                color = Tb.Muted, fontSize = 11.sp, modifier = Modifier.width(170.dp))
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
                .pointerInteropFilter { ev ->
                    val i = ev.actionIndex
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val c = cell(ev.getX(i), ev.getY(i))
                            if (c >= FX_PADS.size) { g.stopAll() }                       // PANIC: everything dry
                            else {
                                val id = FX_PADS[c].id
                                if (latchMode.value) g.toggleLatch(id) else {
                                    val (x, y) = local(ev.getX(i), ev.getY(i))
                                    fingers[ev.getPointerId(i)] = id
                                    xy[2 * id] = x; xy[2 * id + 1] = y
                                    g.fxDown(id, x, y)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> for (p in 0 until ev.pointerCount) {
                            val id = fingers[ev.getPointerId(p)] ?: continue
                            val (x, y) = local(ev.getX(p), ev.getY(p))
                            xy[2 * id] = x; xy[2 * id + 1] = y
                            g.fxMove(id, x, y)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingers.remove(ev.getPointerId(i))?.let(g::fxUp)
                        MotionEvent.ACTION_CANCEL -> { fingers.values.forEach(g::fxUp); fingers.clear() }
                    }
                    true
                }
        ) {
            tel.frame.longValue
            val active = g.fxActiveMask()
            val latched = g.fxLatched.intValue
            val cw = size.width / cols; val ch = size.height / rows
            val gap = 4.dp.toPx(); val r = CornerRadius(12.dp.toPx())
            for (k in 0 until cols * rows) {
                val o = Offset((k % cols) * cw + gap, (k / cols) * ch + gap)
                val s = Size(cw - gap * 2, ch - gap * 2)
                if (k >= FX_PADS.size) {
                    drawRoundRect(Tb.Raised, o, s, r)
                    drawText(tm, "ALL DRY", o + Offset(12.dp.toPx(), 10.dp.toPx()), nameStyle)
                    continue
                }
                val p = FX_PADS[k]
                val on = active and (1 shl p.id) != 0
                if (on && !wasOn[p.id]) since[p.id] = tel.timeSec
                if (!on && wasOn[p.id]) offAt[p.id] = tel.timeSec
                wasOn[p.id] = on
                // A punch-in "pop": the pad swells a touch for 120 ms as it engages.
                val pop = if (on) kotlin.math.exp(-(tel.timeSec - since[p.id]) * 18f) else 0f
                drawRoundRect(if (on) p.color else p.color.copy(alpha = 0.18f), o - Offset(pop * 4f, pop * 4f), Size(s.width + pop * 8f, s.height + pop * 8f), r)
                // Glyph lives top-right, clear of the name (top-left) and the XY hint (bottom).
                val gArea = Offset(o.x + s.width * 0.54f, o.y + 6.dp.toPx())
                val gSize = Size(s.width * 0.42f, (s.height - 30.dp.toPx()).coerceAtLeast(12.dp.toPx()))
                if (p.id == NativeEngine.Fx.HAZE) noirScene(gArea, gSize, tel.timeSec, if (on) tel.timeSec - since[p.id] else -1f,
                    tel.timeSec - offAt[p.id], g.fxEffX(p.id), g.fxEffY(p.id), state.viz.bass, Tb.Pads[7], full = false)
                else fxGlyph(p.id, gArea, gSize, on, if (on) g.fxEffX(p.id) else xy[2 * p.id], if (on) g.fxEffY(p.id) else xy[2 * p.id + 1], tel.timeSec, tel.timeSec - since[p.id],
                    g.transport[0], state.viz.bass, if (on) Tb.Black else p.color)
                if (latched and (1 shl p.id) != 0) drawRoundRect(Tb.Text, o, s, r, style = Stroke(3.dp.toPx()))
                drawText(tm, p.name, o + Offset(12.dp.toPx(), 10.dp.toPx()), if (on) nameStyle.copy(color = Tb.Black) else nameStyle)
                drawText(tm, "↔ ${p.x}   ↕ ${p.y}", o + Offset(12.dp.toPx(), s.height - 20.dp.toPx()), hintStyle)
            }
        }
    }
}

/** Note repeat: hold RPT with one thumb and hit pads or keys with the other (or hold L1). */
@Composable
fun RepeatStrip(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val rate = g.groove.value.repeatRate
    Row(modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HoldButton("RPT", g.repeatHeld.value, Tb.EncOrange, Modifier.width(64.dp).fillMaxHeight()) { g.setRepeatHeld(it) }
        RepeatRate.entries.forEach { r ->
            HwButton(r.label, Modifier.weight(1f).fillMaxHeight(), active = r == rate, accent = Tb.EncWhite, fontSize = 11) { g.setRepeatRate(r) }
        }
    }
}

/**
 * Little animated glyphs, one per punch-in effect (OP-XY style). Idle: a slow, quiet loop in the
 * pad's colour. Engaged: bold black on the lit pad, driven by the finger's XY, the music's bass
 * energy and the song position, so every effect visibly "does its thing" while you hold it.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.fxGlyph(
    id: Int, o: Offset, s: Size, on: Boolean, x: Float, y: Float, t: Float, held: Float, tick: Double, bass: Float, c: Color,
) {
    val w = s.width; val h = s.height
    val cx = o.x + w / 2f; val cy = o.y + h / 2f
    val sw = (if (on) 3.5f else 2.5f) * density
    val alpha = if (on) 1f else 0.55f
    val col = c.copy(alpha = alpha)
    val beat = ((tick / com.thorbox.model.PPQN) % 1.0).toFloat()
    when (id) {
        NativeEngine.Fx.STUTTER -> {
            // Repeating blocks: more (and faster) as X rises. The first block is "the slice", the rest echo it.
            val n = 2 + (x * 6f).toInt()
            val bw = w / n
            val speed = if (on) 4f + 12f * x else 1f
            for (i in 0 until n) {
                val ph = ((t * speed + i * 0.13f) % 1f)
                val hh = h * (0.35f + 0.45f * (if (i == 0) 1f else (1f - i.toFloat() / n)) * (0.7f + 0.3f * kotlin.math.sin(ph * 6.283f)))
                drawRoundRect(col.copy(alpha = alpha * (if (i == 0) 1f else 0.5f + 0.5f * (1f - i.toFloat() / n))),
                    Offset(o.x + i * bw + bw * 0.12f, cy - hh / 2f), Size(bw * 0.76f, hh), CornerRadius(3f * density))
            }
        }
        NativeEngine.Fx.FILTER -> {
            // The response curve: LP on the left half of X, HP on the right; Y = resonance bump.
            val path = androidx.compose.ui.graphics.Path()
            val hp = x > 0.5f
            val fc = if (hp) (x - 0.5f) * 2f else x * 2f
            val q = 0.15f + 0.85f * y
            val wob = if (on) 0.04f * bass * kotlin.math.sin(t * 9f) else 0.02f * kotlin.math.sin(t * 1.5f)
            for (i in 0..40) {
                val u = i / 40f
                val d = (u - (fc + wob)) * 7f
                val shelf = if (hp) 1f / (1f + kotlin.math.exp(-d)) else 1f / (1f + kotlin.math.exp(d))
                val bump = q * kotlin.math.exp(-d * d * 2f) * 0.6f
                val yy = o.y + h * (0.9f - 0.65f * (shelf + bump).coerceAtMost(1.3f))
                if (i == 0) path.moveTo(o.x + w * u, yy) else path.lineTo(o.x + w * u, yy)
            }
            drawPath(path, col, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        NativeEngine.Fx.TAPE_STOP -> {
            // A cassette: reels spin at play speed, slow to a halt while held (Y = how long it takes).
            val stopSec = 0.15f + 1.6f * y
            val speed = if (on) (1f - (held / stopSec)).coerceIn(0f, 1f) else 0.35f
            val ang = (t * 6f * speed) % 6.283f
            val rr = minOf(w * 0.18f, h * 0.3f)
            drawRoundRect(col, Offset(o.x + w * 0.04f, cy - h * 0.36f), Size(w * 0.92f, h * 0.72f), CornerRadius(6f * density), style = Stroke(sw))
            for (side in listOf(-1f, 1f)) {
                val rc = Offset(cx + side * w * 0.22f, cy)
                drawCircle(col, rr, rc, style = Stroke(sw))
                for (k in 0 until 3) {
                    val a = ang + k * 2.094f
                    drawLine(col, rc, Offset(rc.x + kotlin.math.cos(a) * rr * 0.8f, rc.y + kotlin.math.sin(a) * rr * 0.8f), sw * 0.8f)
                }
            }
        }
        NativeEngine.Fx.CRUSH -> {
            // A sine turned into stairs: fewer bits (X left) = taller steps, lower rate (Y) = wider steps.
            val levels = 2 + ((1f - x) * 0f + x * 14f).toInt()
            val stepW = w * (0.03f + 0.17f * (1f - y))
            var u = 0f
            val ph = t * (if (on) 2.5f else 0.6f)
            while (u < w) {
                val v = kotlin.math.sin((u / w) * 6.283f * 1.5f + ph)
                val q = kotlin.math.round(v * levels) / levels
                drawRect(col, Offset(o.x + u, cy - q * h * 0.38f - sw / 2), Size(minOf(stepW, w - u), sw))
                u += stepW
            }
        }
        NativeEngine.Fx.VINYL -> {
            // A spinning record: X = wow (it wobbles off-centre), Y = dust (specks pop up on it).
            val rr = minOf(w, h) * 0.46f
            val wob = (if (on) x else 0.1f) * rr * 0.12f * kotlin.math.sin(t * 3f)
            val rc = Offset(cx + wob, cy)
            drawCircle(col, rr, rc, style = Stroke(sw))
            drawCircle(col.copy(alpha = alpha * 0.5f), rr * 0.66f, rc, style = Stroke(sw * 0.5f))
            drawCircle(col, rr * 0.22f, rc)
            val a = t * (if (on) 5f else 1.5f)
            drawLine(col, rc, Offset(rc.x + kotlin.math.cos(a) * rr * 0.9f, rc.y + kotlin.math.sin(a) * rr * 0.9f), sw * 0.7f)
            if (on) {
                var seed = (t * 30f).toInt() * 196613
                val specks = (y * 14f).toInt()
                for (i in 0 until specks) {
                    seed = seed * 1103515245 + 12345
                    val ra = ((seed ushr 8) and 0xFFFF) / 65535f * 6.283f
                    seed = seed * 1103515245 + 12345
                    val rd = ((seed ushr 8) and 0xFFFF) / 65535f * rr
                    drawCircle(col, 1.6f * density, Offset(rc.x + kotlin.math.cos(ra) * rd, rc.y + kotlin.math.sin(ra) * rd))
                }
            }
        }
        NativeEngine.Fx.REVERSE -> {
            // A waveform scrolling right-to-left (backwards); X = slice length = wavelength.
            val path = androidx.compose.ui.graphics.Path()
            val k = 1.5f + 5f * (1f - x)
            val ph = t * (if (on) 6f else 1.2f)
            for (i in 0..48) {
                val u = i / 48f
                val env = kotlin.math.sin(u * 3.1416f)
                val v = kotlin.math.sin(u * 6.283f * k + ph) * env * (0.6f + 0.4f * if (on) bass else 0.3f)
                val px = o.x + w * u; val py = cy - v * h * 0.38f
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, col, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            // ◀ chevron
            val ax = o.x + w * 0.1f
            drawLine(col, Offset(ax + 8f * density, cy - 7f * density), Offset(ax, cy), sw)
            drawLine(col, Offset(ax, cy), Offset(ax + 8f * density, cy + 7f * density), sw)
        }
        NativeEngine.Fx.GATE -> {
            // Blocks chopped in time: X = rate (1/4 … 1/32), Y = duty (how long each chop stays open).
            val divs = listOf(1, 2, 4, 8)[(x * 3.99f).toInt()]
            val n = divs * 2
            val bw = w / n
            val duty = 0.15f + 0.8f * y
            val pos = ((beat * divs) % 1f)
            val cur = ((beat * n) % n).toInt()
            for (i in 0 until n) {
                val open = on && i == cur && pos < duty + 0.001f
                val hh = h * (if (open) 0.8f else 0.4f)
                drawRoundRect(col.copy(alpha = alpha * (if (open) 1f else 0.45f)), Offset(o.x + i * bw + bw * 0.1f, cy - hh / 2f),
                    Size(bw * duty * 0.9f + bw * 0.08f, hh), CornerRadius(2f * density))
            }
        }
    }
}
