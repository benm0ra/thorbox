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
data class PadSlot(val pitch: Int = 0, val fine: Int = 0, val mode: PlayMode = PlayMode.ONE_SHOT, val gainDb: Float = 0f)

enum class ChopMode { AUTO, EQUAL, LAZY }

/**
 * Serato-style sampler flow:
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
    val bpmSync = mutableStateOf(false)
    val lazyRunning = mutableStateOf(false)
    val rendering = mutableStateOf(false)                       // a stretch is in progress
    private val lazyMarkers = ArrayList<Long>()

    /** Where this track's sample came from (a library file, a field recording, the demo, or a project copy). */
    var sourceUri: android.net.Uri? = null

    /** Saved chop/pad state to apply when a project's sample finishes loading (instead of auto-chopping). */
    class Restore(val regions: List<Region>, val pads: List<PadSlot>, val chopMode: ChopMode, val sensitivity: Float,
                  val equalCount: Int, val keyMatch: Boolean, val bpmSync: Boolean)
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
        keyMatch.value = false; bpmSync.value = false
        assignAll()
    }

    var originalId = 0; private set
    var originalFrames = 0L; private set
    var playingId = 0; private set                               // what the pads point at now
    var playingFrames = 0L; private set
    private var pendingStretch = -1
    private var nextReq = 1_000_000                              // stretch request ids (loads use small ids)

    // ================= loading =================

    /** A freshly decoded sample: it becomes the source, gets auto-chopped and lands on all 16 pads. */
    fun onOriginalLoaded(sampleId: Int, frames: Long) {
        releaseStretched()
        val oldOriginal = originalId
        originalId = sampleId; originalFrames = frames
        playingId = sampleId; playingFrames = frames
        keyMatch.value = false; bpmSync.value = false
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
        if (r != null && (r.keyMatch || r.bpmSync)) { keyMatch.value = r.keyMatch; bpmSync.value = r.bpmSync; rerender() }
    }

    fun isStretchResult(requestId: Int) = requestId >= 1_000_000

    fun onStretchLoaded(requestId: Int, sampleId: Int, frames: Long) {
        if (requestId != pendingStretch) { NativeEngine.unloadSample(sampleId); return }   // superseded
        pendingStretch = -1; rendering.value = false
        val old = playingId
        playingId = sampleId; playingFrames = frames
        assignAll()
        if (old != originalId && old != 0) NativeEngine.unloadSample(old)
    }

    fun onStretchFailed(requestId: Int) { if (requestId == pendingStretch) { pendingStretch = -1; rendering.value = false } }

    private fun releaseStretched() {
        if (playingId != 0 && playingId != originalId) NativeEngine.unloadSample(playingId)
        pendingStretch = -1; rendering.value = false
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
    fun toggleBpmSync() { bpmSync.value = !bpmSync.value; rerender() }

    /** Call when the project tempo or key changes while a match is on. */
    fun projectChanged() { if (keyMatch.value || bpmSync.value) rerender() }

    private fun rerender() {
        if (originalId == 0) return
        val semis = if (keyMatch.value) keyShift() else 0
        val tempo = if (bpmSync.value) tempoRatio() else 1.0
        if (semis == 0 && kotlin.math.abs(tempo - 1.0) < 1e-3) {   // back to the original
            val old = playingId
            pendingStretch = -1; rendering.value = false
            playingId = originalId; playingFrames = originalFrames
            assignAll()
            if (old != originalId && old != 0) NativeEngine.unloadSample(old)
            return
        }
        val id = nextReq++
        pendingStretch = id; rendering.value = true
        NativeEngine.stretchSample(originalId, tempo, semis.toDouble(), id)
    }

    // ================= pads =================

    private fun assignAll() {
        for (p in 0 until ChopLogic.PADS) NativeEngine.assignSample(gp(p), playingId)
        NativeEngine.assignSample(preview, playingId)
        pushRegions()
        pads.value.forEachIndexed(::pushPad)
    }

    private fun pushRegions() {
        val scale = if (originalFrames > 0) playingFrames.toDouble() / originalFrames else 1.0
        val r = regions.value
        for (p in 0 until ChopLogic.PADS) {
            val reg = r.getOrNull(p)
            if (reg == null) NativeEngine.setPadRegion(gp(p), 0, 1)            // empty pad: silent 1-frame slice
            else NativeEngine.setPadRegion(gp(p), (reg.start * scale).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                (reg.end * scale).toLong().coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun pushPad(i: Int, s: PadSlot) {
        NativeEngine.setPitch(gp(i), s.pitch, s.fine.toFloat())
        NativeEngine.setPlayMode(gp(i), NativeEngine.PlayMode.entries[s.mode.ordinal])
        NativeEngine.setPadGain(gp(i), 10f.pow(s.gainDb / 20f))
    }

    fun updatePad(i: Int, f: (PadSlot) -> PadSlot) {
        val list = pads.value.toMutableList()
        val n = f(list[i]).let { it.copy(pitch = it.pitch.coerceIn(-24, 24), fine = it.fine.coerceIn(-100, 100), gainDb = it.gainDb.coerceIn(-24f, 6f)) }
        if (n == list[i]) return
        list[i] = n; pads.value = list; pushPad(i, n)
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
}
