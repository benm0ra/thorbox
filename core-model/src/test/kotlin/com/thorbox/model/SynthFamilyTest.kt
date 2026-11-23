// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthFamilyTest {
    @Test fun factoryPresetsMostlyGuessTheirOwnFamily() {
        val all = FactoryPresets.synth
        val hits = all.count { guessCategory(it.patch) == it.category }
        assertTrue(hits >= all.size * 0.85, "only $hits of ${all.size} factory presets guessed right")
    }

    @Test fun every808IsAn808() {
        FactoryPresets.synth.filter { it.category == SynthCategory.SUB808 }.forEach {
            assertEquals(SynthCategory.SUB808, guessCategory(it.patch), it.name)
        }
    }

    @Test fun initPatchIsKeys() = assertEquals(SynthCategory.KEYS, guessCategory(SynthPatch()))
}
