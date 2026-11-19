// SPDX-License-Identifier: GPL-3.0-or-later
// core-model/src/main/kotlin/com/thorbox/model/Models.kt
//
// Pure-Kotlin, immutable, serializable project model. This is the single source of truth.
// The C++ engine never reads these objects directly. A ProjectSync layer diffs them and sends
// compact commands over JNI.
//
// Units:
//   - Audio positions are in FRAMES at the sample's native rate (never seconds, never normalized floats).
//   - Musical time is in TICKS at PPQN = 960 (one quarter note = 960 ticks).
//   - Pitch is in semitones (Int) plus cents (Int, -100..100).
//   - Normalized parameters are Float 0f..1f. Engine-side curves map them to Hz, dB, ms, and so on.

package com.thorbox.model

import kotlinx.serialization.Serializable

const val PPQN = 960
const val PADS_PER_BANK = 16

// ---------- IDs ----------
@Serializable @JvmInline value class SampleId(val v: String)
@Serializable @JvmInline value class SliceId(val v: String)
@Serializable @JvmInline value class TrackId(val v: String)
@Serializable @JvmInline value class SceneId(val v: String)
@Serializable @JvmInline value class ClipId(val v: String)
@Serializable @JvmInline value class PatchId(val v: String)

// ---------- Music theory ----------
@Serializable enum class PitchClass { C, Cs, D, Ds, E, F, Fs, G, Gs, A, As, B }
@Serializable enum class ScaleMode { MAJOR, MINOR, DORIAN, MIXOLYDIAN, PENTATONIC_MAJOR, PENTATONIC_MINOR, CHROMATIC }

@Serializable
data class MusicalKey(val root: PitchClass, val mode: ScaleMode) {
    // Formats as "C# Min" or "F Maj".
    fun label(): String = root.name.replace("s", "#") + " " +
        when (mode) { ScaleMode.MAJOR -> "Maj"; ScaleMode.MINOR -> "Min"; else -> mode.name.lowercase() }
}

// ---------- Sample ----------
@Serializable
data class Sample(
    val id: SampleId,
    val name: String,
    val filePath: String,            // Relative to the project /audio folder. Always decoded/stored as WAV.
    val origin: SampleOrigin,
    val sampleRate: Int,
    val channels: Int,               // 1 or 2
    val frameCount: Long,
    val analysis: SampleAnalysis? = null, // Null while analysis is running
    val slices: List<Slice> = emptyList(),
)

@Serializable enum class SampleOrigin { IMPORTED, RESAMPLED_PAD, RESAMPLED_TRACK, RESAMPLED_MASTER, RECORDED_INPUT }

@Serializable
data class SampleAnalysis(
    val key: MusicalKey?,            // Null when confidence is too low (for example a drum one-shot). The UI shows "—".
    val keyConfidence: Float,        // 0..1
    val bpm: Float?,                 // Null for one-shots and short material
    val bpmConfidence: Float,
    val transientFrames: IntArray,   // Cached onset positions used by Auto-Chop (sensitivity filters on strength)
    val transientStrengths: FloatArray,
    // Waveform overview peaks (min/max pairs per bucket) are stored in a sidecar .peaks file, not in JSON.
)

// ---------- Slice ----------
@Serializable
data class Slice(
    val id: SliceId,
    val startFrame: Long,
    val endFrame: Long,              // Exclusive
    val source: SliceSource,
)

@Serializable enum class SliceSource { AUTO_TRANSIENT, AUTO_EQUAL, LAZY_CHOP, MANUAL }

// ---------- Pads (sampler instrument) ----------
@Serializable enum class PlayMode { ONE_SHOT, GATE, LOOP }

@Serializable
data class Adsr(
    val attackMs: Float = 1f,
    val decayMs: Float = 100f,
    val sustain: Float = 1f,         // 0..1
    val releaseMs: Float = 30f,
)

@Serializable
data class Pad(
    val index: Int,                  // 0..15. Row-major from the top-left, like Koala and the MPC layout.
    val sampleId: SampleId? = null,
    val sliceId: SliceId? = null,    // Null plays the whole (trimmed) sample
    val trimStart: Long = 0,         // Frames, relative to the slice or sample start
    val trimEnd: Long? = null,       // Null runs to the slice or sample end
    val playMode: PlayMode = PlayMode.ONE_SHOT,
    val pitchSemis: Int = 0,         // -24..+24
    val fineCents: Int = 0,          // -100..+100
    val reverse: Boolean = false,
    val gainDb: Float = 0f,
    val pan: Float = 0f,             // -1..1
    val amp: Adsr = Adsr(),
    val filterCutoff: Float = 1f,    // Normalized. Per-pad LPF.
    val chokeGroup: Int = 0,         // 0 = none, 1..8
    val color: Int = 0,              // Palette index, not ARGB, so themes can remap it
)

@Serializable
data class SamplerInstrument(val pads: List<Pad> = List(PADS_PER_BANK) { Pad(it) })

// ---------- Synth ----------
// Physical units throughout (Hz, ms, cents). Knob curves live in the app's SynthParams spec;
// the engine receives these values as-is (see synth::Param in Synth.h).
@Serializable enum class LfoShape { SINE, TRIANGLE, SAW, SQUARE, SAMPLE_HOLD }
@Serializable enum class LfoTarget { PITCH, CUTOFF, AMP, SHAPE }

