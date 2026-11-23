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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
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
 * THE CAT: a ginger tabby in shades (gold hoop in one ear), side-on and crouched, making biscuits
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
            withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
                // a soft spotlight in the family colour, and the floor she's on
                drawCircle(accent.copy(alpha = (0.06f + 0.10f * amp) * (0.4f + 0.6f * bright)), 110f, Offset(120f, 110f))
                drawCircle(accent.copy(alpha = 0.08f * (0.4f + 0.6f * bright)), 70f, Offset(120f, 100f))
                drawOval(Color.Black.copy(alpha = 0.35f), Offset(20f, 182f), Size(200f, 16f))

                // press amount per paw: 1 = fully down, 0 = lifted; near paw on the beat, far on the off-beat.
                // ATTACK sets how slowly she pushes down, RELEASE how slowly she lifts.
                val atk = (log10(1f + patch.ampEnv.attackMs) / 3.4f).coerceIn(0f, 1f)
                val rel = (log10(1f + patch.ampEnv.releaseMs) / 3.6f).coerceIn(0f, 1f)
                val dl = press(beat, atk, rel); val dr = press((beat + 0.5f) % 1f, atk, rel)
                val depth = 0.6f + 0.6f * amp
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
                    withTransform({ translate(drift, hop * 0.8f); scale(catSize, catSize, Offset(120f, 142f)) }) {
                        cat(cs, now - lag, (beat + 1f - lag) % 1f, playing, amp, lfo, press((beat + 1f - lag) % 1f, atk, rel),
                            press((beat + 1.5f - lag) % 1f, atk, rel), sink * depth, 82f, 108f)
                    }
                    drawIntoCanvas { it.restore() }
                }
                withTransform({ translate(0f, hop); scale(catSize, catSize, Offset(120f, 142f)) }) {
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
                        sparkle(24f + i * 46f, 16f + (i * 37 % 4) * 10f, 2.5f + 2f * tw, Color.White.copy(alpha = k * tw * 0.8f))
                    }
                }
            }
        }
        // light themes: the family colour pulled toward the text colour, so it reads on cream
        val tagColor = if (Tb.palette.light) androidx.compose.ui.graphics.lerp(accent, Tb.Text, 0.45f) else accent.copy(alpha = 0.85f)
        Text("${family.label} · ${kneadObjectName(family)}", color = tagColor, fontSize = 10.sp,
            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 2.dp))
        if (hint.isNotEmpty()) Text(hint, color = Tb.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
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

private fun DrawScope.sparkle(x: Float, y: Float, r: Float, c: Color) {
    drawLine(c, Offset(x - r, y), Offset(x + r, y), 1.2f, cap = StrokeCap.Round)
    drawLine(c, Offset(x, y - r), Offset(x, y + r), 1.2f, cap = StrokeCap.Round)
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
        drawOval(Color(0xFFEDE3C8), Offset(if (sd < 0) x - w - 1f else x + 1f, y - 6f), Size(w, 7f))
        drawOval(Color(0xFFD9CCA8), Offset(if (sd < 0) x - w * 0.8f - 1f else x + 1f, y), Size(w * 0.8f, 5f))
    }
    drawOval(Color(0xFF6B5A44), Offset(x - 1.4f, y - 5f), Size(2.8f, 10f))
    drawLine(Color(0xFF6B5A44), Offset(x, y - 5f), Offset(x - 3f, y - 9f), 0.7f)
    drawLine(Color(0xFF6B5A44), Offset(x, y - 5f), Offset(x + 3f, y - 9f), 0.7f)
}

