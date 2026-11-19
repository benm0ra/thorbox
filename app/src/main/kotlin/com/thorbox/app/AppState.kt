// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.groove.GrooveController
import com.thorbox.app.groove.Scales
import com.thorbox.app.input.InputRouter
import com.thorbox.app.library.LibraryItem
import com.thorbox.app.library.SampleLibrary
import com.thorbox.app.synth.SynthId
import com.thorbox.app.synth.SynthPage
import com.thorbox.app.synth.SynthParams
import com.thorbox.engine.NativeEngine
import com.thorbox.model.Deck
import com.thorbox.model.MusicalKey
import com.thorbox.model.PitchClass
import com.thorbox.model.PlayMode
import com.thorbox.model.ScaleMode
import com.thorbox.model.SynthPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the UI shows about the loaded sample. */
data class LoadedSample(
    val engineId: Int,
    val name: String,
    val frames: Long,
    val sampleRate: Int,
    val channels: Int,
    val key: MusicalKey?,
    val keyConfidence: Float,
    val bpm: Float?,
    val bpmConfidence: Float,
) {
    val seconds: Double get() = frames.toDouble() / sampleRate
}

data class DisplayProbe(
    val id: Int, val name: String, val widthPx: Int, val heightPx: Int, val refreshHz: Float,
    val isDefault: Boolean, val presentationFlag: Boolean, val state: String, val role: ScreenRole,
)

enum class ScreenRole { SESSION, DECK }
enum class TopView { SAMPLER, SYNTH, DRUM, SEQ, MIX, VIZ }

/**
 * Live synth state for animations, refreshed every vsync into preallocated arrays.
 * Composables read [frame] inside their draw lambdas, so a new frame only re-draws them and
 * never recomposes: the animation is free of allocation and composition.
 */
class SynthTelemetry {
    /** Slower clock for decorative redraws (glass, aurora): ≤60 Hz, or ≤30 Hz in SUBTLE. */
    val glassFrame = mutableLongStateOf(0L)
    val v = FloatArray(12)            // layout: see NativeEngine.synthTelemetry
    val notes = LongArray(2)
    val scope = FloatArray(1024)
    var scopeCount = 0
    val lfoHistory = FloatArray(240)  // ring of recent LFO values (for the trail)
    var lfoHead = 0
    val frame = mutableLongStateOf(0L)
    var timeSec = 0f                  // monotonic animation clock

    fun isNoteOn(n: Int) = n in 0..127 && ((notes[n ushr 6] ushr (n and 63)) and 1L) == 1L
    val lfoValue get() = v[0]; val lfoPhase get() = v[1]
    val ampLevel get() = v[2]; val ampStage get() = v[3].toInt(); val ampStageTime get() = v[4]
    val fenvLevel get() = v[5]; val fenvStage get() = v[6].toInt(); val fenvStageTime get() = v[7]
    val cutoffHz get() = v[8]; val activeVoices get() = v[9].toInt(); val newestNote get() = v[10].toInt()
}

/**
 * Single source of truth (M1/M5 scale). Grows into ProjectStore + undo in M4.
 * Main-thread only (see NativeEngine's threading rule).
 */
