// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import com.thorbox.model.MusicalKey
import com.thorbox.model.NoteEvent
import com.thorbox.model.PPQN
import com.thorbox.model.ScaleMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Pure groove logic: scales, quantize, step editing and the pattern generators. There are no
 * Android dependencies, so all of it is unit-testable on the JVM.
 */

const val STEP_TICKS = PPQN / 4   // 1/16 = 240 ticks

enum class RepeatRate(val ticks: Int, val label: String) {
    EIGHTH(480, "1/8"), EIGHTH_T(320, "1/8T"), SIXTEENTH(240, "1/16"),
    SIXTEENTH_T(160, "1/16T"), THIRTYSECOND(120, "1/32"), THIRTYSECOND_T(80, "1/32T"),
}

enum class Lane { NOTE, VEL, PROB, GATE }

data class Groove(
    val bpm: Float = 90f,
    val swing: Float = 0.5f,          // 0.50 straight … 0.75 hard shuffle (MPC %)
    val swing16: Boolean = true,      // swing grid: 1/16 (true) or 1/8
    val quantize: Float = 1f,         // record quantize strength 0..1
    val quantizeGrid: Int = STEP_TICKS,
    val repeatRate: RepeatRate = RepeatRate.SIXTEENTH,
    val metronome: Boolean = false,
)

data class GenParams(
    val density: Float = 0.45f,       // share of steps with a note
    val rangeOctaves: Int = 1,        // synth: note spread
    val humanize: Float = 0.25f,      // velocity variation
    val probSpread: Boolean = false,  // give some steps < 100% probability (generative)
    val anchorRoot: Boolean = true,   // step 1 is always the root (synth) / a hit (drums)
)

// ============================== Scales ==============================

object Scales {
    fun steps(mode: ScaleMode): IntArray = when (mode) {
        ScaleMode.MAJOR -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
        ScaleMode.MINOR -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
        ScaleMode.DORIAN -> intArrayOf(0, 2, 3, 5, 7, 9, 10)
        ScaleMode.MIXOLYDIAN -> intArrayOf(0, 2, 4, 5, 7, 9, 10)
        ScaleMode.PENTATONIC_MAJOR -> intArrayOf(0, 2, 4, 7, 9)
        ScaleMode.PENTATONIC_MINOR -> intArrayOf(0, 3, 5, 7, 10)
        ScaleMode.CHROMATIC -> IntArray(12) { it }
    }

    /** Scale degree (may be negative) → MIDI note, with [base] = the root's MIDI note. */
    fun noteOf(key: MusicalKey, base: Int, degree: Int): Int {
        val s = steps(key.mode)
        val oct = Math.floorDiv(degree, s.size)
        val idx = Math.floorMod(degree, s.size)
        return (base + 12 * oct + s[idx]).coerceIn(0, 127)
    }

    fun inScale(note: Int, key: MusicalKey): Boolean =
        steps(key.mode).contains(Math.floorMod(note - key.root.ordinal, 12))

    /** Nearest in-scale note (ties go down, so a snap never jumps up past the tonic). */
    fun snap(note: Int, key: MusicalKey): Int {
        if (inScale(note, key)) return note
        for (d in 1..11) {   // only consider candidates inside the MIDI range
            if (note - d >= 0 && inScale(note - d, key)) return note - d
            if (note + d <= 127 && inScale(note + d, key)) return note + d
        }
        return note
    }

    /** MIDI note → nearest scale degree relative to [base]. */
    fun degreeOf(note: Int, key: MusicalKey, base: Int): Int {
        val s = steps(key.mode)
        var best = 0; var bestDist = Int.MAX_VALUE
        for (d in -4 * s.size..6 * s.size) {
            val dist = abs(noteOf(key, base, d) - note)
            if (dist < bestDist) { bestDist = dist; best = d }
        }
        return best
    }

    fun label(mode: ScaleMode) = when (mode) {
        ScaleMode.MAJOR -> "MAJ"; ScaleMode.MINOR -> "MIN"; ScaleMode.DORIAN -> "DOR"; ScaleMode.MIXOLYDIAN -> "MIX"
        ScaleMode.PENTATONIC_MAJOR -> "PNT+"; ScaleMode.PENTATONIC_MINOR -> "PNT−"; ScaleMode.CHROMATIC -> "CHR"
    }
}

// ============================== Quantize & recording ==============================

object Quantize {
    /**
     * Moves [tick] toward the nearest [grid] line by [strength] (0 = as played, 1 = on the grid),
     * wrapping inside a pattern of [length] ticks.
     */
    fun apply(tick: Double, grid: Int, strength: Float, length: Int): Int {
        val local = ((tick % length) + length) % length
        val q = (local / grid).roundToInt() * grid.toDouble()
        val t = local + (q - local) * strength.coerceIn(0f, 1f)
        return Math.floorMod(t.roundToInt(), length)
    }
}

// ============================== Step editing ==============================

object Steps {
    fun count(lengthTicks: Int) = (lengthTicks / STEP_TICKS).coerceAtLeast(1)

