// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.app.UndoLog
import com.thorbox.engine.NativeEngine
import com.thorbox.model.Clip
import com.thorbox.model.ClipId
import com.thorbox.model.MusicalKey
import com.thorbox.model.NoteEvent
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import kotlin.math.roundToInt

/** Mixer strip state (UI side). Strip 0 = sampler, 1 = synth. */
/** One mixer strip. scSource = the track whose level ducks this one (-1 off), scAmount 0..1 ("PUMP"). */
data class Channel(val name: String, val volumeDb: Float = 0f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false,
                   val scSource: Int = -1, val scAmount: Float = 0f, val eq: TrackEq = TrackEq())

/** The keyboard/sequencer key. With [followSample] on, the loaded sample's detected key wins. */
data class ScaleSetting(val root: PitchClass = PitchClass.C, val mode: ScaleMode = ScaleMode.MINOR, val followSample: Boolean = true)

/**
 * Sequencer, groove, mixer and punch-in FX state, and the only code that sends those commands
 * to the engine. Main-thread only. Every edit pushes a fresh immutable pattern snapshot.
 */
class GrooveController(private val app: AppState) {
    companion object {
        /** Longest pattern: 64 bars of 1/16 steps. */
        const val MAX_STEPS = 64 * 16
        const val PATTERNS = 16
        const val BAR = 4 * com.thorbox.model.PPQN
    }
    /** True when [track]'s pattern has something to undo. */
    fun hasUndo(track: Int) = undo.getOrNull(track)?.isNotEmpty() == true
    // ---- transport / groove ----
    val groove = mutableStateOf(Groove())
    val playing = mutableStateOf(false)
    val recording = mutableStateOf(false)

    // ---- patterns (index = engine track) ----
    val clips = mutableStateOf(listOf(
        *Array(com.thorbox.model.TRACK_COUNT) { Clip(ClipId("t$it"), "T${it + 1}", color = it, lengthTicks = 16 * STEP_TICKS) }
    ))
    val seqTrack = mutableIntStateOf(0)
    val lane = mutableStateOf(Lane.VEL)
    val cursor = mutableIntStateOf(0)
    val gen = mutableStateOf(GenParams())
    /** Seed of the last roll (shown as #1234). Every roll draws fresh entropy, so sessions never replay. */
    val seed = mutableIntStateOf(0)
    /** What the last roll was (TRAP, HOOK, EUCLID 5/16 …) and when; the die flashes it. */
    val rollName = mutableStateOf("")
    val rollStamp = mutableIntStateOf(0)
    private val entropy = java.security.SecureRandom()
    private var lastBeat: PatternGen.BeatStyle? = null
    private val autoFeel = arrayOfNulls<GrooveTemplate>(com.thorbox.model.TRACK_COUNT)
    private var lastMelody: PatternGen.MelodyStyle? = null
    private fun freshSeed(): Int { seed.intValue = entropy.nextInt() and 0x7fffffff; return seed.intValue }

    /**
     * Roll until the result is actually different from what's there (a roll that changes nothing
     * feels broken), at most a few tries.
     */
    private fun rollInto(track: Int, gen: (PatternGen, Clip) -> List<NoteEvent>, after: (PatternGen) -> Unit = {}) {
        val cur = clips.value[track]
        val before = cur.notes.map { Triple(it.tick, it.note, it.ratchet) }.toSet()
        var g = PatternGen(freshSeed()); var notes = gen(g, cur)
        for (attempt in 0 until 4) {
            if (notes.map { Triple(it.tick, it.note, it.ratchet) }.toSet() != before) break
            g = PatternGen(freshSeed()); notes = gen(g, cur)
        }
        update(track, saveUndo = true) { it.copy(notes = notes) }
        after(g)
        rollName.value = g.style; rollStamp.intValue++
    }
    private val undo = List(com.thorbox.model.TRACK_COUNT) { ArrayDeque<Clip>() }
    val canUndo = mutableStateOf(false)

    // ---- scale ----
    val scale = mutableStateOf(ScaleSetting())

    // ---- mixer ----
    val channels = mutableStateOf(List(com.thorbox.model.TRACK_COUNT) { Channel("T${it + 1}") })
    val masterDb = mutableFloatStateOf(0f)
    val mixSelected = mutableIntStateOf(0)

    // ---- note repeat ----
    val repeatHeld = mutableStateOf(false)
    private val activeRepeats = HashSet<Int>()          // key = track * 128 + note

    // ---- punch-in FX ----
    val fxLatched = mutableIntStateOf(0)                // bitmask: effects that stay on when released
    val fxXyMode = mutableStateOf(false)                // FX deck shows one XY surface for the latched effects
    val fxHeld = mutableIntStateOf(0)                   // bitmask: effects currently touched / pressed

    // ---- live readouts (read in draw lambdas) ----
    val transport = DoubleArray(24)
    /** Where we are in the current pattern (ticks since it started: a pattern change restarts it at 0). */
    val loopTick: Double get() = (transport[0] - transport[16]).coerceAtLeast(0.0)
    private val meterBuf = FloatArray(NativeEngine.METERS)
    /** Displayed peaks with falloff: [2k, 2k+1] track k L/R, [16, 17] master. */
    val meters = FloatArray(2 * com.thorbox.model.TRACK_COUNT + 2)
    /** Sidechain gain per track (1 = no ducking), smoothed for display. */
    val scGain = FloatArray(com.thorbox.model.TRACK_COUNT) { 1f }

    // ================= DUCK: the mix's sidechain compressor =================
    val duck = mutableStateOf(com.thorbox.model.DuckSettings())
    private val duckBuf = FloatArray(4)
    /** Telemetry, refreshed every frame: deepest reduction this frame (dB), the key's level (dBFS), triggers so far. */
    var duckGrDb = 0f; private set
    var duckKeyDb = -90f; private set
    var duckTriggers = 0; private set
    /** The last 3 s of gain reduction (dB), one value per frame, oldest at [duckHead]: the BOUNCE curve. */
    val duckHistory = FloatArray(180)
    var duckHead = 0; private set

    fun setDuck(f: (com.thorbox.model.DuckSettings) -> com.thorbox.model.DuckSettings) {
        val old = duck.value
        val n = f(old).clamped()
        if (n == old) return
        duck.value = n
        pushDuck(n, old)
    }
    /** Send what changed (everything when [old] is null) to the engine. */
    fun pushDuck(d: com.thorbox.model.DuckSettings, old: com.thorbox.model.DuckSettings? = null) {
        val P = NativeEngine.DuckParam
        fun send(id: Int, v: Float, was: Float?) { if (was == null || was != v) NativeEngine.setDuck(id, v) }
        send(P.SOURCE_SLOT, d.sourceSlot.toFloat(), old?.sourceSlot?.toFloat())
        send(P.TARGETS, d.targets.toFloat(), old?.targets?.toFloat())
        send(P.THRESHOLD_DB, d.thresholdDb, old?.thresholdDb)
        send(P.RATIO, d.ratio, old?.ratio)
        send(P.ATTACK_MS, d.attackMs, old?.attackMs)
        send(P.RELEASE_MS, d.releaseMs, old?.releaseMs)
        send(P.MIX, if (d.enabled) d.mix else 0f, old?.let { if (it.enabled) it.mix else 0f })
        send(P.MAKEUP_DB, d.makeupDb, old?.makeupDb)
        send(P.SOURCE, if (d.source == com.thorbox.model.DuckSettings.BEAT) P.BEAT.toFloat() else d.source.toFloat(), old?.source?.let { if (it == com.thorbox.model.DuckSettings.BEAT) P.BEAT.toFloat() else it.toFloat() })
    }
    /** A project from before DUCK: its mixer PUMP settings (per-track source + amount) become one ducker. */
    fun duckFromPump(chans: List<Channel>): com.thorbox.model.DuckSettings? {
        val pumped = chans.withIndex().filter { it.value.scSource >= 0 && it.value.scAmount > 0f }
        if (pumped.isEmpty()) return null
        val src = pumped.first().value.scSource
        var mask = 0
        for ((i, c) in pumped) if (c.scSource == src) mask = mask or (1 shl i)
        return com.thorbox.model.DuckSettings(source = src, sourceSlot = -1, targets = mask, thresholdDb = -30f, ratio = 10f,
            attackMs = 1f, releaseMs = 150f, mix = pumped.maxOf { it.value.scAmount }, makeupDb = 0f, preset = "FROM PUMP")
    }
    private val recBuf = DoubleArray(4)
    private val openNotes = HashMap<Int, Pair<Int, Double>>()   // track*128+note → (event tick, abs start tick)

