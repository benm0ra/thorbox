// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every theme stays readable: a guard for future palette edits (WCAG contrast ratios). */
class ThemeContrastTest {
    private fun cr(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test fun textOnPanels() {
        for (p in Themes.all) {
            assertTrue(cr(p.text, p.panel) >= 7f, "${p.label}: text on panel ${cr(p.text, p.panel)}")
            assertTrue(cr(p.muted, p.panel) >= 4.5f, "${p.label}: muted on panel ${cr(p.muted, p.panel)}")
            assertTrue(cr(p.dim, p.panel) >= 3f, "${p.label}: dim on panel ${cr(p.dim, p.panel)}")
            assertTrue(cr(p.raised, p.panel) >= 1.18f, "${p.label}: controls stand off panels ${cr(p.raised, p.panel)}")
        }
    }

    @Test fun whateverWeWriteOnAFillIsReadable() {
        val saved = Tb.palette
        try {
            for (p in Themes.all) {
                Tb.palette = p
                val fills = p.pads + listOf(p.key, p.tempo, p.good, p.warn, p.encBlue, p.encGreen, p.encWhite, p.encOrange, p.text)
                for (f in fills) assertTrue(cr(Tb.on(f), f) >= 3f, "${p.label}: on(${f}) only ${cr(Tb.on(f), f)}")
            }
        } finally { Tb.palette = saved }
    }

    @Test fun warningsDontLookLikeFine() {
        for (p in Themes.all) {
            val d = kotlin.math.abs(p.good.luminance() - p.warn.luminance()) + kotlin.math.abs(p.good.red - p.warn.red)
            assertTrue(d > 0.15f, "${p.label}: good and warn too alike")
        }
    }
}
