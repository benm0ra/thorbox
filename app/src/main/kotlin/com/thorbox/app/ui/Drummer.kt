// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.model.DRUM_SLOTS
import com.thorbox.model.DrumModel
import com.thorbox.model.DrumVoice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * BIG DAWG, the drummer: a stocky, unbothered bulldog in a fitted cap, a hoodie, baggy jeans, fresh
 * sneakers and a gold chain, playing on a bodega corner at night. He plays exactly the kit the drum
 * synth has loaded.
 *
 * The kit is a real right-handed kit, seen from the audience:
 *  - Kick in front, centre, front head facing you (spurs out, claws round the hoop). The rack tom
 *    sits on the kick's tom mount; the snare stands between his knees, just to his left (your right).
 *  - Hi-hat to his left (your right), its pedal under his left foot; crash high on a boom over it.
 *  - Floor tom on three legs to his right (your left), the ride on a boom stand over it.
 *  - He plays open-handed (a real style, and no crossed arms): the left paw (your right) keeps time
 *    on the hats and crash, the right paw (your left) plays snare, toms, ride and cowbell.
 *
 * The kit IS the sound:
 *  - Only the pieces the kit's voices map to are on stage (no tom voice, no tom; a cymbal voice called
 *    RIDE is the ride, any other cymbal is the crash; a cowbell clamps to the kick hoop; ZAP is a
 *    little electronic pad on the crash stand; SHAKE puts a shaker in his left paw).
 *  - Each drum's size follows its TUNE (tuned down = bigger shell), its glow and ring last as long as
 *    its DECAY, louder LEVEL = brighter flash, DRIVE roughens the ripple, and the hats' openness
 *    follows the voice that hit them (his left foot lifts off the pedal for an open hat).
 *  - Every piece flashes in the colour of the slot that hit it (same colours as the pads and grid rows).
 *
 * Character: half-lidded and chill, nods on the beat, jowls and ears bounce on the kick, the chain
 * swings, his hat foot taps the beat. Big accents get a grin (and the gold tooth). Stopped for a
 * while, he naps (z). All original artwork; no allocation per frame (reused Paths, plain floats).
 * Stage: 400 × 280 virtual units (floor at y = 284), scaled uniformly; the street fills the rest.
 */

private enum class Piece { KICK, SNARE, RIM, HIHAT, RACK_TOM, FLOOR_TOM, CYMBAL, RIDE, COWBELL, CLAP, ZAP, SHAKER }

private class Hand(val restX: Float, val restY: Float) {
    var fromX = restX; var fromY = restY
    var at = -10f
    var piece = Piece.KICK
    // the next sequenced hit for this paw (read ahead from the pattern), so the stroke starts early
    var nextAt = -1f; var nextX = 0f; var nextY = 0f
    /** 0 … 1 through the wind-up into [nextAt]; 0 when nothing is coming. */
    var antic = 0f
}

/** When two hits land on one paw at once, the drum with the higher rank keeps the stick (the other still lights). */
private fun rank(p: Piece) = when (p) { Piece.SNARE, Piece.RIM -> 3; Piece.RACK_TOM, Piece.FLOOR_TOM -> 2; else -> 1 }

private class DrummerState {
    /** Your-left paw = Big Dawg's RIGHT hand (snare, toms, ride, cowbell). */
    val left = Hand(194f, 192f)
    /** Your-right paw = his LEFT hand (hats, crash, ZAP). */
    val right = Hand(262f, 172f)
    val pieceHit = FloatArray(Piece.entries.size) { -10f }
    val pieceSlot = IntArray(Piece.entries.size)
    val pieceVel = FloatArray(Piece.entries.size) { 1f }
    /** The voice that defines each piece's look (size, decay…), refreshed from the kit every frame. */
    val pieceVoice = arrayOfNulls<DrumVoice>(Piece.entries.size)
    val present = BooleanArray(Piece.entries.size)
    val firstSlot = IntArray(Piece.entries.size)
    val seen = FloatArray(DRUM_SLOTS) { -10f }
    var hatOpenness = 0f
    var grinUntil = -10f
    var lastActivity = 0f
    var nextBlink = 2f
    var kickNext = -1f
    val path = Path()
    val bricks = Path(); var bricksKey = Float.NaN
    val gate = Path(); var gateKey = Float.NaN
    // his rig: weight and follow-through (stepped once per frame)
    var lastT = 0f
    val bodyS = Spring(4.5f, 0.42f)       // the whole dog drops on the kick and bounces back
    val headS = Spring(5.5f, 0.4f)        // the nod: a real backbeat nod lands on the snare
    val jowlS = Spring(2.6f, 0.2f)        // jowls lag the head and wobble
    val earS = Spring(3.2f, 0.22f)        // ears flop a beat late
    val capS = Spring(6f, 0.35f)          // the cap lifts off a hair on a hard drop
    val chainS = Spring(1.3f, 0.15f)      // the chain is a pendulum
    val leanS = Spring(2.8f, 0.55f)       // leans into the side of the kit he's playing
    var chainFlip = false
}

// Big Dawg's colours: his own, not the theme's (he looks the same on every theme).
private val COAT = Color(0xFFD9A066)
private val COAT_DARK = Color(0xFFB07A3E)
private val BLAZE = Color(0xFFF3E8DA)
private val MASK = Color(0xFF4A3426)
private val NOSE = Color(0xFF221814)
private val GOLD = Color(0xFFE6B93C)
private val GOLD_DARK = Color(0xFFB8892A)
private val HOODIE = Color(0xFF8A2332)
private val HOODIE_DARK = Color(0xFF5C1520)
private val HOODIE_LIGHT = Color(0xFFB04A57)
private val DENIM = Color(0xFF3C5A86)
private val DENIM_DARK = Color(0xFF263B5C)
private val SNEAKER = Color(0xFFF1F1EE)
private val SNEAKER_SHADE = Color(0xFFC9CAD0)
private val SNEAKER_STRIPE = Color(0xFF2FBF9F)
private val GUM = Color(0xFFB9824A)

// The kit's hardware and the street: fixed colours too (real drums don't change with the theme).
private val CHROME = Color(0xFFC9CED6)
private val CHROME_DARK = Color(0xFF7D838E)
private val HEAD_SKIN = Color(0xFFE6E0D2)
private val BRASS = Color(0xFFC79A3A)
private val BRASS_LIGHT = Color(0xFFE8C46A)
private val BRASS_DARK = Color(0xFF8E6A22)
private val STICK_WOOD = Color(0xFFDDBF83)
private val STICK_DARK = Color(0xFF8A6A3A)
private val BRICK = Color(0xFF6E3528)
private val MORTAR = Color(0xFF3A2019)
private val STONE = Color(0xFF7B5843)
private val TRIM = Color(0xFFD7C6A4)
private val IRON = Color(0xFF16161B)
private val STEEL = Color(0xFF5E6572)
private val STEEL_DARK = Color(0xFF3B404A)
private val CONCRETE = Color(0xFF5C5C64)
private val CONCRETE_DARK = Color(0xFF45454D)
private val CURB = Color(0xFF85858D)
private val ASPHALT = Color(0xFF1C1C22)
private val HYDRANT = Color(0xFFC4352C)
private val NEON_PINK = Color(0xFFFF4FA3)
private val NEON_CYAN = Color(0xFF4FE3FF)
private val SPRAY_ORANGE = Color(0xFFFF8A2A)
private val SPRAY_INK = Color(0xFF15121C)
private val CRATE = Color(0xFF2F64C8)
private val CRATE_DARK = Color(0xFF1E4590)
private const val ANTIC = 0.14f          // seconds of wind-up before a sequenced hit
private const val FLOOR = 284f

private val OUTLINE_THICK = Stroke(14f, join = StrokeJoin.Round)
private val NEON_GLOW = Stroke(10f, join = StrokeJoin.Round)
private val NEON_WASH = androidx.compose.ui.graphics.Brush.radialGradient(
    listOf(Color(0x2EFF4FA3), Color(0x00FF4FA3)), center = Offset(300f, 32f), radius = 90f)

private fun pieceFor(v: DrumVoice, slot: Int, toms: IntArray): Piece = when (v.model) {
    DrumModel.KICK -> Piece.KICK
    DrumModel.SNARE -> Piece.SNARE
    DrumModel.RIM -> Piece.RIM
    DrumModel.CLAP -> Piece.CLAP
    DrumModel.HAT -> Piece.HIHAT
    // Two (or more) toms: the higher-tuned one is the rack tom, the lower the floor tom. One tom: by its tune.
    DrumModel.TOM -> if (toms[1] < 0) (if (v.tune < -5f) Piece.FLOOR_TOM else Piece.RACK_TOM) else if (slot == toms[0]) Piece.RACK_TOM else Piece.FLOOR_TOM
    DrumModel.CYMBAL -> if (v.name.contains("RIDE", ignoreCase = true)) Piece.RIDE else Piece.CYMBAL
    DrumModel.COWBELL -> Piece.COWBELL
    DrumModel.ZAP -> Piece.ZAP
    DrumModel.SHAKER -> Piece.SHAKER
}

/** Which paw plays a piece: true = your-left paw (his right hand). Open-handed, so the arms never cross. */
private fun onLeftPaw(p: Piece) = when (p) {
    Piece.SNARE, Piece.RIM, Piece.RACK_TOM, Piece.FLOOR_TOM, Piece.RIDE, Piece.COWBELL -> true
    else -> false
}

/** Where the stick TIP lands on each piece (stage units). */
private fun strikeX(p: Piece): Float = when (p) {
    Piece.SNARE -> 230f; Piece.RIM -> 214f; Piece.HIHAT -> 298f; Piece.RACK_TOM -> 210f; Piece.FLOOR_TOM -> 124f
    Piece.CYMBAL -> 284f; Piece.RIDE -> 112f; Piece.COWBELL -> 164f; Piece.ZAP -> 330f; else -> 200f
}
private fun strikeY(p: Piece): Float = when (p) {
    Piece.SNARE -> 200f; Piece.RIM -> 204f; Piece.HIHAT -> 144f; Piece.RACK_TOM -> 168f; Piece.FLOOR_TOM -> 198f
    Piece.CYMBAL -> 96f; Piece.RIDE -> 120f; Piece.COWBELL -> 182f; Piece.ZAP -> 192f; else -> 140f
}

/** Size from tune: −24 st ≈ 1.35×, +24 st ≈ 0.75×. */
private fun sizeOf(v: DrumVoice?) = if (v == null) 1f else 2f.pow(-v.tune / 48f).coerceIn(0.75f, 1.35f)
/** Seconds the piece keeps ringing visually: its decay, within reason. */
private fun ringSec(v: DrumVoice?) = ((v?.decayMs ?: 300f) / 1000f).coerceIn(0.08f, 2.5f)
/** Flash strength from velocity and level. */
private fun hitGain(vel: Float, v: DrumVoice?) = (vel * 10f.pow((v?.levelDb ?: 0f) / 40f)).coerceIn(0.3f, 1.25f)