    fun key(): MusicalKey = app.lockKey()
    fun synthBase(): Int = 48 + key().root.ordinal

    // ================= engine sync =================

    fun pushAll() {
        pushGroove()
        NativeEngine.setPlaying(playing.value); NativeEngine.setRecording(recording.value)
        clips.value.indices.forEach(::sendClip)
        channels.value.indices.forEach(::pushChannel)
        NativeEngine.setChannel(NativeEngine.STRIP_MASTER, NativeEngine.Ch.VOLUME_DB, masterDb.floatValue)
    }

    private fun pushGroove() {
        val g = groove.value
        NativeEngine.setTempo(g.bpm)
        NativeEngine.setSwing(g.swing, if (g.swing16) 240 else 480)
        pushClick()
    }

    // Edits mark a track dirty. onFrame() sends at most ONE new pattern per track per frame, however
    // many edits a drag or a recording produced (each push allocates and copies a native pattern).
    private val dirty = BooleanArray(com.thorbox.model.TRACK_COUNT)
    private fun pushClip(track: Int) { dirty[track] = true }
    private fun flushClips() { for (t in dirty.indices) if (dirty[t]) { dirty[t] = false; sendClip(t) } }

    // ================= per-track groove (feel) =================
    val feels = mutableStateOf(List(com.thorbox.model.TRACK_COUNT) { TrackFeel() })
    fun setFeel(track: Int, f: TrackFeel) {
        val l = feels.value.toMutableList(); l[track] = f.copy(amount = f.amount.coerceIn(0f, 1f)); feels.value = l
        dirty[track] = true
    }

    private fun sendClip(track: Int) = sendClipData(track, clips.value[track], queue = false)

    /** Build [c] for the engine and send it now, or ([queue]) stage it for the next scene change. */
    private fun sendClipData(track: Int, c: Clip, queue: Boolean) {
        val feel = feels.value[track]
        val drums = app.trackKind(track) == com.thorbox.model.TrackKind.DRUMS
        // KEY LOCK: synth notes are snapped into the locked key on the way out (the stored notes stay as written)
        val snapKey = if (app.lockAll.value && app.trackKind(track) == com.thorbox.model.TrackKind.SYNTH) key() else null
        // The feel is applied here, on the way to the engine: stored notes stay on the grid.
        val ns = c.notes.take(4096).map { e ->
            val (t, v) = feel.template.apply(e.tick, e.velocity, c.lengthTicks, feel.amount, e.tick + track * 977, if (drums) e.note else -1)
            e.copy(tick = t, velocity = v, note = if (snapKey != null) Scales.snap(e.note, snapKey) else e.note)
        }.sortedBy { it.tick }
        val n = ns.size
        val lockIds = IntArray(2 * n) { -1 }
        val lockVals = FloatArray(2 * n)
        for (i in 0 until n) ns[i].locks.take(2).forEachIndexed { k, l -> lockIds[2 * i + k] = l.param; lockVals[2 * i + k] = l.value }
        val ticks = IntArray(n) { ns[it].tick }; val lens = IntArray(n) { ns[it].lengthTicks.coerceIn(1, c.lengthTicks) }
        val notes = IntArray(n) { ns[it].note }; val vels = FloatArray(n) { ns[it].velocity }; val probs = FloatArray(n) { ns[it].probability }
        val flags = IntArray(n) { ns[it].ratchet.coerceIn(1, 4) or (ns[it].condEvery.coerceIn(1, 8) shl 4) or (ns[it].condPhase.coerceIn(0, 7) shl 8) }
        if (queue) NativeEngine.queuePattern(track, c.lengthTicks, ticks, lens, notes, vels, probs, flags, lockIds, lockVals)
        else NativeEngine.setPattern(track, c.lengthTicks, ticks, lens, notes, vels, probs, flags, lockIds, lockVals)
    }

    /** Re-send every track's pattern (the key changed, KEY LOCK went on / off). */
    fun resendClips() { for (t in dirty.indices) dirty[t] = true }

    // ================= PATTERNS + SONG =================
    // 16 patterns per project, each a pattern for every track (an MPC "sequence"). [clips] is always the
    // current one (that's what every editor edits); the others wait in [bank]. Changing pattern while
    // playing is queued and happens exactly on the current pattern's loop boundary (the engine swaps
    // them sample-accurately and reports the tag back in transport[17]); stopped, it's immediate.
    // SONG plays a list of (pattern × repeats): each change is queued as soon as the previous lands.

    val patternIdx = androidx.compose.runtime.mutableIntStateOf(0)
    val queuedPattern = androidx.compose.runtime.mutableIntStateOf(-1)
    private val bank = arrayOfNulls<List<Clip>>(PATTERNS)
    // each pattern's own automation (the current one's is in [auto]); like the notes, it changes with the pattern
    private val autoBank = arrayOfNulls<Map<String, List<Automation.Point>>>(PATTERNS)
    val bankVersion = androidx.compose.runtime.mutableIntStateOf(0)
    val patternNames = mutableStateOf(List(PATTERNS) { "" })
    val song = mutableStateOf(listOf<com.thorbox.model.SongStep>())
    val songMode = mutableStateOf(false)
    val songLoop = mutableStateOf(true)
    /** The song step playing now (-1 = not in a song). */
    val songPos = androidx.compose.runtime.mutableIntStateOf(-1)
    private var lastSceneTag = 0
    private var songEndAt = -1.0           // loop off: stop when the last step's last repeat ends (loop ticks)

    private fun emptyClips() = List(com.thorbox.model.TRACK_COUNT) { t ->
        Clip(ClipId("t$t"), "T${t + 1}", color = t, lengthTicks = clips.value.getOrNull(t)?.lengthTicks ?: (16 * STEP_TICKS))
    }
    fun clipsOf(p: Int): List<Clip> = if (p == patternIdx.intValue) clips.value else bank.getOrNull(p) ?: emptyClips()
    fun hasNotes(p: Int) = clipsOf(p).any { it.notes.isNotEmpty() }
    /** Which tracks have notes in pattern [p] (for the pattern pads). */
    fun tracksIn(p: Int): Int { var m = 0; clipsOf(p).forEachIndexed { t, c -> if (c.notes.isNotEmpty()) m = m or (1 shl t) }; return m }
    /** A pattern's length: its longest track, in whole bars. */
    fun patternTicks(p: Int): Int {
        val longest = clipsOf(p).maxOfOrNull { it.lengthTicks } ?: BAR
        return ((longest + BAR - 1) / BAR).coerceAtLeast(1) * BAR
    }
    fun patternName(p: Int) = patternNames.value.getOrNull(p)?.takeIf { it.isNotBlank() } ?: "PAT ${p + 1}"

    /** Tag for the engine: the pattern, and (in a song) which step it is. */
    private fun tagOf(p: Int, step: Int = -1) = if (step < 0) p else (step + 1) * 32 + p

    /** Launch pattern [p]: queued for the end of this one while playing, immediate when stopped. Tap the queued one again to cancel. */
    fun selectPattern(p: Int) {
        if (p !in 0 until PATTERNS) return
        if (queuedPattern.intValue == p) { NativeEngine.commitScene(0, -1); queuedPattern.intValue = -1; return }
        if (p == patternIdx.intValue && queuedPattern.intValue < 0) return
        songMode.value = false; songPos.intValue = -1; songEndAt = -1.0
        if (!playing.value) { switchNow(p); return }
        stageScene(p)
        NativeEngine.commitScene(patternTicks(patternIdx.intValue), tagOf(p))
        queuedPattern.intValue = p
    }

    private fun stageScene(p: Int) { val cs = clipsOf(p); for (t in cs.indices) sendClipData(t, cs[t], queue = true) }

    /** Change pattern right now (stopped, or a project load): the editors and the engine both follow. */
    private fun switchNow(p: Int, step: Int = -1) {
        stageScene(p)
        NativeEngine.commitScene(0, tagOf(p, step))
        adopt(p)
    }

