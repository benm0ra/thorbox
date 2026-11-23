// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.SynthTelemetry
import com.thorbox.model.SynthCategory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * THE CAT: a ginger tabby in shades (gold hoop in one ear), side-on and crouched, making biscuits with both front
 * paws in turn, on whatever suits the sound family of the current patch:
 *
 *   PAD    a fat pillow          (slow, deep presses; the pillow puffs back up)
 *   808    a waterbed            (every press sends ripples; the sub makes the whole thing slosh)
 *   BASS   a speaker, cone up    (the woofer pumps with the synth's level)
 *   LEAD   a turntable           (each press scratches the record back, then it spins on)
 *   KEYS   a little keyboard     (the keys you play light up; the ones under her paws go down)
 *   PLUCK  an acoustic guitar    (strings shimmer while notes ring)
 *   WEIRD  a glowing slime blob  (wobbles, sinks under her paws, shifts colour with the LFO)
 *
 * She kneads one paw per beat while the transport runs (left on the beat, right on the off-beat),
 * lazily when it's stopped; presses go deeper the louder the synth is. Her tail swishes with the
 * LFO, her ears twitch on each new note, her brows pop over the shades on loud notes, and she purrs
 * (little lines at her sides) while anything sounds. All original artwork; draws every frame
 * without allocation (two reused Paths, plain floats). Stage: 240 × 200 virtual units.
 */

private class CatState {
    val path = Path()
    val path2 = Path()
    var lastNote = -1
    var twitchAt = -10f
}

// her colours (her own, the same on every theme)
private val FUR = Color(0xFFE08A3C)
private val FUR_DARK = Color(0xFFB0632A)
private val FUR_LIGHT = Color(0xFFF3B067)
private val CREAM = Color(0xFFF6E9D8)
private val BEAN = Color(0xFFF2A1A8)
private val SHADES = Color(0xFF111118)
private val HOOP = Color(0xFFE6B93C)
private val OUT = Color(0xFF7A4018)
private val FUR_SHADOW = Color(0xFF9C5A28)
private val CREAM_SHADOW = Color(0xFFD9CBB9)

/** What she kneads, per sound family. */
fun kneadObjectName(c: SynthCategory) = when (c) {
    SynthCategory.PAD -> "PILLOW"
    SynthCategory.SUB808 -> "WATERBED"
    SynthCategory.BASS -> "SPEAKER"
    SynthCategory.LEAD -> "TURNTABLE"
    SynthCategory.KEYS -> "KEYBOARD"
    SynthCategory.PLUCK -> "GUITAR"
    SynthCategory.WEIRD -> "SLIME"
}

@Composable
fun BiscuitCat(state: AppState, family: SynthCategory, accent: Color, modifier: Modifier = Modifier) {
    val cs = remember { CatState() }
    val tel = state.telemetry
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            tel.frame.longValue                                    // redraw every frame, never recompose
            val now = tel.timeSec
            val playing = state.groove.transport[1] != 0.0
            val beat = if (playing) ((state.groove.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat() else (now * 0.75f) % 1f
            val amp = tel.ampLevel.coerceIn(0f, 1f)
            if (tel.newestNote != cs.lastNote) { if (tel.activeVoices > 0) cs.twitchAt = now; cs.lastNote = tel.newestNote }

            val sc = min(size.width / 240f, size.height / 200f)
            val ox = (size.width - 240f * sc) / 2f; val oy = (size.height - 200f * sc) / 2f
            withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
                // a soft spotlight in the family colour, and the floor she's on
                drawCircle(accent.copy(alpha = 0.10f + 0.10f * amp), 110f, Offset(120f, 110f))
                drawCircle(accent.copy(alpha = 0.08f), 70f, Offset(120f, 100f))
                drawOval(Color.Black.copy(alpha = 0.35f), Offset(20f, 182f), Size(200f, 16f))

                // press amount per paw: 1 = fully down, 0 = lifted; left on the beat, right on the off-beat
                val dl = press(beat); val dr = press((beat + 0.5f) % 1f)
                val depth = 0.6f + 0.6f * amp
                val pl = 82f; val pr = 108f                                // near paw, far paw (where they land)
                when (family) {
                    SynthCategory.PAD -> pillow(cs, dl * depth, dr * depth, pl, pr, now, amp, accent)
                    SynthCategory.SUB808 -> waterbed(beat, dl * depth, dr * depth, pl, pr, now, amp, accent, cs)
                    SynthCategory.BASS -> speaker(dl * depth, dr * depth, now, amp, accent)
                    SynthCategory.LEAD -> turntable(dl, dr, beat, now, amp, accent)
                    SynthCategory.KEYS -> keyboard(tel, dl, dr, pl, pr, accent)
                    SynthCategory.PLUCK -> guitar(cs, now, amp, accent, tel.activeVoices)
                    SynthCategory.WEIRD -> slime(cs, dl * depth, dr * depth, pl, pr, now, amp, tel.lfoValue, accent)
                }
                val sink = when (family) { SynthCategory.PAD -> 7f; SynthCategory.WEIRD -> 9f; SynthCategory.SUB808 -> 4f; else -> 2f }
                cat(cs, now, beat, playing, amp, tel.lfoValue, dl, dr, sink * depth, pl, pr)
            }
        }
        Text("${family.label} · ${kneadObjectName(family)}", color = accent.copy(alpha = 0.85f), fontSize = 10.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 2.dp))
    }
}

