// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.engine.NativeEngine
import com.thorbox.model.Clip
import com.thorbox.model.ClipId
import com.thorbox.model.MusicalKey
import com.thorbox.model.NoteEvent
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import kotlin.math.roundToInt

/** Mixer strip state (UI side). Strip 0 = sampler, 1 = synth. */
data class Channel(val name: String, val volumeDb: Float = 0f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false)

/** The keyboard/sequencer key. With [followSample] on, the loaded sample's detected key wins. */
data class ScaleSetting(val root: PitchClass = PitchClass.C, val mode: ScaleMode = ScaleMode.MINOR, val followSample: Boolean = true)

/**
 * Sequencer, groove, mixer and punch-in FX state, and the only code that sends those commands
 * to the engine. Main-thread only. Every edit pushes a fresh immutable pattern snapshot.
 */
class GrooveController(private val app: AppState) {
    // ---- transport / groove ----
    val groove = mutableStateOf(Groove())
    val playing = mutableStateOf(false)
    val recording = mutableStateOf(false)

    // ---- patterns (index = engine track) ----
    val clips = mutableStateOf(listOf(
        Clip(ClipId("t0"), "SAMPLER", color = 0, lengthTicks = 16 * STEP_TICKS),
        Clip(ClipId("t1"), "SYNTH", color = 1, lengthTicks = 16 * STEP_TICKS),
        Clip(ClipId("t2"), "DRUMS", color = 2, lengthTicks = 16 * STEP_TICKS),
    ))
    val seqTrack = mutableIntStateOf(0)
    val lane = mutableStateOf(Lane.VEL)
    val cursor = mutableIntStateOf(0)
    val gen = mutableStateOf(GenParams())
    val seed = mutableIntStateOf(1)
    private val undo = List(3) { ArrayDeque<Clip>() }
    val canUndo = mutableStateOf(false)

    // ---- scale ----
    val scale = mutableStateOf(ScaleSetting())

    // ---- mixer ----
    val channels = mutableStateOf(listOf(Channel("SAMPLER"), Channel("SYNTH"), Channel("DRUMS")))
    val masterDb = mutableStateOf(0f)
    val mixSelected = mutableIntStateOf(0)

    // ---- note repeat ----
    val repeatHeld = mutableStateOf(false)
    private val activeRepeats = HashSet<Int>()          // key = track * 128 + note

    // ---- punch-in FX ----
    val fxLatched = mutableIntStateOf(0)                // bitmask: effects that stay on when released
    val fxHeld = mutableIntStateOf(0)                   // bitmask: effects currently touched / pressed