    /** Index of the step an event belongs to (nearest step line). */
    fun stepOf(e: NoteEvent, lengthTicks: Int) = Math.floorMod(((e.tick + STEP_TICKS / 2) / STEP_TICKS), count(lengthTicks))

    /** First event on each step (what the step editor shows), or null. */
    fun byStep(notes: List<NoteEvent>, lengthTicks: Int): Array<NoteEvent?> {
        val out = arrayOfNulls<NoteEvent>(count(lengthTicks))
        for (e in notes.sortedBy { it.tick }) { val s = stepOf(e, lengthTicks); if (out[s] == null) out[s] = e }
        return out
    }

    fun toggle(notes: List<NoteEvent>, lengthTicks: Int, step: Int, newNote: Int, velocity: Float = 0.8f): List<NoteEvent> {
        val has = notes.any { stepOf(it, lengthTicks) == step }
        return if (has) notes.filterNot { stepOf(it, lengthTicks) == step }
        else (notes + NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = newNote, velocity = velocity)).sortedBy { it.tick }
    }

    /** Sets a lane value (0..1) on every event of [step]. NOTE is handled by the caller (scale-aware). */
    fun edit(notes: List<NoteEvent>, lengthTicks: Int, step: Int, lane: Lane, v: Float): List<NoteEvent> = notes.map { e ->
        if (stepOf(e, lengthTicks) != step) e else when (lane) {
            Lane.VEL -> e.copy(velocity = v.coerceIn(0.05f, 1f))
            Lane.PROB -> e.copy(probability = (v * 8f).roundToInt().coerceIn(1, 8) / 8f)          // 12.5% steps
            Lane.GATE -> e.copy(lengthTicks = gateTicks(v))
            Lane.NOTE -> e
        }
    }

    /** 0..1 → 1/64 … 4 steps (exponential, so short gates are easy to dial). */
    fun gateTicks(v: Float) = (STEP_TICKS / 4f * Math.pow(16.0, v.coerceIn(0f, 1f).toDouble()).toFloat()).roundToInt().coerceAtLeast(10)
    fun gateNorm(ticks: Int) = (Math.log(ticks / (STEP_TICKS / 4.0)) / Math.log(16.0)).toFloat().coerceIn(0f, 1f)

    fun resize(notes: List<NoteEvent>, newLengthTicks: Int) = notes.filter { it.tick < newLengthTicks }

    fun snapAll(notes: List<NoteEvent>, key: MusicalKey) = notes.map { it.copy(note = Scales.snap(it.note, key)) }
}

// ============================== Generators ==============================

class PatternGen(seed: Int) {
    private val rnd = Random(seed)

    companion object {
        /** Bjorklund/Euclidean rhythm: [hits] spread as evenly as possible over [steps], rotated. */
        fun euclid(hits: Int, steps: Int, rotate: Int = 0): BooleanArray {
            val out = BooleanArray(steps)
            if (hits <= 0 || steps <= 0) return out
            val k = hits.coerceAtMost(steps)
            for (i in 0 until steps) out[Math.floorMod(i + rotate, steps)] = (i * k) % steps < k
            return out
        }
    }

    private fun vel(h: Float, accent: Boolean) =
        ((if (accent) 0.95f else 0.72f) + (rnd.nextFloat() * 2f - 1f) * 0.25f * h).coerceIn(0.2f, 1f)
    private fun prob(spread: Boolean) = if (!spread) 1f else listOf(1f, 1f, 1f, 0.75f, 0.5f, 0.25f).random(rnd)

    /** Drums (sampler track): Euclidean rhythm with a random rotation, accents on beats. */
    fun drums(steps: Int, p: GenParams, pad: Int = 0): List<NoteEvent> {
        val hits = (p.density * steps).roundToInt().coerceIn(1, steps)
        val rot = if (p.anchorRoot) 0 else rnd.nextInt(steps)
        val pattern = euclid(hits, steps, rot)
        if (p.anchorRoot) pattern[0] = true
        return pattern.indices.filter { pattern[it] }.map { s ->
            NoteEvent(tick = s * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = pad,
                velocity = vel(p.humanize, s % 4 == 0), probability = if (s == 0) 1f else prob(p.probSpread))
        }
    }

