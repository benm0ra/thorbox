// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Sound dice. DICE builds a fresh patch inside musical bounds for a category, so
 * every roll is playable (basses stay low and mono, pads stay slow, 808s keep their boom).
 * MUTATE nudges the current patch by [amount] (0..1), keeping its character.
 * Pure and deterministic for a given seed (testable, and "undo → same roll" is possible).
 */
object PatchDice {
    private val adjectives = listOf("FUZZY", "SHINY", "WOBBLY", "SLEEPY", "ANGRY", "CHUNKY", "LUNAR", "DUSTY", "CRISPY", "GOLDEN",
        "WONKY", "VELVET", "RUSTY", "BOUNCY", "COSMIC", "TINY", "HAZY", "ELECTRIC", "SOFT", "WILD")
    private val nouns = listOf("OTTER", "COMET", "PEBBLE", "ROBOT", "MANGO", "MOTH", "ENGINE", "CLOUD", "GHOST", "BEAN",
        "LASER", "PIXEL", "TIGER", "BUBBLE", "RIVER", "MOON", "TOAST", "FERN", "SPARK", "WHALE")

    fun name(rnd: Random) = "${adjectives.random(rnd)} ${nouns.random(rnd)}"

    fun dice(seed: Int, category: SynthCategory? = null): SynthPatch {
        val r = Random(seed)
        val cat = category ?: SynthCategory.entries.random(r)
        fun f(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
        fun log(lo: Float, hi: Float) = exp(ln(lo) + r.nextFloat() * (ln(hi) - ln(lo)))
        fun chance(p: Float) = r.nextFloat() < p
        fun shape(vararg choices: Float) = (choices[r.nextInt(choices.size)] + f(-0.08f, 0.08f)).coerceIn(0f, 1f)
        val lfoNone = Lfo(depth = 0f)
        fun lfo(maxDepth: Float) = if (chance(0.55f)) Lfo(LfoShape.entries.random(r), log(0.1f, 8f), LfoTarget.entries.random(r), f(0.05f, maxDepth)) else lfoNone

        val p = when (cat) {
            SynthCategory.SUB808 -> SynthPatch(
                osc1 = Oscillator(0f, -1), osc2 = Oscillator(shape(0f, 0.333f), -1 + (if (chance(0.3f)) 1 else 0)),
                oscMix = f(0f, 0.2f), cutoffHz = log(1200f, 4000f), resonance = 0f, filterEnvAmount = 0f, keyTracking = 0f,
                ampEnv = Adsr(1f, log(500f, 4000f), if (chance(0.5f)) 0f else f(0.4f, 0.9f), log(150f, 500f)),
                lfo = lfoNone, gainDb = -5f,
                voice = VoiceMod(pitchEnvSemis = f(4f, 24f), pitchEnvDecayMs = log(20f, 120f), glideMs = if (chance(0.6f)) log(40f, 180f) else 0f,
                    mono = true, drive = f(0f, 0.9f)))
            SynthCategory.BASS -> SynthPatch(
                osc1 = Oscillator(shape(0.666f, 1f, 0.333f), -1), osc2 = Oscillator(shape(0.666f, 1f, 0f), -1 + r.nextInt(2), f(-20f, 20f)),
                oscMix = f(0f, 0.6f), cutoffHz = log(180f, 1500f), resonance = f(0f, 0.8f), filterEnvAmount = f(0.1f, 0.7f), keyTracking = f(0.2f, 0.6f),
                ampEnv = Adsr(f(1f, 5f), log(150f, 600f), f(0.2f, 1f), log(50f, 250f)), filterEnv = Adsr(1f, log(80f, 500f), f(0f, 0.3f), log(60f, 300f)),
                lfo = lfo(0.5f), gainDb = -10f,
                voice = VoiceMod(glideMs = if (chance(0.5f)) log(20f, 100f) else 0f, mono = chance(0.85f), drive = f(0f, 0.7f)))
            SynthCategory.LEAD -> SynthPatch(
                osc1 = Oscillator(shape(0.666f, 1f)), osc2 = Oscillator(shape(0.666f, 1f, 0.333f), r.nextInt(2), f(-25f, 25f)),
                oscMix = f(0.1f, 0.6f), cutoffHz = log(1500f, 9000f), resonance = f(0f, 0.6f), filterEnvAmount = f(0f, 0.5f),
                ampEnv = Adsr(f(1f, 20f), log(150f, 800f), f(0.6f, 1f), log(100f, 500f)),
                lfo = if (chance(0.6f)) Lfo(LfoShape.SINE, f(4.5f, 6.5f), LfoTarget.PITCH, f(0.05f, 0.18f)) else lfo(0.4f), gainDb = -12f,
                voice = VoiceMod(glideMs = if (chance(0.5f)) log(20f, 120f) else 0f, mono = chance(0.6f), drive = f(0f, 0.6f)))
            SynthCategory.KEYS -> SynthPatch(
                osc1 = Oscillator(shape(0f, 0.333f, 0.666f)), osc2 = Oscillator(shape(0f, 0.333f), r.nextInt(3) - (if (chance(0.2f)) 1 else 0), f(-10f, 10f)),
                oscMix = f(0.1f, 0.5f), cutoffHz = log(1200f, 6000f), resonance = f(0f, 0.3f), filterEnvAmount = f(0f, 0.4f), keyTracking = f(0.4f, 0.9f),
                ampEnv = Adsr(f(1f, 6f), log(600f, 3000f), f(0f, 0.5f), log(200f, 700f)), filterEnv = Adsr(1f, log(200f, 900f), f(0f, 0.3f), 300f),
                lfo = if (chance(0.5f)) Lfo(LfoShape.SINE, f(3f, 6f), LfoTarget.AMP, f(0.05f, 0.25f)) else lfoNone, gainDb = -10f,
                voice = VoiceMod(drive = f(0f, 0.3f), noise = if (chance(0.3f)) f(0f, 0.08f) else 0f))
            SynthCategory.PLUCK -> SynthPatch(
                osc1 = Oscillator(shape(0f, 0.666f, 1f)), osc2 = Oscillator(shape(0f, 0.333f, 1f), r.nextInt(3), f(-8f, 8f)),
                oscMix = f(0f, 0.5f), cutoffHz = log(400f, 5000f), resonance = f(0f, 0.5f), filterEnvAmount = f(0.2f, 0.8f),
                ampEnv = Adsr(1f, log(120f, 900f), 0f, log(100f, 600f)), filterEnv = Adsr(1f, log(40f, 250f), 0f, 100f),
                lfo = lfoNone, gainDb = -10f)
            SynthCategory.PAD -> SynthPatch(
                osc1 = Oscillator(shape(0.666f, 0.333f)), osc2 = Oscillator(shape(0.666f, 0.333f, 0f), r.nextInt(2), f(-25f, 25f)),
                oscMix = f(0.3f, 0.7f), cutoffHz = log(600f, 5000f), resonance = f(0f, 0.4f), filterEnvAmount = f(0f, 0.3f),
                ampEnv = Adsr(log(300f, 2500f), log(800f, 3000f), f(0.6f, 1f), log(1000f, 4000f)), filterEnv = Adsr(log(300f, 2000f), 1500f, 0.6f, 2000f),
                lfo = Lfo(LfoShape.entries.random(r).let { if (it == LfoShape.SQUARE) LfoShape.TRIANGLE else it }, log(0.1f, 1f),
                    listOf(LfoTarget.CUTOFF, LfoTarget.SHAPE, LfoTarget.PITCH).random(r), f(0.1f, 0.4f)), gainDb = -13f,
                voice = VoiceMod(glideMs = if (chance(0.3f)) log(50f, 400f) else 0f, noise = if (chance(0.3f)) f(0f, 0.15f) else 0f))
            SynthCategory.WEIRD -> SynthPatch(
                osc1 = Oscillator(f(0f, 1f), r.nextInt(3) - 1), osc2 = Oscillator(f(0f, 1f), r.nextInt(4) - 2, f(-60f, 60f)),
                oscMix = f(0f, 1f), cutoffHz = log(300f, 12000f), resonance = f(0f, 0.9f), filterEnvAmount = f(-0.8f, 0.8f), keyTracking = f(0f, 1f),
                ampEnv = Adsr(log(1f, 800f), log(80f, 2000f), f(0f, 1f), log(80f, 2500f)), filterEnv = Adsr(log(1f, 800f), log(80f, 2000f), f(0f, 1f), 500f),
                lfo = Lfo(LfoShape.entries.random(r), log(0.2f, 15f), LfoTarget.entries.random(r), f(0.2f, 0.9f)), gainDb = -12f,
                voice = VoiceMod(pitchEnvSemis = if (chance(0.5f)) f(-36f, 48f) else 0f, pitchEnvDecayMs = log(15f, 400f),
                    glideMs = if (chance(0.4f)) log(30f, 600f) else 0f, mono = chance(0.4f), drive = f(0f, 0.8f), noise = if (chance(0.4f)) f(0f, 0.6f) else 0f))
        }
        return p.copy(id = PatchId("dice:$seed"), name = name(r))
    }

    /** Nudge every continuous parameter by up to ±[amount] of its musical range. Mode switches stay put. */
    fun mutate(p: SynthPatch, amount: Float, seed: Int): SynthPatch {
        val r = Random(seed)
        val a = amount.coerceIn(0f, 1f)
        fun lin(v: Float, lo: Float, hi: Float) = (v + (r.nextFloat() * 2f - 1f) * a * (hi - lo) * 0.5f).coerceIn(lo, hi)
        fun lg(v: Float, lo: Float, hi: Float): Float {
            val span = ln(hi) - ln(lo)
            return exp((ln(v.coerceIn(lo, hi)) + (r.nextFloat() * 2f - 1f) * a * span * 0.35f).coerceIn(ln(lo), ln(hi)))
        }
        fun env(e: Adsr) = Adsr(lg(e.attackMs, 0.5f, 10000f), lg(e.decayMs, 1f, 10000f), lin(e.sustain, 0f, 1f), lg(e.releaseMs, 1f, 10000f))
        return p.copy(
            id = PatchId("mut:$seed"),
            osc1 = p.osc1.copy(shape = lin(p.osc1.shape, 0f, 1f)),
            osc2 = p.osc2.copy(shape = lin(p.osc2.shape, 0f, 1f), detuneCents = lin(p.osc2.detuneCents, -100f, 100f)),
            oscMix = lin(p.oscMix, 0f, 1f),
            cutoffHz = lg(p.cutoffHz, 20f, 20000f), resonance = lin(p.resonance, 0f, 0.95f),
            filterEnvAmount = lin(p.filterEnvAmount, -1f, 1f), keyTracking = lin(p.keyTracking, 0f, 1f),
            ampEnv = env(p.ampEnv), filterEnv = env(p.filterEnv),
            lfo = p.lfo.copy(rateHz = lg(p.lfo.rateHz, 0.05f, 20f), depth = lin(p.lfo.depth, 0f, 1f)),
            voice = p.voice.copy(
                pitchEnvSemis = if (p.voice.pitchEnvSemis == 0f) 0f else lin(p.voice.pitchEnvSemis, -48f, 48f),
                pitchEnvDecayMs = lg(p.voice.pitchEnvDecayMs, 1f, 5000f),
                glideMs = if (p.voice.glideMs == 0f) 0f else lg(p.voice.glideMs, 1f, 2000f),
                drive = lin(p.voice.drive, 0f, 1f), noise = if (p.voice.noise == 0f) 0f else lin(p.voice.noise, 0f, 1f)),
        )
    }

    /** Dice a drum voice within its model's sweet spot (keeps the slot's model and name). */
    fun diceDrum(v: DrumVoice, seed: Int): DrumVoice {
        val r = Random(seed)
        fun f(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
        val decay = when (v.model) {
            DrumModel.KICK -> f(200f, 1200f); DrumModel.SNARE, DrumModel.CLAP -> f(120f, 400f)
            DrumModel.HAT -> if (v.decayMs < 150f) f(20f, 90f) else f(180f, 700f)
            DrumModel.TOM -> f(150f, 700f); DrumModel.RIM -> f(30f, 100f); DrumModel.COWBELL -> f(150f, 600f)
            DrumModel.CYMBAL -> f(800f, 3000f); DrumModel.ZAP -> f(80f, 500f); DrumModel.SHAKER -> f(60f, 200f)
        }
        return v.copy(tune = f(-7f, 7f).let { if (v.model == DrumModel.KICK) it * 0.5f else it }, decayMs = decay,
            tone = f(0f, 1f), snap = f(0.1f, 1f), drive = if (r.nextFloat() < 0.4f) f(0f, 0.7f) else 0f)
    }
}
