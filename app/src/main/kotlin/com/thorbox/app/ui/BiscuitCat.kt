// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.SynthTelemetry
import com.thorbox.model.LfoTarget
import com.thorbox.model.SynthCategory
import com.thorbox.model.SynthPatch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * THE CAT: a ginger tabby in Y2K shades (white oval frames, chrome lenses; a gold hoop in one ear), side-on
 * and crouched in her room by the El (house style: GraffKit.kt, docs/ART_STYLE.md), making biscuits
 * with both front paws in turn, on whatever suits the sound family of the current patch:
 *
 *   PAD    a fat pillow          (slow, deep presses; the pillow puffs back up)
 *   808    a waterbed            (every press sends ripples; the sub makes the whole thing slosh)
 *   BASS   a speaker, cone up    (the woofer pumps with the synth's level)
 *   LEAD   a turntable           (each press scratches the record back, then it spins on)
 *   KEYS   a little keyboard     (the keys you play light up; the ones under her paws go down)
 *   PLUCK  an acoustic guitar    (strings shimmer while notes ring)
 *   WEIRD  a glowing slime blob  (wobbles, sinks under her paws, shifts colour with the LFO)
 *
 * And the patch itself plays out on her, so you can read the synth by watching the cat:
 *
 *   OSC     SHAPE 1/2 + MIX   her tail IS the waveform (smooth curl for sine … crenellated for square)
 *           DETUNE            her out-of-phase twin drifts beside her; MIX sets how solid the twin is
 *   FILTER  CUTOFF (live)     how bright the room is: a closed filter is a dim room, wide open sparkles;
 *                             the filter envelope and key tracking flash it, a cutoff LFO flickers it
 *           RESO              her whiskers buzz and glow
 *   AMP     ATTACK / RELEASE  how slowly she presses down / lifts up; her purr follows the volume
 *   LFO     a moth flies on the LFO (wings beat at its rate, roams by its depth); its TARGET shows too:
 *           pitch bobs her head, cutoff flickers the light, amp pulses the purr, shape wiggles the tail
 *   PITCH   OCT 1/2           low = a big chonk, high = kitten-sized
 *           P.ENV / P.DECAY   she hops on every note, as high and as long as the pitch drop
 *   VOICE   MONO off          every extra note held brings a kitten to knead with her (up to three)
 *           GLIDE             she slides into her presses (speed lines)
 *           DRIVE             she grows a mohawk
 *           NOISE             her fur crackles with static
 * Plus: little notes float up when she hears one, her ears twitch, her brows pop on loud notes.
 *
 * Turning a knob puts a caption under her saying what that knob does to her ([catHint]).
 * All original artwork; draws every frame without allocation. Stage: 240 × 200 virtual units.
 */

private class CatState {
    val path = Path()
    val path2 = Path()
    var lastNote = -1
    var twitchAt = -10f
    var hopAt = -10f
    var lastT = 0f
    var kittens = 0f
    val noteAt = FloatArray(6) { -10f }
    val noteY = FloatArray(6)
    var noteNext = 0
    val twin = Paint()
    var twinTint = 0
    // the look for this frame, read by cat() (set once per frame from the patch + telemetry)
    var tailShape = 0.666f; var tailAmp = 3.5f; var tailPhase = 0f
    var whisker = 0f; var mohawk = 0f; var static = 0f; var glide = 0f; var headLfo = 0f; var purr = 1f
    var accent = Color.White
    // her rig (stepped once per frame in BiscuitCat, read by cat()): weight shift into each press, the
    // head following a beat late, ears and tail lagging, a squash on landing, the shades peek
    val lean = Spring(4.5f, 0.45f)        // body tilt (deg): the chest dips into each press
    val head = Spring(5f, 0.38f)          // head drop, lagging the chest
    val ears = Spring(4f, 0.35f)          // ear flop (deg) from the head's motion (±5°: tuned offline)
    val tailS = Spring(1.8f, 0.3f)        // tail sway, lagging the body
    val land = Spring(9f, 0.3f)           // landing squash after a hop
    var wasUp = false
    var peekAt = 9f                       // she lowers her shades for a look now and then
    val trainJam = PieceCache(); val trainNite = PieceCache(); val tag = PieceCache()
    var lean0 = 0f; var head0 = 0f; var ear0 = 0f; var tail0 = 0f; var land0 = 0f; var peek = 0f; var peekBlink = 0f
}

// her colours (her own, the same on every theme)
private val FUR = Color(0xFFE08A3C)
private val FUR_DARK = Color(0xFFB0632A)
private val FUR_LIGHT = Color(0xFFF3B067)
private val CREAM = Color(0xFFF6E9D8)
private val BEAN = Color(0xFFF2A1A8)
private val SHADES = Color(0xFF111118)
private val HOOP = Color(0xFFE6B93C)
private val OUT = Line79.INK
private val FUR_SHADOW = Color(0xFF9C5A28)
private val CREAM_SHADOW = Color(0xFFD9CBB9)
private val KITTEN_FUR = longArrayOf(0xFFE9A15A, 0xFF9AA0AA, 0xFFF0E2CC)

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

/** What a knob does to the cat, for the caption while you turn it (labels as in SynthParams). */
fun catHint(label: String, patch: SynthPatch): String = when (label) {
    "SHAPE 1", "SHAPE 2" -> "her tail takes the waveform's shape"
    "DETUNE" -> "detune: her out-of-step twin drifts beside her"
    "MIX" -> "mix: tail blends the two shapes · twin gets solid"
    "CUTOFF" -> "cutoff: how bright her room is"
    "RESO" -> "resonance: her whiskers buzz"
    "ENV AMT" -> "envelope amount: each note flashes the lights"
    "KEY TRK" -> "key tracking: high notes light the room up"
    "ATTACK" -> if (patch.ampEnv.attackMs > 0f) "attack: how slowly she presses down" else "attack"
    "DECAY", "SUSTAIN" -> "her purr follows the volume"
    "RELEASE" -> "release: how slowly she lifts her paws"
    "RATE" -> "the moth's wings beat at the LFO rate"
    "DEPTH" -> "depth: how far the moth roams"
    "WAVE" -> "the moth flies the LFO's wave"
    "TARGET" -> when (patch.lfo.target) {
        LfoTarget.PITCH -> "LFO → pitch: her head bobs"
        LfoTarget.CUTOFF -> "LFO → cutoff: the lights flicker"
        LfoTarget.AMP -> "LFO → volume: her purr pulses"
        LfoTarget.SHAPE -> "LFO → shape: her tail wiggles"
    }
    "P.ENV" -> "pitch drop: she hops on every note"
    "P.DECAY" -> "pitch decay: how long each hop lasts"
    "OCT 1", "OCT 2" -> "octave: low = big chonk, high = kitten"
    "GLIDE" -> "glide: she slides into each press"
    "MONO" -> if (patch.voice.mono) "mono: she plays alone" else "poly: every extra note brings a kitten"
    "DRIVE" -> "drive grows her mohawk"
    "NOISE" -> "noise: her fur crackles with static"
    else -> ""
}

@Composable
fun BiscuitCat(state: AppState, family: SynthCategory, accent: Color, patch: SynthPatch,
               hint: String, hintAlpha: Float, modifier: Modifier = Modifier) {
    val cs = remember { CatState() }
    val tel = state.telemetry
    val tm = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 8)
    val dens = androidx.compose.ui.platform.LocalDensity.current
    fun unitSp(u: Float) = (u / dens.density / dens.fontScale).sp
    val tagStyle = remember(dens) { androidx.compose.ui.text.TextStyle(fontSize = unitSp(15f), fontFamily = Anton, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
    val trainStyle = remember(dens) { androidx.compose.ui.text.TextStyle(fontSize = unitSp(17f), fontFamily = ArchivoBlack) }
    // the closed-filter shade, in stage units: full in the middle, gone by the rim (alpha set per frame)
    val dimPool = remember {
        androidx.compose.ui.graphics.Brush.radialGradient(
            0f to Color(0xFF07060C), 0.55f to Color(0xFF07060C).copy(alpha = 0.85f), 1f to Color(0x0007060C),
            center = Offset(120f, 108f), radius = 124f)
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            tel.frame.longValue                                    // redraw every frame, never recompose
            val now = tel.timeSec
            val dt = (now - cs.lastT).coerceIn(0f, 0.1f); cs.lastT = now
            val playing = state.groove.transport[1] != 0.0
            val beat = if (playing) ((state.groove.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat() else (now * 0.75f) % 1f
            val amp = tel.ampLevel.coerceIn(0f, 1f)
            val voices = tel.activeVoices
            val lfo = tel.lfoValue
            val v = patch.voice
            val depthL = patch.lfo.depth
            if (tel.newestNote != cs.lastNote) {
                if (voices > 0) {
                    cs.twitchAt = now; cs.hopAt = now
                    cs.noteAt[cs.noteNext] = now; cs.noteY[cs.noteNext] = ((tel.newestNote - 60) * 0.5f).coerceIn(-14f, 14f)
                    cs.noteNext = (cs.noteNext + 1) % cs.noteAt.size
                }
                cs.lastNote = tel.newestNote
            }

            // ---- the patch → her look, this frame ----
            val mix = patch.oscMix
            cs.accent = accent
            cs.tailShape = patch.osc1.shape + (patch.osc2.shape - patch.osc1.shape) * mix
            cs.tailAmp = 3.5f * (if (patch.lfo.target == LfoTarget.SHAPE) 1f + lfo * depthL * 1.5f else 1f)
            cs.tailPhase = now * (0.6f + 1.4f * amp)
            cs.whisker = patch.resonance
            cs.mohawk = v.drive
            cs.static = v.noise
            cs.glide = (v.glideMs / 400f).coerceIn(0f, 1f)
            cs.headLfo = if (patch.lfo.target == LfoTarget.PITCH) lfo * depthL * 4f else 0f
            cs.purr = if (patch.lfo.target == LfoTarget.AMP) 0.4f + 0.6f * (0.5f + 0.5f * lfo * depthL) else 1f
            // the room's light: the LIVE cutoff (envelope, key tracking and a cutoff LFO all move it)
            val hz = if (voices > 0 && tel.cutoffHz > 1f) tel.cutoffHz else patch.cutoffHz
            var bright = (ln(hz / 60f) / ln(14000f / 60f)).coerceIn(0f, 1f)
            if (patch.lfo.target == LfoTarget.CUTOFF) bright = (bright + lfo * depthL * 0.15f).coerceIn(0f, 1f)
            // size from the octave: low = chonk, high = kitten
            val oct = patch.osc1.octave + (patch.osc2.octave - patch.osc1.octave) * mix
            val catSize = 1.13f.pow(-oct).coerceIn(0.78f, 1.25f)
            // the hop on a pitch drop
            val hopH = (abs(v.pitchEnvSemis) * 0.7f).coerceAtMost(16f)
            val hopT = (v.pitchEnvDecayMs / 1000f * 3f).coerceIn(0.15f, 0.6f)
            val hu = (now - cs.hopAt) / hopT
            val hop = if (hopH > 1f && hu in 0f..1f) -hopH * 4f * hu * (1f - hu) else 0f
            // kittens: one per extra note held, poly only; they pop in and wander off
            val target = if (v.mono) 0f else (voices - 1).coerceIn(0, 3).toFloat()
            cs.kittens += (target - cs.kittens) * min(1f, dt * (if (target > cs.kittens) 10f else 2.5f))

            val sc = min(size.width / 240f, size.height / 200f)
            val ox = (size.width - 240f * sc) / 2f; val oy = (size.height - 200f * sc) / 2f
            Graff.begin(density, sc)                                      // house-style line weights for this scale
            withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
                // her room by the El: the wall, a window onto the elevated line (a train goes by), the floor
                room(cs, -ox / sc, (size.width - ox) / sc, -oy / sc, (size.height - oy) / sc, now, amp, accent, tm, tagStyle, trainStyle)
                drawOval(Line79.INK.copy(alpha = 0.35f), Offset(20f, 182f), Size(200f, 14f))

                // press amount per paw: 1 = fully down, 0 = lifted; near paw on the beat, far on the off-beat.
                // ATTACK sets how slowly she pushes down, RELEASE how slowly she lifts.
                val atk = (log10(1f + patch.ampEnv.attackMs) / 3.4f).coerceIn(0f, 1f)
                val rel = (log10(1f + patch.ampEnv.releaseMs) / 3.6f).coerceIn(0f, 1f)
                val dl = press(beat, atk, rel); val dr = press((beat + 0.5f) % 1f, atk, rel)
                val depth = 0.6f + 0.6f * amp
                // ---- her rig: springs chase the kneading, so the body has weight and the extremities lag ----
                val breath = if (!playing && amp < 0.02f) sin(now * 1.7f) * 0.7f else 0f
                cs.lean0 = cs.lean.step((dl * 0.6f + dr * 0.4f) * 2.6f * depth + breath, dt)
                cs.head0 = cs.head.step(cs.lean0 * 1.3f, dt)
                cs.ear0 = cs.ears.step(cs.head.v * -0.12f, dt)
                cs.tail0 = cs.tailS.step(cs.lean0 * 5f + bob(beat, playing) * 3f, dt)
                val up = hop < -0.5f
                if (cs.wasUp && !up) cs.land.kick(5f)                           // she lands: a little squash
                cs.wasUp = up
                cs.land0 = cs.land.step(0f, dt)
                // the peek: every 9-16 s (when it's calm), the shades slide down her nose; she gives you a slow blink
                if (now > cs.peekAt + 2.2f) cs.peekAt = now + 9f + ((now * 7.31f) % 7f)
                val pu = (now - cs.peekAt) / 2.2f
                val calm = amp < 0.6f
                cs.peek = if (calm && pu in 0f..1f) smooth01(minOf(pu / 0.15f, (1f - pu) / 0.15f, 1f)) else 0f
                cs.peekBlink = if (pu in 0.45f..0.75f) sin((pu - 0.45f) / 0.3f * PI.toFloat()) else 0f
                val pl = 120f + (82f - 120f) * catSize; val pr = 120f + (108f - 120f) * catSize
                when (family) {
                    SynthCategory.PAD -> pillow(cs, dl * depth, dr * depth, pl, pr, now, amp, accent)
                    SynthCategory.SUB808 -> waterbed(beat, dl * depth, dr * depth, pl, pr, now, amp, accent, cs)
                    SynthCategory.BASS -> speaker(dl * depth, dr * depth, now, amp, accent)
                    SynthCategory.LEAD -> turntable(dl, dr, beat, now, amp, accent)
                    SynthCategory.KEYS -> keyboard(tel, dl, dr, pl, pr, accent)
                    SynthCategory.PLUCK -> guitar(cs, now, amp, accent, voices)
                    SynthCategory.WEIRD -> slime(cs, dl * depth, dr * depth, pl, pr, now, amp, lfo, accent)
                }
                val sink = when (family) { SynthCategory.PAD -> 7f; SynthCategory.WEIRD -> 9f; SynthCategory.SUB808 -> 4f; else -> 2f }

                // DETUNE: her twin, a tinted ghost drifting in and out of step beside her
                val det = patch.osc2.detuneCents
                if (abs(det) > 2f) {
                    val drift = sign(det) * (3f + abs(det) * 0.16f) * (0.55f + 0.45f * sin(now * (0.8f + abs(det) * 0.05f)))
                    if (cs.twinTint != accent.toArgb()) { cs.twinTint = accent.toArgb(); cs.twin.colorFilter = ColorFilter.tint(accent, BlendMode.Modulate) }
                    cs.twin.alpha = (0.18f + 0.35f * mix).coerceAtMost(0.5f)
                    val lag = (abs(det) / 400f).coerceAtMost(0.2f)
                    drawIntoCanvas { it.saveLayer(Rect(0f, 0f, 240f, 200f), cs.twin) }
                    Graff.lite = true                                        // the ghost: flat fills (detail is lost under the tint anyway)
                    withTransform({ translate(drift, hop * 0.8f); scale(catSize, catSize, Offset(120f, 142f)) }) {
                        cat(cs, now - lag, (beat + 1f - lag) % 1f, playing, amp, lfo, press((beat + 1f - lag) % 1f, atk, rel),
                            press((beat + 1.5f - lag) % 1f, atk, rel), sink * depth, 82f, 108f)
                    }
                    Graff.lite = false
                    drawIntoCanvas { it.restore() }
                }
                val squashL = (cs.land0 * 0.02f).coerceIn(-0.12f, 0.12f)
                withTransform({ translate(0f, hop); scale(catSize * (1f + squashL * 0.6f), catSize * (1f - squashL), Offset(120f, 142f)) }) {
                    cat(cs, now, beat, playing, amp, lfo, dl, dr, sink * depth, 82f, 108f)
                }
                // POLY: kittens kneading along the front edge
                for (k in 0 until 3) {
                    val pop = (cs.kittens - k).coerceIn(0f, 1f)
                    if (pop > 0.02f) kitten(cs, 150f + k * 26f, 152f + (k % 2) * 4f, pop, now, beat + k * 0.31f, Color(KITTEN_FUR[k]))
                }
                // LFO: the moth
                if (depthL > 0.03f) moth(now, lfo, tel.lfoPhase, patch.lfo.rateHz, depthL, accent, bright)
                // notes she heard, floating up
                for (i in cs.noteAt.indices) {
                    val u = (now - cs.noteAt[i]) / 1.6f
                    if (u in 0f..1f) musicNote(70f + sin(u * 6f + i) * 5f + i * 3f, 40f - cs.noteY[i] - u * 34f, accent.copy(alpha = (1f - u) * 0.9f))
                }
                // the room's light: a soft pool of shade around her when the filter's closed (it fades out
                // before the panel's edges, so no slab and nothing spills onto the waveform next door; lighter
                // on light themes), sparkles when it's wide open
                if (bright < 0.98f) {
                    val dim = 0.5f * (1f - bright).pow(1.3f) * (if (Tb.palette.light) 0.55f else 1f)
                    drawCircle(dimPool, 124f, Offset(120f, 108f), alpha = dim.coerceIn(0f, 1f))
                }
                if (bright > 0.72f) {
                    val k = (bright - 0.72f) / 0.28f
                    for (i in 0 until 5) {
                        val tw = 0.5f + 0.5f * sin(now * (2.3f + i) + i * 1.7f)
                        sparkle(24f + i * 46f, 16f + (i * 37 % 4) * 10f, 3f + 2.5f * tw, k * tw * 0.9f)
                    }
                }
            }
        }
        // her room is night on every theme: the family colour lifted toward white so it reads on the dark wall
        val tagColor = androidx.compose.ui.graphics.lerp(accent, Color.White, 0.45f)
        Text("${family.label} · ${kneadObjectName(family)}", color = tagColor, fontSize = 10.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 2.dp))
        if (hint.isNotEmpty()) Text(hint, color = Line79.WHITE, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 4.dp).alpha(hintAlpha))
    }
}

