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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.model.DRUM_SLOTS
import com.thorbox.model.DrumModel
import com.thorbox.model.DrumVoice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * BIG DAWG, the drummer: a stocky, unbothered bulldog in a fitted cap and a gold chain, who plays
 * exactly the kit the drum synth has loaded.
 *
 * The kit IS the sound:
 *  - Only the pieces the kit's voices map to are on stage (no tom voice, no tom; a cowbell voice adds
 *    a cowbell; ZAP brings a little electronic pad; SHAKE puts a shaker in his paw).
 *  - Each drum's size follows its TUNE (tuned down = bigger shell), its glow and its ring last as long
 *    as its DECAY, louder LEVEL = brighter flash, DRIVE roughens the ripple, and a hat's openness
 *    follows the voice that hit it. Two toms become rack + floor by pitch.
 *  - Every piece flashes in the colour of the slot that hit it (same colours as the pads and grid rows).
 *
 * Character: half-lidded and chill, nods on the beat, jowls and ears bounce on the kick, the chain
 * swings, the stub tail wags in time. Big accents get a grin (and the gold tooth). Stopped for a
 * while, he naps (z). All original artwork; no allocation per frame (one reused Path, plain floats).
 * Stage: 400 × 300 virtual units, scaled uniformly to fit.
 */

private enum class Piece { KICK, SNARE, RIM, HIHAT, RACK_TOM, FLOOR_TOM, CYMBAL, COWBELL, CLAP, ZAP, SHAKER }

private class Hand(val restX: Float, val restY: Float) {
    var fromX = restX; var fromY = restY
    var at = -10f
}

private class DrummerState {
    val left = Hand(154f, 150f)
    val right = Hand(246f, 150f)
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
    val path = Path()
}

// Big Dawg's colours: his own, not the theme's (he looks the same on every theme).
private val COAT = Color(0xFFD9A066)
private val COAT_DARK = Color(0xFFB07A3E)
private val BLAZE = Color(0xFFF3E8DA)
private val MASK = Color(0xFF4A3426)
private val NOSE = Color(0xFF221814)
private val GOLD = Color(0xFFE6B93C)
private val GOLD_DARK = Color(0xFFB8892A)

private fun pieceFor(v: DrumVoice, slot: Int, toms: IntArray): Piece = when (v.model) {
    DrumModel.KICK -> Piece.KICK
    DrumModel.SNARE -> Piece.SNARE
    DrumModel.RIM -> Piece.RIM
    DrumModel.CLAP -> Piece.CLAP
    DrumModel.HAT -> Piece.HIHAT
    // Two (or more) toms: the higher-tuned one is the rack tom, the lower the floor tom. One tom: by its tune.
    DrumModel.TOM -> if (toms[1] < 0) (if (v.tune < -5f) Piece.FLOOR_TOM else Piece.RACK_TOM) else if (slot == toms[0]) Piece.RACK_TOM else Piece.FLOOR_TOM
    DrumModel.CYMBAL -> Piece.CYMBAL
    DrumModel.COWBELL -> Piece.COWBELL
    DrumModel.ZAP -> Piece.ZAP
    DrumModel.SHAKER -> Piece.SHAKER
}