@Composable
fun Drummer(state: AppState, modifier: Modifier = Modifier) {
    val st = remember { DrummerState() }
    val drums = state.drums
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 64)
    val toms = remember { IntArray(2) }
    val logo = remember { TextStyle(color = Color(0xFFF4F4F5), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val spray = remember { TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = (-1).sp) }
    val neon = remember { TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
    Canvas(modifier) {
        tel.frame.longValue                                   // redraw every frame, never recompose
        val now = tel.timeSec
        val voices = drums.kit.value.voices
        KitFinish.pick(drums.kit.value.name)

        // ---- what's in the kit right now ----
        toms[0] = -1; toms[1] = -1
        var hiTune = -99f
        for (s in voices.indices) if (voices[s].model == DrumModel.TOM) {
            if (toms[0] < 0 || voices[s].tune > hiTune) { if (toms[0] >= 0) toms[1] = toms[0]; toms[0] = s; hiTune = voices[s].tune }
            else if (toms[1] < 0) toms[1] = s
        }
        // Each piece shows the voice that last hit it (or, until then, the first voice that maps to it).
        st.present.fill(false)
        st.firstSlot.fill(-1)
        val n = min(DRUM_SLOTS, voices.size)
        for (s in 0 until n) {
            val p = pieceFor(voices[s], s, toms).ordinal
            st.present[p] = true
            if (st.firstSlot[p] < 0) st.firstSlot[p] = s
        }
        for (p in st.pieceVoice.indices) {
            val owner = st.pieceSlot[p]
            val ownerOk = owner < n && pieceFor(voices[owner], owner, toms).ordinal == p
            if (!ownerOk && st.firstSlot[p] >= 0) st.pieceSlot[p] = st.firstSlot[p]
            st.pieceVoice[p] = if (st.present[p]) voices[st.pieceSlot[p]] else null
        }
        if (st.present[Piece.RIM.ordinal]) st.present[Piece.SNARE.ordinal] = true     // a rim needs a drum to click on

        // ---- route new hits (live or sequenced) to pieces and paws ----
        for (s in 0 until n) {
            val t = drums.hitTime[s]
            if (t == st.seen[s]) continue
            st.seen[s] = t
            if (now - t > 0.25f) continue                     // stale (e.g. first frame)
            val v = voices[s]
            val p = pieceFor(v, s, toms)
            st.pieceHit[p.ordinal] = t; st.pieceSlot[p.ordinal] = s; st.pieceVel[p.ordinal] = drums.hitVel[s]; st.pieceVoice[p.ordinal] = v
            if (p == Piece.HIHAT) st.hatOpenness = ((v.decayMs - 60f) / 500f).coerceIn(0f, 1f)
            if (drums.hitVel[s] >= 0.92f && (p == Piece.SNARE || p == Piece.CYMBAL || p == Piece.CLAP)) st.grinUntil = t + 0.5f
            st.lastActivity = t
            val vel = drums.hitVel[s].coerceIn(0.3f, 1f)
            when (p) {
                // (impulses tuned offline at 90 BPM boom bap: snare nod ≈ 5 units, kick drop ≈ 2, jowls trail ≈ 4)
                Piece.KICK -> { st.bodyS.kick(110f * vel); st.chainS.kick(if (st.chainFlip) 40f else -40f); st.chainFlip = !st.chainFlip }
                Piece.SNARE, Piece.CLAP, Piece.RIM -> st.headS.kick(260f * vel)
                Piece.CYMBAL -> { st.headS.kick(-200f * vel); st.bodyS.kick(-55f * vel) }      // a crash: head back, chest up
                Piece.HIHAT, Piece.RIDE -> st.headS.kick(30f * vel)
                Piece.RACK_TOM, Piece.FLOOR_TOM -> { st.headS.kick(110f * vel); st.bodyS.kick(30f * vel) }
                else -> Unit
            }
            when (p) {
                Piece.KICK -> Unit                                         // the foot does it
                Piece.CLAP -> { st.left.at = t; st.right.at = t; st.left.fromX = 192f; st.left.fromY = 150f; st.right.fromX = 208f; st.right.fromY = 150f }
                else -> {
                    // Each piece belongs to one paw. If that paw is already hitting something more
                    // important, this piece just lights up.
                    val own = if (onLeftPaw(p)) st.left else st.right
                    val hand = if (t - own.at >= 0.07f || rank(p) > rank(own.piece)) own else null
                    if (hand != null) { hand.at = t; hand.fromX = strikeX(p); hand.fromY = strikeY(p); hand.piece = p }
                }
            }
        }

        // ---- read ahead in the pattern: each paw starts its stroke ~140 ms before its next hit ----
        val g = state.groove
        st.left.nextAt = -1f; st.right.nextAt = -1f; st.kickNext = -1f
        if (g.transport[1] != 0.0) {
            val clip = g.clips.value.getOrNull(drums.track)
            val len = clip?.lengthTicks?.toDouble() ?: 0.0
            if (clip != null && len > 0.0) {
                val pos = g.loopTick % len
                val tps = g.groove.value.bpm * com.thorbox.model.PPQN / 60.0
                for (e in clip.notes) {
                    if (e.note !in voices.indices) continue
                    var d = e.tick - pos; if (d < 0) d += len
                    val sec = (d / tps).toFloat()
                    if (sec <= 0.004f || sec > ANTIC) continue
                    val p = pieceFor(voices[e.note], e.note, toms)
                    if (p == Piece.KICK) { if (st.kickNext < 0f || now + sec < st.kickNext) st.kickNext = now + sec; continue }
                    if (p == Piece.CLAP || p == Piece.SHAKER) continue
                    val hand = if (onLeftPaw(p)) st.left else st.right
                    if (hand.nextAt < 0f || now + sec < hand.nextAt) { hand.nextAt = now + sec; hand.nextX = strikeX(p); hand.nextY = strikeY(p) }
                }
            }
        }

        // virtual stage 400 × 280 (y 8..288): cap top to floor, no dead band above
        val sc = min(size.width / 400f, size.height / 280f)
        val ox = (size.width - 400f * sc) / 2f; val oy = (size.height - 280f * sc) / 2f - 8f * sc
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            val playing = state.groove.transport[1] != 0.0
            val beatPhase = ((state.groove.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat()
            // ---- his rig: the hits above kicked the springs; here they settle (follow-through) ----
            val fdt = (now - st.lastT).coerceIn(0f, 0.05f); st.lastT = now
            val base = if (playing) abs(sin(PI.toFloat() * beatPhase)) * 1.5f else sin(now * 1.1f) * 1.2f   // a light groove + breathing
            val bodyDrop = st.bodyS.step(0f, fdt)
            val headNod = st.headS.step(0f, fdt)
            val nod = base + headNod
            val headTotal = bodyDrop + nod
            val jowlLag = (st.jowlS.step(headTotal, fdt) - headTotal).coerceIn(-6f, 6f)
            val earLag = (st.earS.step(headTotal, fdt) - headTotal).coerceIn(-7f, 7f)
            val capLag = (headTotal - st.capS.step(headTotal, fdt)).coerceIn(0f, 5f)
            // lean toward whichever paw is working (hats + crash one way, snare + toms the other)
            val leanTarget = 4f * (envFor(now - st.right.at, 0.5f) - envFor(now - st.left.at, 0.5f)) +
                3f * (st.right.antic - st.left.antic)
            val leanX = st.leanS.step(leanTarget, fdt)
            val chainSwing = st.chainS.step(leanX * 1.2f, fdt).coerceIn(-9f, 9f)
            val kickK = env(now - st.pieceHit[Piece.KICK.ordinal], 14f)
            val sleepy = !playing && now - st.lastActivity > 8f

            // ---- the corner: brick wall + bodega with a graffiti'd gate, neon, boombox, hydrant ----
            // what the street hears: each part of the kit drives its own bit of the scene
            val snareK = maxOf(env(now - st.pieceHit[Piece.SNARE.ordinal], 9f), env(now - st.pieceHit[Piece.CLAP.ordinal], 9f))
            val hatK = env(now - st.pieceHit[Piece.HIHAT.ordinal], 10f)
            val crashK = maxOf(envFor(now - st.pieceHit[Piece.CYMBAL.ordinal], 1.0f), 0.4f * envFor(now - st.pieceHit[Piece.RIDE.ordinal], 0.6f))
            val level = state.level.floatValue.coerceIn(0f, 1f)
            street(st, -ox / sc, (size.width - ox) / sc, -oy / sc, (size.height - oy) / sc, now, playing, beatPhase, kickK, tm, spray, neon,
                snareK, hatK, crashK, level)

            // ---- cymbals + hats: beside and above him ----
            if (st.present[Piece.RIDE.ordinal]) rideStand(st, now)
            if (st.present[Piece.CYMBAL.ordinal]) crashStand(st, now)
            if (st.present[Piece.ZAP.ordinal]) zapPad(st, now)
            val hatOpen = hatOpenNow(st, now)
            val footTap = if (playing) 3f * sin(PI.toFloat() * beatPhase) else 0f
            val footLift = (footTap + 7f * hatOpen).coerceAtMost(8f)
            if (st.present[Piece.HIHAT.ordinal]) hihat(st, now, hatOpen, footLift)

            // ---- Big Dawg ----
            val bodyY = base * 0.6f + bodyDrop
            legs(bodyY, footLift, kickK, st.present[Piece.HIHAT.ordinal])
            withTransform({ translate(leanX, 0f) }) { body(st, bodyY, chainSwing) }
            // Arms go BEHIND the drums (the kit hides them where it's in front); only paws + sticks
            // are drawn over the kit, so nothing crosses the shells.
            val beatL = if (playing) -6f * (1f - abs(sin(PI.toFloat() * ((beatPhase + 0.5f) % 1f)))) else 0f
            val beatR = if (playing) -6f * (1f - abs(sin(PI.toFloat() * beatPhase))) else 0f
            // pawPos is where the stick tip is heading; reach() keeps the paw near the shoulder
            // and lets the stick cover the rest, so arms never sweep across the kit.
            val lt = pawPos(st.left, now, beatL); val rt = pawPos(st.right, now, beatR)
            // shoulders at the sides of his frame, below the jowls
            val shY = 146f + bodyY
            val lp = reach(166f + leanX, shY, lt); val lx = lp.x; val ly = lp.y
            val rp = reach(234f + leanX, shY, rt); val rx = rp.x; val ry = rp.y
            arm(166f + leanX, shY, lx, ly, mirror = false)
            arm(234f + leanX, shY, rx, ry, mirror = true)
            // the head leans a touch further than the body, and tilts with it
            withTransform({ translate(leanX * 1.3f, 0f); rotate(leanX * 1.1f, Offset(200f, 130f + bodyY)) }) {
                head(st, now, bodyY + nod * 0.5f + headNod * 0.5f, sleepy, kickK, jowlLag, earLag, capLag)
            }

            // ---- the drums, back to front ----
            if (st.present[Piece.FLOOR_TOM.ordinal]) floorTom(st, now)
            if (st.present[Piece.SNARE.ordinal]) snare(st, now)
            if (st.present[Piece.RACK_TOM.ordinal]) rackTom(st, now)
            if (st.present[Piece.COWBELL.ordinal]) cowbell(st, now)
            if (st.present[Piece.KICK.ordinal]) kick(st, now, kickK, tm, logo)

            // ---- paws + sticks on top; between hits they ride the beat ----
            val shaking = st.present[Piece.SHAKER.ordinal]
            val shakeNow = shaking && now - st.pieceHit[Piece.SHAKER.ordinal] in 0f..0.6f
            paw(st.left, lx, ly, lt, now, mirror = false, stick = true)
            paw(st.right, rx, ry, rt, now, mirror = true, stick = !shakeNow)
            if (shakeNow) shaker(st, now, rx, ry)
            if (now - st.pieceHit[Piece.CLAP.ordinal] in 0f..0.25f) clapBurst(st, now)
        }
    }
}

/** 1 at the hit, falling off exponentially (rate per second). 0 before the hit. */
private fun env(dt: Float, rate: Float) = if (dt < 0f || dt > 4f) 0f else exp(-dt * rate)
/** Like env, but lasting about [sec] seconds (to ~5%). */
private fun envFor(dt: Float, sec: Float) = env(dt, 3f / sec)

private fun slotColor(st: DrummerState, p: Piece) = Tb.Pads[st.pieceSlot[p.ordinal] % Tb.Pads.size]

/** Expanding ripple on a hit, lasting as long as the voice's decay; DRIVE makes it jagged. */
private fun DrawScope.ripple(st: DrummerState, p: Piece, cx: Float, cy: Float, rx: Float, ry: Float, now: Float) {
    val dt = now - st.pieceHit[p.ordinal]
    val v = st.pieceVoice[p.ordinal]
    val dur = (ringSec(v) * 0.6f).coerceIn(0.15f, 0.9f)
    if (dt < 0f || dt > dur) return
    val k = dt / dur
    val grow = 1f + k * 0.7f
    val c = slotColor(st, p).copy(alpha = (1f - k) * 0.9f * hitGain(st.pieceVel[p.ordinal], v).coerceAtMost(1f))
    val drive = v?.drive ?: 0f
    if (drive < 0.25f) {
        drawOval(c, Offset(cx - rx * grow, cy - ry * grow), Size(2 * rx * grow, 2 * ry * grow), style = Stroke(3f))
    } else {
        val path = st.path; path.reset()
        val n = 28
        for (i in 0..n) {
            val a = 2f * PI.toFloat() * i / n
            val j = 1f + drive * 0.18f * (if (i % 2 == 0) 1f else -1f)
            val x = cx + cos(a) * rx * grow * j; val y = cy + sin(a) * ry * grow * j
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c, style = Stroke(3f))
    }
}

// ----------------------------------------------------------------------------- kit

/**
 * A drum, three-quarter view: shell lacquered in the voice's colour (so you can tell which sound it
 * is), chrome lugs with tension rods, top and bottom hoops, and a coated head that flexes (squash)
 * and lights on a hit.
 */
private fun DrawScope.drumShell(cx: Float, top: Float, w: Float, h: Float, depth: Float, squash: Float, accent: Color, flash: Float, finish: Color = KitFinish.color) {
    val sh = h * (1f - 0.35f * squash)
    val l = cx - w / 2
    drawOval(lerp(finish, Color.Black, 0.35f), Offset(l, top + depth - sh / 2), Size(w, sh))   // bottom edge
    drawRect(finish, Offset(l, top), Size(w, depth))
    drawRect(Color.White.copy(alpha = 0.13f), Offset(cx - w * 0.30f, top), Size(w * 0.07f, depth))         // lacquer shine
    drawRect(Color.Black.copy(alpha = 0.18f), Offset(cx + w * 0.30f, top), Size(w * 0.20f, depth))         // shade
    if (KitFinish.sparkle) for (i in 0 until (w * depth / 60f).toInt().coerceAtMost(40)) {                  // sparkle flake
        val fx = l + ((i * 37 + (cx * 7).toInt()) % 100) / 100f * w; val fy = top + ((i * 53) % 97) / 97f * depth
        drawCircle(Color.White.copy(alpha = 0.35f), 0.7f, Offset(fx, fy))
    }
    // lugs: tension casings round the visible side (bigger towards the middle = they face you)
    val nl = if (w > 54f) 5 else 3
    for (i in 0 until nl) {
        val u = (i + 0.5f) / nl
        val face = sin(PI.toFloat() * u)
        val lx = l + u * w
        drawRoundRect(CHROME, Offset(lx - 1.6f - face, top + depth * 0.22f), Size(3.2f + 2f * face, depth * 0.56f), CornerRadius(2f))
        drawLine(CHROME_DARK, Offset(lx, top + 1f), Offset(lx, top + depth * 0.22f), 1.2f)               // tension rods
        drawLine(CHROME_DARK, Offset(lx, top + depth * 0.78f), Offset(lx, top + depth - 1f), 1.2f)
    }
    drawOval(CHROME_DARK, Offset(l, top + depth - sh / 2), Size(w, sh), style = Stroke(2.2f))             // bottom hoop
    val hy = top - sh / 2 + squash * 2f
    drawOval(lerp(HEAD_SKIN, accent, 0.12f + 0.7f * flash), Offset(l, hy), Size(w, sh))                    // coated head
    drawOval(Color.White.copy(alpha = 0.18f * (1f - flash)), Offset(cx - w * 0.28f, hy + sh * 0.18f), Size(w * 0.3f, sh * 0.35f))   // sheen
    drawOval(CHROME, Offset(l - 1.5f, hy - 1f), Size(w + 3f, sh + 2f), style = Stroke(3f))                 // top hoop
}

/** A tripod stand: centre tube from [topY] down, three legs splayed onto the floor. */
private fun DrawScope.tripod(x: Float, topY: Float, spread: Float = 16f) {
    val knee = FLOOR - 22f
    drawLine(CHROME_DARK, Offset(x, topY), Offset(x, knee), 3f)
    drawLine(CHROME, Offset(x - 0.8f, topY), Offset(x - 0.8f, knee), 0.8f)                     // tube highlight
    drawLine(CHROME_DARK, Offset(x, knee), Offset(x - spread, FLOOR), 2.4f, cap = StrokeCap.Round)
    drawLine(CHROME_DARK, Offset(x, knee), Offset(x + spread, FLOOR), 2.4f, cap = StrokeCap.Round)
    drawLine(CHROME_DARK.copy(alpha = 0.6f), Offset(x, knee), Offset(x + spread * 0.25f, FLOOR - 3f), 2f, cap = StrokeCap.Round)
    drawCircle(CHROME, 2.4f, Offset(x, knee))
    drawCircle(IRON, 1.8f, Offset(x - spread, FLOOR)); drawCircle(IRON, 1.8f, Offset(x + spread, FLOOR))   // rubber feet
}

/** A boom cymbal stand: tripod, a height tube up to [jointY], the boom arm out to the cymbal. */
private fun DrawScope.boomStand(x: Float, jointY: Float, cx: Float, cy: Float) {
    tripod(x, jointY, 17f)
    drawLine(CHROME_DARK, Offset(x, jointY), Offset(cx, cy + 3f), 2.6f, cap = StrokeCap.Round)
    drawCircle(CHROME, 3f, Offset(x, jointY))                                                  // tilter / boom clamp
    drawCircle(CHROME_DARK, 3f, Offset(x, jointY), style = Stroke(1f))
}

private fun DrawScope.snare(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.SNARE.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.SNARE.ordinal]
    val c = slotColor(st, Piece.SNARE)
    val cx = 240f; val top = 202f; val w = 58f * s; val depth = 15f * s
    // snare stand: tripod + a basket whose three arms grip the bottom hoop
    tripod(cx, top + depth + 8f, 17f)
    drawLine(CHROME, Offset(cx - w * 0.42f, top + depth + 1f), Offset(cx, top + depth + 8f), 2f)
    drawLine(CHROME, Offset(cx + w * 0.42f, top + depth + 1f), Offset(cx, top + depth + 8f), 2f)
    drawLine(CHROME_DARK, Offset(cx, top + depth + 2f), Offset(cx, top + depth + 8f), 2f)
    drumShell(cx, top, w, 18f * s, depth, env(dt, 30f), c, envFor(dt, ringSec(v) * 0.7f) * hitGain(st.pieceVel[Piece.SNARE.ordinal], v))
    // throw-off lever on the side, and the wires rattling under the bottom head for as long as it rings
    drawRoundRect(CHROME, Offset(cx + w / 2f - 7f, top + depth * 0.25f), Size(4f, depth * 0.5f), CornerRadius(1.5f))
    val rattle = envFor(dt, ringSec(v))
    if (rattle > 0.05f) for (i in 0 until 3) {
        val y = top + depth + 2f + i * 1.6f + sin(now * 90f + i) * 1.2f * rattle
        drawLine(CHROME.copy(alpha = rattle), Offset(cx - w * 0.34f, y), Offset(cx + w * 0.34f, y), 0.9f)
    }
    ripple(st, Piece.SNARE, cx, top, w / 2f, 9f * s, now)
    // RIM: a click on the hoop: the rim lights instead of the skin
    val rk = env(now - st.pieceHit[Piece.RIM.ordinal], 18f)
    if (rk > 0.02f) drawOval(slotColor(st, Piece.RIM).copy(alpha = rk), Offset(cx - w / 2f - 1.5f, top - 10f * s), Size(w + 3f, 20f * s), style = Stroke(5f))
}

/** Rack tom: on the tom arm that comes up out of the kick's mount (drawn before the kick, which hides the mount). */
private fun DrawScope.rackTom(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.RACK_TOM.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.RACK_TOM.ordinal]
    val c = slotColor(st, Piece.RACK_TOM)
    val cx = 212f; val w = 44f * s; val depth = 24f * s; val top = 188f - depth
    drawLine(CHROME_DARK, Offset(204f, 206f), Offset(204f, top + depth * 0.7f), 3.4f)            // tom arm
    drawLine(CHROME_DARK, Offset(204f, top + depth * 0.7f), Offset(cx - w * 0.2f, top + depth * 0.7f), 3f)
    drumShell(cx, top, w, 14f * s, depth, env(dt, 26f), c, envFor(dt, ringSec(v)) * hitGain(st.pieceVel[Piece.RACK_TOM.ordinal], v))
    drawRoundRect(CHROME, Offset(cx - w * 0.26f, top + depth * 0.55f), Size(7f, 6f), CornerRadius(1.5f))   // mount bracket
    ripple(st, Piece.RACK_TOM, cx, top, w / 2f, 7f * s, now)
}