/** 1 at the press (phase 0), 0 lifted (phase 0.5); a quick press and a slower lift, like a real knead. */
private fun press(ph: Float): Float {
    val c = 0.5f + 0.5f * cos(2f * PI.toFloat() * ph)
    return c.pow(0.7f)
}

// ----------------------------------------------------------------------------- the cat

private fun DrawScope.cat(cs: CatState, now: Float, beat: Float, playing: Boolean, amp: Float, lfo: Float,
                          dn: Float, df: Float, sink: Float, nearX: Float, farX: Float) {
    // Side-on, three-quarter head: she's crouched on it facing left, chest up, rear sitting, and
    // kneads with the near paw (on the beat) and the far paw (off-beat) in turn.
    val bob = if (playing) 1.5f * abs(sin(PI.toFloat() * beat)) else 0.8f * sin(now * 1.4f)
    val rock = (dn - df) * 1.2f
    val p = cs.path

    // ---- tail: up and over, swishing with the LFO ----
    val wag = lfo * 10f + sin(now * 1.3f) * 4f
    p.reset(); p.moveTo(190f, 120f)
    p.cubicTo(222f, 116f, 230f, 80f + wag * 0.5f, 210f + wag * 0.3f, 60f + wag)
    drawPath(p, OUT, style = Stroke(11f, cap = StrokeCap.Round))
    drawPath(p, FUR, style = Stroke(8f, cap = StrokeCap.Round))
    drawCircle(FUR_DARK, 4.2f, Offset(210f + wag * 0.3f, 60f + wag))

    // ---- far front leg, in shadow behind her chest ----
    val liftF = 10f * (1f - df).pow(1.5f)
    val pfx = farX + 2f - 4f * (1f - df); val pfy = 140f + sink * df - liftF
    p.reset(); p.moveTo(112f, 110f + rock); p.quadraticTo((112f + pfx) / 2f - 4f, (110f + pfy) / 2f, pfx, pfy - 4f)
    drawPath(p, FUR_SHADOW, style = Stroke(12f, cap = StrokeCap.Round))
    drawOval(CREAM_SHADOW, Offset(pfx - 2f - 8f - 2f * df, pfy - 5f), Size(16f + 4f * df, 10f))

    // ---- body: back, haunch, belly ----
    p.reset()
    p.moveTo(90f, 98f)
    p.cubicTo(110f, 80f, 166f, 78f, 192f, 94f)
    p.cubicTo(210f, 106f, 208f, 136f, 188f, 143f)
    p.lineTo(152f, 143f)
    p.cubicTo(142f, 134f, 130f, 124f, 116f, 121f)
    p.cubicTo(102f, 119f, 90f, 114f, 87f, 106f)
    p.cubicTo(86f, 102f, 87f, 100f, 90f, 98f)
    p.close()
    drawPath(p, FUR)
    drawPath(p, OUT, style = Stroke(1.6f, join = StrokeJoin.Round))
    p.reset(); p.moveTo(150f, 142f); p.cubicTo(146f, 120f, 160f, 100f, 184f, 100f)            // haunch
    drawPath(p, FUR_DARK, style = Stroke(2f, cap = StrokeCap.Round))
    drawOval(CREAM, Offset(150f, 136.5f), Size(20f, 9f))                                        // hind paw
    drawOval(OUT, Offset(150f, 136.5f), Size(20f, 9f), style = Stroke(1f))
    for (k in 0 until 4) {                                                                      // tabby stripes
        val x = 130f + k * 14.5f; val y = if (k == 0) 84f else 82f
        p.reset(); p.moveTo(x, y); p.quadraticTo(x + 3f, y + 8f, x, y + 14f)
        drawPath(p, FUR_DARK, style = Stroke(3f, cap = StrokeCap.Round))
    }
    // purr: little lines off her chest while anything sounds
    if (amp > 0.02f || playing) {
        val j = sin(now * 60f) * 1f
        for (k in 0 until 2) drawLine(FUR_LIGHT.copy(alpha = 0.55f - k * 0.2f), Offset(78f - k * 6f + j, 112f + k * 6f), Offset(74f - k * 6f + j, 108f + k * 6f), 1.6f, cap = StrokeCap.Round)
    }

    // ---- near front leg + paw ----
    val liftN = 10f * (1f - dn).pow(1.5f)
    val pnx = nearX + 2f - 4f * (1f - dn); val pny = 140f + sink * dn - liftN
    p.reset(); p.moveTo(98f, 108f); p.quadraticTo((98f + pnx) / 2f - 5f, (108f + pny) / 2f, pnx, pny - 4f)
    drawPath(p, OUT, style = Stroke(15f, cap = StrokeCap.Round))
    drawPath(p, FUR, style = Stroke(12f, cap = StrokeCap.Round))
    drawOval(FUR, Offset(89f, 98f), Size(18f, 16f))                                             // shoulder, blended into the chest
    val sp = 1f + 2.5f * dn                                                                     // toes spread as it presses
    drawOval(CREAM, Offset(pnx - 2f - 9f - sp, pny - 5.5f), Size(18f + 2f * sp, 11f))
    drawOval(OUT, Offset(pnx - 2f - 9f - sp, pny - 5.5f), Size(18f + 2f * sp, 11f), style = Stroke(1f))
    for (t in -1..1) drawLine(OUT.copy(alpha = 0.6f), Offset(pnx - 2f + t * (3f + sp * 0.5f), pny + 1f), Offset(pnx - 2f + t * (3.5f + sp * 0.7f), pny + 5f), 1f, cap = StrokeCap.Round)
    if (dn < 0.3f) drawCircle(BEAN, 2.4f, Offset(pnx - 2f, pny + 1f))                         // a bean shows on the lift

    // ---- head, three-quarter, on her shoulders ----
    val hx = 84f; val hy = 76f + rock * 0.5f + bob
    val tw = now - cs.twitchAt
    val twitch = if (tw in 0f..0.3f) sin(tw * 40f) * 9f * (1f - tw / 0.3f) else 0f
    rotate(twitch * 0.6f, Offset(hx + 18f, hy - 20f)) {                                        // far ear (gold hoop)
        p.reset(); p.moveTo(hx + 10f, hy - 22f); p.lineTo(hx + 26f, hy - 44f); p.lineTo(hx + 28f, hy - 14f); p.close()
        drawPath(p, FUR); drawPath(p, OUT, style = Stroke(1.6f, join = StrokeJoin.Round))
        p.reset(); p.moveTo(hx + 14f, hy - 22f); p.lineTo(hx + 24f, hy - 37f); p.lineTo(hx + 24f, hy - 18f); p.close()
        drawPath(p, BEAN)
        drawCircle(HOOP, 3f, Offset(hx + 27f, hy - 16f), style = Stroke(1.4f))
    }
    rotate(-twitch, Offset(hx - 14f, hy - 18f)) {                                               // near ear
        p.reset(); p.moveTo(hx - 26f, hy - 12f); p.lineTo(hx - 24f, hy - 44f); p.lineTo(hx - 4f, hy - 24f); p.close()
        drawPath(p, FUR); drawPath(p, OUT, style = Stroke(1.6f, join = StrokeJoin.Round))
        p.reset(); p.moveTo(hx - 22f, hy - 17f); p.lineTo(hx - 21f, hy - 37f); p.lineTo(hx - 9f, hy - 24f); p.close()
        drawPath(p, BEAN)
    }
    p.reset()
    p.moveTo(hx - 30f, hy)
    p.cubicTo(hx - 32f, hy - 26f, hx + 28f, hy - 30f, hx + 30f, hy - 2f)
    p.cubicTo(hx + 32f, hy + 16f, hx + 16f, hy + 24f, hx, hy + 24f)
    p.cubicTo(hx - 18f, hy + 24f, hx - 30f, hy + 14f, hx - 30f, hy)
    p.close()
    drawPath(p, FUR)
    drawPath(p, OUT, style = Stroke(1.6f))
    for (sd in floatArrayOf(-1f, 1f)) {                                                         // cheek tufts
        p.reset(); p.moveTo(hx + sd * 29f, hy + 5f); p.lineTo(hx + sd * 35f, hy + 10f); p.lineTo(hx + sd * 28f, hy + 11f); p.close()
        drawPath(p, FUR)
    }
    p.reset(); p.moveTo(hx - 10f, hy - 20f); p.lineTo(hx - 6f, hy - 14f); p.lineTo(hx - 2f, hy - 21f); p.lineTo(hx + 2f, hy - 14f); p.lineTo(hx + 6f, hy - 20f)
    drawPath(p, FUR_DARK, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))   // the tabby M
    // muzzle, nose, a pleased little mouth
    val mx = hx - 4f
    drawOval(CREAM, Offset(mx - 15f, hy + 4f), Size(18f, 14f))
    drawOval(CREAM, Offset(mx - 3f, hy + 4f), Size(18f, 14f))
    p.reset(); p.moveTo(mx - 4f, hy + 4f); p.lineTo(mx + 4f, hy + 4f); p.lineTo(mx, hy + 8f); p.close()
    drawPath(p, BEAN)
    p.reset(); p.moveTo(mx, hy + 8f); p.lineTo(mx, hy + 11f)
    p.moveTo(mx, hy + 11f); p.quadraticTo(mx - 3f, hy + 14f, mx - 6f, hy + 11f)
    p.moveTo(mx, hy + 11f); p.quadraticTo(mx + 4f, hy + 15f, mx + 8f, hy + 10f)
    drawPath(p, OUT, style = Stroke(1.2f, cap = StrokeCap.Round))
    for (k in -1..1) {                                                                          // whiskers: short and soft
        drawLine(CREAM.copy(alpha = 0.8f), Offset(mx - 12f, hy + 10f + k * 2f), Offset(mx - 30f, hy + 7f + k * 5f), 0.8f)
        drawLine(CREAM.copy(alpha = 0.8f), Offset(mx + 12f, hy + 10f + k * 2f), Offset(mx + 28f, hy + 7f + k * 5f), 0.8f)
    }
    // brows pop over the shades on loud notes
    val brow = 2.5f * amp + (if (tw in 0f..0.25f) 1.5f else 0f)
    if (brow > 0.3f) {
        drawLine(FUR_DARK, Offset(hx - 22f, hy - 12f - brow), Offset(hx - 9f, hy - 13f - brow), 2f, cap = StrokeCap.Round)
        drawLine(FUR_DARK, Offset(hx + 7f, hy - 13f - brow), Offset(hx + 19f, hy - 12f - brow), 2f, cap = StrokeCap.Round)
    }
    // shades: the near lens bigger (three-quarter), a bridge, an arm back to the far ear, a sweeping glint
    p.reset(); p.moveTo(hx - 26f, hy - 9f); p.lineTo(hx - 3f, hy - 9f); p.lineTo(hx - 5f, hy + 1f); p.quadraticTo(hx - 14f, hy + 5f, hx - 24f, hy + 1f); p.close()
    drawPath(p, SHADES)
    p.reset(); p.moveTo(hx + 3f, hy - 9f); p.lineTo(hx + 24f, hy - 9f); p.lineTo(hx + 23f, hy); p.quadraticTo(hx + 14f, hy + 4f, hx + 5f, hy + 1f); p.close()
    drawPath(p, SHADES)
    drawLine(SHADES, Offset(hx - 3f, hy - 8f), Offset(hx + 3f, hy - 8f), 2f)
    drawLine(SHADES, Offset(hx + 24f, hy - 8f), Offset(hx + 30f, hy - 10f), 2f)
    drawLine(Color.White.copy(alpha = 0.25f), Offset(hx - 21f, hy - 7f), Offset(hx - 16f, hy - 7f), 2f, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.25f), Offset(hx + 7f, hy - 7f), Offset(hx + 12f, hy - 7f), 2f, cap = StrokeCap.Round)
    val g = (now * 0.35f) % 1f
    if (g < 0.2f) {
        val gx = hx - 24f + g / 0.2f * 46f
        if (gx < hx - 5f || gx > hx + 5f) drawLine(Color.White.copy(alpha = 0.6f), Offset(gx, hy - 8f), Offset(gx + 4f, hy), 1.5f, cap = StrokeCap.Round)
    }
}

