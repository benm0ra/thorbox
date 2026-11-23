// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlinx.serialization.Serializable

/**
 * DUCK: the mix's sidechain compressor (MPC-style one-knob-per-job ducker). One KEY, any number of
 * TARGET tracks. The kid-friendly names on the page map to real compressor controls:
 *
 *   WEIGHT = threshold (dB) · SQUASH = ratio · SNAP = attack (ms) · BOUNCE = release (ms)
 *   PUMP   = mix (how much of the ducking you hear) · LOUD = makeup gain on the targets (dB)
 *
 * [source]: the key track (0…7), [BEAT] for a ghost pulse on every quarter note, -1 = off.
 * [sourceSlot]: when the key is a drum track, one drum voice (0 = the kit's first pad, usually the
 * kick), or -1 for the whole kit. [targets]: bitmask of ducked tracks.
 */
@Serializable
data class DuckSettings(
    val source: Int = -1,
    val sourceSlot: Int = 0,
    val targets: Int = 0,
    val thresholdDb: Float = -28f,
    val ratio: Float = 6f,
    val attackMs: Float = 2f,
    val releaseMs: Float = 160f,
    val mix: Float = 0.8f,
    val makeupDb: Float = 1.5f,
    val preset: String = "BOOM BAP POCKET",
    /** The page's ON / OFF: off keeps every setting, the mix just stops ducking. */
    val enabled: Boolean = true,
) {
    val on get() = enabled && source >= 0 && targets != 0 && mix > 0f
    fun isTarget(t: Int) = targets and (1 shl t) != 0
    fun withTarget(t: Int, on: Boolean) = copy(targets = if (on) targets or (1 shl t) else targets and (1 shl t).inv())
    /** The four sound settings of [p], keeping the routing (source, slot, targets). */
    fun withPreset(p: DuckPreset) = copy(thresholdDb = p.thresholdDb, ratio = p.ratio, attackMs = p.attackMs,
        releaseMs = p.releaseMs, mix = p.mix, makeupDb = p.makeupDb, preset = p.name)
    fun clamped() = copy(
        sourceSlot = sourceSlot.coerceIn(-1, 15), targets = targets and 0xFF,
        thresholdDb = thresholdDb.coerceIn(-60f, 0f), ratio = ratio.coerceIn(1f, 20f), attackMs = attackMs.coerceIn(0.1f, 50f),
        releaseMs = releaseMs.coerceIn(20f, 1000f), mix = mix.coerceIn(0f, 1f), makeupDb = makeupDb.coerceIn(0f, 12f))

    companion object {
        const val BEAT = 99
        const val OFF = -1
    }
}

/** A one-tap starting point (sound only: the routing stays as you set it). */
data class DuckPreset(
    val name: String, val blurb: String,
    val thresholdDb: Float, val ratio: Float, val attackMs: Float, val releaseMs: Float, val mix: Float, val makeupDb: Float,
)

object DuckPresets {
    val all = listOf(
        DuckPreset("BOOM BAP POCKET", "the chop dips under every kick and swings back in time",
            thresholdDb = -28f, ratio = 6f, attackMs = 2f, releaseMs = 160f, mix = 0.8f, makeupDb = 1.5f),
        DuckPreset("HEAVY DILLA PUMP", "deep, slow, drunk: the whole loop breathes with the kick",
            thresholdDb = -36f, ratio = 20f, attackMs = 1f, releaseMs = 340f, mix = 1f, makeupDb = 3f),
        DuckPreset("SUBTLE GLUE", "just a nudge, so the kick and the sample sit together",
            thresholdDb = -20f, ratio = 2.5f, attackMs = 8f, releaseMs = 120f, mix = 0.5f, makeupDb = 1f),
        DuckPreset("LO-FI GHOST", "a soft, slow swell, like the loop's coming from the next room",
            thresholdDb = -30f, ratio = 8f, attackMs = 15f, releaseMs = 520f, mix = 0.65f, makeupDb = 2f),
    )
    fun byName(n: String) = all.firstOrNull { it.name == n }
}