    /**
     * A whole beat for the drum machine (slots: 0 kick, 1 snare, 2 clap, 3 closed hat, 4 open hat,
     * 5 tom, 6 perc, 7 cymbal). Always a groove, never noise: the kick owns the downbeat, the
     * backbeat lands on 2 and 4, hats keep time (with the odd trap roll), and [GenParams.density]
     * decides how busy the extras get.
     */
    fun beat(steps: Int, p: GenParams): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        val d = p.density.coerceIn(0.05f, 1f)
        fun hit(step: Int, slot: Int, vel: Float, prob: Float = 1f, ratchet: Int = 1) {
            out += NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = slot,
                velocity = (vel + (rnd.nextFloat() * 2f - 1f) * 0.12f * p.humanize).coerceIn(0.2f, 1f), probability = prob, ratchet = ratchet)
        }
        val kickExtras = listOf(3, 6, 7, 8, 10, 11, 14).shuffled(rnd).take(1 + (d * 3).roundToInt())
        val hatRate = if (rnd.nextFloat() < 0.35f + 0.4f * d) 1 else 2            // 16ths or 8ths
        val openOffbeat = rnd.nextFloat() < 0.5f
        val clapLayer = rnd.nextFloat() < 0.5f
        for (s in 0 until steps) {
            val b = s % 16
            if (b == 0 || b in kickExtras) hit(s, 0, if (b == 0) 1f else 0.85f)
            if (b == 4 || b == 12) { hit(s, 1, 0.95f); if (clapLayer) hit(s, 2, 0.8f) }
            else if (!clapLayer && b == 15 && rnd.nextFloat() < d) hit(s, 1, 0.55f, prob = 0.5f)   // ghost snare
            val open = openOffbeat && b % 4 == 2
            if (open) hit(s, 4, 0.8f)
            else if (b % hatRate == 0) {
                val roll = hatRate == 1 && rnd.nextFloat() < 0.12f * d
                hit(s, 3, if (b % 4 == 0) 0.85f else 0.6f, ratchet = if (roll) 2 + rnd.nextInt(2) else 1)
            }
            if (rnd.nextFloat() < 0.07f * d) hit(s, 5, 0.75f, prob = 0.75f)
            if (rnd.nextFloat() < 0.09f * d) hit(s, 6, 0.7f, prob = if (p.probSpread) 0.5f else 1f)
            if (s == 0 && rnd.nextFloat() < 0.4f) hit(s, 7, 0.7f)
        }
        return out.sortedBy { it.tick }
    }

    /**
     * Melody (synth track): always in [key]. Chord tones (degrees 0, 2, 4) are twice as likely,
     * steps move mostly by small intervals so lines sound sung, not sprayed, and gates vary.
     */
    fun melody(steps: Int, p: GenParams, key: MusicalKey, base: Int): List<NoteEvent> {
        val size = Scales.steps(key.mode).size
        val span = size * p.rangeOctaves.coerceIn(1, 3)
        val out = ArrayList<NoteEvent>()
        var deg = 0
        for (s in 0 until steps) {
            val on = (s == 0 && p.anchorRoot) || rnd.nextFloat() < p.density
            if (!on) continue
            deg = if (s == 0 && p.anchorRoot) 0 else nextDegree(deg, size, span)
            val gate = listOf(0.5f, 0.5f, 1f, 1f, 2f, 3f).random(rnd)
            out += NoteEvent(tick = s * STEP_TICKS, lengthTicks = (STEP_TICKS * gate).roundToInt(),
                note = Scales.noteOf(key, base, deg), velocity = vel(p.humanize, s % 4 == 0),
                probability = if (s == 0) 1f else prob(p.probSpread))
        }
        return out
    }

    private fun nextDegree(prev: Int, size: Int, span: Int): Int {
        val r = rnd.nextFloat()
        val cand = when {
            r < 0.45f -> prev + listOf(-2, -1, 1, 2).random(rnd)       // stepwise motion
            r < 0.8f -> listOf(0, 2, 4, size, size + 2).random(rnd)    // chord tones
            else -> rnd.nextInt(span)                                  // leap
        }
        return Math.floorMod(cand, span)
    }

    /** Mutate: keep the groove, nudge ~[amount] of it. Notes move by scale steps; a few hits move or toggle. */
    fun mutate(notes: List<NoteEvent>, lengthTicks: Int, amount: Float, key: MusicalKey?, base: Int, isDrums: Boolean): List<NoteEvent> {
        val steps = Steps.count(lengthTicks)
        val out = notes.map { e ->
            if (rnd.nextFloat() >= amount) e
            else if (isDrums || key == null) e.copy(velocity = (e.velocity + (rnd.nextFloat() - 0.5f) * 0.4f).coerceIn(0.2f, 1f))
            else {
                val d = Scales.degreeOf(e.note, key, base) + listOf(-2, -1, 1, 2).random(rnd)
                e.copy(note = Scales.noteOf(key, base, d))
            }
        }.toMutableList()
        // Move one hit to a free step (keeps density) and maybe add a ghost note.
        if (out.isNotEmpty() && rnd.nextFloat() < amount * 2f) {
            val i = rnd.nextInt(out.size)
            val taken = out.map { Steps.stepOf(it, lengthTicks) }.toSet()
            val free = (0 until steps).filter { it !in taken }
            if (free.isNotEmpty()) out[i] = out[i].copy(tick = free.random(rnd) * STEP_TICKS)
        }
        if (isDrums && rnd.nextFloat() < amount) {
            val taken = out.map { Steps.stepOf(it, lengthTicks) }.toSet()
            val free = (0 until steps).filter { it !in taken }
            if (free.isNotEmpty()) out += NoteEvent(tick = free.random(rnd) * STEP_TICKS, lengthTicks = STEP_TICKS / 2,
                note = notes.firstOrNull()?.note ?: 0, velocity = 0.35f, probability = 0.75f)
        }
        return out.sortedBy { it.tick }
    }
}