/** Floor tom: big and deep, standing on its own three legs. */
private fun DrawScope.floorTom(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.FLOOR_TOM.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.FLOOR_TOM.ordinal]
    val c = slotColor(st, Piece.FLOOR_TOM)
    val cx = 112f; val w = 64f * s; val depth = 34f * s; val top = 202f
    // the back leg first (dimmer), then the two you see, each out of a bracket on the shell
    drawLine(CHROME_DARK.copy(alpha = 0.55f), Offset(cx + 4f, top + depth * 0.4f), Offset(cx + 8f, FLOOR - 4f), 2.2f, cap = StrokeCap.Round)
    for (k in floatArrayOf(-1f, 1f)) {
        val bx = cx + k * w * 0.44f
        drawLine(CHROME_DARK, Offset(bx, top + depth * 0.35f), Offset(bx + k * 7f, FLOOR), 2.8f, cap = StrokeCap.Round)
        drawCircle(IRON, 2.4f, Offset(bx + k * 7f, FLOOR))
    }
    drumShell(cx, top, w, 20f * s, depth, env(dt, 26f), c, envFor(dt, ringSec(v)) * hitGain(st.pieceVel[Piece.FLOOR_TOM.ordinal], v))
    for (k in floatArrayOf(-1f, 1f)) drawRoundRect(CHROME, Offset(cx + k * w * 0.44f - 3f, top + depth * 0.3f), Size(6f, 6f), CornerRadius(1.5f))
    ripple(st, Piece.FLOOR_TOM, cx, top, w / 2f, 10f * s, now)
}

