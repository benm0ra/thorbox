// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.Automation
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * This track's automation, right under its knobs (synth and drum screens), so recording a sweep
 * never means leaving the sound you're shaping:
 *
 *   [● REC]  ~~~ every lane of this track, one line each, in its knob's colour, with the playhead ~~~  [CLEAR]
 *
 * REC + play, then turn any knob: the move loops with the pattern. Touch the knob again to take
 * over. CLEAR asks once ("SURE?") and only clears this track. Empty, the strip says how it works.
 */
@Composable
fun TrackLanes(state: AppState, track: Int, modifier: Modifier = Modifier, height: Dp = 32.dp) {
    val g = state.groove
    val all = g.auto.lanes.value
    val keys = all.keys.filter { Automation.trackOf(it) == track }.sorted()
    val rec = g.recording.value
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(2500); confirm = false } }
    val path = remember { Path() }

    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        HwButton(if (rec) "● REC" else "○ REC", Modifier.width(78.dp).fillMaxHeight(), active = rec, accent = Tb.Warn, minHeight = height, fontSize = 11, hPad = 4.dp) {
            g.toggleRecord()
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(Tb.Void.copy(alpha = 0.6f), RoundedCornerShape(6.dp))) {
            if (keys.isEmpty()) {
                Text(if (rec) "Recording: press ▶ and turn any knob" else "AUTOMATION · REC + ▶, then turn a knob: the move loops with the pattern",
                    color = if (rec) Tb.Warn else Tb.Dim, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp))
            } else Canvas(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 6.dp, vertical = 4.dp)) {
                state.telemetry.frame.longValue
                val len = g.loopLen(track).toFloat().coerceAtLeast(1f)
                for (key in keys) {
                    val pts = all[key].orEmpty(); if (pts.isEmpty()) continue
                    val (lo, hi) = Automation.range(key)
                    fun y(v: Float) = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                    path.reset()
                    var prev = pts.last().value
                    path.moveTo(0f, y(prev))
                    for (p in pts) { val x = size.width * p.tick / len; path.lineTo(x, y(prev)); path.lineTo(x, y(p.value)); prev = p.value }
                    path.lineTo(size.width, y(prev))
                    drawPath(path, laneColor(key), style = Stroke(2.dp.toPx()))
                }
                if (g.transport[1] != 0.0) {
                    val x = size.width * ((g.loopTick % len) / len).toFloat()
                    drawLine(if (rec) Tb.Warn else Tb.Text, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                }
            }
        }
        if (keys.isNotEmpty()) {
            Text("${keys.size}", color = Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Mono)
            HwButton(if (confirm) "SURE?" else "CLEAR", Modifier.width(70.dp).fillMaxHeight(), active = confirm, accent = Tb.Warn, minHeight = height, fontSize = 11, hPad = 4.dp) {
                if (confirm) { g.auto.clearTrack(track); confirm = false } else confirm = true
            }
        }
    }
}

/** A lane is drawn in the colour of the knob it moves (blue / green / white / orange), mixer lanes muted. */
private fun laneColor(key: String): Color {
    val p = key.split(":")
    val knob = when (p.getOrNull(1)) {
        "syn" -> p.getOrNull(3)?.toIntOrNull()
        "drm" -> p.getOrNull(4)?.toIntOrNull()
        else -> null
    }
    return when (knob) {
        0 -> Tb.EncBlue; 1 -> Tb.EncGreen; 2 -> Tb.EncWhite; 3 -> Tb.EncOrange
        else -> if (key.endsWith(":pitch")) Tb.Key else Tb.Muted
    }
}
