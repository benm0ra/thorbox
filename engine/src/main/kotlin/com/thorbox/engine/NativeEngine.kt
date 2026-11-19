// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.engine

/**
 * Kotlin facade over the C++ engine.
 *
 * THREADING RULE: call everything here from the Android main thread. The native command queue
 * is single-producer, and the main thread is that producer. Both screens (Activity +
 * Presentation) and gamepad input all run on the main thread, so this holds naturally.
 * The one exception is [peaks], which is read-only and safe from any thread.
 */
object NativeEngine {
    init { System.loadLibrary("thorbox_engine") }

    enum class PlayMode { ONE_SHOT, GATE, LOOP } // ordinals match C++ PlayMode

    data class Stats(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val bufferFrames: Int,
        val latencyMs: Double,      // -1 when the device can't report it yet
        val xruns: Int,
        val exclusive: Boolean,     // true ≈ MMAP fast path
        val aaudio: Boolean,
    ) {
        val bufferMs: Double get() = bufferFrames * 1000.0 / sampleRate
    }

    sealed interface Event {
        data class SampleLoaded(
            val requestId: Int, val sampleId: Int, val frames: Long, val sampleRate: Int, val channels: Int,
            val keyRoot: Int, val keyMinor: Boolean, val keyConfidence: Float,
            val bpm: Float, val bpmConfidence: Float,
        ) : Event
        data class LoadFailed(val requestId: Int) : Event
    }

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    fun noteOn(pad: Int, velocity: Float) = nativeNoteOn(pad, velocity)
    fun noteOff(pad: Int) = nativeNoteOff(pad)
    fun setPitch(pad: Int, semitones: Int, cents: Float) = nativeSetPitch(pad, semitones, cents)
    fun setPlayMode(pad: Int, mode: PlayMode) = nativeSetPlayMode(pad, mode.ordinal)
    fun setMasterGainDb(db: Float) = nativeSetMasterGainDb(db)

    // ---- Synth ----
    fun synthNoteOn(track: Int, note: Int, velocity: Float) = nativeSynthNoteOn(track, note, velocity)
    fun synthNoteOff(track: Int, note: Int) = nativeSynthNoteOff(track, note)
    /** [id] = SynthParam.id, [value] in physical units. Rejected (false) if out of range or not finite. */
    fun synthParam(track: Int, id: Int, value: Float) = nativeSynthParam(track, id, value)
    fun synthAllOff(track: Int) = nativeSynthAllOff(track)

    /**
     * Live synth state for animations, filled in place (zero allocation per frame).
     * [values] layout (12): 0 lfoValue, 1 lfoPhase, 2 ampLevel, 3 ampStage, 4 ampStageTime s,
     * 5 fenvLevel, 6 fenvStage, 7 fenvStageTime s, 8 cutoffHz (modulated), 9 activeVoices,
     * 10 newestNote (-1 none), 11 master gain. Stages: 0 off, 1 A, 2 D, 3 S, 4 R.
     * [notes] (2): bitmask of sounding MIDI notes (0–63, 64–127).
     */
    fun synthTelemetry(values: FloatArray, notes: LongArray) = nativeGetSynthTelemetry(values, notes)
    /** Latest synth output samples (mono), oldest first. Returns count written. */
    fun scope(out: FloatArray): Int = nativeGetScope(out)

    // ---- Transport / groove ----
    fun setTempo(bpm: Float) = nativeSetTempo(bpm)
    /** [amount] 0.50 (straight) … 0.75; [gridTicks] 240 = 1/16, 480 = 1/8. */
    fun setSwing(amount: Float, gridTicks: Int) = nativeSetSwing(amount, gridTicks)
    fun setPlaying(on: Boolean) = nativeSetPlaying(on)
    fun setRecording(on: Boolean) = nativeSetRecording(on)
    fun setMetronome(on: Boolean) = nativeSetMetronome(on)