/** Bass drum from the front: resonant head with the band name and a port, hoop, claws, spurs. */
/**
 * The kit's finish: one lacquer on every shell, as on a real kit (the pad colours only flash on the
 * heads when they're hit). Each kit preset gets one of these, picked by its name.
 */
private object KitFinish {
    private val colors = longArrayOf(0xFF1E1E26, 0xFF6E1A2A, 0xFFE4E0D6, 0xFFC08A4E, 0xFFC9B07A, 0xFF2C4C7A, 0xFF2E5E3E)
    private val sparkles = booleanArrayOf(true, false, false, false, true, true, true)
    var color = Color(colors[0]); private set
    var sparkle = true; private set
    private var forName: String? = null
    fun pick(kitName: String) {
        if (kitName == forName) return
        forName = kitName
        val i = (kitName.hashCode() and 0x7fffffff) % colors.size
        color = Color(colors[i]); sparkle = sparkles[i]
    }
}

private fun DrawScope.kick(st: DrummerState, now: Float, k: Float, tm: TextMeasurer, logo: TextStyle) {
    val v = st.pieceVoice[Piece.KICK.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.KICK.ordinal]
    val c = slotColor(st, Piece.KICK)
    val r = 42f * s * (1f + 0.04f * k)
    val cx = 200f; val cy = FLOOR - r - 1f
    val glow = envFor(dt, ringSec(v)) * hitGain(st.pieceVel[Piece.KICK.ordinal], v)
    // spurs: legs out of the shell's sides that stop it creeping forward
    for (sd in floatArrayOf(-1f, 1f)) {
        drawLine(CHROME_DARK, Offset(cx + sd * r * 0.86f, cy + r * 0.32f), Offset(cx + sd * (r + 13f), FLOOR + 1f), 2.6f, cap = StrokeCap.Round)
        drawCircle(CHROME, 2.4f, Offset(cx + sd * r * 0.86f, cy + r * 0.32f))
    }
    // resonant head (black, a soft thump of the voice's colour when it hits) inside a lacquered wooden hoop
    drawCircle(lerp(Color(0xFF15131A), c, 0.10f + 0.28f * glow), r, Offset(cx, cy))
    val flex = 1f - 0.03f * k
    drawCircle(Color.White.copy(alpha = 0.05f + 0.05f * k), r * 0.78f * flex, Offset(cx, cy), style = Stroke(1.2f))
    drawCircle(KitFinish.color, r - 3.5f, Offset(cx, cy), style = Stroke(7f))                                // the hoop, in the kit's finish
    if (glow > 0.03f) drawCircle(c.copy(alpha = 0.8f * glow), r - 3.5f, Offset(cx, cy), style = Stroke(3f))  // the hit, in the pad's colour
    drawCircle(CHROME, r, Offset(cx, cy), style = Stroke(1.6f))
    drawCircle(CHROME_DARK, r - 7.5f, Offset(cx, cy), style = Stroke(1.2f))
    for (i in 0 until 10) {                                    // claws + T-rods round the hoop
        val a = i * (2f * PI.toFloat() / 10f) + 0.31f
        val ca = cos(a); val sa = sin(a)
        drawLine(CHROME_DARK, Offset(cx + ca * (r - 6f), cy + sa * (r - 6f)), Offset(cx + ca * (r + 2f), cy + sa * (r + 2f)), 1.6f)
        drawCircle(CHROME, 2.4f, Offset(cx + ca * (r - 3.5f), cy + sa * (r - 3.5f)))
    }
    // port hole, with the muffling pillow peeking out
    val px = cx + r * 0.42f; val py = cy + r * 0.40f; val pr = r * 0.17f
    drawCircle(Color.Black.copy(alpha = 0.7f), pr, Offset(px, py))
    drawOval(Color(0xFFDCD6E6).copy(alpha = 0.55f), Offset(px - pr * 0.8f, py + pr * 0.1f), Size(pr * 1.6f, pr * 0.8f))
    drawCircle(CHROME_DARK, pr, Offset(px, py), style = Stroke(1.2f))
    drawOval(Color.White.copy(alpha = 0.06f), Offset(cx - r * 0.7f, cy - r * 0.8f), Size(r * 0.9f, r * 0.6f))
    // the band name on the front head, like every proper kick
    val t = tm.measure("BIG DAWG", logo)
    val fit = min(1f, (1.25f * r) / (t.size.width + 12f))
    withTransform({ scale(fit, fit, Offset(cx, cy - r * 0.08f)) }) {
        drawRoundRect(MASK, Offset(cx - t.size.width / 2f - 6f, cy - r * 0.08f - t.size.height / 2f - 3f), Size(t.size.width + 12f, t.size.height + 6f), CornerRadius(6f))
        drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - r * 0.08f - t.size.height / 2f))
    }
    // the beater is behind the drum (his side): the front head bulges a touch as it's about to land
    val toGo = st.kickNext - now
    if (st.kickNext > 0f && toGo in 0f..0.12f) drawCircle(c.copy(alpha = 0.12f * (1f - toGo / 0.12f)), r * 0.6f, Offset(cx, cy))
    ripple(st, Piece.KICK, cx, cy, r, r, now)
}

/** A brass cymbal seen slightly from above: lathe rings, a raised bell, and a glow in the voice's colour when it rings. */
private fun DrawScope.cymbal(cx: Float, cy: Float, w: Float, tilt: Float, wobble: Float, c: Color, flash: Float) {
    rotate(tilt + wobble, Offset(cx, cy)) {
        if (flash > 0.04f) drawOval(c.copy(alpha = 0.35f * flash), Offset(cx - w / 2 - 6f, cy - 11f), Size(w + 12f, 22f))
        drawOval(BRASS_DARK, Offset(cx - w / 2, cy - 5f), Size(w, 12f))                       // underside
        drawOval(lerp(BRASS, c, 0.35f * flash), Offset(cx - w / 2, cy - 6f), Size(w, 11f))
        drawOval(BRASS_LIGHT.copy(alpha = 0.55f), Offset(cx - w * 0.36f, cy - 4.2f), Size(w * 0.72f, 7.4f), style = Stroke(0.9f))
        drawOval(BRASS_LIGHT.copy(alpha = 0.35f), Offset(cx - w * 0.22f, cy - 3f), Size(w * 0.44f, 5f), style = Stroke(0.9f))
        drawOval(lerp(BRASS_LIGHT, c, 0.4f * flash), Offset(cx - w * 0.11f, cy - 5.5f), Size(w * 0.22f, 6f))   // bell
        drawOval(BRASS_DARK, Offset(cx - w / 2, cy - 6f), Size(w, 11f), style = Stroke(1.2f))
        drawCircle(CHROME, 1.8f, Offset(cx, cy - 3.5f))                                         // felt + wing nut
    }
}

private fun wobbleOf(dt: Float, ring: Float, amount: Float) =
    if (dt in 0f..ring * 2f) sin(dt * (30f - 8f * min(1f, ring))) * exp(-dt * 3f / ring) * amount else 0f

/** Crash: high on a boom over the hats, tilted toward him. */
private fun DrawScope.crashStand(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.CYMBAL.ordinal]
    val dt = now - st.pieceHit[Piece.CYMBAL.ordinal]
    val ring = ringSec(v)
    val w = 70f + 24f * ((ring - 0.3f) / 2f).coerceIn(0f, 1f)
    boomStand(352f, 134f, 298f, 94f)
    cymbal(298f, 94f, w, -12f, wobbleOf(dt, ring, 12f), slotColor(st, Piece.CYMBAL), envFor(dt, ring) * hitGain(st.pieceVel[Piece.CYMBAL.ordinal], v))
}

/** Ride: the big one, on a boom over the floor tom, tilted toward him; it sways slowly. */
private fun DrawScope.rideStand(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.RIDE.ordinal]
    val dt = now - st.pieceHit[Piece.RIDE.ordinal]
    val ring = ringSec(v)
    boomStand(56f, 150f, 100f, 120f)
    cymbal(100f, 120f, 100f, 9f, wobbleOf(dt, ring, 5f), slotColor(st, Piece.RIDE), envFor(dt, ring) * hitGain(st.pieceVel[Piece.RIDE.ordinal], v))
}

/** How far open the hats are right now (0 closed … 1 wide), from the voice that last hit them. */
private fun hatOpenNow(st: DrummerState, now: Float): Float {
    val dt = now - st.pieceHit[Piece.HIHAT.ordinal]
    val ring = ringSec(st.pieceVoice[Piece.HIHAT.ordinal])
    return if (dt in 0f..ring) st.hatOpenness * exp(-dt * 2f / ring) else 0f
}

/** Hi-hat: stand, the pedal under his left foot, pull rod, bottom cymbal fixed, top cymbal on the clutch. */
private fun DrawScope.hihat(st: DrummerState, now: Float, open: Float, footLift: Float) {
    val dt = now - st.pieceHit[Piece.HIHAT.ordinal]
    val c = slotColor(st, Piece.HIHAT)
    val ring = ringSec(st.pieceVoice[Piece.HIHAT.ordinal])
    val x = 314f; val y = 148f
    val gap = 2.5f + 10f * open + 2f * sin(dt * 40f) * open
    val wob = sin(dt * 30f) * open * 6f
    tripod(x, y + 4f, 16f)
    // pedal: base plate at the stand, footboard running back toward him, heel plate under the heel
    drawLine(CHROME_DARK, Offset(x, FLOOR - 22f), Offset(x, FLOOR - 2f), 2f)                     // pull chain housing
    drawLine(IRON, Offset(x - 4f, FLOOR - 1f), Offset(x - 26f, FLOOR - 7f - footLift * 0.5f), 5f, cap = StrokeCap.Round)
    drawLine(CHROME_DARK, Offset(x - 4f, FLOOR - 2f), Offset(x - 26f, FLOOR - 8f - footLift * 0.5f), 1.2f)
    drawLine(CHROME, Offset(x, y - gap), Offset(x, y - gap - 8f), 1.6f)                          // pull rod over the clutch
    cymbal(x, y, 66f, 0f, 0f, c, envFor(dt, ring))
    cymbal(x, y - gap, 66f, 0f, wob, c, envFor(dt, ring))
    drawRoundRect(CHROME, Offset(x - 2.5f, y - gap - 6f), Size(5f, 5f), CornerRadius(1f))        // clutch
}

/** Cowbell on a clamp off the kick's hoop, mouth toward him. */
private fun DrawScope.cowbell(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.COWBELL.ordinal]
    val dt = now - st.pieceHit[Piece.COWBELL.ordinal]
    val c = slotColor(st, Piece.COWBELL)
    val s = sizeOf(v)
    val shake = if (dt in 0f..0.4f) sin(dt * 60f) * exp(-dt * 10f) * 4f else 0f
    drawLine(CHROME_DARK, Offset(176f, 212f), Offset(168f, 192f), 2.4f)                       // the clamp rod
    rotate(shake, Offset(166f, 186f)) {
        val p = st.path.apply { reset(); moveTo(158f - 2f * s, 176f); lineTo(172f + 2f * s, 178f); lineTo(176f + 4f * s, 194f); lineTo(154f - 4f * s, 192f); close() }
        drawPath(p, lerp(Color(0xFF3A3640), c, 0.25f + 0.7f * envFor(dt, ringSec(v))))
        drawPath(p, CHROME_DARK, style = Stroke(1.6f))
        drawLine(Color.White.copy(alpha = 0.25f), Offset(160f, 180f), Offset(158f, 190f), 1.5f)
    }
}

