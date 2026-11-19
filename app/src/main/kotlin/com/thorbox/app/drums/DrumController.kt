// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.drums

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.app.synth.EncoderColor
import com.thorbox.engine.NativeEngine
import com.thorbox.engine.NativeEngine.DrumParam
import com.thorbox.model.DRUM_SLOTS
import com.thorbox.model.DrumKit
import com.thorbox.model.DrumModel
import com.thorbox.model.DrumVoice
import com.thorbox.model.KitPreset
import com.thorbox.model.PatchDice
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.roundToInt

/** One drum encoder: normalized position ↔ voice field. */
class DrumKnob(val label: String, val color: EncoderColor, val get: (DrumVoice) -> Float, val set: (DrumVoice, Float) -> DrumVoice, val format: (DrumVoice) -> String)

/**
 * The drum synth track (engine track 2, mixer strip 2): the kit, which slot is selected, live
 * play (with note repeat and recording via the groove controller), presets, dice, and the hit
 * telemetry that drives the drummer animation. Main-thread only.
 */
class DrumController(private val app: AppState, val track: Int = 2) {
    val kit = mutableStateOf(DrumKit(name = "TR-808", voices = com.thorbox.model.FactoryPresets.kits.first().kit.voices))
    val selected = mutableIntStateOf(0)
    val page = mutableIntStateOf(0)             // 0 SOUND, 1 MIX

    // Animation telemetry, read in draw lambdas: level per slot and the clock time of its last hit.
    val levels = FloatArray(DRUM_SLOTS)
    val hitTime = FloatArray(DRUM_SLOTS) { -10f }
    val hitVel = FloatArray(DRUM_SLOTS) { 1f }
    private val hits = IntArray(DRUM_SLOTS)
    private val lastHits = IntArray(DRUM_SLOTS)
    private var primed = false

    private val sent = Array(DRUM_SLOTS) { FloatArray(DrumParam.COUNT) { Float.NaN } }
    private val undo = ArrayDeque<DrumKit>()
    val canUndo = mutableStateOf(false)
    private var seed = (android.os.SystemClock.uptimeMillis() and 0x7fffffff).toInt()

    // ---------------- engine sync ----------------

    fun pushAll() { sent.forEach { it.fill(Float.NaN) }; push() }

    private fun push() {
        val voices = kit.value.voices
        for (s in 0 until DRUM_SLOTS) {
            val v = voices.getOrNull(s) ?: continue
            send(s, DrumParam.MODEL, v.model.ordinal.toFloat())
            send(s, DrumParam.TUNE, v.tune.coerceIn(-24f, 24f))
            send(s, DrumParam.DECAY, v.decayMs.coerceIn(5f, 4000f))
            send(s, DrumParam.TONE, v.tone.coerceIn(0f, 1f))
            send(s, DrumParam.SNAP, v.snap.coerceIn(0f, 1f))
            send(s, DrumParam.DRIVE, v.drive.coerceIn(0f, 1f))
            send(s, DrumParam.LEVEL_DB, v.levelDb.coerceIn(-40f, 6f))
            send(s, DrumParam.PAN, v.pan.coerceIn(-1f, 1f))
            send(s, DrumParam.CHOKE, v.choke.coerceIn(0, 4).toFloat())
        }
    }
    private fun send(slot: Int, id: Int, v: Float) {
        if (sent[slot][id] != v && NativeEngine.drumParam(track, slot, id, v)) sent[slot][id] = v
    }

    // ---------------- playing ----------------

    fun hit(slot: Int, velocity: Float) {
        if (slot !in 0 until DRUM_SLOTS) return
        selected.intValue = slot
        if (!app.groove.liveDown(track, slot, velocity)) NativeEngine.drumTrigger(track, slot, velocity)
        hitVel[slot] = velocity
    }
    fun release(slot: Int) { app.groove.liveUp(track, slot) }

    /** Per frame: pull levels + hit counters; a changed counter = a new hit (live or sequenced). */
    fun onFrame(timeSec: Float) {
        NativeEngine.drumTelemetry(track, levels, hits)
        for (s in 0 until DRUM_SLOTS) {
            if (primed && hits[s] != lastHits[s]) hitTime[s] = timeSec
            lastHits[s] = hits[s]
        }
        primed = true
    }

    // ---------------- editing ----------------

