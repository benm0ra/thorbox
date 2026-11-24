// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navTarget
import com.thorbox.app.input.NavGrid
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
 * Bottom deck for the drum synth: 16 pads (4 × 4, MPC layout: pad 1 bottom-left), each in its sound's colour. Multi-touch,
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
    val tm = rememberTextMeasurer(cacheSize = 64)
    val nameStyle = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 13.sp, fontWeight = FontWeight.Black) }
    val numStyle = remember(Tb.palette) { TextStyle(color = Tb.Black.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Black) }
    val modelStyle = remember(Tb.palette) { TextStyle(color = Tb.Black.copy(alpha = 0.55f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }

    fun cellAt(x: Float, y: Float): Pair<Int, Float> {
        val cw = dims[0].coerceAtLeast(1) / 4f; val ch = dims[1].coerceAtLeast(1) / 4f
        val col = (x / cw).toInt().coerceIn(0, 3); val row = (y / ch).toInt().coerceIn(0, 3)
        val inCell = ((y % ch) / ch).coerceIn(0f, 1f)
        return ((3 - row) * 4 + col) to (0.4f + 0.6f * (1f - inCell))
    }

    // NAV: A enters the pads, the D-pad picks one (pad 1 bottom-left), A plays it.
    fun slotOf(c: Int) = (3 - c / 4) * 4 + c % 4
    val navGrid = NavGrid(4, 4,
        get = { val p = dc.selected.intValue; (3 - p / 4) * 4 + p % 4 },
        set = { c -> dc.selected.intValue = slotOf(c) },
        down = { c ->
            val lm = state.levelMode.value
            if (lm != com.thorbox.app.levels.LevelMode.OFF) dc.hitLevel(state.levelSrc.intValue, slotOf(c), lm, 0.85f) else dc.hit(slotOf(c), 0.85f)
        },
        up = { c -> if (state.levelMode.value == com.thorbox.app.levels.LevelMode.OFF) dc.release(slotOf(c)) },
        describe = { c -> dc.kit.value.voices.getOrNull(slotOf(c))?.name ?: "PAD ${slotOf(c) + 1}" })
    Canvas(
        modifier.onSizeChanged { dims[0] = it.width; dims[1] = it.height }
            .navTarget("DRUM PADS", grid = navGrid, corner = 10f)
            .pointerInteropFilter { ev ->
                val i = ev.actionIndex
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val (slot, vel) = cellAt(ev.getX(i), ev.getY(i))
                        val lm = state.levelMode.value
                        if (lm != com.thorbox.app.levels.LevelMode.OFF) dc.hitLevel(state.levelSrc.intValue, slot, lm, vel)
                        else { fingers[ev.getPointerId(i)] = slot; dc.hit(slot, vel) }
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
        val cw = size.width / 4f; val ch = size.height / 4f; val gap = 4.dp.toPx(); val r = CornerRadius(10.dp.toPx())
        // One name size for all 16 pads (the biggest that fits the longest name), so the grid reads
        // evenly instead of some names shrinking and others not.
        val room = cw - 2 * gap - 18.dp.toPx()
        var fit = 1f
        for (s in 0 until minOf(DRUM_SLOTS, voices.size)) {
            val wpx = tm.measure(voices[s].name, nameStyle).size.width
            if (wpx > room) fit = minOf(fit, room / wpx)
        }
        val shared = if (fit >= 1f) nameStyle else nameStyle.copy(fontSize = (13f * fit).coerceAtLeast(9f).sp)
        for (s in 0 until DRUM_SLOTS) {
            val col = s % 4; val row = 3 - s / 4
            val dt = now - dc.hitTime[s]
            val pop = if (dt in 0f..1f) exp(-dt * 16f) else 0f
            val grow = 1f + 0.05f * pop
            val w = (cw - 2 * gap) * grow; val h = (ch - 2 * gap) * grow
            val cx = col * cw + cw / 2f; val cy = row * ch + ch / 2f
            val o = Offset(cx - w / 2f, cy - h / 2f)
            val lm = state.levelMode.value
            if (lm != com.thorbox.app.levels.LevelMode.OFF) {
                // 16 LEVELS: every pad is the source sound, shaded low → high, labelled with its level
                val src = state.levelSrc.intValue
                val c = drumColor(src)
                val lit = maxOf(pop, if (now - dc.hitTime[src] < 0.15f) 0.3f else 0f)
                drawRoundRect(c.copy(alpha = 0.25f + 0.6f * s / 15f + 0.15f * lit), o, Size(w, h), r)
                if (s == 8 && lm == com.thorbox.app.levels.LevelMode.TUNE || s == 11 && lm == com.thorbox.app.levels.LevelMode.DECAY)
                    drawRoundRect(Tb.Text, o, Size(w, h), r, style = Stroke(2.dp.toPx()))     // "as it is"
                drawTextSafe(tm, com.thorbox.app.levels.Levels.label(lm, s), o + Offset(9.dp.toPx(), 7.dp.toPx()), nameStyle)
                drawTextSafe(tm, "L${s + 1}", Offset(o.x + w - 8.dp.toPx() - tm.measure("L${s + 1}", numStyle).size.width, o.y + h - 20.dp.toPx()), numStyle)
                continue
            }
            val c = drumColor(s)
            drawRoundRect(c.copy(alpha = 0.62f + 0.38f * maxOf(pop, dc.levels[s].coerceIn(0f, 1f))), o, Size(w, h), r)
            if (s == dc.selected.intValue) drawRoundRect(Tb.Text, o, Size(w, h), r, style = Stroke(3.dp.toPx()))
            val v = voices.getOrNull(s) ?: continue
            // name top-left (same size on every pad), the sound type under it when the name doesn't say it,
            // pad number bottom-right: they never meet
            val nameLay = tm.measure(v.name, shared)
            drawText(nameLay, topLeft = o + Offset(9.dp.toPx(), 7.dp.toPx()))
            val num = "${s + 1}"
            drawTextSafe(tm, num, Offset(o.x + w - 8.dp.toPx() - tm.measure(num, numStyle).size.width, o.y + h - 20.dp.toPx()), numStyle)
            if (!v.name.uppercase().contains(v.model.label.uppercase()) && tm.measure(v.model.label, modelStyle).size.width < room)
                drawTextSafe(tm, v.model.label, o + Offset(9.dp.toPx(), 7.dp.toPx() + nameLay.size.height + 1.dp.toPx()), modelStyle)
            // decay bar along the bottom: a quick read of the sound's length
            val frac = (kotlin.math.cbrt(((v.decayMs - 5f) / 3995f).coerceIn(0f, 1f)))
            drawRoundRect(Tb.Black.copy(alpha = 0.35f), Offset(o.x + 9.dp.toPx(), o.y + h - 10.dp.toPx()), Size((w - 40.dp.toPx()).coerceAtLeast(4f) * frac, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
    }
}
