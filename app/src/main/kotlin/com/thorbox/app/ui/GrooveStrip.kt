// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navModal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.GrooveTemplate
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * GROOVE strip (sampler + drum screens): this track's feel with its AMOUNT, plus the song's SWING,
 * all one tap away from the pads, the way you'd reach for swing on an MPC while the loop plays.
 * ‹ › steps through the feels so you can audition them against the running loop; tapping the name
 * swaps the strip for the full list (whole names, nothing cut off), one tap to pick.
 */
@Composable
fun GrooveStrip(state: AppState, track: Int, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 40.dp, showLabel: Boolean = true) {
    val g = state.groove
    val feel = g.feels.value[track]
    var choosing by remember { mutableStateOf(false) }
    fun pick(t: GrooveTemplate) {
        g.setFeel(track, feel.copy(template = t))
        Info.title.value = t.label; Info.body.value = t.blurb; Info.stamp.intValue++
    }
    Row(modifier.then(if (choosing) Modifier.navModal { choosing = false } else Modifier)
        .fillMaxWidth().height(height).glass(state, 10.dp).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (choosing) {
            GrooveTemplate.entries.forEach { t ->
                // width follows the name, so STRAIGHT and BOOM BAP fit whole
                HwButton(t.label, Modifier.weight(t.label.length.coerceAtLeast(5).toFloat()).fillMaxHeight(), active = feel.template == t,
                    accent = Tb.Key, minHeight = 28.dp, fontSize = 10, hPad = 2.dp) { pick(t); choosing = false }
            }
            HwButton("✕", Modifier.width(40.dp).fillMaxHeight(), minHeight = 28.dp, fontSize = 12) { choosing = false }
        } else {
        if (showLabel) Text("GROOVE", color = Tb.Muted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp))
        val wide = LocalWide.current           // narrow: tap the name for the list (‹ › make way for the values)
        if (wide) HwButton("‹", Modifier.width(30.dp).fillMaxHeight(), minHeight = 28.dp, fontSize = 13, hPad = 0.dp) { pick(feel.template.next(-1)) }
        HwButton(feel.template.label, Modifier.weight(1.3f).fillMaxHeight(), active = feel.template != GrooveTemplate.STRAIGHT,
            accent = Tb.Key, minHeight = 28.dp, fontSize = 11, hPad = 4.dp) { choosing = true }
        if (wide) HwButton("›", Modifier.width(30.dp).fillMaxHeight(), minHeight = 28.dp, fontSize = 13, hPad = 0.dp) { pick(feel.template.next(+1)) }
        DragValue("FEEL", "${(feel.amount * 100).roundToInt()}%", Tb.Key, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f, compact = true,
            onDelta = { g.setFeel(track, feel.copy(amount = feel.amount + it)) }, onReset = { g.setFeel(track, feel.copy(amount = 1f)) })
        DragValue("SWING", "${(g.groove.value.swing * 100).roundToInt()}%", Tb.Tempo, Modifier.weight(1.05f).fillMaxHeight(), pxPerUnit = 600f, compact = true,
            onDelta = { g.setSwing(g.groove.value.swing + it) }, onReset = { g.setSwing(0.5f) })
        }
    }
}