    /**
     * Replaces a track's pattern (0 = sampler, note = pad; 1 = synth, note = MIDI). Arrays are parallel;
     * flags[i] = ratchet | condEvery shl 4 | condPhase shl 8; lockIds/lockVals hold 2 entries per event (-1 = none).
     */
    fun setPattern(track: Int, lengthTicks: Int, ticks: IntArray, lengths: IntArray, notes: IntArray, velocities: FloatArray,
                   probabilities: FloatArray, flags: IntArray, lockIds: IntArray, lockVals: FloatArray) =
        nativeSetPattern(track, lengthTicks, ticks, lengths, notes, velocities, probabilities, flags, lockIds, lockVals)

    // ---- Sampler pads & chopping ----
    /** Sampler pads are global: track * PADS_PER_TRACK + pad. Pad 16 of each track is its chop-preview voice. */
    const val PADS_PER_TRACK = 17
    const val PREVIEW_PAD = 16
    fun pad(track: Int, local: Int) = track * PADS_PER_TRACK + local

    // ---- Tracks: 8, each a sampler, synth or drum machine (ordinals match TrackType in Commands.h) ----
    const val TRACKS = 8
    object TrackType { const val NONE = 0; const val SAMPLER = 1; const val SYNTH = 2; const val DRUMS = 3 }
    fun setTrackType(track: Int, type: Int) = nativeSetTrackType(track, type)
    /** Which synth feeds the synth telemetry / scope, and which sampler track the pad telemetry. */
    fun setFocus(track: Int) = nativeSetFocus(track)
    /** [2k, 2k+1] strip k L/R peaks, [16, 17] master, [18 + k] strip k sidechain gain (1 = none). */
    fun meters(out: FloatArray) = nativeGetMeters(out)
    const val METERS = 2 * TRACKS + 2 + TRACKS
    /** Slice region for a pad, in frames of its sample (end -1 = to the end). */
    fun setPadRegion(pad: Int, startFrame: Int, endFrame: Int) = nativeSetPadRegion(pad, startFrame, endFrame)
    fun setPadGain(pad: Int, linear: Float) = nativeSetPadGain(pad, linear)
    /** Onset frames for auto-chop (first is 0). Thread-safe: call it off the main thread. */
    fun transients(sampleId: Int, sensitivity: Float, maxSlices: Int, out: LongArray): Int = nativeGetTransients(sampleId, sensitivity, maxSlices, out)
    /** Time-stretch / pitch-shift a loaded sample on the worker. The result arrives via pollEvent as a new sample. */
    fun stretchSample(sampleId: Int, tempo: Double, semitones: Double, requestId: Int) = nativeStretchSample(sampleId, tempo, semitones, requestId)

    fun repeatOn(track: Int, note: Int, rateTicks: Int, velocity: Float) = nativeRepeatOn(track, note, rateTicks, velocity)
    fun repeatOff(track: Int, note: Int) = nativeRepeatOff(track, note)
    fun repeatVelocity(v: Float) = nativeRepeatVelocity(v)

    // ---- Mixer: strip 0 sampler, 1 synth, 2 drums, 3 master (volume only) ----
    object Ch { const val VOLUME_DB = 0; const val PAN = 1; const val MUTE = 2; const val SOLO = 3
        const val SC_SOURCE = 4; const val SC_AMOUNT = 5; const val SC_RELEASE = 6 }
    const val STRIP_MASTER = TRACKS

