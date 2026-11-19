// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.GrooveTemplate
import kotlin.math.roundToInt

/**
 * GROOVE strip (sampler + drum screens): this track's feel (STRAIGHT · MPC 60 · SP-1200 · DILLA ·
 * BOOM BAP · TRAP · HOUSE) with its AMOUNT, plus the song's SWING, all one tap away from the pads,
 * the way you'd reach for swing on an MPC while the loop plays.
 */
@Composable
fun GrooveStrip(state: AppState, track: Int, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 40.dp) {
    val g = state.groove
    val feel = g.feels.value[track]
    Row(modifier.fillMaxWidth().height(height).glass(state, 10.dp).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("GROOVE", color = Tb.Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp))
        GrooveTemplate.entries.forEach { t ->
            HwButton(t.label, Modifier.weight(1f).fillMaxHeight(), active = feel.template == t, accent = Tb.Key, minHeight = 28.dp, fontSize = 9, hPad = 2.dp) {
                g.setFeel(track, feel.copy(template = t))
                Info.title.value = t.label; Info.body.value = t.blurb; Info.stamp.intValue++
            }
        }
        DragValue("FEEL", "${(feel.amount * 100).roundToInt()}%", Tb.Key, Modifier.width(62.dp).fillMaxHeight(), pxPerUnit = 300f,
            onDelta = { g.setFeel(track, feel.copy(amount = feel.amount + it)) }, onReset = { g.setFeel(track, feel.copy(amount = 1f)) })
        DragValue("SWING", "${(g.groove.value.swing * 100).roundToInt()}%", Tb.Tempo, Modifier.width(62.dp).fillMaxHeight(), pxPerUnit = 600f,
            onDelta = { g.setSwing(g.groove.value.swing + it) }, onReset = { g.setSwing(0.5f) })
    }
}
