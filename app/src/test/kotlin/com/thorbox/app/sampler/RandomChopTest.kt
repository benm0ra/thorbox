// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomChopTest {
    private val frames = 48_000L * 8                                  // an 8 s loop
    private val hits = LongArray(40) { it * 9_600L + 1_000 }            // a hit every 200 ms

    @Test fun sixteenSlicesCoverTheSampleInOrder() {
        val r = ChopLogic.random(frames, hits, seed = 7)
        assertEquals(16, r.size)
        assertEquals(0L, r.first().start); assertEquals(frames, r.last().end)
        for (i in 1 until r.size) assertEquals(r[i - 1].end, r[i].start)
        assertTrue(r.all { it.end - it.start >= frames / 64 })
    }

    @Test fun cutsLandOnHitsWhenThereAreEnough() {
        val r = ChopLogic.random(frames, hits, seed = 3)
        assertTrue(r.drop(1).all { it.start in hits })
    }

    @Test fun sameSeedSameChopNewSeedNewChop() {
        assertEquals(ChopLogic.random(frames, hits, 11), ChopLogic.random(frames, hits, 11))
        assertNotEquals(ChopLogic.random(frames, hits, 11), ChopLogic.random(frames, hits, 12))
    }

    @Test fun noHitsStillGivesSixteen() {
        val r = ChopLogic.random(frames, LongArray(0), seed = 5)
        assertEquals(16, r.size)
        assertTrue(r.all { it.end - it.start >= frames / 64 })
    }

    @Test fun tinySampleFallsBackToEqual() {
        assertEquals(ChopLogic.equal(1000, 16), ChopLogic.random(1000, LongArray(0), 1))
    }
}