@Serializable
data class Oscillator(
    val shape: Float = 0.666f,       // 0 sine · 0.333 triangle · 0.666 saw · 1 square (continuous morph)
    val octave: Int = 0,             // -3..+3
    val detuneCents: Float = 0f,     // osc2 only, -100..+100
)

@Serializable
data class Lfo(
    val shape: LfoShape = LfoShape.SINE,
    val rateHz: Float = 1.5f,        // 0.05..20
    val target: LfoTarget = LfoTarget.CUTOFF,
    val depth: Float = 0.2f,         // 0..1
)

@Serializable
data class SynthPatch(
    val id: PatchId = PatchId("init"),
    val name: String = "INIT",
    val osc1: Oscillator = Oscillator(),
    val osc2: Oscillator = Oscillator(shape = 1f, detuneCents = 7f),
    val oscMix: Float = 0.35f,       // 0 = osc1 only, 1 = osc2 only (equal-power)
    val cutoffHz: Float = 1800f,     // 20..20000
    val resonance: Float = 0.25f,    // 0..1
    val filterEnvAmount: Float = 0.45f, // -1..1 (±5 octaves)
    val keyTracking: Float = 0.5f,   // 0..1
    val ampEnv: Adsr = Adsr(attackMs = 5f, decayMs = 250f, sustain = 0.8f, releaseMs = 300f),
    val filterEnv: Adsr = Adsr(attackMs = 2f, decayMs = 350f, sustain = 0.15f, releaseMs = 300f),
    val lfo: Lfo = Lfo(),
    val gainDb: Float = -9f,
)

// ---------- Tracks ----------
@Serializable
sealed interface Instrument {
    @Serializable data class Sampler(val instrument: SamplerInstrument) : Instrument
    @Serializable data class Synth(val patch: SynthPatch) : Instrument
}

@Serializable
data class Track(
    val id: TrackId,
    val name: String,
    val color: Int,
    val instrument: Instrument,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
)

// ---------- Clips & Scenes ----------
@Serializable enum class LaunchQuant { NONE, SIXTEENTH, EIGHTH, QUARTER, BAR, TWO_BARS, FOUR_BARS }
@Serializable enum class LaunchMode { TRIGGER, GATE, TOGGLE, REPEAT }

/** A per-step parameter lock (synth param id from SynthId, value in physical units). */
@Serializable data class PLock(val param: Int, val value: Float)

@Serializable
data class NoteEvent(
    val tick: Int,                   // Relative to the clip start
    val lengthTicks: Int,
    val note: Int,                   // Sampler: pad index 0..15. Synth: MIDI note 0..127.
    val velocity: Float,             // 0..1
    val probability: Float = 1f,
    // OP-XY-style step components
    val ratchet: Int = 1,            // 1..4 retriggers within the note
    val condEvery: Int = 1,          // play on loop condPhase+1 of every condEvery loops (1 = always)
    val condPhase: Int = 0,
    val locks: List<PLock> = emptyList(),   // up to 2
)

@Serializable
data class Clip(
    val id: ClipId,
    val name: String,
    val color: Int,
    val lengthTicks: Int = PPQN * 4 * 2, // Defaults to 2 bars in 4/4
    val notes: List<NoteEvent> = emptyList(),
    val launchQuant: LaunchQuant? = null, // Null uses the project default
    val launchMode: LaunchMode = LaunchMode.TRIGGER,
    val loop: Boolean = true,
    val legato: Boolean = false,
    val swing: Float? = null,        // Null uses the project swing
)

@Serializable
data class Scene(
    val id: SceneId,
    val name: String,
    val tempo: Float? = null,        // Optional tempo change on launch
)

// The Session matrix: columns are tracks, rows are scenes, and a sparse map holds the occupied slots.
// NOTE: object map keys need Json { allowStructuredMapKeys = true } when (de)serializing Project.
@Serializable data class SlotKey(val track: TrackId, val scene: SceneId)

// ---------- Master FX (performance) ----------
@Serializable enum class PerfFx { VINYL_SIM, STUTTER_LOOPER, ISOLATOR, TAPE_STOP, BITCRUSH }

@Serializable
data class PerfFxSettings(
    val fx: PerfFx,
    val paramA: Float = 0.5f,        // The X axis when held on the XY matrix
    val paramB: Float = 0.5f,        // The Y axis
    val latch: Boolean = false,      // Off by default: release snaps back dry
)

// ---------- Project ----------
@Serializable
data class Project(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val bpm: Float = 90f,
    val timeSigNum: Int = 4,
    val timeSigDen: Int = 4,
    val swing: Float = 0f,           // 0..1 (displayed as 50%..75%)
    val defaultLaunchQuant: LaunchQuant = LaunchQuant.BAR,
    val key: MusicalKey? = null,     // The project-wide key that locks the scale keyboard. Seeded from the first analyzed sample.
    val samples: Map<SampleId, Sample> = emptyMap(),
    val tracks: List<Track> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val clips: Map<SlotKey, Clip> = emptyMap(),
    val perfFx: List<PerfFxSettings> = PerfFx.entries.map { PerfFxSettings(it) },
)

// ---------- Transient UI/session state (NOT persisted) ----------
sealed interface Selection {
    data class PadSel(val track: TrackId, val pad: Int) : Selection
    data class ClipSel(val slot: SlotKey) : Selection
    data class TrackSel(val track: TrackId) : Selection
    data object None : Selection
}

enum class AppMode { DAW, PERFORMANCE }
enum class Deck { PADS, KEYS, STEP, FX, STRUM }
