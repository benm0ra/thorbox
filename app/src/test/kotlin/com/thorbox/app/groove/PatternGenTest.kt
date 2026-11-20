// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import com.thorbox.model.MusicalKey
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PatternGenTest {
    private val p = GenParams()
    private fun sig(n: List<com.thorbox.model.NoteEvent>) = n.map { Triple(it.tick, it.note, it.ratchet) }

    @Test fun sameSeedSameRoll() {
        val k = MusicalKey(PitchClass.A, ScaleMode.MINOR)
        assertEquals(sig(PatternGen(42).melody(16, p, k, 57)), sig(PatternGen(42).melody(16, p, k, 57)))
        assertEquals(sig(PatternGen(42).beat(16, p)), sig(PatternGen(42).beat(16, p)))
    }

    @Test fun melodiesVaryAcrossRolls() {
        val k = MusicalKey(PitchClass.C, ScaleMode.MINOR)
        val rolls = (1..60).map { sig(PatternGen(it * 7919).melody(16, p, k, 48)) }.toSet()
        assertTrue(rolls.size >= 55, "only ${rolls.size} distinct melodies in 60 rolls")
    }

    @Test fun beatsVaryAndUseEveryStyle() {
        val styles = HashSet<String>()
        val rolls = (1..60).map { val g = PatternGen(it * 104729); sig(g.beat(16, p)).also { _ -> styles += g.style } }.toSet()
        assertTrue(rolls.size >= 55, "only ${rolls.size} distinct beats in 60 rolls")
        assertEquals(PatternGen.BeatStyle.entries.size, styles.size, "styles seen: $styles")
    }

    /** The old pad dice gave the same steps every time when "step 1 always hits" was on. */
    @Test fun padRollsVaryWithAnchorOn() {
        val rolls = (1..40).map { PatternGen(it * 31337).drums(16, p.copy(anchorRoot = true), pad = 3) }
        rolls.forEach { r -> assertTrue(r.any { it.tick == 0 }, "anchored roll must hit step 1"); assertTrue(r.all { it.note == 3 }) }
        val distinct = rolls.map { r -> r.map { it.tick } }.toSet()
        assertTrue(distinct.size >= 25, "only ${distinct.size} distinct pad rhythms in 40 rolls")
    }

    @Test fun melodiesStayInKeyAndInRange() {
        for (mode in ScaleMode.entries) for (seed in 1..40) for (steps in listOf(8, 16, 32, 64)) {
            val k = MusicalKey(PitchClass.Fs, mode)
            val notes = PatternGen(seed).melody(steps, p.copy(rangeOctaves = 1 + seed % 3), k, 48 + k.root.ordinal)
            assertTrue(notes.isNotEmpty(), "$mode/$seed/$steps empty")
            for (n in notes) {
                assertTrue(Scales.inScale(n.note, k), "$mode: ${n.note} out of key")
                assertTrue(n.note in 24..96, "$mode: ${n.note} out of range")
                assertTrue(n.tick in 0 until steps * STEP_TICKS)
            }
        }
    }

    @Test fun beatsAreWellFormed() {
        for (seed in 1..60) for (steps in listOf(8, 12, 16, 32, 64)) {
            val notes = PatternGen(seed).beat(steps, p)
            assertTrue(notes.all { it.note in 0..7 && it.tick in 0 until steps * STEP_TICKS })
            assertTrue(notes.any { it.note == 0 && it.tick == 0 }, "kick on the one ($seed/$steps)")
            assertEquals(notes.size, notes.map { it.note to it.tick }.toSet().size, "no stacked duplicates")
        }
    }

    @Test fun avoidSkipsLastStyle() {
        for (seed in 1..50) {
            val g = PatternGen(seed); g.beat(16, p, avoid = PatternGen.BeatStyle.TRAP)
            assertNotEquals(PatternGen.BeatStyle.TRAP, g.beatStyle)
            val m = PatternGen(seed); m.melody(16, p, MusicalKey(PitchClass.C, ScaleMode.MAJOR), 48, avoid = PatternGen.MelodyStyle.ARP)
            assertNotEquals(PatternGen.MelodyStyle.ARP, m.melodyStyle)
        }
    }
}
