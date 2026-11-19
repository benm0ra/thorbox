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
import com.thorbox.app.AppState
import com.thorbox.model.DRUM_SLOTS
import com.thorbox.model.DrumModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * BIT, the drummer. Design notes (Thorbox UX):
 *  - Every kit piece is struck by the slot that fired it, and flashes in THAT slot's colour:
 *    the pads, the grid rows and the drum all share one colour language.
 *  - Flat, geometric, thick strokes on black. One white character, coloured only by what it plays.
 *  - Motion is physical but quick: sticks snap down on the hit and spring back (overshoot),
 *    skins squash, cymbals wobble, the kick breathes. Nothing lingers longer than the sound.
 *  - Personality: blinks, nods on the beat while playing, grins (^ ^) on accents, dozes off (z)
 *    when the transport has been stopped a while, and wakes up on the next hit.
 *  - Zero allocation per frame: positions are plain floats, the one Path is reused.
 * Laid out in a 400 × 300 virtual stage, scaled uniformly to fit.
 */

private enum class Piece { KICK, SNARE, HIHAT, RACK_TOM, FLOOR_TOM, CRASH, COWBELL, CLAP, ANTENNA, SHAKER }

private class Hand(val restX: Float, val restY: Float) {
    var fromX = restX; var fromY = restY     // where the stick struck
    var at = -10f                            // clock time of the strike
}

private class DrummerState {
    val left = Hand(150f, 118f)
    val right = Hand(250f, 118f)
    val pieceHit = FloatArray(Piece.entries.size) { -10f }
    val pieceSlot = IntArray(Piece.entries.size)
    val pieceVel = FloatArray(Piece.entries.size) { 1f }
    val seen = FloatArray(DRUM_SLOTS) { -10f }
    var hatOpen = false
    var grinUntil = -10f
    var lastActivity = 0f
    var nextBlink = 2f
    val path = Path()
}

/** The strike point (top of the skin / edge of the cymbal) for each piece, in stage units. */
private fun strike(p: Piece): Pair<Float, Float> = when (p) {
    Piece.SNARE -> 128f to 178f
    Piece.HIHAT -> 66f to 140f
    Piece.RACK_TOM -> 172f to 152f
    Piece.FLOOR_TOM -> 292f to 186f
    Piece.CRASH -> 318f to 104f
    Piece.COWBELL -> 238f to 150f
    Piece.SHAKER -> 270f to 120f
    else -> 200f to 120f
}

private fun pieceFor(model: DrumModel, slot: Int, decayMs: Float, firstTomSlot: Int): Piece = when (model) {
    DrumModel.KICK -> Piece.KICK
    DrumModel.SNARE, DrumModel.RIM -> Piece.SNARE
    DrumModel.CLAP -> Piece.CLAP
    DrumModel.HAT -> Piece.HIHAT
    DrumModel.TOM -> if (slot == firstTomSlot) Piece.RACK_TOM else Piece.FLOOR_TOM
    DrumModel.CYMBAL -> Piece.CRASH
    DrumModel.COWBELL -> Piece.COWBELL
    DrumModel.ZAP -> Piece.ANTENNA
    DrumModel.SHAKER -> Piece.SHAKER
}