/** A kitten: round, big-eyed, no shades yet; it bobs on its own little knead. [pop] scales it in. */
private fun DrawScope.kitten(cs: CatState, x: Float, y: Float, pop: Float, now: Float, beat: Float, fur: Color) {
    val dark = lerp2(fur, Color.Black, 0.3f)
    val b = 1.5f * abs(sin(PI.toFloat() * (beat % 1f)))
    withTransform({ scale(pop, pop, Offset(x, y + 14f)) }) {
        drawOval(dark, Offset(x - 1f, y + 2f), Size(20f, 13f))                                      // body
        drawOval(fur, Offset(x, y + 3f), Size(18f, 11f))
        drawLine(fur, Offset(x + 17f, y + 8f), Offset(x + 22f, y - 1f + sin(now * 3f + x) * 2f), 2.6f, cap = StrokeCap.Round)   // tail
        val p = cs.path2
        for (sd in floatArrayOf(-1f, 1f)) {                                                        // ears
            p.reset(); p.moveTo(x + 4f + sd * 4f, y - 6f + b); p.lineTo(x + 4f + sd * 7f, y - 14f + b); p.lineTo(x + 4f + sd * 1f, y - 9f + b); p.close()
            drawPath(p, fur)
        }
        drawCircle(dark, 7.5f, Offset(x + 4f, y - 3f + b))                                          // head
        drawCircle(fur, 6.8f, Offset(x + 4f, y - 3f + b))
        for (sd in floatArrayOf(-1f, 1f)) {                                                        // big eyes
            drawCircle(Color(0xFF1A1A22), 1.9f, Offset(x + 4f + sd * 2.8f, y - 4f + b))
            drawCircle(Color.White, 0.7f, Offset(x + 4.5f + sd * 2.8f, y - 4.6f + b))
        }
        drawCircle(BEAN, 0.9f, Offset(x + 4f, y - 1.4f + b))
        drawOval(CREAM, Offset(x - 1f, y + 11f - b * 0.6f), Size(6f, 3.5f))                          // kneading paw
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
    val wag = lfo * 10f + sin(now * 1.3f) * 4f
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
    drawPath(p, OUT, style = Stroke(11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(p, FUR, style = Stroke(8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(FUR_DARK, 4.2f, Offset(tipX, tipY))

    // ---- far front leg, in shadow behind her chest ----
    val liftF = 10f * (1f - df).pow(1.5f)
    val pfx = farX + 2f - 4f * (1f - df); val pfy = 140f + sink * df - liftF
    p.reset(); p.moveTo(112f, 110f + rock); p.quadraticTo((112f + pfx) / 2f - 4f, (110f + pfy) / 2f, pfx, pfy - 4f)
    drawPath(p, FUR_SHADOW, style = Stroke(12f, cap = StrokeCap.Round))
    drawOval(CREAM_SHADOW, Offset(pfx - 2f - 8f - 2f * df, pfy - 5f), Size(16f + 4f * df, 10f))

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
            drawPath(p, col)
        }
    }
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
    drawPath(body, FUR)
    p.reset(); p.moveTo(150f, 142f); p.cubicTo(146f, 120f, 160f, 100f, 184f, 100f)            // haunch
    drawPath(p, FUR_DARK, style = Stroke(2f, cap = StrokeCap.Round))
    drawOval(CREAM, Offset(150f, 136.5f), Size(20f, 9f))                                        // hind paw
    drawOval(OUT, Offset(150f, 136.5f), Size(20f, 9f), style = Stroke(1f))
    clipPath(body) {                                                                            // tabby stripes, inside her coat only
        for (k in 0 until 4) {
            val x = 130f + k * 14.5f; val y = 78f
            p.reset(); p.moveTo(x, y); p.quadraticTo(x + 3f, y + 10f, x, y + 18f)
            drawPath(p, FUR_DARK, style = Stroke(3f, cap = StrokeCap.Round))
        }
    }
    drawPath(body, OUT, style = Stroke(1.6f, join = StrokeJoin.Round))
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
    // purr: little lines off her chest while anything sounds
    if (amp > 0.02f || playing) {
        val j = sin(now * 60f) * 1f
        for (k in 0 until 2) drawLine(FUR_LIGHT.copy(alpha = (0.55f - k * 0.2f) * cs.purr), Offset(78f - k * 6f + j, 112f + k * 6f), Offset(74f - k * 6f + j, 108f + k * 6f), 1.6f, cap = StrokeCap.Round)
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
    if (cs.glide > 0.02f && dn in 0.05f..0.95f) for (k in 0 until 3) {                          // GLIDE: she slides into it
        val len = 6f + 14f * cs.glide
        drawLine(CREAM.copy(alpha = 0.5f * cs.glide), Offset(pnx + 10f, pny - 4f + k * 3f), Offset(pnx + 10f + len - k * 3f, pny - 4f + k * 3f), 1f, cap = StrokeCap.Round)
    }

    // ---- head, three-quarter, on her shoulders ----
    val hx = 84f; val hy = 76f + rock * 0.5f + bob + cs.headLfo
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
