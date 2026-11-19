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

private data class FxPad(val id: Int, val name: String, val x: String, val y: String, val color: Color)
private val FX_PADS = listOf(
    FxPad(NativeEngine.Fx.STUTTER, "STUTTER", "rate", "—", Color(0xFFFF5A36)),
    FxPad(NativeEngine.Fx.FILTER, "FILTER", "LP ◂ ▸ HP", "reso", Color(0xFF2F7BFF)),
    FxPad(NativeEngine.Fx.TAPE_STOP, "TAPE STOP", "—", "time", Color(0xFFFFB000)),
    FxPad(NativeEngine.Fx.CRUSH, "CRUSH", "bits", "rate", Color(0xFF9A6BFF)),
    FxPad(NativeEngine.Fx.VINYL, "VINYL", "wow", "dust", Color(0xFFE6E23A)),
    FxPad(NativeEngine.Fx.REVERSE, "REVERSE", "length", "—", Color(0xFF2FD1C5)),
    FxPad(NativeEngine.Fx.GATE, "GATE", "rate", "duty", Color(0xFF1FCB6E)),
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
    val nameStyle = remember { TextStyle(color = Tb.Text, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val hintStyle = remember { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    val tel = state.telemetry
    val cols = 2; val rows = 4                            // 7 effects + PANIC

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
            Box(Modifier.weight(1f))
            Text(if (latchMode.value) "tap a pad to latch it" else "hold = on · release = dry",
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
                                    g.fxDown(id, x, y)
                                }
                            }
                        }
                        MotionEvent.ACTION_MOVE -> for (p in 0 until ev.pointerCount) {
                            val id = fingers[ev.getPointerId(p)] ?: continue
                            val (x, y) = local(ev.getX(p), ev.getY(p))
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
                drawRoundRect(if (on) p.color else p.color.copy(alpha = 0.18f), o, s, r)
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