/**
 * How far a paw is pressed (1 = down, 0 = up) through one knead cycle; the press lands at phase 0.
 * [atk] 0…1 stretches the push down (slow attack = slow push), [rel] 0…1 the lift (long release = slow lift).
 */
private fun press(ph: Float, atk: Float, rel: Float): Float {
    val down = 0.08f + 0.32f * atk                    // fraction of the cycle spent pushing down
    val up = 0.12f + 0.36f * rel                      // fraction spent lifting
    val x = when {
        ph < up -> 1f - ph / up                       // just landed: lifting off
        ph > 1f - down -> (ph - (1f - down)) / down   // coming down
        else -> 0f
    }
    return x * x * (3f - 2f * x)
}


/** A little ♪: head, stem, flag. */
private fun DrawScope.musicNote(x: Float, y: Float, c: Color) {
    drawOval(c, Offset(x - 3.4f, y - 2.2f), Size(6.8f, 4.6f))
    drawLine(c, Offset(x + 3f, y), Offset(x + 3f, y - 11f), 1.3f)
    drawLine(c, Offset(x + 3f, y - 11f), Offset(x + 7f, y - 7f), 1.6f, cap = StrokeCap.Round)
}

/** The LFO moth: flies the LFO (x = its value, a figure-8 on its phase), wings beat at its rate, glows in the dark. */
private fun DrawScope.moth(now: Float, lfo: Float, phase: Float, rate: Float, depth: Float, accent: Color, bright: Float) {
    val roam = 18f + 62f * sqrt(depth)
    val x = 132f + lfo * roam
    val y = 34f + sin(phase * 4f * PI.toFloat()) * 8f
    val flap = abs(sin(now * PI.toFloat() * rate.coerceIn(0.3f, 14f)))
    drawCircle(accent.copy(alpha = 0.10f + 0.25f * (1f - bright)), 12f, Offset(x, y))
    for (sd in floatArrayOf(-1f, 1f)) {
        val w = 3f + 6f * flap
        inkOval(if (sd < 0) x - w - 1f else x + 1f, y - 6f, w, 7f, Color(0xFFEDE3C8), w = Graff.kl * 0.5f)
        inkOval(if (sd < 0) x - w * 0.8f - 1f else x + 1f, y, w * 0.8f, 5f, Color(0xFFD9CCA8), w = Graff.kl * 0.5f)
    }
    inkOval(x - 1.4f, y - 5f, 2.8f, 10f, Color(0xFF6B5A44), w = Graff.kl * 0.4f)
    drawLine(Color(0xFF6B5A44), Offset(x, y - 5f), Offset(x - 3f, y - 9f), 0.7f)
    drawLine(Color(0xFF6B5A44), Offset(x, y - 5f), Offset(x + 3f, y - 9f), 0.7f)
}