/** ZAP: a little electronic pad clamped to the crash stand, throwing sparks. */
private fun DrawScope.zapPad(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.ZAP.ordinal]
    val dt = now - st.pieceHit[Piece.ZAP.ordinal]
    val c = slotColor(st, Piece.ZAP)
    val k = envFor(dt, ringSec(v))
    if (!st.present[Piece.CYMBAL.ordinal]) tripod(352f, 200f, 15f)                             // own stand if no crash
    drawLine(CHROME_DARK, Offset(352f, 200f), Offset(340f, 196f), 2.4f)
    drawRoundRect(Color(0xFF26232C), Offset(316f, 186f), Size(30f, 12f), CornerRadius(3f))
    drawRoundRect(lerp(Color(0xFF3A3642), c, 0.2f + 0.8f * k), Offset(318f, 187f), Size(26f, 8f), CornerRadius(2f))
    if (k > 0.05f) for (i in 0 until 3) {
        val a = -PI.toFloat() / 2f + (i - 1) * 0.6f
        val x0 = 331f + cos(a) * 10f; val y0 = 186f + sin(a) * 5f
        val x1 = 331f + cos(a) * (20f + 12f * k); val y1 = 184f + sin(a) * (14f + 10f * k)
        drawLine(c.copy(alpha = k), Offset(x0, y0), Offset((x0 + x1) / 2f + 4f, (y0 + y1) / 2f), 2.5f, cap = StrokeCap.Round)
        drawLine(c.copy(alpha = k), Offset((x0 + x1) / 2f + 4f, (y0 + y1) / 2f), Offset(x1, y1), 2.5f, cap = StrokeCap.Round)
    }
}

/** SHAKE: an egg shaker in his left paw, jiggling while it sounds. */
private fun DrawScope.shaker(st: DrummerState, now: Float, x: Float, y: Float) {
    val dt = now - st.pieceHit[Piece.SHAKER.ordinal]
    val c = slotColor(st, Piece.SHAKER)
    val j = sin(dt * 70f) * exp(-dt * 6f) * 6f
    drawOval(c, Offset(x - 7f + j, y - 22f), Size(14f, 19f))
    drawOval(Color.Black.copy(alpha = 0.5f), Offset(x - 7f + j, y - 22f), Size(14f, 19f), style = Stroke(1.6f))
}

/** CLAP: paws meet in front of his chest, with a burst in the clap's colour. */
private fun DrawScope.clapBurst(st: DrummerState, now: Float) {
    val dt = now - st.pieceHit[Piece.CLAP.ordinal]
    val k = 1f - dt / 0.25f
    val c = slotColor(st, Piece.CLAP).copy(alpha = k)
    for (i in 0 until 8) {
        val a = PI.toFloat() * 2f * i / 8f + 0.2f
        val r0 = 14f + 10f * (1f - k); val r1 = r0 + 9f
        drawLine(c, Offset(200f + cos(a) * r0, 146f + sin(a) * r0), Offset(200f + cos(a) * r1, 146f + sin(a) * r1), 3f, cap = StrokeCap.Round)
    }
}

// ----------------------------------------------------------------------------- Big Dawg

/**
 * Seated, from the front: thighs come toward you and spread, so the knees sit wide of the hips. The
 * right foot is on the kick pedal (behind the drum); the left leg angles out to the hi-hat pedal,
 * sneaker tapping the beat and lifting off for an open hat.
 */
private fun DrawScope.legs(y: Float, footLift: Float, kickK: Float, hats: Boolean) {
    val hy = 220f + y * 0.4f
    // right leg (your left): mostly behind the kick
    val rk = Offset(166f, 238f + kickK * 2f)
    denimLeg(Offset(186f, hy), rk, Offset(182f, 280f))
    // left leg (your right): out to the hat pedal
    val ankle = if (hats) Offset(292f, 268f - footLift) else Offset(262f, 276f)
    val lk = Offset(254f, 232f + 0.3f * footLift)
    denimLeg(Offset(214f, hy), lk, ankle)
    sneaker(ankle.x + 2f, ankle.y + 5f, if (hats) -8f - footLift * 1.2f else 0f)
}

/** One leg of baggy jeans: thigh, shin, and the stacked hem. */
private fun DrawScope.denimLeg(hip: Offset, knee: Offset, ankle: Offset) {
    drawLine(DENIM_DARK, hip, knee, 25f, cap = StrokeCap.Round)
    drawLine(DENIM_DARK, knee, ankle, 21f, cap = StrokeCap.Round)
    drawLine(DENIM, hip, knee, 22f, cap = StrokeCap.Round)
    drawLine(DENIM, knee, ankle, 18f, cap = StrokeCap.Round)
    // a highlight down the front crease, and the stack at the hem
    drawLine(Color.White.copy(alpha = 0.10f), Offset(knee.x - 2f, knee.y - 4f), Offset((knee.x + ankle.x) / 2f - 2f, (knee.y + ankle.y) / 2f), 2f, cap = StrokeCap.Round)
    drawOval(DENIM_DARK, Offset(ankle.x - 12f, ankle.y - 7f), Size(24f, 10f))
    drawOval(DENIM, Offset(ankle.x - 11f, ankle.y - 7f), Size(22f, 8f))
}

/** A fresh white high-top (our own design: a mint side stripe, a gum sole), toe angled by [angle] degrees. */
private fun DrawScope.sneaker(x: Float, y: Float, angle: Float) {
    rotate(angle, Offset(x - 6f, y)) {
        drawRoundRect(GUM, Offset(x - 14f, y + 3f), Size(28f, 5f), CornerRadius(2.5f))
        drawRoundRect(SNEAKER_SHADE, Offset(x - 13f, y - 7f), Size(26f, 12f), CornerRadius(6f))
        drawRoundRect(SNEAKER, Offset(x - 12.5f, y - 7f), Size(24f, 10.5f), CornerRadius(5.5f))
        drawOval(SNEAKER, Offset(x + 2f, y - 4f), Size(12f, 9f))                                     // toe box
        drawLine(SNEAKER_STRIPE, Offset(x - 9f, y + 1f), Offset(x + 6f, y - 4f), 2.4f, cap = StrokeCap.Round)
        for (i in 0 until 3) drawLine(SNEAKER_SHADE, Offset(x - 6f + i * 3.5f, y - 7f), Offset(x - 4f + i * 3.5f, y - 4f), 1.1f)   // laces
    }
}

/**
 * Broad and stocky: a maroon hoodie with the hood bunched behind his neck, shoulders out at the
 * edges of the frame (that's where the arms come from), drawstrings, and the gold chain with a
 * bone pendant over the top.
 */
private fun DrawScope.body(st: DrummerState, y: Float, swingNow: Float) {
    drawRoundRect(HOODIE_DARK, Offset(170f, 110f + y), Size(60f, 24f), CornerRadius(12f))          // hood, behind the neck
    val p = st.path; p.reset()
    p.moveTo(157f, 160f + y)
    p.quadraticTo(156f, 130f + y, 182f, 125f + y)
    p.lineTo(218f, 125f + y)
    p.quadraticTo(244f, 130f + y, 243f, 160f + y)
    p.lineTo(238f, 228f + y)
    p.lineTo(162f, 228f + y)
    p.close()
    drawPath(p, HOODIE)
    drawPath(p, HOODIE_DARK, style = Stroke(2f))
    drawRect(Color.Black.copy(alpha = 0.14f), Offset(222f, 132f + y), Size(18f, 96f))           // shade on his left side
    drawLine(HOODIE_LIGHT.copy(alpha = 0.6f), Offset(166f, 136f + y), Offset(164f, 180f + y), 2f, cap = StrokeCap.Round)
    // neckline + drawstrings
    drawArc(HOODIE_DARK, 0f, 180f, false, Offset(180f, 118f + y), Size(40f, 16f), style = Stroke(3f))
    drawLine(BLAZE, Offset(192f, 132f + y), Offset(190f, 160f + y), 1.6f, cap = StrokeCap.Round)
    drawLine(BLAZE, Offset(208f, 132f + y), Offset(210f, 162f + y), 1.6f, cap = StrokeCap.Round)
    drawRect(CHROME, Offset(188.8f, 159f + y), Size(2.4f, 4f)); drawRect(CHROME, Offset(208.8f, 161f + y), Size(2.4f, 4f))
    // gold chain: a pendulum (a spring), knocked by the kick and the lean, a gold bone on the end
    val swing = swingNow
    p.reset()
    p.moveTo(181f, 128f + y); p.quadraticTo(200f + swing, 172f + y, 219f, 128f + y)
    drawPath(p, GOLD_DARK, style = Stroke(4.2f))
    drawPath(p, GOLD, style = Stroke(2.4f))
    val bx = 200f + swing * 0.55f; val by = 154f + y
    drawRoundRect(GOLD_DARK, Offset(bx - 7f, by - 2.2f), Size(14f, 5f), CornerRadius(2f))
    for (ex in floatArrayOf(bx - 7f, bx + 7f)) for (ey in floatArrayOf(by - 2f, by + 2.5f)) drawCircle(GOLD, 2.6f, Offset(ex, ey))
    drawRoundRect(GOLD, Offset(bx - 6f, by - 1.4f), Size(12f, 3.5f), CornerRadius(1.5f))
    drawCircle(Color.White.copy(alpha = 0.7f), 0.9f, Offset(bx - 3f, by - 0.6f))
}