// ----------------------------------------------------------------------------- what she kneads

private fun DrawScope.pillow(cs: CatState, dl: Float, dr: Float, pl: Float, pr: Float, now: Float, amp: Float, accent: Color) {
    val base = lerp2(Color(0xFFC3B1F0), accent, 0.10f)
    val dark = lerp2(base, Color.Black, 0.3f)
    val puff = 1.5f * sin(now * 1.2f) + 2f * amp
    // the pillow: puffy body, corner tufts, a seam
    drawRoundRect(dark, Offset(26f, 134f - puff), Size(188f, 54f + puff), CornerRadius(26f))
    drawRoundRect(base, Offset(28f, 134f - puff), Size(184f, 50f + puff), CornerRadius(24f))
    for ((cx, cy) in CORNERS) {
        val p = cs.path2
        p.reset(); p.moveTo(cx, cy); p.lineTo(cx + (if (cx < 120f) -8f else 8f), cy + (if (cy < 160f) -6f else 6f)); p.lineTo(cx + (if (cx < 120f) 4f else -4f), cy + (if (cy < 160f) -2f else 2f)); p.close()
        drawPath(p, base)
    }
    drawLine(dark.copy(alpha = 0.5f), Offset(40f, 162f), Offset(200f, 162f), 1.4f)
    drawOval(Color.White.copy(alpha = 0.18f), Offset(44f, 140f - puff), Size(70f, 12f))
    // dents where her paws press
    for (k in 0..1) {
        val d = if (k == 0) dl else dr; val x = if (k == 0) pl else pr
        if (d > 0.05f) drawOval(dark.copy(alpha = 0.45f * d), Offset(x - 15f, 140f - puff * 0.5f), Size(30f, 4f + 6f * d))
    }
}
private val CORNERS = listOf(30f to 138f, 210f to 138f, 30f to 182f, 210f to 182f)

