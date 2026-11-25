// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MasterBusTest {
    @Test fun aNewProjectHasOnlyTheLimiter() {
        val m = MasterBus()
        assertTrue(m.limit.on); assertFalse(m.glue.on); assertFalse(m.eq.shaped)
        assertEquals(-0.3f, m.limit.ceilingDb)
    }

    @Test fun glueCurveMatchesTheEngine() {
        val g = MasterBus.Glue(thresholdDb = -20f, ratio = 4f)
        assertTrue(abs(g.curveDb(-40f) - -40f) < 1e-4f, "below the knee: untouched")
        assertTrue(abs(g.curveDb(-8f) - (-20f + 12f / 4f)) < 1e-3f, "12 dB over at 4:1 → 3 dB over")   // reductionDb in Ducker.h
        val s = MasterBus.Glue(thresholdDb = -20f, ratio = 4f).curveDb(-20f)
        assertTrue(s < -20f && s > -21f, "the soft knee bends at the threshold ($s)")
    }

    @Test fun clampAndAutoRelease() {
        val g = MasterBus.Glue(releaseMs = 5f, scHpHz = 10f, ratio = 0.2f, mix = 3f).clamped()
        assertEquals(0f, g.releaseMs); assertEquals(0f, g.scHpHz); assertEquals(1f, g.ratio); assertEquals(1f, g.mix)
        val l = MasterBus.Limit(ceilingDb = 2f, inputDb = -3f).clamped()
        assertEquals(0f, l.ceilingDb); assertEquals(0f, l.inputDb)
    }

    @Test fun engineOrder() {
        assertEquals(8, MasterBus.Glue().values().size)
        assertEquals(4, MasterBus.Limit().values().size)
        assertEquals(1f, MasterBus.Limit().values()[0])
    }

    @Test fun presetsAreValidAndDistinct() {
        val base = MasterBus()
        val outs = MasterBus.PRESETS.map { it.second(base) }
        outs.forEach { assertEquals(it, it.clamped()) }
        assertEquals(outs.size, outs.toSet().size)
    }

    @Test fun savedAndOpenedAgain() {
        val m = MasterBus(eq = TrackEq(hpHz = 30f), glue = MasterBus.Glue(on = true, ratio = 4f), limit = MasterBus.Limit(inputDb = 4f), targetLufs = -11f)
        assertEquals(m, m.toDto().toMasterBus())
        val bad = m.toDto().copy(limit = com.thorbox.model.LimitDto(ceilingDb = Float.NaN))
        assertEquals(MasterBus(eq = m.eq), bad.toMasterBus())
    }
}