class AppState(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val presets = com.thorbox.app.presets.PresetStore(app, scope)
    val library = SampleLibrary(app)
    val input = InputRouter(this)
    val groove = GrooveController(this)
    val sampler = com.thorbox.app.sampler.SamplerController(this)
    val drums = com.thorbox.app.drums.DrumController(this)

    // ---- Look & visualizer ----
    val glass = mutableStateOf(com.thorbox.app.ui.GlassLevel.FULL)
    fun cycleGlass() { glass.value = com.thorbox.app.ui.GlassLevel.entries[(glass.value.ordinal + 1) % 3] }
    val viz = com.thorbox.app.viz.VizData()
    val vizMode = mutableStateOf(com.thorbox.app.ui.VizMode.SPECTRA)
    fun cycleViz() { vizMode.value = com.thorbox.app.ui.VizMode.entries[(vizMode.value.ordinal + 1) % com.thorbox.app.ui.VizMode.entries.size] }
    /** Show the visualizer automatically once music has played for a while with no touches on the top screen. */
    val vizAuto = mutableStateOf(true)
    private var lastTopTouch = 0L
    private var viewBeforeViz = TopView.SAMPLER
    fun noteTopTouch() { lastTopTouch = SystemClock.uptimeMillis() }

    fun setLibraryRoot(uri: Uri) { library.setRoot(uri); rescanLibrary() }
    fun rescanLibrary() { scope.launch { library.rescan() } }

    // ---- Navigation: deck tab drives the top screen's contextual view (Ableton's "selection → detail") ----
    val deck = mutableStateOf(Deck.PADS)
    val topView = mutableStateOf(TopView.SAMPLER)
    /** Leave the visualizer back to whatever was showing before it. */
    fun exitViz() { if (topView.value == TopView.VIZ) topView.value = viewBeforeViz; noteTopTouch() }

    fun selectDeck(d: Deck) {
        if (d == Deck.STRUM) return   // not built yet
        if (deck.value == Deck.KEYS && d != Deck.KEYS) releaseAllKeys()
        if (deck.value == Deck.FX && d != Deck.FX) groove.stopAll()
        deck.value = d
        // The instrument decks pull their editor onto the top screen. SEQ and MIX stay put.
        when (d) {
            Deck.PADS -> { if (topView.value == TopView.SYNTH || topView.value == TopView.DRUM) topView.value = TopView.SAMPLER; groove.seqTrack.intValue = 0 }
            Deck.KEYS -> { if (topView.value == TopView.SAMPLER || topView.value == TopView.DRUM) topView.value = TopView.SYNTH; groove.seqTrack.intValue = 1 }
            Deck.DRUMS -> { if (topView.value == TopView.SAMPLER || topView.value == TopView.SYNTH) topView.value = TopView.DRUM; groove.seqTrack.intValue = 2 }
            // The STEP deck is the synth's OP-XY step sequencer (drums have their own TR grid on DRM).
            Deck.STEP -> { topView.value = TopView.SEQ; groove.seqTrack.intValue = 1; groove.synthEdit.value = com.thorbox.app.groove.GrooveController.SynthEdit.GRID }
            else -> Unit
        }
    }
    fun selectTop(v: TopView) {
        if (v == TopView.VIZ && topView.value != TopView.VIZ) viewBeforeViz = topView.value
        topView.value = v
        when (v) {
            TopView.SAMPLER -> if (deck.value == Deck.KEYS || deck.value == Deck.DRUMS) selectDeck(Deck.PADS)
            TopView.SYNTH -> if (deck.value == Deck.PADS || deck.value == Deck.DRUMS) selectDeck(Deck.KEYS)
            TopView.DRUM -> if (deck.value == Deck.PADS || deck.value == Deck.KEYS) selectDeck(Deck.DRUMS)
            else -> Unit
        }
    }

    // ---- Sample & pad ----
    private val _sample = MutableStateFlow<LoadedSample?>(null)
    val sample = _sample.asStateFlow()
    private val _loadingName = MutableStateFlow<String?>(null)
    val loadingName = _loadingName.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _peaks = MutableStateFlow<FloatArray?>(null)
    val peaks = _peaks.asStateFlow()
    private val _padHeld = MutableStateFlow(false)
    val padHeld = _padHeld.asStateFlow()

    // ---- Per-frame readouts: unboxed snapshot state, read in draw lambdas only ----
    val playhead = mutableFloatStateOf(-1f)
    val level = mutableFloatStateOf(0f)
    private val _stats = MutableStateFlow<NativeEngine.Stats?>(null)
    val stats = _stats.asStateFlow()

    // ---- Synth ----
    private val _patch = MutableStateFlow(SynthPatch())
    val patch = _patch.asStateFlow()
    val synthPage = mutableStateOf(SynthPage.OSC)
    val telemetry = SynthTelemetry()
    private val sentParams = FloatArray(SynthId.COUNT) { Float.NaN }   // NaN = never sent
    private val nextParams = FloatArray(SynthId.COUNT)

    // ---- Keyboard (KEYS deck) ----
    val keyOctave = mutableStateOf(0)
    val chordMode = mutableStateOf(false)
    val keyLock = mutableStateOf(true)
    private val noteRefs = IntArray(128)
    /** The key the keyboard and sequencer lock to: the sample's detected key (if following), else the chosen key. */
    fun lockKey(): MusicalKey {
        val s = groove.scale.value
        val detected = _sample.value?.key
        return if (s.followSample && detected != null) detected else MusicalKey(s.root, s.mode)
    }

    // ---- Dual-screen / input diagnostics ----
    val displays = MutableStateFlow<List<DisplayProbe>>(emptyList())
    val secondScreenMode = MutableStateFlow("—")
    val lastInput = MutableStateFlow("—")
    /** Developer diagnostics footer (displays, audio path, input). Off by default; long-press the level meter. */
    val showDiagnostics = mutableStateOf(false)
    /** MainActivity owns the folder-picker launcher (a Presentation has no ActivityResultRegistry). */
    val folderPickRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var nextRequestId = 1
    private val pendingNames = HashMap<Int, String>()
    private var latestRequest = 0
    private var engineRunning = false

    // ================= Engine lifecycle =================

    fun startEngine() {
        if (engineRunning) return
        engineRunning = NativeEngine.start()
        if (!engineRunning) _error.value = "Audio engine failed to start"
        sentParams.fill(Float.NaN)
        pushPatch(_patch.value)
        drums.pushAll()
        groove.pushAll()
        startTicker()
    }

    fun stopEngine() {
        stopTicker()
        padUp()
        releaseAllKeys()
        groove.stopAll()
        NativeEngine.stop()
        engineRunning = false
    }

    // ================= Sampler pads (16, Serato-style chops) =================
    // Wrappers kept for the gamepad and older call sites: they act on the SELECTED pad.

    fun padDown(velocity: Float) = padDown(sampler.selected.intValue, velocity)
    fun padUp() = padUp(sampler.selected.intValue)
    fun padDown(pad: Int, velocity: Float) { _padHeld.value = true; sampler.padDown(pad, velocity) }
    fun padUp(pad: Int) { _padHeld.value = false; sampler.padUp(pad) }

    private fun sel() = sampler.selected.intValue
    fun setPitch(semis: Int, cents: Int = sampler.pads.value[sel()].fine) = sampler.updatePad(sel()) { it.copy(pitch = semis, fine = cents) }
    fun nudgeSemis(delta: Int) = sampler.updatePad(sel()) { it.copy(pitch = it.pitch + delta) }
    fun resetPitch() = sampler.updatePad(sel()) { it.copy(pitch = 0, fine = 0) }
    fun setPlayMode(mode: PlayMode) = sampler.updatePad(sel()) { it.copy(mode = mode) }
    fun cyclePlayMode() = sampler.updatePad(sel()) { it.copy(mode = PlayMode.entries[(it.mode.ordinal + 1) % PlayMode.entries.size]) }

    /** The project's key (what KEY MATCH moves samples into). */
    fun projectKey(): MusicalKey = lockKey()

    /** "PROJECT ← SAMPLE": adopt the sample's BPM and key as the project's (no stretching needed). */
    fun setProjectFromSample() {
        val s = _sample.value ?: return
        s.bpm?.let { groove.setGroove(groove.groove.value.copy(bpm = it)) }
        s.key?.let { groove.setScale(groove.scale.value.copy(root = it.root, mode = it.mode, followSample = false)) }
        sampler.projectChanged()
    }

    /** Tempo octave fix: detection can't always tell 87 from 174. One tap fixes it. */
    fun scaleBpm(factor: Float) {
        val s = _sample.value ?: return
        val nb = (s.bpm ?: return) * factor
        if (nb in 40f..300f) _sample.value = s.copy(bpm = nb)
    }

    // ================= Synth =================

    fun knobValue(page: SynthPage, i: Int): Float = SynthParams.pages.getValue(page)[i].get(_patch.value)

    fun setKnob(page: SynthPage, i: Int, n: Float) {
        val knob = SynthParams.pages.getValue(page).getOrNull(i) ?: return
        val new = knob.set(_patch.value, n.coerceIn(0f, 1f))
        if (new != _patch.value) { _patch.value = new; pushPatch(new) }
    }

    // Stepped knobs (LFO wave/target, whole-cent detune) snap. Adding a small drag delta to the
    // snapped value would round straight back, so the knob would never move on slow drags or
    // light stick tilts. Each knob keeps its unsnapped position here instead.
    private val shadow = FloatArray(SynthPage.entries.size * 4) { Float.NaN }
    private val shadowSnapped = FloatArray(SynthPage.entries.size * 4) { Float.NaN }

    fun nudgeKnob(page: SynthPage, i: Int, delta: Float) {
        if (i !in 0..3) return
        val slot = page.ordinal * 4 + i
        val current = knobValue(page, i)
        // Reseed if the value changed elsewhere (reset, double-tap, preset).
        if (shadow[slot].isNaN() || current != shadowSnapped[slot]) shadow[slot] = current
        shadow[slot] = (shadow[slot] + delta).coerceIn(0f, 1f)
        setKnob(page, i, shadow[slot])
        shadowSnapped[slot] = knobValue(page, i)
    }

    fun resetPage(page: SynthPage) {
        val def = SynthPatch()
        var p = _patch.value
        SynthParams.pages.getValue(page).forEach { k -> p = k.set(p, k.get(def)) }
        _patch.value = p; pushPatch(p)
    }

    fun cyclePage(delta: Int) {
        val n = SynthPage.entries.size
        synthPage.value = SynthPage.entries[(synthPage.value.ordinal + delta + n) % n]
    }

    /** Sends only the engine params whose values changed (a knob turn ≈ 1 command). */
    private fun pushPatch(p: SynthPatch) {
        SynthParams.toEngine(p, nextParams)
        // Switching mono ↔ poly mid-note would strand voices the other mode can't release.
        if (!sentParams[SynthId.MONO].isNaN() && nextParams[SynthId.MONO] != sentParams[SynthId.MONO]) {
            NativeEngine.synthAllOff(); noteRefs.fill(0)
        }
        for (id in 0 until SynthId.COUNT) {
            val v = nextParams[id]
            if (v != sentParams[id] && NativeEngine.synthParam(id, v)) sentParams[id] = v
        }
    }

    // ================= Synth presets + dice (OP-XY style) =================

    /** Category filter for browsing and for DICE (null = any). */
    val synthCategory = mutableStateOf<com.thorbox.model.SynthCategory?>(null)
    private val patchUndo = ArrayDeque<SynthPatch>()
    val canUndoPatch = mutableStateOf(false)
    private var diceSeed = (SystemClock.uptimeMillis() and 0x7fffffff).toInt()

    private fun applyPatch(p: SynthPatch, undoable: Boolean = true) {
        if (p == _patch.value) return
        if (undoable) { patchUndo.addLast(_patch.value); while (patchUndo.size > 32) patchUndo.removeFirst(); canUndoPatch.value = true }
        _patch.value = p
        pushPatch(p)
    }
    fun loadSynthPreset(p: com.thorbox.model.SynthPreset) = applyPatch(p.patch.copy(name = p.name))

    /** Previous / next preset (within the category filter, wrapping). */
    fun stepSynthPreset(d: Int) {
        val cat = synthCategory.value
        val list = presets.allSynth().filter { cat == null || it.category == cat }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.name == _patch.value.name }
        val next = if (i < 0) (if (d > 0) 0 else list.lastIndex) else (i + d + list.size) % list.size
        loadSynthPreset(list[next])
    }
    /** DICE: a brand-new playable sound in the current category. */
    fun diceSynth() { diceSeed = diceSeed * 1103515245 + 12345 and 0x7fffffff; applyPatch(com.thorbox.model.PatchDice.dice(diceSeed, synthCategory.value)) }
    /** MUTATE: nudge the current sound. */
    fun mutateSynth(amount: Float = 0.25f) {
        diceSeed = diceSeed * 1103515245 + 12345 and 0x7fffffff
        val cur = _patch.value
        applyPatch(com.thorbox.model.PatchDice.mutate(cur, amount, diceSeed).copy(name = cur.name.removeSuffix("*") + "*"))
    }
    fun undoPatch() {
        val prev = patchUndo.removeLastOrNull() ?: return
        canUndoPatch.value = patchUndo.isNotEmpty()
        applyPatch(prev, undoable = false)
    }
    fun saveSynthPreset(name: String, category: com.thorbox.model.SynthCategory): String {
        val stored = presets.saveSynth(com.thorbox.model.SynthPreset(name, category, _patch.value))
        _patch.value = _patch.value.copy(name = stored)
        return stored
    }

    // ================= Keyboard =================

    fun notesForKey(index: Int): IntArray {
        val base = 48 + 12 * keyOctave.value
        if (!keyLock.value) {
            val n = base + index
            return if (chordMode.value) intArrayOf(n, n + 4, n + 7) else intArrayOf(n)
        }
        val key = lockKey()
        val root = base + key.root.ordinal
        fun degree(d: Int) = Scales.noteOf(key, root, d)
        return if (chordMode.value) intArrayOf(degree(index), degree(index + 2), degree(index + 4)) else intArrayOf(degree(index))
    }

    fun keyDown(notes: IntArray, velocity: Float) {
        for (n in notes) if (n in 0..127) {
            if (groove.liveDown(1, n, velocity)) continue                       // RPT held → note repeat
            if (noteRefs[n]++ == 0 || chordMode.value) NativeEngine.synthNoteOn(n, velocity)
        }
    }

    fun keyUp(notes: IntArray) {
        for (n in notes) if (n in 0..127) {
            if (groove.liveUp(1, n)) continue
            if (noteRefs[n] > 0 && --noteRefs[n] == 0) NativeEngine.synthNoteOff(n)
        }
    }

    fun releaseAllKeys() { noteRefs.fill(0); NativeEngine.synthAllOff() }

    fun shiftOctave(d: Int) { releaseAllKeys(); keyOctave.value = (keyOctave.value + d).coerceIn(-2, 3) }

    // ================= Loading =================

    fun requestFolder() { folderPickRequests.tryEmit(Unit) }
    fun load(item: LibraryItem) {
        if (item.sizeBytes > MAX_FILE_BYTES) { _error.value = "${item.name} is larger than 1 GB"; return }
        load(item.uri, item.name)
    }

    fun load(uri: Uri, name: String) {
        val id = nextRequestId++
        latestRequest = id
        pendingNames[id] = name
        _loadingName.value = name
        _error.value = null
        scope.launch {
            val fd = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.detachFd() }.getOrNull()
            }
            if (fd == null) {
                pendingNames.remove(id)
                if (id == latestRequest) { _loadingName.value = null; _error.value = "Can't open $name" }
                return@launch
            }
            NativeEngine.loadSample(fd, id)   // native side owns and closes the fd
        }
    }

    private fun onEngineEvent(e: NativeEngine.Event) {
        when (e) {
            is NativeEngine.Event.SampleLoaded -> {
                if (sampler.isStretchResult(e.requestId)) { sampler.onStretchLoaded(e.requestId, e.sampleId, e.frames); return }
                val name = pendingNames.remove(e.requestId) ?: "sample"
                if (e.requestId != latestRequest) { NativeEngine.unloadSample(e.sampleId); return }
                _sample.value = LoadedSample(
                    engineId = e.sampleId, name = name, frames = e.frames, sampleRate = e.sampleRate, channels = e.channels,
                    key = if (e.keyRoot in 0..11) MusicalKey(PitchClass.entries[e.keyRoot], if (e.keyMinor) ScaleMode.MINOR else ScaleMode.MAJOR) else null,
                    keyConfidence = e.keyConfidence, bpm = e.bpm.takeIf { it > 0f }, bpmConfidence = e.bpmConfidence,
                )
                _loadingName.value = null
                _peaks.value = null
                sampler.onOriginalLoaded(e.sampleId, e.frames)      // auto-chop onto all 16 pads
                val sid = e.sampleId
                scope.launch {
                    val pk = withContext(Dispatchers.Default) { NativeEngine.peaks(sid, 2048) }
                    if (_sample.value?.engineId == sid) _peaks.value = pk
                }
                // File names can be personal: log them in debug builds only.
                if (BuildConfig.DEBUG) Log.d(TAG, "loaded $name: ${e.frames} frames @${e.sampleRate}, key=${_sample.value?.key?.label()}, bpm=${e.bpm}")
            }
            is NativeEngine.Event.LoadFailed -> {
                if (sampler.isStretchResult(e.requestId)) { sampler.onStretchFailed(e.requestId); _error.value = "Couldn't re-render the sample"; return }
                val name = pendingNames.remove(e.requestId)
                if (e.requestId == latestRequest) { _loadingName.value = null; _error.value = "Couldn't decode ${name ?: "file"}" }
            }
        }
    }

    // ================= Frame ticker =================
    // One Choreographer callback drives polling, telemetry and stick→encoder integration.
    // Steady state allocates nothing: every buffer here is preallocated.

    private var ticking = false
    private var frameCount = 0L
    private var vizAccum = 0f
    private var lastStatsAt = 0L
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameTimeNanos

            while (true) onEngineEvent(NativeEngine.pollEvent() ?: break)
            playhead.floatValue = NativeEngine.playhead()
            level.floatValue = maxOf(NativeEngine.peakLevel(), level.floatValue * 0.85f)   // meter falloff

            val t = telemetry
            NativeEngine.synthTelemetry(t.v, t.notes)
            if (topView.value == TopView.SYNTH) t.scopeCount = NativeEngine.scope(t.scope)
            t.lfoHistory[t.lfoHead] = t.lfoValue; t.lfoHead = (t.lfoHead + 1) % t.lfoHistory.size
            t.timeSec += dt
            t.frame.longValue = frameTimeNanos

            groove.onFrame(dt)
            drums.onFrame(telemetry.timeSec)
            input.applySticks(dt)

            // Visual features feed the visualizer AND the glass aurora. The 2048-point FFT only runs when
            // something will show it: every frame while VIZ is up, at ~30 Hz for FULL glass, never with glass OFF.
            frameCount++
            val vizUp = topView.value == TopView.VIZ
            val gl = glass.value
            vizAccum += dt
            if (vizUp || (gl == com.thorbox.app.ui.GlassLevel.FULL && frameCount % 4 == 0L)) { viz.update(vizAccum); vizAccum = 0f }
            else if (gl == com.thorbox.app.ui.GlassLevel.OFF) vizAccum = 0f
            // Glass redraw clock: every 2nd frame on 120 Hz (≤60 Hz) for FULL, every 4th for SUBTLE.
            val every = if (gl == com.thorbox.app.ui.GlassLevel.FULL) 2L else 4L
            if (gl != com.thorbox.app.ui.GlassLevel.OFF && frameCount % every == 0L) t.glassFrame.longValue = frameTimeNanos
            com.thorbox.app.ui.GlassClock.time += dt
            com.thorbox.app.ui.GlassClock.bass = viz.bass; com.thorbox.app.ui.GlassClock.mid = viz.mid
            com.thorbox.app.ui.GlassClock.high = viz.high; com.thorbox.app.ui.GlassClock.beat = viz.beat
            val nowMs = SystemClock.uptimeMillis()
            if (lastTopTouch == 0L) lastTopTouch = nowMs
            val musicPlaying = groove.playing.value || level.floatValue > 0.05f
            if (!musicPlaying) lastTopTouch = maxOf(lastTopTouch, nowMs - 25_000)   // the idle timer only runs while music plays
            if (vizAuto.value && topView.value != TopView.VIZ && musicPlaying && nowMs - lastTopTouch > 30_000) {
                viewBeforeViz = topView.value; topView.value = TopView.VIZ
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastStatsAt > 500) { lastStatsAt = now; _stats.value = NativeEngine.stats() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true; lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopTicker() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    companion object {
        const val TAG = "Thorbox"
        const val MAX_FILE_BYTES = 1024L * 1024 * 1024
    }
}