private fun DrawScope.waterbed(beat: Float, dl: Float, dr: Float, pl: Float, pr: Float, now: Float, amp: Float, accent: Color, cs: CatState) {
    val water = lerp2(Color(0xFF2E7BD6), accent, 0.2f)
    // wooden frame, then the mattress whose top edge sloshes with the sub
    drawRoundRect(Color(0xFF6B3F22), Offset(22f, 150f), Size(196f, 40f), CornerRadius(5f))
    drawRoundRect(Color(0xFF8A5530), Offset(22f, 150f), Size(196f, 6f), CornerRadius(3f))
    val p = cs.path2; p.reset()
    p.moveTo(28f, 168f)
    var x = 28f
    while (x <= 212f) {
        val y = 136f + sin(x * 0.06f + now * 5f) * (1.2f + 4f * amp) + sin(x * 0.11f - now * 3f) * 1.2f
        p.lineTo(x, y); x += 6f
    }
    p.lineTo(212f, 168f); p.close()
    drawPath(p, water)
    drawRect(lerp2(water, Color.Black, 0.25f), Offset(28f, 158f), Size(184f, 10f))
    drawOval(Color.White.copy(alpha = 0.25f), Offset(40f, 140f), Size(56f, 6f))
    // ripples ring out from each press
    for (k in 0..1) {
        val ph = if (k == 0) beat else (beat + 0.5f) % 1f
        val cx = if (k == 0) pl else pr
        for (r in 0..1) {
            val t = (ph + r * 0.5f) % 1f
            val rad = 6f + t * 46f
            drawOval(Color.White.copy(alpha = 0.35f * (1f - t) * (0.5f + 0.5f * amp)), Offset(cx - rad, 146f - rad * 0.16f), Size(rad * 2f, rad * 0.32f), style = Stroke(1.2f))
        }
        val d = if (k == 0) dl else dr
        if (d > 0.05f) drawOval(lerp2(water, Color.Black, 0.3f).copy(alpha = 0.5f * d), Offset(cx - 12f, 141f), Size(24f, 3f + 4f * d))
    }
}

