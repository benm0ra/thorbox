// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * A complete colour theme. Roles, not hues:
 *  void   the deepest background (AMOLED black on dark themes, paper on light ones)
 *  ink    text/marks drawn ON an accent (a lit pad, an active button)
 *  pads   the 8 slot colours: pads, clips, drum sounds, FX. Must stay distinguishable.
 *  enc*   the four encoder colours, matched 1:1 by the graphics they move
 *  aurora the 4 tints the glass backdrop shader blends (see Glass.kt)
 *  sections one colour per section (SAMPLE · SYNTH · DRUMS · STEPS · MIXER · FX · VISUAL, see Tokens.kt):
 *         all different, and none equal to `key` (active toggles) or `good` (PLAY)
 */
data class Palette(
    val id: String, val label: String, val light: Boolean,
    val void: Color, val bg: Color, val panel: Color, val raised: Color, val line: Color,
    val text: Color, val muted: Color, val dim: Color, val ink: Color,
    val pads: List<Color>,
    val key: Color, val tempo: Color, val good: Color, val warn: Color,
    val encBlue: Color, val encGreen: Color, val encWhite: Color, val encOrange: Color,
    val aurora: List<Color>,
    val sections: List<Color> = emptyList(),
    val monoUi: Boolean = false,       // every label in monospace (TERMINAL)
    val scanlines: Float = 0f,         // CRT scanline overlay strength, 0 = off
)

