// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import com.thorbox.model.MusicalKey
import com.thorbox.model.ScaleMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** A slice of the ORIGINAL sample, in frames: [start, end). */
data class Region(val start: Long, val end: Long) { val length get() = end - start }

/** Pure chopping maths (no Android), so it's unit-tested on the JVM. */
object ChopLogic {
    const val PADS = 16
    private const val MIN_SLICE = 256L   // frames (~5 ms): markers can't cross or collapse

    /**
     * SEQ CHOPS: where each slice lands on a 1/16 grid of [totalSteps] steps that spans the whole
     * sample, so the pattern plays the chops back in their original order and timing. Two slices that
     * round onto one step: the later one moves to the next free step (or is dropped if there's none).
     * Returns a step per slice (-1 = didn't fit).
     */
    fun chopSteps(regions: List<Region>, frames: Long, totalSteps: Int, maxSteps: Int = 64): IntArray {
        val out = IntArray(regions.size) { -1 }
        if (frames <= 0 || totalSteps <= 0) return out
        val taken = BooleanArray(maxSteps)
        regions.forEachIndexed { i, r ->
            var s = (r.start.toDouble() / frames * totalSteps).roundToInt()
            while (s < maxSteps && taken[s]) s++
            if (s in 0 until maxSteps) { taken[s] = true; out[i] = s }
        }
        return out
    }

    /** n equal slices across the sample. */
    fun equal(frames: Long, n: Int): List<Region> {
        val k = n.coerceIn(1, PADS)
        return (0 until k).map { i -> Region(frames * i / k, frames * (i + 1) / k) }
    }

    /** Slices from ascending onset frames: each runs to the next onset (the last to the end). */
    fun fromStarts(starts: List<Long>, frames: Long): List<Region> {
        val s = (listOf(0L) + starts).filter { it in 0 until frames }.distinct().sorted().take(PADS)
        return s.mapIndexed { i, st -> Region(st, if (i + 1 < s.size) s[i + 1] else frames) }
    }

    /**
     * Moves the START marker of slice [index] (also the end of the slice before it), clamped
     * between its neighbours so slices never cross or vanish. Slice 0 always starts at 0.
     */
    fun moveStart(regions: List<Region>, index: Int, newStart: Long): List<Region> {
        if (index <= 0 || index >= regions.size) return regions
        val lo = regions[index - 1].start + MIN_SLICE
        val hi = regions[index].end - MIN_SLICE
        if (lo > hi) return regions
        val s = newStart.coerceIn(lo, hi)
        return regions.mapIndexed { i, r ->
            when (i) { index - 1 -> r.copy(end = s); index -> r.copy(start = s); else -> r }
        }
    }

    /**
     * Lazy chop: drop a marker at [frame] for pad [pad]. Pads are kept in time order, so the
     * slice list is rebuilt from all markers dropped so far (plus 0) and sorted.
     */
    fun lazyDrop(markers: MutableList<Long>, frame: Long, frames: Long): List<Region> {
        if (markers.none { abs(it - frame) < MIN_SLICE } && markers.size < PADS - 1) markers += frame.coerceIn(MIN_SLICE, frames - MIN_SLICE)
        return fromStarts(markers.sorted(), frames)
    }

    /**
     * KEY MATCH: the smallest pitch move (−5…+6 semitones) that puts the sample's scale onto the
     * project's. Major and minor keys are compared through their relative major, so a sample in
     * A minor needs no shift for a C major project. Chromatic either side means no shift.
     */
    fun keyMatchSemis(sample: MusicalKey, project: MusicalKey): Int {
        if (sample.mode == ScaleMode.CHROMATIC || project.mode == ScaleMode.CHROMATIC) return 0
        fun majorCentre(k: MusicalKey) = when (k.mode) {
            ScaleMode.MINOR, ScaleMode.DORIAN, ScaleMode.PENTATONIC_MINOR -> (k.root.ordinal + 3) % 12
            else -> k.root.ordinal
        }
        val d = Math.floorMod(majorCentre(project) - majorCentre(sample), 12)
        return if (d > 6) d - 12 else d
    }

    /**
     * BPM SYNC: the tempo ratio that lands the sample on the project tempo. The sample's BPM may
     * have been read at half or double time, so we pick whichever of ratio, ratio×2, ratio÷2 is
     * closest to 1: the least stretch, which sounds best.
     */
    fun syncRatio(sampleBpm: Float, projectBpm: Float): Double {
        if (sampleBpm <= 0f || projectBpm <= 0f) return 1.0
        val r = projectBpm.toDouble() / sampleBpm
        return listOf(r, r * 2, r / 2).minBy { abs(ln(it)) }.coerceIn(0.25, 4.0)
    }

    /** "93 → 140" label for the sync button, with the effective (octave-corrected) source tempo. */
    fun syncLabel(sampleBpm: Float, projectBpm: Float): String {
        val r = syncRatio(sampleBpm, projectBpm)
        val effective = projectBpm / r
        return "${effective.roundToInt()}→${projectBpm.roundToInt()}"
    }
}
