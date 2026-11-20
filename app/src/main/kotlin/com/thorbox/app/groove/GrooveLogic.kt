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
    private fun chance(p: Float) = rnd.nextFloat() < p
    private fun <T> pick(vararg xs: T): T = xs[rnd.nextInt(xs.size)]

    /** What the last roll came out as (TRAP, HOOK, EUCLID 5/16 …); the die shows it for a moment. */
    var style = ""
        private set
    var beatStyle: BeatStyle? = null
        private set
    var melodyStyle: MelodyStyle? = null
        private set

    // ------------------------------------------------------------------ sampler pad

    /**
     * One pad's steps (sampler track). Three different ways to roll, picked at random each time, so
     * consecutive rolls really differ: a rotated Euclidean rhythm, a short motif repeated with a
     * twist at the end, or weighted syncopation (offbeats favoured). Step 1 is kept when anchored.
     */
    fun drums(steps: Int, p: GenParams, pad: Int = 0): List<NoteEvent> {
        val on = BooleanArray(steps)
        val target = ((p.density * steps).roundToInt() + rnd.nextInt(-2, 3)).coerceIn(1, steps)
        when (rnd.nextInt(3)) {
            0 -> {
                val base = euclid(target, steps)
                // rotate so that a random onset (or any step, unanchored) lands on step 1
                val onsets = base.indices.filter { base[it] }
                val shift = if (p.anchorRoot) onsets[rnd.nextInt(onsets.size)] else rnd.nextInt(steps)
                for (i in 0 until steps) on[i] = base[(i + shift) % steps]
                if (steps >= 8 && chance(0.5f)) on[rnd.nextInt(steps)] = true          // one stray hit
                style = "EUCLID $target/$steps"
            }
            1 -> {
                val m = if (steps >= 8) pick(4, 8).coerceAtMost(steps) else steps
                val motif = rhythm(m, (target.toFloat() / steps).coerceIn(0.1f, 1f), p.anchorRoot)
                for (i in 0 until steps) on[i] = motif[i % m]
                if (steps >= 2 * m) {                                                   // answer: vary the last repeat
                    val from = steps - m
                    for (i in from until steps) if (chance(0.35f)) on[i] = !on[i]
                }
                style = "MOTIF $m"
            }
            else -> {
                val w = FloatArray(4) { 0.4f + rnd.nextFloat() * 0.8f }; w[2] += 0.3f     // favour the "and"
                for (i in 0 until steps) on[i] = rnd.nextFloat() < p.density * w[i % 4] * 1.3f
                style = "SYNCO"
            }
        }
        if (p.anchorRoot) on[0] = true
        if (on.none { it }) on[0] = true
        return on.indices.filter { on[it] }.map { s ->
            NoteEvent(tick = s * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = pad,
                velocity = vel(p.humanize, s % 4 == 0), probability = if (s == 0) 1f else prob(p.probSpread))
        }
    }

    /** A rhythm of [m] steps: weighted onsets (downbeats > 8ths > 16ths) scaled to [density]. */
    private fun rhythm(m: Int, density: Float, anchor: Boolean): BooleanArray {
        val w = floatArrayOf(1f, 0.45f + rnd.nextFloat() * 0.4f, 0.6f + rnd.nextFloat() * 0.5f, 0.35f + rnd.nextFloat() * 0.5f)
        val out = BooleanArray(m) { rnd.nextFloat() < density * w[it % 4] * 1.5f }
        if (anchor) out[0] = true
        if (out.none { it }) out[rnd.nextInt(m)] = true
        return out
    }

    // ------------------------------------------------------------------ drum machine beat

    enum class BeatStyle(val label: String) { BOOM_BAP("BOOM BAP"), TRAP("TRAP"), HOUSE("HOUSE"), BREAKS("BREAKS"), DEMBOW("DEMBOW"), LOFI("LO-FI") }

    /**
     * A whole beat for the drum machine (slots: 0 kick, 1 snare, 2 clap, 3 closed hat, 4 open hat,
     * 5 tom, 6 perc, 7 cymbal). Each roll picks a style (never the same one twice in a row unless
     * asked), then its kick placement, hat feel, ghosts and fills are rolled inside that style.
     * Loops longer than a bar get a varied 2nd bar and a fill into the top.
     */
    fun beat(steps: Int, p: GenParams, avoid: BeatStyle? = null, force: BeatStyle? = null): List<NoteEvent> {
        val st = force ?: BeatStyle.entries.filter { it != avoid }.let { it[rnd.nextInt(it.size)] }
        style = st.label; beatStyle = st
        val out = ArrayList<NoteEvent>()
        val d = p.density.coerceIn(0.05f, 1f)
        fun hit(step: Int, slot: Int, vel: Float, prob: Float = 1f, ratchet: Int = 1) {
            if (step !in 0 until steps) return
            if (out.any { it.note == slot && it.tick == step * STEP_TICKS }) return
            out += NoteEvent(tick = step * STEP_TICKS, lengthTicks = STEP_TICKS / 2, note = slot,
                velocity = (vel + (rnd.nextFloat() * 2f - 1f) * 0.12f * p.humanize).coerceIn(0.2f, 1f), probability = prob, ratchet = ratchet)
        }
        fun extras(pool: List<Int>, n: Int) = pool.shuffled(rnd).take(n.coerceIn(0, pool.size)).toSet()
        val bars = ((steps + 15) / 16).coerceAtLeast(1)
        // one kick/hat idea for the loop; later bars re-roll a little of it
        var kicks = emptySet<Int>()
        val hatRate = when (st) { BeatStyle.TRAP -> pick(1, 1, 2); BeatStyle.HOUSE, BeatStyle.DEMBOW -> pick(1, 2); else -> if (chance(0.3f + 0.4f * d)) 1 else 2 }
        val openOff = chance(if (st == BeatStyle.HOUSE) 0.85f else 0.35f)
        val clap = chance(0.5f)
        val rollSteps = if (st == BeatStyle.TRAP) extras((0 until 16).filter { it % 2 == 1 || it in setOf(6, 14) }, 1 + (d * 3).roundToInt()) else emptySet()
        for (bar in 0 until bars) {
            if (bar == 0 || chance(0.5f)) kicks = when (st) {
                BeatStyle.BOOM_BAP -> setOf(0) + extras(listOf(3, 7, 8, 10, 11, 14), 1 + (d * 2.5f).roundToInt())
                BeatStyle.TRAP -> setOf(0) + extras(listOf(3, 6, 7, 10, 11, 13, 14), 1 + (d * 3).roundToInt())
                BeatStyle.HOUSE -> setOf(0, 4, 8, 12) + (if (chance(0.3f * d)) setOf(pick(3, 7, 14, 15)) else emptySet())
                BeatStyle.BREAKS -> setOf(0, 10) + extras(listOf(2, 6, 7, 11, 13), (d * 2f).roundToInt())
                BeatStyle.DEMBOW -> setOf(0, 4, 8, 12)
                BeatStyle.LOFI -> setOf(0) + extras(listOf(5, 7, 9, 10, 13), 1 + (d * 1.5f).roundToInt())
            }
            val fill = bars > 1 && bar == bars - 1 && chance(0.6f)
            for (b in 0 until 16) {
                val s = bar * 16 + b
                if (s >= steps) break
                if (fill && b >= 12) {                                   // fill into the top of the loop
                    hit(s, pick(1, 1, 5), 0.6f + 0.1f * (b - 12), ratchet = if (b == 15 && chance(0.4f)) 2 else 1)
                    continue
                }
                if (b in kicks) hit(s, 0, if (b == 0) 1f else 0.82f)
                when (st) {
                    BeatStyle.TRAP -> if (b == 8) { hit(s, 1, 0.95f); if (clap) hit(s, 2, 0.85f) }
                    BeatStyle.DEMBOW -> if (b in setOf(3, 6, 11, 14)) hit(s, 1, 0.9f)
                    else -> if (b == 4 || b == 12) { hit(s, 1, 0.95f); if (clap || st == BeatStyle.HOUSE) hit(s, 2, 0.8f) }
                }
                // ghosts: quiet snares in the cracks
                if (st == BeatStyle.BREAKS || st == BeatStyle.BOOM_BAP || st == BeatStyle.LOFI)
                    if (b in setOf(7, 9, 15) && chance(0.35f * d)) hit(s, 1, 0.35f, prob = if (p.probSpread) 0.6f else 1f)
                // hats
                val open = openOff && b % 4 == 2 && st != BeatStyle.TRAP
                if (open) hit(s, 4, 0.8f)
                else if (st == BeatStyle.LOFI && b % 2 == 1 && chance(0.6f)) Unit         // lazy, gappy hats
                else if (b % hatRate == 0) {
                    val ratchet = if (b in rollSteps) pick(2, 3, 3, 4) else if (hatRate == 1 && chance(0.06f * d)) 2 else 1
                    hit(s, 3, if (b % 4 == 0) 0.85f else 0.55f + rnd.nextFloat() * 0.15f, ratchet = ratchet)
                }
                if (st == BeatStyle.TRAP && b == 14 && chance(0.4f)) hit(s, 4, 0.7f)
                // colour
                if (chance(0.06f * d)) hit(s, 5, 0.75f, prob = 0.75f)
                if (chance((if (st == BeatStyle.HOUSE || st == BeatStyle.DEMBOW) 0.14f else 0.07f) * d)) hit(s, 6, 0.7f, prob = if (p.probSpread) 0.5f else 1f)
            }
            if (bar == 0 && chance(0.4f)) hit(0, 7, 0.7f)
        }
        return out.sortedBy { it.tick }
    }

    // ------------------------------------------------------------------ synth melody

    enum class MelodyStyle(val label: String) { HOOK("HOOK"), ARP("ARP"), BASS("BASSLINE"), RIFF("RIFF") }

    /**
     * Melody (synth track), always in [key]. Each roll picks a style and a chord movement:
     *  - HOOK: a 4/8-step motif sung over the chords, answered differently at the end
     *  - ARP: chord tones in a rolled order (up / down / up-down / shuffled) at 1/16 or 1/8
     *  - BASSLINE: an octave down, roots and fifths with octave pops and approach notes
     *  - RIFF: a stepwise motif repeated near-exactly (loop-friendly)
     * Patterns repeat with variation, because loops that repeat are what make a beat.
     */
    fun melody(steps: Int, p: GenParams, key: MusicalKey, base: Int, avoid: MelodyStyle? = null, force: MelodyStyle? = null): List<NoteEvent> {
        val st = force ?: MelodyStyle.entries.filter { it != avoid }.let { it[rnd.nextInt(it.size)] }
        melodyStyle = st
        val size = Scales.steps(key.mode).size
        val span = size * p.rangeOctaves.coerceIn(1, 3)
        val d = p.density.coerceIn(0.05f, 1f)
        // chord roots (scale degrees), one per chord length
        val progs = if (size == 7) listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 5, 3, 4), intArrayOf(0, 3, 4, 4), intArrayOf(0, 5, 2, 6),
            intArrayOf(0, 4, 5, 3), intArrayOf(0, 6, 5, 6), intArrayOf(0, 3, 0, 4)) else listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, fourth(key), 0))
        val prog = if (chance(0.25f)) progs[0] else progs[rnd.nextInt(progs.size)]
        val chordLen = if (steps <= 16) 8.coerceAtMost(steps) else steps / 4
        fun chordAt(s: Int) = prog[(s / chordLen.coerceAtLeast(1)) % prog.size]
        style = st.label + if (prog.any { it != 0 }) "" else " · DRONE"

        val out = ArrayList<NoteEvent>()
        fun note(s: Int, deg: Int, gate: Float, accent: Boolean = s % 4 == 0) {
            out += NoteEvent(tick = s * STEP_TICKS, lengthTicks = (STEP_TICKS * gate).roundToInt().coerceAtLeast(30),
                note = Scales.noteOf(key, base, deg), velocity = vel(p.humanize, accent),
                probability = if (s == 0) 1f else prob(p.probSpread))
        }
        when (st) {
            MelodyStyle.ARP -> {
                val rate = if (d > 0.55f) 1 else pick(1, 2)
                val order = rnd.nextInt(4)
                val octs = if (span > size) 2 else pick(1, 2)
                val gate = pick(0.5f, 0.75f, 1f)
                val shuf = (0 until 3 * octs).shuffled(rnd)        // one rolled order, kept for the whole loop
                var i = 0
                for (s in 0 until steps step rate) {
                    if (s != 0 && d < 0.5f && chance(0.5f - d)) { i++; continue }  // gaps at low density
                    val tones = triad(key, chordAt(s)).let { t -> (0 until octs).flatMap { o -> t.map { it + o * size } } }
                    val seq = when (order) { 0 -> tones; 1 -> tones.reversed(); 2 -> tones + tones.reversed().drop(1).dropLast(1); else -> shuf.map { tones[it] } }
                    note(s, seq[i % seq.size], gate * rate)
                    i++
                }
            }
            MelodyStyle.BASS -> {
                val r = rhythm(if (steps >= 8) 8 else steps, (d * 0.9f).coerceIn(0.15f, 0.8f), true)
                for (s in 0 until steps) {
                    val chord = chordAt(s)
                    val nextChord = chordAt(s + 1)
                    if (!r[s % r.size] && !(s == 0 && p.anchorRoot)) continue
                    val deg = when {
                        nextChord != chord && s % chordLen.coerceAtLeast(1) == chordLen - 1 -> nextChord - size + pick(-1, 1)   // approach
                        chance(0.6f) || s == 0 -> chord - size                                                            // root, low
                        chance(0.5f) -> chord                                                                              // octave pop
                        else -> triad(key, chord)[2] - size                                                                // fifth
                    }
                    note(s, deg, pick(0.75f, 1f, 1.5f, 2f))
                }
            }
            MelodyStyle.HOOK, MelodyStyle.RIFF -> {
                val m = if (steps >= 8) (if (st == MelodyStyle.RIFF) pick(4, 8) else 8).coerceAtMost(steps) else steps
                val rhy = rhythm(m, d, p.anchorRoot)
                // motif pitches relative to the chord root
                val contour = rnd.nextInt(5)          // 0 up, 1 down, 2 arch, 3 valley, 4 wander
                val motif = IntArray(m)
                var deg = if (p.anchorRoot) 0 else pick(0, 2, 4)
                var k = 0; val hits = rhy.count { it }.coerceAtLeast(1)
                for (i in 0 until m) {
                    if (!rhy[i]) continue
                    val t = k.toFloat() / hits
                    val dir = when (contour) { 0 -> 1; 1 -> -1; 2 -> if (t < 0.5f) 1 else -1; 3 -> if (t < 0.5f) -1 else 1; else -> pick(-1, 1) }
                    if (k > 0) deg += dir * (if (st == MelodyStyle.RIFF) pick(1, 1, 2) else pick(1, 2, 2, 3)) + (if (chance(0.15f)) pick(-size / 2, size / 2) else 0)
                    if (st == MelodyStyle.HOOK && i % 4 == 0 && chance(0.6f)) deg = nearestChordTone(key, deg)
                    deg = deg.coerceIn(-2, span)
                    motif[i] = deg; k++
                }
                val gates = FloatArray(m) { pick(0.5f, 0.75f, 1f, 1f, 2f) }
                for (s in 0 until steps) {
                    val i = s % m
                    if (!rhy[i]) continue
                    val rep = s / m; val lastRep = (steps - 1) / m
                    var dg = motif[i] + (if (st == MelodyStyle.HOOK) chordAt(s) else 0)
                    // the answer phrase: the last repeat resolves differently (home, or up to the 5th)
                    if (rep == lastRep && rep > 0 && i >= m / 2) {
                        dg = if (i == m - 1 || rhy.indexOfLast { it } == i) pick(0, triad(key, 0)[2]) else dg + pick(-1, 1, 2)
                    } else if (st == MelodyStyle.RIFF && rep > 0 && chance(0.08f)) dg += pick(-1, 1)
                    note(s, Math.floorMod(dg + 2, span + 2) - 2, gates[i])
                }
                style = (if (st == MelodyStyle.HOOK) "HOOK" else "RIFF $m") + if (prog.any { it != 0 }) "" else " · DRONE"
            }
        }
        return out.sortedBy { it.tick }
    }

    /** Degrees of the triad on [root] (root, 3rd, 5th) in this scale, found by interval so any mode works. */
    private fun triad(key: MusicalKey, root: Int): IntArray {
        val r = Scales.noteOf(key, 60, root)
        var third = root + 2; var fifth = root + 4
        for (dd in 1..12) { val iv = Scales.noteOf(key, 60, root + dd) - r; if (iv in 3..4) { third = root + dd; break } }
        for (dd in 1..12) { val iv = Scales.noteOf(key, 60, root + dd) - r; if (iv == 7) { fifth = root + dd; break } else if (iv > 7) { fifth = root + dd - 1; break } }
        return intArrayOf(root, third, fifth)
    }
    private fun nearestChordTone(key: MusicalKey, deg: Int): Int {
        val t = triad(key, 0); val size = Scales.steps(key.mode).size
        val oct = Math.floorDiv(deg, size); val local = Math.floorMod(deg, size)
        val best = (t.toList() + (t[0] + size)).minBy { abs(it - local) }
        return oct * size + best
    }
    private fun fourth(key: MusicalKey): Int {
        for (dd in 1..12) if (Scales.noteOf(key, 60, dd) - Scales.noteOf(key, 60, 0) >= 5) return dd
        return 0
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
