// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable

/**
 * Theme picker: one card per theme, each drawn IN its own palette (paper, panel, the 8 pad
 * colours, a type sample), so you see the theme before you pick it. Tapping applies it live.
 */
@Composable
fun ThemeGrid(state: AppState, modifier: Modifier = Modifier) {
        // two rows of four: eight themes stay big enough to judge at a glance
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Themes.all.chunked(4).forEach { rowThemes ->
          Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowThemes.forEach { p ->
                val on = p == Tb.palette
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(p.void)
                    .border(if (on) 3.dp else 1.dp, if (on) p.text else p.line, RoundedCornerShape(12.dp))
                    .navClickable(label = p.label) { state.setTheme(p) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitText(p.label, androidx.compose.ui.text.TextStyle(color = p.text, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp,
                        fontFamily = if (p.monoUi) FontFamily.Monospace else null), minSize = 7f)
                    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(p.panel).padding(6.dp)) {
                        Text("120.0", color = p.key, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }
                    // the 8 slot colours as a 4 × 2 pad grid
                    for (row in 0 until 2) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until 4) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(p.pads[row * 4 + c]))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(p.encBlue, p.encGreen, p.encWhite, p.encOrange).forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(it)) }
                    }
                }
            }
          }
          }
        }
}