/** Strike point for each piece (stage units), given its size factor. */
private fun strikeX(p: Piece): Float = when (p) {
    // where the stick TIP lands (near edge of each piece); the paw stays close to the body
    Piece.SNARE -> 132f; Piece.RIM -> 106f; Piece.HIHAT -> 92f; Piece.RACK_TOM -> 172f; Piece.FLOOR_TOM -> 282f
    Piece.CYMBAL -> 300f; Piece.COWBELL -> 252f; Piece.ZAP -> 334f; else -> 200f
}
/** Paw position for a strike: just above the skin / edge, so the stick tip lands on it. */
private fun strikeY(p: Piece): Float = when (p) {
    Piece.SNARE -> 180f; Piece.RIM -> 184f; Piece.HIHAT -> 150f; Piece.RACK_TOM -> 170f; Piece.FLOOR_TOM -> 186f
    Piece.CYMBAL -> 106f; Piece.COWBELL -> 164f; Piece.ZAP -> 174f; else -> 140f
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
    val tm = rememberTextMeasurer()
    val toms = remember { IntArray(2) }
    val logo = remember { TextStyle(color = Color(0xFFF4F4F5), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    Canvas(modifier) {
        tel.frame.longValue                                   // redraw every frame, never recompose
        val now = tel.timeSec
        val voices = drums.kit.value.voices

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
        for (s in 0 until min(DRUM_SLOTS, voices.size)) {
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
            when (p) {
                Piece.KICK -> Unit                                         // the foot does it
                Piece.CLAP -> { st.left.at = t; st.right.at = t; st.left.fromX = 190f; st.left.fromY = 70f; st.right.fromX = 210f; st.right.fromY = 70f }
                else -> {
                    val x = strikeX(p); val y = strikeY(p)
                    val leftSide = x < 200f
                    val hand = if (leftSide) (if (t - st.left.at < 0.07f) st.right else st.left)
                               else (if (t - st.right.at < 0.07f) st.left else st.right)
                    hand.at = t; hand.fromX = x; hand.fromY = y
                }
            }
        }

        // virtual stage 400 × 280 (y 8..288): cap top to floor, no dead band above
        val sc = min(size.width / 400f, size.height / 280f)
        val ox = (size.width - 400f * sc) / 2f; val oy = (size.height - 280f * sc) / 2f - 8f * sc
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            val playing = state.groove.transport[1] != 0.0
            val beatPhase = ((state.groove.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat()
            val nod = if (playing) abs(sin(PI.toFloat() * beatPhase)) * 4f else sin(now * 1.1f) * 1.2f
            val kickK = env(now - st.pieceHit[Piece.KICK.ordinal], 14f)
            val sleepy = !playing && now - st.lastActivity > 8f

            drawLine(Tb.Line, Offset(10f, 284f), Offset(390f, 284f), 2f)

            // ---- back row: cymbal, hats ----
            if (st.present[Piece.CYMBAL.ordinal]) cymbalStand(st, now)
            if (st.present[Piece.HIHAT.ordinal]) hihat(st, now)

            // ---- Big Dawg ----
            val bodyY = nod * 0.6f + kickK * 2.5f
            tail(now, playing, beatPhase)
            drawRoundRect(Tb.Raised, Offset(170f, 240f), Size(60f, 10f), CornerRadius(5f))          // stool
            drawLine(Tb.Raised, Offset(200f, 250f), Offset(200f, 284f), 6f)
            body(st, now, bodyY, beatPhase, playing)
            // Arms go BEHIND the drums (the kit hides them where it's in front); only paws + sticks
            // are drawn over the kit, so nothing crosses the shells.
            val beatL = if (playing) -6f * (1f - abs(sin(PI.toFloat() * beatPhase))) else 0f
            val beatR = if (playing) -6f * (1f - abs(sin(PI.toFloat() * ((beatPhase + 0.5f) % 1f)))) else 0f
            // pawPos is where the stick tip is heading; reach() keeps the paw near the shoulder
            // and lets the stick cover the rest, so arms never sweep across the kit.
            val lt = pawPos(st.left, now, beatL); val rt = pawPos(st.right, now, beatR)
            val lsy = 144f + bodyY; val rsy = 144f + bodyY
            val lp = reach(180f, lsy, lt); val lx = lp.x; val ly = lp.y
            val rp = reach(220f, rsy, rt); val rx = rp.x; val ry = rp.y
            arm(180f, lsy, lx, ly, mirror = false)
            arm(220f, rsy, rx, ry, mirror = true)
            head(st, now, bodyY + nod * 0.5f, sleepy, kickK)

            // ---- front row ----
            if (st.present[Piece.RACK_TOM.ordinal]) tom(st, Piece.RACK_TOM, 170f, 176f, 40f, 18f, now)
            if (st.present[Piece.COWBELL.ordinal]) cowbell(st, now)
            if (st.present[Piece.SNARE.ordinal]) snare(st, now)
            if (st.present[Piece.FLOOR_TOM.ordinal]) tom(st, Piece.FLOOR_TOM, 296f, 194f, 62f, 36f, now)
            if (st.present[Piece.ZAP.ordinal]) zapPad(st, now)
            if (st.present[Piece.KICK.ordinal]) kick(st, now, kickK, tm, logo)

            // ---- paws + sticks on top; between hits they ride the beat ----
            val shaking = st.present[Piece.SHAKER.ordinal]
            paw(st.left, lx, ly, lt, now, mirror = false, stick = true)
            paw(st.right, rx, ry, rt, now, mirror = true, stick = !shaking || now - st.pieceHit[Piece.SHAKER.ordinal] > 0.6f)
            if (shaking) shaker(st, now)
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
            val x = cx + kotlin.math.cos(a) * rx * grow * j; val y = cy + sin(a) * ry * grow * j
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c, style = Stroke(3f))
    }
}

// ----------------------------------------------------------------------------- kit

private fun DrawScope.drumShell(cx: Float, top: Float, w: Float, h: Float, depth: Float, squash: Float, accent: Color, flash: Float) {
    val sh = h * (1f - 0.35f * squash)
    drawRect(Tb.Raised, Offset(cx - w / 2, top), Size(w, depth))
    drawOval(Tb.Raised, Offset(cx - w / 2, top + depth - sh / 2), Size(w, sh))
    // shell stripe in the slot colour, so you can tell which voice this drum is
    drawRect(accent.copy(alpha = 0.55f), Offset(cx - w / 2, top + depth * 0.45f), Size(w, max(2f, depth * 0.18f)))
    drawOval(lerp(Tb.Panel, accent, 0.18f + 0.82f * flash), Offset(cx - w / 2, top - sh / 2 + squash * 2f), Size(w, sh))
    drawOval(Tb.Text, Offset(cx - w / 2, top - sh / 2 + squash * 2f), Size(w, sh), style = Stroke(2.5f))
}

private fun DrawScope.snare(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.SNARE.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.SNARE.ordinal]
    val c = slotColor(st, Piece.SNARE)
    drawLine(Tb.Line, Offset(120f, 204f), Offset(110f, 284f), 3f); drawLine(Tb.Line, Offset(120f, 204f), Offset(132f, 284f), 3f)
    drumShell(120f, 186f, 70f * s, 20f * s, 16f, env(dt, 30f), c, envFor(dt, ringSec(v) * 0.7f) * hitGain(st.pieceVel[Piece.SNARE.ordinal], v))
    // snare wires rattle underneath for as long as it rings
    val rattle = envFor(dt, ringSec(v))
    if (rattle > 0.05f) for (i in 0 until 4) {
        val y = 204f + i * 2f + sin(now * 90f + i) * 1.5f * rattle
        drawLine(Tb.Text.copy(alpha = rattle), Offset(120f - 26f * s, y), Offset(120f + 26f * s, y), 1f)
    }
    ripple(st, Piece.SNARE, 120f, 186f, 35f * s, 10f * s, now)
    // RIM: a click on the hoop: the rim lights instead of the skin
    val rdt = now - st.pieceHit[Piece.RIM.ordinal]
    val rk = env(rdt, 18f)
    if (rk > 0.02f) drawOval(slotColor(st, Piece.RIM).copy(alpha = rk), Offset(120f - 35f * s, 186f - 10f * s), Size(70f * s, 20f * s), style = Stroke(5f))
}

private fun DrawScope.tom(st: DrummerState, p: Piece, cx: Float, top: Float, w: Float, depth: Float, now: Float) {
    val v = st.pieceVoice[p.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[p.ordinal]
    val c = slotColor(st, p)
    if (p == Piece.FLOOR_TOM) { drawLine(Tb.Line, Offset(cx - 24f * s, top + 24f), Offset(cx - 28f * s, 284f), 3f); drawLine(Tb.Line, Offset(cx + 24f * s, top + 24f), Offset(cx + 28f * s, 284f), 3f) }
    drumShell(cx, top, w * s, w * 0.32f * s, depth * s, env(dt, 26f), c, envFor(dt, ringSec(v)) * hitGain(st.pieceVel[p.ordinal], v))
    ripple(st, p, cx, top, w * s / 2f, w * 0.16f * s, now)
}

private fun DrawScope.kick(st: DrummerState, now: Float, k: Float, tm: androidx.compose.ui.text.TextMeasurer, logo: TextStyle) {
    val v = st.pieceVoice[Piece.KICK.ordinal]
    val s = sizeOf(v)
    val dt = now - st.pieceHit[Piece.KICK.ordinal]
    val c = slotColor(st, Piece.KICK)
    val r = 42f * s * (1f + 0.07f * k)
    val cy = 284f - r - 2f
    drawCircle(Tb.Panel, r, Offset(200f, cy))
    drawCircle(lerp(Tb.Raised, c, 0.25f + 0.75f * envFor(dt, ringSec(v)) * hitGain(st.pieceVel[Piece.KICK.ordinal], v)), r, Offset(200f, cy), style = Stroke(9f))
    drawCircle(Tb.Text, r - 9f, Offset(200f, cy), style = Stroke(2f))
    // the band name on the front head, like every proper kick
    val t = tm.measure("BIG DAWG", logo)
    // scaled to sit inside the front head, whatever the screen density
    val fit = min(1f, (1.25f * r) / (t.size.width + 12f))
    withTransform({ scale(fit, fit, Offset(200f, cy)) }) {
        drawRoundRect(MASK, Offset(200f - t.size.width / 2f - 6f, cy - t.size.height / 2f - 3f), Size(t.size.width + 12f, t.size.height + 6f), CornerRadius(6f))
        drawText(t, topLeft = Offset(200f - t.size.width / 2f, cy - t.size.height / 2f))
    }
    // pedal beater (back paw) swings in on the hit
    val swing = env(dt, 14f)
    drawLine(Tb.Text, Offset(232f, 282f), Offset(222f - 10f * swing, cy + 16f - 5f * swing), 4f, cap = StrokeCap.Round)
    drawCircle(Tb.Text, 4f, Offset(222f - 10f * swing, cy + 16f - 5f * swing))
    ripple(st, Piece.KICK, 200f, cy, r, r, now)
}

private fun DrawScope.cymbal(cx: Float, cy: Float, w: Float, tilt: Float, wobble: Float, c: Color, flash: Float) {
    rotate(tilt + wobble, Offset(cx, cy)) {
        drawOval(lerp(Tb.Raised, c, 0.2f + 0.8f * flash), Offset(cx - w / 2, cy - 6f), Size(w, 12f))
        drawOval(Tb.Text, Offset(cx - w / 2, cy - 6f), Size(w, 12f), style = Stroke(2.5f))
        drawCircle(Tb.Text, 3f, Offset(cx, cy - 1f))
    }
}

private fun DrawScope.cymbalStand(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.CYMBAL.ordinal]
    val dt = now - st.pieceHit[Piece.CYMBAL.ordinal]
    val ring = ringSec(v)
    // Long decay = big ride-ish wash: wider cymbal, slower wobble.
    val w = (70f + 40f * ((ring - 0.3f) / 2f).coerceIn(0f, 1f))
    val wob = if (dt in 0f..ring * 2f) sin(dt * (30f - 8f * min(1f, ring))) * exp(-dt * 3f / ring) * 12f else 0f
    drawLine(Tb.Line, Offset(326f, 110f), Offset(342f, 284f), 3f)
    cymbal(326f, 108f, w, -10f, wob, slotColor(st, Piece.CYMBAL), envFor(dt, ring) * hitGain(st.pieceVel[Piece.CYMBAL.ordinal], v))
}

private fun DrawScope.hihat(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.HIHAT.ordinal]
    val dt = now - st.pieceHit[Piece.HIHAT.ordinal]
    val c = slotColor(st, Piece.HIHAT)
    val ring = ringSec(v)
    // Openness follows the voice that hit: a closed hat barely parts, an open hat lifts and wobbles.
    val open = if (dt in 0f..ring) st.hatOpenness * exp(-dt * 2f / ring) else 0f
    val gap = 2.5f + 10f * open + 2f * sin(dt * 40f) * open
    val wob = sin(dt * 30f) * open * 6f
    drawLine(Tb.Line, Offset(64f, 156f), Offset(64f, 284f), 3f)
    cymbal(64f, 156f, 70f, 0f, 0f, c, envFor(dt, ring))
    cymbal(64f, 156f - gap, 70f, 0f, wob, c, envFor(dt, ring))
}

private fun DrawScope.cowbell(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.COWBELL.ordinal]
    val dt = now - st.pieceHit[Piece.COWBELL.ordinal]
    val c = slotColor(st, Piece.COWBELL)
    val s = sizeOf(v)
    val shake = if (dt in 0f..0.4f) sin(dt * 60f) * exp(-dt * 10f) * 4f else 0f
    rotate(shake, Offset(252f, 172f)) {
        val p = st.path.apply { reset(); moveTo(252f - 9f * s, 160f); lineTo(252f + 9f * s, 160f); lineTo(252f + 12f * s, 160f + 22f * s); lineTo(252f - 12f * s, 160f + 22f * s); close() }
        drawPath(p, lerp(Tb.Raised, c, 0.3f + 0.7f * envFor(dt, ringSec(v))))
        drawPath(p, Tb.Text, style = Stroke(2.5f))
    }
}

