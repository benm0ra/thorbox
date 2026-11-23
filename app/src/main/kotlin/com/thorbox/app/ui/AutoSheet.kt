// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.Automation
import com.thorbox.app.synth.SynthPage
import com.thorbox.app.synth.SynthParams

/**
 * AUTO: every recorded automation lane as a little live curve (with the playhead running across),
 * each with a ✕. Recording is just: REC on, play, move things.
 */
@Composable
fun AutoSheet(state: AppState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val g = state.groove
    val lanes = g.auto.lanes.value
    val tracks = state.tracks.value
    fun knobName(page: Int, i: Int) = SynthPage.entries.getOrNull(page)?.let { SynthParams.pages.getValue(it).getOrNull(i)?.label } ?: "?"
    Column(modifier.sheet(onClose = onDone).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AUTOMATION", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            if (lanes.isNotEmpty()) HwButton("CLEAR ALL", minHeight = 34.dp, fontSize = 11, accent = Tb.Warn) { g.auto.clearAll() }
            HwButton("✕", Modifier.padding(start = 6.dp).width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onDone)
        }
        if (lanes.isEmpty()) {
            Text("Nothing recorded yet.\n\n1  Turn on REC (● or tap SELECT)\n2  Press play\n3  Move any synth or drum knob, a mixer fader / pan / PUMP, a pad's pitch, or punch in an FX\n\nYour moves loop with the pattern. Touch a control while it plays to take over.",
                color = Tb.Muted, fontSize = 13.sp)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lanes.keys.sorted()) { key ->
                val pts = lanes[key].orEmpty()
                val len = g.laneLen(key)
                val (lo, hi) = com.thorbox.app.groove.Automation.range(key)
                Row(Modifier.fillMaxWidth().height(44.dp).background(Tb.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g.auto.label(key, { tracks.getOrNull(it)?.name ?: "T${it + 1}" }, ::knobName), color = Tb.Text, fontSize = 11.sp,
                        fontWeight = FontWeight.Black, maxLines = 1, modifier = Modifier.width(150.dp))
                    val path = androidx.compose.runtime.remember { Path() }
                    Canvas(Modifier.weight(1f).height(30.dp)) {
                        state.telemetry.frame.longValue
                        path.reset()
                        fun y(v: Float) = size.height * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                        var prev = pts.lastOrNull()?.value ?: 0f
                        path.moveTo(0f, y(prev))
                        for (p in pts) {
                            val x = size.width * p.tick / len
                            path.lineTo(x, y(prev)); path.lineTo(x, y(p.value)); prev = p.value
                        }
                        path.lineTo(size.width, y(prev))
                        drawPath(path, Tb.Key, style = Stroke(2.dp.toPx()))
                        if (g.transport[1] != 0.0) {
                            val x = size.width * ((g.loopTick % len) / len).toFloat()
                            drawLine(Tb.Text, Offset(x, 0f), Offset(x, size.height), 1.5f.dp.toPx())
                        }
                    }
                    HwButton("✕", Modifier.width(40.dp), minHeight = 32.dp, fontSize = 12) { g.auto.clear(key) }
                }
            }
        }
    }
}