    // ---- live readouts (read in draw lambdas) ----
    val transport = DoubleArray(20)
    val meters = FloatArray(8)                          // displayed peaks with falloff: sampler, synth, master, drums (L/R)
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
        NativeEngine.setChannel(NativeEngine.STRIP_MASTER, NativeEngine.Ch.VOLUME_DB, masterDb.value)
    }

    private fun pushGroove() {
        val g = groove.value
        NativeEngine.setTempo(g.bpm)
        NativeEngine.setSwing(g.swing, if (g.swing16) 240 else 480)
        NativeEngine.setMetronome(g.metronome)
    }

    // Edits mark a track dirty. onFrame() sends at most ONE new pattern per track per frame, however
    // many edits a drag or a recording produced (each push allocates and copies a native pattern).
    private val dirty = BooleanArray(3)
    private fun pushClip(track: Int) { dirty[track] = true }
    private fun flushClips() { for (t in dirty.indices) if (dirty[t]) { dirty[t] = false; sendClip(t) } }

    private fun sendClip(track: Int) {
        val c = clips.value[track]
        val n = c.notes.size.coerceAtMost(512)
        val ns = c.notes.take(n)
        val lockIds = IntArray(2 * n) { -1 }
        val lockVals = FloatArray(2 * n)
        for (i in 0 until n) ns[i].locks.take(2).forEachIndexed { k, l -> lockIds[2 * i + k] = l.param; lockVals[2 * i + k] = l.value }
        NativeEngine.setPattern(track, c.lengthTicks,
            IntArray(n) { ns[it].tick }, IntArray(n) { ns[it].lengthTicks.coerceIn(1, c.lengthTicks) },
            IntArray(n) { ns[it].note }, FloatArray(n) { ns[it].velocity }, FloatArray(n) { ns[it].probability },
            IntArray(n) { ns[it].ratchet.coerceIn(1, 4) or (ns[it].condEvery.coerceIn(1, 8) shl 4) or (ns[it].condPhase.coerceIn(0, 7) shl 8) },
            lockIds, lockVals)
    }

    private fun pushChannel(i: Int) {
        val ch = channels.value[i]
        NativeEngine.setChannel(i, NativeEngine.Ch.VOLUME_DB, ch.volumeDb)
        NativeEngine.setChannel(i, NativeEngine.Ch.PAN, ch.pan)
        NativeEngine.setChannel(i, NativeEngine.Ch.MUTE, if (ch.mute) 1f else 0f)
        NativeEngine.setChannel(i, NativeEngine.Ch.SOLO, if (ch.solo) 1f else 0f)
    }

    fun stopAll() {
        activeRepeats.forEach { NativeEngine.repeatOff(it / 128, it % 128) }; activeRepeats.clear()
        NativeEngine.fxAllOff(); fxHeld.intValue = 0; fxLatched.intValue = 0
    }

    // ================= transport =================

    fun togglePlay() { playing.value = !playing.value; NativeEngine.setPlaying(playing.value); if (!playing.value) openNotes.clear() }
    fun toggleRecord() {
        recording.value = !recording.value
        NativeEngine.setRecording(recording.value)
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
        if (saveUndo) { undo[track].addLast(old); while (undo[track].size > 16) undo[track].removeFirst(); canUndo.value = true }
        list[track] = new
        clips.value = list
        pushClip(track)
    }

    /** Tracks whose notes are pads/slots (one row per sound) rather than pitches. */
    fun isPadTrack(track: Int) = track != 1
    /** The pad / drum slot that pad-track step edits apply to. */
    fun selectedPad(track: Int) = if (track == NativeEngine.TRACK_DRUMS) app.drums.selected.intValue else app.sampler.selected.intValue

    fun defaultNote(track: Int): Int = if (isPadTrack(track)) selectedPad(track) else
        clips.value[1].notes.lastOrNull()?.note ?: synthBase()

    /** Pad tracks edit one pad at a time (the selected pad), like an MPC/Koala step view. Synth edits everything. */
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

    fun drumHas(step: Int, slot: Int): Boolean {
        val c = clips.value[NativeEngine.TRACK_DRUMS]
        return c.notes.any { it.note == slot && Steps.stepOf(it, c.lengthTicks) == step }
    }
    /** Toggle one cell of the TR grid. Accent (velocity) cycles on a second tap? No: tap = on/off, kept simple. */
    fun drumToggle(step: Int, slot: Int) = update(NativeEngine.TRACK_DRUMS) { c ->
        val hit = c.notes.firstOrNull { it.note == slot && Steps.stepOf(it, c.lengthTicks) == step }
        if (hit != null) c.copy(notes = c.notes - hit)
        else c.copy(notes = (c.notes + NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = slot,
            velocity = if (step % 4 == 0) 0.95f else 0.8f)).sortedBy { it.tick })
    }
    /** Dice a whole beat: kick on the grid, backbeat snare/clap, busy hats, sprinkles of the rest. */
    fun diceBeat() {
        seed.intValue = seed.intValue * 1103515245 + 12345 and 0x7fffffff
        val g = PatternGen(seed.intValue)
        update(NativeEngine.TRACK_DRUMS, saveUndo = true) { c -> c.copy(notes = g.beat(Steps.count(c.lengthTicks), gen.value)) }
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

    fun setLength(track: Int, steps: Int) = update(track, saveUndo = true) { c ->
        val len = steps.coerceIn(1, 64) * STEP_TICKS
        c.copy(lengthTicks = len, notes = Steps.resize(c.notes, len))
    }

    fun clear(track: Int) = update(track, saveUndo = true) { it.copy(notes = emptyList()) }

    fun dice(track: Int) {
        seed.intValue = seed.intValue * 1103515245 + 12345 and 0x7fffffff
        val g = PatternGen(seed.intValue)
        update(track, saveUndo = true) { c ->
            val steps = Steps.count(c.lengthTicks)
            c.copy(notes = if (isPadTrack(track)) scoped(track, c.notes) { g.drums(steps, gen.value, selectedPad(track)) } else g.melody(steps, gen.value, key(), synthBase()))
        }
    }

    fun mutate(track: Int) {
        seed.intValue = seed.intValue * 1103515245 + 12345 and 0x7fffffff
        val g = PatternGen(seed.intValue)
        update(track, saveUndo = true) { c -> c.copy(notes = g.mutate(c.notes, c.lengthTicks, 0.3f, if (track == 1) key() else null, synthBase(), isPadTrack(track))) }
    }

    /** Snap the synth pattern to the current scale (after you change key). */
    fun snapToScale() = update(1, saveUndo = true) { c -> c.copy(notes = Steps.snapAll(c.notes, key())) }

    fun undo(track: Int) {
        val prev = undo[track].removeLastOrNull() ?: return
        val list = clips.value.toMutableList(); list[track] = prev; clips.value = list; pushClip(track)
        canUndo.value = undo.any { it.isNotEmpty() }
    }

    fun setScale(s: ScaleSetting) { val old = scale.value; scale.value = s; app.releaseAllKeys(); if (old != s) app.sampler.projectChanged() }

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

    fun gridToggle(step: Int, note: Int) = update(1) { c ->
        val t = step * STEP_TICKS
        val hit = c.notes.firstOrNull { it.note == note && t >= it.tick && t < it.tick + maxOf(it.lengthTicks, STEP_TICKS / 2) }
        if (hit != null) c.copy(notes = c.notes - hit)
        else c.copy(notes = (c.notes + NoteEvent(tick = t, lengthTicks = STEP_TICKS, note = note, velocity = 0.8f)).sortedBy { it.tick })
    }

    fun gridResize(tick: Int, note: Int, lengthTicks: Int) = update(1) { c ->
        c.copy(notes = c.notes.map { if (it.tick == tick && it.note == note) it.copy(lengthTicks = lengthTicks.coerceIn(STEP_TICKS / 4, c.lengthTicks)) else it })
    }

    fun gridMove(tick: Int, note: Int, newNote: Int) = update(1) { c ->
        if (c.notes.any { it.tick == tick && it.note == newNote }) c
        else c.copy(notes = c.notes.map { if (it.tick == tick && it.note == note) it.copy(note = newNote.coerceIn(0, 127)) else it })
    }

    // ================= synth: OP-XY-style step entry =================

    val heldStep = mutableIntStateOf(-1)         // the step whose components the top screen shows
    private var heldEdited = false
    private var heldChordStarted = false

    fun stepEvents(step: Int): List<NoteEvent> {
        val c = clips.value[1]
        return c.notes.filter { Steps.stepOf(it, c.lengthTicks) == step }
    }

    fun holdStep(step: Int) { heldStep.intValue = step; heldEdited = false; heldChordStarted = false }

    /** Releasing a step you only tapped toggles it; one you edited while holding keeps the edit. */
    fun releaseStep(step: Int, tapped: Boolean) {
        if (heldStep.intValue == step) heldStep.intValue = -1
        if (!heldEdited && tapped) stepTap(1, step)
    }

    /** A key pressed while a step is held: the first key replaces the step's notes, later keys add (chords). */
    fun keyIntoHeldStep(note: Int, velocity: Float): Boolean {
        val step = heldStep.intValue
        if (step < 0) return false
        heldEdited = true
        val add = heldChordStarted
        heldChordStarted = true
        update(1) { c ->
            val t = step * STEP_TICKS
            val mine = c.notes.filter { Steps.stepOf(it, c.lengthTicks) == step }
            val template = mine.firstOrNull() ?: NoteEvent(tick = t, lengthTicks = STEP_TICKS, note = note, velocity = velocity)
            val kept = if (add) mine.filterNot { it.note == note } else emptyList()
            val others = c.notes - mine.toSet()
            c.copy(notes = (others + kept + template.copy(note = note, velocity = velocity)).sortedBy { it.tick })
        }
        return true
    }

    fun editHeld(f: (NoteEvent) -> NoteEvent) {
        val step = heldStep.intValue
        if (step < 0) return
        heldEdited = true
        update(1) { c -> c.copy(notes = c.notes.map { if (Steps.stepOf(it, c.lengthTicks) == step) f(it) else it }) }
    }

    /** Trig conditions, OP-XY / Elektron style: "a:b" = play on loop a of every b. */
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
            val q = Quantize.apply(tick, g.quantizeGrid, g.quantize, c.lengthTicks)
            update(track) { cl ->
                val kept = cl.notes.filterNot { it.note == note && kotlin.math.abs(it.tick - q) < 60 }   // replace, don't stack
                cl.copy(notes = (kept + NoteEvent(tick = q, lengthTicks = STEP_TICKS / 2, note = note, velocity = vel)).sortedBy { it.tick })
            }
            openNotes[k] = q to tick
        } else {
            val (q, start) = openNotes.remove(k) ?: return
            val len = (tick - start).roundToInt().coerceIn(30, c.lengthTicks)
            if (track == 1) update(track) { cl -> cl.copy(notes = cl.notes.map { if (it.note == note && it.tick == q) it.copy(lengthTicks = len) else it }) }
        }
    }

    // ================= note repeat =================

    /** Returns true if the hit became a repeat (the caller must not also trigger it). */
    fun liveDown(track: Int, note: Int, vel: Float): Boolean {
        if (!repeatHeld.value) return false
        activeRepeats += track * 128 + note
        NativeEngine.repeatOn(track, note, groove.value.repeatRate.ticks, vel)
        return true
    }

    fun liveUp(track: Int, note: Int): Boolean {
        if (!activeRepeats.remove(track * 128 + note)) return false
        NativeEngine.repeatOff(track, note)
        return true
    }

    fun setRepeatHeld(held: Boolean) {
        repeatHeld.value = held
        if (!held) { activeRepeats.forEach { NativeEngine.repeatOff(it / 128, it % 128) }; activeRepeats.clear() }
    }

    // ================= punch-in FX =================

    fun fxDown(id: Int, x: Float, y: Float) { fxHeld.intValue = fxHeld.intValue or (1 shl id); NativeEngine.fxOn(id, x, y) }
    fun fxMove(id: Int, x: Float, y: Float) = NativeEngine.fxMove(id, x, y)
    fun fxUp(id: Int) {
        fxHeld.intValue = fxHeld.intValue and (1 shl id).inv()
        if (fxLatched.intValue and (1 shl id) == 0) NativeEngine.fxOff(id)
    }
    fun toggleLatch(id: Int) {
        val bit = 1 shl id
        fxLatched.intValue = fxLatched.intValue xor bit
        if (fxLatched.intValue and bit == 0 && fxHeld.intValue and bit == 0) NativeEngine.fxOff(id)
    }
    fun fxActiveMask(): Int = transport[12].toInt()

    // ================= mixer =================

    fun setChannel(i: Int, f: (Channel) -> Channel) {
        val list = channels.value.toMutableList()
        val new = f(list[i]).let { it.copy(volumeDb = it.volumeDb.coerceIn(-60f, 6f), pan = it.pan.coerceIn(-1f, 1f)) }
        if (new == list[i]) return
        list[i] = new; channels.value = list; pushChannel(i)
    }
    fun setMaster(db: Float) { masterDb.value = db.coerceIn(-60f, 6f); NativeEngine.setChannel(NativeEngine.STRIP_MASTER, NativeEngine.Ch.VOLUME_DB, masterDb.value) }

    // ================= per frame =================

    /** Current step (0-based) of [track]'s pattern, or -1 when stopped. */
    fun stepPosition(track: Int): Int {
        if (transport[1] == 0.0) return -1
        val c = clips.value.getOrNull(track) ?: return -1
        return ((transport[0] % c.lengthTicks) / STEP_TICKS).toInt()
    }

    fun onFrame(dt: Float) {
        flushClips()
        NativeEngine.transport(transport)
        val fall = Math.pow(0.05, dt.toDouble()).toFloat()          // ~0.5 s meter falloff
        for (i in 0 until 6) meters[i] = maxOf(transport[4 + i].toFloat(), meters[i] * fall)
        for (i in 0 until 2) meters[6 + i] = maxOf(transport[16 + i].toFloat(), meters[6 + i] * fall)
        while (true) {
            val t = NativeEngine.pollRecord(recBuf)
            if (t == 0) break
            onRecord(t, recBuf[0].toInt(), recBuf[1].toInt(), recBuf[2].toFloat(), recBuf[3])
        }
    }
}
