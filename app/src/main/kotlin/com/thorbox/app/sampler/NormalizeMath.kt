// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

/** Pure maths for the sampler's NORMALIZE (unit-tested). */
object NormalizeMath {
    const val CEILING_DB = -1f      // no pad's peak goes above this
    const val MAX_GAIN = 24f

    /**
     * Per-pad gains (dB) from each pad's [peakDb] and [loudDb] (loudest 50 ms RMS). Silent pads
     * (below −80 dB) are left alone. Each pad aims at a shared loudness: the median of how loud
     * every pad could get without clipping, so one spiky hit can't hold the others back.
     */
    fun gains(peakDb: FloatArray, loudDb: FloatArray): FloatArray {
        val n = minOf(peakDb.size, loudDb.size)
        val live = (0 until n).filter { loudDb[it] > -80f }
        val out = FloatArray(n)
        if (live.isEmpty()) return out
        val reach = live.map { i -> loudDb[i] + minOf(CEILING_DB - peakDb[i], MAX_GAIN) }.sorted()
        val target = if (reach.size % 2 == 1) reach[reach.size / 2] else (reach[reach.size / 2 - 1] + reach[reach.size / 2]) / 2f
        for (i in live) {
            val g = minOf(target - loudDb[i], CEILING_DB - peakDb[i])
            out[i] = (Math.round(g.coerceIn(-MAX_GAIN, MAX_GAIN) * 10f) / 10f)
        }
        return out
    }
}
