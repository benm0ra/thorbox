// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.synth

import com.thorbox.model.LfoShape
import com.thorbox.model.LfoTarget
import com.thorbox.model.SynthPatch
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** The four encoder colours, TE-style: every page maps its 4 params to the same 4 colours,
 *  and the page's animation draws each param in its encoder's colour. */
enum class EncoderColor { BLUE, GREEN, WHITE, ORANGE }

/** Engine param ids. These MUST match `synth::Param` in Synth.h. */
object SynthId {
    const val OSC1_SHAPE = 0; const val OSC2_SHAPE = 1; const val OSC2_DETUNE = 2; const val OSC_MIX = 3
    const val OSC1_OCT = 4; const val OSC2_OCT = 5
    const val CUTOFF = 6; const val RESO = 7; const val FENV_AMT = 8; const val KEYTRACK = 9
    const val AMP_A = 10; const val AMP_D = 11; const val AMP_S = 12; const val AMP_R = 13
    const val FENV_A = 14; const val FENV_D = 15; const val FENV_S = 16; const val FENV_R = 17
    const val LFO_RATE = 18; const val LFO_DEPTH = 19; const val LFO_SHAPE = 20; const val LFO_TARGET = 21
    const val GAIN = 22
    const val PENV_AMT = 23; const val PENV_DECAY = 24; const val GLIDE = 25; const val MONO = 26
    const val DRIVE = 27; const val NOISE = 28
    const val COUNT = 29
}

/** One knob: normalized 0..1 position ↔ patch field, with a display string. */
class Knob(
    val label: String,
    val color: EncoderColor,
    val get: (SynthPatch) -> Float,
    val set: (SynthPatch, Float) -> SynthPatch,
    val format: (SynthPatch) -> String,
)

enum class SynthPage(val label: String) { OSC("OSC"), FILTER("FILTER"), AMP("AMP"), FENV("F·ENV"), LFO("LFO"), PITCH("PITCH"), VOICE("VOICE") }

object SynthParams {
    // ---- curves (normalized ↔ physical) ----
    fun hzFromN(n: Float) = 20f * 1000f.pow(n.coerceIn(0f, 1f))                  // 20 Hz … 20 kHz
    fun nFromHz(hz: Float) = (ln(hz / 20f) / ln(1000f)).coerceIn(0f, 1f)
    fun msFromN(n: Float) = 1f + 9999f * n.coerceIn(0f, 1f).pow(3)                // 1 ms … 10 s, fine at the short end
    fun nFromMs(ms: Float) = cbrt(((ms - 1f) / 9999f).coerceIn(0f, 1f))
    fun lfoHzFromN(n: Float) = 0.05f * 400f.pow(n.coerceIn(0f, 1f))                // 0.05 … 20 Hz
    fun nFromLfoHz(hz: Float) = (ln(hz / 0.05f) / ln(400f)).coerceIn(0f, 1f)

    fun shapeName(s: Float): String {
        val names = arrayOf("SIN", "TRI", "SAW", "SQR")
        val x = s.coerceIn(0f, 1f) * 3f
        val i = x.toInt().coerceAtMost(2)
        val f = x - i
        return when { f < 0.08f -> names[i]; f > 0.92f -> names[i + 1]; else -> "${names[i]}›${names[i + 1]}" }
    }
    fun fmtHz(hz: Float) = if (hz >= 1000f) String.format(Locale.US, "%.1fk", hz / 1000f) else "${hz.roundToInt()}"
    fun fmtMs(ms: Float) = if (ms >= 1000f) String.format(Locale.US, "%.2fs", ms / 1000f) else "${ms.roundToInt()}ms"
    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun signedPct(v: Float) = String.format(Locale.US, "%+d%%", (v * 100).roundToInt())