/** A kitten: round, big-eyed, no shades yet; it bobs on its own little knead. [pop] scales it in. */
private fun DrawScope.kitten(cs: CatState, x: Float, y: Float, pop: Float, now: Float, beat: Float, fur: Color) {
    val dark = lerp2(fur, Color.Black, 0.3f)
    val b = 1.5f * abs(sin(PI.toFloat() * (beat % 1f)))
    withTransform({ scale(pop, pop, Offset(x, y + 14f)) }) {
        limb(x + 17f, y + 8f, x + 22f, y - 1f + sin(now * 3f + x) * 2f, 2.6f, fur)                    // tail
        inkOval(x, y + 3f, 18f, 11f, fur, dark, 2f, Graff.kl * 0.8f)                                // body
        val p = cs.path2
        for (sd in floatArrayOf(-1f, 1f)) {                                                        // ears
            p.reset(); p.moveTo(x + 4f + sd * 4f, y - 6f + b); p.lineTo(x + 4f + sd * 7f, y - 14f + b); p.lineTo(x + 4f + sd * 1f, y - 9f + b); p.close()
            ink(p, fur, Graff.kl * 0.7f)
        }
        inkCircle(x + 4f, y - 3f + b, 6.8f, fur, dark, 1.6f, Graff.kl * 0.8f)                       // head
        for (sd in floatArrayOf(-1f, 1f)) {                                                        // big eyes
            drawCircle(Color(0xFF1A1A22), 1.9f, Offset(x + 4f + sd * 2.8f, y - 4f + b))
            drawCircle(Color.White, 0.7f, Offset(x + 4.5f + sd * 2.8f, y - 4.6f + b))
        }
        drawCircle(BEAN, 0.9f, Offset(x + 4f, y - 1.4f + b))
        inkOval(x - 1f, y + 11f - b * 0.6f, 6f, 3.5f, CREAM, w = Graff.kl * 0.5f)                    // kneading paw
    }
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
    // her tail IS the oscillator's waveform, travelling along it (sine curls … square crenellates)
    val wag = lfo * 10f + sin(now * 1.3f) * 4f + cs.tail0
    val x3 = 210f + wag * 0.3f; val y3 = 60f + wag
    p.reset()
    val n = 28
    var tipX = 190f; var tipY = 120f
    for (i in 0..n) {
        val t = i / n.toFloat(); val u = 1f - t
        val bx = u * u * u * 190f + 3f * u * u * t * 222f + 3f * u * t * t * 230f + t * t * t * x3
        val by = u * u * u * 120f + 3f * u * u * t * 116f + 3f * u * t * t * (80f + wag * 0.5f) + t * t * t * y3
        // tangent → normal
        val tx = 3f * u * u * (222f - 190f) + 6f * u * t * (230f - 222f) + 3f * t * t * (x3 - 230f)
        val ty = 3f * u * u * (116f - 120f) + 6f * u * t * (80f + wag * 0.5f - 116f) + 3f * t * t * (y3 - 80f - wag * 0.5f)
        val tl = sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
        val off = cs.tailAmp * sqrt(t) * wave(cs.tailShape, t * 2.5f - cs.tailPhase)
        val x = bx - ty / tl * off; val y = by + tx / tl * off
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        tipX = x; tipY = y
    }
    limbPath(p, 8f, FUR, FUR_DARK)
    inkCircle(tipX, tipY, 4.4f, FUR_DARK, w = Graff.kl * 0.8f)

    // the chest dips into each press: everything from here to the head tilts around her haunch
    val lean = cs.lean0
    val pivot = Offset(176f, 142f)
    val ca = kotlin.math.cos(-lean * PI.toFloat() / 180f); val sa = sin(-lean * PI.toFloat() / 180f)
    fun tiltY(x: Float, y: Float) = pivot.y + (x - pivot.x) * sa + (y - pivot.y) * ca
    fun tiltX(x: Float, y: Float) = pivot.x + (x - pivot.x) * ca - (y - pivot.y) * sa
    // ---- far front leg, in shadow behind her chest ----
    val liftF = 10f * (1f - df).pow(1.5f)
    val pfx = farX + 2f - 4f * (1f - df); val pfy = 140f + sink * df - liftF
    val fsx = tiltX(112f, 110f + rock); val fsy = tiltY(112f, 110f + rock)
    p.reset(); p.moveTo(fsx, fsy); p.quadraticTo((fsx + pfx) / 2f - 4f, (fsy + pfy) / 2f, pfx, pfy - 4f)
    limbPath(p, 12f, FUR_SHADOW)
    inkOval(pfx - 2f - 8f - 2f * df, pfy - 5f, 16f + 4f * df, 10f, CREAM_SHADOW, w = Graff.kl * 0.8f)

    // ---- body: back, haunch, belly ----
    // DRIVE: a mohawk along her back (drawn first, so the body hides the roots)
    if (cs.mohawk > 0.04f) {
        val h = 2f + cs.mohawk * 13f
        val col = lerp2(FUR_DARK, cs.accent, cs.mohawk)
        for (k in 0 until 7) {
            val t = 0.12f + k * 0.08f; val u = 1f - t
            val bx = u * u * u * 90f + 3f * u * u * t * 110f + 3f * u * t * t * 166f + t * t * t * 192f
            val by = u * u * u * 98f + 3f * u * u * t * 80f + 3f * u * t * t * 78f + t * t * t * 94f
            val hk = h * (0.7f + 0.3f * sin(k * 1.7f)) + sin(now * 9f + k) * cs.mohawk
            p.reset(); p.moveTo(bx - 5f, by + 3f); p.lineTo(bx + 3f, by - hk); p.lineTo(bx + 5f, by + 3f); p.close()
            ink(p, col, Graff.kl * 0.8f)
        }
    }
    rotate(-lean, pivot) {
    val body = cs.path2                                                                         // kept: the stripes clip to it
    body.reset()
    body.moveTo(90f, 98f)
    body.cubicTo(110f, 80f, 166f, 78f, 192f, 94f)
    body.cubicTo(210f, 106f, 208f, 136f, 188f, 143f)
    body.lineTo(152f, 143f)
    body.cubicTo(142f, 134f, 130f, 124f, 116f, 121f)
    body.cubicTo(102f, 119f, 90f, 114f, 87f, 106f)
    body.cubicTo(86f, 102f, 87f, 100f, 90f, 98f)
    body.close()
    keyline(body, Graff.kl * 2f)                                                                // the silhouette's keyline, under the fill
    cel(body, FUR, FUR_DARK, 6f)
    p.reset(); p.moveTo(150f, 142f); p.cubicTo(146f, 120f, 160f, 100f, 184f, 100f)            // haunch
    drawPath(p, Line79.INK, style = Stroke(Graff.kl * 0.7f, cap = StrokeCap.Round))
    inkOval(150f, 136.5f, 20f, 9f, CREAM, w = Graff.kl * 0.8f)                                // hind paw
    clipPath(body) {                                                                            // tabby stripes, inside her coat only
        for (k in 0 until 4) {
            val x = 130f + k * 14.5f; val y = 78f
            p.reset(); p.moveTo(x, y); p.quadraticTo(x + 3f, y + 10f, x, y + 18f)
            drawPath(p, FUR_DARK, style = Stroke(3f, cap = StrokeCap.Round))
        }
    }
    shine(118f, 90f, 10f, -15f, Graff.kl)
    // NOISE: static crackling off her fur
    if (cs.static > 0.03f) {
        val frame = (now * 14f).toInt()
        val count = (2 + cs.static * 10f).toInt()
        for (i in 0 until count) {
            val hsh = ((frame * 73 + i * 151) * 2654435761L).toInt() ushr 8
            val t = (hsh and 1023) / 1023f; val u = 1f - t
            val bx = u * u * u * 90f + 3f * u * u * t * 110f + 3f * u * t * t * 166f + t * t * t * 192f
            val by = u * u * u * 98f + 3f * u * u * t * 80f + 3f * u * t * t * 78f + t * t * t * 94f - 3f
            val c = (if (i % 2 == 0) Color.White else cs.accent).copy(alpha = 0.5f + 0.5f * cs.static)
            drawLine(c, Offset(bx, by), Offset(bx + 2f, by - 4f), 1f); drawLine(c, Offset(bx + 2f, by - 4f), Offset(bx - 1f, by - 6f), 1f)
        }
    }
    }   // (end of the body tilt)
    // purr: little lines off her chest while anything sounds
    if (amp > 0.02f || playing) {
        val j = sin(now * 60f) * 1f
        for (k in 0 until 2) drawLine(FUR_LIGHT.copy(alpha = (0.55f - k * 0.2f) * cs.purr), Offset(78f - k * 6f + j, 112f + k * 6f), Offset(74f - k * 6f + j, 108f + k * 6f), 1.6f, cap = StrokeCap.Round)
    }

    // ---- near front leg + paw ----
    val liftN = 10f * (1f - dn).pow(1.5f)
    val pnx = nearX + 2f - 4f * (1f - dn); val pny = 140f + sink * dn - liftN
    val nsx = tiltX(98f, 108f); val nsy = tiltY(98f, 108f)
    p.reset(); p.moveTo(nsx, nsy); p.quadraticTo((nsx + pnx) / 2f - 5f, (nsy + pny) / 2f, pnx, pny - 4f)
    limbPath(p, 12f, FUR, FUR_DARK)
    drawOval(FUR, Offset(nsx - 9f, nsy - 10f), Size(18f, 16f))                                  // shoulder, blended into the chest
    val sp = 1f + 2.5f * dn                                                                     // toes spread as it presses
    inkOval(pnx - 2f - 9f - sp, pny - 5.5f, 18f + 2f * sp, 11f, CREAM, w = Graff.kl * 0.8f)
    for (t in -1..1) drawLine(OUT, Offset(pnx - 2f + t * (3f + sp * 0.5f), pny + 1f), Offset(pnx - 2f + t * (3.5f + sp * 0.7f), pny + 5f), 1f, cap = StrokeCap.Round)
    if (dn < 0.3f) drawCircle(BEAN, 2.4f, Offset(pnx - 2f, pny + 1f))                         // a bean shows on the lift
    if (cs.glide > 0.02f && dn in 0.05f..0.95f) for (k in 0 until 3) {                          // GLIDE: she slides into it
        val len = 6f + 14f * cs.glide
        drawLine(CREAM.copy(alpha = 0.5f * cs.glide), Offset(pnx + 10f, pny - 4f + k * 3f), Offset(pnx + 10f + len - k * 3f, pny - 4f + k * 3f), 1f, cap = StrokeCap.Round)
    }

    // ---- head, three-quarter, on her shoulders ----
    val hx = tiltX(84f, 76f); val hy = tiltY(84f, 76f) + rock * 0.5f + bob + cs.headLfo + (cs.head0 - lean) * 0.9f
    val tw = now - cs.twitchAt
    val twitch = if (tw in 0f..0.3f) sin(tw * 40f) * 9f * (1f - tw / 0.3f) else 0f
    rotate(twitch * 0.6f + cs.ear0, Offset(hx + 18f, hy - 20f)) {                              // far ear (gold hoop)
        p.reset(); p.moveTo(hx + 10f, hy - 22f); p.lineTo(hx + 26f, hy - 44f); p.lineTo(hx + 28f, hy - 14f); p.close()
        inkCel(p, FUR, FUR_DARK, 2.5f)
        p.reset(); p.moveTo(hx + 14f, hy - 22f); p.lineTo(hx + 24f, hy - 37f); p.lineTo(hx + 24f, hy - 18f); p.close()
        drawPath(p, BEAN)
        drawCircle(Line79.INK, 3.2f, Offset(hx + 27f, hy - 16f), style = Stroke(1.6f + Graff.kl))
        drawCircle(HOOP, 3.2f, Offset(hx + 27f, hy - 16f), style = Stroke(1.6f))
        sparkle(hx + 25f, hy - 18f, 2.4f)
    }
    rotate(-twitch - cs.ear0, Offset(hx - 14f, hy - 18f)) {                                     // near ear
        p.reset(); p.moveTo(hx - 26f, hy - 12f); p.lineTo(hx - 24f, hy - 44f); p.lineTo(hx - 4f, hy - 24f); p.close()
        inkCel(p, FUR, FUR_DARK, 2.5f)
        p.reset(); p.moveTo(hx - 22f, hy - 17f); p.lineTo(hx - 21f, hy - 37f); p.lineTo(hx - 9f, hy - 24f); p.close()
        drawPath(p, BEAN)
    }
    p.reset()
    p.moveTo(hx - 30f, hy)
    p.cubicTo(hx - 32f, hy - 26f, hx + 28f, hy - 30f, hx + 30f, hy - 2f)
    p.cubicTo(hx + 32f, hy + 16f, hx + 16f, hy + 24f, hx, hy + 24f)
    p.cubicTo(hx - 18f, hy + 24f, hx - 30f, hy + 14f, hx - 30f, hy)
    p.close()
    inkCel(p, FUR, FUR_DARK, 5f)
    shine(hx - 20f, hy - 18f, 7f, -30f, Graff.kl)
    for (sd in floatArrayOf(-1f, 1f)) {                                                         // cheek tufts
        p.reset(); p.moveTo(hx + sd * 29f, hy + 5f); p.lineTo(hx + sd * 35f, hy + 10f); p.lineTo(hx + sd * 28f, hy + 11f); p.close()
        drawPath(p, FUR)
    }
    p.reset(); p.moveTo(hx - 10f, hy - 20f); p.lineTo(hx - 6f, hy - 14f); p.lineTo(hx - 2f, hy - 21f); p.lineTo(hx + 2f, hy - 14f); p.lineTo(hx + 6f, hy - 20f)
    drawPath(p, FUR_DARK, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))   // the tabby M
    // muzzle, nose, a pleased little mouth
    val mx = hx - 4f
    drawOval(Line79.INK, Offset(mx - 15f - Graff.kl * 0.8f, hy + 4f - Graff.kl * 0.8f), Size(18f + Graff.kl * 1.6f, 14f + Graff.kl * 1.6f))
    drawOval(Line79.INK, Offset(mx - 3f - Graff.kl * 0.8f, hy + 4f - Graff.kl * 0.8f), Size(18f + Graff.kl * 1.6f, 14f + Graff.kl * 1.6f))
    drawOval(CREAM, Offset(mx - 15f, hy + 4f), Size(18f, 14f))
    drawOval(CREAM, Offset(mx - 3f, hy + 4f), Size(18f, 14f))
    p.reset(); p.moveTo(mx - 4f, hy + 4f); p.lineTo(mx + 4f, hy + 4f); p.lineTo(mx, hy + 8f); p.close()
    drawPath(p, BEAN)
    p.reset(); p.moveTo(mx, hy + 8f); p.lineTo(mx, hy + 11f)
    p.moveTo(mx, hy + 11f); p.quadraticTo(mx - 3f, hy + 14f, mx - 6f, hy + 11f)
    p.moveTo(mx, hy + 11f); p.quadraticTo(mx + 4f, hy + 15f, mx + 8f, hy + 10f)
    drawPath(p, OUT, style = Stroke(Graff.kl * 0.55f, cap = StrokeCap.Round))
    // whiskers: short and soft; RESONANCE makes them buzz and glow
    val buzz = cs.whisker * cs.whisker * 2.2f
    val wc = lerp2(CREAM, cs.accent, cs.whisker).copy(alpha = 0.8f)
    for (k in -1..1) {
        val j = sin(now * 95f + k * 2f) * buzz
        drawLine(wc, Offset(mx - 12f, hy + 10f + k * 2f), Offset(mx - 30f, hy + 7f + k * 5f + j), 0.8f + cs.whisker * 0.5f)
        drawLine(wc, Offset(mx + 12f, hy + 10f + k * 2f), Offset(mx + 28f, hy + 7f + k * 5f - j), 0.8f + cs.whisker * 0.5f)
    }
    // brows pop over the shades on loud notes
    val brow = 2.5f * amp + (if (tw in 0f..0.25f) 1.5f else 0f)
    if (brow > 0.3f) {
        drawLine(FUR_DARK, Offset(hx - 22f, hy - 12f - brow), Offset(hx - 9f, hy - 13f - brow), 2f, cap = StrokeCap.Round)
        drawLine(FUR_DARK, Offset(hx + 7f, hy - 13f - brow), Offset(hx + 19f, hy - 12f - brow), 2f, cap = StrokeCap.Round)
    }
    // the peek: her eyes (green, slit pupils) show over the lowered shades, with one slow cat-blink
    val pk = cs.peek * 6f
    if (pk > 0.3f) {
        val lid = cs.peekBlink
        for ((ex, w) in listOf(hx - 14f to 9f, hx + 13f to 8f)) {
            val eh = 7f * (1f - 0.92f * lid)
            drawOval(Color(0xFFB9E07A), Offset(ex - w / 2f, hy - 7f - eh / 2f), Size(w, eh))
            if (eh > 2f) drawLine(SHADES, Offset(ex, hy - 7f - eh / 2f + 0.8f), Offset(ex, hy - 7f + eh / 2f - 0.8f), 1.6f, cap = StrokeCap.Round)
            drawLine(OUT, Offset(ex - w / 2f, hy - 7f - eh / 2f), Offset(ex + w / 2f, hy - 7f - eh / 2f), 1.2f, cap = StrokeCap.Round)
        }
    }
    withTransform({ translate(0f, pk) }) {
    // Y2K sport shades: two oval lenses in a thick glossy white frame, chrome mirror lenses, a glint sweeping across
    y2kShades(cs, hx, hy - 4f, now)
    }   // (end of the shades peek)
}