/** ZAP: a little electronic pad on a stand that throws sparks. */
private fun DrawScope.zapPad(st: DrummerState, now: Float) {
    val v = st.pieceVoice[Piece.ZAP.ordinal]
    val dt = now - st.pieceHit[Piece.ZAP.ordinal]
    val c = slotColor(st, Piece.ZAP)
    val k = envFor(dt, ringSec(v))
    drawLine(Tb.Line, Offset(344f, 186f), Offset(344f, 284f), 3f)
    drawRoundRect(lerp(Tb.Raised, c, 0.2f + 0.8f * k), Offset(326f, 172f), Size(36f, 14f), CornerRadius(4f))
    drawRoundRect(Tb.Text, Offset(326f, 172f), Size(36f, 14f), CornerRadius(4f), style = Stroke(2f))
    if (k > 0.05f) for (i in 0 until 3) {
        val a = -PI.toFloat() / 2f + (i - 1) * 0.6f
        val x0 = 344f + kotlin.math.cos(a) * 12f; val y0 = 170f + sin(a) * 6f
        val x1 = 344f + kotlin.math.cos(a) * (22f + 14f * k); val y1 = 168f + sin(a) * (16f + 12f * k)
        drawLine(c.copy(alpha = k), Offset(x0, y0), Offset((x0 + x1) / 2f + 4f, (y0 + y1) / 2f), 2.5f, cap = StrokeCap.Round)
        drawLine(c.copy(alpha = k), Offset((x0 + x1) / 2f + 4f, (y0 + y1) / 2f), Offset(x1, y1), 2.5f, cap = StrokeCap.Round)
    }
}