object Themes {
    val Studio = Palette(
        "studio", "STUDIO", light = false,
        void = Color(0xFF000000), bg = Color(0xFF0B0B0D), panel = Color(0xFF151518), raised = Color(0xFF2A2A30), line = Color(0xFF3A3A42),
        text = Color(0xFFF4F4F5), muted = Color(0xFF8E8E96), dim = Color(0xFF6E6E77), ink = Color(0xFF000000),
        pads = listOf(Color(0xFFFF5A36), Color(0xFFFFB000), Color(0xFFE6E23A), Color(0xFF5BD75B),
            Color(0xFF2FD1C5), Color(0xFF3D8BFF), Color(0xFF9A6BFF), Color(0xFFFF5FAE)),
        key = Color(0xFFFFB000), tempo = Color(0xFF2FD1C5), good = Color(0xFF5BD75B), warn = Color(0xFFFF5A36),
        encBlue = Color(0xFF2F7BFF), encGreen = Color(0xFF1FCB6E), encWhite = Color(0xFFE9E9EC), encOrange = Color(0xFFFF6A13),
        aurora = listOf(Color(0xFF294DFF), Color(0xFFFF3D85), Color(0xFF14D9BF), Color(0xFFFF6B14)),
        sections = listOf(Color(0xFF2FD1C5), Color(0xFFFF6A13), Color(0xFF9A6BFF), Color(0xFFFF5FAE), Color(0xFFE9E9EC), Color(0xFF3D8BFF), Color(0xFFE6E23A)),
    )
    val Cyberpunk = Palette(
        "cyberpunk", "CYBERPUNK", light = false,
        void = Color(0xFF05010A), bg = Color(0xFF0A0614), panel = Color(0xFF140C24), raised = Color(0xFF2C1D4B), line = Color(0xFF4A3075),
        text = Color(0xFFF2E9FF), muted = Color(0xFF9C86C9), dim = Color(0xFF7A68A6), ink = Color(0xFF0A0614),
        pads = listOf(Color(0xFFFF2A6D), Color(0xFFFF8A00), Color(0xFFFCEE0A), Color(0xFF05FFA1),
            Color(0xFF01CDFE), Color(0xFF5A7BFF), Color(0xFFB967FF), Color(0xFFFF71CE)),
        key = Color(0xFFFCEE0A), tempo = Color(0xFF01CDFE), good = Color(0xFF05FFA1), warn = Color(0xFFFF2A6D),
        encBlue = Color(0xFF01CDFE), encGreen = Color(0xFF05FFA1), encWhite = Color(0xFFF2E9FF), encOrange = Color(0xFFFF2A6D),
        aurora = listOf(Color(0xFFFF2A6D), Color(0xFF01CDFE), Color(0xFFB967FF), Color(0xFFFCEE0A)),
        sections = listOf(Color(0xFF01CDFE), Color(0xFFFF8A00), Color(0xFFB967FF), Color(0xFFFF71CE), Color(0xFFF2E9FF), Color(0xFF3D5AFE), Color(0xFFFF2A6D)),
        scanlines = 0.05f,
    )
    val Pastel = Palette(
        "pastel", "PASTEL", light = true,
        void = Color(0xFFE8DECD), bg = Color(0xFFEFE6D8), panel = Color(0xFFF3ECE1), raised = Color(0xFFE2D5C0), line = Color(0xFFC9B89F),
        text = Color(0xFF2E2A33), muted = Color(0xFF5E564B), dim = Color(0xFF857A69), ink = Color(0xFF2E2A33),
        // Deep enough to stand off the cream panels (≥ 1.9:1) while dark ink on them stays readable (≥ 4.5:1).
        pads = listOf(Color(0xFFEE6A6A), Color(0xFFF29440), Color(0xFFCDAE1F), Color(0xFF5DB84F),
            Color(0xFF36B0C8), Color(0xFF6F92F2), Color(0xFF9A80F0), Color(0xFFE86FC4)),
        key = Color(0xFFE58A22), tempo = Color(0xFF239FB8), good = Color(0xFF4CAE6E), warn = Color(0xFFF0686C),
        encBlue = Color(0xFF7FA6FF), encGreen = Color(0xFF6CC48A), encWhite = Color(0xFF5A5560), encOrange = Color(0xFFFF9F6E),
        aurora = listOf(Color(0xFFFFB3C7), Color(0xFFA8D8FF), Color(0xFFB9F2D0), Color(0xFFFFD9A8)),
        sections = listOf(Color(0xFF239FB8), Color(0xFFFF8A8A), Color(0xFF8F78F0), Color(0xFFE472C4), Color(0xFF5A5560), Color(0xFF5F86F0), Color(0xFF5FB86F)),
    )
    val Terminal = Palette(
        "terminal", "TERMINAL", light = false,
        void = Color(0xFF000000), bg = Color(0xFF020702), panel = Color(0xFF061206), raised = Color(0xFF0F2C12), line = Color(0xFF1C5A22),
        text = Color(0xFF33FF66), muted = Color(0xFF1FA84A), dim = Color(0xFF178A3D), ink = Color(0xFF001A06),
        pads = listOf(Color(0xFF33FF66), Color(0xFFFFB000), Color(0xFF33FFFF), Color(0xFFB6FF33),
            Color(0xFFCCFFDD), Color(0xFFFFD580), Color(0xFF00C2A8), Color(0xFFFF7A45)),
        key = Color(0xFFFFB000), tempo = Color(0xFF33FFFF), good = Color(0xFF33FF66), warn = Color(0xFFFF5555),
        encBlue = Color(0xFF33FFFF), encGreen = Color(0xFF33FF66), encWhite = Color(0xFFCCFFDD), encOrange = Color(0xFFFFB000),
        aurora = listOf(Color(0xFF0F7A32), Color(0xFF0A4F2A), Color(0xFF0E6B6B), Color(0xFF6B4A00)),
        sections = listOf(Color(0xFF33FFFF), Color(0xFFFF7A45), Color(0xFFB6FF33), Color(0xFFFFD580), Color(0xFFCCFFDD), Color(0xFF00C2A8), Color(0xFF66B3FF)),
        monoUi = true, scanlines = 0.10f,
    )
    val Grayscale = Palette(
        "grayscale", "GRAYSCALE", light = false,
        void = Color(0xFF000000), bg = Color(0xFF0A0A0A), panel = Color(0xFF151515), raised = Color(0xFF2B2B2B), line = Color(0xFF3D3D3D),
        text = Color(0xFFF2F2F2), muted = Color(0xFF8C8C8C), dim = Color(0xFF6E6E6E), ink = Color(0xFF000000),
        // Eight lightness steps, alternating light / dark so neighbouring pads always contrast.
        pads = listOf(Color(0xFFFFFFFF), Color(0xFF767676), Color(0xFFD2D2D2), Color(0xFF999999),
            Color(0xFFB8B8B8), Color(0xFF686868), Color(0xFFE9E9E9), Color(0xFFA8A8A8)),
        // one colour in the whole theme: red, for clipping / record / stop, so a warning never looks like "fine"
        key = Color(0xFFFFFFFF), tempo = Color(0xFFC8C8C8), good = Color(0xFFBDBDBD), warn = Color(0xFFFF5C5C),
        encBlue = Color(0xFFFFFFFF), encGreen = Color(0xFFBDBDBD), encWhite = Color(0xFF8C8C8C), encOrange = Color(0xFFDDDDDD),
        aurora = listOf(Color(0xFF5A5A5A), Color(0xFF3C3C3C), Color(0xFF7A7A7A), Color(0xFF2A2A2A)),
        sections = listOf(Color(0xFFF2F2F2), Color(0xFFCDCDCD), Color(0xFFB4B4B4), Color(0xFF9B9B9B), Color(0xFFE6E6E6), Color(0xFF828282), Color(0xFFDADADA)),
    )
    /** The brand look: deep purple with neon green (and one hot orange), like the app icon. */
    val Genesis = Palette(
        "genesis", "GENESIS", light = false,
        void = Color(0xFF0E0718), bg = Color(0xFF150A24), panel = Color(0xFF21103A), raised = Color(0xFF371F5E), line = Color(0xFF5A3290),
        text = Color(0xFFF1EAFF), muted = Color(0xFFA48BD1), dim = Color(0xFF7E68AA), ink = Color(0xFF120820),
        pads = listOf(Color(0xFF9DFF3C), Color(0xFFB07CFF), Color(0xFFFF7A1A), Color(0xFF3CFFC2),
            Color(0xFFE04BFF), Color(0xFFD4FF3C), Color(0xFF6E8BFF), Color(0xFFFF3C6E)),
        key = Color(0xFF9DFF3C), tempo = Color(0xFF3CFFC2), good = Color(0xFF9DFF3C), warn = Color(0xFFFF7A1A),
        encBlue = Color(0xFF8C6BFF), encGreen = Color(0xFF9DFF3C), encWhite = Color(0xFFF1EAFF), encOrange = Color(0xFFFF7A1A),
        aurora = listOf(Color(0xFF7C3AED), Color(0xFF9DFF3C), Color(0xFF3CFFC2), Color(0xFFB07CFF)),
        sections = listOf(Color(0xFF3CFFC2), Color(0xFFFF7A1A), Color(0xFFB07CFF), Color(0xFFFF3C6E), Color(0xFFF1EAFF), Color(0xFF6E8BFF), Color(0xFFE04BFF)),
    )
    /** Night on the corner: sodium amber, hydrant red and the bodega's pink + cyan neon on deep navy. */
    val Bodega = Palette(
        "bodega", "BODEGA", light = false,
        void = Color(0xFF06070F), bg = Color(0xFF0B0D1A), panel = Color(0xFF141729), raised = Color(0xFF242A4A), line = Color(0xFF374070),
        text = Color(0xFFF3F1FF), muted = Color(0xFF9AA0C8), dim = Color(0xFF747AA6), ink = Color(0xFF0B0D1A),
        pads = listOf(Color(0xFFFF4FA3), Color(0xFF4FE3FF), Color(0xFFFFB547), Color(0xFFB8F34A),
            Color(0xFFA67CFF), Color(0xFFFF5A4F), Color(0xFF3FE0B0), Color(0xFF6FA8FF)),
        key = Color(0xFFFFB547), tempo = Color(0xFF4FE3FF), good = Color(0xFF3FE0B0), warn = Color(0xFFFF5A4F),
        encBlue = Color(0xFF6FA8FF), encGreen = Color(0xFF3FE0B0), encWhite = Color(0xFFF3F1FF), encOrange = Color(0xFFFF8A3D),
        aurora = listOf(Color(0xFFFF4FA3), Color(0xFF4FE3FF), Color(0xFF2E3560), Color(0xFFFFB547)),
        sections = listOf(Color(0xFF4FE3FF), Color(0xFFFF8A3D), Color(0xFFA67CFF), Color(0xFFFF4FA3), Color(0xFFF3F1FF), Color(0xFF6FA8FF), Color(0xFFB8F34A)),
    )
    /** Warm grey hardware in daylight: a classic sampler's casing, rubber-pad colours, an orange key. Reads well in the sun. */
    val Vintage = Palette(
        "vintage", "VINTAGE", light = true,
        void = Color(0xFFD4CDBE), bg = Color(0xFFDED8CB), panel = Color(0xFFE7E2D7), raised = Color(0xFFD6CEBE), line = Color(0xFFB3A992),
        text = Color(0xFF23211D), muted = Color(0xFF4F4A42), dim = Color(0xFF746C5E), ink = Color(0xFF1E1C19),
        pads = listOf(Color(0xFFE8604A), Color(0xFFEE8B32), Color(0xFFD4AA22), Color(0xFF6DAE52),
            Color(0xFF3FA3B0), Color(0xFF6A8FD8), Color(0xFF9A7CD0), Color(0xFFDA6A92)),
        key = Color(0xFFE0782A), tempo = Color(0xFF2F8C99), good = Color(0xFF5E9E4A), warn = Color(0xFFE0583C),
        encBlue = Color(0xFF3F6FB5), encGreen = Color(0xFF4F8F3E), encWhite = Color(0xFF3B3832), encOrange = Color(0xFFE0782A),
        aurora = listOf(Color(0xFFF0C9A0), Color(0xFFC9DCC0), Color(0xFFB9CFE0), Color(0xFFE6C0C8)),
        sections = listOf(Color(0xFF2F8C99), Color(0xFFE0782A), Color(0xFF7A5AA8), Color(0xFFC2507A), Color(0xFF3B3832), Color(0xFF3F6FB5), Color(0xFF4F8F3E)),
    )
    val all = listOf(Genesis, Studio, Bodega, Cyberpunk, Vintage, Pastel, Terminal, Grayscale)
    /** New installs start on the brand theme; a theme you picked is remembered. */
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Genesis
}