private fun DrawScope.head(st: DrummerState, now: Float, y: Float, sleepy: Boolean, kickK: Float,
                           jowlLag: Float = 0f, earLag: Float = 0f, capLag: Float = 0f) {
    val cx = 200f; val cy = 90f + y
    val hop = 4f * kickK + (if (now < st.grinUntil) 2f else 0f) + capLag
    val jowl = 1.5f * kickK - jowlLag                             // jowls trail the head and wobble after it
    // rose ears: small, folded back; they flop a beat late (follow-through)
    val flap = 4f * kickK + earLag * 2.2f
    rotate(-18f - flap, Offset(cx - 38f, cy - 30f)) {
        drawOval(COAT_DARK, Offset(cx - 52f, cy - 40f), Size(22f, 16f))
        drawOval(MASK, Offset(cx - 48f, cy - 36f), Size(12f, 8f))
    }
    rotate(18f + flap, Offset(cx + 38f, cy - 30f)) {
        drawOval(COAT_DARK, Offset(cx + 30f, cy - 40f), Size(22f, 16f))
        drawOval(MASK, Offset(cx + 36f, cy - 36f), Size(12f, 8f))
    }
    // big square-ish head
    drawRoundRect(COAT, Offset(cx - 44f, cy - 40f), Size(88f, 74f), CornerRadius(30f))
    // brow wrinkles
    drawLine(COAT_DARK, Offset(cx - 14f, cy - 26f), Offset(cx + 14f, cy - 26f), 2.5f, cap = StrokeCap.Round)
    drawLine(COAT_DARK, Offset(cx - 10f, cy - 21f), Offset(cx + 10f, cy - 21f), 2f, cap = StrokeCap.Round)
    // jowls + dark muzzle mask
    drawOval(COAT, Offset(cx - 46f, cy + 2f + jowl), Size(40f, 32f))
    drawOval(COAT, Offset(cx + 6f, cy + 2f + jowl), Size(40f, 32f))
    drawRoundRect(MASK, Offset(cx - 26f, cy - 4f), Size(52f, 30f + jowl), CornerRadius(14f))
    // underbite: lower jaw juts out, two little fangs up, and the gold tooth on a grin
    val grin = now < st.grinUntil
    val open = env(now - st.pieceHit[Piece.KICK.ordinal], 16f) * 3f + (if (grin) 3f else 0f)
    drawRoundRect(COAT_DARK, Offset(cx - 22f, cy + 20f + jowl), Size(44f, 12f + open), CornerRadius(6f))
    drawRoundRect(NOSE, Offset(cx - 18f, cy + 22f + jowl), Size(36f, 4f + open), CornerRadius(3f))
    val path = st.path
    for (fx in floatArrayOf(cx - 12f, cx + 12f)) {
        path.reset(); path.moveTo(fx - 3f, cy + 22f + jowl); path.lineTo(fx, cy + 15f + jowl); path.lineTo(fx + 3f, cy + 22f + jowl); path.close()
        drawPath(path, BLAZE)
    }
    if (grin) drawRect(GOLD, Offset(cx - 3f, cy + 18f + jowl), Size(6f, 5f))
    // nose: wide and wet (a highlight)
    drawRoundRect(NOSE, Offset(cx - 13f, cy - 6f), Size(26f, 14f), CornerRadius(7f))
    drawOval(Color.White.copy(alpha = 0.35f), Offset(cx - 7f, cy - 4f), Size(8f, 4f))
    // eyes: half-lidded, unbothered
    if (now > st.nextBlink + 0.12f) st.nextBlink = now + 2.8f + (now * 7.13f % 3f)
    val blinking = now in st.nextBlink..(st.nextBlink + 0.12f)
    for (ex in floatArrayOf(cx - 20f, cx + 20f)) {
        when {
            grin -> {   // happy squint
                drawLine(NOSE, Offset(ex - 7f, cy - 10f), Offset(ex, cy - 15f), 3.5f, cap = StrokeCap.Round)
                drawLine(NOSE, Offset(ex, cy - 15f), Offset(ex + 7f, cy - 10f), 3.5f, cap = StrokeCap.Round)
            }
            sleepy || blinking -> drawLine(NOSE, Offset(ex - 7f, cy - 12f), Offset(ex + 7f, cy - 12f), 3f, cap = StrokeCap.Round)
            else -> {
                var look = 0f; var newest = -10f
                for (p in Piece.entries) if (st.pieceHit[p.ordinal] > newest) { newest = st.pieceHit[p.ordinal]; look = (strikeX(p) - 200f) / 200f }
                val glance = env(now - newest, 3f) * look * 3f
                drawOval(BLAZE, Offset(ex - 8f, cy - 18f), Size(16f, 12f))
                drawCircle(NOSE, 4f, Offset(ex + glance, cy - 12f))
                // heavy lid over the top half: the chill look
                drawArc(COAT_DARK, 180f, 180f, true, Offset(ex - 9f, cy - 20f), Size(18f, 14f))
                drawLine(MASK, Offset(ex - 9f, cy - 13f), Offset(ex + 9f, cy - 13f), 2f)
            }
        }
    }
    if (sleepy) {
        val zt = (now % 2.4f) / 2.4f
        val zx = cx + 48f + zt * 14f; val zy = cy - 40f - zt * 26f; val zs = 6f + zt * 4f
        val za = (1f - zt)
        drawLine(Tb.Muted.copy(alpha = za), Offset(zx, zy), Offset(zx + zs, zy), 2.5f)
        drawLine(Tb.Muted.copy(alpha = za), Offset(zx + zs, zy), Offset(zx, zy + zs), 2.5f)
        drawLine(Tb.Muted.copy(alpha = za), Offset(zx, zy + zs), Offset(zx + zs, zy + zs), 2.5f)
    }
    fittedCap(cx, cy - 6f, cy - 62f - hop, hop)
}

/** Where a paw is now: it springs from its last strike point back to rest (slight overshoot), riding the beat. */
private fun pawPos(h: Hand, now: Float, groove: Float): Offset {
    val dt = now - h.at
    // after a hit: spring back to rest (a little overshoot)
    val k = if (dt < 0f) 1f else min(1f, dt / 0.22f).let { x -> 1f + 1.7f * (x - 1f) * (x - 1f) * (x - 1f) + 0.7f * (x - 1f) * (x - 1f) }
    var x = h.fromX + (h.restX - h.fromX) * k
    var y = h.fromY + (h.restY + groove - h.fromY) * k
    // before a sequenced hit: travel to the drum while lifting, then drop onto it exactly on time
    h.antic = 0f
    val toGo = h.nextAt - now
    if (h.nextAt > 0f && toGo in 0f..ANTIC && dt > 0.05f) {
        val u = 1f - toGo / ANTIC
        val e = u * u * (3f - 2f * u)                                 // smoothstep
        x += (h.nextX - x) * e
        y += (h.nextY - y) * e - 16f * sin(PI.toFloat() * u) * (1f - 0.5f * u)
        h.antic = u
    }
    return Offset(x, y)
}

// A bulldog's arms are short: compact limbs, full-length sticks. (Tuned against sketches of every reach:
// rest, snare + hats, floor tom + crash, ride + ZAP, rack tom.)
private const val UPPER_ARM = 31f
private const val FOREARM = 29f
private const val ARM_REACH = 58f      // shoulder → paw, max
private const val STICK = 38f          // paw → tip

/** Paw position for a stick-tip target: the stick covers its length, the arm the rest (28 … ARM_REACH). */
private fun reach(sx: Float, sy: Float, tip: Offset): Offset {
    val dx = tip.x - sx; val dy = tip.y - sy
    val d = sqrt(dx * dx + dy * dy)
    if (d < 1f) return tip
    val use = (d - STICK).coerceIn(min(d, 28f), ARM_REACH)
    return Offset(sx + dx / d * use, sy + dy / d * use)
}

/**
 * Hoodie sleeve with a real elbow: two-bone IK (fixed upper arm + forearm), and of the two possible
 * elbows the one nearer a natural spot, out to the side and down from the shoulder (drummers keep
 * their elbows out). The old version pushed the elbow sideways by a formula that, on a reaching arm,
 * folded it up into the chest.
 */
private fun DrawScope.arm(sx: Float, sy: Float, hx: Float, hy: Float, mirror: Boolean) {
    val out = if (mirror) 1f else -1f
    val dx = hx - sx; val dy = hy - sy
    val d = sqrt(dx * dx + dy * dy).coerceIn(8f, UPPER_ARM + FOREARM - 0.5f)
    val cosA = ((UPPER_ARM * UPPER_ARM + d * d - FOREARM * FOREARM) / (2f * UPPER_ARM * d)).coerceIn(-1f, 1f)
    val a = kotlin.math.acos(cosA); val b = kotlin.math.atan2(dy, dx)
    val e1x = sx + UPPER_ARM * kotlin.math.cos(b + a); val e1y = sy + UPPER_ARM * sin(b + a)
    val e2x = sx + UPPER_ARM * kotlin.math.cos(b - a); val e2y = sy + UPPER_ARM * sin(b - a)
    val hx0 = sx + out * 24f; val hy0 = sy + 30f
    val pick1 = (e1x - hx0) * (e1x - hx0) + (e1y - hy0) * (e1y - hy0) <= (e2x - hx0) * (e2x - hx0) + (e2y - hy0) * (e2y - hy0)
    val ex = if (pick1) e1x else e2x; val ey = if (pick1) e1y else e2y
    drawLine(HOODIE_DARK, Offset(sx, sy), Offset(ex, ey), 18f, cap = StrokeCap.Round)
    drawLine(HOODIE_DARK, Offset(ex, ey), Offset(hx, hy), 15f, cap = StrokeCap.Round)
    drawLine(HOODIE, Offset(sx, sy), Offset(ex, ey), 15f, cap = StrokeCap.Round)
    drawLine(HOODIE, Offset(ex, ey), Offset(hx, hy), 12f, cap = StrokeCap.Round)
    drawLine(HOODIE_LIGHT.copy(alpha = 0.35f), Offset(sx + out * 3f, sy + 2f), Offset(ex + out * 3f, ey), 3f, cap = StrokeCap.Round)   // sleeve highlight
    // cuff: the last few units before the paw, a darker ribbed band
    val fx = hx - ex; val fy = hy - ey; val fl = max(1f, sqrt(fx * fx + fy * fy))
    val cx0 = hx - fx / fl * 9f; val cy0 = hy - fy / fl * 9f
    drawLine(HOODIE_DARK, Offset(cx0, cy0), Offset(hx - fx / fl * 3f, hy - fy / fl * 3f), 13f, cap = StrokeCap.Butt)
}

/** Paw + stick, on top of the kit. The stick points down at the strike and tips back up at rest. */
private fun DrawScope.paw(h: Hand, hx: Float, hy: Float, tip: Offset, now: Float, mirror: Boolean, stick: Boolean) {
    val dt = now - h.at
    val raise = if (dt < 0f) 1f else min(1f, dt / 0.2f)
    if (stick) {
        val dir = if (mirror) 1f else -1f
        // at the hit the tip sits on the target; recovering, it swings to a short up-and-out rest angle
        var ax = tip.x - hx; var ay = tip.y - hy
        val al = sqrt(ax * ax + ay * ay)
        if (al < 14f) { ax = dir * 10f; ay = 10f } else if (al > STICK) { ax *= STICK / al; ay *= STICK / al }
        // wind-up: the tip cocks back and up before a sequenced hit, then whips down onto the head
        val wind = if (h.antic > 0f) sin(PI.toFloat() * h.antic) else 0f
        val r = if (h.antic > 0f) (1f - h.antic * h.antic).coerceIn(0f, 1f) else raise
        val restX = dir * (27f - 9f * wind); val restY = -26f - 10f * wind
        val tipX = hx + ax + (restX - ax) * r
        val tipY = hy + ay + (restY - ay) * r
        // held, not balanced on the paw: the butt end pokes out behind the grip
        val sl = max(1f, sqrt((tipX - hx) * (tipX - hx) + (tipY - hy) * (tipY - hy)))
        val bx = hx - (tipX - hx) / sl * 7f; val by = hy - (tipY - hy) / sl * 7f
        drawLine(STICK_DARK, Offset(bx, by), Offset(tipX, tipY), 5.5f, cap = StrokeCap.Round)
        drawLine(STICK_WOOD, Offset(bx, by), Offset(tipX, tipY), 3.5f, cap = StrokeCap.Round)
        drawCircle(STICK_WOOD, 2.6f, Offset(tipX, tipY))
    }
    drawCircle(COAT_DARK, 8.5f, Offset(hx, hy))
    drawCircle(BLAZE, 7f, Offset(hx, hy))
    for (i in -1..1) drawLine(COAT_DARK.copy(alpha = 0.7f), Offset(hx + i * 3f, hy + 2f), Offset(hx + i * 3f, hy + 5.5f), 1.3f, cap = StrokeCap.Round)
}


// ----------------------------------------------------------------------------- the corner

/** Scene colours sit back from the dog and kit: blended a little toward the theme's deepest tone. */
private fun back(c: Color, k: Float = 0.38f) = lerp(c, Tb.Void, k)

