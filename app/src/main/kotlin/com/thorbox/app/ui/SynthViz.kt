// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.SynthTelemetry
import com.thorbox.app.synth.SynthParams
import com.thorbox.model.LfoTarget
import com.thorbox.model.SynthPatch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Page animations. Design rules (borrowed in spirit from Teenage Engineering's instruments):
 *  - one bold idea per page, drawn in thick flat vector strokes on black;
 *  - each param is drawn in its encoder's colour, so turning the blue knob visibly moves the blue thing;
 *  - everything reacts to the real engine (telemetry + scope), not a canned loop;
 *  - nothing allocates per frame: Paths are remembered and reset, and telemetry arrays are preallocated.
 * Every Canvas reads `tel.frame` in its draw lambda, so a new frame re-draws it without recomposing.
 */

private fun DrawScope.dotGrid(step: Float) {
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(Tb.Line, 1.6f, Offset(x, y)); x += step }
        y += step
    }
}

// ============================== OSC ==============================

@Composable
fun OscViz(patch: SynthPatch, tel: SynthTelemetry, modifier: Modifier) {
    val p1 = remember { Path() }; val p2 = remember { Path() }; val sc = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue                                   // subscribe to frames (draw-only)
        dotGrid(24.dp.toPx())
        val w = size.width; val h = size.height
        val mid = h * 0.42f
        val pulse = 0.55f + 0.45f * tel.ampLevel              // breathes with the newest note
        val amp = h * 0.26f * pulse
        val mix = patch.oscMix

        // Osc 2: drifting phase visualises detune as slow beating against osc 1.
        val drift = (tel.timeSec * patch.osc2.detuneCents / 100f * 0.6f)
        fun wave(path: Path, shape: Float, phase: Float) {
            path.reset()
            val n = 220
            for (i in 0..n) {
                val x = w * i / n
                var t = (2f * i / n + phase) % 1f; if (t < 0) t += 1f
                val y = mid - SynthParams.shapeSample(shape, t) * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        wave(p2, patch.osc2.shape, drift)
        wave(p1, patch.osc1.shape, 0f)
        val s = 3.dp.toPx()
        drawPath(p2, Tb.EncGreen.copy(alpha = 0.35f + 0.65f * mix), style = Stroke(s + 6.dp.toPx() * mix, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(p1, Tb.EncBlue.copy(alpha = 0.35f + 0.65f * (1 - mix)), style = Stroke(s + 6.dp.toPx() * (1 - mix), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Live output scope (trigger on a rising zero crossing so it stands still).
        val count = tel.scopeCount
        val win = 480
        if (count > win + 64) {
            var start = count - win - 1
            for (i in count - win - 1 downTo max(1, count - win - 400)) {
                if (tel.scope[i - 1] < 0f && tel.scope[i] >= 0f) { start = i; break }
            }
            var peak = 0.05f
            for (i in start until start + win) peak = max(peak, abs(tel.scope[i]))
            val base = h * 0.86f; val sAmp = h * 0.1f / peak
            sc.reset()
            for (i in 0 until win) {
                val x = w * i / (win - 1)
                val y = base - tel.scope[start + i] * sAmp
                if (i == 0) sc.moveTo(x, y) else sc.lineTo(x, y)
            }
            drawPath(sc, Tb.EncWhite.copy(alpha = 0.9f), style = Stroke(1.5f.dp.toPx()))
        }
        drawLine(Tb.Line, Offset(0f, h * 0.86f), Offset(w, h * 0.86f), 1f)
    }
}

// ============================== FILTER ==============================

private fun svfMagDb(f: Float, fc: Float, res: Float): Float {
    val k = 2f - 1.96f * res.coerceIn(0f, 1f)                  // same damping as Synth.h
    val x = f / fc
    val d = (1 - x * x).pow(2) + (k * x).pow(2)
    return (-10f * log10(d.coerceAtLeast(1e-9f)))
}

@Composable
fun FilterViz(patch: SynthPatch, tel: SynthTelemetry, modifier: Modifier) {
    val live = remember { Path() }; val liveFill = remember { Path() }; val base = remember { Path() }
    val tm = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = Tb.Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    Canvas(modifier) {
        tel.frame.longValue
        val w = size.width; val h = size.height
        val top = h * 0.08f; val bottom = h * 0.9f
        fun xOf(f: Float) = w * (ln(f / 20f) / ln(1000f))
        fun yOf(db: Float) = top + (18f - db.coerceIn(-48f, 18f)) / 66f * (bottom - top)

        // Grid: decades and 0 dB
        for (f in floatArrayOf(100f, 1000f, 10000f)) {
            val x = xOf(f)
            drawLine(Tb.Line, Offset(x, top), Offset(x, bottom), 1f)
            drawText(tm, if (f >= 1000f) "${(f / 1000).toInt()}k" else "${f.toInt()}", Offset(x + 4, bottom - 14.dp.toPx()), labelStyle)
        }
        drawLine(Tb.Line, Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1f)

        val baseFc = patch.cutoffHz
        val liveFc = if (tel.activeVoices > 0) tel.cutoffHz else baseFc
        val n = 160
        live.reset(); liveFill.reset(); base.reset()
        liveFill.moveTo(0f, bottom)
        for (i in 0..n) {
            val f = 20f * 1000f.pow(i.toFloat() / n)
            val x = w * i / n
            val yl = yOf(svfMagDb(f, liveFc, patch.resonance))
            val yb = yOf(svfMagDb(f, baseFc, patch.resonance))
            if (i == 0) { live.moveTo(x, yl); base.moveTo(x, yb) } else { live.lineTo(x, yl); base.lineTo(x, yb) }
            liveFill.lineTo(x, yl)
        }
        liveFill.lineTo(w, bottom); liveFill.close()

        // Harmonics of the newest note, scaled by the live response: you see the filter eat them.
        val note = tel.newestNote
        if (note >= 0) {
            val f0 = 440f * 2f.pow((note - 69) / 12f)
            var k = 1
            while (k <= 96) {
                val f = f0 * k
                if (f > 20000f) break
                val db = svfMagDb(f, liveFc, patch.resonance) + 20f * log10(1f / k)
                val x = xOf(f)
                val y = yOf(db)
                drawLine(Tb.EncOrange.copy(alpha = 0.85f), Offset(x, bottom), Offset(x, max(y, top)), 2.dp.toPx(), cap = StrokeCap.Round)
                k++
            }
        }

        // Env-amount sweep bracket (white): where the envelope will push the cutoff.
        val sweepFc = (baseFc * 2f.pow(patch.filterEnvAmount * 5f)).coerceIn(20f, 20000f)
        val yb0 = top + 6.dp.toPx()
        drawLine(Tb.EncWhite, Offset(xOf(baseFc), yb0), Offset(xOf(sweepFc), yb0), 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Tb.EncWhite, 4.dp.toPx(), Offset(xOf(sweepFc), yb0))

        drawPath(liveFill, Tb.EncBlue.copy(alpha = 0.16f))
        drawPath(base, Tb.Dim, style = Stroke(1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        drawPath(live, Tb.EncBlue, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Resonance peak marker (green), size grows with resonance
        val px = xOf(liveFc.coerceIn(20f, 20000f))
        val py = yOf(svfMagDb(liveFc, liveFc, patch.resonance))
        drawCircle(Tb.EncGreen, (5f + 9f * patch.resonance).dp.toPx(), Offset(px, py))
        drawCircle(Tb.Black, (2f + 3f * patch.resonance).dp.toPx(), Offset(px, py))
    }
}

// ============================== ENVELOPES ==============================

@Composable
fun EnvViz(patch: SynthPatch, tel: SynthTelemetry, amp: Boolean, modifier: Modifier) {
    val segA = remember { Path() }; val segD = remember { Path() }; val segR = remember { Path() }; val fill = remember { Path() }
    val tm = rememberTextMeasurer()
    val styles = remember {
        listOf(Tb.EncBlue, Tb.EncGreen, Tb.EncWhite, Tb.EncOrange).map { TextStyle(color = it, fontSize = 11.sp, fontWeight = FontWeight.Black) }
    }
    Canvas(modifier) {
        tel.frame.longValue
        dotGrid(24.dp.toPx())
        val env = if (amp) patch.ampEnv else patch.filterEnv
        val w = size.width; val h = size.height
        val top = h * 0.14f; val bottom = h * 0.8f
        fun span(ms: Float) = 0.07f + 0.24f * (log10(1f + ms) / 4f)
        val wa = span(env.attackMs); val wd = span(env.decayMs); val ws = 0.2f; val wr = span(env.releaseMs)
        val scale = (w * 0.94f) / (wa + wd + ws + wr)
        val x0 = w * 0.03f; val x1 = x0 + wa * scale; val x2 = x1 + wd * scale; val x3 = x2 + ws * scale; val x4 = x3 + wr * scale
        val ySus = bottom - (bottom - top) * env.sustain

        // Curved segments: attack bows up, decay/release fall off exponentially.
        val curveA: Path.() -> Unit = { cubicTo(x0 + (x1 - x0) * 0.35f, top + (bottom - top) * 0.25f, x1 - (x1 - x0) * 0.2f, top, x1, top) }
        val curveD: Path.() -> Unit = { cubicTo(x1 + (x2 - x1) * 0.15f, ySus - (ySus - top) * 0.1f, x1 + (x2 - x1) * 0.35f, ySus, x2, ySus) }
        val curveR: Path.() -> Unit = { cubicTo(x3 + (x4 - x3) * 0.15f, bottom - (bottom - ySus) * 0.1f, x3 + (x4 - x3) * 0.35f, bottom, x4, bottom) }
        segA.reset(); segA.moveTo(x0, bottom); segA.curveA()
        segD.reset(); segD.moveTo(x1, top); segD.curveD()
        segR.reset(); segR.moveTo(x3, ySus); segR.curveR()
        fill.reset(); fill.moveTo(x0, bottom); fill.curveA(); fill.curveD(); fill.lineTo(x3, ySus); fill.curveR(); fill.close()

        drawPath(fill, Tb.EncWhite.copy(alpha = 0.05f))
        val sw = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(segA, Tb.EncBlue, style = sw)
        drawPath(segD, Tb.EncGreen, style = sw)
        drawLine(Tb.EncWhite, Offset(x2, ySus), Offset(x3, ySus), 5.dp.toPx(), cap = StrokeCap.Round)
        drawPath(segR, Tb.EncOrange, style = sw)
        drawLine(Tb.Line, Offset(0f, bottom), Offset(w, bottom), 1f)

        // Labels under each segment, in segment colour
        drawText(tm, "A ${SynthParams.fmtMs(env.attackMs)}", Offset(x0, bottom + 8.dp.toPx()), styles[0])
        drawText(tm, "D ${SynthParams.fmtMs(env.decayMs)}", Offset(x1 + 4, bottom + 8.dp.toPx()), styles[1])
        drawText(tm, "S ${(env.sustain * 100).toInt()}%", Offset(x2 + 4, bottom + 8.dp.toPx()), styles[2])
        drawText(tm, "R ${SynthParams.fmtMs(env.releaseMs)}", Offset(x3 + 4, bottom + 8.dp.toPx()), styles[3])

        // The live dot: x from stage progress, y from the engine's real envelope level.
        val stage = if (amp) tel.ampStage else tel.fenvStage
        val level = if (amp) tel.ampLevel else tel.fenvLevel
        val t = (if (amp) tel.ampStageTime else tel.fenvStageTime) * 1000f
        if (stage != 0) {
            val x = when (stage) {
                1 -> x0 + (x1 - x0) * (t / env.attackMs.coerceAtLeast(1f)).coerceIn(0f, 1f)
                2 -> x1 + (x2 - x1) * (t / env.decayMs.coerceAtLeast(1f)).coerceIn(0f, 1f)
                3 -> x2 + (x3 - x2) * (0.5f + 0.5f * sin(tel.timeSec * 2f))   // idles along the plateau
                else -> x3 + (x4 - x3) * (t / env.releaseMs.coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            val y = bottom - (bottom - top) * level.coerceIn(0f, 1f)
            drawCircle(Tb.Text.copy(alpha = 0.18f), 16.dp.toPx(), Offset(x, y))
            drawCircle(Tb.Text, 7.dp.toPx(), Offset(x, y))
            drawLine(Tb.Text.copy(alpha = 0.3f), Offset(x, y), Offset(x, bottom), 1.dp.toPx())
        }
    }
}

// ============================== LFO ==============================

@Composable
fun LfoViz(patch: SynthPatch, tel: SynthTelemetry, modifier: Modifier) {
    val ring = remember { Path() }; val trail = remember { Path() }
    val tm = rememberTextMeasurer()
    val bigStyle = remember { TextStyle(color = Tb.EncOrange, fontSize = 26.sp, fontWeight = FontWeight.Black) }
    val smallStyle = remember { TextStyle(color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    Canvas(modifier) {
        tel.frame.longValue
        val w = size.width; val h = size.height
        val r = min(h * 0.4f, w * 0.2f)
        val c = Offset(w * 0.24f, h * 0.5f)
        val depth = patch.lfo.depth
        val shapeIdx = patch.lfo.shape.ordinal

        fun lfoShape(t: Float): Float = when (shapeIdx) {
            0 -> sin(2f * PI.toFloat() * t)
            1 -> 1f - 4f * abs(t - 0.5f)
            2 -> 2f * t - 1f
            3 -> if (t < 0.5f) 1f else -1f
            else -> { val k = (t * 8).toInt(); ((k * 7919 % 13) / 6f) - 1f }   // stepped preview for S&H
        }

        // Turntable: the waveform wrapped around a circle (white = WAVE), modulated outward by depth (green).
        drawCircle(Tb.Line, r, c, style = Stroke(1.dp.toPx()))
        ring.reset()
        val n = 180
        for (i in 0..n) {
            val t = i.toFloat() / n
            val a = 2f * PI.toFloat() * t - PI.toFloat() / 2f
            val rr = r * (0.7f + 0.26f * (0.25f + 0.75f * depth) * lfoShape(t))
            val p = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr)
            if (i == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
        }
        ring.close()
        drawPath(ring, Tb.EncWhite, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))

        // Hand (blue = RATE) sweeping at the real LFO phase, and the tip at the real value (green = DEPTH).
        val ph = tel.lfoPhase
        val a = 2f * PI.toFloat() * ph - PI.toFloat() / 2f
        val dir = Offset(cos(a), sin(a))
        drawLine(Tb.EncBlue, c, c + dir * (r * 1.08f), 5.dp.toPx(), cap = StrokeCap.Round)
        val tipR = r * (0.7f + 0.26f * (0.25f + 0.75f * depth) * lfoShape(ph))
        drawCircle(Tb.EncGreen, (5f + 7f * depth).dp.toPx(), c + dir * tipR)
        drawCircle(Tb.EncBlue, 6.dp.toPx(), c)

        // Trail of recent LFO output, scrolling right→left.
        val left = w * 0.5f; val right = w * 0.97f; val mid = h * 0.58f; val amp = h * 0.24f
        trail.reset()
        val hist = tel.lfoHistory; val len = hist.size
        for (i in 0 until len) {
            val v = hist[(tel.lfoHead + i) % len]
            val x = left + (right - left) * i / (len - 1)
            val y = mid - v * amp
            if (i == 0) trail.moveTo(x, y) else trail.lineTo(x, y)
        }
        drawLine(Tb.Line, Offset(left, mid), Offset(right, mid), 1f)
        drawPath(trail, Tb.EncGreen, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Target (orange = TARGET)
        val target = when (patch.lfo.target) { LfoTarget.PITCH -> "→ PITCH"; LfoTarget.CUTOFF -> "→ CUTOFF"; LfoTarget.AMP -> "→ AMP"; LfoTarget.SHAPE -> "→ SHAPE" }
        drawText(tm, target, Offset(left, h * 0.08f), bigStyle)
        drawText(tm, String.format(java.util.Locale.US, "%.2f Hz · %d%%", patch.lfo.rateHz, (depth * 100).toInt()), Offset(left, h * 0.08f + 36.dp.toPx()), smallStyle)
    }
}


// ============================== PITCH ==============================
// The pitch envelope as a falling (or rising) line from P.ENV back to the note, drawn over a
// semitone ladder; a dot rides it from every new note. Octave ladders for both oscillators on the right.

@Composable
fun PitchViz(patch: SynthPatch, tel: SynthTelemetry, modifier: Modifier) {
    val curve = remember { Path() }
    val track = remember { FloatArray(2) { -1f } }            // [0] last newest note, [1] its start time
    Canvas(modifier) {
        tel.frame.longValue
        dotGrid(24.dp.toPx())
        val w = size.width * 0.74f; val h = size.height
        val amt = patch.voice.pitchEnvSemis
        val decay = patch.voice.pitchEnvDecayMs.coerceAtLeast(1f)
        val span = max(12f, abs(amt) * 1.15f)
        val mid = if (amt >= 0f) h * 0.82f else h * 0.18f
        val pxPerSemi = (h * 0.64f) / span
        // Semitone ladder: octaves bold.
        for (s in 0..span.toInt()) {
            val y = mid - (if (amt >= 0f) s else -s) * pxPerSemi
            val oct = s % 12 == 0
            drawLine(if (oct) Tb.Line else Tb.Raised, Offset(0f, y), Offset(w, y), if (oct) 2f else 1f)
        }
        // Window: 4 × decay, at least 200 ms.
        val windowMs = max(200f, decay * 1.6f)
        curve.reset()
        val n = 120
        for (i in 0..n) {
            val tMs = windowMs * i / n
            val env = kotlin.math.exp(-4f * tMs / decay)
            val x = w * i / n
            val y = mid - amt * env * pxPerSemi
            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
        }
        drawPath(curve, Tb.EncBlue, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Decay marker (green): where 98 % of the drop is done.
        val xd = (w * decay / windowMs).coerceAtMost(w)
        drawLine(Tb.EncGreen, Offset(xd, mid - 14.dp.toPx()), Offset(xd, mid + 14.dp.toPx()), 4.dp.toPx(), cap = StrokeCap.Round)
        // Rider dot from the latest note-on.
        val newest = tel.newestNote.toFloat()
        if (newest != track[0]) { track[0] = newest; track[1] = tel.timeSec }
        if (tel.activeVoices > 0) {
            val tMs = (tel.timeSec - track[1]) * 1000f
            if (tMs in 0f..windowMs) {
                val env = kotlin.math.exp(-4f * tMs / decay)
                drawCircle(Tb.Text, 7.dp.toPx() * (0.6f + 0.4f * tel.ampLevel), Offset(w * tMs / windowMs, mid - amt * env * pxPerSemi))
            }
        }
        // Octave ladders (white = osc 1, orange = osc 2).
        fun ladder(x: Float, oct: Int, c: Color) {
            val step = h / 8f; val bw = size.width * 0.09f
            for (o in -3..3) {
                val y = h / 2f - o * step
                val on = o == oct
                drawRoundRect(if (on) c else Tb.Raised, Offset(x, y - step * 0.32f), androidx.compose.ui.geometry.Size(bw, step * 0.64f),
                    androidx.compose.ui.geometry.CornerRadius(6f))
            }
        }
        ladder(size.width * 0.78f, patch.osc1.octave, Tb.EncWhite)
        ladder(size.width * 0.89f, patch.osc2.octave, Tb.EncOrange)
    }
}

// ============================== VOICE ==============================
// Four little instruments in one panel: GLIDE (a note sliding between two keys), MONO/POLY
// (voice dots), DRIVE (a sine bent by the actual clip curve), NOISE (sparkle grains).

@Composable
fun VoiceViz(patch: SynthPatch, tel: SynthTelemetry, modifier: Modifier) {
    val wave = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue
        dotGrid(24.dp.toPx())
        val w = size.width; val h = size.height
        val t = tel.timeSec
        val pad = 10.dp.toPx()
        val cw = w / 2f; val ch = h / 2f

        // GLIDE (blue, top-left): a dot slides between two keys; slide time ∝ glide.
        run {
            val g = patch.voice.glideMs
            val period = 1.4f
            val ph = (t % period) / period
            val slide = (g / 1000f).coerceIn(0.02f, 0.6f)
            val a = Offset(pad * 2, ch * 0.75f); val b = Offset(cw - pad * 2, ch * 0.3f)
            val k = if (g <= 0f) (if (ph < 0.5f) 0f else 1f) else (((ph - 0.5f) / slide).coerceIn(0f, 1f).let { if (ph < 0.5f) 0f else it })
            drawLine(Tb.Raised, Offset(a.x, a.y), Offset(b.x, a.y), 2f)
            drawLine(Tb.Raised, Offset(a.x, b.y), Offset(b.x, b.y), 2f)
            val p = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
            if (g > 0f) drawLine(Tb.EncBlue.copy(alpha = 0.45f), a, p, 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Tb.EncBlue, 8.dp.toPx(), p)
        }
        // MONO / POLY (green, top-right): one big voice, or eight small ones lit by activity.
        run {
            val cx = cw + cw / 2f; val cy = ch / 2f
            if (patch.voice.mono) {
                drawCircle(Tb.EncGreen.copy(alpha = 0.25f + 0.75f * tel.ampLevel), ch * 0.28f, Offset(cx, cy))
                drawCircle(Tb.EncGreen, ch * 0.28f, Offset(cx, cy), style = Stroke(3.dp.toPx()))
            } else for (i in 0 until 8) {
                val x = cw + pad * 2 + (cw - pad * 4) * (i + 0.5f) / 8f
                drawCircle(if (i < tel.activeVoices) Tb.EncGreen else Tb.Raised, min(ch * 0.12f, (cw - pad * 4) / 20f), Offset(x, cy))
            }
        }
        // DRIVE (white, bottom-left): a sine through the engine's clip curve.
        run {
            val d = patch.voice.drive
            val gain = 1.2f * 2f.pow(d * 4f); val comp = 1f / (1f + 1.6f * d)
            fun clip(x: Float): Float { val c = x.coerceIn(-3f, 3f); return c * (27f + c * c) / (27f + 9f * c * c) }
            wave.reset()
            val n = 100; val y0 = ch + ch / 2f; val amp = ch * 0.3f
            for (i in 0..n) {
                val ph = i.toFloat() / n
                val x = pad * 2 + (cw - pad * 4) * ph
                val y = y0 - clip(sin(2f * PI.toFloat() * (ph * 2f + t * 0.3f)) * gain) * comp * amp * 1.6f
                if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
            }
            drawPath(wave, Tb.EncWhite, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // NOISE (orange, bottom-right): grains, count ∝ noise, re-seeded every frame like static.
        run {
            val nz = patch.voice.noise
            val count = (nz * 90f).toInt()
            var seed = (t * 60f).toInt() * 1103515245
            fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }
            for (i in 0 until count) {
                val x = cw + pad * 2 + (cw - pad * 4) * rnd(); val y = ch + pad + (ch - pad * 2) * rnd()
                drawCircle(Tb.EncOrange.copy(alpha = 0.4f + 0.6f * rnd()), 1.5f.dp.toPx() + 2.dp.toPx() * rnd(), Offset(x, y))
            }
            if (count == 0) drawLine(Tb.EncOrange.copy(alpha = 0.4f), Offset(cw + pad * 2, ch + ch / 2f), Offset(w - pad * 2, ch + ch / 2f), 3.dp.toPx(), cap = StrokeCap.Round)
        }
        // Quadrant dividers
        drawLine(Tb.Line, Offset(cw, pad), Offset(cw, h - pad), 1f)
        drawLine(Tb.Line, Offset(pad, ch), Offset(w - pad, ch), 1f)
    }
}
