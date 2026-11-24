// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ChopStepsTest {
    @Test fun chopsLandOnTheirOriginalSteps() {
        // a 1-bar loop (16 steps), slices at steps 0, 4, 8, 12
        val f = 160_000L
        val regs = listOf(Region(0, 40_000), Region(40_000, 80_000), Region(80_000, 120_000), Region(120_000, f))
        assertContentEquals(intArrayOf(0, 4, 8, 12), ChopLogic.chopSteps(regs, f, 16, 16))
    }

    @Test fun twoChopsOnOneStepDontStack() {
        val f = 16_000L
        val regs = listOf(Region(0, 400), Region(400, 900), Region(900, f))   // both round to step 0 / 1
        assertContentEquals(intArrayOf(0, 1, 2), ChopLogic.chopSteps(regs, f, 16, 16))
    }

    @Test fun chopsPastTheEndAreDropped() {
        val f = 100L
        val regs = listOf(Region(0, 50), Region(99, 100))
        assertContentEquals(intArrayOf(0, -1), ChopLogic.chopSteps(regs, f, 128, 64))
    }
}
