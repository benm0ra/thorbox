// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

/**
 * The factory sound bank. Synth presets are voiced for the synth's default keyboard range
 * (C3 = MIDI 48), so basses sit an octave down via the oscillator octave. Shapes: 0 sine,
 * 0.333 triangle, 0.666 saw, 1 square.
 */
object FactoryPresets {
    private const val SIN = 0f
    private const val TRI = 0.333f
    private const val SAW = 0.666f
    private const val SQR = 1f

    private fun patch(
        name: String, s1: Float, s2: Float, o1: Int = 0, o2: Int = 0, detune: Float = 0f, mix: Float = 0f,
        cutoff: Float = 4000f, reso: Float = 0.1f, fenv: Float = 0f, keyTrack: Float = 0.5f,
        amp: Adsr = Adsr(2f, 300f, 0.8f, 250f), fe: Adsr = Adsr(2f, 300f, 0.2f, 300f),
        lfo: Lfo = Lfo(depth = 0f), gain: Float = -9f, voice: VoiceMod = VoiceMod(),
    ) = SynthPatch(
        id = PatchId("factory:" + name.lowercase().replace(' ', '-')), name = name,
        osc1 = Oscillator(shape = s1, octave = o1), osc2 = Oscillator(shape = s2, octave = o2, detuneCents = detune),
        oscMix = mix, cutoffHz = cutoff, resonance = reso, filterEnvAmount = fenv, keyTracking = keyTrack,
        ampEnv = amp, filterEnv = fe, lfo = lfo, gainDb = gain, voice = voice,
    )
    private fun preset(cat: SynthCategory, p: SynthPatch) = SynthPreset(p.name, cat, p)