    val pages: Map<SynthPage, List<Knob>> = mapOf(
        SynthPage.OSC to listOf(
            Knob("SHAPE 1", EncoderColor.BLUE, { it.osc1.shape }, { p, n -> p.copy(osc1 = p.osc1.copy(shape = n)) }, { shapeName(it.osc1.shape) }),
            Knob("SHAPE 2", EncoderColor.GREEN, { it.osc2.shape }, { p, n -> p.copy(osc2 = p.osc2.copy(shape = n)) }, { shapeName(it.osc2.shape) }),
            Knob("DETUNE", EncoderColor.WHITE, { (it.osc2.detuneCents + 100f) / 200f },
                { p, n -> p.copy(osc2 = p.osc2.copy(detuneCents = (n * 200f - 100f).roundToInt().toFloat())) },
                { String.format(Locale.US, "%+dct", it.osc2.detuneCents.roundToInt()) }),
            Knob("MIX", EncoderColor.ORANGE, { it.oscMix }, { p, n -> p.copy(oscMix = n) },
                { "${(100 - it.oscMix * 100).roundToInt()}·${(it.oscMix * 100).roundToInt()}" }),
        ),
        SynthPage.FILTER to listOf(
            Knob("CUTOFF", EncoderColor.BLUE, { nFromHz(it.cutoffHz) }, { p, n -> p.copy(cutoffHz = hzFromN(n)) }, { fmtHz(it.cutoffHz) }),
            Knob("RESO", EncoderColor.GREEN, { it.resonance }, { p, n -> p.copy(resonance = n) }, { pct(it.resonance) }),
            Knob("ENV AMT", EncoderColor.WHITE, { (it.filterEnvAmount + 1f) / 2f }, { p, n -> p.copy(filterEnvAmount = n * 2f - 1f) }, { signedPct(it.filterEnvAmount) }),
            Knob("KEY TRK", EncoderColor.ORANGE, { it.keyTracking }, { p, n -> p.copy(keyTracking = n) }, { pct(it.keyTracking) }),
        ),
        SynthPage.AMP to adsrKnobs(amp = true),
        SynthPage.FENV to adsrKnobs(amp = false),
        SynthPage.LFO to listOf(
            Knob("RATE", EncoderColor.BLUE, { nFromLfoHz(it.lfo.rateHz) }, { p, n -> p.copy(lfo = p.lfo.copy(rateHz = lfoHzFromN(n))) },
                { String.format(Locale.US, "%.2fHz", it.lfo.rateHz) }),
            Knob("DEPTH", EncoderColor.GREEN, { it.lfo.depth }, { p, n -> p.copy(lfo = p.lfo.copy(depth = n)) }, { pct(it.lfo.depth) }),
            Knob("WAVE", EncoderColor.WHITE, { it.lfo.shape.ordinal / 4f },
                { p, n -> p.copy(lfo = p.lfo.copy(shape = LfoShape.entries[(n * 4f).roundToInt().coerceIn(0, 4)])) },
                { arrayOf("SIN", "TRI", "SAW", "SQR", "S&H")[it.lfo.shape.ordinal] }),
            Knob("TARGET", EncoderColor.ORANGE, { it.lfo.target.ordinal / 3f },
                { p, n -> p.copy(lfo = p.lfo.copy(target = LfoTarget.entries[(n * 3f).roundToInt().coerceIn(0, 3)])) },
                { it.lfo.target.name }),
        ),
        SynthPage.PITCH to listOf(
            Knob("P.ENV", EncoderColor.BLUE, { (it.voice.pitchEnvSemis + 48f) / 96f },
                { p, n -> p.copy(voice = p.voice.copy(pitchEnvSemis = (n * 96f - 48f).roundToInt().toFloat())) },
                { String.format(Locale.US, "%+dst", it.voice.pitchEnvSemis.roundToInt()) }),
            Knob("P.DECAY", EncoderColor.GREEN, { nFromMs(it.voice.pitchEnvDecayMs.coerceAtMost(5000f)) },
                { p, n -> p.copy(voice = p.voice.copy(pitchEnvDecayMs = msFromN(n).coerceAtMost(5000f))) }, { fmtMs(it.voice.pitchEnvDecayMs) }),
            Knob("OCT 1", EncoderColor.WHITE, { (it.osc1.octave + 3) / 6f },
                { p, n -> p.copy(osc1 = p.osc1.copy(octave = (n * 6f).roundToInt() - 3)) }, { String.format(Locale.US, "%+d", it.osc1.octave) }),
            Knob("OCT 2", EncoderColor.ORANGE, { (it.osc2.octave + 3) / 6f },
                { p, n -> p.copy(osc2 = p.osc2.copy(octave = (n * 6f).roundToInt() - 3)) }, { String.format(Locale.US, "%+d", it.osc2.octave) }),
        ),
        SynthPage.VOICE to listOf(
            Knob("GLIDE", EncoderColor.BLUE, { cbrt(it.voice.glideMs / 2000f) },
                { p, n -> p.copy(voice = p.voice.copy(glideMs = if (n < 0.02f) 0f else 2000f * n * n * n)) },
                { if (it.voice.glideMs <= 0f) "OFF" else fmtMs(it.voice.glideMs) }),
            Knob("MONO", EncoderColor.GREEN, { if (it.voice.mono) 1f else 0f },
                { p, n -> p.copy(voice = p.voice.copy(mono = n >= 0.5f)) }, { if (it.voice.mono) "MONO" else "POLY" }),
            Knob("DRIVE", EncoderColor.WHITE, { it.voice.drive }, { p, n -> p.copy(voice = p.voice.copy(drive = n)) }, { pct(it.voice.drive) }),
            Knob("NOISE", EncoderColor.ORANGE, { it.voice.noise }, { p, n -> p.copy(voice = p.voice.copy(noise = n)) }, { pct(it.voice.noise) }),
        ),
    )