/** SHAKE: an egg shaker in the right paw, jiggling while it sounds. */
private fun DrawScope.shaker(st: DrummerState, now: Float) {
    val dt = now - st.pieceHit[Piece.SHAKER.ordinal]
    if (dt !in 0f..0.6f) return
    val c = slotColor(st, Piece.SHAKER)
    val j = sin(dt * 70f) * exp(-dt * 6f) * 7f
    drawOval(c, Offset(266f + j, 118f), Size(18f, 24f))
    drawOval(Tb.Text, Offset(266f + j, 118f), Size(18f, 24f), style = Stroke(2f))
}

/** CLAP: paws meet above his head, with a burst in the clap's colour. */
private fun DrawScope.clapBurst(st: DrummerState, now: Float) {
    val dt = now - st.pieceHit[Piece.CLAP.ordinal]
    val k = 1f - dt / 0.25f
    val c = slotColor(st, Piece.CLAP).copy(alpha = k)
    for (i in 0 until 6) {
        val a = PI.toFloat() * (1.1f + i * 0.16f)
        val r0 = 14f + 10f * (1f - k); val r1 = r0 + 10f
        drawLine(c, Offset(200f + kotlin.math.cos(a) * r0, 66f + sin(a) * r0), Offset(200f + kotlin.math.cos(a) * r1, 66f + sin(a) * r1), 3f, cap = StrokeCap.Round)
    }
}

