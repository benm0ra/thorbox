// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Matte zinc hardware palette with flat, saturated "pad" colours. Black (not grey) where
 *  AMOLED pixels can switch off. */
object Tb {
    val Black = Color(0xFF000000)
    val Bg = Color(0xFF0B0B0D)
    val Panel = Color(0xFF151518)
    val Raised = Color(0xFF222226)
    val Line = Color(0xFF2E2E33)
    val Text = Color(0xFFF4F4F5)
    val Muted = Color(0xFF8E8E96)
    val Dim = Color(0xFF55555C)

    // Flat pad / clip palette (index = model colour index), Koala-bright and Ableton-legible
    val Pads = listOf(
        Color(0xFFFF5A36), Color(0xFFFFB000), Color(0xFFE6E23A), Color(0xFF5BD75B),
        Color(0xFF2FD1C5), Color(0xFF3D8BFF), Color(0xFF9A6BFF), Color(0xFFFF5FAE),
    )
    val Key = Color(0xFFFFB000)
    val Tempo = Color(0xFF2FD1C5)
    val Good = Color(0xFF5BD75B)
    val Warn = Color(0xFFFF5A36)

    // Encoder colours (TE-style 1:1 mapping between hardware-ish knobs and on-screen graphics)
    val EncBlue = Color(0xFF2F7BFF)
    val EncGreen = Color(0xFF1FCB6E)
    val EncWhite = Color(0xFFE9E9EC)
    val EncOrange = Color(0xFFFF6A13)
    fun enc(c: com.thorbox.app.synth.EncoderColor) = when (c) {
        com.thorbox.app.synth.EncoderColor.BLUE -> EncBlue
        com.thorbox.app.synth.EncoderColor.GREEN -> EncGreen
        com.thorbox.app.synth.EncoderColor.WHITE -> EncWhite
        com.thorbox.app.synth.EncoderColor.ORANGE -> EncOrange
    }

    val Mono = FontFamily.Monospace
    val Readout = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 0.sp)
    val Label = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted)
}

@Composable
fun ThorboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tb.Pads[0], onPrimary = Tb.Black, background = Tb.Bg, surface = Tb.Panel,
            onSurface = Tb.Text, onBackground = Tb.Text, surfaceVariant = Tb.Raised, outline = Tb.Line,
        ),
        content = content,
    )
}