/** Her Y2K shades: thick white oval frames (a hard shadow inside the rim), chrome lenses (sky, steel, dark), a streak + a sweeping glint. */
private fun DrawScope.y2kShades(cs: CatState, hx: Float, ey: Float, now: Float) {
    val kl = Graff.kl
    // bridge + the arm back to the far ear, under the frames
    drawLine(Line79.INK, Offset(hx - 6f, ey - 2f), Offset(hx + 6f, ey - 2f), 2.6f + kl, cap = StrokeCap.Round)
    drawLine(FRAME, Offset(hx - 6f, ey - 2f), Offset(hx + 6f, ey - 2f), 2.6f, cap = StrokeCap.Round)
    drawLine(Line79.INK, Offset(hx + 26f, ey - 3f), Offset(hx + 32f, ey - 7f), 2.8f + kl, cap = StrokeCap.Round)
    drawLine(FRAME, Offset(hx + 26f, ey - 3f), Offset(hx + 32f, ey - 7f), 2.8f, cap = StrokeCap.Round)
    val glint = (now * 0.35f) % 1f
    for (k in 0..1) {
        val cx = if (k == 0) hx - 15f else hx + 15f
        val w = if (k == 0) 20f else 18f; val h = if (k == 0) 11f else 10.5f
        rotate(if (k == 0) -6.9f else 6.9f, Offset(cx, ey)) {
            drawOval(Line79.INK, Offset(cx - (w + 7f) / 2f, ey - (h + 6f) / 2f), Size(w + 7f, h + 6f), style = Stroke(kl * 2f))
            celOval(cx - (w + 7f) / 2f, ey - (h + 6f) / 2f, w + 7f, h + 6f, FRAME, FRAME_DK, 2.2f)
            drawOval(Brush.verticalGradient(0f to Color(0xFFCFE8F7), 0.35f to Color(0xFFA9B3BE), 0.62f to Color(0xFF4E5560), 1f to Color(0xFF2A2E36),
                startY = ey - h / 2f, endY = ey + h / 2f), Offset(cx - w / 2f, ey - h / 2f), Size(w, h))
            // the streaks and the glint, kept inside the lens
            lensStreak(cx, ey, w, h, cx - w * 0.3f, ey + h * 0.5f, cx + w * 0.05f, ey - h * 0.6f, 2.4f, 1f)
            lensStreak(cx, ey, w, h, cx - w * 0.05f, ey + h * 0.6f, cx + w * 0.22f, ey - h * 0.5f, 1.2f, 0.6f)
            if (glint < 0.2f) {
                val gx = hx - 26f + glint / 0.2f * 52f
                if (kotlin.math.abs(gx - cx) < w * 0.4f) lensStreak(cx, ey, w, h, gx, ey + h * 0.6f, gx + 5f, ey - h * 0.6f, 2f, 0.8f)
            }
            drawOval(Line79.INK, Offset(cx - w / 2f, ey - h / 2f), Size(w, h), style = Stroke(kl * 0.8f))
            shine(cx - w * 0.42f, ey - h * 0.62f, 3.5f, -15f, 1.6f)
        }
    }
    sparkle(hx - 21f, ey - 6f, 3.5f)
}
/** A white streak [sw] wide from (x0, y0) to (x1, y1), its ends pulled in so it stays inside the lens (centre cx, ey; w × h). */
private fun DrawScope.lensStreak(cx: Float, ey: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float, sw: Float, alpha: Float) {
    fun inside(x: Float, y: Float): Offset {
        val ux = (x - cx) / (w / 2f); val uy = (y - ey) / (h / 2f)
        val e = ux * ux + uy * uy
        val k = if (e > 0.62f) kotlin.math.sqrt(0.62f / e) else 1f
        return Offset(cx + (x - cx) * k, ey + (y - ey) * k)
    }
    drawLine(Color.White.copy(alpha = alpha), inside(x0, y0), inside(x1, y1), sw, cap = StrokeCap.Round)
}
private val FRAME = Color(0xFFF7F5F0)
private val FRAME_DK = Color(0xFFD9D6D0)

