// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuckTest {
    @Test fun presetsAreInRangeAndDistinct() {
        assertEquals(4, DuckPresets.all.size)
        for (p in DuckPresets.all) {
            val s = DuckSettings().withPreset(p)
            assertEquals(s, s.clamped(), "${p.name} is inside the engine's ranges")
        }
        assertEquals(DuckPresets.all.size, DuckPresets.all.map { it.releaseMs }.toSet().size, "every preset bounces differently")
    }

    @Test fun presetsKeepTheRouting() {
        val routed = DuckSettings(source = 2, sourceSlot = 0, targets = 0b1011)
        val s = routed.withPreset(DuckPresets.all[1])
        assertEquals(2, s.source); assertEquals(0, s.sourceSlot); assertEquals(0b1011, s.targets)
        assertEquals("HEAVY DILLA PUMP", s.preset)
    }

    @Test fun targetsToggle() {
        var s = DuckSettings(source = 2)
        assertFalse(s.on, "no targets = nothing ducks")
        s = s.withTarget(0, true).withTarget(3, true)
        assertTrue(s.isTarget(0) && s.isTarget(3) && !s.isTarget(1) && s.on)
        s = s.withTarget(0, false)
        assertTrue(!s.isTarget(0) && s.isTarget(3))
    }

    @Test fun savesAndLoads() {
        val p = ProjectFile(duck = DuckSettings(source = DuckSettings.BEAT, targets = 3).withPreset(DuckPresets.all[3]))
        val back = ProjectCodec.decode(ProjectCodec.encode(p))!!
        assertEquals(p.duck, back.duck)
        assertEquals(null, ProjectCodec.decode(ProjectCodec.encode(ProjectFile()))!!.duck, "older projects: no DUCK block")
    }
}
