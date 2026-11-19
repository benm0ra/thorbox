// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState

/**
 * Theme picker: one card per theme, each drawn IN its own palette (paper, panel, the 8 pad
 * colours, a type sample), so you see the theme before you pick it. Tapping applies it live.
 */
@Composable
fun ThemePicker(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Tb.Bg.copy(alpha = 0.96f), RoundedCornerShape(14.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THEME", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            Text("SELECT + L3 cycles", color = Tb.Dim, fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14) { state.showThemes.value = false }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Themes.all.forEach { p ->
                val on = p == Tb.palette
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(p.void)
                    .border(if (on) 3.dp else 1.dp, if (on) p.text else p.line, RoundedCornerShape(12.dp))
                    .clickable { state.setTheme(p) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.label, color = p.text, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp,
                        fontFamily = if (p.monoUi) FontFamily.Monospace else null)
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
