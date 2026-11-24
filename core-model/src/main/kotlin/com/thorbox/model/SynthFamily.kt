// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.math.abs

/**
 * Which sound family a patch belongs to, when it isn't (or no longer is) a named preset: a patch
 * from the dice, an edited preset, an old project. Reads the patch the way you'd hear it: a sine
 * an octave down with a pitch drop is an 808; noise, big pitch dives or fast wild modulation is
 * WEIRD; anything an octave down is BASS; a slow attack is a PAD; no sustain is a PLUCK; mono or
 * glide is a LEAD; everything else plays like KEYS.
 */
fun guessCategory(p: SynthPatch): SynthCategory {
    val v = p.voice
    val a = p.ampEnv
    val lfo = p.lfo
    return when {
        p.osc1.shape < 0.2f && p.osc1.octave <= -1 && v.pitchEnvSemis >= 3f -> SynthCategory.SUB808
        v.noise >= 0.25f || abs(v.pitchEnvSemis) >= 12f ||
            (lfo.shape == LfoShape.SAMPLE_HOLD && lfo.depth >= 0.6f) ||
            ((lfo.target == LfoTarget.PITCH || lfo.target == LfoTarget.SHAPE) && lfo.depth >= 0.3f && lfo.rateHz >= 4f) -> SynthCategory.WEIRD
        minOf(p.osc1.octave, p.osc2.octave) <= -1 -> SynthCategory.BASS
        a.attackMs >= 120f -> SynthCategory.PAD
        a.sustain <= 0.05f && a.decayMs <= 1000f && !v.mono -> SynthCategory.PLUCK
        v.mono || v.glideMs > 0f -> SynthCategory.LEAD
        else -> SynthCategory.KEYS
    }
}