/**
 * The live palette. Every colour is read through the current theme (a snapshot state), so
 * switching themes recomposes/redraws whatever uses it. Names are kept from the original
 * palette: `Black` = ink on accents, `Void` = the deepest background.
 */
object Tb {
    var palette by mutableStateOf(Themes.Genesis)

    val Void get() = palette.void
    val Black get() = palette.ink

    /**
     * The colour to write ON a filled [bg]: the theme's dark ink or its light tone, whichever stands out
     * more. Dark themes: ink on bright accents, text on dark fills. Light themes (Pastel, Vintage): their
     * "text" and "white" accents are dark, so writing ink on them was dark-on-dark (selected tabs and
     * active buttons went blank); this picks the light tone there.
     */
    fun on(bg: Color): Color {
        val dark = palette.ink
        val light = if (palette.light) Color(0xFFFBF8F1) else palette.text
        val lb = bg.luminance()
        fun cr(c: Color): Float { val l = c.luminance(); return (maxOf(l, lb) + 0.05f) / (minOf(l, lb) + 0.05f) }
        return if (cr(dark) >= cr(light)) dark else light
    }
    /** Ink for a Tb.Text-filled background (selected tabs, the playhead's time). */
    val OnText get() = on(palette.text)
    val Bg get() = palette.bg
    val Panel get() = palette.panel
    val Raised get() = palette.raised
    val Line get() = palette.line
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Dim get() = palette.dim

