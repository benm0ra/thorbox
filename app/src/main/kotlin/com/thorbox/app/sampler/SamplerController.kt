// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.engine.NativeEngine
import com.thorbox.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/** One of the 16 pads: which slice it plays and how. */
/** [warp]: pitch changes keep the slice's length and timing (rendered per pad) instead of speeding it up like a turntable. */
data class PadSlot(val pitch: Int = 0, val fine: Int = 0, val mode: PlayMode = PlayMode.ONE_SHOT, val gainDb: Float = 0f, val warp: Boolean = false)

/**
 * How the whole sample is time-stretched (pitch kept): OFF, SYNC (follow the project tempo),
 * HALF / DOUBLE time, or FIT it to a whole number of bars (works without a detected BPM, e.g. a
 * field recording).
 */
enum class StretchMode(val label: String) { OFF("OFF"), SYNC("SYNC"), HALF("HALF-TIME"), DOUBLE("DOUBLE-TIME"), FIT("FIT") }

enum class ChopMode { AUTO, EQUAL, LAZY }

/**
 * Sampler flow:
 *   load → auto-chop to 16 pads → play. KEY MATCH / BPM SYNC re-render the sample with pitch and
 *   tempo kept independent (always from the original, so quality never degrades), and the
 *   slices follow automatically.
 * Slice positions are stored in ORIGINAL-sample frames and scaled to whichever buffer is playing.
 * Main-thread only.
 */
class SamplerController(private val app: AppState, val track: Int = 0) {
    private fun gp(local: Int) = NativeEngine.pad(track, local)   // global engine pad id
    private val preview get() = gp(NativeEngine.PREVIEW_PAD)
    /** This track's loaded sample (key/BPM analysis) and waveform peaks; the screens show the current track's. */
    var info: com.thorbox.app.LoadedSample? = null
    var peaks: FloatArray? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val pads = mutableStateOf(List(ChopLogic.PADS) { PadSlot() })
    val regions = mutableStateOf<List<Region>>(emptyList())     // ORIGINAL frames
    val selected = mutableIntStateOf(0)
    val chopMode = mutableStateOf(ChopMode.AUTO)
    val sensitivity = mutableFloatStateOf(0.6f)
    val equalCount = mutableIntStateOf(16)
    val keyMatch = mutableStateOf(false)
    val stretch = mutableStateOf(StretchMode.OFF)
    val fitBars = mutableIntStateOf(2)
    /** Back-compat for projects / callers that think in "BPM SYNC on/off". */
    val bpmSync get() = stretch.value == StretchMode.SYNC
    val lazyRunning = mutableStateOf(false)
    val rendering = mutableStateOf(false)                       // a stretch is in progress
    private val lazyMarkers = ArrayList<Long>()

    /** Where this track's sample came from (a library file, a field recording, the demo, or a project copy). */
    var sourceUri: android.net.Uri? = null

    /** Saved chop/pad state to apply when a project's sample finishes loading (instead of auto-chopping). */
    class Restore(val regions: List<Region>, val pads: List<PadSlot>, val chopMode: ChopMode, val sensitivity: Float,
                  val equalCount: Int, val keyMatch: Boolean, val stretch: StretchMode, val fitBars: Int)
    var pendingRestore: Restore? = null
    fun onRestoreFailed() { pendingRestore = null }

    /** Forget the sample (new / opened project): pads go silent, the engine buffer is released. */
    fun clearSample() {
        stopLazy(); releaseStretched()
        if (originalId != 0) NativeEngine.unloadSample(originalId)
        originalId = 0; originalFrames = 0; playingId = 0; playingFrames = 0
        info = null; peaks = null; sourceUri = null; pendingRestore = null
        regions.value = emptyList(); pads.value = List(ChopLogic.PADS) { PadSlot() }
        chopMode.value = ChopMode.AUTO; sensitivity.floatValue = 0.6f; equalCount.intValue = 16
        keyMatch.value = false; stretch.value = StretchMode.OFF
        assignAll()
    }