    // ---- Drum synth: 8 slots, each a synthesis model (ordinals match drums::Model in DrumSynth.h) ----
    enum class DrumModel(val label: String) { KICK("KICK"), SNARE("SNARE"), CLAP("CLAP"), HAT("HAT"), TOM("TOM"), RIM("RIM"),
        COWBELL("BELL"), CYMBAL("CYMB"), ZAP("ZAP"), SHAKER("SHAKE") }
    object DrumParam { const val MODEL = 0; const val TUNE = 1; const val DECAY = 2; const val TONE = 3; const val SNAP = 4
        const val DRIVE = 5; const val LEVEL_DB = 6; const val PAN = 7; const val CHOKE = 8; const val COUNT = 9 }
    const val DRUM_SLOTS = 8
    fun drumTrigger(track: Int, slot: Int, velocity: Float) = nativeDrumTrigger(track, slot, velocity)
    fun drumParam(track: Int, slot: Int, id: Int, value: Float) = nativeDrumParam(track, slot, id, value)
    /** Per-slot level (0..1) and hit counters (compare with the last read to spot new hits). */
    fun drumTelemetry(track: Int, levels: FloatArray, hits: IntArray) = nativeGetDrumTelemetry(track, levels, hits)
    fun setChannel(strip: Int, param: Int, value: Float) = nativeSetChannel(strip, param, value)

