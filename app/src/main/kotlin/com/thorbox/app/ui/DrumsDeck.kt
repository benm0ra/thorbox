// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.thorbox.model.DRUM_SLOTS
import kotlin.math.exp

/**
 * Bottom deck for the drum synth: 8 big pads (4 × 2), each in its sound's colour. Multi-touch,
 * velocity from where you hit (top = loud). A pad "pops" (grows, then settles) on every hit,
 * live or sequenced, so the deck feels alive even when you're only listening.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrumsDeck(state: AppState, modifier: Modifier = Modifier) {
    val dc = state.drums
    val tel = state.telemetry
    val dims = remember { IntArray(2) }
    val fingers = remember { HashMap<Int, Int>() }
    val tm = rememberTextMeasurer()
    val nameStyle = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 15.sp, fontWeight = FontWeight.Black) }
    val modelStyle = remember(Tb.palette) { TextStyle(color = Tb.Black.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }

    fun cellAt(x: Float, y: Float): Pair<Int, Float> {
        val cw = dims[0].coerceAtLeast(1) / 4f; val ch = dims[1].coerceAtLeast(1) / 2f
        val col = (x / cw).toInt().coerceIn(0, 3); val row = (y / ch).toInt().coerceIn(0, 1)
        val inCell = ((y % ch) / ch).coerceIn(0f, 1f)
        return (row * 4 + col) to (0.4f + 0.6f * (1f - inCell))
    }

    Canvas(
        modifier.onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .pointerInteropFilter { ev ->
                val i = ev.actionIndex
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val (slot, vel) = cellAt(ev.getX(i), ev.getY(i))
                        fingers[ev.getPointerId(i)] = slot
                        dc.hit(slot, vel)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingers.remove(ev.getPointerId(i))?.let(dc::release)
                    MotionEvent.ACTION_CANCEL -> { fingers.values.forEach(dc::release); fingers.clear() }
                }
                true
            }
    ) {
        tel.frame.longValue
        val now = tel.timeSec
        val voices = dc.kit.value.voices
        val cw = size.width / 4f; val ch = size.height / 2f; val gap = 5.dp.toPx(); val r = CornerRadius(14.dp.toPx())
        for (s in 0 until DRUM_SLOTS) {
            val col = s % 4; val row = s / 4
            val dt = now - dc.hitTime[s]
            val pop = if (dt in 0f..1f) exp(-dt * 16f) else 0f
            val grow = 1f + 0.05f * pop
            val w = (cw - 2 * gap) * grow; val h = (ch - 2 * gap) * grow
            val cx = col * cw + cw / 2f; val cy = row * ch + ch / 2f
            val o = Offset(cx - w / 2f, cy - h / 2f)
            val c = drumColor(s)
            drawRoundRect(c.copy(alpha = 0.62f + 0.38f * maxOf(pop, dc.levels[s].coerceIn(0f, 1f))), o, Size(w, h), r)
            if (s == dc.selected.intValue) drawRoundRect(Tb.Text, o, Size(w, h), r, style = Stroke(3.dp.toPx()))
            val v = voices.getOrNull(s) ?: continue
            drawTextSafe(tm, v.name, o + Offset(12.dp.toPx(), 10.dp.toPx()), nameStyle)
            if (!v.name.uppercase().contains(v.model.label.uppercase())) drawTextSafe(tm, v.model.label, o + Offset(12.dp.toPx(), 30.dp.toPx()), modelStyle)
            // decay bar along the bottom: a quick read of the sound's length
            val frac = (kotlin.math.cbrt(((v.decayMs - 5f) / 3995f).coerceIn(0f, 1f)))
            drawRoundRect(Tb.Black.copy(alpha = 0.35f), Offset(o.x + 12.dp.toPx(), o.y + h - 14.dp.toPx()), Size((w - 24.dp.toPx()) * frac, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
    }
}