/**
 * Her room by the elevated line, filling the panel ([l]…[r] × [t]…[b] in stage units): faded striped
 * wallpaper with a marker tag (she lives with writers), a window onto the El where a silver train with
 * pieces end to end rolls by now and then (faster and brighter while the synth sounds), and floorboards.
 */
private fun DrawScope.room(cs: CatState, l: Float, r: Float, t: Float, b: Float, now: Float, amp: Float, accent: Color,
                           tm: androidx.compose.ui.text.TextMeasurer, tagStyle: androidx.compose.ui.text.TextStyle, trainStyle: androidx.compose.ui.text.TextStyle) {
    val kl = Graff.kl * 0.6f
    drawRect(WALL, Offset(l, t), Size(r - l, 150f - t))
    var x = l - ((l % 26f) + 26f) % 26f
    while (x < r) { drawLine(WALL_STRIPE, Offset(x, t), Offset(x, 150f), 3f); x += 26f }
    // the window onto the El
    val wx = 134f; val wy = 14f; val ww = 96f; val wh = 78f
    inkRRect(wx - 6f, wy - 6f, ww + 12f, wh + 12f, 3f, SASH, w = kl)
    clipRect(wx, wy, wx + ww, wy + wh) {                                // a rectangle clip is a scissor: cheap
        drawRect(Line79.NIGHT, Offset(wx, wy), Size(ww, wh))
        for (i in 0 until 6) {                                       // the block across the street, lit windows
            val bx = wx + i * 17f - 4f; val bh = 20f + (i * 37 % 26)
            inkRect(bx, wy + wh - bh - 18f, 15f, bh + 20f, Line79.NIGHT_LT, kl * 0.7f)
            for (w in 0 until 4) if ((i * 7 + w * 3) % 5 < 3)
                drawRect(Line79.SODIUM.copy(alpha = 0.85f), Offset(bx + 3f + (w % 2) * 6f, wy + wh - bh - 13f + (w / 2) * 8f), Size(3f, 4f))
        }
        // the El's steel
        inkRect(wx - 2f, wy + 46f, ww + 4f, 5f, EL_STEEL, kl)
        var px = wx + 4f
        while (px < wx + ww) { inkRect(px, wy + 51f, 4f, 30f, EL_STEEL, kl); px += 22f }
        // the train: every ~12 s (sooner while she's playing), a silver car with pieces on its side
        val ph = (now * (0.06f + 0.05f * amp) + 0.3f) % 1f
        if (ph < 0.45f) {
            val tx = wx + ww - ph / 0.45f * 300f
            inkRRect(tx, wy + 16f, 190f, 30f, 4f, Line79.SILVER, w = kl)
            for (k in 0 until 6) inkRRect(tx + 8f + k * 30f, wy + 19f, 16f, 9f, 2f, Line79.NIGHT_DK, w = kl * 0.7f)
            val j = tm.measure("JAM", trainStyle)
            cachedBlockbuster(cs.trainJam, j, tx + 14f, wy + 44f - j.size.height * 0.9f, Graff.scale, Line79.CASCADE_LT, Line79.CASCADE, null, Line79.INK, 1.5f, j.size.height * 0.06f)
            val n = tm.measure("NITE", trainStyle)
            cachedBlockbuster(cs.trainNite, n, tx + 76f, wy + 44f - n.size.height * 0.9f, Graff.scale, Line79.SILVER_LT, Line79.SILVER, Line79.RED, Line79.INK, 1.4f, n.size.height * 0.06f)
        }
    }
    inkRect(wx + ww / 2f - 1.5f, wy, 3f, wh, SASH, kl * 0.6f)
    inkRect(wx - 8f, wy + wh + 4f, ww + 16f, 5f, SASH, kl)
    // the tag on the wall: her name, in marker
    val tg = tm.measure("BISCUIT", tagStyle)
    val th = tg.size.height.toFloat()
    cachedArt(cs.tag, (tg.size.width * 31L + th.toLong()), tg.size.width + 4f, th * 1.5f + 4f, Graff.scale, 6f, 24f) {
        drawText(tg, Line79.CASCADE_LT, Offset(2f, 2f), drawStyle = Stroke(th * 0.08f, join = StrokeJoin.Round))
        drip(2f + tg.size.width * 0.18f, 2f + th * 0.85f, th * 0.45f, th * 0.06f, Line79.CASCADE_LT)
        drip(2f + tg.size.width * 0.7f, 2f + th * 0.85f, th * 0.3f, th * 0.05f, Line79.CASCADE_LT)
    }
    // the floor
    drawRect(FLOOR_WOOD, Offset(l, 150f), Size(r - l, b - 150f))
    drawLine(Line79.INK, Offset(l, 150f), Offset(r, 150f), kl)
    var fy = 158f
    while (fy < b) { drawLine(FLOOR_SEAM, Offset(l, fy), Offset(r, fy), 1.4f); fy += 9f }
}
private val WALL = Color(0xFF3B3550)
private val WALL_STRIPE = Color(0xFF342F48)
private val SASH = Color(0xFFE7DECB)
private val EL_STEEL = Color(0xFF4E5A55)
private val FLOOR_WOOD = Color(0xFF6A4A34)
private val FLOOR_SEAM = Color(0xFF563B29)