    var originalId = 0; private set
    var originalFrames = 0L; private set
    var playingId = 0; private set                               // what the pads point at now
    var playingFrames = 0L; private set
    private var pendingStretch = -1
    // Stretch request ids: per track, so a render always goes back to the track that asked (loads use small ids).
    private var nextReq = 1_000_000 + track * 100_000
    private val warpPending = HashMap<Int, Int>()                // request id → pad
    private val warpIds = IntArray(ChopLogic.PADS)               // per-pad rendered slice (0 = none)
    private val warpFrames = LongArray(ChopLogic.PADS)
    /** True for render results this track asked for (whole-sample stretch or a pad's WARP slice). */
    fun ownsRequest(id: Int) = id == pendingStretch || warpPending.containsKey(id)

    // ================= loading =================

    /** A freshly decoded sample: it becomes the source, gets auto-chopped and lands on all 16 pads. */
    fun onOriginalLoaded(sampleId: Int, frames: Long) {
        releaseStretched()
        val oldOriginal = originalId
        originalId = sampleId; originalFrames = frames
        playingId = sampleId; playingFrames = frames
        keyMatch.value = false; stretch.value = StretchMode.OFF
        val r = pendingRestore; pendingRestore = null
        if (r != null) {
            // Opening a project: put back its slices, pads and settings instead of auto-chopping.
            pads.value = List(ChopLogic.PADS) { r.pads.getOrElse(it) { PadSlot() } }
            chopMode.value = if (r.chopMode == ChopMode.LAZY) ChopMode.AUTO else r.chopMode
            sensitivity.floatValue = r.sensitivity; equalCount.intValue = r.equalCount
            regions.value = r.regions.filter { it.start in 0 until frames && it.end in 1..frames && it.end > it.start }
        }
        assignAll()
        if (oldOriginal != 0 && oldOriginal != sampleId) NativeEngine.unloadSample(oldOriginal)
        if (r == null || regions.value.isEmpty()) rechop()
        if (r != null && (r.keyMatch || r.stretch != StretchMode.OFF)) {
            keyMatch.value = r.keyMatch; stretch.value = r.stretch; fitBars.intValue = r.fitBars.coerceIn(1, 16); rerender()
        } else renderWarps()
    }

    fun isStretchResult(requestId: Int) = requestId >= 1_000_000

    fun onStretchLoaded(requestId: Int, sampleId: Int, frames: Long) {
        warpPending.remove(requestId)?.let { pad -> onWarpLoaded(pad, sampleId, frames); return }
        if (requestId != pendingStretch) { NativeEngine.unloadSample(sampleId); return }   // superseded
        pendingStretch = -1; rendering.value = false
        val old = playingId
        playingId = sampleId; playingFrames = frames
        assignAll()
        if (old != originalId && old != 0) NativeEngine.unloadSample(old)
        renderWarps()                                        // slices come from the new render
    }

    fun onStretchFailed(requestId: Int) {
        warpPending.remove(requestId)?.let { return }
        if (requestId == pendingStretch) { pendingStretch = -1; rendering.value = false }
    }

    // ================= per-pad WARP =================

    /** Turn WARP on/off for pad [i]: on, its pitch no longer changes its length. */
    fun toggleWarp(i: Int) { updatePad(i) { it.copy(warp = !it.warp) } }

    /** (Re)render every warped pad's slice at its pitch, from whatever the pads currently play. */
    private var warpJob: kotlinx.coroutines.Job? = null
    private fun renderWarps() {
        if (pads.value.none { it.warp }) return
        warpJob?.cancel()                                     // marker drags call this every frame: render once they settle
        warpJob = scope.launch { kotlinx.coroutines.delay(150); for (p in 0 until ChopLogic.PADS) renderWarp(p) }
    }

    private fun renderWarp(p: Int) {
        val slot = pads.value[p]
        warpPending.entries.removeAll { it.value == p }          // only the newest render per pad counts
        if (!slot.warp || (slot.pitch == 0 && slot.fine == 0) || playingId == 0) { dropWarp(p); assignPad(p); return }
        val reg = regions.value.getOrNull(p) ?: run { dropWarp(p); assignPad(p); return }
        val scale = if (originalFrames > 0) playingFrames.toDouble() / originalFrames else 1.0
        val a = (reg.start * scale).toLong(); val b = (reg.end * scale).toLong().coerceAtMost(playingFrames)
        if (b - a < 256) { dropWarp(p); assignPad(p); return }
        val id = nextReq++
        warpPending[id] = p
        NativeEngine.stretchSample(playingId, 1.0, slot.pitch + slot.fine / 100.0, id, a, b)
    }

