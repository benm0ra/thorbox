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
    // ================= Tracks: 8, each a sampler, synth or drum machine =================
    val tracks = mutableStateOf(List(com.thorbox.model.TRACK_COUNT) { i ->
        when (i) {
            0 -> com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.SAMPLER, "SAMPLER")
            1 -> com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.SYNTH, "SYNTH")
            2 -> com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.DRUMS, "DRUMS")
            else -> com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.EMPTY, "")
        }
    })
    fun trackKind(t: Int) = tracks.value.getOrNull(t)?.kind ?: com.thorbox.model.TrackKind.EMPTY
    /** The track each editor shows: the last selected track of that kind. */
    val samplerTrack = androidx.compose.runtime.mutableIntStateOf(0)
    val synthTrack = androidx.compose.runtime.mutableIntStateOf(1)
    val drumTrack = androidx.compose.runtime.mutableIntStateOf(2)
    private val samplers = arrayOfNulls<com.thorbox.app.sampler.SamplerController>(com.thorbox.model.TRACK_COUNT)
    private val drumMachines = arrayOfNulls<com.thorbox.app.drums.DrumController>(com.thorbox.model.TRACK_COUNT)
    fun samplerFor(t: Int) = samplers[t] ?: com.thorbox.app.sampler.SamplerController(this, t).also { samplers[t] = it }
    fun drumsFor(t: Int) = drumMachines[t] ?: com.thorbox.app.drums.DrumController(this, t).also { drumMachines[t] = it }
    /** The sampler / drum machine the editors are showing (reads are snapshot-tracked). */
    val sampler get() = samplerFor(samplerTrack.intValue)
    val drums get() = drumsFor(drumTrack.intValue)
    val tilt = com.thorbox.app.input.TiltController(this, app)

    // ---- Theme (persisted) ----
    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    init { com.thorbox.app.ui.Tb.palette = com.thorbox.app.ui.Themes.byId(uiPrefs.getString("theme", null)) }
    /** SETTINGS sheet (top screen): look, meters, start-up defaults, controls, system / debug. */
    val showSettings = mutableStateOf(false)
    val settingsTab = mutableStateOf(0)
    /** The controller map pinned open from SETTINGS (normally it shows while SELECT is held). B closes it. */
    val showControllerMap = mutableStateOf(false)

    // ---- Settings (persisted) ----
    /** Master meter strip across the top screen. */
    val miniMeters = mutableStateOf(uiPrefs.getBoolean("miniMeters", false))
    fun setMiniMeters(on: Boolean) { miniMeters.value = on; uiPrefs.edit().putBoolean("miniMeters", on).apply() }
    init { com.thorbox.app.ui.Info.enabled.value = uiPrefs.getBoolean("hints", true) }
    fun setHints(on: Boolean) {
        com.thorbox.app.ui.Info.enabled.value = on; if (!on) com.thorbox.app.ui.Info.body.value = null
        uiPrefs.edit().putBoolean("hints", on).apply()
    }

    /** Start-up defaults: the tempo, swing, key and click a fresh launch begins with. */
    data class StartDefaults(val bpm: Float, val swing: Float, val root: com.thorbox.model.PitchClass, val mode: com.thorbox.model.ScaleMode, val follow: Boolean, val click: Boolean)
    fun startDefaults(): StartDefaults? {
        val bpm = uiPrefs.getFloat("defBpm", -1f).takeIf { it > 0f } ?: return null
        return StartDefaults(bpm, uiPrefs.getFloat("defSwing", 0.5f),
            runCatching { com.thorbox.model.PitchClass.valueOf(uiPrefs.getString("defRoot", "C")!!) }.getOrDefault(com.thorbox.model.PitchClass.C),
            runCatching { com.thorbox.model.ScaleMode.valueOf(uiPrefs.getString("defMode", "MINOR")!!) }.getOrDefault(com.thorbox.model.ScaleMode.MINOR),
            uiPrefs.getBoolean("defFollow", true), uiPrefs.getBoolean("defClick", false))
    }
    fun saveStartDefaults() {
        val g = groove.groove.value; val k = groove.scale.value
        uiPrefs.edit().putFloat("defBpm", g.bpm).putFloat("defSwing", g.swing).putString("defRoot", k.root.name)
            .putString("defMode", k.mode.name).putBoolean("defFollow", k.followSample).putBoolean("defClick", g.metronome).apply()
        lastInput.value = "Saved as start-up defaults"
    }
    fun clearStartDefaults() {
        uiPrefs.edit().remove("defBpm").remove("defSwing").remove("defRoot").remove("defMode").remove("defFollow").remove("defClick").apply()
        lastInput.value = "Start-up defaults cleared"
    }
    /** First-run welcome; long-press the ? chip to see it again. */
    val showWelcome = mutableStateOf(!uiPrefs.getBoolean("welcomed", false))
    fun finishWelcome() { showWelcome.value = false; uiPrefs.edit().putBoolean("welcomed", true).apply() }
    init {
        startDefaults()?.let { d ->
            groove.groove.value = groove.groove.value.copy(bpm = d.bpm, swing = d.swing, metronome = d.click)
            groove.scale.value = com.thorbox.app.groove.ScaleSetting(d.root, d.mode, d.follow)
        }
    }
    fun setTheme(p: com.thorbox.app.ui.Palette) {
        com.thorbox.app.ui.Tb.palette = p
        uiPrefs.edit().putString("theme", p.id).apply()
    }
    fun cycleTheme() {
        val all = com.thorbox.app.ui.Themes.all
        setTheme(all[(all.indexOf(com.thorbox.app.ui.Tb.palette) + 1) % all.size])
    }

    // ---- Look & visualizer ----
    val glass = mutableStateOf(com.thorbox.app.ui.GlassLevel.FULL)
    fun cycleGlass() { glass.value = com.thorbox.app.ui.GlassLevel.entries[(glass.value.ordinal + 1) % 3] }
    val viz = com.thorbox.app.viz.VizData()
    val vizMode = mutableStateOf(com.thorbox.app.ui.VizMode.SPECTRA)
    fun cycleViz() { vizMode.value = com.thorbox.app.ui.VizMode.entries[(vizMode.value.ordinal + 1) % com.thorbox.app.ui.VizMode.entries.size] }
    // The visualizer only opens when asked for (VISUAL in the rail); it never takes over the screen on its own.
    private var viewBeforeViz = TopView.SAMPLER

    fun setLibraryRoot(uri: Uri) { library.setRoot(uri); rescanLibrary() }
    fun rescanLibrary() { scope.launch { library.rescan() } }

    // ---- Navigation: deck tab drives the top screen's contextual view (selection → detail) ----
    val deck = mutableStateOf(Deck.PADS)
    val topView = mutableStateOf(TopView.SAMPLER)
    /** Leave the visualizer back to whatever was showing before it. */
    fun exitViz() { if (topView.value == TopView.VIZ) topView.value = viewBeforeViz }

    fun selectDeck(d: Deck) {
        if (d == Deck.STRUM) return   // not built yet
        if (deck.value == Deck.KEYS && d != Deck.KEYS) releaseAllKeys()
        if (deck.value == Deck.FX && d != Deck.FX) groove.stopAll()
        deck.value = d
        // The instrument decks pull their editor onto the top screen. SEQ and MIX stay put.
        when (d) {
            Deck.PADS -> { if (topView.value == TopView.SYNTH || topView.value == TopView.DRUM) topView.value = TopView.SAMPLER; groove.seqTrack.intValue = samplerTrack.intValue }
            Deck.KEYS -> { if (topView.value == TopView.SAMPLER || topView.value == TopView.DRUM) topView.value = TopView.SYNTH; groove.seqTrack.intValue = synthTrack.intValue }
            Deck.DRUMS -> { if (topView.value == TopView.SAMPLER || topView.value == TopView.SYNTH) topView.value = TopView.DRUM; groove.seqTrack.intValue = drumTrack.intValue }
            // The STEP deck is the synth's step sequencer (drums have their own grid on DRM).
            Deck.STEP -> { topView.value = TopView.SEQ; groove.seqTrack.intValue = synthTrack.intValue; groove.synthEdit.value = com.thorbox.app.groove.GrooveController.SynthEdit.GRID }
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
    // Per-track synth state. The editor works on _patch (the current synth track's patch).
    private val synthPatches = arrayOfNulls<SynthPatch>(com.thorbox.model.TRACK_COUNT)
    private val sentAll = Array(com.thorbox.model.TRACK_COUNT) { FloatArray(SynthId.COUNT) { Float.NaN } }   // NaN = never sent
    private val sentParams: FloatArray get() = sentAll[synthTrack.intValue]
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
    private val pendingTargets = HashMap<Int, Int>()                       // request id → sampler track
    private val pendingSources = HashMap<Int, Uri>()
    private val latestForTrack = IntArray(com.thorbox.model.TRACK_COUNT)   // newest load per track wins
    private var latestRequest = 0
    private var engineRunning = false

    // ================= Engine lifecycle =================

    fun startEngine() {
        if (engineRunning) return
        engineRunning = NativeEngine.start()
        if (!engineRunning) _error.value = "Audio engine failed to start"
        sentAll.forEach { it.fill(Float.NaN) }
        synthPatches[synthTrack.intValue] = _patch.value
        for (t in 0 until com.thorbox.model.TRACK_COUNT) {
            NativeEngine.setTrackType(t, trackKind(t).ordinal)
            when (trackKind(t)) {
                com.thorbox.model.TrackKind.SYNTH -> pushPatchTo(t, synthPatches[t] ?: SynthPatch())
                com.thorbox.model.TrackKind.DRUMS -> drumsFor(t).pushAll()
                else -> Unit
            }
        }
        tracks.value.forEachIndexed { t, info -> if (info.kind != com.thorbox.model.TrackKind.EMPTY) groove.nameChannel(t, info.name) }
        NativeEngine.setFocus(synthTrack.intValue); NativeEngine.setFocus(samplerTrack.intValue)
        groove.pushAll()
        tilt.onForeground(true)
        startTicker()
        if (!restoredSession) { restoredSession = true; projects.restoreLastSession() }   // pick up where you left off
        projects.startAutosave()
    }
    private var restoredSession = false

    fun stopEngine() {
        projects.stopAutosave()
        runCatching { projects.saveIfChanged() }        // going to the background: never lose the session
        tilt.onForeground(false)
        if (mic.recording.value) mic.stop()      // never keep the mic open in the background
        stopTicker()
        padUp()
        releaseAllKeys()
        groove.stopAll()
        NativeEngine.stop()
        engineRunning = false
    }

    // ================= Sampler pads (16 chops) =================
    // Wrappers kept for the gamepad and older call sites: they act on the SELECTED pad.

    fun padDown(velocity: Float) = padDown(sampler.selected.intValue, velocity)
    fun padUp() = padUp(sampler.selected.intValue)
    fun padDown(pad: Int, velocity: Float) { _padHeld.value = true; sampler.padDown(pad, velocity) }
    fun padUp(pad: Int) { _padHeld.value = false; sampler.padUp(pad) }

    private fun sel() = sampler.selected.intValue
    fun setPitch(semis: Int, cents: Int = sampler.pads.value[sel()].fine) { sampler.updatePad(sel()) { it.copy(pitch = semis, fine = cents) }; recordPadPitch() }
    fun nudgeSemis(delta: Int) { sampler.updatePad(sel()) { it.copy(pitch = it.pitch + delta) }; recordPadPitch() }
    /** REC + play: pad pitch changes write a lane (step values, like a sampler's pitch automation). */
    private fun recordPadPitch() {
        val t = samplerTrack.intValue
        groove.auto.touch(com.thorbox.app.groove.Automation.padPitchKey(t, sel()), groove.autoArmed(), groove.transport[0], groove.loopLen(t),
            sampler.pads.value[sel()].pitch.toFloat())
    }
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
        if (nb in 40f..300f) { _sample.value = s.copy(bpm = nb); sampler.info = _sample.value }
    }

    // ================= Synth =================

    fun knobValue(page: SynthPage, i: Int): Float = SynthParams.pages.getValue(page)[i].get(_patch.value)

    fun setKnob(page: SynthPage, i: Int, n: Float) {
        val knob = SynthParams.pages.getValue(page).getOrNull(i) ?: return
        val new = knob.set(_patch.value, n.coerceIn(0f, 1f))
        if (new != _patch.value) { _patch.value = new; pushPatch(new) }
        val t = synthTrack.intValue
        groove.auto.touch(com.thorbox.app.groove.Automation.synthKey(t, page.ordinal, i), groove.autoArmed(), groove.transport[0], groove.loopLen(t), knob.get(new))
    }

    /** Automation playback: move knob [i] of [page] on synth track [t] (the editor follows if it's showing that track). */
    fun applySynthKnob(t: Int, page: Int, i: Int, n: Float) {
        val knob = SynthParams.pages.getValue(SynthPage.entries.getOrNull(page) ?: return).getOrNull(i) ?: return
        if (t == synthTrack.intValue) {
            val new = knob.set(_patch.value, n.coerceIn(0f, 1f))
            if (new != _patch.value) { _patch.value = new; pushPatch(new) }
        } else {
            val p = synthPatches[t] ?: return
            pushPatchTo(t, knob.set(p, n.coerceIn(0f, 1f)))
        }
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
    private fun pushPatch(p: SynthPatch) = pushPatchTo(synthTrack.intValue, p)

    private fun pushPatchTo(t: Int, p: SynthPatch) {
        synthPatches[t] = p
        val sent = sentAll[t]
        SynthParams.toEngine(p, nextParams)
        // Switching mono ↔ poly mid-note would strand voices the other mode can't release.
        if (!sent[SynthId.MONO].isNaN() && nextParams[SynthId.MONO] != sent[SynthId.MONO]) {
            NativeEngine.synthAllOff(t); if (t == synthTrack.intValue) noteRefs.fill(0)
        }
        for (id in 0 until SynthId.COUNT) {
            val v = nextParams[id]
            if (v != sent[id] && NativeEngine.synthParam(t, id, v)) sent[id] = v
        }
    }

    // ================= Projects: what ProjectManager reads and writes =================

    /** The synth patch on track [t] (the live editor's copy for the current synth track). */
    fun synthPatchFor(t: Int): SynthPatch = if (t == synthTrack.intValue) _patch.value else synthPatches[t] ?: SynthPatch()
    fun setError(msg: String?) { _error.value = msg }
    fun postInfo(title: String, body: String) {
        com.thorbox.app.ui.Info.title.value = title; com.thorbox.app.ui.Info.body.value = body; com.thorbox.app.ui.Info.stamp.intValue++
    }

    /** Replace the whole session with [p] (opening a project, or a blank one). Samples load in the background. */
    internal fun applyProject(p: com.thorbox.model.ProjectFile, sampleFile: (String) -> java.io.File?) {
        padUp(); releaseAllKeys()
        for (t in 0 until com.thorbox.model.TRACK_COUNT) samplers[t]?.clearSample()
        _sample.value = null; _peaks.value = null
        val list = List(com.thorbox.model.TRACK_COUNT) { t -> p.tracks.getOrNull(t) ?: com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.EMPTY, "") }
        // Never leave the editors without a track of their kind.
        val fixed = list.toMutableList()
        for (kind in listOf(com.thorbox.model.TrackKind.SAMPLER, com.thorbox.model.TrackKind.SYNTH, com.thorbox.model.TrackKind.DRUMS)) if (fixed.none { it.kind == kind }) {
            val free = fixed.indexOfFirst { it.kind == com.thorbox.model.TrackKind.EMPTY }; if (free >= 0) fixed[free] = com.thorbox.model.TrackInfo(kind, kind.label)
        }
        tracks.value = fixed
        fixed.forEachIndexed { t, info -> NativeEngine.setTrackType(t, info.kind.ordinal) }

        val tp = p.transport; val sc = p.scale
        val groove0 = com.thorbox.app.groove.Groove(bpm = tp.bpm.coerceIn(30f, 300f), swing = tp.swing.coerceIn(0.5f, 0.75f), swing16 = tp.swing16,
            quantize = tp.quantize.coerceIn(0f, 1f), repeatRate = runCatching { com.thorbox.app.groove.RepeatRate.valueOf(tp.repeatRate) }.getOrDefault(com.thorbox.app.groove.RepeatRate.SIXTEENTH),
            metronome = tp.metronome)
        val scale0 = com.thorbox.app.groove.ScaleSetting(
            runCatching { com.thorbox.model.PitchClass.valueOf(sc.root) }.getOrDefault(com.thorbox.model.PitchClass.C),
            runCatching { com.thorbox.model.ScaleMode.valueOf(sc.mode) }.getOrDefault(com.thorbox.model.ScaleMode.MINOR), sc.followSample)
        keyLock.value = sc.keyLock
        groove.restore(groove0, scale0, p.clips,
            p.channels.map { com.thorbox.app.groove.Channel(it.name, it.volumeDb, it.pan, it.mute, it.solo, it.scSource, it.scAmount) },
            p.feels.map { com.thorbox.app.groove.TrackFeel(runCatching { com.thorbox.app.groove.GrooveTemplate.byName(it.template) }.getOrDefault(com.thorbox.app.groove.GrooveTemplate.STRAIGHT), it.amount) },
            p.masterDb, p.automation.mapValues { (_, v) -> v.map { com.thorbox.app.groove.Automation.Point(it.tick, it.value) } })

        val f = p.focus
        fun pick(want: Int, kind: com.thorbox.model.TrackKind) = if (fixed.getOrNull(want)?.kind == kind) want else fixed.indexOfFirst { it.kind == kind }
        synthTrack.intValue = pick(f.synthTrack, com.thorbox.model.TrackKind.SYNTH); samplerTrack.intValue = pick(f.samplerTrack, com.thorbox.model.TrackKind.SAMPLER); drumTrack.intValue = pick(f.drumTrack, com.thorbox.model.TrackKind.DRUMS)
        for (t in 0 until com.thorbox.model.TRACK_COUNT) {
            when (fixed[t].kind) {
                com.thorbox.model.TrackKind.SYNTH -> { val patch = p.synths[t] ?: SynthPatch(); sentAll[t].fill(Float.NaN); pushPatchTo(t, patch) }
                com.thorbox.model.TrackKind.DRUMS -> drumsFor(t).let { d -> p.kits[t]?.let(d::restoreKit); d.pushAll() }
                com.thorbox.model.TrackKind.SAMPLER -> {
                    val sd = p.samplers[t] ?: continue
                    val file = sd.file?.let(sampleFile) ?: continue
                    val sm = samplerFor(t)
                    sm.pendingRestore = com.thorbox.app.sampler.SamplerController.Restore(
                        regions = sd.regions.chunked(2).filter { it.size == 2 }.map { com.thorbox.app.sampler.Region(it[0], it[1]) },
                        pads = sd.pads.map { pd -> com.thorbox.app.sampler.PadSlot(pd.pitch, pd.fine,
                            runCatching { com.thorbox.model.PlayMode.valueOf(pd.mode) }.getOrDefault(com.thorbox.model.PlayMode.ONE_SHOT), pd.gainDb, pd.warp) },
                        chopMode = runCatching { com.thorbox.app.sampler.ChopMode.valueOf(sd.chopMode) }.getOrDefault(com.thorbox.app.sampler.ChopMode.AUTO),
                        sensitivity = sd.sensitivity, equalCount = sd.equalCount, keyMatch = sd.keyMatch,
                        stretch = runCatching { com.thorbox.app.sampler.StretchMode.valueOf(sd.stretch) }
                            .getOrDefault(if (sd.bpmSync) com.thorbox.app.sampler.StretchMode.SYNC else com.thorbox.app.sampler.StretchMode.OFF),
                        fitBars = sd.fitBars)
                    load(Uri.fromFile(file), sd.sampleName ?: file.nameWithoutExtension, t)
                }
                else -> Unit
            }
        }
        _patch.value = synthPatches[synthTrack.intValue] ?: SynthPatch()
        patchUndo.clear(); canUndoPatch.value = false
        fixed.forEachIndexed { t, info -> if (info.kind != com.thorbox.model.TrackKind.EMPTY) groove.nameChannel(t, info.name) }
        NativeEngine.setFocus(synthTrack.intValue); NativeEngine.setFocus(samplerTrack.intValue)
        groove.seqTrack.intValue = f.seqTrack.takeIf { fixed.getOrNull(it)?.kind != com.thorbox.model.TrackKind.EMPTY } ?: samplerTrack.intValue
    }

    val projects by lazy { com.thorbox.app.project.ProjectManager(this, app, scope) }

    // ================= Track management =================

    /** Show track [t] in its editor and make it the step-sequencer / record target. */
    fun selectTrack(t: Int) {
        when (trackKind(t)) {
            com.thorbox.model.TrackKind.SYNTH -> {
                if (t != synthTrack.intValue) {
                    releaseAllKeys()
                    synthPatches[synthTrack.intValue] = _patch.value
                    synthTrack.intValue = t
                    _patch.value = synthPatches[t] ?: SynthPatch().also { pushPatchTo(t, it) }
                    patchUndo.clear(); canUndoPatch.value = false
                }
                NativeEngine.setFocus(t); selectTop(TopView.SYNTH)
            }
            com.thorbox.model.TrackKind.SAMPLER -> {
                samplerTrack.intValue = t; NativeEngine.setFocus(t)
                _sample.value = samplerFor(t).info; _peaks.value = samplerFor(t).peaks     // show this track's sample
                selectTop(TopView.SAMPLER)
            }
            com.thorbox.model.TrackKind.DRUMS -> { drumTrack.intValue = t; selectTop(TopView.DRUM) }
            com.thorbox.model.TrackKind.EMPTY -> return
        }
        groove.seqTrack.intValue = t
    }

    /** Point the step sequencer at [t] (and make it current for its kind) without leaving the STEPS view. */
    fun selectTrackForSteps(t: Int) {
        val view = topView.value
        selectTrack(t)
        if (view == TopView.SEQ) topView.value = TopView.SEQ
    }

    /** Adds a track of [kind] in the first empty slot and selects it. Returns its index, or -1 if all 8 are used. */
    fun addTrack(kind: com.thorbox.model.TrackKind): Int {
        if (kind == com.thorbox.model.TrackKind.EMPTY) return -1
        val list = tracks.value
        val t = list.indexOfFirst { it.kind == com.thorbox.model.TrackKind.EMPTY }
        if (t < 0) return -1
        val n = list.count { it.kind == kind } + 1
        tracks.value = list.toMutableList().also { it[t] = com.thorbox.model.TrackInfo(kind, if (n == 1) kind.label else "${kind.label} $n") }
        NativeEngine.setTrackType(t, kind.ordinal)
        when (kind) {
            com.thorbox.model.TrackKind.SYNTH -> { sentAll[t].fill(Float.NaN); pushPatchTo(t, SynthPatch()) }
            com.thorbox.model.TrackKind.DRUMS -> drumsFor(t).pushAll()
            com.thorbox.model.TrackKind.SAMPLER -> samplerFor(t)
            else -> Unit
        }
        groove.resetTrack(t, tracks.value[t].name)
        selectTrack(t)
        return t
    }

    /**
     * Export every track's pattern as one multi-track .mid in Downloads (drag it into your DAW).
     * Synth tracks keep their notes; sampler pads are C1 upward (MPC/Drum Rack layout); drum
     * tracks go to channel 10 with General MIDI notes so a Drum Rack maps them instantly.
     */
    fun exportMidi(loops: Int = 4) {
        val tracksNow = tracks.value
        val clips = groove.clips.value
        val out = ArrayList<com.thorbox.model.MidiWriter.Track>(); val lengths = ArrayList<Int>()
        var synthCh = 0
        tracksNow.forEachIndexed { t, info ->
            if (info.kind == com.thorbox.model.TrackKind.EMPTY) return@forEachIndexed
            val c = clips[t]
            val voices = if (info.kind == com.thorbox.model.TrackKind.DRUMS) drumsFor(t).kit.value.voices else null
            val notes = c.notes.map { e ->
                val note = when (info.kind) {
                    com.thorbox.model.TrackKind.DRUMS -> voices?.getOrNull(e.note)?.let { com.thorbox.model.MidiWriter.gmDrum(it.model, it.decayMs) } ?: (36 + e.note)
                    com.thorbox.model.TrackKind.SAMPLER -> 36 + e.note
                    else -> e.note
                }
                com.thorbox.model.MidiWriter.Note(e.tick, e.lengthTicks, note, (e.velocity * 127f).toInt())
            }
            val ch = if (info.kind == com.thorbox.model.TrackKind.DRUMS) 9 else (synthCh++).let { if (it >= 9) it + 1 else it }
            out += com.thorbox.model.MidiWriter.Track(info.name, ch, notes); lengths += c.lengthTicks
        }
        val bytes = com.thorbox.model.MidiWriter.write(out, groove.groove.value.bpm, loops, lengths)
        scope.launch {
            val name = withContext(Dispatchers.IO) { LogExport.saveBytes(app, "ClamshellBeats", "mid", "audio/midi", bytes) }
            lastInput.value = if (name != null) "Saved Downloads/$name" else "MIDI export failed"
            com.thorbox.app.ui.Info.title.value = "MIDI"
            com.thorbox.app.ui.Info.body.value = if (name != null) "Saved to Downloads: $name (${out.size} tracks, $loops× loop). Drag it into your DAW." else "Couldn't save the MIDI file."
            com.thorbox.app.ui.Info.stamp.intValue++
        }
    }

    /** True if [t] can be removed: there must stay at least one track of each instrument kind. */
    fun canRemoveTrack(t: Int): Boolean {
        val k = trackKind(t)
        return k != com.thorbox.model.TrackKind.EMPTY && tracks.value.count { it.kind == k } > 1
    }
    fun removeTrack(t: Int) {
        if (!canRemoveTrack(t)) return
        val kind = trackKind(t)
        NativeEngine.setTrackType(t, 0)
        tracks.value = tracks.value.toMutableList().also { it[t] = com.thorbox.model.TrackInfo(com.thorbox.model.TrackKind.EMPTY, "") }
        groove.resetTrack(t, "T${t + 1}")
        // Any editor pointing at the removed track moves to another track of the same kind.
        val other = tracks.value.indexOfFirst { it.kind == kind }
        when (kind) {
            com.thorbox.model.TrackKind.SYNTH -> if (synthTrack.intValue == t) selectTrack(other)
            com.thorbox.model.TrackKind.SAMPLER -> if (samplerTrack.intValue == t) selectTrack(other)
            com.thorbox.model.TrackKind.DRUMS -> if (drumTrack.intValue == t) drumTrack.intValue = other
            else -> Unit
        }
        if (groove.seqTrack.intValue == t) groove.seqTrack.intValue = other
    }
    fun renameTrack(t: Int, name: String) {
        if (trackKind(t) == com.thorbox.model.TrackKind.EMPTY) return
        tracks.value = tracks.value.toMutableList().also { it[t] = it[t].copy(name = name.trim().uppercase().take(12).ifEmpty { it[t].name }) }
        groove.nameChannel(t, tracks.value[t].name)
    }

    // ================= Synth presets + dice ==================

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
            if (groove.liveDown(synthTrack.intValue, n, velocity)) continue    // RPT held → note repeat
            if (noteRefs[n]++ == 0 || chordMode.value) NativeEngine.synthNoteOn(synthTrack.intValue, n, velocity)
        }
    }

    fun keyUp(notes: IntArray) {
        for (n in notes) if (n in 0..127) {
            if (groove.liveUp(synthTrack.intValue, n)) continue
            if (noteRefs[n] > 0 && --noteRefs[n] == 0) NativeEngine.synthNoteOff(synthTrack.intValue, n)
        }
    }

    fun releaseAllKeys() { noteRefs.fill(0); NativeEngine.synthAllOff(synthTrack.intValue) }

    fun shiftOctave(d: Int) { releaseAllKeys(); keyOctave.value = (keyOctave.value + d).coerceIn(-2, 3) }

    // ================= Loading =================

    fun requestFolder() { folderPickRequests.tryEmit(Unit) }

    // ---- Mic / field recording ----
    val mic = MicRecorder(app)
    val micPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showMic = mutableStateOf(false)
    private fun hasMic() = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    /** MIC button: open the recorder (asks for the mic permission the first time). */
    fun openMic() { if (hasMic()) showMic.value = true else micPermissionRequests.tryEmit(Unit) }
    fun onMicPermission(granted: Boolean) { if (granted) showMic.value = true else _error.value = "Mic permission denied: allow it in Settings → Apps → Clamshell Beats" }
    fun toggleMicRecording() {
        if (mic.recording.value) { mic.stop(); return }
        if (!hasMic()) { micPermissionRequests.tryEmit(Unit); return }
        mic.start { msg -> _error.value = msg }
    }
    /** KEEP: save the take, then drop it onto the current sampler track, auto-chopped. */
    fun keepMicTake() {
        mic.keep { uri, name ->
            if (uri == null) { _error.value = name; return@keep }
            if (trackKind(samplerTrack.intValue) == com.thorbox.model.TrackKind.SAMPLER) {
                load(uri, name)
                showMic.value = false
                if (topView.value != TopView.SAMPLER) selectTop(TopView.SAMPLER)    // see it land on the pads
            } else _error.value = "Saved $name. Add a SAMPLE track (MIXER → + TRACK) to chop it."
            lastInput.value = "Saved Music/Clamshell Beats/Field Recordings/$name"
        }
    }
    /** REDO: throw the take away and record again straight away. */
    fun redoMicTake() { mic.discard(); toggleMicRecording() }
    /** Load the built-in demo break (synthesized once into the cache) onto the current sampler track. */
    fun loadDemo() {
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { com.thorbox.app.sampler.DemoBreak.write(app.cacheDir) }.getOrNull() }
            if (f == null) { _error.value = "Couldn't make the demo break"; return@launch }
            load(Uri.fromFile(f), "Demo break (90 BPM)")
        }
    }
    fun load(item: LibraryItem) {
        if (item.sizeBytes > MAX_FILE_BYTES) { _error.value = "${item.name} is larger than 1 GB"; return }
        load(item.uri, item.name)
    }

    /** Load [uri] onto sampler track [track] (default: the one you're on). Several tracks can load at once. */
    fun load(uri: Uri, name: String, track: Int = samplerTrack.intValue) {
        val id = nextRequestId++
        latestRequest = id
        pendingNames[id] = name
        pendingTargets[id] = track                 // the track that asked gets the sample, even if you switch
        pendingSources[id] = uri
        latestForTrack[track] = id
        _loadingName.value = name
        _error.value = null
        scope.launch {
            val fd = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.detachFd() }.getOrNull()
            }
            if (fd == null) {
                pendingNames.remove(id); pendingTargets.remove(id); pendingSources.remove(id)
                if (id == latestRequest) { _loadingName.value = null; _error.value = "Can't open $name" }
                samplerFor(track).onRestoreFailed()
                return@launch
            }
            NativeEngine.loadSample(fd, id)   // native side owns and closes the fd
        }
    }

    private fun onEngineEvent(e: NativeEngine.Event) {
        when (e) {
            is NativeEngine.Event.SampleLoaded -> {
                if (e.requestId >= 1_000_000) {           // a render (stretch / WARP): back to the track that asked
                    samplers.firstOrNull { it?.ownsRequest(e.requestId) == true }?.onStretchLoaded(e.requestId, e.sampleId, e.frames)
                        ?: NativeEngine.unloadSample(e.sampleId)
                    return
                }
                val name = pendingNames.remove(e.requestId) ?: "sample"
                val track = pendingTargets.remove(e.requestId) ?: samplerTrack.intValue
                val source = pendingSources.remove(e.requestId)
                if (e.requestId != latestForTrack[track]) { NativeEngine.unloadSample(e.sampleId); return }   // superseded on that track
                val target = samplerFor(track)
                target.sourceUri = source
                val info = LoadedSample(
                    engineId = e.sampleId, name = name, frames = e.frames, sampleRate = e.sampleRate, channels = e.channels,
                    key = if (e.keyRoot in 0..11) MusicalKey(PitchClass.entries[e.keyRoot], if (e.keyMinor) ScaleMode.MINOR else ScaleMode.MAJOR) else null,
                    keyConfidence = e.keyConfidence, bpm = e.bpm.takeIf { it > 0f }, bpmConfidence = e.bpmConfidence,
                )
                target.info = info; target.peaks = null
                if (target === sampler) { _sample.value = info; _peaks.value = null }
                if (e.requestId == latestRequest) _loadingName.value = null
                target.onOriginalLoaded(e.sampleId, e.frames)      // auto-chop onto all 16 pads
                val sid = e.sampleId
                scope.launch {
                    val pk = withContext(Dispatchers.Default) { NativeEngine.peaks(sid, 2048) }
                    if (target.info?.engineId == sid) target.peaks = pk
                    if (_sample.value?.engineId == sid) _peaks.value = pk
                }
                // File names can be personal: log them in debug builds only.
                if (BuildConfig.DEBUG) Log.d(TAG, "loaded $name: ${e.frames} frames @${e.sampleRate}, key=${_sample.value?.key?.label()}, bpm=${e.bpm}")
            }
            is NativeEngine.Event.LoadFailed -> {
                if (e.requestId >= 1_000_000) {
                    samplers.firstOrNull { it?.ownsRequest(e.requestId) == true }?.onStretchFailed(e.requestId)
                    _error.value = "Couldn't re-render the sample"; return
                }
                val name = pendingNames.remove(e.requestId)
                pendingSources.remove(e.requestId)
                pendingTargets.remove(e.requestId)?.let { samplerFor(it).onRestoreFailed() }
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
            tilt.onFrame()
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