    /** Make [p] the current pattern (park the old one in the bank). */
    private fun adopt(p: Int) {
        if (p != patternIdx.intValue) {
            bank[patternIdx.intValue] = clips.value
            clips.value = bank[p] ?: emptyClips()
            autoBank[patternIdx.intValue] = auto.lanes.value
            auto.lanes.value = autoBank[p] ?: emptyMap(); autoBank[p] = null
            auto.resetPlayback(); releaseAutoFx()          // the new pattern's lanes apply from its first tick
            patternIdx.intValue = p
            undo.forEach { it.clear() }; canUndo.value = false
        }
        queuedPattern.intValue = -1
        bankVersion.intValue++
        resendClips()                       // the freshest notes (an edit made while it was queued) go out at once
    }

    /** Called every frame: did the engine change scene? Then follow it, and in a song queue the next step. */
    private fun followScene() {
        val tag = transport[17].toInt()
        if (tag != lastSceneTag) {
            lastSceneTag = tag
            val p = tag % 32; val step = tag / 32 - 1
            adopt(p)
            if (step >= 0 && songMode.value) { songPos.intValue = step; queueNextStep(step) }
        }
        // loop off: stop when the song's last step has played out (an export stops on the exact sample itself)
        if (!exporting && songEndAt > 0 && playing.value && loopTick >= songEndAt - 1) { songEndAt = -1.0; togglePlay() }
    }

    private fun queueNextStep(step: Int) {
        val s = song.value
        if (s.isEmpty()) return
        val cur = s.getOrNull(step) ?: return
        val playFor = patternTicks(cur.pattern) * cur.repeats.coerceIn(1, 16)
        var next = step + 1
        if (next >= s.size) {
            if (!songLoop.value) { songEndAt = playFor.toDouble(); return }
            next = 0
        }
        stageScene(s[next].pattern)
        NativeEngine.commitScene(playFor, tagOf(s[next].pattern, next))
        queuedPattern.intValue = s[next].pattern
    }

    /** SONG ▶: from step 1 (or [from]). */
    fun playSong(from: Int = 0) {
        val s = song.value
        if (s.isEmpty()) return
        if (playing.value) togglePlay()
        songMode.value = true; songEndAt = -1.0
        val i = from.coerceIn(0, s.size - 1)
        switchNow(s[i].pattern, i)
        lastSceneTag = -1                   // so the frame loop picks the tag up and queues the next step
        togglePlay()
    }
    fun setSongMode(on: Boolean) {
        if (on == songMode.value) return
        if (playing.value) togglePlay()
        songMode.value = on; songPos.intValue = -1; songEndAt = -1.0
        if (queuedPattern.intValue >= 0) { NativeEngine.commitScene(0, -1); queuedPattern.intValue = -1 }
    }

    // ---- editing the bank ----
    /** Pattern [p]'s automation lanes. */
    fun automationOf(p: Int): Map<String, List<Automation.Point>> = if (p == patternIdx.intValue) auto.lanes.value else autoBank.getOrNull(p) ?: emptyMap()
    fun copyPattern(from: Int, to: Int) {
        if (from == to || to !in 0 until PATTERNS) return
        val src = clipsOf(from)
        val srcAuto = automationOf(from)
        if (to == patternIdx.intValue) { clips.value = src; resendClips(); auto.lanes.value = srcAuto; auto.resetPlayback() } else { bank[to] = src; autoBank[to] = srcAuto }
        val names = patternNames.value.toMutableList(); names[to] = ""; patternNames.value = names
        bankVersion.intValue++
    }
    fun clearPattern(p: Int) {
        val e = clipsOf(p).map { it.copy(notes = emptyList()) }
        if (p == patternIdx.intValue) { for (t in clips.value.indices) update(t, saveUndo = true) { c -> c.copy(notes = emptyList()) }; auto.clearAll() }
        else { bank[p] = e; autoBank[p] = null }
        bankVersion.intValue++
    }
    fun firstEmptyPattern(): Int = (0 until PATTERNS).firstOrNull { it != patternIdx.intValue && !hasNotes(it) } ?: -1

    fun addSongStep(p: Int) { if (song.value.size < 64) song.value = song.value + com.thorbox.model.SongStep(p, 1) }
    /** Put pattern [p] into the song as part [at] (the ones after it move down). */
    fun insertSongStep(at: Int, p: Int) {
        if (song.value.size >= 64) return
        val l = song.value.toMutableList(); l.add(at.coerceIn(0, l.size), com.thorbox.model.SongStep(p, 1)); song.value = l
    }
    fun removeSongStep(i: Int) { song.value = song.value.filterIndexed { k, _ -> k != i } }
    fun setRepeats(i: Int, r: Int) { song.value = song.value.mapIndexed { k, s -> if (k == i) s.copy(repeats = r.coerceIn(1, 16)) else s } }
    fun moveSongStep(i: Int, d: Int) {
        val l = song.value.toMutableList(); val j = i + d
        if (i !in l.indices || j !in l.indices) return
        val x = l[i]; l[i] = l[j]; l[j] = x; song.value = l
    }
    /** Total song length in bars. */
    fun songBars(): Int = song.value.sumOf { patternTicks(it.pattern) / BAR * it.repeats }

    /** For saving: all 16 patterns (the current one from the editors). */
    fun patternSlots(): List<com.thorbox.model.PatternSlot> =
        List(PATTERNS) { p -> com.thorbox.model.PatternSlot(patternNames.value[p], if (hasNotes(p) || p == patternIdx.intValue) clipsOf(p) else emptyList(),
            // the current pattern's lanes are saved at the top level (ProjectFile.automation), as before
            if (p == patternIdx.intValue) emptyMap() else automationOf(p).mapValues { (_, v) -> v.map { com.thorbox.model.AutoPointDto(it.tick, it.value) } }) }

    /** Loading: put the bank back ([current]'s clips are already in [clips]). */
    fun restorePatterns(slots: List<com.thorbox.model.PatternSlot>, current: Int, songSteps: List<com.thorbox.model.SongStep>, mode: Boolean, loop: Boolean) {
        for (p in 0 until PATTERNS) bank[p] = slots.getOrNull(p)?.clips?.takeIf { it.isNotEmpty() }?.let { cs ->
            List(com.thorbox.model.TRACK_COUNT) { t -> cs.getOrNull(t) ?: emptyClips()[t] }
        }
        for (p in 0 until PATTERNS) autoBank[p] = slots.getOrNull(p)?.automation?.takeIf { it.isNotEmpty() }
            ?.mapValues { (_, v) -> v.map { Automation.Point(it.tick, it.value) } }
        patternIdx.intValue = current.coerceIn(0, PATTERNS - 1)
        bank[patternIdx.intValue] = null; autoBank[patternIdx.intValue] = null
        patternNames.value = List(PATTERNS) { slots.getOrNull(it)?.name ?: "" }
        song.value = songSteps.filter { it.pattern in 0 until PATTERNS }.map { it.copy(repeats = it.repeats.coerceIn(1, 16)) }
        songMode.value = mode; songLoop.value = loop; songPos.intValue = -1; queuedPattern.intValue = -1; songEndAt = -1.0
        NativeEngine.commitScene(0, tagOf(patternIdx.intValue)); lastSceneTag = tagOf(patternIdx.intValue)
        bankVersion.intValue++
    }

    private fun pushChannel(i: Int) {
        pushStrip(i)
        val v = channels.value[i].eq.values()
        for (id in v.indices) NativeEngine.setChannel(i, NativeEngine.Ch.EQ_BASE + id, v[id])
    }
    /** Fader, pan, mute, solo, pump: everything but the EQ (which only changes through [setEq]). */
    private fun pushStrip(i: Int) {
        val ch = channels.value[i]
        NativeEngine.setChannel(i, NativeEngine.Ch.VOLUME_DB, ch.volumeDb)
        NativeEngine.setChannel(i, NativeEngine.Ch.PAN, ch.pan)
        NativeEngine.setChannel(i, NativeEngine.Ch.MUTE, if (ch.mute) 1f else 0f)
        NativeEngine.setChannel(i, NativeEngine.Ch.SOLO, if (ch.solo) 1f else 0f)
        NativeEngine.setChannel(i, NativeEngine.Ch.SC_SOURCE, ch.scSource.toFloat())
        NativeEngine.setChannel(i, NativeEngine.Ch.SC_AMOUNT, ch.scAmount)
    }

