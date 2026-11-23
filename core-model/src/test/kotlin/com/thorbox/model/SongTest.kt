// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongTest {
    @Test fun patternsSongAndKeyLockSaveAndLoad() {
        val clip = Clip(ClipId("t0"), "T1", color = 0, lengthTicks = 3840, notes = listOf(NoteEvent(tick = 0, lengthTicks = 120, note = 3, velocity = 0.9f)))
        val p = ProjectFile(
            patterns = listOf(PatternSlot("VERSE", listOf(clip)), PatternSlot(), PatternSlot("HOOK", listOf(clip.copy(lengthTicks = 7680)))),
            currentPattern = 2, song = listOf(SongStep(0, 2), SongStep(2, 1)), songMode = true, songLoop = false,
            scale = ScaleDto(root = "F", mode = "MINOR", followSample = false, lockAll = true),
        )
        val back = ProjectCodec.decode(ProjectCodec.encode(p))!!
        assertEquals(p.patterns, back.patterns)
        assertEquals(2, back.currentPattern)
        assertEquals(listOf(SongStep(0, 2), SongStep(2, 1)), back.song)
        assertTrue(back.songMode && !back.songLoop && back.scale.lockAll)
    }

    @Test fun olderProjectsHaveNoPatterns() {
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectFile()))!!
        assertTrue(back.patterns.isEmpty() && back.song.isEmpty() && !back.scale.lockAll)
    }
}
