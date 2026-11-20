// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizeMathTest {
    @Test fun padsEndUpEquallyLoudWithoutClipping() {
        val peak = floatArrayOf(-3f, -12f, -20f, -6f)
        val loud = floatArrayOf(-9f, -18f, -26f, -12f)          // all crest factor 6 dB
        val g = NormalizeMath.gains(peak, loud)
        val after = loud.indices.map { loud[it] + g[it] }
        assertTrue(after.max() - after.min() < 0.2f, "levels after: $after")
        assertTrue(peak.indices.all { peak[it] + g[it] <= -0.95f }, "no peak above −1 dBFS")
    }

    @Test fun oneSpikyPadDoesNotHoldTheRestBack() {
        val peak = floatArrayOf(-1f, -10f, -10f, -10f, -10f)
        val loud = floatArrayOf(-30f, -16f, -16f, -16f, -16f)    // pad 0: a click, huge crest
        val g = NormalizeMath.gains(peak, loud)
        assertTrue(g[1] > 8f, "the normal pads still come up (${g[1]})")
        assertEquals(0f, g[0], 0.05f)                            // the spike can't go up without clipping
    }

    @Test fun silentPadsLeftAloneAndGainBounded() {
        val g = NormalizeMath.gains(floatArrayOf(-120f, -60f, -3f), floatArrayOf(-120f, -66f, -9f))
        assertEquals(0f, g[0])
        assertTrue(g.all { it in -24f..24f })
    }
}