/** Her beat bob (shared by the head and the tail's lag). */
private fun bob(beat: Float, playing: Boolean) = if (playing) abs(sin(PI.toFloat() * beat)) else 0f

// ----------------------------------------------------------------------------- what she kneads

private fun DrawScope.pillow(cs: CatState, dl: Float, dr: Float, pl: Float, pr: Float, now: Float, amp: Float, accent: Color) {
    val base = lerp2(Color(0xFFC3B1F0), accent, 0.10f)
    val dark = lerp2(base, Color.Black, 0.3f)
    val puff = 1.5f * sin(now * 1.2f) + 2f * amp
    // the pillow: puffy body, corner tufts, a seam
    inkRRect(28f, 134f - puff, 184f, 52f + puff, 24f, base, dark, 4f)
    for ((cx, cy) in CORNERS) {
        val p = cs.path2
        p.reset(); p.moveTo(cx, cy); p.lineTo(cx + (if (cx < 120f) -8f else 8f), cy + (if (cy < 160f) -6f else 6f)); p.lineTo(cx + (if (cx < 120f) 4f else -4f), cy + (if (cy < 160f) -2f else 2f)); p.close()
        ink(p, base, Graff.kl * 0.7f)
    }
    drawLine(dark.copy(alpha = 0.5f), Offset(40f, 162f), Offset(200f, 162f), 1.4f)
    shine(46f, 142f - puff, 16f, -8f, Graff.kl)
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
    inkRRect(22f, 150f, 196f, 40f, 5f, Color(0xFF6B3F22), Color(0xFF4E2D17), 3f)
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
    keyline(p, Graff.kl * 0.8f)
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
    inkRRect(36f, 146f, 168f, 44f, 4f, cab, Color(0xFF121116), 3f)
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
    inkRRect(24f, 146f, 192f, 42f, 5f, Color(0xFF2B2A31), Color(0xFF1A191E), 3f)
    drawRect(Color(0xFF3A3942), Offset(24f, 126f), Size(192f, 20f))
    drawRoundRect(Color(0xFFC9CED6), Offset(196f, 156f), Size(10f, 24f), CornerRadius(2f))                   // pitch fader
    drawRect(Color(0xFF111116), Offset(198f, 165f), Size(6f, 4f))
    drawCircle(accent.copy(alpha = 0.9f), 4f, Offset(42f, 170f))                                                  // start/stop
    // platter + record; the label spins at 33 and scratches back on each press
    inkOval(38f, 122f, 140f, 28f, Line79.SILVER, Line79.SILVER_DK, 3f)
    drawOval(Color(0xFF0D0D10), Offset(42f, 123f), Size(132f, 25f))
    for (k in 1..3) drawOval(Color.White.copy(alpha = 0.06f), Offset(42f + k * 9f, 123f + k * 1.7f), Size(132f - k * 18f, 25f - k * 3.4f), style = Stroke(0.8f))
    val scratch = 0.9f * sin(2f * PI.toFloat() * beat) * (0.5f + 0.5f * amp)
    val ang = now * 3.46f - scratch
    drawOval(accent, Offset(96f, 131f), Size(24f, 9f))
    drawLine(Color.Black.copy(alpha = 0.6f), Offset(108f, 135.5f), Offset(108f + cos(ang) * 11f, 135.5f + sin(ang) * 4f), 1.6f)
    drawCircle(Color(0xFFC9CED6), 1.4f, Offset(108f, 135.5f))
    // tonearm from its pivot to the record
    inkCircle(196f, 132f, 6f, Line79.SILVER, Line79.SILVER_DK, 1.6f, Graff.kl * 0.7f); sparkle(194f, 130f, 3f)
    drawLine(Color(0xFFC9CED6), Offset(196f, 132f), Offset(170f, 138f), 2.4f, cap = StrokeCap.Round)
    drawLine(Color(0xFFC9CED6), Offset(170f, 138f), Offset(158f, 136f), 2.4f, cap = StrokeCap.Round)
    drawRoundRect(Color(0xFF1A1A20), Offset(152f, 133f), Size(8f, 5f), CornerRadius(1f))
}

