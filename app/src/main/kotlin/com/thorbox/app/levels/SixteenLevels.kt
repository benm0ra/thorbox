// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.levels

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 16 LEVELS (MPC-style): one sound spread across all 16 pads, graded by one parameter, pad 1 the
 * lowest and pad 16 the highest. VELOCITY levels record as ordinary hits of the source sound; the
 * others are for playing (they record as the source sound at its normal setting).
 */
enum class LevelMode(val label: String, val drumsOnly: Boolean = false) {
    OFF("OFF"), VELOCITY("VEL"), TUNE("TUNE"), DECAY("DECAY", drumsOnly = true), TONE("TONE", drumsOnly = true)
}

object Levels {
    /** Pad k (0..15) velocity: 1/16 … 16/16. */
    fun velocity(k: Int) = ((k + 1) / 16f).coerceIn(0.06f, 1f)
    /** Semitones from the sound's own tune: pad 9 is as-is, −8 … +7. */
    fun tune(k: Int) = k - 8
    /** Decay multiplier: pad 12 is as-is, ×0.15 … ×2. */
    fun decay(k: Int) = 2f.pow((k - 11) / 4f)
    /** Absolute TONE 0 … 1. */
    fun tone(k: Int) = k / 15f

    fun label(mode: LevelMode, k: Int): String = when (mode) {
        LevelMode.VELOCITY -> "${(velocity(k) * 100).roundToInt()}"
        LevelMode.TUNE -> tune(k).let { if (it == 0) "0 st" else String.format(Locale.US, "%+d st", it) }
        LevelMode.DECAY -> String.format(Locale.US, "×%.2f", decay(k))
        LevelMode.TONE -> "${(tone(k) * 100).roundToInt()}%"
        LevelMode.OFF -> ""
    }
}