// ----------------------------------------------------------------------------- Big Dawg

/** Stub tail behind the stool, wagging in time while the beat runs. */
private fun DrawScope.tail(now: Float, playing: Boolean, beatPhase: Float) {
    val wag = if (playing) sin(2f * PI.toFloat() * beatPhase) * 18f else sin(now * 2f) * 4f
    rotate(wag, Offset(232f, 232f)) {
        drawLine(COAT_DARK, Offset(232f, 232f), Offset(252f, 220f), 9f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.body(st: DrummerState, now: Float, y: Float, beatPhase: Float, playing: Boolean) {
    // barrel chest: one clean shape with a soft outline (no clutter), white blaze down the front
    drawRoundRect(COAT_DARK, Offset(163f, 126f + y), Size(74f, 100f), CornerRadius(32f))
    drawRoundRect(COAT, Offset(165f, 128f + y), Size(70f, 96f), CornerRadius(30f))
    drawOval(BLAZE, Offset(186f, 142f + y), Size(28f, 50f))
    // gold chain: swings with the beat, a heavy link pendant
    val swing = if (playing) sin(2f * PI.toFloat() * beatPhase) * 5f else 0f
    val path = st.path; path.reset()
    path.moveTo(180f, 132f + y); path.quadraticTo(200f + swing, 162f + y, 220f, 132f + y)
    drawPath(path, GOLD_DARK, style = Stroke(4f))
    drawPath(path, GOLD, style = Stroke(2.2f))
    // pendant: a small gold tag, swinging with the chain
    val tx = 200f + swing * 0.7f; val ty = 148f + y
    drawCircle(GOLD_DARK, 6.5f, Offset(tx, ty + 5f))
    drawCircle(GOLD, 5.2f, Offset(tx, ty + 5f))
    drawCircle(Color.White.copy(alpha = 0.6f), 1.4f, Offset(tx - 1.8f, ty + 3.2f))
}

private fun DrawScope.head(st: DrummerState, now: Float, y: Float, sleepy: Boolean, kickK: Float) {
    val cx = 200f; val cy = 90f + y
    val hop = 4f * kickK + (if (now < st.grinUntil) 2f else 0f)
    val jowl = 3f * kickK                                         // jowls drop and bounce on the kick
    // rose ears: small, folded back, flapping a touch on hits
    val flap = 8f * kickK
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
    val k = if (dt < 0f) 1f else min(1f, dt / 0.2f).let { x -> 1f + 1.7f * (x - 1f) * (x - 1f) * (x - 1f) + 0.7f * (x - 1f) * (x - 1f) }
    return Offset(h.fromX + (h.restX - h.fromX) * k, h.fromY + (h.restY + groove - h.fromY) * k)
}

private const val ARM_REACH = 70f      // shoulder → paw, max
private const val STICK = 46f          // paw → tip, max

/** Paw position for a stick-tip target: arm takes up to ARM_REACH, the stick the rest (up to STICK). */
private fun reach(sx: Float, sy: Float, tip: Offset): Offset {
    val dx = tip.x - sx; val dy = tip.y - sy
    val d = sqrt(dx * dx + dy * dy)
    if (d < 1f) return tip
    val use = (d - STICK).coerceIn(min(d, 40f), ARM_REACH)
    return Offset(sx + dx / d * use, sy + dy / d * use)
}

/** Upper arm + forearm, one smooth tapered limb with a soft elbow. Drawn behind the kit. */
private fun DrawScope.arm(sx: Float, sy: Float, hx: Float, hy: Float, mirror: Boolean) {
    val dx = hx - sx; val dy = hy - sy
    val len = max(1f, sqrt(dx * dx + dy * dy))
    val bend = max(0f, 30f - len * 0.35f)
    val side = if (mirror) 1f else -1f
    val ex = sx + dx * 0.5f + (-dy / len) * bend * side
    val ey = sy + dy * 0.5f + (dx / len) * bend * side + 6f       // elbows hang a little
    drawLine(COAT_DARK, Offset(sx, sy), Offset(ex, ey), 15f, cap = StrokeCap.Round)
    drawLine(COAT_DARK, Offset(ex, ey), Offset(hx, hy), 13f, cap = StrokeCap.Round)
    drawLine(COAT, Offset(sx, sy), Offset(ex, ey), 12f, cap = StrokeCap.Round)
    drawLine(COAT, Offset(ex, ey), Offset(hx, hy), 10f, cap = StrokeCap.Round)
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
        val restX = dir * 22f; val restY = -18f
        val tipX = hx + ax + (restX - ax) * raise
        val tipY = hy + ay + (restY - ay) * raise
        drawLine(Tb.Black, Offset(hx, hy), Offset(tipX, tipY), 6f, cap = StrokeCap.Round)
        drawLine(Tb.Key, Offset(hx, hy), Offset(tipX, tipY), 3.5f, cap = StrokeCap.Round)
    }
    drawCircle(COAT_DARK, 8.5f, Offset(hx, hy))
    drawCircle(BLAZE, 7f, Offset(hx, hy))
    for (i in -1..1) drawLine(COAT_DARK.copy(alpha = 0.7f), Offset(hx + i * 3f, hy + 2f), Offset(hx + i * 3f, hy + 5.5f), 1.3f, cap = StrokeCap.Round)
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}

private val CAP = Color(0xFF14213D)            // navy fitted
private val CAP_SHADE = Color(0xFF0C152A)
private val STICKER = Color(0xFFE0B03A)

/**
 * The fitted cap: navy crown with panel seams, a top button, the interlocking "CB" monogram
 * (Clamshell Beats; our own mark) and the gold size sticker left on the brim, the way it should be.
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
    val ink = Color(0xFFF4F4F5)
    drawArc(ink, 50f, 260f, false, Offset(mx - 11f, my - 8f), Size(14f, 16f), style = Stroke(2.4f, cap = StrokeCap.Round))
    drawLine(ink, Offset(mx - 1f, my - 8f), Offset(mx - 1f, my + 8f), 2.4f, cap = StrokeCap.Round)
    drawArc(ink, -90f, 180f, false, Offset(mx - 5f, my - 8f), Size(11f, 8f), style = Stroke(2.2f))
    drawArc(ink, -90f, 180f, false, Offset(mx - 5f, my), Size(13f, 8f), style = Stroke(2.2f))
    drawOval(CAP_SHADE, Offset(cx - 50f, base - 4f), Size(100f, 14f))
    drawOval(CAP, Offset(cx - 50f, base - 6f), Size(100f, 12f))
    drawCircle(STICKER, 4.5f, Offset(cx + 28f, base))
    drawCircle(Color(0xFFC0C0C8), 1.8f, Offset(cx + 28f, base))
}