    fun stopAll() {
        activeRepeats.forEach { NativeEngine.repeatOff(it / 128, it % 128) }; activeRepeats.clear()
        NativeEngine.fxAllOff(); fxHeld.intValue = 0; fxLatched.intValue = 0
    }

    // ================= transport =================

    private var startingSong = false
    fun togglePlay() {
        // SONG mode: ▶ plays the song from its first step
        if (!playing.value && songMode.value && song.value.isNotEmpty() && !startingSong) {
            startingSong = true; try { playSong(0) } finally { startingSong = false }; return
        }
        // starting to record: the count-in (SETTINGS → AUDIO) clicks the bars in first
        if (!playing.value) NativeEngine.setCountIn(app.audioSetup.countInTicks(recording.value))
        playing.value = !playing.value; NativeEngine.setPlaying(playing.value)
        if (!playing.value) {
            openNotes.clear()
            // stopping drops a pending pattern change (nothing is half-queued next time)
            if (queuedPattern.intValue >= 0) { NativeEngine.commitScene(0, -1); queuedPattern.intValue = -1 }
            songPos.intValue = -1; songEndAt = -1.0
        }
    }
    /** The click: on when CLK is on, unless SETTINGS → AUDIO says only while recording. */
    fun pushClick() = NativeEngine.setMetronome(groove.value.metronome && (!app.audioSetup.clickOnlyRec.value || recording.value))
    fun toggleRecord() {
        recording.value = !recording.value
        NativeEngine.setRecording(recording.value)
        pushClick()
        if (recording.value && !playing.value) togglePlay()          // REC starts the transport, like hardware
    }
    fun setGroove(g: Groove) {
        val old = groove.value; groove.value = g
        if (g != old) pushGroove()
        if (g.bpm != old.bpm) app.sampler.projectChanged()        // BPM SYNC follows the project tempo
    }
    fun nudgeBpm(d: Float) = setGroove(groove.value.copy(bpm = ((groove.value.bpm + d) * 10f).roundToInt() / 10f).let { it.copy(bpm = it.bpm.coerceIn(30f, 300f)) })
    fun setSwing(s: Float) = setGroove(groove.value.copy(swing = s.coerceIn(0.5f, 0.75f)))
    fun toggleSwingGrid() = setGroove(groove.value.copy(swing16 = !groove.value.swing16))
    fun setQuantize(q: Float) = setGroove(groove.value.copy(quantize = q.coerceIn(0f, 1f)))
    fun setRepeatRate(r: RepeatRate) = setGroove(groove.value.copy(repeatRate = r))
    fun toggleMetronome() = setGroove(groove.value.copy(metronome = !groove.value.metronome))

    /** Tap tempo: the average of the last few intervals (resets after a 2 s pause). */
    private val taps = ArrayDeque<Long>()
    fun tapTempo(nowMs: Long) {
        if (taps.isNotEmpty() && nowMs - taps.last() > 2000) taps.clear()
        taps.addLast(nowMs); while (taps.size > 5) taps.removeFirst()
        if (taps.size >= 3) {
            val avg = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
            setGroove(groove.value.copy(bpm = (60000f / avg * 10f).roundToInt() / 10f))
        }
    }

    // ================= pattern editing =================

    private fun update(track: Int, saveUndo: Boolean = false, f: (Clip) -> Clip) {
        val list = clips.value.toMutableList()
        val old = list[track]
        val new = f(old)
        if (new == old) return
        if (saveUndo) { undo[track].addLast(old); while (undo[track].size > 16) undo[track].removeFirst(); canUndo.value = true; app.undoLog.note(UndoLog.PATTERN, track) }
        list[track] = new
        clips.value = list
        pushClip(track)
    }

    /** Tracks whose notes are pads/slots (one row per sound) rather than pitches. */
    fun isPadTrack(track: Int) = app.trackKind(track) != com.thorbox.model.TrackKind.SYNTH
    /** The pad / drum slot that pad-track step edits apply to. */
    fun selectedPad(track: Int) = when (app.trackKind(track)) {
        com.thorbox.model.TrackKind.DRUMS -> app.drumsFor(track).selected.intValue
        else -> app.samplerFor(track).selected.intValue
    }
    /** The track the synth step sequencer / piano roll edits: the current synth track. */
    val synthT: Int get() = app.synthTrack.intValue
    /** The track the drum grid edits: the current drum track. */
    val drumT: Int get() = app.drumTrack.intValue

    fun defaultNote(track: Int): Int = if (isPadTrack(track)) selectedPad(track) else
        clips.value[track].notes.lastOrNull()?.note ?: synthBase()

    /** Pad tracks edit one pad at a time (the selected pad), like an MPC step view. Synth edits everything. */
    private inline fun scoped(track: Int, notes: List<NoteEvent>, f: (List<NoteEvent>) -> List<NoteEvent>): List<NoteEvent> {
        if (!isPadTrack(track)) return f(notes)
        val pad = selectedPad(track)
        val (mine, others) = notes.partition { it.note == pad }
        return (others + f(mine)).sortedBy { it.tick }
    }
    fun visibleNotes(track: Int): List<NoteEvent> {
        val c = clips.value[track]
        return if (isPadTrack(track)) c.notes.filter { it.note == selectedPad(track) } else c.notes
    }

    // ================= drum machine grid (TR style: one row per drum slot) =================

