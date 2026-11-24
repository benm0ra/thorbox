// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.GrooveController
import com.thorbox.app.groove.STEP_TICKS
import com.thorbox.app.groove.Steps

/**
 * A pattern's length and where you are in it, for every editor (patterns run 1 step … 64 bars).
 *
 *   ÷2 · − · 12 BARS · + · ×2      the length of [track]'s pattern (− / + a bar at a time)
 *   ◀ [ ▮▯▮▮▯ … ] ▶                one cell per bar: lit where there are notes, the page on screen
 *                                  outlined, the playhead's bar marked; tap a bar to show it
 *   FOLLOW                         while playing, the page follows the playhead
 *
 * [stepsPerPage] is how many steps the editor draws at once (16 = a bar); [lengthControls] off
 * leaves just the navigator (for tight spaces).
 */
@Composable
fun BarNav(state: AppState, track: Int, stepsPerPage: Int, modifier: Modifier = Modifier, height: Dp = 34.dp, lengthControls: Boolean = true) {
    val g = state.groove
    val clip = g.clips.value[track]
    val steps = Steps.count(clip.lengthTicks)
    val bars = (steps + 15) / 16
    val hasNotes = remember(clip) {
        BooleanArray(bars).also { a -> for (e in clip.notes) { val b = (e.tick / STEP_TICKS) / 16; if (b in a.indices) a[b] = true } }
    }
    val fs = 11
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lengthControls) {
            HwButton("÷2", Modifier.fillMaxHeight(), enabled = steps > 1, minHeight = height, fontSize = fs, hPad = 6.dp) { g.setLength(track, (steps / 2).coerceAtLeast(1)) }
            HwButton("−", Modifier.width(34.dp).fillMaxHeight(), enabled = steps > 8, minHeight = height, fontSize = 14, hPad = 0.dp) {
                g.setLength(track, if (steps <= 16) 8 else ((steps - 1) / 16) * 16)
            }
            Text(lengthLabel(steps), color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 12.sp,
                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
            HwButton("+", Modifier.width(34.dp).fillMaxHeight(), enabled = steps < GrooveController.MAX_STEPS, minHeight = height, fontSize = 14, hPad = 0.dp) {
                g.setLength(track, if (steps < 16) 16 else (steps / 16 + 1) * 16)
            }
            HwButton("×2", Modifier.fillMaxHeight(), enabled = steps * 2 <= GrooveController.MAX_STEPS, minHeight = height, fontSize = fs, hPad = 6.dp) {
                g.setLength(track, steps * 2)
            }
        }
        if (steps > stepsPerPage) {
            val pages = (steps + stepsPerPage - 1) / stepsPerPage
            fun go(d: Int) {
                val cur = g.pageStart(track, stepsPerPage) / stepsPerPage
                g.showPage((cur + d).coerceIn(0, pages - 1), stepsPerPage)
            }
            HwButton("◀", Modifier.width(34.dp).fillMaxHeight(), minHeight = height, fontSize = 12, hPad = 0.dp) { go(-1) }
            Canvas(Modifier.weight(1f).fillMaxHeight()
                .pointerInput(steps, stepsPerPage) {
                    detectTapGestures { p ->
                        val bar = (p.x / size.width * bars).toInt().coerceIn(0, bars - 1)
                        g.showPage(bar * 16 / stepsPerPage, stepsPerPage)
                    }
                }) {
                state.telemetry.frame.longValue
                val cw = size.width / bars
                val gap = if (cw > 6.dp.toPx()) 1.dp.toPx() else 0f
                val r = CornerRadius(2.dp.toPx())
                for (b in 0 until bars) {
                    val c = if (hasNotes[b]) Tb.EncGreen.copy(alpha = 0.75f) else Tb.Raised
                    drawRoundRect(c, Offset(b * cw + gap, size.height * 0.2f), Size(cw - 2 * gap, size.height * 0.6f), r)
                }
                // the page on screen
                val first = g.pageStart(track, stepsPerPage)
                val x0 = first / 16f * cw; val x1 = minOf(steps, first + stepsPerPage) / 16f * cw
                drawRoundRect(Tb.Text, Offset(x0, 1f), Size((x1 - x0).coerceAtLeast(2f), size.height - 2f), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
                // the playhead's bar
                val ps = g.stepPosition(track)
                if (ps >= 0) { val px = (ps + 0.5f) / 16f * cw; drawLine(Tb.Warn, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx()) }
            }
            HwButton("▶", Modifier.width(34.dp).fillMaxHeight(), minHeight = height, fontSize = 12, hPad = 0.dp) { go(+1) }
            HwButton("FOLLOW", Modifier.fillMaxHeight(), active = g.follow.value, accent = Tb.EncWhite, minHeight = height, fontSize = fs, hPad = 6.dp) {
                g.follow.value = !g.follow.value
            }
        } else androidx.compose.foundation.layout.Box(Modifier.weight(1f))
    }
}

/** "8 STEPS", "1 BAR", "12 BARS", "2½ BARS". */
fun lengthLabel(steps: Int): String = when {
    steps < 16 -> "$steps STEPS"
    steps % 16 == 0 -> (steps / 16).let { "$it BAR" + if (it > 1) "S" else "" }
    steps % 16 == 8 -> "${steps / 16}½ BARS"
    else -> "$steps STEPS"
}