    val Pads get() = palette.pads
    val Key get() = palette.key
    val Tempo get() = palette.tempo
    val Good get() = palette.good
    val Warn get() = palette.warn

    val EncBlue get() = palette.encBlue
    val EncGreen get() = palette.encGreen
    val EncWhite get() = palette.encWhite
    val EncOrange get() = palette.encOrange
    fun enc(c: com.thorbox.app.synth.EncoderColor) = when (c) {
        com.thorbox.app.synth.EncoderColor.BLUE -> EncBlue
        com.thorbox.app.synth.EncoderColor.GREEN -> EncGreen
        com.thorbox.app.synth.EncoderColor.WHITE -> EncWhite
        com.thorbox.app.synth.EncoderColor.ORANGE -> EncOrange
    }

    val Mono = FontFamily.Monospace
    val Readout get() = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 0.sp)
    val Label get() = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted)
}

@Composable
fun ThorboxTheme(content: @Composable () -> Unit) {
    val p = Tb.palette
    val scheme = if (p.light)
        lightColorScheme(primary = p.pads[0], onPrimary = p.ink, background = p.bg, surface = p.panel,
            onSurface = p.text, onBackground = p.text, surfaceVariant = p.raised, outline = p.line)
    else
        darkColorScheme(primary = p.pads[0], onPrimary = p.ink, background = p.bg, surface = p.panel,
            onSurface = p.text, onBackground = p.text, surfaceVariant = p.raised, outline = p.line)
    val type = if (p.monoUi) Typography().let { t ->
        fun TextStyle.m() = copy(fontFamily = FontFamily.Monospace)
        Typography(displayLarge = t.displayLarge.m(), displayMedium = t.displayMedium.m(), displaySmall = t.displaySmall.m(),
            headlineLarge = t.headlineLarge.m(), headlineMedium = t.headlineMedium.m(), headlineSmall = t.headlineSmall.m(),
            titleLarge = t.titleLarge.m(), titleMedium = t.titleMedium.m(), titleSmall = t.titleSmall.m(),
            bodyLarge = t.bodyLarge.m(), bodyMedium = t.bodyMedium.m(), bodySmall = t.bodySmall.m(),
            labelLarge = t.labelLarge.m(), labelMedium = t.labelMedium.m(), labelSmall = t.labelSmall.m())
    } else Typography()
    // Text() takes bodyLarge unless told otherwise, and its line height is a fixed 24 sp. Our labels are
    // mostly 8-14 sp, so they sat low in a 24 sp line and got clipped by small chips (the drum rows,
    // pad tabs…). Line height relative to the font size, centred, fixes every one of them at once.
    val tight = type.copy(bodyLarge = type.bodyLarge.copy(lineHeight = 1.2.em,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)))
    MaterialTheme(colorScheme = scheme, typography = tight) {
        // Our own press/focus indication (FocusRing.kt): the NAV cursor's ring on every clickable.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides FocusRingIndication, content = content)
    }
}

/** CRT scanlines over a whole screen (TERMINAL / CYBERPUNK). Its own layer: drawn once, never per frame. */
@Composable
fun Scanlines(modifier: Modifier = Modifier) {
    val k = Tb.palette.scanlines
    if (k <= 0f) return
    val c = Color.Black
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) { drawLine(c.copy(alpha = k * 2.2f), Offset(0f, y), Offset(size.width, y), step * 0.45f); y += step }
    }
}