    private fun onWarpLoaded(p: Int, sampleId: Int, frames: Long) {
        val slot = pads.value[p]
        if (!slot.warp) { NativeEngine.unloadSample(sampleId); return }
        val old = warpIds[p]
        warpIds[p] = sampleId; warpFrames[p] = frames
        assignPad(p)
        if (old != 0) NativeEngine.unloadSample(old)
    }

    private fun dropWarp(p: Int) {
        if (warpIds[p] != 0) NativeEngine.unloadSample(warpIds[p])
        warpIds[p] = 0; warpFrames[p] = 0
    }

    /** Point pad [p] at its WARP render (whole buffer, pitch baked in) or at the shared sample + its slice. */
    private fun assignPad(p: Int) {
        val slot = pads.value[p]
        if (slot.warp && warpIds[p] != 0) {
            NativeEngine.assignSample(gp(p), warpIds[p])
            NativeEngine.setPadRegion(gp(p), 0, warpFrames[p].coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            NativeEngine.setPitch(gp(p), 0, 0f)
        } else {
            NativeEngine.assignSample(gp(p), playingId)
            val reg = regions.value.getOrNull(p)
            val scale = if (originalFrames > 0) playingFrames.toDouble() / originalFrames else 1.0
            if (reg == null) NativeEngine.setPadRegion(gp(p), 0, 1)
            else NativeEngine.setPadRegion(gp(p), (reg.start * scale).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                (reg.end * scale).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            NativeEngine.setPitch(gp(p), slot.pitch, slot.fine.toFloat())
        }
    }

    private fun releaseStretched() {
        if (playingId != 0 && playingId != originalId) NativeEngine.unloadSample(playingId)
        pendingStretch = -1; rendering.value = false
        warpPending.clear()
        for (p in 0 until ChopLogic.PADS) dropWarp(p)
    }

    // ================= chopping =================

    fun setChopMode(m: ChopMode) {
        if (m == ChopMode.LAZY) startLazy() else stopLazy()
        chopMode.value = m
        if (m != ChopMode.LAZY) rechop()
    }

    fun setSensitivity(s: Float) { sensitivity.floatValue = s.coerceIn(0f, 1f); if (chopMode.value == ChopMode.AUTO) rechop() }
    fun setEqualCount(n: Int) { equalCount.intValue = n; chopMode.value = ChopMode.EQUAL; stopLazy(); rechop() }

    fun rechop() {
        if (originalId == 0) return
        when (chopMode.value) {
            ChopMode.EQUAL -> applyRegions(ChopLogic.equal(originalFrames, equalCount.intValue))
            ChopMode.AUTO -> {
                val id = originalId; val sens = sensitivity.floatValue; val frames = originalFrames
                scope.launch {
                    val starts = withContext(Dispatchers.Default) {
                        val out = LongArray(ChopLogic.PADS)
                        val n = NativeEngine.transients(id, sens, ChopLogic.PADS, out)
                        out.take(n)
                    }
                    if (id != originalId) return@launch
                    // Too few transients (a pad, a vocal): fall back to equal slices so all 16 pads do something.
                    applyRegions(if (starts.size >= 4) ChopLogic.fromStarts(starts, frames) else ChopLogic.equal(frames, 16))
                }
            }
            ChopMode.LAZY -> Unit
        }
    }

    fun moveMarker(index: Int, originalFrame: Long) = applyRegions(ChopLogic.moveStart(regions.value, index, originalFrame))

    private fun startLazy() {
        if (originalId == 0) return
        lazyMarkers.clear()
        applyRegions(listOf(Region(0, originalFrames)))
        lazyRunning.value = true
        NativeEngine.setPadRegion(preview, 0, -1)
        NativeEngine.setPlayMode(preview, NativeEngine.PlayMode.ONE_SHOT)
        NativeEngine.noteOn(preview, 0.9f)
    }

    private fun stopLazy() {
        if (!lazyRunning.value) return
        lazyRunning.value = false
        NativeEngine.noteOff(preview)
    }

    /** Plays the whole sample from the top (a quick listen; tap again to stop). */
    fun togglePreview(playing: Boolean) {
        NativeEngine.setPadRegion(preview, 0, -1)
        NativeEngine.setPlayMode(preview, NativeEngine.PlayMode.GATE)
        if (playing) NativeEngine.noteOn(preview, 0.9f) else NativeEngine.noteOff(preview)
    }

    private fun applyRegions(r: List<Region>) {
        if (r != regions.value) { normalized.value = false; preNormalize = null }   // new slices: NORMALIZE no longer applies
        regions.value = r
        pushRegions()
    }

    // ================= KEY MATCH / BPM SYNC =================

    fun keyShift(): Int {
        val s = info?.key ?: return 0
        val p = app.projectKey()
        return ChopLogic.keyMatchSemis(s, p)
    }
    fun tempoRatio(): Double {
        val b = info?.bpm ?: return 1.0
        return ChopLogic.syncRatio(b, app.groove.groove.value.bpm)
    }

    fun toggleKeyMatch() { keyMatch.value = !keyMatch.value; rerender() }
    fun toggleBpmSync() = setStretch(if (stretch.value == StretchMode.SYNC) StretchMode.OFF else StretchMode.SYNC)
    fun setStretch(m: StretchMode, bars: Int = fitBars.intValue) {
        stretch.value = m; fitBars.intValue = bars.coerceIn(1, 16); rerender()
    }
    /** Speed factor for the current stretch mode (>1 = faster / shorter), or 1 when it can't apply. */
    fun stretchTempo(): Double = when (stretch.value) {
        StretchMode.OFF -> 1.0
        StretchMode.SYNC -> tempoRatio()
        StretchMode.HALF -> 0.5
        StretchMode.DOUBLE -> 2.0
        StretchMode.FIT -> {
            val sr = info?.sampleRate ?: 0
            if (sr <= 0 || originalFrames <= 0) 1.0 else {
                val seconds = originalFrames.toDouble() / sr
                val target = fitBars.intValue * 4 * 60.0 / app.groove.groove.value.bpm
                (seconds / target).coerceIn(0.25, 4.0)
            }
        }
    }
    /** Short read-out for the STRETCH button: "SYNC 93→90", "FIT 2 BARS ×0.87"… */
    fun stretchLabel(): String = when (stretch.value) {
        StretchMode.OFF -> "OFF"
        StretchMode.SYNC -> info?.bpm?.let { "SYNC " + ChopLogic.syncLabel(it, app.groove.groove.value.bpm) } ?: "SYNC"
        StretchMode.HALF -> "HALF-TIME"
        StretchMode.DOUBLE -> "DOUBLE-TIME"
        StretchMode.FIT -> String.format(java.util.Locale.US, "FIT %d BAR%s ×%.2f", fitBars.intValue, if (fitBars.intValue == 1) "" else "S", stretchTempo())
    }

    /** Call when the project tempo or key changes while a match is on. */
    fun projectChanged() { if (keyMatch.value || stretch.value == StretchMode.SYNC || stretch.value == StretchMode.FIT) rerender() }

    private fun rerender() {
        if (originalId == 0) return
        val semis = if (keyMatch.value) keyShift() else 0
        val tempo = stretchTempo()
        if (semis == 0 && kotlin.math.abs(tempo - 1.0) < 1e-3) {   // back to the original
            val old = playingId
            pendingStretch = -1; rendering.value = false
            playingId = originalId; playingFrames = originalFrames
            assignAll()
            if (old != originalId && old != 0) NativeEngine.unloadSample(old)
            renderWarps()
            return
        }
        val id = nextReq++
        pendingStretch = id; rendering.value = true
        NativeEngine.stretchSample(originalId, tempo, semis.toDouble(), id)
    }

    // ================= pads =================

    private fun assignAll() {
        NativeEngine.assignSample(preview, playingId)
        for (p in 0 until ChopLogic.PADS) { assignPad(p); pushPadModes(p, pads.value[p]) }
    }

    // ================= NORMALIZE =================

    /** On while the pads carry NORMALIZE gains; tapping again puts the previous levels back. */
    val normalized = mutableStateOf(false)
    private var preNormalize: List<PadSlot>? = null

    /**
     * NORMALIZE: every chop to the same loudness (its loudest 50 ms), using per-pad gain (±24 dB),
     * never pushing a pad's peak above −1 dBFS. The common level is the median of what each pad can
     * reach, so one peaky pad can't drag the rest down. Toggle: a second tap restores the old levels.
     * Returns a one-line message for the status bar.
     */
    fun toggleNormalize(): String {
        if (normalized.value) {
            val prev = preNormalize
            if (prev != null) for (i in pads.value.indices) updatePad(i) { it.copy(gainDb = prev.getOrNull(i)?.gainDb ?: 0f) }
            normalized.value = false; preNormalize = null
            return "Pad levels back as they were"
        }
        val regs = regions.value.take(ChopLogic.PADS)
        if (originalId == 0 || regs.isEmpty()) return "Load a sample first"
        val arr = LongArray(regs.size * 2) { if (it % 2 == 0) regs[it / 2].start else regs[it / 2].end }
        val lv = FloatArray(regs.size * 2)
        if (!NativeEngine.regionLevels(originalId, arr, lv)) return "Couldn't measure the pads"
        val gains = NormalizeMath.gains(FloatArray(regs.size) { lv[2 * it] }, FloatArray(regs.size) { lv[2 * it + 1] })
        preNormalize = pads.value
        for (i in regs.indices) updatePad(i) { it.copy(gainDb = gains[i]) }
        normalized.value = true
        val moved = gains.count { kotlin.math.abs(it) >= 0.5f }
        return "Normalized: $moved of ${regs.size} pads levelled (peaks kept under −1 dBFS)"
    }

    /** Slices changed (re-chop, marker drag): every pad's region, and warped pads re-render their slice. */
    private fun pushRegions() { for (p in 0 until ChopLogic.PADS) assignPad(p); renderWarps() }

    private fun pushPadModes(i: Int, s: PadSlot) {
        NativeEngine.setPlayMode(gp(i), NativeEngine.PlayMode.entries[s.mode.ordinal])
        NativeEngine.setPadGain(gp(i), 10f.pow(s.gainDb / 20f))
    }

    fun updatePad(i: Int, f: (PadSlot) -> PadSlot) {
        val list = pads.value.toMutableList()
        val n = f(list[i]).let { it.copy(pitch = it.pitch.coerceIn(-24, 24), fine = it.fine.coerceIn(-100, 100), gainDb = it.gainDb.coerceIn(-24f, 24f)) }
        if (n == list[i]) return
        val old = list[i]
        list[i] = n; pads.value = list
        pushPadModes(i, n)
        if (n.warp != old.warp || n.pitch != old.pitch || n.fine != old.fine) {
            assignPad(i)                                      // immediate: plain pitch now, WARP render follows
            if (n.warp) renderWarp(i) else dropWarp(i)
        }
    }

    fun padDown(i: Int, velocity: Float) {
        selected.intValue = i
        if (lazyRunning.value) {
            // Lazy chop: this tap drops a marker where the preview is now.
            val frame = (app.playhead.floatValue.coerceIn(0f, 1f) * originalFrames).roundToInt().toLong()
            applyRegions(ChopLogic.lazyDrop(lazyMarkers, frame, originalFrames))
            return
        }
        if (!app.groove.liveDown(track, i, velocity)) NativeEngine.noteOn(gp(i), velocity)
    }

    fun padUp(i: Int) { if (!app.groove.liveUp(track, i)) NativeEngine.noteOff(gp(i)) }

    // ================= 16 LEVELS =================

    /** Pad [k] of 16 LEVELS plays pad [src]: by velocity, or re-tuned −8 … +7 st (turntable-style). */
    fun levelDown(src: Int, k: Int, mode: com.thorbox.app.levels.LevelMode, velocity: Float) {
        val slot = pads.value[src]
        when (mode) {
            com.thorbox.app.levels.LevelMode.TUNE -> {
                // each hit retriggers the (monophonic) pad, so repitching right before the hit is safe
                NativeEngine.setPitch(gp(src), (slot.pitch + com.thorbox.app.levels.Levels.tune(k)).coerceIn(-36, 36), slot.fine.toFloat())
                NativeEngine.noteOn(gp(src), velocity)
            }
            else -> NativeEngine.noteOn(gp(src), com.thorbox.app.levels.Levels.velocity(k))
        }
    }
    fun levelUp(src: Int) = NativeEngine.noteOff(gp(src))
    /** Leaving 16 LEVELS: the source pad back to its own pitch (and WARP slice). */
    fun restorePad(src: Int) = assignPad(src)
}
