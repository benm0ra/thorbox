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
 * THE CAT: a ginger cat in shades (gold hoop in one ear) making biscuits, kneading with both front
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
                val pl = 98f; val pr = 142f
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
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp))
    }
}

/** 1 at the press (phase 0), 0 lifted (phase 0.5); a quick press and a slower lift, like a real knead. */
private fun press(ph: Float): Float {
    val c = 0.5f + 0.5f * cos(2f * PI.toFloat() * ph)
    return c.pow(0.7f)
}

// ----------------------------------------------------------------------------- the cat

private fun DrawScope.cat(cs: CatState, now: Float, beat: Float, playing: Boolean, amp: Float, lfo: Float,
                          dl: Float, dr: Float, sink: Float, pl: Float, pr: Float) {
    val bob = if (playing) 2.5f * abs(sin(PI.toFloat() * beat)) else 1f * sin(now * 1.4f)
    val lean = (dl - dr) * 3f                                   // weight shifts onto the pressing paw
    val p = cs.path

    // ---- tail: behind her, swishing with the LFO ----
    val wag = lfo * 14f + sin(now * 1.3f) * 5f
    p.reset(); p.moveTo(166f, 132f)
    p.cubicTo(196f, 136f, 214f, 112f + wag * 0.5f, 206f + wag * 0.4f, 84f + wag)
    drawPath(p, FUR_DARK, style = Stroke(11f, cap = StrokeCap.Round))
    drawPath(p, FUR, style = Stroke(8f, cap = StrokeCap.Round))
    drawCircle(FUR_DARK, 4.5f, Offset(206f + wag * 0.4f, 84f + wag))

    // ---- body: a loaf behind the paws, cream bib ----
    drawOval(FUR_DARK, Offset(60f + lean, 84f + bob * 0.5f), Size(120f, 68f))
    drawOval(FUR, Offset(62f + lean, 85f + bob * 0.5f), Size(116f, 64f))
    for (k in 0 until 3) {                                       // tabby stripes on the flank
        val x = 150f + k * 8f + lean
        drawLine(FUR_DARK, Offset(x, 96f + bob * 0.5f), Offset(x + 4f, 116f + bob * 0.5f), 3f, cap = StrokeCap.Round)
    }
    drawOval(CREAM, Offset(100f + lean, 92f + bob * 0.5f), Size(40f, 44f))
    // purr: little lines at her sides while anything sounds
    if (amp > 0.02f || playing) {
        val j = sin(now * 60f) * 1.2f
        for (sd in floatArrayOf(-1f, 1f)) for (k in 0 until 2) {
            val x = 120f + sd * (68f + k * 7f) + j
            drawLine(FUR_LIGHT.copy(alpha = 0.5f - k * 0.2f), Offset(x, 104f + k * 6f), Offset(x + sd * 4f, 100f + k * 6f), 1.6f, cap = StrokeCap.Round)
        }
    }

    // ---- front legs + paws: kneading ----
    for (side in 0..1) {
        val d = if (side == 0) dl else dr
        val px = if (side == 0) pl else pr
        val sx = if (side == 0) 106f else 134f
        val lift = 12f * (1f - d).pow(1.5f)
        val py = 140f + sink * d - lift
        val shY = 110f + bob * 0.5f
        drawLine(FUR_DARK, Offset(sx + lean, shY), Offset(px, py - 3f), 17f, cap = StrokeCap.Round)
        drawLine(FUR, Offset(sx + lean, shY), Offset(px, py - 3f), 14f, cap = StrokeCap.Round)
        // the paw: white sock, toes spread when it presses; lifted high you see the beans
        val spread = 1.5f + 2.5f * d
        drawOval(CREAM, Offset(px - 10f - spread * 0.5f, py - 6f), Size(20f + spread, 11f))
        for (t in -1..1) drawLine(FUR_DARK.copy(alpha = 0.55f), Offset(px + t * (3.5f + spread * 0.4f), py - 1f), Offset(px + t * (3.5f + spread * 0.6f), py + 4f), 1.2f, cap = StrokeCap.Round)
        if (lift > 7f) {
            drawCircle(BEAN, 2.6f, Offset(px, py - 1f))
            for (t in -1..1) drawCircle(BEAN, 1.3f, Offset(px + t * 4f, py - 5.5f))
        }
    }

    // ---- head ----
    val hy = 62f + bob
    val tw = now - cs.twitchAt
    val twitch = if (tw in 0f..0.3f) sin(tw * 40f) * 10f * (1f - tw / 0.3f) else 0f
    ear(cs, 92f, hy, -1f, -twitch)
    ear(cs, 148f, hy, 1f, twitch * 0.6f)
    drawOval(FUR_DARK, Offset(80f, hy - 32f), Size(80f, 64f))
    drawOval(FUR, Offset(82f, hy - 31f), Size(76f, 62f))
    // cheek fluff
    for (sd in floatArrayOf(-1f, 1f)) {
        p.reset(); p.moveTo(120f + sd * 34f, hy + 4f); p.lineTo(120f + sd * 46f, hy + 14f); p.lineTo(120f + sd * 32f, hy + 18f); p.close()
        drawPath(p, FUR)
    }
    // forehead stripes
    drawLine(FUR_DARK, Offset(120f, hy - 30f), Offset(120f, hy - 20f), 3f, cap = StrokeCap.Round)
    drawLine(FUR_DARK, Offset(111f, hy - 28f), Offset(113f, hy - 20f), 2.5f, cap = StrokeCap.Round)
    drawLine(FUR_DARK, Offset(129f, hy - 28f), Offset(127f, hy - 20f), 2.5f, cap = StrokeCap.Round)
    // muzzle, nose, a smug little mouth
    drawOval(CREAM, Offset(104f, hy + 2f), Size(32f, 22f))
    p.reset(); p.moveTo(115f, hy + 5f); p.lineTo(125f, hy + 5f); p.lineTo(120f, hy + 10f); p.close()
    drawPath(p, BEAN)
    drawLine(FUR_DARK, Offset(120f, hy + 10f), Offset(120f, hy + 13f), 1.4f)
    drawArc(FUR_DARK, 20f, 120f, false, Offset(113f, hy + 9f), Size(7f, 6f), style = Stroke(1.4f))
    drawArc(FUR_DARK, 40f, 120f, false, Offset(120f, hy + 9f), Size(8f, 7f), style = Stroke(1.4f))      // the smirk side
    // whiskers
    for (sd in floatArrayOf(-1f, 1f)) for (k in -1..1) {
        drawLine(CREAM.copy(alpha = 0.85f), Offset(120f + sd * 14f, hy + 10f + k * 2.5f), Offset(120f + sd * 40f, hy + 6f + k * 6f), 1f)
    }
    // brows pop over the shades on loud notes
    val brow = 3f * amp + (if (tw in 0f..0.25f) 1.5f else 0f)
    drawLine(FUR_DARK, Offset(96f, hy - 14f - brow), Offset(112f, hy - 16f - brow), 2.4f, cap = StrokeCap.Round)
    drawLine(FUR_DARK, Offset(128f, hy - 16f - brow), Offset(144f, hy - 14f - brow), 2.4f, cap = StrokeCap.Round)
    // shades: two dark lenses, a bridge, and a glint that sweeps across now and then
    drawRoundRect(SHADES, Offset(93f, hy - 12f), Size(25f, 14f), CornerRadius(5f))
    drawRoundRect(SHADES, Offset(122f, hy - 12f), Size(25f, 14f), CornerRadius(5f))
    drawLine(SHADES, Offset(116f, hy - 9f), Offset(124f, hy - 9f), 2.4f)
    drawLine(SHADES, Offset(93f, hy - 9f), Offset(86f, hy - 12f), 2f)
    drawLine(SHADES, Offset(147f, hy - 9f), Offset(154f, hy - 12f), 2f)
    val g = (now * 0.35f) % 1f
    if (g < 0.25f) {
        val gx = 93f + g / 0.25f * 54f
        if (gx < 116f || gx > 124f) drawLine(Color.White.copy(alpha = 0.65f), Offset(gx, hy - 10f), Offset(gx + 5f, hy - 1f), 1.6f, cap = StrokeCap.Round)
    }
    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(96f, hy - 10f), Size(9f, 3f), CornerRadius(1.5f))
    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(125f, hy - 10f), Size(9f, 3f), CornerRadius(1.5f))
}