private fun DrawScope.speaker(dl: Float, dr: Float, now: Float, amp: Float, accent: Color) {
    val cab = Color(0xFF1E1D24); val edge = Color(0xFF3A3944)
    // the cab (front face), on its back so the woofer faces up
    drawRoundRect(cab, Offset(36f, 146f), Size(168f, 44f), CornerRadius(4f))
    drawRoundRect(edge, Offset(36f, 146f), Size(168f, 44f), CornerRadius(4f), style = Stroke(2f))
    for (cx in floatArrayOf(40f, 200f)) for (cy in floatArrayOf(150f, 186f)) drawCircle(Color(0xFF7D838E), 2.6f, Offset(cx, cy))   // corners
    drawCircle(Color.Black, 7f, Offset(70f, 168f)); drawCircle(edge, 7f, Offset(70f, 168f), style = Stroke(1.5f))                    // port
    drawRoundRect(accent.copy(alpha = 0.8f), Offset(150f, 164f), Size(28f, 8f), CornerRadius(2f))                                  // badge
    // top face + the woofer: surround, cone, dust cap. The cone moves with the level and her presses.
    drawRect(Color(0xFF2A2932), Offset(36f, 128f), Size(168f, 18f))
    val push = 2.5f * amp * sin(now * 50f) + 1.8f * (dl + dr) * 0.5f
    drawOval(Color(0xFF0E0E12), Offset(54f, 126f), Size(132f, 22f))
    drawOval(Color(0xFF2F2F38), Offset(60f, 128f + push * 0.3f), Size(120f, 18f))
    drawOval(Color(0xFF1A1A20), Offset(72f, 130f + push * 0.6f), Size(96f, 14f))
    drawOval(lerp2(Color(0xFF3A3A44), accent, 0.25f + 0.5f * amp), Offset(108f, 133f + push), Size(24f, 7f))
    if (amp > 0.05f) drawOval(accent.copy(alpha = 0.25f * amp), Offset(60f, 124f), Size(120f, 26f), style = Stroke(2f))
}

