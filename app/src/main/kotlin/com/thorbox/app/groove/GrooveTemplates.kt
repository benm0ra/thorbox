// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.roundToInt

/**
 * Groove templates: the timing + accent "feel" a track plays with, applied per track on top of
 * the global swing (like MPC swing per sequence, or Ableton's groove pool per clip). Each template
 * is 16 sixteenth-note slots: a timing offset in ticks (960 PPQN, + = late) and a velocity scale.
 * AMOUNT 0..1 blends from straight to the full template. The stored notes are never changed:
 * the feel is applied when the pattern is sent to the engine, so you can switch it freely.
 */
enum class GrooveTemplate(val label: String, val blurb: String, private val offsets: IntArray, private val accents: FloatArray, val jitter: Int = 0) {
    STRAIGHT("STRAIGHT", "On the grid. Machine-tight.", IntArray(16), FloatArray(16) { 1f }),
    MPC60("MPC 60", "The classic: late 16ths (≈62% swing) with soft off-beats.",
        IntArray(16) { if (it % 2 == 1) 58 else 0 },
        FloatArray(16) { when { it % 4 == 0 -> 1f; it % 2 == 0 -> 0.9f; else -> 0.78f } }),
    SP1200("SP-1200", "Gritty, tighter shuffle (≈56%) with punchy downbeats.",
        IntArray(16) { if (it % 2 == 1) 29 else 0 },
        FloatArray(16) { if (it % 4 == 0) 1f else 0.85f }),
    DILLA("DILLA", "Drunk on purpose: late off-beats, lazy backbeat, nothing quite on the grid.",
        intArrayOf(0, 46, 14, 52, 26, 40, 18, 58, 0, 44, 12, 50, 30, 42, 16, 60),
        floatArrayOf(1f, 0.72f, 0.86f, 0.7f, 0.95f, 0.74f, 0.84f, 0.68f, 0.98f, 0.73f, 0.85f, 0.7f, 0.96f, 0.75f, 0.83f, 0.69f), jitter = 10),
    BOOMBAP("BOOM BAP", "Head-nod swing (≈60%) with the snare sat back a hair.",
        IntArray(16) { when { it % 2 == 1 -> 48; it == 4 || it == 12 -> 12; else -> 0 } },
        FloatArray(16) { when { it == 0 || it == 8 -> 1f; it == 4 || it == 12 -> 0.97f; it % 2 == 0 -> 0.85f; else -> 0.74f } }),
    TRAP("TRAP", "Straight timing, rolling hat dynamics (loud-soft-soft-loud).",
        IntArray(16),
        FloatArray(16) { when (it % 4) { 0 -> 1f; 1 -> 0.62f; 2 -> 0.8f; else -> 0.66f } }),
    HOUSE("HOUSE", "Light 16th shuffle (≈54%) and pushed off-beat 8ths, for hats and chops.",
        IntArray(16) { if (it % 2 == 1) 19 else 0 },
        FloatArray(16) { when { it % 4 == 2 -> 1f; it % 4 == 0 -> 0.92f; else -> 0.72f } });

    /** New tick + velocity for a note at [tick] in a loop of [len] ticks, at [amount] 0..1. */
    fun apply(tick: Int, velocity: Float, len: Int, amount: Float, seed: Int): Pair<Int, Float> {
        if (this == STRAIGHT || amount <= 0f) return tick to velocity
        val pos = tick.toFloat() / STEP_TICKS
        val slot = pos.roundToInt()
        if (kotlin.math.abs(pos - slot) > 0.35f) return tick to velocity      // off-grid (live-played): leave its own feel
        val i = ((slot % 16) + 16) % 16
        var off = offsets[i] * amount
        if (jitter > 0) off += (((seed * 1103515245 + slot * 12345) ushr 8) % (2 * jitter + 1) - jitter) * amount
        val t = ((tick + off.roundToInt()) % len + len) % len
        val v = (velocity * (1f + (accents[i] - 1f) * amount)).coerceIn(0.05f, 1f)
        return t to v
    }
}

/** A track's feel. */
data class TrackFeel(val template: GrooveTemplate = GrooveTemplate.STRAIGHT, val amount: Float = 1f)