    private fun setKit(k: DrumKit, undoable: Boolean = true) {
        if (k == kit.value) return
        if (undoable) { undo.addLast(kit.value); while (undo.size > 32) undo.removeFirst(); canUndo.value = true }
        kit.value = k
        push()
    }
    fun updateVoice(slot: Int, f: (DrumVoice) -> DrumVoice) {
        val list = kit.value.voices.toMutableList()
        val old = list.getOrNull(slot) ?: return
        val new = f(old)
        if (new == old) return
        list[slot] = new
        setKit(kit.value.copy(voices = list), undoable = false)   // knob turns don't flood undo
    }
    fun knobValue(i: Int): Float = knobs(page.intValue)[i].get(kit.value.voices[selected.intValue])
    fun setKnob(i: Int, n: Float) {
        val k = knobs(page.intValue).getOrNull(i) ?: return
        updateVoice(selected.intValue) { k.set(it, n.coerceIn(0f, 1f)) }
    }
    // Stepped knobs (MODEL) snap; keep an unsnapped shadow so slow drags still move them.
    private val shadow = FloatArray(2 * 4) { Float.NaN }
    private val shadowSnapped = FloatArray(2 * 4) { Float.NaN }
    fun nudgeKnob(i: Int, d: Float) {
        val slot = page.intValue * 4 + i
        val cur = knobValue(i)
        if (shadow[slot].isNaN() || cur != shadowSnapped[slot]) shadow[slot] = cur
        shadow[slot] = (shadow[slot] + d).coerceIn(0f, 1f)
        setKnob(i, shadow[slot])
        shadowSnapped[slot] = knobValue(i)
    }

    // ---------------- presets / dice ----------------

    fun loadKit(p: KitPreset) = setKit(p.kit.copy(name = p.name))
    fun stepKit(d: Int) {
        val list = app.presets.allKits()
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.name == kit.value.name }
        loadKit(list[if (i < 0) 0 else (i + d + list.size) % list.size])
    }
    fun saveKit(name: String): String {
        val stored = app.presets.saveKit(KitPreset(name, kit.value))
        kit.value = kit.value.copy(name = stored)
        return stored
    }
    /** Dice the selected sound (keeps its model). */
    fun diceVoice() { seed = next(); updateVoiceUndoable(selected.intValue) { PatchDice.diceDrum(it, seed) } }
    /** Dice every sound in the kit (models stay, so it's still a playable kit). */
    fun diceKit() {
        seed = next()
        val k = kit.value
        setKit(k.copy(name = PatchDice.name(kotlin.random.Random(seed)), voices = k.voices.mapIndexed { i, v -> PatchDice.diceDrum(v, seed + i * 7919) }))
    }
    fun undoKit() {
        val prev = undo.removeLastOrNull() ?: return
        canUndo.value = undo.isNotEmpty()
        setKit(prev, undoable = false)
    }
    private fun updateVoiceUndoable(slot: Int, f: (DrumVoice) -> DrumVoice) {
        val list = kit.value.voices.toMutableList()
        list[slot] = f(list[slot])
        setKit(kit.value.copy(voices = list))
    }
    private fun next() = seed * 1103515245 + 12345 and 0x7fffffff

    companion object {
        private fun msN(ms: Float) = cbrt(((ms - 5f) / 3995f).coerceIn(0f, 1f))
        private fun nMs(n: Float) = 5f + 3995f * n * n * n
        private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

        val soundKnobs = listOf(
            DrumKnob("TUNE", EncoderColor.BLUE, { (it.tune + 24f) / 48f }, { v, n -> v.copy(tune = (n * 48f - 24f).roundToInt().toFloat()) },
                { String.format(Locale.US, "%+dst", it.tune.roundToInt()) }),
            DrumKnob("DECAY", EncoderColor.GREEN, { msN(it.decayMs) }, { v, n -> v.copy(decayMs = nMs(n)) },
                { if (it.decayMs >= 1000f) String.format(Locale.US, "%.2fs", it.decayMs / 1000f) else "${it.decayMs.roundToInt()}ms" }),
            DrumKnob("TONE", EncoderColor.WHITE, { it.tone }, { v, n -> v.copy(tone = n) }, { pct(it.tone) }),
            DrumKnob("SNAP", EncoderColor.ORANGE, { it.snap }, { v, n -> v.copy(snap = n) }, { pct(it.snap) }),
        )
        val mixKnobs = listOf(
            DrumKnob("MODEL", EncoderColor.BLUE, { it.model.ordinal / (DrumModel.entries.size - 1f) },
                { v, n -> v.copy(model = DrumModel.entries[(n * (DrumModel.entries.size - 1)).roundToInt()]) }, { it.model.label }),
            DrumKnob("DRIVE", EncoderColor.GREEN, { it.drive }, { v, n -> v.copy(drive = n) }, { pct(it.drive) }),
            DrumKnob("LEVEL", EncoderColor.WHITE, { (it.levelDb + 40f) / 46f }, { v, n -> v.copy(levelDb = (n * 46f - 40f)) },
                { String.format(Locale.US, "%+.1fdB", it.levelDb) }),
            DrumKnob("PAN", EncoderColor.ORANGE, { (it.pan + 1f) / 2f }, { v, n -> v.copy(pan = n * 2f - 1f) },
                { val p = (it.pan * 100).roundToInt(); if (p == 0) "C" else if (p < 0) "L${-p}" else "R$p" }),
        )
        fun knobs(page: Int) = if (page == 0) soundKnobs else mixKnobs
    }
}