/** An ear: fur triangle, pink inside, swivelling on its base by [angle] degrees. The right one wears a gold hoop. */
private fun DrawScope.ear(cs: CatState, x: Float, hy: Float, side: Float, angle: Float) {
    rotate(angle, Offset(x, hy - 16f)) {
        val p = cs.path2
        p.reset(); p.moveTo(x + 10f * side, hy - 18f); p.lineTo(x + 2f * side, hy - 46f); p.lineTo(x - 16f * side, hy - 28f); p.close()
        drawPath(p, FUR_DARK)
        p.reset(); p.moveTo(x + 6f * side, hy - 22f); p.lineTo(x + 2f * side, hy - 40f); p.lineTo(x - 8f * side, hy - 28f); p.close()
        drawPath(p, BEAN.copy(alpha = 0.8f))
        if (side > 0f) drawCircle(HOOP, 3.2f, Offset(x + 10f, hy - 20f), style = Stroke(1.4f))
    }
}

// ----------------------------------------------------------------------------- what she kneads

private fun DrawScope.pillow(cs: CatState, dl: Float, dr: Float, pl: Float, pr: Float, now: Float, amp: Float, accent: Color) {
    val base = lerp2(Color(0xFFB9A6E8), accent, 0.25f)
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
    val ring = if (voices > 0) 0.6f + 1.8f * amp else 0f
    val p = cs.path2
    for (s in 0 until 6) {
        val y0 = 153.5f + s * 2.6f
        p.reset(); p.moveTo(56f, y0)
        var x = 56f
        while (x <= 222f) {
            val env = sin(PI.toFloat() * (x - 56f) / 166f)
            p.lineTo(x, y0 + sin(now * (70f + s * 9f) + x * 0.3f) * ring * env); x += 8f
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