    // ---- Punch-in FX ----
    object Fx { const val STUTTER = 0; const val FILTER = 1; const val TAPE_STOP = 2; const val CRUSH = 3; const val VINYL = 4; const val REVERSE = 5; const val GATE = 6; const val HAZE = 7; const val COUNT = 8 }
    fun fxOn(id: Int, x: Float, y: Float) = nativeFxOn(id, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    fun fxMove(id: Int, x: Float, y: Float) = nativeFxMove(id, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    fun fxOff(id: Int) = nativeFxOff(id)
    fun fxAllOff() = nativeFxAllOff()

    /**
     * Transport + meters, filled in place. Layout (20): 0 tick (absolute since PLAY), 1 playing,
     * 2 recording, 3 bpm, 4/5 sampler L/R peak, 6/7 synth L/R, 8/9 master L/R, 10 swing,
     * 11 metronome, 12 active FX bitmask, 13 sounding pads mask, 14 newest pad, 16/17 drums L/R,
     * 18 sounding drum slots mask. Peaks are held until read.
     */
    fun transport(out: DoubleArray) = nativeGetTransport(out)

    /** Recorded live hit: returns 0 none, 1 note-on, 2 note-off; [out] = track, note, velocity, tick. */
    fun pollRecord(out: DoubleArray): Int = nativePollRecord(out)

    /** Visualizer: FFT of the master output into [bands] (≤64, 0..1, log 30 Hz–16 kHz), latest
     *  [wave] samples, and [energy] = bass, mid, treble. Runs the FFT on the calling (main) thread. */
    fun visual(bands: FloatArray, wave: FloatArray, energy: FloatArray) = nativeGetVisual(bands, wave, energy)

    /** Takes ownership of [fd] (a detached ParcelFileDescriptor). The result arrives via [pollEvent]. */
    fun loadSample(fd: Int, requestId: Int) = nativeLoadSampleFd(fd, requestId)
    fun assignSample(pad: Int, sampleId: Int) = nativeAssignSample(pad, sampleId)
    fun unloadSample(sampleId: Int) = nativeUnloadSample(sampleId)

    /** Min/max pairs, [buckets] long. Thread-safe. */
    fun peaks(sampleId: Int, buckets: Int): FloatArray? {
        val out = FloatArray(buckets * 2)
        return if (nativeGetPeaks(sampleId, out)) out else null
    }

    fun playhead(): Float = nativePlayhead()
    fun peakLevel(): Float = nativePeakLevel()

    private val statsBuf = DoubleArray(8)
    fun stats(): Stats? {
        nativeGetStats(statsBuf)
        val a = statsBuf
        if (a[0] == 0.0) return null
        return Stats(a[1].toInt(), a[2].toInt(), a[3].toInt(), a[4], a[5].toInt(), a[6] == 1.0, a[7] == 1.0)
    }

    private val evBuf = DoubleArray(10)
    fun pollEvent(): Event? = when (nativePollEvent(evBuf)) {
        1 -> evBuf.let {
            Event.SampleLoaded(
                requestId = it[0].toInt(), sampleId = it[1].toInt(), frames = it[2].toLong(),
                sampleRate = it[3].toInt(), channels = it[4].toInt(),
                keyRoot = it[5].toInt(), keyMinor = it[6].toInt() == 1, keyConfidence = it[7].toFloat(),
                bpm = it[8].toFloat(), bpmConfidence = it[9].toFloat(),
            )
        }
        2 -> Event.LoadFailed(evBuf[0].toInt())
        else -> null
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(pad: Int, velocity: Float): Boolean
    private external fun nativeNoteOff(pad: Int): Boolean
    private external fun nativeSetPitch(pad: Int, semitones: Int, cents: Float): Boolean
    private external fun nativeSetPlayMode(pad: Int, mode: Int): Boolean
    private external fun nativeSetMasterGainDb(db: Float): Boolean
    private external fun nativeLoadSampleFd(fd: Int, requestId: Int)
    private external fun nativeAssignSample(pad: Int, sampleId: Int): Boolean
    private external fun nativeUnloadSample(sampleId: Int)
    private external fun nativePollEvent(out: DoubleArray): Int
    private external fun nativeGetPeaks(sampleId: Int, out: FloatArray): Boolean
    private external fun nativePlayhead(): Float
    private external fun nativePeakLevel(): Float
    private external fun nativeGetStats(out: DoubleArray)
    private external fun nativeSynthNoteOn(track: Int, note: Int, velocity: Float): Boolean
    private external fun nativeSynthNoteOff(track: Int, note: Int): Boolean
    private external fun nativeSynthParam(track: Int, id: Int, value: Float): Boolean
    private external fun nativeSynthAllOff(track: Int): Boolean
    private external fun nativeSetTrackType(track: Int, type: Int): Boolean
    private external fun nativeSetFocus(track: Int): Boolean
    private external fun nativeGetMeters(out: FloatArray)
    private external fun nativeGetSynthTelemetry(values: FloatArray, notes: LongArray)
    private external fun nativeGetScope(out: FloatArray): Int
    private external fun nativeSetTempo(bpm: Float): Boolean
    private external fun nativeSetSwing(amount: Float, grid: Int): Boolean
    private external fun nativeSetPlaying(on: Boolean): Boolean
    private external fun nativeSetRecording(on: Boolean): Boolean
    private external fun nativeSetMetronome(on: Boolean): Boolean
    private external fun nativeSetPattern(track: Int, lengthTicks: Int, ticks: IntArray, lens: IntArray, notes: IntArray, vels: FloatArray, probs: FloatArray,
                                          flags: IntArray, lockIds: IntArray, lockVals: FloatArray): Boolean
    private external fun nativeSetPadRegion(pad: Int, start: Int, end: Int): Boolean
    private external fun nativeSetPadGain(pad: Int, g: Float): Boolean
    private external fun nativeGetTransients(sampleId: Int, sens: Float, maxSlices: Int, out: LongArray): Int
    private external fun nativeStretchSample(sampleId: Int, tempo: Double, semis: Double, requestId: Int)
    private external fun nativeRepeatOn(track: Int, note: Int, rate: Int, vel: Float): Boolean
    private external fun nativeRepeatOff(track: Int, note: Int): Boolean
    private external fun nativeRepeatVelocity(v: Float): Boolean
    private external fun nativeSetChannel(strip: Int, param: Int, value: Float): Boolean
    private external fun nativeFxOn(id: Int, x: Float, y: Float): Boolean
    private external fun nativeFxMove(id: Int, x: Float, y: Float): Boolean
    private external fun nativeFxOff(id: Int): Boolean
    private external fun nativeFxAllOff(): Boolean
    private external fun nativeGetTransport(out: DoubleArray)
    private external fun nativeDrumTrigger(track: Int, slot: Int, velocity: Float): Boolean
    private external fun nativeDrumParam(track: Int, slot: Int, id: Int, value: Float): Boolean
    private external fun nativeGetDrumTelemetry(track: Int, levels: FloatArray, hits: IntArray)
    private external fun nativePollRecord(out: DoubleArray): Int
    private external fun nativeGetVisual(bands: FloatArray, wave: FloatArray, energy: FloatArray)
}
