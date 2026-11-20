// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrumKitTest {
    @Test fun factoryKitsAreWellFormed() {
        for (k in FactoryPresets.kits) {
            assertEquals(DRUM_SLOTS, k.kit.voices.size, "${k.name}: 8 voices")
            val b = k.kit.bus
            assertTrue(listOf(b.crunch, b.muffle, b.tape, b.dust).all { it in 0f..1f }, "${k.name}: bus in range")
            for (v in k.kit.voices) assertTrue(v.decayMs in 5f..4000f && v.tune in -24f..24f && v.levelDb in -40f..6f, "${k.name}/${v.name}")
        }
        val names = FactoryPresets.kits.map { it.name }
        assertTrue(names.containsAll(listOf("DRUNK SWING", "CRATE DUST", "VILLAIN", "SOUL CHOP")))
        assertEquals(names.size, names.toSet().size, "kit names unique")
    }

    @Test fun kitsSavedBeforeTheBusStillLoad() {
        val old = """[{"name":"MINE","kit":{"name":"MINE","voices":[{"name":"KICK","model":"KICK"}]}}]"""
        val k = PresetCodec.decodeKits(old).single()
        assertEquals(DrumBus(), k.kit.bus)
        val round = PresetCodec.decodeKits(PresetCodec.encodeKits(listOf(KitPreset("X", DrumKit("X", bus = DrumBus(crunch = 0.6f))))))
        assertEquals(0.6f, round.single().kit.bus.crunch)
    }
}