    private fun adsrKnobs(amp: Boolean): List<Knob> {
        fun env(p: SynthPatch) = if (amp) p.ampEnv else p.filterEnv
        fun with(p: SynthPatch, e: com.thorbox.model.Adsr) = if (amp) p.copy(ampEnv = e) else p.copy(filterEnv = e)
        return listOf(
            Knob("ATTACK", EncoderColor.BLUE, { nFromMs(env(it).attackMs) }, { p, n -> with(p, env(p).copy(attackMs = msFromN(n))) }, { fmtMs(env(it).attackMs) }),
            Knob("DECAY", EncoderColor.GREEN, { nFromMs(env(it).decayMs) }, { p, n -> with(p, env(p).copy(decayMs = msFromN(n))) }, { fmtMs(env(it).decayMs) }),
            Knob("SUSTAIN", EncoderColor.WHITE, { env(it).sustain }, { p, n -> with(p, env(p).copy(sustain = n)) }, { pct(env(it).sustain) }),
            Knob("RELEASE", EncoderColor.ORANGE, { nFromMs(env(it).releaseMs) }, { p, n -> with(p, env(p).copy(releaseMs = msFromN(n))) }, { fmtMs(env(it).releaseMs) }),
        )
    }

    /** Patch → engine values, indexed by SynthId. Clamped to the engine's accepted ranges. */
    fun toEngine(p: SynthPatch, out: FloatArray) {
        out[SynthId.OSC1_SHAPE] = p.osc1.shape.coerceIn(0f, 1f)
        out[SynthId.OSC2_SHAPE] = p.osc2.shape.coerceIn(0f, 1f)
        out[SynthId.OSC2_DETUNE] = p.osc2.detuneCents.coerceIn(-100f, 100f)
        out[SynthId.OSC_MIX] = p.oscMix.coerceIn(0f, 1f)
        out[SynthId.OSC1_OCT] = p.osc1.octave.coerceIn(-3, 3).toFloat()
        out[SynthId.OSC2_OCT] = p.osc2.octave.coerceIn(-3, 3).toFloat()
        out[SynthId.CUTOFF] = p.cutoffHz.coerceIn(20f, 20000f)
        out[SynthId.RESO] = p.resonance.coerceIn(0f, 1f)
        out[SynthId.FENV_AMT] = p.filterEnvAmount.coerceIn(-1f, 1f)
        out[SynthId.KEYTRACK] = p.keyTracking.coerceIn(0f, 1f)
        out[SynthId.AMP_A] = p.ampEnv.attackMs.coerceIn(0.5f, 10000f)
        out[SynthId.AMP_D] = p.ampEnv.decayMs.coerceIn(1f, 10000f)
        out[SynthId.AMP_S] = p.ampEnv.sustain.coerceIn(0f, 1f)
        out[SynthId.AMP_R] = p.ampEnv.releaseMs.coerceIn(1f, 10000f)
        out[SynthId.FENV_A] = p.filterEnv.attackMs.coerceIn(0.5f, 10000f)
        out[SynthId.FENV_D] = p.filterEnv.decayMs.coerceIn(1f, 10000f)
        out[SynthId.FENV_S] = p.filterEnv.sustain.coerceIn(0f, 1f)
        out[SynthId.FENV_R] = p.filterEnv.releaseMs.coerceIn(1f, 10000f)
        out[SynthId.LFO_RATE] = p.lfo.rateHz.coerceIn(0.05f, 20f)
        out[SynthId.LFO_DEPTH] = p.lfo.depth.coerceIn(0f, 1f)
        out[SynthId.LFO_SHAPE] = p.lfo.shape.ordinal.toFloat()
        out[SynthId.LFO_TARGET] = p.lfo.target.ordinal.toFloat()
        out[SynthId.GAIN] = p.gainDb.coerceIn(-40f, 6f)
        out[SynthId.PENV_AMT] = p.voice.pitchEnvSemis.coerceIn(-48f, 48f)
        out[SynthId.PENV_DECAY] = p.voice.pitchEnvDecayMs.coerceIn(1f, 5000f)
        out[SynthId.GLIDE] = p.voice.glideMs.coerceIn(0f, 2000f)
        out[SynthId.MONO] = if (p.voice.mono) 1f else 0f
        out[SynthId.DRIVE] = p.voice.drive.coerceIn(0f, 1f)
        out[SynthId.NOISE] = p.voice.noise.coerceIn(0f, 1f)
    }

    /** Same morph as the C++ oscillator (without band-limiting), for drawing. t in [0,1). */
    fun shapeSample(shape: Float, t: Float): Float {
        val x = shape.coerceIn(0f, 1f) * 3f
        val seg = x.toInt().coerceAtMost(2)
        val f = x - seg
        val sine = kotlin.math.sin(2f * Math.PI.toFloat() * t)
        val tri = 1f - 4f * kotlin.math.abs(t - 0.5f)
        val saw = 2f * t - 1f
        val sqr = if (t < 0.5f) 1f else -1f
        return when (seg) { 0 -> sine * (1 - f) + tri * f; 1 -> tri * (1 - f) + saw * f; else -> saw * (1 - f) + sqr * f }
    }
}