/**
 * The corner BIG DAWG plays on, at night: a brick wall with wheatpasted posters and a tag on your
 * left; a bodega on the right with a neon BODEGA sign and a little OPEN sign (they pulse with the music) over a roll-down
 * gate with a piece sprayed across it; a power line overhead with a pair of sneakers slung over it;
 * a boombox on a milk crate whose speakers pump on the kick; a hydrant; the sidewalk in slabs with a
 * crack; the curb; steam out of a manhole in the street. [l]…[r] × [t]…[b] is the whole panel in
 * stage units, so the scene fills it however the panel is shaped.
 */
private fun DrawScope.street(
    st: DrummerState, l: Float, r: Float, t: Float, b: Float, now: Float, playing: Boolean, beatPhase: Float, kickK: Float,
    tm: TextMeasurer, spray: TextStyle, neon: TextStyle,
    snareK: Float = 0f, hatK: Float = 0f, crashK: Float = 0f, level: Float = 0f,
) {
    val walk = 236f                                          // wall meets sidewalk
    val curb = 293f
    val shopL = 150f                                         // where the bodega front starts
    val pulse = if (playing) (1f - beatPhase).pow(3) else 0f

    // ---- brick wall (your left) ----
    drawRect(back(BRICK), Offset(l, t), Size(shopL - l, walk - t))
    val key = l * 31f + t
    if (key != st.bricksKey) {                                 // brick courses: one cached path
        st.bricksKey = key
        val p = st.bricks; p.reset()
        var y = walk - 7f; var row = 0
        while (y > t) {
            p.moveTo(l, y); p.lineTo(shopL, y)
            var x = l + (if (row % 2 == 0) 0f else 9f)
            while (x < shopL) { p.moveTo(x, y); p.lineTo(x, y + 7f); x += 18f }
            y -= 7f; row++
        }
    }
    drawPath(st.bricks, back(MORTAR, 0.2f).copy(alpha = 0.55f), style = Stroke(0.8f))
    if (snareK > 0.02f) drawRect(Color.White.copy(alpha = 0.07f * snareK), Offset(l, t), Size(shopL - l, walk - t))   // the snare cracks off the bricks
    // wheatpasted posters, layered and a bit torn; their loose corners flap on the snare
    val flap = sin(now * 38f) * 3f * snareK
    poster(14f, 36f, 38f, 50f, -3f + flap, back(Color(0xFFE9DFC8), 0.25f), back(HYDRANT, 0.25f))
    poster(48f, 30f, 42f, 56f, 2f - flap * 0.6f, back(Color(0xFFF2E36A), 0.3f), back(SPRAY_INK, 0.1f))
    poster(94f, 44f, 36f, 46f, -2f + flap * 0.8f, back(Color(0xFFE9DFC8), 0.25f), back(Color(0xFF2F64C8), 0.25f))
    // a tag low on the bricks: one fat stroke and a flick
    val p = st.path; p.reset()
    p.moveTo(10f, 196f); p.cubicTo(22f, 168f, 34f, 210f, 44f, 180f); p.cubicTo(52f, 160f, 60f, 206f, 72f, 178f)
    drawPath(p, back(Color(0xFFF4F4F5), 0.35f), style = Stroke(3.2f, cap = StrokeCap.Round))
    drawLine(back(Color(0xFFF4F4F5), 0.35f), Offset(16f, 204f), Offset(74f, 196f), 2f, cap = StrokeCap.Round)

    // ---- bodega front (your right): pillar, sign band, roll-down gate ----
    drawRect(back(STONE), Offset(shopL - 4f, t), Size(8f, walk - t))
    drawRect(back(Color(0xFF1B1A22), 0.15f), Offset(shopL + 4f, t), Size(r - shopL - 4f, walk - t))
    val signT = 12f; val signB = 50f
    drawRect(back(Color(0xFF111018), 0.1f), Offset(shopL + 4f, signT), Size(r - shopL - 4f, signB - signT))
    drawLine(back(CHROME_DARK, 0.3f), Offset(shopL + 4f, signB), Offset(r, signB), 2f)
    // the gate: corrugated steel, one cached path of ribs
    val gT = signB + 4f; val gB = walk - 4f
    drawRect(back(STEEL, 0.3f), Offset(shopL + 8f, gT), Size(r - shopL - 8f, gB - gT))
    val gk = r * 7f + l
    if (gk != st.gateKey) {
        st.gateKey = gk
        val g = st.gate; g.reset()
        var y = gT + 4f
        while (y < gB) { g.moveTo(shopL + 8f, y); g.lineTo(r, y); y += 4.5f }
    }
    // the gate rattles on the kick (the ribs shiver), and glows faintly with the neon's colour as the music gets loud
    val rattle = sin(now * 95f) * 1.3f * kickK
    withTransform({ translate(0f, rattle) }) { drawPath(st.gate, back(STEEL_DARK, 0.3f), style = Stroke(1.1f)) }
    if (level > 0.05f) drawRect(NEON_PINK.copy(alpha = 0.05f * level + 0.06f * crashK), Offset(shopL + 8f, gT), Size(r - shopL - 8f, gB - gT))
    drawRect(back(STEEL_DARK, 0.2f), Offset(shopL + 8f, gB - 6f), Size(r - shopL - 8f, 6f))      // bottom bar
    drawRoundRect(back(BRASS, 0.3f), Offset(shopL + 40f, gB - 10f), Size(7f, 8f), CornerRadius(1.5f))   // padlock
    drawArc(back(CHROME_DARK, 0.3f), 180f, 180f, false, Offset(shopL + 40.5f, gB - 14f), Size(6f, 8f), style = Stroke(1.4f))
    // the piece across the gate: fat italic letters, a hard shadow, a black outline, highlights + drips
    val gl = tm.measure("DAWG", spray)
    val pw = 140f
    val fit = pw / max(1f, gl.size.width.toFloat())
    val gx = 254f; val gy = 152f
    withTransform({ translate(gx, gy); rotate(-5f, Offset.Zero); scale(fit, fit, Offset.Zero) }) {
        val glow = 0.35f * kickK + 0.15f * level
        if (glow > 0.02f) drawText(gl, color = SPRAY_ORANGE.copy(alpha = glow), drawStyle = NEON_GLOW)   // the piece glows with the bass
        drawText(gl, color = back(SPRAY_INK, 0.05f), topLeft = Offset(9f, 9f))
        drawText(gl, color = back(SPRAY_INK, 0.05f), drawStyle = OUTLINE_THICK)
        drawText(gl, color = back(SPRAY_ORANGE, 0.22f))
        drawText(gl, color = back(Color(0xFFFFE08A), 0.25f).copy(alpha = 0.5f), topLeft = Offset(-2f, -3f), drawStyle = Stroke(2f))
    }
    for (k in 0 until 5) {                                      // drips under the letters
        val dx = gx + 14f + k * 27f; val dy = gy + gl.size.height * fit * 0.78f - k * 2.4f
        val dl = 6f + (k * 37 % 11)
        drawLine(back(SPRAY_ORANGE, 0.25f), Offset(dx, dy), Offset(dx, dy + dl), 1.8f, cap = StrokeCap.Round)
        drawCircle(back(SPRAY_ORANGE, 0.25f), 1.4f, Offset(dx, dy + dl + 0.6f))
    }
    // neon: BODEGA in pink (brighter with the music, a punch on the kick, white-hot on a crash, the odd flicker),
    // and the little cyan OPEN sign that flickers on the hats
    val flick = if (((now * 3.7f).toInt() * 2654435761L % 23L) == 0L) 0.35f else 1f
    neonWord(tm.measure("BODEGA", neon), 258f, 16f, 88f, NEON_PINK, ((0.65f + 0.35f * level + 0.2f * kickK + 0.5f * crashK) * flick).coerceAtMost(1.4f))
    // the little OPEN sign: its own tube, flickers on the hats
    drawRoundRect(back(Color(0xFF111018), 0.05f), Offset(352f, 20f), Size(40f, 18f), CornerRadius(4f))
    drawRoundRect(NEON_CYAN.copy(alpha = 0.35f + 0.4f * hatK + 0.2f * pulse), Offset(352f, 20f), Size(40f, 18f), CornerRadius(4f), style = Stroke(1.5f))
    neonWord(tm.measure("OPEN", neon), 356f, 22f, 32f, NEON_CYAN, 0.7f + 0.3f * hatK + 0.2f * pulse)
    drawCircle(NEON_WASH, 90f, Offset(300f, 32f), alpha = (0.5f + 0.3f * pulse + 0.4f * level + 0.6f * crashK).coerceAtMost(1f))   // wash on the wall
    // dust drifting through the neon's light, catching it on the hats
    for (i in 0 until 14) {
        val ph = (now * (0.03f + (i % 5) * 0.008f) + i * 0.137f) % 1f
        val dx = 236f + ((i * 53) % 130) + sin(now * 0.7f + i) * 6f
        val dy = 96f - ph * 70f
        drawCircle(Color.White.copy(alpha = (0.10f + 0.55f * hatK) * sin(PI.toFloat() * ph)), 0.9f + 0.5f * (i % 3), Offset(dx, dy))
    }

    // ---- the power line, with a pair of sneakers slung over it ----
    p.reset(); p.moveTo(l - 10f, 6f); p.quadraticTo(200f, 26f, r + 10f, 4f)
    drawPath(p, back(IRON, 0.05f), style = Stroke(1.4f))
    val sway = sin(now * 0.9f) * 3f + kickK * 1.5f
    val wu = ((123f - (l - 10f)) / (r - l + 20f)).coerceIn(0f, 1f)                          // where on the curve the shoes hang
    val wireY = (1f - wu) * (1f - wu) * 6f + 2f * wu * (1f - wu) * 26f + wu * wu * 4f
    for (k in 0..1) {
        val sx = 114f + k * 16f; val ex = sx + sway; val ey = wireY + 30f + k * 6f
        drawLine(back(TRIM, 0.2f), Offset(123f, wireY), Offset(ex - 6f, ey - 4f), 1f)
        rotate(80f + sway * 4f, Offset(ex, ey)) {
            drawRoundRect(back(GUM, 0.15f), Offset(ex - 12f, ey + 4f), Size(26f, 4f), CornerRadius(2f))
            drawRoundRect(back(SNEAKER, 0.15f), Offset(ex - 12f, ey - 6f), Size(24f, 11f), CornerRadius(5f))
            drawOval(back(SNEAKER, 0.15f), Offset(ex + 2f, ey - 3f), Size(12f, 8f))
            drawLine(back(SNEAKER_STRIPE, 0.15f), Offset(ex - 8f, ey + 2f), Offset(ex + 5f, ey - 3f), 2.2f, cap = StrokeCap.Round)
        }
    }

    // ---- sidewalk: slabs with joints, a crack, the curb, a strip of street ----
    drawRect(back(CONCRETE), Offset(l, walk), Size(r - l, curb - walk))
    drawLine(back(CONCRETE_DARK), Offset(l, 262f), Offset(r, 262f), 1.2f)
    var jx = -400f
    while (jx < 800f) {                                         // joints converge a little toward the wall
        val xb = jx; val xt = 200f + (jx - 200f) * 0.86f
        if (xb > l - 80f && xb < r + 80f) drawLine(back(CONCRETE_DARK), Offset(xt, walk), Offset(xb, curb), 1.2f)
        jx += 76f
    }
    p.reset(); p.moveTo(264f, 262f); p.lineTo(272f, 270f); p.lineTo(269f, 276f); p.lineTo(280f, 290f)
    drawPath(p, back(CONCRETE_DARK, 0.15f), style = Stroke(1f))
    drawRect(back(CURB, 0.3f), Offset(l, curb), Size(r - l, 5f))
    if (b > curb + 5f) {
        drawRect(back(ASPHALT, 0.2f), Offset(l, curb + 5f), Size(r - l, b - curb - 5f))
        drawOval(back(IRON, 0.1f), Offset(36f, curb + 12f), Size(52f, 12f))                    // manhole
        drawOval(back(CONCRETE_DARK, 0.2f), Offset(36f, curb + 12f), Size(52f, 12f), style = Stroke(1.2f))
        // a puddle in the gutter holding the neon; the kick ripples it
        val py = curb + 12f; val pxc = 300f
        drawOval(Color(0xFF0D0C14), Offset(pxc - 60f, py), Size(120f, 14f))
        drawOval(NEON_PINK.copy(alpha = 0.18f + 0.25f * level + 0.3f * crashK), Offset(pxc - 40f, py + 3f), Size(52f, 5f))
        drawOval(NEON_CYAN.copy(alpha = 0.14f + 0.3f * hatK), Offset(pxc + 18f, py + 4f), Size(26f, 4f))
        if (kickK > 0.03f) for (k in 0..1) {
            val rr = 8f + (1f - kickK) * 40f + k * 12f
            drawOval(Color.White.copy(alpha = 0.25f * kickK), Offset(pxc - rr, py + 7f - rr * 0.12f), Size(rr * 2f, rr * 0.24f), style = Stroke(0.8f))
        }
    }
    // steam rising out of the manhole, drifting up past the curb
    for (k in 0 until 3) {
        val ph = ((now * 0.16f + k / 3f) % 1f)
        val sx = 62f + sin(now * 0.9f + k * 2f) * 8f * ph
        drawCircle(Color.White.copy(alpha = (0.14f + 0.12f * kickK) * (1f - ph) * (0.6f + 0.4f * ph)), 7f + 18f * ph + 4f * kickK, Offset(sx, curb + 14f - 90f * ph))
    }
    // the drum rug (every kit sits on one, or it walks): a worn red runner in perspective, border, medallion, fringe
    p.reset(); p.moveTo(96f, 266f); p.lineTo(334f, 266f); p.lineTo(362f, 292f); p.lineTo(68f, 292f); p.close()
    drawPath(p, back(Color(0xFF7A2430), 0.25f))
    p.reset(); p.moveTo(104f, 269f); p.lineTo(326f, 269f); p.lineTo(350f, 289f); p.lineTo(80f, 289f); p.close()
    drawPath(p, back(Color(0xFFC9A15A), 0.35f), style = Stroke(1.4f))
    p.reset(); p.moveTo(215f, 271f); p.lineTo(245f, 279f); p.lineTo(215f, 287f); p.lineTo(185f, 279f); p.close()
    drawPath(p, back(Color(0xFF233A5E), 0.3f)); drawPath(p, back(Color(0xFFC9A15A), 0.35f), style = Stroke(1f))
    var fx = 72f; while (fx < 360f) { drawLine(back(TRIM, 0.4f), Offset(fx, 292f), Offset(fx - 1f, 296f), 0.8f); fx += 4f }
    // soft shadow under the kit
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(60f, 276f), Size(310f, 16f))

    // ---- boombox on a milk crate (your left); the woofers pump on the kick ----
    val cx0 = 2f; val cyT = 256f
    drawRect(back(CRATE, 0.3f), Offset(cx0, cyT), Size(40f, FLOOR - cyT))
    for (i in 0 until 3) for (j in 0 until 2) drawRoundRect(back(CRATE_DARK, 0.3f), Offset(cx0 + 4f + i * 12f, cyT + 5f + j * 12f), Size(8f, 8f), CornerRadius(2f))
    val bbT = 234f
    drawLine(back(CHROME, 0.3f), Offset(cx0 + 6f, bbT), Offset(cx0 + 10f, bbT - 6f), 2f)        // handle
    drawLine(back(CHROME, 0.3f), Offset(cx0 + 10f, bbT - 6f), Offset(cx0 + 32f, bbT - 6f), 2f)
    drawLine(back(CHROME, 0.3f), Offset(cx0 + 32f, bbT - 6f), Offset(cx0 + 36f, bbT), 2f)
    drawLine(back(CHROME, 0.3f), Offset(cx0 + 36f, bbT), Offset(cx0 + 48f, bbT - 22f), 1f)        // antenna
    drawRoundRect(back(Color(0xFF2A2A31), 0.2f), Offset(cx0 - 1f, bbT), Size(44f, 22f), CornerRadius(3f))
    drawRoundRect(back(CHROME_DARK, 0.3f), Offset(cx0 + 15f, bbT + 4f), Size(12f, 8f), CornerRadius(1f))   // tape deck
    val cone = 6.2f + 1.6f * kickK
    for (sx in floatArrayOf(cx0 + 8f, cx0 + 34f)) {
        drawCircle(back(Color(0xFF15151A), 0.1f), 7.5f, Offset(sx, bbT + 12f))
        drawCircle(back(CHROME_DARK, 0.3f), cone, Offset(sx, bbT + 12f), style = Stroke(1.2f))
        drawCircle(back(CHROME, 0.3f), 1.6f + 0.6f * kickK, Offset(sx, bbT + 12f))
    }
    for (i in 0 until 4) drawRect(lerp(back(Color(0xFF3B3B44), 0.2f), NEON_CYAN, if (i < 1 + (3 * maxOf(kickK, level)).toInt()) 0.7f else 0f), Offset(cx0 + 15f + i * 3.2f, bbT + 15f), Size(2.2f, 3f))
    if (kickK > 0.05f) for (sx in floatArrayOf(cx0 + 8f, cx0 + 34f)) {                      // the woofers push air on the kick
        val rr = 9f + (1f - kickK) * 16f
        drawCircle(Color.White.copy(alpha = 0.22f * kickK), rr, Offset(sx, bbT + 12f), style = Stroke(1f))
    }

    // ---- hydrant, sidewalk right ----
    val hx = 388f
    drawRoundRect(back(HYDRANT, 0.15f), Offset(hx - 7f, 262f), Size(14f, 22f), CornerRadius(3f))
    drawRoundRect(back(HYDRANT, 0.1f), Offset(hx - 9f, 280f), Size(18f, 5f), CornerRadius(2f))
    drawOval(back(HYDRANT, 0.1f), Offset(hx - 8f, 256f), Size(16f, 10f))
    drawRect(back(HYDRANT, 0.05f), Offset(hx - 12f, 268f), Size(24f, 5f))
    drawCircle(back(CHROME_DARK, 0.2f), 1.6f, Offset(hx, 256f))
    // a crash lights the whole corner up for a moment
    if (crashK > 0.02f) drawRect(NEON_PINK.copy(alpha = 0.06f * crashK), Offset(l, t), Size(r - l, b - t))
}