private fun DrawScope.turntable(dl: Float, dr: Float, beat: Float, now: Float, amp: Float, accent: Color) {
    // plinth
    drawRoundRect(Color(0xFF2B2A31), Offset(24f, 146f), Size(192f, 42f), CornerRadius(5f))
    drawRoundRect(Color(0xFF474652), Offset(24f, 146f), Size(192f, 42f), CornerRadius(5f), style = Stroke(1.6f))
    drawRect(Color(0xFF3A3942), Offset(24f, 126f), Size(192f, 20f))
    drawRoundRect(Color(0xFFC9CED6), Offset(196f, 156f), Size(10f, 24f), CornerRadius(2f))                   // pitch fader
    drawRect(Color(0xFF111116), Offset(198f, 165f), Size(6f, 4f))
    drawCircle(accent.copy(alpha = 0.9f), 4f, Offset(42f, 170f))                                                  // start/stop
    // platter + record; the label spins at 33 and scratches back on each press
    drawOval(Color(0xFF8E939C), Offset(38f, 122f), Size(140f, 28f))
    drawOval(Color(0xFF0D0D10), Offset(42f, 123f), Size(132f, 25f))
    for (k in 1..3) drawOval(Color.White.copy(alpha = 0.06f), Offset(42f + k * 9f, 123f + k * 1.7f), Size(132f - k * 18f, 25f - k * 3.4f), style = Stroke(0.8f))
    val scratch = 0.9f * sin(2f * PI.toFloat() * beat) * (0.5f + 0.5f * amp)
    val ang = now * 3.46f - scratch
    drawOval(accent, Offset(96f, 131f), Size(24f, 9f))
    drawLine(Color.Black.copy(alpha = 0.6f), Offset(108f, 135.5f), Offset(108f + cos(ang) * 11f, 135.5f + sin(ang) * 4f), 1.6f)
    drawCircle(Color(0xFFC9CED6), 1.4f, Offset(108f, 135.5f))
    // tonearm from its pivot to the record
    drawCircle(Color(0xFFC9CED6), 6f, Offset(196f, 132f))
    drawLine(Color(0xFFC9CED6), Offset(196f, 132f), Offset(170f, 138f), 2.4f, cap = StrokeCap.Round)
    drawLine(Color(0xFFC9CED6), Offset(170f, 138f), Offset(158f, 136f), 2.4f, cap = StrokeCap.Round)
    drawRoundRect(Color(0xFF1A1A20), Offset(152f, 133f), Size(8f, 5f), CornerRadius(1f))
}

