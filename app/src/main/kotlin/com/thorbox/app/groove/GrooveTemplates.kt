// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.roundToInt

/**
 * Groove templates: the timing + accent "feel" a track plays with, applied per track on top of
 * the global swing (like MPC swing per sequence, but per track). Each template
 * is 16 sixteenth-note slots: a timing offset in ticks (960 PPQN, + = late) and a velocity scale.
 * AMOUNT 0..1 blends from straight to the full template. The stored notes are never changed:
 * the feel is applied when the pattern is sent to the engine, so you can switch it freely.
 */
enum class GrooveTemplate(
    val label: String, val blurb: String, private val offsets: IntArray, private val accents: FloatArray, val jitter: Int = 0,
    /** Drum tracks only: extra push (+ late) per drum-machine slot (kick, snare, clap, c.hat, o.hat, tom, perc, cymbal). */
    private val slotShift: IntArray? = null,
    /** Drum tracks only: per-slot random timing spread (± ticks, fixed per step so the loop repeats). */
    private val slotJitter: IntArray? = null,
) {
    STRAIGHT("STRAIGHT", "On the grid. Machine-tight.", IntArray(16), FloatArray(16) { 1f }),
    MPC60("MPC 60", "The classic: late 16ths (≈62% swing) with soft off-beats.",
        IntArray(16) { if (it % 2 == 1) 58 else 0 },
        FloatArray(16) { when { it % 4 == 0 -> 1f; it % 2 == 0 -> 0.9f; else -> 0.78f } },
        slotShift = intArrayOf(0, 6, 8, 0, 0, 0, 4, 0), slotJitter = intArrayOf(2, 3, 3, 4, 4, 3, 4, 2)),
    GRIT("GRIT", "Gritty, tighter shuffle (≈56%) with punchy downbeats.",
        IntArray(16) { if (it % 2 == 1) 29 else 0 },
        FloatArray(16) { if (it % 4 == 0) 1f else 0.85f },
        slotShift = intArrayOf(0, 4, 6, 0, 0, 0, 0, 0)),
    DRUNK("DRUNK", "Drunk on purpose: every drum on its own clock. Kick wanders, snare drags, the clap flams in late behind it, hats float loose.",
        intArrayOf(0, 46, 14, 52, 26, 40, 18, 58, 0, 44, 12, 50, 30, 42, 16, 60),
        floatArrayOf(1f, 0.72f, 0.86f, 0.7f, 0.95f, 0.74f, 0.84f, 0.68f, 0.98f, 0.73f, 0.85f, 0.7f, 0.96f, 0.75f, 0.83f, 0.69f), jitter = 6,
        slotShift = intArrayOf(4, 26, 44, 0, 6, 14, 20, 0), slotJitter = intArrayOf(22, 8, 10, 16, 12, 12, 14, 6)),
    LAZY("LAZY", "Laid all the way back: steady kick, the snare dragging well behind the beat, lazy swung hats. Head-nod heavy.",
        IntArray(16) { if (it % 2 == 1) 52 else 0 },
        FloatArray(16) { when { it == 0 || it == 8 -> 1f; it == 4 || it == 12 -> 1f; it % 2 == 0 -> 0.8f; else -> 0.66f } }, jitter = 3,
        slotShift = intArrayOf(0, 40, 48, 12, 12, 20, 24, 6), slotJitter = intArrayOf(3, 5, 6, 8, 6, 6, 8, 4)),
    BOOMBAP("BOOM BAP", "Head-nod swing (≈60%) with the snare sat back a hair.",
        IntArray(16) { when { it % 2 == 1 -> 48; it == 4 || it == 12 -> 12; else -> 0 } },
        FloatArray(16) { when { it == 0 || it == 8 -> 1f; it == 4 || it == 12 -> 0.97f; it % 2 == 0 -> 0.85f; else -> 0.74f } },
        slotShift = intArrayOf(0, 8, 16, 0, 0, 4, 6, 0), slotJitter = intArrayOf(3, 4, 5, 6, 5, 4, 6, 3)),
    TRAP("TRAP", "Straight timing, rolling hat dynamics (loud-soft-soft-loud).",
        IntArray(16),
        FloatArray(16) { when (it % 4) { 0 -> 1f; 1 -> 0.62f; 2 -> 0.8f; else -> 0.66f } }),
    HOUSE("HOUSE", "Light 16th shuffle (≈54%) and pushed off-beat 8ths, for hats and chops.",
        IntArray(16) { if (it % 2 == 1) 19 else 0 },
        FloatArray(16) { when { it % 4 == 2 -> 1f; it % 4 == 0 -> 0.92f; else -> 0.72f } });

    /**
     * New tick + velocity for a note at [tick] in a loop of [len] ticks, at [amount] 0..1.
     * [drumSlot] (0..7, drum-machine tracks only) adds that drum's own push and looseness, so the
     * kick, snare and hats sit differently against the grid, the way a played or hand-placed beat does.
     * Nothing is ever pulled earlier than tick 0, so the downbeat always plays when you press play.
     */
    fun apply(tick: Int, velocity: Float, len: Int, amount: Float, seed: Int, drumSlot: Int = -1): Pair<Int, Float> {
        if (this == STRAIGHT || amount <= 0f) return tick to velocity
        val pos = tick.toFloat() / STEP_TICKS
        val slot = pos.roundToInt()
        if (kotlin.math.abs(pos - slot) > 0.35f) return tick to velocity      // off-grid (live-played): leave its own feel
        val i = ((slot % 16) + 16) % 16
        var off = offsets[i] * amount
        val ds = if (drumSlot in 0..7) drumSlot else -1
        val spread = if (ds >= 0 && slotJitter != null) slotJitter[ds] else jitter
        if (ds >= 0 && slotShift != null) off += slotShift[ds] * amount
        if (spread > 0) {
            val h = (seed * 1103515245 + slot * 12345 + (ds + 1) * 7919) ushr 8
            off += (Math.floorMod(h, 2 * spread + 1) - spread) * amount
        }
        val shifted = (tick + off.roundToInt()).coerceAtLeast(0)
        val t = shifted % len
        val v = (velocity * (1f + (accents[i] - 1f) * amount)).coerceIn(0.05f, 1f)
        return t to v
    }

    fun next(d: Int): GrooveTemplate = entries[Math.floorMod(ordinal + d, entries.size)]

    companion object {
        /** By saved name; projects from before the rename used SP1200 / DILLA. */
        fun byName(n: String): GrooveTemplate = when (n) {
            "SP1200" -> GRIT; "DILLA" -> DRUNK
            else -> entries.firstOrNull { it.name == n } ?: STRAIGHT
        }
    }
}

/** A track's feel. */
data class TrackFeel(val template: GrooveTemplate = GrooveTemplate.STRAIGHT, val amount: Float = 1f)