@Composable
fun Drummer(state: AppState, modifier: Modifier = Modifier) {
    val st = remember { DrummerState() }
    val drums = state.drums
    val tel = state.telemetry
    Canvas(modifier) {
        tel.frame.longValue                                   // redraw every frame, never recompose
        val now = tel.timeSec
        val voices = drums.kit.value.voices
        val firstTom = voices.indexOfFirst { it.model == DrumModel.TOM }

        // ---- route new hits (live or sequenced) to pieces and hands ----
        for (s in 0 until min(DRUM_SLOTS, voices.size)) {
            val t = drums.hitTime[s]
            if (t == st.seen[s]) continue
            st.seen[s] = t
            if (now - t > 0.25f) continue                     // stale (e.g. first frame)
            val v = voices[s]
            val p = pieceFor(v.model, s, v.decayMs, firstTom)
            st.pieceHit[p.ordinal] = t; st.pieceSlot[p.ordinal] = s; st.pieceVel[p.ordinal] = drums.hitVel[s]
            if (p == Piece.HIHAT) st.hatOpen = v.decayMs >= 150f
            if (drums.hitVel[s] >= 0.92f && (p == Piece.SNARE || p == Piece.CRASH || p == Piece.CLAP)) st.grinUntil = t + 0.45f
            st.lastActivity = t
            when (p) {
                Piece.KICK, Piece.ANTENNA -> Unit             // foot / antenna, no hands
                Piece.CLAP -> { st.left.at = t; st.right.at = t; st.left.fromX = 188f; st.left.fromY = 44f; st.right.fromX = 212f; st.right.fromY = 44f }
                else -> {
                    val (x, y) = strike(p)
                    val leftSide = x < 200f
                    // Prefer the near hand; if it struck in the last 70 ms, cross over with the other one.
                    val hand = if (leftSide) (if (t - st.left.at < 0.07f) st.right else st.left)
                               else (if (t - st.right.at < 0.07f) st.left else st.right)
                    hand.at = t; hand.fromX = x; hand.fromY = y
                }
            }
        }

        // ---- stage transform: 400 × 300, centred ----
        val sc = min(size.width / 400f, size.height / 300f)
        val ox = (size.width - 400f * sc) / 2f; val oy = (size.height - 300f * sc) / 2f
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            val playing = state.groove.transport[1] != 0.0
            val beatPhase = ((state.groove.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat()
            val bob = if (playing) abs(sin(PI.toFloat() * beatPhase)) * 4f else sin(now * 1.3f) * 1.2f
            val kickK = env(now - st.pieceHit[Piece.KICK.ordinal], 18f)
            val sleepy = !playing && now - st.lastActivity > 8f

            // floor
            drawLine(Tb.Line, Offset(10f, 282f), Offset(390f, 282f), 2f)

            // ---- cymbals and stands (behind) ----
            crash(st, voices, now)
            hihat(st, voices, now)

            // ---- body ----
            val bodyY = bob + kickK * 3f
            // stool
            drawRoundRect(Tb.Raised, Offset(176f, 236f), Size(48f, 10f), CornerRadius(5f))
            drawLine(Tb.Raised, Offset(200f, 246f), Offset(200f, 282f), 6f)
            // torso (capsule)
            drawRoundRect(Tb.Text, Offset(172f, 128f + bodyY), Size(56f, 92f), CornerRadius(26f))
            drawRoundRect(Tb.Black, Offset(186f, 150f + bodyY), Size(28f, 8f), CornerRadius(4f))   // chest slot, very TE
            // head
            head(st, now, bodyY + bob * 0.6f, sleepy)

            // ---- drums (in front of the body) ----
            rackTom(st, voices, now)
            snare(st, voices, now)
            floorTom(st, voices, now)
            cowbell(st, voices, now)
            kick(st, voices, now, kickK)

            // ---- arms + sticks (in front of everything) ----
            // Between hits, the sticks ride the beat (left on the beat, right on the off-beat): he's always grooving.
            val beatL = if (playing) -7f * (1f - abs(sin(PI.toFloat() * beatPhase))) else 0f
            val beatR = if (playing) -7f * (1f - abs(sin(PI.toFloat() * ((beatPhase + 0.5f) % 1f)))) else 0f
            arm(st.left, 182f, 146f + bodyY, now, mirror = false, groove = beatL)
            arm(st.right, 218f, 146f + bodyY, now, mirror = true, groove = beatR)
            shaker(st, voices, now)
        }
    }
}

/** 1 at the hit, falling off exponentially (rate per second). 0 before the hit. */
private fun env(dt: Float, rate: Float) = if (dt < 0f || dt > 3f) 0f else exp(-dt * rate)

private fun slotColor(st: DrummerState, p: Piece) = Tb.Pads[st.pieceSlot[p.ordinal] % Tb.Pads.size]

private fun DrawScope.ring(cx: Float, cy: Float, rx: Float, ry: Float, dt: Float, c: Color) {
    if (dt < 0f || dt > 0.35f) return
    val k = dt / 0.35f
    val grow = 1f + k * 0.6f
    drawOval(c.copy(alpha = (1f - k) * 0.9f), Offset(cx - rx * grow, cy - ry * grow), Size(2 * rx * grow, 2 * ry * grow), style = Stroke(3f))
}

// ----------------------------------------------------------------------------- pieces

private fun DrawScope.drumShell(cx: Float, top: Float, w: Float, h: Float, depth: Float, squash: Float, accent: Color, flash: Float) {
    val sh = h * (1f - 0.35f * squash)
    // shell
    drawRect(Tb.Raised, Offset(cx - w / 2, top), Size(w, depth))
    drawOval(Tb.Raised, Offset(cx - w / 2, top + depth - sh / 2), Size(w, sh))
    // skin
    drawOval(lerp(Tb.Panel, accent, 0.25f + 0.75f * flash), Offset(cx - w / 2, top - sh / 2 + squash * 2f), Size(w, sh))
    drawOval(Tb.Text, Offset(cx - w / 2, top - sh / 2 + squash * 2f), Size(w, sh), style = Stroke(2.5f))
}

private fun DrawScope.snare(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    val dt = now - st.pieceHit[Piece.SNARE.ordinal]
    val c = slotColor(st, Piece.SNARE)
    drawLine(Tb.Line, Offset(128f, 196f), Offset(118f, 282f), 3f); drawLine(Tb.Line, Offset(128f, 196f), Offset(140f, 282f), 3f)
    drumShell(128f, 178f, 70f, 20f, 16f, env(dt, 30f), c, env(dt, 9f))
    ring(128f, 178f, 35f, 10f, dt, c)
}

private fun DrawScope.rackTom(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    if (v.none { it.model == DrumModel.TOM }) return
    val dt = now - st.pieceHit[Piece.RACK_TOM.ordinal]
    val c = slotColor(st, Piece.RACK_TOM)
    drumShell(172f, 152f, 46f, 16f, 18f, env(dt, 30f), c, env(dt, 9f))
    ring(172f, 152f, 23f, 8f, dt, c)
}

private fun DrawScope.floorTom(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    // Always drawn (even with one or no tom in the kit), so the kit silhouette stays complete.
    val dt = now - st.pieceHit[Piece.FLOOR_TOM.ordinal]
    val c = slotColor(st, Piece.FLOOR_TOM)
    drawLine(Tb.Line, Offset(268f, 210f), Offset(264f, 282f), 3f); drawLine(Tb.Line, Offset(316f, 210f), Offset(320f, 282f), 3f)
    drumShell(292f, 186f, 64f, 20f, 34f, env(dt, 26f), c, env(dt, 8f))
    ring(292f, 186f, 32f, 10f, dt, c)
}

private fun DrawScope.kick(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float, k: Float) {
    val dt = now - st.pieceHit[Piece.KICK.ordinal]
    val c = slotColor(st, Piece.KICK)
    val r = 52f * (1f + 0.07f * k)
    // front head faces us: a big circle with a slot-coloured hoop and a tiny badge
    drawCircle(Tb.Panel, r, Offset(200f, 226f))
    drawCircle(lerp(Tb.Raised, c, 0.3f + 0.7f * env(dt, 7f)), r, Offset(200f, 226f), style = Stroke(9f))
    drawCircle(Tb.Text, r - 9f, Offset(200f, 226f), style = Stroke(2f))
    drawRoundRect(Tb.Text, Offset(186f, 214f), Size(28f, 12f), CornerRadius(6f))
    drawCircle(c, 3.5f, Offset(193f, 220f))
    // pedal beater (right foot) swings in on the hit
    val swing = env(dt, 14f)
    drawLine(Tb.Text, Offset(232f, 280f), Offset(222f - 10f * swing, 252f - 6f * swing), 5f, cap = StrokeCap.Round)
    drawCircle(Tb.Text, 5f, Offset(222f - 10f * swing, 252f - 6f * swing))
    ring(200f, 226f, r, r, dt, c)
}

private fun DrawScope.cymbal(cx: Float, cy: Float, w: Float, tilt: Float, wobble: Float, c: Color, flash: Float) {
    rotate(tilt + wobble, Offset(cx, cy)) {
        drawOval(lerp(Tb.Raised, c, 0.2f + 0.8f * flash), Offset(cx - w / 2, cy - 6f), Size(w, 12f))
        drawOval(Tb.Text, Offset(cx - w / 2, cy - 6f), Size(w, 12f), style = Stroke(2.5f))
        drawCircle(Tb.Text, 3f, Offset(cx, cy - 1f))
    }
}

private fun DrawScope.crash(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    val dt = now - st.pieceHit[Piece.CRASH.ordinal]
    val wob = if (dt in 0f..2.5f) sin(dt * 26f) * exp(-dt * 3.5f) * 12f else 0f
    drawLine(Tb.Line, Offset(322f, 106f), Offset(340f, 282f), 3f)
    cymbal(322f, 104f, 92f, -10f, wob, slotColor(st, Piece.CRASH), env(dt, 5f))
}

private fun DrawScope.hihat(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    val dt = now - st.pieceHit[Piece.HIHAT.ordinal]
    val c = slotColor(st, Piece.HIHAT)
    val open = st.hatOpen && dt in 0f..0.6f
    val gap = if (open) 9f + 3f * sin(dt * 40f) * exp(-dt * 6f) else 2.5f
    val wob = if (open) sin(dt * 30f) * exp(-dt * 5f) * 6f else 0f
    drawLine(Tb.Line, Offset(66f, 146f), Offset(66f, 282f), 3f)
    cymbal(66f, 146f, 70f, 0f, 0f, c, env(dt, 10f))
    cymbal(66f, 146f - gap, 70f, 0f, wob, c, env(dt, 10f))
}

private fun DrawScope.cowbell(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    if (v.none { it.model == DrumModel.COWBELL }) return
    val dt = now - st.pieceHit[Piece.COWBELL.ordinal]
    val c = slotColor(st, Piece.COWBELL)
    val shake = if (dt in 0f..0.4f) sin(dt * 60f) * exp(-dt * 10f) * 4f else 0f
    rotate(shake, Offset(238f, 160f)) {
        val p = st.path.apply { reset(); moveTo(228f, 146f); lineTo(248f, 146f); lineTo(252f, 172f); lineTo(224f, 172f); close() }
        drawPath(p, lerp(Tb.Raised, c, 0.3f + 0.7f * env(dt, 8f)))
        drawPath(p, Tb.Text, style = Stroke(2.5f))
    }
}

private fun DrawScope.shaker(st: DrummerState, v: List<com.thorbox.model.DrumVoice>, now: Float) {
    val dt = now - st.pieceHit[Piece.SHAKER.ordinal]
    if (dt !in 0f..0.5f) return
    val c = slotColor(st, Piece.SHAKER)
    val j = sin(dt * 70f) * exp(-dt * 7f) * 7f
    drawOval(c, Offset(262f + j, 108f), Size(18f, 24f))
    drawOval(Tb.Text, Offset(262f + j, 108f), Size(18f, 24f), style = Stroke(2f))
}

// ----------------------------------------------------------------------------- character

private fun DrawScope.head(st: DrummerState, now: Float, y: Float, sleepy: Boolean) {
    val cx = 200f; val cy = 96f + y
    // The cap hops a little on the kick and on big accents, then settles (it's fitted, it doesn't fly off).
    val hop = 4f * env(now - st.pieceHit[Piece.KICK.ordinal], 20f) + (if (now < st.grinUntil) 2f else 0f)
    val capTop = cy - 52f - hop
    // antenna pokes through the cap's top button (ZAP lights it up with a little bolt)
    val zap = env(now - st.pieceHit[Piece.ANTENNA.ordinal], 7f)
    val ac = if (zap > 0.02f) Tb.Pads[st.pieceSlot[Piece.ANTENNA.ordinal] % Tb.Pads.size] else Tb.Text
    drawLine(Tb.Text, Offset(cx, capTop), Offset(cx, capTop - 12f), 3f)
    drawCircle(ac, 5f + 5f * zap, Offset(cx, capTop - 15f))
    if (zap > 0.05f) {
        val a = zap
        drawLine(ac.copy(alpha = a), Offset(cx + 8f, capTop - 22f), Offset(cx + 18f, capTop - 30f), 3f, cap = StrokeCap.Round)
        drawLine(ac.copy(alpha = a), Offset(cx - 8f, capTop - 22f), Offset(cx - 18f, capTop - 30f), 3f, cap = StrokeCap.Round)
        drawLine(ac.copy(alpha = a), Offset(cx, capTop - 26f), Offset(cx, capTop - 38f), 3f, cap = StrokeCap.Round)
    }
    // head block + screen face
    drawRoundRect(Tb.Text, Offset(cx - 36f, cy - 34f), Size(72f, 64f), CornerRadius(18f))
    drawRoundRect(Tb.Black, Offset(cx - 27f, cy - 24f), Size(54f, 40f), CornerRadius(10f))
    // eyes
    if (now > st.nextBlink + 0.12f) st.nextBlink = now + 2.5f + (now * 7.13f % 3f)
    val blinking = now in st.nextBlink..(st.nextBlink + 0.12f)
    val ec = Tb.Text
    when {
        now < st.grinUntil -> {   // ^ ^
            for (ex in floatArrayOf(cx - 12f, cx + 12f)) {
                drawLine(ec, Offset(ex - 6f, cy - 2f), Offset(ex, cy - 9f), 3.5f, cap = StrokeCap.Round)
                drawLine(ec, Offset(ex, cy - 9f), Offset(ex + 6f, cy - 2f), 3.5f, cap = StrokeCap.Round)
            }
        }
        sleepy -> {
            for (ex in floatArrayOf(cx - 12f, cx + 12f)) drawLine(ec, Offset(ex - 6f, cy - 4f), Offset(ex + 6f, cy - 4f), 3.5f, cap = StrokeCap.Round)
            val zt = (now % 2.4f) / 2.4f
            val zx = cx + 40f + zt * 14f; val zy = cy - 30f - zt * 26f; val zs = 6f + zt * 4f
            val za = (1f - zt)
            drawLine(Tb.Muted.copy(alpha = za), Offset(zx, zy), Offset(zx + zs, zy), 2.5f)
            drawLine(Tb.Muted.copy(alpha = za), Offset(zx + zs, zy), Offset(zx, zy + zs), 2.5f)
            drawLine(Tb.Muted.copy(alpha = za), Offset(zx, zy + zs), Offset(zx + zs, zy + zs), 2.5f)
        }
        blinking -> for (ex in floatArrayOf(cx - 12f, cx + 12f)) drawLine(ec, Offset(ex - 5f, cy - 5f), Offset(ex + 5f, cy - 5f), 3f, cap = StrokeCap.Round)
        else -> {
            // pupils glance toward the last thing hit
            var look = 0f
            var newest = -10f
            for (p in Piece.entries) if (st.pieceHit[p.ordinal] > newest) { newest = st.pieceHit[p.ordinal]; look = (strike(p).first - 200f) / 200f }
            val glance = env(now - newest, 3f) * look * 3f
            for (ex in floatArrayOf(cx - 12f, cx + 12f)) drawRoundRect(ec, Offset(ex - 4f + glance, cy - 11f), Size(8f, 12f), CornerRadius(4f))
        }
    }
    fittedCap(cx, cy, capTop, hop)
    // mouth: a tiny line that opens with the kick
    val m = env(now - st.pieceHit[Piece.KICK.ordinal], 16f)
    drawRoundRect(Tb.Text, Offset(cx - 6f, cy + 6f), Size(12f, 2.5f + 4f * m), CornerRadius(2f))
}

/** Shoulder → elbow → hand, then the stick. The hand springs from the strike point back to rest. */
private fun DrawScope.arm(h: Hand, sx: Float, sy: Float, now: Float, mirror: Boolean, groove: Float = 0f) {
    val dt = now - h.at
    // 0 at the strike, overshooting slightly past rest, settling by ~0.22 s
    val k = if (dt < 0f) 1f else min(1f, dt / 0.22f).let { x -> 1f + 1.7f * (x - 1f) * (x - 1f) * (x - 1f) + 0.7f * (x - 1f) * (x - 1f) }
    val hx = h.fromX + (h.restX - h.fromX) * k
    val hy = h.fromY + (h.restY + groove - h.fromY) * k
    // elbow: bend outward, perpendicular to shoulder→hand
    val dx = hx - sx; val dy = hy - sy
    val len = max(1f, sqrt(dx * dx + dy * dy))
    val bend = max(0f, 46f - len * 0.5f)
    val side = if (mirror) 1f else -1f
    val ex = sx + dx * 0.5f + (-dy / len) * bend * side
    val ey = sy + dy * 0.5f + (dx / len) * bend * side
    drawLine(Tb.Text, Offset(sx, sy), Offset(ex, ey), 11f, cap = StrokeCap.Round)
    drawLine(Tb.Text, Offset(ex, ey), Offset(hx, hy), 11f, cap = StrokeCap.Round)
    // stick: points down toward the strike when hitting, tilts up at rest
    val raise = k.coerceIn(0f, 1.2f)
    val tipX = hx + (if (mirror) 1f else -1f) * (10f + 8f * raise)
    val tipY = hy + 24f - 40f * raise
    drawLine(Tb.Key, Offset(hx, hy), Offset(tipX, tipY), 4.5f, cap = StrokeCap.Round)
    drawCircle(Tb.Text, 7f, Offset(hx, hy))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}

private val CAP = Color(0xFF14213D)            // classic navy fitted
private val CAP_SHADE = Color(0xFF0C152A)
private val STICKER = Color(0xFFE0B03A)

/**
 * BIT's fitted cap: navy crown with panel seams, a top button, an original interlocking "TB"
 * monogram (ours, not a team's) and the gold size sticker left on the brim, the way it should be.
 */
private fun DrawScope.fittedCap(cx: Float, cy: Float, capTop: Float, hop: Float) {
    val base = cy - 22f - hop * 0.5f
    // crown: a dome sitting over the top of the head block
    drawArc(CAP, 180f, 180f, true, Offset(cx - 39f, capTop), Size(78f, (base - capTop) * 2f))
    drawRect(CAP, Offset(cx - 39f, base - 6f), Size(78f, 8f))
    // panel seams + top button
    drawLine(CAP_SHADE, Offset(cx, capTop + 2f), Offset(cx, base), 1.5f)
    drawLine(CAP_SHADE, Offset(cx - 14f, capTop + 6f), Offset(cx - 22f, base), 1.5f)
    drawLine(CAP_SHADE, Offset(cx + 14f, capTop + 6f), Offset(cx + 22f, base), 1.5f)
    drawCircle(CAP_SHADE, 3.5f, Offset(cx, capTop + 1f))
    // monogram: a T laid over a B, in white stitching
    val mx = cx; val my = capTop + (base - capTop) * 0.55f
    val ink = Color(0xFFF4F4F5)
    drawLine(ink, Offset(mx - 7f, my - 8f), Offset(mx + 7f, my - 8f), 2.6f, cap = StrokeCap.Round)          // T bar
    drawLine(ink, Offset(mx, my - 8f), Offset(mx, my + 8f), 2.6f, cap = StrokeCap.Round)                    // T stem / B spine
    drawArc(ink, -90f, 180f, false, Offset(mx - 4f, my - 7f), Size(10f, 8f), style = Stroke(2.2f))          // B upper bowl
    drawArc(ink, -90f, 180f, false, Offset(mx - 4f, my), Size(12f, 8f), style = Stroke(2.2f))               // B lower bowl
    // brim: flat and forward, slightly darker underside
    drawOval(CAP_SHADE, Offset(cx - 44f, base - 4f), Size(88f, 14f))
    drawOval(CAP, Offset(cx - 44f, base - 6f), Size(88f, 12f))
    // the sticker, never peeled
    drawCircle(STICKER, 4.5f, Offset(cx + 24f, base))
    drawCircle(Color(0xFFC0C0C8), 1.8f, Offset(cx + 24f, base))
}