    /** Toggle one cell of the TR grid. Accent (velocity) cycles on a second tap? No: tap = on/off, kept simple. */
    fun drumToggle(step: Int, slot: Int) = update(drumT) { c ->
        val hit = c.notes.firstOrNull { it.note == slot && Steps.stepOf(it, c.lengthTicks) == step }
        if (hit != null) c.copy(notes = c.notes - hit)
        else c.copy(notes = (c.notes + NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = slot,
            velocity = if (step % 4 == 0) 0.95f else 0.8f)).sortedBy { it.tick })
    }
    /** Dice a whole beat: kick on the grid, backbeat snare/clap, busy hats, sprinkles of the rest. */
    fun diceBeat(track: Int = drumT) {
        rollInto(track, { g, c -> g.beat(Steps.count(c.lengthTicks), gen.value, avoid = lastBeat) }) { g ->
            lastBeat = g.beatStyle
            // A style comes with its groove (DRUNK beat → DRUNK feel, DUSTY → LAZY …). Only take over a straight
            // track or one the dice set last time, never a feel you picked yourself.
            val want = g.beatStyle?.feel
            val cur = feels.value[track].template
            if (want != null && (cur == GrooveTemplate.STRAIGHT || cur == autoFeel[track])) {
                if (cur != want) setFeel(track, feels.value[track].copy(template = want))
                autoFeel[track] = want
            }
        }
        val f = feels.value[track].template
        if (f != GrooveTemplate.STRAIGHT) rollName.value = "${rollName.value} · ${f.label}"
    }

    /**
     * The one "roll the notes" action every beat grid shares: a synth track gets a fresh melody in key,
     * a drum track a whole new beat, a sampler track new steps for the selected pad. Undo with ↶.
     */
    fun diceNotes(track: Int) {
        when (app.trackKind(track)) {
            com.thorbox.model.TrackKind.DRUMS -> diceBeat(track)
            com.thorbox.model.TrackKind.EMPTY -> Unit
            else -> dice(track)
        }
    }
    /** What the dice button on a grid says for this track: NOTES / BEAT / PAD n. */
    fun diceLabel(track: Int): String = when (app.trackKind(track)) {
        com.thorbox.model.TrackKind.SYNTH -> "NOTES"
        com.thorbox.model.TrackKind.DRUMS -> "BEAT"
        else -> "PAD ${selectedPad(track) + 1}"
    }

    /** Every hit on [step] of [track] goes (all pads). */
    fun clearStep(track: Int, step: Int) = update(track, saveUndo = true) { c -> c.copy(notes = c.notes.filterNot { Steps.stepOf(it, c.lengthTicks) == step }) }
    /** Replace a track's pattern (SEQ CHOPS): one undo step back to what was there. */
    fun setPattern(track: Int, lengthTicks: Int, notes: List<NoteEvent>) = update(track, saveUndo = true) { c ->
        c.copy(lengthTicks = lengthTicks, notes = notes.filter { it.tick in 0 until lengthTicks }.sortedBy { it.tick })
    }
    fun stepTap(track: Int, step: Int) = update(track) { c -> c.copy(notes = scoped(track, c.notes) { Steps.toggle(it, c.lengthTicks, step, defaultNote(track)) }) }

    /** Lane edit with v in 0..1. NOTE is scale-aware: two octaves of scale degrees from the root. */
    fun stepEdit(track: Int, step: Int, lane: Lane, v: Float) = update(track) { c ->
        if (lane != Lane.NOTE) c.copy(notes = scoped(track, c.notes) { Steps.edit(it, c.lengthTicks, step, lane, v) })
        else if (isPadTrack(track)) c
        else {
            val note = if (app.keyLock.value) {
                val k = key(); val size = Scales.steps(k.mode).size
                Scales.noteOf(k, synthBase(), (v * (size * 2)).roundToInt().coerceIn(0, size * 2))
            } else synthBase() + (v * 24).roundToInt()
            c.copy(notes = c.notes.map { if (Steps.stepOf(it, c.lengthTicks) == step) it.copy(note = note) else it })
        }
    }

    /** Lane value of a step as 0..1 (for drawing). */
    fun laneValue(track: Int, e: NoteEvent, lane: Lane): Float = when (lane) {
        Lane.VEL -> e.velocity
        Lane.PROB -> e.probability
        Lane.GATE -> Steps.gateNorm(e.lengthTicks)
        Lane.NOTE -> if (isPadTrack(track)) 0.5f else if (app.keyLock.value) {
            val k = key(); val size = Scales.steps(k.mode).size
            (Scales.degreeOf(e.note, k, synthBase()).toFloat() / (size * 2)).coerceIn(0f, 1f)
        } else ((e.note - synthBase()) / 24f).coerceIn(0f, 1f)
    }

    /** A track's pattern length, 1 step … 64 bars (shortening drops the notes past the end; UNDO brings them back). */
    fun setLength(track: Int, steps: Int) = update(track, saveUndo = true) { c ->
        val len = steps.coerceIn(1, MAX_STEPS) * STEP_TICKS
        c.copy(lengthTicks = len, notes = Steps.resize(c.notes, len))
    }

    // ---- which part of a long pattern the editors show ----
    /** While playing, editors turn the page with the playhead. Picking a bar by hand turns this off. */
    val follow = mutableStateOf(true)
    /**
     * First step of the page an editor showing [stepsPerPage] steps at a time draws for [track]. Call
     * it while drawing or handling a tap (it follows the playhead), not in composition.
     */
    fun pageStart(track: Int, stepsPerPage: Int): Int {
        val steps = Steps.count(clips.value.getOrNull(track)?.lengthTicks ?: return 0)
        if (steps <= stepsPerPage || stepsPerPage <= 0) return 0
        val pages = (steps + stepsPerPage - 1) / stepsPerPage
        val ps = if (stepRec.value && track == synthT) stepRecPos.intValue else if (follow.value) stepPosition(track) else -1
        val page = if (ps >= 0) ps / stepsPerPage else gridPage.intValue * 16 / stepsPerPage
        return page.coerceIn(0, pages - 1) * stepsPerPage
    }
    /** Show page [page] (of [stepsPerPage] steps) in every editor; stops following the playhead. */
    fun showPage(page: Int, stepsPerPage: Int) { gridPage.intValue = page * stepsPerPage / 16; follow.value = false }

    fun clear(track: Int) = update(track, saveUndo = true) { it.copy(notes = emptyList()) }

    /** A track was added or removed: empty pattern, fresh strip (named after the track), any sidechain on it dropped. */
    /** Opening a project (or starting a new one): replace the whole sequencer / mixer state and resend it. */
    fun restore(g: Groove, s: ScaleSetting, clipList: List<com.thorbox.model.Clip>, channelList: List<Channel>, feelList: List<TrackFeel>,
                master: Float, lanes: Map<String, List<Automation.Point>>, duckSaved: com.thorbox.model.DuckSettings? = null) {
        if (playing.value) togglePlay()
        val n = com.thorbox.model.TRACK_COUNT
        groove.value = g; scale.value = s
        clips.value = List(n) { t -> clipList.getOrNull(t) ?: com.thorbox.model.Clip(com.thorbox.model.ClipId("t$t"), "T${t + 1}", color = t, lengthTicks = 16 * STEP_TICKS) }
        var chans = List(n) { t -> channelList.getOrNull(t) ?: Channel("T${t + 1}") }
        // DUCK replaces the mixer's per-track PUMP: an older project's PUMP moves over (and stops ducking twice)
        val migrated = if (duckSaved == null) duckFromPump(chans) else null
        if (migrated != null || duckSaved != null) chans = chans.map { it.copy(scSource = -1, scAmount = 0f) }
        channels.value = chans
        duck.value = (duckSaved ?: migrated ?: com.thorbox.model.DuckSettings()).clamped()
        pushDuck(duck.value)
        feels.value = List(n) { t -> feelList.getOrNull(t) ?: TrackFeel() }
        masterDb.floatValue = master
        auto.clearAll(); auto.lanes.value = lanes
        undo.forEach { it.clear() }; canUndo.value = false
        eqUndo.forEach { it.clear() }
        pushAll()
    }

    fun resetTrack(t: Int, name: String) {
        undo[t].clear(); eqUndo[t].clear()
        update(t) { it.copy(notes = emptyList(), lengthTicks = 16 * STEP_TICKS) }
        auto.clearTrack(t)
        val list = channels.value.toMutableList()
        list[t] = Channel(name)
        for (k in list.indices) if (list[k].scSource == t) list[k] = list[k].copy(scSource = -1)
        channels.value = list
        // a reset / removed track leaves DUCK: no longer a target, and no longer the key
        setDuck { d -> d.withTarget(t, false).let { if (it.source == t) it.copy(source = -1) else it } }
        for (k in list.indices) pushChannel(k)
    }
    /** Rename a strip (mirrors the track name). */
    fun nameChannel(t: Int, name: String) { val l = channels.value.toMutableList(); l[t] = l[t].copy(name = name); channels.value = l }

    fun dice(track: Int) {
        if (isPadTrack(track)) {
            rollInto(track, { g, c -> scoped(track, c.notes) { g.drums(Steps.count(c.lengthTicks), gen.value, selectedPad(track)) } })
            return
        }
        rollInto(track, { g, c -> g.melody(Steps.count(c.lengthTicks), gen.value, key(), synthBase(), avoid = lastMelody) }) { lastMelody = it.melodyStyle }
    }

    fun mutate(track: Int) {
        val g = PatternGen(freshSeed())
        update(track, saveUndo = true) { c -> c.copy(notes = g.mutate(c.notes, c.lengthTicks, 0.3f, if (!isPadTrack(track)) key() else null, synthBase(), isPadTrack(track))) }
    }

    /** Snap the synth pattern to the current scale (after you change key). */
    fun snapToScale() = update(synthT, saveUndo = true) { c -> c.copy(notes = Steps.snapAll(c.notes, key())) }

    fun undo(track: Int) {
        val prev = undo[track].removeLastOrNull() ?: return
        val list = clips.value.toMutableList(); list[track] = prev; clips.value = list; pushClip(track)
        canUndo.value = undo.any { it.isNotEmpty() }
    }

    fun setScale(s: ScaleSetting) {
        val old = scale.value; scale.value = s; app.releaseAllKeys()
        if (old != s) {
            app.forEachSampler { it.projectChanged() }           // every sampler track that's key-matched follows
            if (app.lockAll.value) resendClips()                  // KEY LOCK: the synth notes re-snap to the new key
        }
    }

    // ================= synth: note grid (piano roll) =================
    // Track 1 only. Notes are identified by (tick, note): the grid never stacks two equal notes.

    enum class SynthEdit { GRID, LANES }
    val synthEdit = mutableStateOf(SynthEdit.GRID)
    val gridPage = mutableIntStateOf(0)          // 16-step page shown
    val gridOctave = mutableIntStateOf(0)        // shifts the visible rows by an octave

    /** Rows of the grid, top → bottom (MIDI notes): two octaves of the scale (or chromatic when free). */
    fun gridRows(): IntArray {
        val base = synthBase() + 12 * gridOctave.intValue
        if (!app.keyLock.value) return IntArray(25) { base + 24 - it }
        val k = key(); val size = Scales.steps(k.mode).size
        return IntArray(size * 2 + 1) { Scales.noteOf(k, base, size * 2 - it) }
    }

    fun gridToggle(step: Int, note: Int) = update(synthT) { c ->
        val t = step * STEP_TICKS
        val hit = c.notes.firstOrNull { it.note == note && t >= it.tick && t < it.tick + maxOf(it.lengthTicks, STEP_TICKS / 2) }
        if (hit != null) c.copy(notes = c.notes - hit)
        else c.copy(notes = (c.notes + NoteEvent(tick = t, lengthTicks = STEP_TICKS, note = note, velocity = 0.8f)).sortedBy { it.tick })
    }

    fun gridResize(tick: Int, note: Int, lengthTicks: Int) = update(synthT) { c ->
        c.copy(notes = c.notes.map { if (it.tick == tick && it.note == note) it.copy(lengthTicks = lengthTicks.coerceIn(STEP_TICKS / 4, c.lengthTicks)) else it })
    }

    fun gridMove(tick: Int, note: Int, newNote: Int) = update(synthT) { c ->
        if (c.notes.any { it.tick == tick && it.note == newNote }) c
        else c.copy(notes = c.notes.map { if (it.tick == tick && it.note == note) it.copy(note = newNote.coerceIn(0, 127)) else it })
    }

    // ================= synth: step entry (the 16 step keys) =================
    // Tap an empty step: it gets the notes you last played on the keys (a chord, if you played one).
    // Hold keys, then press a step: those notes go in. Hold a step, then press keys: each one adds or
    // removes that note. Hold a step and press a later one: the note lasts to there (press that one
    // again and it ties over into the next step). Hold a lit step: its notes light on the keys; let go
    // and it's copied: tap an empty step to paste (playing a key forgets the copy). Tap a lit step: cleared.
    // STEP REC: the keys fill the steps one after another, a chord on one step; ‹ › move without writing.

    val heldStep = mutableIntStateOf(-1)         // the step whose components the top screen shows
    private var heldEdited = false
    private var lengthStep = -1                  // a later step pressed while one is held (sets the length); its release is spent
    /** A copied step (ticks relative to its step), ready to paste on the next empty step you tap; null = none. */
    val stepCopy = mutableStateOf<List<NoteEvent>?>(null)
    val stepRec = mutableStateOf(false)
    val stepRecPos = mutableIntStateOf(0)
    private var stepRecWrote = false

    fun stepEvents(step: Int): List<NoteEvent> {
        val c = clips.value[synthT]
        return c.notes.filter { Steps.stepOf(it, c.lengthTicks) == step }
    }

    fun holdStep(step: Int) {
        val h = heldStep.intValue
        if (h >= 0 && h != step) { lengthStep = step; stretchHeld(step); return }
        heldStep.intValue = step; heldEdited = false
        val keys = app.heldKeyNotes()
        if (keys.isNotEmpty()) { writeStep(step, keys, app.lastVelocity); heldEdited = true }     // held keys → this step
        else if (!playing.value) stepEvents(step).forEach { app.previewSynthNote(it.note) }       // hear what's on it
    }

    /** Let go of a step: an empty one gets the last notes played (or the copy); a tapped lit one is cleared; a held lit one is copied. */
    fun releaseStep(step: Int, tapped: Boolean, cancelled: Boolean = false) {
        if (step == lengthStep) { lengthStep = -1; return }
        if (heldStep.intValue != step) return
        heldStep.intValue = -1
        if (heldEdited || cancelled) return
        val mine = stepEvents(step)
        val copy = stepCopy.value
        when {
            mine.isEmpty() && copy != null -> pasteStep(step, copy)
            mine.isEmpty() -> writeStep(step, app.lastPlayed.takeIf { it.isNotEmpty() } ?: intArrayOf(defaultNote(synthT)), app.lastVelocity)
            tapped -> update(synthT, saveUndo = true) { c -> c.copy(notes = c.notes - mine.toSet()) }
            else -> stepCopy.value = mine.map { it.copy(tick = it.tick - step * STEP_TICKS) }
        }
    }

    /** Replace [step]'s notes with [notes] (a chord if several), keeping the step's length / components if it had any. */
    fun writeStep(step: Int, notes: IntArray, velocity: Float) {
        if (notes.isEmpty()) return
        update(synthT, saveUndo = true) { c ->
            val mine = c.notes.filter { Steps.stepOf(it, c.lengthTicks) == step }
            val template = mine.firstOrNull() ?: NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = notes[0], velocity = velocity)
            val fresh = notes.distinct().map { template.copy(note = it.coerceIn(0, 127), velocity = velocity) }
            c.copy(notes = (c.notes - mine.toSet() + fresh).sortedBy { it.tick })
        }
    }

    private fun pasteStep(step: Int, copy: List<NoteEvent>) = update(synthT, saveUndo = true) { c ->
        val base = step * STEP_TICKS
        c.copy(notes = (c.notes + copy.map { it.copy(tick = (base + it.tick).coerceIn(0, c.lengthTicks - 1)) }).sortedBy { it.tick })
    }

    /** Hold a step, press a later one: the held notes last until the end of that step; again: they tie over into the next. */
    private fun stretchHeld(to: Int) {
        val from = heldStep.intValue
        heldEdited = true
        if (to <= from) return
        val full = (to - from + 1) * STEP_TICKS
        update(synthT, saveUndo = true) { c ->
            val mine = c.notes.filter { Steps.stepOf(it, c.lengthTicks) == from }
            if (mine.isEmpty()) return@update c
            val len = (if (mine[0].lengthTicks == full) full + STEP_TICKS / 2 else full).coerceAtMost(c.lengthTicks)
            c.copy(notes = c.notes.map { if (it in mine) it.copy(lengthTicks = len) else it })
        }
    }

    /** A key pressed while a step is held: adds that note to the step, or takes it off if it's there. True = added. */
    fun keyIntoHeldStep(note: Int, velocity: Float): Boolean {
        val step = heldStep.intValue
        if (step < 0) return false
        heldEdited = true
        var added = false
        update(synthT, saveUndo = true) { c ->
            val mine = c.notes.filter { Steps.stepOf(it, c.lengthTicks) == step }
            val there = mine.firstOrNull { it.note == note }
            if (there != null) c.copy(notes = c.notes - there)
            else {
                added = true
                val template = mine.firstOrNull() ?: NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = note, velocity = velocity)
                c.copy(notes = (c.notes + template.copy(note = note, velocity = velocity)).sortedBy { it.tick })
            }
        }
        return added
    }

    /** Called by the keys: playing a note forgets a pending step copy, and in STEP REC writes the chord held now. */
    fun onKeysPlayed(held: IntArray, velocity: Float) {
        stepCopy.value = null
        if (!stepRec.value || held.isEmpty()) return
        writeStep(stepRecPos.intValue.coerceIn(0, Steps.count(clips.value[synthT].lengthTicks) - 1), held, velocity)
        stepRecWrote = true
    }
    /** All keys up in STEP REC: on to the next step. */
    fun onKeysReleased() { if (stepRec.value && stepRecWrote) { stepRecWrote = false; stepRecMove(+1) } }
    fun toggleStepRec() { stepRec.value = !stepRec.value; stepRecWrote = false; if (stepRec.value) stepRecPos.intValue = pageStart(synthT, 16) }
    fun stepRecMove(d: Int) {
        val steps = Steps.count(clips.value[synthT].lengthTicks)
        stepRecPos.intValue = Math.floorMod(stepRecPos.intValue + d, steps)
        if (d < 0 && !playing.value) stepEvents(stepRecPos.intValue).forEach { app.previewSynthNote(it.note) }
    }

    fun editHeld(f: (NoteEvent) -> NoteEvent) {
        val step = heldStep.intValue
        if (step < 0) return
        heldEdited = true
        update(synthT) { c -> c.copy(notes = c.notes.map { if (Steps.stepOf(it, c.lengthTicks) == step) f(it) else it }) }
    }

    /** Trig conditions: "a:b" = play on loop a of every b. */
    val conditions = listOf(1 to 0, 2 to 0, 2 to 1, 4 to 0, 4 to 1, 4 to 2, 4 to 3, 3 to 0)
    fun conditionLabel(every: Int, phase: Int) = if (every <= 1) "ALWAYS" else "${phase + 1}:$every"

    fun setLock(param: Int, value: Float) = editHeld { e ->
        val others = e.locks.filterNot { it.param == param }
        // At most 2 locks per step: the newest one replaces the oldest.
        e.copy(locks = (others + com.thorbox.model.PLock(param, value)).takeLast(2))
    }
    fun clearLock(param: Int) = editHeld { e -> e.copy(locks = e.locks.filterNot { it.param == param }) }

    // ================= live recording =================

    private fun onRecord(type: Int, track: Int, note: Int, vel: Float, tick: Double) {
        val c = clips.value.getOrNull(track) ?: return
        val k = track * 128 + note
        if (type == 1) {
            val g = groove.value
            val q = Quantize.apply((tick - transport[16]).coerceAtLeast(0.0), g.quantizeGrid, g.quantize, c.lengthTicks)   // in the pattern's own time
            update(track) { cl ->
                val kept = cl.notes.filterNot { it.note == note && kotlin.math.abs(it.tick - q) < 60 }   // replace, don't stack
                cl.copy(notes = (kept + NoteEvent(tick = q, lengthTicks = STEP_TICKS / 2, note = note, velocity = vel)).sortedBy { it.tick })
            }
            openNotes[k] = q to tick
        } else {
            val (q, start) = openNotes.remove(k) ?: return
            val len = (tick - start).roundToInt().coerceIn(30, c.lengthTicks)
            if (!isPadTrack(track)) update(track) { cl -> cl.copy(notes = cl.notes.map { if (it.note == note && it.tick == q) it.copy(lengthTicks = len) else it }) }
        }
    }

    // ================= note repeat =================

    /** Returns true if the hit became a repeat (the caller must not also trigger it). */
    fun liveDown(track: Int, note: Int, vel: Float): Boolean {
        if (!repeatHeld.value) return false
        repeatUsed = true
        activeRepeats += track * 128 + note
        NativeEngine.repeatOn(track, note, groove.value.repeatRate.ticks, vel)
        return true
    }

    fun liveUp(track: Int, note: Int): Boolean {
        if (!activeRepeats.remove(track * 128 + note)) return false
        NativeEngine.repeatOff(track, note)
        return true
    }

    /** RPT held (touch or L1) — momentary. */
    fun setRepeatHeld(held: Boolean) { repeatMomentary = held; refreshRepeat() }
    /** RPT tapped: note repeat stays on until tapped again (one hand free for the pads on a handheld). */
    val repeatLatched = mutableStateOf(false)
    fun setRepeatLatched(on: Boolean) { repeatLatched.value = on; refreshRepeat() }
    private var repeatMomentary = false
    /** Set when a pad / key repeated while RPT was held: L1 then doesn't also switch screens on release. */
    var repeatUsed = false
    private fun refreshRepeat() {
        val on = repeatMomentary || repeatLatched.value
        repeatHeld.value = on
        if (!on) { activeRepeats.forEach { NativeEngine.repeatOff(it / 128, it % 128) }; activeRepeats.clear() }
    }

    // ================= punch-in FX =================

    // XY per effect: where the finger/trigger put it (base), plus the tilt offset when TILT is on.
    val fxX = FloatArray(NativeEngine.Fx.COUNT) { 0.5f }
    val fxY = FloatArray(NativeEngine.Fx.COUNT) { 0.5f }
    private var tiltX = 0f; private var tiltY = 0f
    fun fxEffX(id: Int): Float {
        val x = (fxX[id] + tiltX).coerceIn(0f, 1f)
        if (id != NativeEngine.Fx.RESONATOR) return x
        // The resonator rings at a note: snap it into the song's key, so it's always in tune.
        val note = Scales.snap(resonatorNote(x).roundToInt(), key())
        return ((note - 36f) / 48f).coerceIn(0f, 1f)
    }
    fun fxEffY(id: Int) = (fxY[id] + tiltY).coerceIn(0f, 1f)

    fun fxDown(id: Int, x: Float, y: Float) {
        fxX[id] = x; fxY[id] = y
        auto.touch(Automation.fxKey(id), autoArmed(), loopTick, fxLoopLen(), 1f + x)
        auto.touch(Automation.fxYKey(id), autoArmed(), loopTick, fxLoopLen(), y)
        fxHeld.intValue = fxHeld.intValue or (1 shl id); NativeEngine.fxOn(id, fxEffX(id), fxEffY(id))
    }
    fun fxMove(id: Int, x: Float, y: Float) {
        fxX[id] = x; fxY[id] = y; NativeEngine.fxMove(id, fxEffX(id), fxEffY(id))
        auto.touch(Automation.fxKey(id), autoArmed(), loopTick, fxLoopLen(), 1f + x)
        auto.touch(Automation.fxYKey(id), autoArmed(), loopTick, fxLoopLen(), y)
    }
    /** Tilt offset (±0.5) applied to every held effect. */
    fun setTilt(dx: Float, dy: Float) {
        tiltX = dx; tiltY = dy
        val held = fxHeld.intValue
        for (id in 0 until NativeEngine.Fx.COUNT) if (held and (1 shl id) != 0) NativeEngine.fxMove(id, fxEffX(id), fxEffY(id))
    }
    fun fxUp(id: Int) {
        fxHeld.intValue = fxHeld.intValue and (1 shl id).inv()
        auto.touch(Automation.fxKey(id), autoArmed(), loopTick, fxLoopLen(), 0f)
        if (fxLatched.intValue and (1 shl id) == 0) NativeEngine.fxOff(id)
    }
    /** Let go of every held (not latched) effect, e.g. when you leave the FX deck. */
    fun releaseHeldFx() { val h = fxHeld.intValue; for (id in 0 until NativeEngine.Fx.COUNT) if (h and (1 shl id) != 0) fxUp(id) }
    /** The XY surface: one finger steers every latched effect at once (recorded like a normal move). */
    fun fxMoveLatched(x: Float, y: Float) {
        val m = fxLatched.intValue
        for (id in 0 until NativeEngine.Fx.COUNT) if (m and (1 shl id) != 0) fxMove(id, x, y)
    }
    /** LATCH: on = the effect engages (at its last XY) and stays on hands-free; off = it releases (unless held). */
    fun toggleLatch(id: Int, x: Float = fxX[id], y: Float = fxY[id]) {
        val bit = 1 shl id
        fxLatched.intValue = fxLatched.intValue xor bit
        if (fxLatched.intValue and bit != 0) {
            fxX[id] = x; fxY[id] = y
            if (fxHeld.intValue and bit == 0) NativeEngine.fxOn(id, fxEffX(id), fxEffY(id))
        } else if (fxHeld.intValue and bit == 0) NativeEngine.fxOff(id)
    }
    fun fxActiveMask(): Int = transport[12].toInt()

    // ================= mixer =================

    fun setChannel(i: Int, fromAutomation: Boolean = false, f: (Channel) -> Channel) {
        val list = channels.value.toMutableList()
        val old = list[i]
        val new = f(old).let { it.copy(volumeDb = it.volumeDb.coerceIn(-60f, 6f), pan = it.pan.coerceIn(-1f, 1f), eq = old.eq) }
        if (new == old) return
        list[i] = new; channels.value = list; pushStrip(i)
        if (!fromAutomation) {
            if (new.volumeDb != old.volumeDb) auto.touch(Automation.volKey(i), autoArmed(), loopTick, loopLen(i), new.volumeDb)
            if (new.pan != old.pan) auto.touch(Automation.panKey(i), autoArmed(), loopTick, loopLen(i), new.pan)
            if (new.scAmount != old.scAmount) auto.touch(Automation.scKey(i), autoArmed(), loopTick, loopLen(i), new.scAmount)
        }
    }

    // ================= EQ =================
    private val eqUndo = List(com.thorbox.model.TRACK_COUNT) { ArrayDeque<TrackEq>() }
    private val eqEditAt = LongArray(com.thorbox.model.TRACK_COUNT)
    /** Track [i]'s EQ for the mixer's EQ page. A burst of moves (one drag) is one undo step. */
    fun setEq(i: Int, f: (TrackEq) -> TrackEq) {
        val list = channels.value.toMutableList()
        val old = list[i].eq
        val new = f(old).clamped()
        if (new == old) return
        val now = System.nanoTime() / 1_000_000L
        if (now - eqEditAt[i] > 700) {
            eqUndo[i].addLast(old); while (eqUndo[i].size > 32) eqUndo[i].removeFirst()
            app.undoLog.note(UndoLog.EQ, i)
        }
        eqEditAt[i] = now
        list[i] = list[i].copy(eq = new); channels.value = list
        pushEqDiff(i, old, new)
    }
    fun canUndoEq(i: Int) = eqUndo[i].isNotEmpty()
    fun undoEq(i: Int) {
        val prev = eqUndo[i].removeLastOrNull() ?: return
        val list = channels.value.toMutableList()
        val old = list[i].eq
        list[i] = list[i].copy(eq = prev); channels.value = list
        eqEditAt[i] = 0L
        pushEqDiff(i, old, prev)
    }
    private fun pushEqDiff(i: Int, old: TrackEq, new: TrackEq) {
        val a = old.values(); val b = new.values()
        for (id in b.indices) if (a[id] != b[id]) NativeEngine.setChannel(i, NativeEngine.Ch.EQ_BASE + id, b[id])
    }

    // ================= automation =================
    val auto = Automation()
    /** Moves are written into lanes while REC is armed and the transport runs. */
    fun autoArmed() = recording.value && playing.value
    fun loopLen(track: Int) = clips.value.getOrNull(track)?.lengthTicks ?: (16 * STEP_TICKS)
    private fun fxLoopLen() = clips.value.maxOf { it.lengthTicks }
    /** Loop length a lane repeats over: its track's pattern, or the longest pattern for FX / master. */
    fun laneLen(key: String): Int = Automation.trackOf(key).let { t -> if (t < 0) fxLoopLen() else loopLen(t) }
    private var autoFx = 0                                  // effects engaged by automation (not by a finger)

    private fun applyAutomation(key: String, v: Float) {
        val p = key.split(":")
        if (p[0] == Automation.MASTER) { setMaster(v, fromAutomation = true); return }
        if (p[0] == "fx") {
            val id = p[1].toInt()
            if (p.size == 3) { if (autoFx and (1 shl id) != 0) { fxY[id] = v; NativeEngine.fxMove(id, fxEffX(id), fxEffY(id)) }; return }
            if (fxHeld.intValue and (1 shl id) != 0) return  // a finger owns it
            if (v <= 0f) { if (autoFx and (1 shl id) != 0) { autoFx = autoFx and (1 shl id).inv(); if (fxLatched.intValue and (1 shl id) == 0) NativeEngine.fxOff(id) } }
            else {
                fxX[id] = v - 1f
                if (autoFx and (1 shl id) == 0) { autoFx = autoFx or (1 shl id); NativeEngine.fxOn(id, fxEffX(id), fxEffY(id)) }
                else NativeEngine.fxMove(id, fxEffX(id), fxEffY(id))
            }
            return
        }
        val t = p[0].drop(1).toInt()
        when (p[1]) {
            "syn" -> app.applySynthKnob(t, p[2].toInt(), p[3].toInt(), v)
            "vol" -> setChannel(t, fromAutomation = true) { it.copy(volumeDb = v) }
            "pan" -> setChannel(t, fromAutomation = true) { it.copy(pan = v) }
            "sc" -> setChannel(t, fromAutomation = true) { it.copy(scAmount = v.coerceIn(0f, 1f)) }
            "drm" -> app.drumsFor(t).setKnobAt(p[2].toInt(), p[3].toInt(), p[4].toInt(), v)
            "pad" -> app.samplerFor(t).updatePad(p[2].toInt()) { it.copy(pitch = kotlin.math.round(v).toInt()) }
        }
    }
    private fun releaseAutoFx() {
        for (id in 0 until NativeEngine.Fx.COUNT) if (autoFx and (1 shl id) != 0 && fxHeld.intValue and (1 shl id) == 0 && fxLatched.intValue and (1 shl id) == 0) NativeEngine.fxOff(id)
        autoFx = 0
    }
    private var autoWasPlaying = false
    private fun runAutomation() {
        val playingNow = transport[1] != 0.0
        if (playingNow) {
            auto.play(loopTick, { k -> laneLen(k) }, ::applyAutomation)
        } else if (autoWasPlaying) { auto.resetPlayback(); releaseAutoFx() }
        autoWasPlaying = playingNow
    }
    fun setMaster(db: Float, fromAutomation: Boolean = false) {
        masterDb.floatValue = db.coerceIn(-60f, 6f); NativeEngine.setChannel(NativeEngine.STRIP_MASTER, NativeEngine.Ch.VOLUME_DB, masterDb.floatValue)
        if (!fromAutomation) auto.touch(Automation.MASTER, autoArmed(), loopTick, fxLoopLen(), masterDb.floatValue)
    }

    // ================= per frame =================

    /** Current step (0-based) of [track]'s pattern, or -1 when stopped. */
    fun stepPosition(track: Int): Int {
        if (transport[1] == 0.0) return -1
        val c = clips.value.getOrNull(track) ?: return -1
        return ((loopTick % c.lengthTicks) / STEP_TICKS).toInt()
    }

    // ================= EXPORT hooks (see export/Exporter) =================
    /** True while an export renders: the per-frame work runs per rendered block instead. */
    var exporting = false
    /** After each export block: the same song / pattern / automation logic a live frame runs. */
    fun exportFrame() {
        NativeEngine.transport(transport)
        followScene()
        flushClips()
        while (NativeEngine.pollRecord(recBuf) != 0) Unit
        runAutomation()
    }
    /** Play the current pattern from its top (PATTERN export). */
    fun playPatternFromTop() {
        if (playing.value) togglePlay()
        setSongMode(false)
        switchNow(patternIdx.intValue)
        togglePlay()
    }
    /** Stop everything that could still be sounding or held (between export passes). */
    fun silenceForExport() {
        if (playing.value) togglePlay()
        if (recording.value) { recording.value = false; NativeEngine.setRecording(false) }
        stopAll(); releaseAutoFx(); auto.resetPlayback()
        NativeEngine.stopPads(-1)
        for (t in 0 until com.thorbox.model.TRACK_COUNT) NativeEngine.synthAllOff(t)
    }
    /** Put a saved mixer back (after solo passes). */
    fun restoreChannels(list: List<Channel>) { channels.value = list; for (i in list.indices) pushChannel(i) }
    /** Solo exactly [track] (or restore [saved] when -1), without writing automation. */
    fun soloOnly(track: Int, saved: List<Channel>) {
        channels.value = saved.mapIndexed { i, c -> if (track < 0) c else c.copy(solo = i == track) }
        for (i in channels.value.indices) pushChannel(i)
    }
    /** Restore after an export: the pattern that was current, song settings. */
    fun returnTo(pattern: Int, songSteps: List<com.thorbox.model.SongStep>, mode: Boolean, loop: Boolean) {
        if (playing.value) togglePlay()
        song.value = songSteps; songLoop.value = loop
        songMode.value = false; songPos.intValue = -1; songEndAt = -1.0
        if (queuedPattern.intValue >= 0) { NativeEngine.commitScene(0, -1); queuedPattern.intValue = -1 }
        switchNow(pattern); lastSceneTag = tagOf(pattern)
        songMode.value = mode
    }

    fun onFrame(dt: Float) {
        NativeEngine.transport(transport)
        followScene()
        flushClips()
        val fall = Math.pow(0.05, dt.toDouble()).toFloat()          // ~0.5 s meter falloff
        NativeEngine.meters(meterBuf)
        for (i in meters.indices) meters[i] = maxOf(meterBuf[i], meters[i] * fall)
        for (k in scGain.indices) scGain[k] = minOf(meterBuf[meters.size + k], scGain[k] + (1f - scGain[k]) * 0.2f)
        NativeEngine.duck(duckBuf)
        duckGrDb = if (duck.value.on) maxOf(duckBuf[0], duckBuf[1]) else 0f
        duckKeyDb = if (duckBuf[2] > 1e-5f) 20f * kotlin.math.log10(duckBuf[2]) else -90f
        duckTriggers = duckBuf[3].toInt()
        duckHistory[duckHead] = duckGrDb; duckHead = (duckHead + 1) % duckHistory.size
        while (true) {
            val t = NativeEngine.pollRecord(recBuf)
            if (t == 0) break
            onRecord(t, recBuf[0].toInt(), recBuf[1].toInt(), recBuf[2].toFloat(), recBuf[3])
        }
        runAutomation()
    }
}