private fun DrawScope.keyboard(tel: SynthTelemetry, dl: Float, dr: Float, pl: Float, pr: Float, accent: Color) {
    drawRoundRect(Color(0xFF26252C), Offset(22f, 146f), Size(196f, 40f), CornerRadius(5f))
    drawRoundRect(Color(0xFF26252C), Offset(22f, 124f), Size(196f, 24f), CornerRadius(3f))
    drawRect(Color(0xFF111116), Offset(28f, 150f), Size(184f, 5f))
    for (k in 0 until 4) drawCircle(Color(0xFF7D838E), 3.4f, Offset(40f + k * 12f, 168f))       // knobs
    drawRoundRect(accent.copy(alpha = 0.7f), Offset(160f, 164f), Size(40f, 8f), CornerRadius(2f))   // screen
    // two octaves of keys, top face in perspective: lit when that note is held (any octave)
    val n = 14; val l = 28f; val w = 184f / n
    val whiteSemi = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    for (i in 0 until n) {
        val semi = whiteSemi[i % 7] + 12 * (i / 7)
        val x = l + i * w
        val under = (abs(x + w / 2 - pl) < w * 0.8f && dl > 0.6f) || (abs(x + w / 2 - pr) < w * 0.8f && dr > 0.6f)
        val lit = heldClass(tel, semi % 12)
        drawRect(if (lit) lerp2(Color(0xFFF1F1EE), accent, 0.65f) else Color(0xFFF1F1EE), Offset(x + 0.6f, 128f + (if (under) 1.5f else 0f)), Size(w - 1.2f, 20f))
    }
    for (i in 0 until n) {
        if (i % 7 == 2 || i % 7 == 6) continue
        val semi = whiteSemi[i % 7] + 1
        val x = l + (i + 1) * w - w * 0.3f
        drawRect(if (heldClass(tel, semi % 12)) accent else Color(0xFF15141A), Offset(x, 128f), Size(w * 0.6f, 12f))
    }
}
/** Is any note of this pitch class held? */
private fun heldClass(tel: SynthTelemetry, pc: Int): Boolean {
    var nn = pc
    while (nn < 128) { if (tel.isNoteOn(nn)) return true; nn += 12 }
    return false
}