private fun DrawScope.keyboard(tel: SynthTelemetry, dl: Float, dr: Float, pl: Float, pr: Float, accent: Color) {
    inkRRect(22f, 124f, 196f, 62f, 5f, Color(0xFF26252C), Color(0xFF17161B), 3f)
    drawRect(Color(0xFF111116), Offset(28f, 150f), Size(184f, 5f))
    for (k in 0 until 4) inkCircle(40f + k * 12f, 168f, 3.4f, Line79.SILVER, w = Graff.kl * 0.5f)   // knobs
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
    drawOval(Line79.INK, Offset(26f - Graff.kl, 130f - Graff.kl), Size(84f + Graff.kl * 2f, 60f + Graff.kl * 2f))
    drawOval(Line79.INK, Offset(88f - Graff.kl, 136f - Graff.kl), Size(70f + Graff.kl * 2f, 48f + Graff.kl * 2f))
    drawOval(woodDark, Offset(26f, 130f), Size(84f, 60f))
    drawOval(woodDark, Offset(88f, 136f), Size(70f, 48f))
    drawOval(wood, Offset(28f, 132f), Size(80f, 56f))
    drawOval(wood, Offset(90f, 138f), Size(66f, 44f))
    shine(40f, 142f, 12f, -20f, Graff.kl)
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
    keyline(p, Graff.kl)
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

/** The oscillator's shape morph, as the tail draws it: 0 sine · ⅓ triangle · ⅔ saw · 1 square, blended between. */
private fun wave(shape: Float, ph: Float): Float {
    val f = ph - kotlin.math.floor(ph)
    val sine = sin(2f * PI.toFloat() * f)
    val tri = triangle(f)
    val saw = 2f * f - 1f
    val sq = if (f < 0.5f) 1f else -1f
    val s = shape.coerceIn(0f, 1f) * 3f
    return when {
        s < 1f -> sine + (tri - sine) * s
        s < 2f -> tri + (saw - tri) * (s - 1f)
        else -> saw + (sq - saw) * (s - 2f)
    }
}
private fun triangle(f: Float) = if (f < 0.25f) 4f * f else if (f < 0.75f) 2f - 4f * f else 4f * f - 4f
