// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The curve drawn on the EQ page must be the one the engine applies (eq_test.cpp checks the same points). */
class TrackEqTest {
    private fun near(want: Float, got: Float, tol: Float) = assertTrue(abs(want - got) <= tol, "want $want ± $tol, got $got")

    @Test fun flatIsFlat() {
        val e = TrackEq()
        assertFalse(e.shaped)
        for (f in listOf(20f, 100f, 1000f, 10000f, 19000f)) near(0f, e.responseDb(f), 1e-4f)
    }

    @Test fun filtersMatchTheEngine() {
        near(-3.01f, TrackEq(hpHz = 1000f).responseDb(1000f), 0.05f)
        near(-36.1f, TrackEq(hpHz = 1000f).responseDb(125f), 1f)
        near(-48f, TrackEq(hpHz = 1000f, hp24 = true).responseDb(250f), 1f)
        near(-3.01f, TrackEq(lpHz = 1000f).responseDb(1000f), 0.05f)
        near(0f, TrackEq(lpHz = 1000f).responseDb(100f), 0.1f)
    }

    @Test fun bandsMatchTheEngine() {
        near(6f, TrackEq(midDb = 6f, midHz = 1000f, midQ = 1f).responseDb(1000f), 0.01f)
        near(6f, TrackEq(lowDb = 6f, lowHz = 100f).responseDb(20f), 0.3f)
        near(-6f, TrackEq(highDb = -6f, highHz = 6000f).responseDb(18000f), 0.5f)
    }

    @Test fun bypassDrawsFlatButKeepsTheSettings() {
        val e = TrackEq(on = false, midDb = 9f)
        near(0f, e.responseDb(1000f), 1e-4f)
        assertTrue(e.shaped)
    }

    @Test fun clampAndEngineOrder() {
        val c = TrackEq(hpHz = 1f, lpHz = 1e6f, midDb = 40f, midQ = 0f).clamped()
        assertEquals(TrackEq.HP_OFF, c.hpHz); assertEquals(TrackEq.LP_OFF, c.lpHz); assertEquals(TrackEq.MAX_DB, c.midDb); assertEquals(0.2f, c.midQ)
        val v = TrackEq(hpHz = 80f, hp24 = true).values()
        assertEquals(TrackEq.Param.COUNT, v.size)
        assertEquals(80f, v[TrackEq.Param.HP_HZ]); assertEquals(24f, v[TrackEq.Param.HP_SLOPE]); assertEquals(1f, v[TrackEq.Param.ON])
    }

    @Test fun savedAndOpenedAgain() {
        val e = TrackEq(hpHz = 90f, lowDb = -2f, midHz = 2500f, highDb = 1.5f, lp24 = true)
        assertEquals(e, e.toDto().toTrackEq())
        assertEquals(TrackEq(), com.thorbox.model.EqDto(midDb = Float.NaN).toTrackEq())
    }
}