private fun DrawScope.guitar(cs: CatState, now: Float, amp: Float, accent: Color, voices: Int) {
    val wood = Color(0xFFC98A44); val woodDark = Color(0xFF8A5528); val neck = Color(0xFF5A3418)
    // lying on its back, neck off to the right
    drawRoundRect(neck, Offset(150f, 154f), Size(70f, 14f), CornerRadius(2f))
    for (k in 1..6) drawLine(Color(0xFFC9CED6).copy(alpha = 0.6f), Offset(150f + k * 11f, 154f), Offset(150f + k * 11f, 168f), 1f)   // frets
    drawRoundRect(woodDark, Offset(214f, 150f), Size(16f, 22f), CornerRadius(4f))                                                 // headstock
    drawOval(woodDark, Offset(26f, 130f), Size(84f, 60f))
    drawOval(woodDark, Offset(88f, 136f), Size(70f, 48f))
    drawOval(wood, Offset(28f, 132f), Size(80f, 56f))
    drawOval(wood, Offset(90f, 138f), Size(66f, 44f))
    drawCircle(Color(0xFF2A1A10), 13f, Offset(106f, 160f))                                                          // sound hole
    drawCircle(accent.copy(alpha = 0.7f), 15.5f, Offset(106f, 160f), style = Stroke(1.6f))                         // rosette
    drawRoundRect(neck, Offset(52f, 152f), Size(8f, 16f), CornerRadius(1.5f))                                    // bridge
    // strings: shimmer while notes ring
    val ring = if (voices > 0) 0.4f + 1.1f * amp else 0f
    val p = cs.path2
    for (s in 0 until 6) {
        val y0 = 153.5f + s * 2.6f
        p.reset(); p.moveTo(56f, y0)
        var x = 56f
        while (x <= 222f) {
            val env = sin(PI.toFloat() * (x - 56f) / 166f)
            p.lineTo(x, y0 + sin(now * (70f + s * 9f) + s) * ring * env); x += 8f
        }
        drawPath(p, Color(0xFFE8E2D6).copy(alpha = 0.85f), style = Stroke(0.7f + (5 - s) * 0.1f))
    }
}

private fun DrawScope.slime(cs: CatState, dl: Float, dr: Float, pl: Float, pr: Float, now: Float, amp: Float, lfo: Float, accent: Color) {
    val goo = lerp2(Color(0xFF6CFF6A), Color(0xFFE05BFF), 0.5f + 0.5f * lfo)
    val p = cs.path2; p.reset()
    val n = 48
    for (i in 0..n) {
        val a = 2f * PI.toFloat() * i / n
        val r = 1f + 0.05f * sin(3f * a + now * 2f) + 0.035f * sin(5f * a - now * 3f) + amp * 0.07f * sin(7f * a + now * 9f)
        var y = 164f + sin(a) * 30f * r
        val x = 120f + cos(a) * 98f * r
        // the top sags under her paws
        if (sin(a) < 0f) y += 7f * dl * gauss(x - pl) + 7f * dr * gauss(x - pr)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawCircle(goo.copy(alpha = 0.18f + 0.15f * amp), 104f, Offset(120f, 164f))
    drawPath(p, goo.copy(alpha = 0.9f))
    drawPath(p, lerp2(goo, Color.Black, 0.35f), style = Stroke(2f))
    drawOval(Color.White.copy(alpha = 0.35f), Offset(48f, 142f), Size(50f, 9f))
    // bubbles rising inside
    for (k in 0 until 5) {
        val t = (now * 0.4f + k * 0.21f) % 1f
        val bx = 50f + k * 34f + sin(now + k) * 4f
        drawCircle(Color.White.copy(alpha = 0.35f * (1f - t)), 2f + k % 3, Offset(bx, 186f - t * 36f), style = Stroke(1f))
    }
    if (amp > 0.01f) drawCircle(accent.copy(alpha = 0.2f * amp), 6f, Offset(120f, 168f))
}
private fun gauss(dx: Float) = kotlin.math.exp(-(dx * dx) / 200f)

private fun lerp2(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, a.alpha + (b.alpha - a.alpha) * k)
}