    val synth: List<SynthPreset> = listOf(
        // ---------------- 808 ----------------
        preset(SynthCategory.SUB808, patch("808 CLASSIC", SIN, TRI, o1 = -1, o2 = -1, mix = 0.08f, cutoff = 2400f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 1800f, 0f, 300f), gain = -5f, voice = VoiceMod(pitchEnvSemis = 12f, pitchEnvDecayMs = 50f, mono = true, drive = 0.15f))),
        preset(SynthCategory.SUB808, patch("808 SLIDE", SIN, TRI, o1 = -1, o2 = -1, mix = 0.1f, cutoff = 2200f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 2600f, 0.55f, 350f), gain = -5f, voice = VoiceMod(pitchEnvSemis = 7f, pitchEnvDecayMs = 45f, glideMs = 90f, mono = true, drive = 0.2f))),
        preset(SynthCategory.SUB808, patch("808 DRILL", SIN, SIN, o1 = -1, o2 = 0, mix = 0.12f, cutoff = 3000f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 3000f, 0.8f, 260f), gain = -5f, voice = VoiceMod(pitchEnvSemis = 9f, pitchEnvDecayMs = 40f, glideMs = 140f, mono = true, drive = 0.5f))),
        preset(SynthCategory.SUB808, patch("808 DIRTY", SIN, TRI, o1 = -1, o2 = -1, mix = 0.15f, cutoff = 1800f, reso = 0.1f, keyTrack = 0f,
            amp = Adsr(1f, 1200f, 0f, 220f), gain = -6f, voice = VoiceMod(pitchEnvSemis = 14f, pitchEnvDecayMs = 35f, mono = true, drive = 0.85f))),
        preset(SynthCategory.SUB808, patch("808 BOOM", SIN, SIN, o1 = -1, o2 = -1, cutoff = 1500f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 4000f, 0f, 500f), gain = -5f, voice = VoiceMod(pitchEnvSemis = 5f, pitchEnvDecayMs = 120f, mono = true, drive = 0.05f))),
        preset(SynthCategory.SUB808, patch("808 PUNCH", SIN, TRI, o1 = -1, o2 = -1, mix = 0.1f, cutoff = 2800f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 700f, 0f, 150f), gain = -5f, voice = VoiceMod(pitchEnvSemis = 24f, pitchEnvDecayMs = 25f, mono = true, drive = 0.35f))),

        // ---------------- BASS ----------------
        preset(SynthCategory.BASS, patch("ACID LINE", SAW, SQR, o1 = -1, o2 = -1, mix = 0f, cutoff = 280f, reso = 0.82f, fenv = 0.65f, keyTrack = 0.3f,
            amp = Adsr(1f, 250f, 0.7f, 60f), fe = Adsr(1f, 180f, 0f, 120f), voice = VoiceMod(glideMs = 60f, mono = true, drive = 0.45f))),
        preset(SynthCategory.BASS, patch("REESE", SAW, SAW, o1 = -1, o2 = -1, detune = 18f, mix = 0.5f, cutoff = 900f, reso = 0.2f, fenv = 0.1f,
            amp = Adsr(5f, 300f, 1f, 180f), lfo = Lfo(LfoShape.TRIANGLE, 0.3f, LfoTarget.CUTOFF, 0.25f), gain = -10f,
            voice = VoiceMod(glideMs = 40f, mono = true, drive = 0.3f))),
        preset(SynthCategory.BASS, patch("SUB SINE", SIN, SIN, o1 = -1, o2 = -1, cutoff = 600f, reso = 0f, keyTrack = 0f,
            amp = Adsr(3f, 200f, 1f, 120f), gain = -6f, voice = VoiceMod(mono = true, drive = 0.05f))),
        preset(SynthCategory.BASS, patch("WOBBLE", SAW, SQR, o1 = -1, o2 = -1, detune = 10f, mix = 0.4f, cutoff = 400f, reso = 0.55f,
            amp = Adsr(2f, 300f, 1f, 150f), lfo = Lfo(LfoShape.SINE, 4f, LfoTarget.CUTOFF, 0.75f), gain = -10f,
            voice = VoiceMod(mono = true, drive = 0.5f))),
        preset(SynthCategory.BASS, patch("RUBBER", 0.85f, SIN, o1 = -1, o2 = -1, mix = 0.25f, cutoff = 500f, reso = 0.45f, fenv = 0.55f,
            amp = Adsr(1f, 350f, 0.2f, 80f), fe = Adsr(1f, 120f, 0f, 100f), voice = VoiceMod(glideMs = 25f, mono = true, drive = 0.2f))),
        preset(SynthCategory.BASS, patch("GROWL", SAW, SQR, o1 = -1, o2 = 0, detune = -12f, mix = 0.45f, cutoff = 700f, reso = 0.6f, fenv = 0.3f,
            amp = Adsr(2f, 300f, 1f, 120f), lfo = Lfo(LfoShape.SAMPLE_HOLD, 7f, LfoTarget.CUTOFF, 0.45f), gain = -11f,
            voice = VoiceMod(mono = true, drive = 0.7f))),

        // ---------------- LEAD ----------------
        preset(SynthCategory.LEAD, patch("SUPERSAW", SAW, SAW, detune = 22f, mix = 0.5f, cutoff = 6000f, reso = 0.15f, fenv = 0.2f,
            amp = Adsr(8f, 400f, 0.85f, 350f), lfo = Lfo(LfoShape.TRIANGLE, 0.25f, LfoTarget.PITCH, 0.05f), gain = -12f)),
        preset(SynthCategory.LEAD, patch("SQUARE LEAD", SQR, SQR, o2 = 1, detune = 5f, mix = 0.3f, cutoff = 3500f, reso = 0.3f,
            amp = Adsr(3f, 200f, 0.9f, 200f), lfo = Lfo(LfoShape.SINE, 5.5f, LfoTarget.PITCH, 0.12f), gain = -12f,
            voice = VoiceMod(glideMs = 50f, mono = true))),
        preset(SynthCategory.LEAD, patch("SCREAMER", SAW, SAW, detune = 7f, mix = 0.3f, cutoff = 2500f, reso = 0.7f, fenv = 0.5f,
            amp = Adsr(2f, 300f, 0.9f, 200f), fe = Adsr(2f, 400f, 0.3f, 300f), gain = -12f, voice = VoiceMod(glideMs = 30f, mono = true, drive = 0.7f))),
        preset(SynthCategory.LEAD, patch("8-BIT", SQR, SQR, o2 = 1, mix = 0.2f, cutoff = 12000f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 80f, 0.8f, 40f), gain = -14f, voice = VoiceMod(mono = true))),
        preset(SynthCategory.LEAD, patch("WHISTLE", SIN, TRI, o2 = 1, mix = 0.2f, cutoff = 5000f, reso = 0f,
            amp = Adsr(40f, 200f, 1f, 250f), lfo = Lfo(LfoShape.SINE, 5f, LfoTarget.PITCH, 0.15f), gain = -9f,
            voice = VoiceMod(glideMs = 80f, mono = true))),

        // ---------------- KEYS ----------------
        preset(SynthCategory.KEYS, patch("E-PIANO", SIN, TRI, o2 = 1, detune = 4f, mix = 0.25f, cutoff = 3500f, reso = 0.05f, fenv = 0.25f, keyTrack = 0.6f,
            amp = Adsr(2f, 1800f, 0.25f, 400f), fe = Adsr(1f, 600f, 0.1f, 400f), lfo = Lfo(LfoShape.SINE, 4.5f, LfoTarget.AMP, 0.15f))),
        preset(SynthCategory.KEYS, patch("UPRIGHT", TRI, SAW, o2 = 1, mix = 0.15f, cutoff = 2600f, reso = 0.05f, fenv = 0.35f, keyTrack = 0.8f,
            amp = Adsr(1f, 2600f, 0f, 350f), fe = Adsr(1f, 500f, 0f, 300f), voice = VoiceMod(drive = 0.1f))),
        preset(SynthCategory.KEYS, patch("LOFI KEYS", TRI, SIN, detune = 9f, mix = 0.4f, cutoff = 1800f, reso = 0.1f, fenv = 0.1f,
            amp = Adsr(3f, 1400f, 0.3f, 500f), lfo = Lfo(LfoShape.TRIANGLE, 0.7f, LfoTarget.PITCH, 0.08f),
            voice = VoiceMod(drive = 0.2f, noise = 0.04f))),
        preset(SynthCategory.KEYS, patch("ORGAN", SIN, SIN, o2 = 1, mix = 0.45f, cutoff = 6000f, reso = 0f,
            amp = Adsr(5f, 10f, 1f, 60f), lfo = Lfo(LfoShape.SINE, 6f, LfoTarget.AMP, 0.2f), gain = -11f)),
        preset(SynthCategory.KEYS, patch("RAVE STAB", SAW, SAW, detune = 12f, mix = 0.5f, cutoff = 1200f, reso = 0.3f, fenv = 0.6f,
            amp = Adsr(1f, 350f, 0f, 200f), fe = Adsr(1f, 200f, 0f, 150f), gain = -12f)),

        // ---------------- PLUCK ----------------
        preset(SynthCategory.PLUCK, patch("TRAP BELL", SIN, TRI, o2 = 2, mix = 0.35f, cutoff = 8000f, reso = 0f,
            amp = Adsr(1f, 900f, 0f, 600f))),
        preset(SynthCategory.PLUCK, patch("KALIMBA", SIN, SQR, o2 = 2, mix = 0.12f, cutoff = 3000f, reso = 0.1f, fenv = 0.3f,
            amp = Adsr(1f, 600f, 0f, 400f), fe = Adsr(1f, 60f, 0f, 60f), voice = VoiceMod(pitchEnvSemis = 0.6f, pitchEnvDecayMs = 20f))),
        preset(SynthCategory.PLUCK, patch("HOUSE PLUCK", SAW, SQR, detune = 6f, mix = 0.3f, cutoff = 600f, reso = 0.35f, fenv = 0.7f,
            amp = Adsr(1f, 280f, 0f, 200f), fe = Adsr(1f, 140f, 0f, 100f), gain = -10f)),

        // ---------------- PAD ----------------
        preset(SynthCategory.PAD, patch("WARM PAD", SAW, SAW, detune = 14f, mix = 0.5f, cutoff = 1100f, reso = 0.15f, fenv = 0.15f,
            amp = Adsr(900f, 1500f, 0.85f, 2000f), fe = Adsr(900f, 2000f, 0.6f, 2000f),
            lfo = Lfo(LfoShape.TRIANGLE, 0.2f, LfoTarget.CUTOFF, 0.2f), gain = -13f)),
        preset(SynthCategory.PAD, patch("GLASS PAD", TRI, SIN, o2 = 1, mix = 0.4f, cutoff = 5000f, reso = 0.1f,
            amp = Adsr(1200f, 1000f, 1f, 2500f), lfo = Lfo(LfoShape.SINE, 0.3f, LfoTarget.SHAPE, 0.4f), gain = -12f)),

        // ---------------- WEIRD ----------------
        preset(SynthCategory.WEIRD, patch("LASER", SIN, SIN, cutoff = 12000f, reso = 0f, keyTrack = 0f,
            amp = Adsr(1f, 300f, 0f, 100f), voice = VoiceMod(pitchEnvSemis = 48f, pitchEnvDecayMs = 90f))),
        preset(SynthCategory.WEIRD, patch("ROBOT", SQR, SQR, o2 = -1, mix = 0.4f, cutoff = 900f, reso = 0.7f,
            amp = Adsr(2f, 200f, 1f, 100f), lfo = Lfo(LfoShape.SAMPLE_HOLD, 9f, LfoTarget.CUTOFF, 0.8f), gain = -11f,
            voice = VoiceMod(mono = true))),
        preset(SynthCategory.WEIRD, patch("TAPE GHOST", TRI, TRI, detune = 20f, mix = 0.5f, cutoff = 1400f, reso = 0.2f,
            amp = Adsr(400f, 800f, 0.8f, 1500f), lfo = Lfo(LfoShape.TRIANGLE, 0.5f, LfoTarget.PITCH, 0.35f),
            voice = VoiceMod(drive = 0.3f, noise = 0.3f))),
        preset(SynthCategory.WEIRD, patch("BROKEN RADIO", SQR, SAW, detune = 30f, mix = 0.3f, cutoff = 2200f, reso = 0.6f,
            amp = Adsr(2f, 300f, 1f, 200f), lfo = Lfo(LfoShape.SQUARE, 7f, LfoTarget.AMP, 0.9f), gain = -11f,
            voice = VoiceMod(noise = 0.5f, drive = 0.4f))),
        preset(SynthCategory.WEIRD, patch("ALIEN CHOIR", SAW, TRI, detune = 35f, mix = 0.5f, cutoff = 1500f, reso = 0.5f, fenv = 0.3f,
            amp = Adsr(700f, 800f, 0.9f, 1800f), fe = Adsr(600f, 1000f, 0.5f, 1500f),
            lfo = Lfo(LfoShape.SINE, 6.5f, LfoTarget.SHAPE, 0.6f), gain = -13f, voice = VoiceMod(glideMs = 200f))),
        preset(SynthCategory.WEIRD, patch("WIND", SIN, SIN, mix = 0f, cutoff = 900f, reso = 0.8f, keyTrack = 1f,
            amp = Adsr(1500f, 500f, 1f, 3000f), lfo = Lfo(LfoShape.SINE, 0.15f, LfoTarget.CUTOFF, 0.7f), gain = -10f,
            voice = VoiceMod(noise = 1f))),
        preset(SynthCategory.WEIRD, patch("HOOVER", SAW, SAW, o2 = -1, detune = -30f, mix = 0.5f, cutoff = 3000f, reso = 0.2f,
            amp = Adsr(5f, 400f, 0.9f, 300f), lfo = Lfo(LfoShape.SINE, 4.5f, LfoTarget.PITCH, 0.3f), gain = -12f,
            voice = VoiceMod(pitchEnvSemis = -12f, pitchEnvDecayMs = 150f, glideMs = 120f, mono = true, drive = 0.4f))),
    )

    // ---------------- Drum kits ----------------
    private fun v(name: String, m: DrumModel, tune: Float = 0f, decay: Float = 400f, tone: Float = 0.5f, snap: Float = 0.5f,
                  drive: Float = 0f, level: Float = 0f, pan: Float = 0f, choke: Int = 0) =
        DrumVoice(name, m, tune, decay, tone, snap, drive, level, pan, choke)
    private fun kit(name: String, vararg voices: DrumVoice) = KitPreset(name, DrumKit(name, voices.toList()))
    private fun kit(name: String, bus: DrumBus, vararg voices: DrumVoice) = KitPreset(name, DrumKit(name, voices.toList(), bus))
    private val K = DrumModel.KICK; private val S = DrumModel.SNARE; private val C = DrumModel.CLAP; private val H = DrumModel.HAT
    private val T = DrumModel.TOM; private val R = DrumModel.RIM; private val B = DrumModel.COWBELL; private val Y = DrumModel.CYMBAL
    private val Z = DrumModel.ZAP; private val SH = DrumModel.SHAKER

    val kits: List<KitPreset> = listOf(
        kit("TR-808",
            v("KICK", K, decay = 900f, tone = 0.1f, snap = 0.35f, drive = 0.05f), v("SNARE", S, tune = 2f, decay = 180f, tone = 0.55f, snap = 0.55f),
            v("CLAP", C, decay = 300f, tone = 0.4f), v("C.HAT", H, decay = 45f, snap = 0.3f, choke = 1, pan = 0.15f),
            v("O.HAT", H, decay = 450f, snap = 0.3f, choke = 1, pan = 0.15f), v("TOM", T, decay = 400f, tone = 0.4f, snap = 0.3f, pan = -0.2f),
            v("COWBELL", B, decay = 300f, snap = 0.4f, level = -4f), v("CYMBAL", Y, decay = 1800f, level = -5f, pan = 0.25f)),
        kit("TR-909",
            v("KICK", K, tune = 2f, decay = 380f, tone = 0.5f, snap = 0.75f, drive = 0.25f), v("SNARE", S, tune = 4f, decay = 200f, tone = 0.75f, snap = 0.8f),
            v("CLAP", C, decay = 220f, tone = 0.6f), v("C.HAT", H, decay = 50f, tone = 0.8f, choke = 1),
            v("O.HAT", H, decay = 380f, tone = 0.8f, choke = 1), v("TOM", T, tune = 3f, decay = 300f, snap = 0.6f),
            v("RIM", R, decay = 50f), v("CRASH", Y, decay = 2200f, tone = 0.7f, level = -4f)),
        kit("TRAP",
            v("KICK", K, tune = -2f, decay = 600f, tone = 0.3f, snap = 0.5f, drive = 0.3f), v("SNARE", S, tune = 5f, decay = 160f, tone = 0.7f, snap = 0.9f),
            v("CLAP", C, decay = 350f, tone = 0.5f, level = 1f), v("C.HAT", H, decay = 35f, tone = 0.9f, snap = 0.1f, choke = 1),
            v("O.HAT", H, decay = 300f, tone = 0.9f, choke = 1), v("TOM", T, tune = -5f, decay = 500f, snap = 0.5f),
            v("RIM", R, tune = 5f, decay = 60f, level = -3f), v("CYMBAL", Y, decay = 1600f, level = -6f)),
        kit("BOOM BAP", DrumBus(crunch = 0.3f, tape = 0.3f),
            v("KICK", K, tune = -1f, decay = 350f, tone = 0.7f, snap = 0.6f, drive = 0.5f), v("SNARE", S, decay = 260f, tone = 0.4f, snap = 0.7f, drive = 0.4f),
            v("CLAP", C, decay = 200f, tone = 0.3f, level = -3f), v("C.HAT", H, decay = 70f, tone = 0.35f, snap = 0.6f, drive = 0.3f, choke = 1),
            v("O.HAT", H, decay = 350f, tone = 0.35f, drive = 0.3f, choke = 1), v("SHAKER", SH, decay = 120f, tone = 0.4f, snap = 0.4f, level = -5f),
            v("RIM", R, decay = 60f, tone = 0.3f), v("RIDE", Y, decay = 1200f, tone = 0.3f, level = -6f)),
        kit("LO-FI DUST", DrumBus(crunch = 0.5f, muffle = 0.35f, tape = 0.2f, dust = 0.4f),
            v("KICK", K, tune = -3f, decay = 300f, tone = 0.2f, snap = 0.3f, drive = 0.6f), v("SNARE", S, tune = -2f, decay = 240f, tone = 0.2f, snap = 0.5f, drive = 0.6f),
            v("CLAP", C, decay = 260f, tone = 0.15f, drive = 0.5f, level = -3f), v("C.HAT", H, decay = 60f, tone = 0.2f, drive = 0.5f, choke = 1),
            v("O.HAT", H, decay = 280f, tone = 0.2f, drive = 0.5f, choke = 1), v("SHAKER", SH, decay = 150f, tone = 0.2f, snap = 0.2f, level = -4f),
            v("RIM", R, tune = -4f, decay = 70f, tone = 0.2f, drive = 0.4f), v("CYMBAL", Y, decay = 900f, tone = 0.1f, drive = 0.5f, level = -7f)),
        // ---- crate-digger kits: the kit voiced like chopped records, the DUST bus doing the sampler ----
        // Drunk, soft-kicked soul swing: round kick, warm snare with a clap tucked under it, thin hats.
        kit("DRUNK SWING", DrumBus(crunch = 0.35f, muffle = 0.25f, tape = 0.45f, dust = 0.15f),
            v("KICK", K, tune = -2f, decay = 320f, tone = 0.25f, snap = 0.35f, drive = 0.2f), v("SNARE", S, tune = -1f, decay = 210f, tone = 0.35f, snap = 0.75f, drive = 0.25f),
            v("CLAP", C, decay = 240f, tone = 0.35f, level = -4f), v("C.HAT", H, decay = 55f, tone = 0.3f, snap = 0.5f, level = -5f, pan = 0.2f, choke = 1),
            v("O.HAT", H, decay = 300f, tone = 0.3f, level = -6f, pan = 0.2f, choke = 1), v("CONGA", T, tune = -7f, decay = 300f, tone = 0.3f, snap = 0.2f, level = -4f),
            v("SHAKER", SH, decay = 110f, tone = 0.35f, snap = 0.3f, level = -7f, pan = -0.25f), v("RIDE", Y, decay = 1100f, tone = 0.25f, level = -9f, pan = 0.3f)),
        // Dark, filtered, heavy: a big cracking snare over a muffled kick, record dust underneath.
        kit("CRATE DUST", DrumBus(crunch = 0.45f, muffle = 0.45f, tape = 0.35f, dust = 0.35f),
            v("KICK", K, tune = -3f, decay = 380f, tone = 0.15f, snap = 0.55f, drive = 0.45f, level = 1f), v("SNARE", S, tune = -2f, decay = 300f, tone = 0.3f, snap = 0.85f, drive = 0.5f),
            v("CLAP", C, decay = 280f, tone = 0.25f, level = -5f), v("C.HAT", H, decay = 50f, tone = 0.2f, snap = 0.4f, level = -7f, choke = 1),
            v("O.HAT", H, decay = 260f, tone = 0.2f, level = -8f, choke = 1), v("TOM", T, tune = -4f, decay = 260f, tone = 0.3f, level = -5f),
            v("RIM", R, tune = -3f, decay = 60f, tone = 0.25f, level = -4f), v("CYMBAL", Y, decay = 900f, tone = 0.15f, level = -10f)),
        // Raw and crunchy: loud overdriven snare, gritty hats, a low thud, heavy crush and crackle.
        kit("VILLAIN", DrumBus(crunch = 0.65f, muffle = 0.3f, tape = 0.5f, dust = 0.5f),
            v("KICK", K, tune = -1f, decay = 300f, tone = 0.3f, snap = 0.6f, drive = 0.6f), v("SNARE", S, tune = 1f, decay = 240f, tone = 0.45f, snap = 0.9f, drive = 0.7f, level = 1f),
            v("CLAP", C, decay = 220f, tone = 0.4f, drive = 0.4f, level = -4f), v("C.HAT", H, decay = 45f, tone = 0.35f, snap = 0.7f, drive = 0.4f, level = -4f, choke = 1),
            v("O.HAT", H, decay = 220f, tone = 0.35f, drive = 0.4f, level = -6f, choke = 1), v("THUD", T, tune = -9f, decay = 350f, tone = 0.2f, snap = 0.4f, level = -3f),
            v("RIM", R, tune = 2f, decay = 55f, drive = 0.3f, level = -3f), v("CYMBAL", Y, decay = 700f, tone = 0.3f, drive = 0.4f, level = -9f)),
        // Sped-up-soul era: punchy kick, hard snare stacked with a big clap, tambourine, crisp hats.
        kit("SOUL CHOP", DrumBus(crunch = 0.25f, muffle = 0.12f, tape = 0.4f, dust = 0.1f),
            v("KICK", K, decay = 340f, tone = 0.45f, snap = 0.7f, drive = 0.35f, level = 1f), v("SNARE", S, tune = 2f, decay = 230f, tone = 0.55f, snap = 0.85f, drive = 0.3f),
            v("CLAP", C, decay = 260f, tone = 0.55f, snap = 0.7f, level = -1f), v("C.HAT", H, decay = 50f, tone = 0.55f, snap = 0.5f, level = -4f, choke = 1),
            v("O.HAT", H, decay = 320f, tone = 0.55f, level = -6f, choke = 1), v("TOM", T, tune = -3f, decay = 320f, tone = 0.35f, level = -4f),
            v("TAMB", SH, decay = 160f, tone = 0.75f, snap = 0.8f, level = -5f, pan = -0.2f), v("CYMBAL", Y, decay = 1400f, tone = 0.45f, level = -9f)),
        kit("HOUSE",
            v("KICK", K, tune = 1f, decay = 450f, tone = 0.45f, snap = 0.7f, drive = 0.2f), v("SNARE", S, tune = 3f, decay = 180f, tone = 0.7f, snap = 0.7f, level = -3f),
            v("CLAP", C, decay = 280f, tone = 0.55f, level = 1f), v("C.HAT", H, decay = 40f, tone = 0.75f, choke = 1, pan = -0.2f),
            v("O.HAT", H, decay = 260f, tone = 0.75f, choke = 1, pan = -0.2f), v("SHAKER", SH, decay = 90f, tone = 0.7f, snap = 0.5f, pan = 0.3f, level = -4f),
            v("COWBELL", B, tune = 2f, decay = 200f, level = -6f, pan = 0.25f), v("RIDE", Y, decay = 900f, tone = 0.9f, level = -6f)),
        kit("TECHNO",
            v("KICK", K, tune = -1f, decay = 550f, tone = 0.2f, snap = 0.8f, drive = 0.6f), v("CLAP", C, decay = 240f, tone = 0.45f, drive = 0.3f),
            v("SNARE", S, tune = 6f, decay = 120f, tone = 0.8f, snap = 0.9f, level = -4f), v("C.HAT", H, decay = 30f, tone = 0.7f, choke = 1),
            v("O.HAT", H, decay = 200f, tone = 0.7f, choke = 1), v("PERC", T, tune = 5f, decay = 180f, snap = 0.8f, drive = 0.3f, pan = 0.3f),
            v("RIM", R, tune = 2f, decay = 45f, pan = -0.3f), v("RIDE", Y, decay = 900f, tone = 0.9f, level = -5f)),
        kit("DRILL",
            v("KICK", K, tune = 1f, decay = 250f, tone = 0.6f, snap = 0.9f, drive = 0.3f), v("SNARE", S, tune = 8f, decay = 120f, tone = 0.85f, snap = 1f),
            v("CLAP", C, decay = 180f, tone = 0.7f), v("C.HAT", H, decay = 30f, tone = 1f, snap = 0.2f, choke = 1),
            v("O.HAT", H, decay = 220f, tone = 1f, choke = 1), v("TOM", T, tune = -3f, decay = 260f, snap = 0.7f),
            v("RIM", R, tune = 7f, decay = 40f, level = -2f), v("CRASH", Y, decay = 1400f, tone = 0.8f, level = -6f)),
        kit("ELECTRO",
            v("KICK", K, tune = -2f, decay = 700f, tone = 0.15f, snap = 0.6f, drive = 0.2f), v("SNARE", S, tune = 1f, decay = 200f, tone = 0.6f, snap = 0.6f),
            v("CLAP", C, decay = 300f, tone = 0.5f), v("C.HAT", H, decay = 45f, tone = 0.6f, choke = 1),
            v("O.HAT", H, decay = 380f, tone = 0.6f, choke = 1), v("ZAP", Z, decay = 220f, tone = 0.4f, snap = 0.6f, level = -3f),
            v("COWBELL", B, decay = 260f, level = -4f), v("CYMBAL", Y, decay = 1600f, level = -6f)),
        kit("MINIMAL",
            v("KICK", K, tune = 0f, decay = 320f, tone = 0.4f, snap = 0.6f), v("RIM", R, tune = 3f, decay = 50f, tone = 0.6f),
            v("CLAP", C, decay = 160f, tone = 0.7f, level = -3f), v("C.HAT", H, decay = 25f, tone = 0.85f, snap = 0.1f, choke = 1),
            v("O.HAT", H, decay = 160f, tone = 0.85f, choke = 1), v("SHAKER", SH, decay = 70f, tone = 0.8f, snap = 0.7f, level = -5f),
            v("BLIP", Z, tune = 12f, decay = 80f, tone = 0.1f, snap = 0.2f, level = -6f), v("TOM", T, tune = 7f, decay = 140f, snap = 0.5f, level = -4f)),
        kit("TOY BOX",
            v("KICK", K, tune = 12f, decay = 140f, tone = 0.9f, snap = 0.2f), v("SNARE", S, tune = 12f, decay = 90f, tone = 0.6f, snap = 0.4f),
            v("CLAP", C, tune = 12f, decay = 120f, tone = 0.9f), v("TICK", H, tune = 7f, decay = 25f, tone = 0.3f, choke = 1),
            v("TSSS", H, tune = 7f, decay = 200f, tone = 0.3f, choke = 1), v("BOING", Z, tune = 5f, decay = 260f, tone = 0.8f, snap = 0.3f),
            v("BELL", B, tune = 7f, decay = 400f, snap = 0.8f), v("SHAKE", SH, tune = 12f, decay = 100f, tone = 0.9f)),
        kit("INDUSTRIAL",
            v("KICK", K, tune = -4f, decay = 500f, tone = 0.8f, snap = 1f, drive = 0.95f), v("SNARE", S, tune = -3f, decay = 300f, tone = 0.3f, snap = 1f, drive = 0.9f),
            v("CLAP", C, decay = 400f, tone = 0.2f, drive = 0.8f), v("C.HAT", H, decay = 50f, tone = 0.1f, drive = 0.9f, choke = 1),
            v("O.HAT", H, decay = 500f, tone = 0.1f, drive = 0.9f, choke = 1), v("ZAP", Z, tune = -7f, decay = 400f, tone = 0.9f, snap = 1f, drive = 0.6f),
            v("METAL", B, tune = -5f, decay = 600f, snap = 1f, drive = 0.8f), v("CYMBAL", Y, decay = 3000f, tone = 0.2f, drive = 0.7f, level = -6f)),
    )
}