/** A wheatpasted poster: paper, a colour block header, a few lines of type, one torn corner. */
private fun DrawScope.poster(x: Float, y: Float, w: Float, h: Float, tilt: Float, paper: Color, ink: Color) {
    rotate(tilt, Offset(x + w / 2f, y + h / 2f)) {
        drawRect(paper, Offset(x, y), Size(w, h))
        drawRect(ink, Offset(x + 4f, y + 4f), Size(w - 8f, h * 0.3f))
        for (k in 0 until 3) drawLine(ink.copy(alpha = 0.8f), Offset(x + 5f, y + h * 0.45f + k * 5f), Offset(x + w - 5f - (k % 2) * 8f, y + h * 0.45f + k * 5f), 1.3f)
        drawLine(Color.Black.copy(alpha = 0.25f), Offset(x + w - 9f, y + h), Offset(x + w, y + h - 9f), 1f)
    }
}

/** A neon word, [w] stage units wide at ([x], [y]): a soft tube glow, then the bright tube. */
private fun DrawScope.neonWord(t: androidx.compose.ui.text.TextLayoutResult, x: Float, y: Float, w: Float, c: Color, on: Float) {
    val fit = w / max(1f, t.size.width.toFloat())
    withTransform({ translate(x, y); scale(fit, fit, Offset.Zero) }) {
        // [on] can run past 1 (a crash): the tube goes white-hot and the glow thickens; alphas stay ≤ 1
        val o = on.coerceIn(0f, 1f); val hot = (on - 1f).coerceIn(0f, 1f)
        drawText(t, color = c.copy(alpha = (0.22f * o + 0.25f * hot).coerceAtMost(1f)), drawStyle = NEON_GLOW)
        drawText(t, color = lerp(c, Color.White, 0.35f * o + 0.5f * hot).copy(alpha = 0.35f + 0.65f * o))
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}

private val CAP = Color(0xFF14213D)            // navy fitted
private val CAP_SHADE = Color(0xFF0C152A)
private val STICKER = Color(0xFFE0B03A)

/**
 * The fitted cap: navy crown with panel seams, a top button, a dog's face embroidered on the front
 * and the gold size sticker left on the brim, the way it should be.
 */
private fun DrawScope.fittedCap(cx: Float, cy: Float, capTop: Float, hop: Float) {
    val base = cy - 22f - hop * 0.5f
    drawArc(CAP, 180f, 180f, true, Offset(cx - 44f, capTop), Size(88f, (base - capTop) * 2f))
    drawRect(CAP, Offset(cx - 44f, base - 6f), Size(88f, 8f))
    drawLine(CAP_SHADE, Offset(cx, capTop + 2f), Offset(cx, base), 1.5f)
    drawLine(CAP_SHADE, Offset(cx - 16f, capTop + 6f), Offset(cx - 25f, base), 1.5f)
    drawLine(CAP_SHADE, Offset(cx + 16f, capTop + 6f), Offset(cx + 25f, base), 1.5f)
    drawCircle(CAP_SHADE, 3.5f, Offset(cx, capTop + 1f))
    val mx = cx; val my = capTop + (base - capTop) * 0.55f
    // the patch: a dog's face (floppy ears, a muzzle, a little smile), embroidered in white
    val ink = Color(0xFFF4F4F5)
    withTransform({ translate(mx, my); scale(1.3f, 1.3f, Offset.Zero) }) {
        rotate(25f, Offset(-7.5f, -1f)) { drawOval(ink, Offset(-10.9f, -6.4f), Size(6.8f, 10.8f)) }
        rotate(-25f, Offset(7.5f, -1f)) { drawOval(ink, Offset(4.1f, -6.4f), Size(6.8f, 10.8f)) }
        drawOval(ink, Offset(-7f, -7f), Size(14f, 13f))
        drawOval(CHROME, Offset(-4.6f, -0.6f), Size(9.2f, 6.4f))
        drawOval(CAP, Offset(-2f, 0.1f), Size(4f, 2.6f))
        drawCircle(CAP, 1f, Offset(-3f, -2.6f)); drawCircle(CAP, 1f, Offset(3f, -2.6f))
        drawArc(CAP, 20f, 140f, false, Offset(-2f, 2.6f), Size(4f, 3f), style = Stroke(0.7f))
    }
    drawOval(CAP_SHADE, Offset(cx - 50f, base - 4f), Size(100f, 14f))
    drawOval(CAP, Offset(cx - 50f, base - 6f), Size(100f, 12f))
    drawCircle(STICKER, 4.5f, Offset(cx + 28f, base))
    drawCircle(Color(0xFFC0C0C8), 1.8f, Offset(cx + 28f, base))
}
