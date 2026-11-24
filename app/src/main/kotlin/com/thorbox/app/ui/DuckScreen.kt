// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.model.DuckPresets
import com.thorbox.model.DuckSettings
import com.thorbox.model.TrackKind
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * DUCK: the mix's sidechain compressor, on its own page. One KEY (the kick, a whole track, or the
 * beat) makes every TARGET track dip, then swell back in time: the boom-bap pump.
 *
 *   header   DUCK tag · four one-tap presets · ON / OFF
 *   hero     the subway-platform rat (left) · the squash meter + the BOUNCE curve + the key vs WEIGHT (right)
 *   routing  KEY (what makes it duck) · DIPS (what gets ducked; drums usually stay dry)
 *   knobs    WEIGHT · SQUASH · SNAP · BOUNCE · PUMP · LOUD  (threshold · ratio · attack · release · mix · makeup)
 *
 * Everything moving here is drawn from the engine's real gain reduction every frame (60 fps, no
 * recomposition): when the rat sinks 12 dB worth, the targets really are 12 dB down.
 */
@Composable
fun DuckScreen(state: AppState) {
    val g = state.groove
    val d = g.duck.value
    val tracks = state.tracks.value
    val lastKnob = remember { mutableIntStateOf(0) }
    val dragging = remember { mutableIntStateOf(-1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- header: tag · presets · on/off ----
        Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Tb.Key))
            Text("DUCK", style = Type.title, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
            DuckPresets.all.forEach { p ->
                HwButton(p.name, Modifier.weight(1f).fillMaxHeight(), active = d.preset == p.name, accent = Tb.Key, minHeight = 30.dp, fontSize = 11, hPad = 4.dp) {
                    g.setDuck { it.withPreset(p) }; com.thorbox.app.ui.Info.toast(p.name, p.blurb)
                }
            }
            HwButton(if (d.on) "ON" else "OFF", Modifier.width(64.dp).fillMaxHeight(), active = d.on, accent = Tb.Good, minHeight = 30.dp, fontSize = 12) {
                state.toggleDuck()
            }
        }

        // ---- hero: the rat | the meter ----
        Row(Modifier.weight(1f).fillMaxWidth().bezel()) {
            SubwayRat(state, Modifier.weight(1f).fillMaxHeight().clipToBounds())
            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(Tb.Line))
            DuckMeter(state, Modifier.weight(1f).fillMaxHeight().clipToBounds().padding(10.dp))
        }

        // ---- routing: KEY + DIPS ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("KEY", color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.width(40.dp))
            tracks.forEachIndexed { t, tr ->
                if (tr.kind == TrackKind.EMPTY) return@forEachIndexed
                HwButton(tr.name.take(8), Modifier.weight(1f).fillMaxHeight(), active = d.source == t, accent = kindColor(tr.kind), minHeight = 30.dp, fontSize = 11, hPad = 2.dp) {
                    // a drum key starts on its first voice (the kick); tracks stop ducking themselves
                    g.setDuck { it.copy(source = t, sourceSlot = if (tr.kind == TrackKind.DRUMS) 0 else -1).withTarget(t, false) }
                }
            }
            HwButton("BEAT", Modifier.weight(1f).fillMaxHeight(), active = d.source == DuckSettings.BEAT, accent = Tb.Tempo, minHeight = 30.dp, fontSize = 11, hPad = 2.dp) {
                g.setDuck { it.copy(source = DuckSettings.BEAT) }
            }
            // a drum key: which voice (tap to step through the kit's first eight, or the whole kit)
            if (d.source in tracks.indices && tracks[d.source].kind == TrackKind.DRUMS) {
                val kit = state.drumsFor(d.source).kit.value
                val name = if (d.sourceSlot < 0) "ALL DRUMS" else kit.voices.getOrNull(d.sourceSlot)?.name ?: "PAD ${d.sourceSlot + 1}"
                HwButton("$name ▸", Modifier.weight(1.4f).fillMaxHeight(), active = true, accent = Tb.EncOrange, minHeight = 30.dp, fontSize = 11, hPad = 2.dp) {
                    g.setDuck { it.copy(sourceSlot = if (it.sourceSlot >= 7) -1 else it.sourceSlot + 1) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DIPS", color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.width(40.dp))
            tracks.forEachIndexed { t, tr ->
                if (tr.kind == TrackKind.EMPTY) return@forEachIndexed
                val isKey = d.source == t && d.sourceSlot < 0
                HwButton(if (d.isTarget(t)) "${tr.name.take(7)} ✓" else tr.name.take(8), Modifier.weight(1f).fillMaxHeight(), active = d.isTarget(t),
                    enabled = !isKey, accent = kindColor(tr.kind), minHeight = 30.dp, fontSize = 11, hPad = 2.dp) {
                    g.setDuck { it.withTarget(t, !it.isTarget(t)) }
                }
            }
        }

        // ---- the six knobs ----
        Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DuckKnob.entries.forEachIndexed { i, k ->
                Encoder(
                    label = k.label, display = k.format(d), value = k.norm(d), color = k.color(), active = dragging.intValue == i,
                    onDragStart = { dragging.intValue = i; lastKnob.intValue = i },
                    onDelta = { dv -> g.setDuck { k.set(it, k.norm(it) + dv).copy(preset = "") } },
                    onDragEnd = { if (dragging.intValue == i) dragging.intValue = -1 },
                    onReset = { g.setDuck { k.set(it, k.norm(DuckSettings().withPreset(DuckPresets.all[0]))).copy(preset = "") } },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** The six controls: kid name, the real thing, its range on the knob (log where the ear is). */
enum class DuckKnob(val label: String, val real: String) {
    WEIGHT("WEIGHT", "threshold"), SQUASH("SQUASH", "ratio"), SNAP("SNAP", "attack"),
    BOUNCE("BOUNCE", "release"), PUMP("PUMP", "mix"), LOUD("LOUD", "makeup");

    fun color(): Color = when (this) {
        WEIGHT -> Tb.EncBlue; SQUASH -> Tb.EncGreen; SNAP -> Tb.EncWhite; BOUNCE -> Tb.EncOrange; PUMP -> Tb.Key; LOUD -> Tb.Tempo
    }
    fun norm(d: DuckSettings): Float = when (this) {
        WEIGHT -> (d.thresholdDb + 60f) / 60f
        SQUASH -> ln(d.ratio) / ln(20f)
        SNAP -> ln(d.attackMs / 0.1f) / ln(500f)
        BOUNCE -> ln(d.releaseMs / 20f) / ln(50f)
        PUMP -> d.mix
        LOUD -> d.makeupDb / 12f
    }.coerceIn(0f, 1f)
    fun set(d: DuckSettings, n0: Float): DuckSettings {
        val n = n0.coerceIn(0f, 1f)
        return when (this) {
            WEIGHT -> d.copy(thresholdDb = (n * 60f - 60f).roundTo(0.5f))
            SQUASH -> d.copy(ratio = exp(n * ln(20f)).roundTo(0.1f))
            SNAP -> d.copy(attackMs = (0.1f * exp(n * ln(500f))).roundTo(0.1f))
            BOUNCE -> d.copy(releaseMs = (20f * exp(n * ln(50f))).roundTo(1f))
            PUMP -> d.copy(mix = n.roundTo(0.01f))
            LOUD -> d.copy(makeupDb = (n * 12f).roundTo(0.1f))
        }
    }
    fun format(d: DuckSettings): String = when (this) {
        WEIGHT -> String.format(Locale.US, "%.0f dB", d.thresholdDb)
        SQUASH -> when {
            d.ratio >= 19.95f -> "∞:1"
            abs(d.ratio - d.ratio.roundToInt()) < 0.05f || d.ratio >= 10f -> "${d.ratio.roundToInt()}:1"
            else -> String.format(Locale.US, "%.1f:1", d.ratio)
        }
        SNAP -> String.format(Locale.US, if (d.attackMs < 10f) "%.1f ms" else "%.0f ms", d.attackMs)
        BOUNCE -> String.format(Locale.US, "%.0f ms", d.releaseMs)
        PUMP -> "${(d.mix * 100).roundToInt()}%"
        LOUD -> String.format(Locale.US, "+%.1f dB", d.makeupDb)
    }
}
private fun Float.roundTo(step: Float) = (this / step).roundToInt() * step

/**
 * The squash meter (right half of the hero): how far the targets are down right now, the last 3 s of
 * it as a curve (the dips are the kicks, the slopes back up are BOUNCE), and the key's level against
 * the WEIGHT line (cross it and the ducking starts).
 */
@Composable
private fun DuckMeter(state: AppState, modifier: Modifier) {
    val g = state.groove
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 48)
    val big = remember(Tb.palette) { TextStyle(color = Tb.Key, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 26.sp) }
    val small = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val mono = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 9.sp) }
    val curve = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue
        val d = g.duck.value
        val w = size.width; val h = size.height
        val gr = g.duckGrDb
        // headline: the squash right now
        val head = tm.measure(if (!d.on) "OFF" else if (gr < 0.1f) "0.0 dB" else String.format(Locale.US, "−%.1f dB", gr), big)
        drawText(head, topLeft = Offset(0f, 0f), color = if (d.on) Tb.Key else Tb.Dim)
        drawText(tm.measure("SQUASH NOW", small), topLeft = Offset(head.size.width + 8.dp.toPx(), head.size.height - 16.dp.toPx()))
        // the bar beside it: fills right → left as the targets go down (0 … 24 dB)
        val barTop = head.size.height + 4.dp.toPx(); val barH = 8.dp.toPx()
        drawRoundRect(Tb.Raised, Offset(0f, barTop), Size(w, barH), CornerRadius(barH / 2))
        val f = (gr / 24f).coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(Tb.Key, Offset(w * (1f - f), barTop), Size(w * f, barH), CornerRadius(barH / 2))

        // the BOUNCE curve: 0 dB along the top, dips hang down (scale 0 … 30 dB)
        val cTop = barTop + barH + 10.dp.toPx(); val keyH = 22.dp.toPx()
        val cBot = h - keyH - 8.dp.toPx()
        val lblH = 14.dp.toPx()                                   // the BOUNCE label gets its own strip under the plot
        if (cBot - lblH - cTop > 20f) {
            val span = cBot - lblH - cTop
            for (db in intArrayOf(0, 6, 12, 24)) {
                val y = cTop + span * db / 30f
                drawLine(Tb.Line, Offset(0f, y), Offset(w, y), 1f)
                drawText(tm.measure(if (db == 0) "0" else "−$db", mono), topLeft = Offset(w - 18.dp.toPx(), y + 1f))
            }
            val hist = g.duckHistory; val n = hist.size; val head0 = g.duckHead
            curve.reset()
            curve.moveTo(0f, cTop)
            for (i in 0 until n) {
                val v = hist[(head0 + i) % n]
                curve.lineTo(w * i / (n - 1), cTop + span * (v / 30f).coerceIn(0f, 1f))
            }
            curve.lineTo(w, cTop); curve.close()
            drawPath(curve, Tb.Key.copy(alpha = 0.28f))
            // the outline on top (same points), so the release slope reads as a line
            var px = 0f; var py = cTop + span * (hist[head0 % n] / 30f).coerceIn(0f, 1f)
            for (i in 1 until n) {
                val x = w * i / (n - 1); val y = cTop + span * (hist[(head0 + i) % n] / 30f).coerceIn(0f, 1f)
                drawLine(Tb.Key, Offset(px, py), Offset(x, y), 2.dp.toPx(), cap = StrokeCap.Round)
                px = x; py = y
            }
            drawText(tm.measure("BOUNCE (last 3 s)", small), topLeft = Offset(0f, cTop + span + 1.dp.toPx()))
        }

        // the key against WEIGHT: −60 … 0 dBFS; past the WEIGHT line, it ducks
        val ky = h - keyH
        drawText(tm.measure("KEY", small), topLeft = Offset(0f, ky))
        val kx0 = 32.dp.toPx(); val kw = w - kx0; val kbh = 10.dp.toPx(); val kby = ky + 2.dp.toPx()
        drawRoundRect(Tb.Raised, Offset(kx0, kby), Size(kw, kbh), CornerRadius(3f))
        val kf = ((g.duckKeyDb + 60f) / 60f).coerceIn(0f, 1f)
        val over = g.duckKeyDb > d.thresholdDb
        if (kf > 0f) drawRoundRect(if (over) Tb.Warn else Tb.Good, Offset(kx0, kby), Size(kw * kf, kbh), CornerRadius(3f))
        val tx = kx0 + kw * ((d.thresholdDb + 60f) / 60f).coerceIn(0f, 1f)
        drawLine(Tb.Text, Offset(tx, kby - 3.dp.toPx()), Offset(tx, kby + kbh + 3.dp.toPx()), 2.dp.toPx())
        val wl = tm.measure("WEIGHT", small)
        drawText(wl, topLeft = Offset((tx - wl.size.width / 2f).coerceIn(kx0, w - wl.size.width.toFloat()), kby + kbh + 2.dp.toPx()))
    }
}

/**
 * The NYC subway rat, on a tiled platform (DUCK ST). He's holding a slice over his head like a barbell:
 * every kick drops its weight on him and he squashes by the real gain reduction; then he pushes it
 * back up on the BOUNCE (release) curve. The fit: a charcoal hoodie (the theme's colour on the sleeve
 * stripes and the cap button), baggy denim stacked on wheat Timbs, a backwards fitted, a gold chain,
 * headphones round his neck.
 *
 * How he moves (the animation principles, each from a real signal):
 *  - squash & stretch: a spring chases the gain reduction, so the recovery overshoots into a stretch
 *    (that's the "bounce"); his width trades with his height so he keeps his volume
 *  - anticipation: he reads the key's pattern (or the beat) ahead and braces just before each kick
 *  - follow-through: ears, tail, headphone cups and the slice's wobble are springs that lag and settle
 *  - secondary action: he blinks, nods to the tempo (drop on the beat, ease up), sniffs and breathes
 *    when it's quiet; the woofers, the pigeon and the steam react to the key
 *  - the hit itself: a BOOM burst on the slice, dust kicked out at his feet, steam through the grate
 *    and a short shake of the whole platform, all scaled by how hard that kick dipped
 */
@Composable
fun SubwayRat(state: AppState, modifier: Modifier) {
    val g = state.groove
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 16)
    // text drawn INSIDE the scaled scene is sized in scene units (1 unit = 1 px before the scale), so
    // the sign reads the same at any size instead of being scaled twice
    val dens = androidx.compose.ui.platform.LocalDensity.current
    fun unitSp(u: Float) = (u / dens.density / dens.fontScale).sp
    val sign = remember(dens) { TextStyle(color = Color.White, fontSize = unitSp(15f), fontWeight = FontWeight.Black, letterSpacing = unitSp(1f)) }
    val bullet = remember(dens) { TextStyle(color = Color.White, fontSize = unitSp(11f), fontWeight = FontWeight.Black) }
    val boom = remember(dens) { TextStyle(color = INK, fontSize = unitSp(9f), fontWeight = FontWeight.Black) }
    val notice = remember { TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val rs = remember { RatState() }
    val slice = remember { Path() }; val tail = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue
        val d = g.duck.value
        val now = tel.timeSec
        val dt = (now - rs.lastT).coerceIn(0f, 0.05f); rs.lastT = now
        val playing = g.transport[1] != 0.0
        val bpm = g.groove.value.bpm.coerceAtLeast(30f)
        val beatPos = g.transport[0] / com.thorbox.model.PPQN
        val beat = if (playing) (beatPos % 1.0).toFloat() else (now * 0.5f) % 1f
        val gr = if (d.on) g.duckGrDb else 0f

        // ---- a new trigger: the weight lands ----
        val fresh = g.duckTriggers != rs.lastTrig
        if (fresh) {
            if (d.on && g.duckTriggers > rs.lastTrig) {
                rs.hitAt = now
                rs.slice.kick(if (rs.flip) 140f else -140f); rs.flip = !rs.flip
                rs.cups.kick(-60f); rs.ears.kick(80f); rs.cone.kick(30f)
                rs.peak = 0f; rs.popped = false
                if (gr > 14f || rs.lastGr > 14f) { rs.pigeonAt = now; rs.pigeon.kick(-70f) }
            }
            rs.lastTrig = g.duckTriggers
        }
        rs.lastGr = gr
        val since = now - rs.hitAt

        // ---- anticipation: brace in the last 120 ms before the next kick ----
        val toGo = if (d.on && playing) nextKeyIn(state, d, beatPos, bpm) else -1f
        val brace = if (toGo in 0f..0.12f) smooth01(1f - toGo / 0.12f) else 0f

        // ---- the springs ----
        val target = (gr / 24f).coerceIn(0f, 1.1f) + 0.12f * brace
        // the boing: when he's nearly back up, he pops past standing (tuned offline: ~0.18 of a stretch,
        // ~380 ms after a heavy kick; a release envelope alone eases out too smoothly to overshoot)
        rs.peak = maxOf(rs.peak, target)
        if (!rs.popped && rs.peak > 0.15f && target < 0.35f * rs.peak) { rs.pop.kick(-18f * rs.peak); rs.popped = true }
        val sq = rs.body.step(target, dt) + rs.pop.step(0f, dt)                // below 0 = taller than standing
        val nodCurve = if (playing) decay((beat * 60f / bpm), 9f) * (1f - 0.6f * (gr / 24f).coerceIn(0f, 1f)) else 0f
        val nod = rs.head.step(nodCurve, dt)
        val ear = rs.ears.step(sq * 14f, dt)                                  // droop (deg), lagging the body
        val tailSway = rs.tail.step(nod * 8f + sq * 10f, dt)
        val tilt = rs.slice.step(0f, dt)
        val cupDy = rs.cups.step(0f, dt)
        val keyLvl = ((g.duckKeyDb + 50f) / 50f).coerceIn(0f, 1f)
        val cone = rs.cone.step(keyLvl * 1.6f, dt)
        val pigeonHop = rs.pigeon.step(0f, dt)
        val blink = rs.blink.closed(now)
        // quiet: breathe, and every few seconds a sniff (the nose and whiskers flutter)
        val idle = !playing || gr < 0.1f && since > 1.5f
        val breathe = if (idle) sin(now * 2.2f) * 0.018f else 0f
        if (now > rs.sniffAt + 0.7f) rs.sniffAt = now + 3.5f + ((now * 13.7f) % 3f)
        val sniff = if (idle && now in rs.sniffAt..(rs.sniffAt + 0.6f)) sin((now - rs.sniffAt) * 75f) else 0f

        val sc = min(size.width / 240f, size.height / 200f)
        val ox = (size.width - 240f * sc) / 2f; val oy = (size.height - 200f * sc) / 2f
        // the scene's backdrop runs edge to edge (beyond the 240 × 200 stage, the tiles just continue)
        drawRect(TILE, Offset.Zero, size)
        // how hard this kick landed (0.35 … 1, grows as the dip deepens): scales the shake, dust, steam, burst
        val hit = (0.35f + 0.65f * rs.peak / 0.6f).coerceIn(0.35f, 1f)
        val fxSince = if (d.on) since else -1f
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            // the platform shakes when the weight lands (a fast, decaying wobble)
            val shake = if (fxSince in 0f..0.35f) 2.6f * hit * exp(-fxSince * 14f) * sin(fxSince * 95f) else 0f
            translate(0f, shake) {
                platform(now, tm, sign, bullet, d.source)
                boombox(cone)
                pigeon(pigeonHop, now - rs.pigeonAt, now)
                steam(now, fxSince, hit)
                dust(110f, 178f, fxSince, hit)
                val sliceY = rat(RatPose(sq, nod, ear, tailSway, tilt, cupDy, blink, sniff, breathe, brace), now, slice, tail)
                burst(124f, sliceY - 20f, fxSince, hit, slice, tm, boom)
            }
        }
        if (!d.on) {
            drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, size)
            val t = tm.measure(when { d.source < 0 -> "PICK A KEY ↓"; d.targets == 0 -> "PICK WHAT DIPS ↓"; else -> "DUCK IS OFF" }, notice)
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, size.height * 0.12f))
        }
    }
}

/** Seconds until the DUCK key next fires (from the key track's pattern, or the next quarter for BEAT); -1 = none soon. */
private fun nextKeyIn(state: AppState, d: DuckSettings, beatPos: Double, bpm: Float): Float {
    val secPerBeat = 60f / bpm
    if (d.source == DuckSettings.BEAT) return ((1.0 - beatPos % 1.0) * secPerBeat).toFloat()
    val clip = state.groove.clips.value.getOrNull(d.source) ?: return -1f
    val len = clip.lengthTicks.toDouble()
    if (len <= 0.0) return -1f
    val pos = state.groove.loopTick % len
    var best = Double.MAX_VALUE
    val drumKey = d.sourceSlot >= 0 && state.trackKind(d.source) == TrackKind.DRUMS
    for (e in clip.notes) {
        if (drumKey && e.note != d.sourceSlot) continue
        var dd = e.tick - pos; if (dd <= 0) dd += len
        if (dd < best) best = dd
    }
    return if (best == Double.MAX_VALUE) -1f else (best / com.thorbox.model.PPQN * secPerBeat).toFloat()
}

private class RatState {
    var lastT = 0f; var hitAt = -10f; var lastTrig = 0; var lastGr = 0f; var flip = false
    var pigeonAt = -10f; var sniffAt = 3f; var peak = 0f; var popped = true
    val pop = Spring(3.5f, 0.35f)        // the end-of-bounce pop (kicked once per kick, as he comes free)
    val body = Spring(6.5f, 0.34f)       // squash (+) / stretch (−): overshoots on the way back up
    val head = Spring(7f, 0.45f)         // the nod
    val ears = Spring(4.5f, 0.25f)       // ear droop, lagging
    val tail = Spring(2.4f, 0.3f)        // tail tip sway
    val slice = Spring(3.2f, 0.18f)      // the slice's wobble (deg) after it lands
    val cups = Spring(8f, 0.3f)          // headphone cups bounce
    val cone = Spring(12f, 0.3f)         // boombox woofers
    val pigeon = Spring(5f, 0.45f)       // the pigeon's hop
    val blink = Blinker(11)
}

/** One frame of the rat's pose (all from the springs above). */
private class RatPose(
    val sq: Float, val nod: Float, val ear: Float, val tail: Float, val tilt: Float, val cupDy: Float,
    val blink: Float, val sniff: Float, val breathe: Float, val brace: Float,
)

// subway-platform palette (the scene has its own colours, like the drummer's corner)
private val TILE = Color(0xFFE8E2D2)
private val GROUT = Color(0xFFCFC6B0)
private val BAND = Color(0xFF1C1C21)
private val TRIM = Color(0xFF7C2F2B)
private val CONCRETE = Color(0xFF8D8A84)
private val EDGE = Color(0xFFE9C21B)
private val PIT = Color(0xFF141417)
private val FUR = Color(0xFF8E8C95)
private val FUR_DARK = Color(0xFF6E6C76)
private val PINK = Color(0xFFF2A0B0)
private val TIMB = Color(0xFFC98E3A)
private val TIMB_DARK = Color(0xFFA87428)
private val TIMB_SOLE = Color(0xFF3B2A1A)
private val HOOD = Color(0xFF2E3038)
private val HOOD_LT = Color(0xFF3E414B)
private val HOOD_DK = Color(0xFF202228)
private val DENIM = Color(0xFF3F5F8A)
private val DENIM_DK = Color(0xFF2E4767)
private val DENIM_LT = Color(0xFF557AA8)
private val CAP = Color(0xFF1F2A44)
private val GOLD = Color(0xFFE6B93C)
private val DUST = Color(0xFFD9D2C2)
private val ARCH = Color(0xFF3A3632)
private val HEADLIGHT = Color(0xFFFFF2B0)
private val CHEESE = Color(0xFFF6C445)
private val CRUST = Color(0xFFC0772E)
private val PEP = Color(0xFFC63A2E)
private val INK = Color(0xFF17151A)

/** Tiled wall, the station band (DUCK ST + a route bullet), the platform and its yellow edge, the tunnel. */
private fun DrawScope.platform(now: Float, tm: androidx.compose.ui.text.TextMeasurer, sign: TextStyle, bullet: TextStyle, source: Int) {
    // running-bond subway tile
    var y = 0f; var row = 0
    while (y < 150f) {
        drawLine(GROUT, Offset(-60f, y), Offset(300f, y), 0.8f)
        var x = if (row % 2 == 0) -60f else -52f
        while (x < 300f) { drawLine(GROUT, Offset(x, y), Offset(x, y + 8f), 0.8f); x += 16f }
        y += 8f; row++
    }
    // station band with a mosaic trim
    drawRect(TRIM, Offset(-60f, 14f), Size(360f, 3f))
    drawRect(BAND, Offset(-60f, 18f), Size(360f, 26f))
    drawRect(TRIM, Offset(-60f, 45f), Size(360f, 3f))
    val routeCol = when (source) { DuckSettings.BEAT -> Color(0xFF2E8BD6); -1 -> Color(0xFF808080); else -> Color(0xFFE5502B) }
    drawCircle(routeCol, 9f, Offset(30f, 31f))
    val bl = tm.measure(if (source == DuckSettings.BEAT) "B" else "K", bullet)
    drawText(bl, topLeft = Offset(30f - bl.size.width / 2f, 31f - bl.size.height / 2f))
    val st = tm.measure("DUCK ST", sign)
    drawText(st, topLeft = Offset(46f, 31f - st.size.height / 2f))
    // the tunnel on the right: an arched stone portal, a signal lamp inside, and every so often a
    // train's headlights glowing up out of the dark
    val tx = 198f; val tw = 76f; val ty = 66f; val tr = tw / 2f
    drawCircle(ARCH, tr + 6f, Offset(tx + tr, ty + tr)); drawRect(ARCH, Offset(tx - 6f, ty + tr), Size(tw + 12f, 150f - ty - tr))
    drawCircle(PIT, tr, Offset(tx + tr, ty + tr)); drawRect(PIT, Offset(tx, ty + tr), Size(tw, 150f - ty - tr))
    for (k in 0..8) {                                                        // the voussoir joints
        val a = PI.toFloat() * (1f + k / 8f); val c = kotlin.math.cos(a); val sn = sin(a)
        drawLine(Color(0xFF57504A), Offset(tx + tr + c * tr, ty + tr + sn * tr), Offset(tx + tr + c * (tr + 6f), ty + tr + sn * (tr + 6f)), 1f)
    }
    drawCircle(Color(0xFF2A2A2A), 3.2f, Offset(tx + 10f, ty + 44f))
    drawCircle(if ((now * 0.7f) % 1f < 0.5f) Color(0xFFE84B3C) else Color(0xFF5A2420), 2f, Offset(tx + 10f, ty + 44f))
    val sweep = ((now * 0.12f) % 1f)
    if (sweep < 0.25f) {
        val a = sin(sweep / 0.25f * PI.toFloat())
        drawRect(Brush.radialGradient(listOf(HEADLIGHT.copy(alpha = 0.35f * a), HEADLIGHT.copy(alpha = 0f)), Offset(tx + tr, 128f), 34f),
            Offset(tx, 94f), Size(tw, 56f))
        drawCircle(HEADLIGHT.copy(alpha = 0.9f * a), 4f, Offset(tx + tr - 10f, 126f))
        drawCircle(HEADLIGHT.copy(alpha = 0.9f * a), 4f, Offset(tx + tr + 10f, 126f))
    }
    // platform, tactile yellow edge, the drop to the tracks
    drawRect(CONCRETE, Offset(-60f, 150f), Size(360f, 32f))
    drawRect(Color(0xFF7E7B75), Offset(-60f, 150f), Size(360f, 2f))
    drawRect(EDGE, Offset(-60f, 178f), Size(360f, 7f))
    var dx = -56f
    while (dx < 300f) { drawCircle(Color(0xFFC9A512), 1.1f, Offset(dx, 181.5f)); dx += 5f }
    drawRect(PIT, Offset(-60f, 185f), Size(360f, 20f))
    drawLine(Color(0xFF8C8C92), Offset(-60f, 194f), Offset(300f, 194f), 1.5f)
    // the grate the steam comes up through
    drawRect(Color(0xFF55534F), Offset(22f, 160f), Size(40f, 9f))
    for (i in 0 until 7) drawLine(Color(0xFF2E2D2B), Offset(25f + i * 5.5f, 161f), Offset(25f + i * 5.5f, 168f), 1.2f)
}

/** A boombox on the platform; the woofers push out with the key (a spring, so they flex past and back). */
private fun DrawScope.boombox(cone: Float) {
    val x = 156f; val y = 150f
    drawLine(Color(0xFF2A2A2E), Offset(x + 6f, y - 4f), Offset(x + 34f, y - 4f), 2.5f)                // handle
    drawRoundRect(Color(0xFF2C2C33), Offset(x, y), Size(40f, 22f), CornerRadius(3f))
    drawRoundRect(Color(0xFF45454E), Offset(x + 15f, y + 3f), Size(10f, 6f), CornerRadius(1f))         // tape deck
    for (cx in floatArrayOf(x + 8f, x + 32f)) {
        drawCircle(Color(0xFF101013), 6.5f + cone * 0.8f, Offset(cx, y + 14f))
        drawCircle(Color(0xFF5B5B66), (2.5f + cone * 1.2f).coerceAtLeast(1f), Offset(cx, y + 14f))
    }
}

/** An NYC pigeon perched on the boombox: head-bobs like pigeons do (a jerk forward, a hold), and startles
 *  on a big hit: a flap and a hop (a spring) that lands back on the box. */
private fun DrawScope.pigeon(hop: Float, sinceStartle: Float, now: Float) {
    val x = 182f; val y = 143f + hop
    val flap = if (sinceStartle in 0f..0.5f) sin(sinceStartle / 0.5f * PI.toFloat() * 4f) * (1f - sinceStartle / 0.5f) else 0f
    // the pigeon bob: the head snaps forward, holds, snaps back (a stepped curve, not a sine)
    val bp = (now * 1.6f) % 1f
    val bob = if (bp < 0.12f) -3f * smooth01(bp / 0.12f) else if (bp < 0.55f) -3f else -3f * (1f - smooth01((bp - 0.55f) / 0.12f))
    drawLine(Color(0xFFE07A5F), Offset(x - 3f, y + 4f), Offset(x - 3f, 150f), 1.2f)                    // feet stay on the box
    drawLine(Color(0xFFE07A5F), Offset(x + 2f, y + 4f), Offset(x + 2f, 150f), 1.2f)
    drawOval(Color(0xFF8D93A0), Offset(x - 9f, y - 4f), Size(16f, 9f))                                 // body
    drawOval(Color(0xFF5E6472), Offset(x + 4f, y - 3f), Size(8f, 4f))                                  // tail
    rotate(-20f - flap * 70f, Offset(x - 2f, y - 2f)) {                                                // wing
        drawOval(Color(0xFF6E7482), Offset(x - 6f, y - 4f), Size(11f, 5f))
    }
    drawCircle(Color(0xFF5DA28C), 3.2f, Offset(x - 8f, y - 4f))                                        // the green-purple neck
    drawCircle(Color(0xFF8A5BA8).copy(alpha = 0.6f), 2.2f, Offset(x - 7f, y - 3f))
    drawCircle(Color(0xFF8D93A0), 3.4f, Offset(x - 11f + bob, y - 8f))                                 // head
    drawCircle(Color(0xFFF08A24), 0.9f, Offset(x - 12f + bob, y - 9f))                                 // eye
    drawLine(Color(0xFF3A3A3A), Offset(x - 14f + bob, y - 7.5f), Offset(x - 16.5f + bob, y - 6.8f), 1.3f)   // beak
}

/**
 * Steam off the grate: a lazy wisp that's always drifting up, and on each kick a burst of puffs pushed
 * through (bigger and thicker the harder the kick). [since] < 0 = no kick to show.
 */
private fun DrawScope.steam(now: Float, since: Float, hit: Float) {
    for (k in 0 until 4) {
        val t = (now * 0.35f + k * 0.25f) % 1f
        val a = 0.16f * sin(t * PI.toFloat())
        drawCircle(Color.White.copy(alpha = a), 4f + t * 9f, Offset(34f + k * 6f + sin(now * 1.3f + k * 2f) * 4f * t, 160f - t * 40f))
    }
    if (since !in 0f..1.3f) return
    for (k in 0 until 6) {
        val t = since - k * 0.05f
        if (t < 0f || t > 1.1f) continue
        val a = (1f - t / 1.1f) * 0.55f * hit
        drawCircle(Color.White.copy(alpha = a), 5f + t * 16f + (k % 3) * 2f,
            Offset(28f + k * 6f + sin(t * 4f + k) * 4f * t, 160f - t * (60f + k * 6f)))
    }
}

/** Dust kicked out along the platform at his feet when the weight lands: puffs that roll outward and fade. */
private fun DrawScope.dust(fx: Float, fy: Float, since: Float, hit: Float) {
    if (since !in 0f..0.55f) return
    for (s in floatArrayOf(-1f, 1f)) for (k in 0 until 3) {
        val t = since - k * 0.03f
        if (t < 0f) continue
        val p = smooth01(t / 0.55f)
        val a = ((1f - t / 0.55f) * 0.75f * hit).coerceIn(0f, 1f)
        drawCircle(DUST.copy(alpha = a), 2.5f + p * (6f + k * 2f), Offset(fx + s * (14f + p * (30f + k * 10f)), fy - 2f - p * (5f + k * 3f)))
    }
}

/** A comic BOOM burst on the slice as it lands: pops out past full size, holds, fades. Skipped on tiny dips. */
private fun DrawScope.burst(cx: Float, cy: Float, since: Float, hit: Float, star: Path, tm: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    if (since !in 0f..0.28f || hit < 0.15f) return
    val t = since / 0.28f
    val sc = 0.55f + 0.6f * smooth01(t * 2.2f)
    val a = 1f - smooth01((t - 0.45f) / 0.55f)
    withTransform({ translate(cx, cy); scale(sc, sc, Offset.Zero); rotate(-7f, Offset.Zero) }) {
        starPath(star, 25f, 14f); drawPath(star, Color(0xFFE8541E), alpha = a)
        starPath(star, 21f, 11.5f); drawPath(star, Color(0xFFFFE27A), alpha = a)
        val l = tm.measure("BOOM", style)
        drawText(l, topLeft = Offset(-l.size.width / 2f, -l.size.height / 2f), alpha = a)
    }
}

/** A ten-point comic star (alternate long points a little shorter), a bit wider than tall, into [p]. */
private fun starPath(p: Path, big: Float, small: Float) {
    p.reset()
    for (i in 0 until 20) {
        val ang = i * PI.toFloat() / 10f - PI.toFloat() / 2f
        val r = if (i % 2 == 0) big * (if (i % 4 == 0) 1f else 0.86f) else small
        val x = kotlin.math.cos(ang) * r; val y = sin(ang) * r * 0.72f
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
}

/**
 * The rat. [RatPose.sq] 0 = standing tall, slice held high · 1 = squatting under it · below 0 = stretched
 * taller than rest (the overshoot on the way back up). Feet at (110, 178); the floor is the pivot.
 * His knees bend and his stance widens as he sinks; the hoodie takes the rest of the squash (it gets
 * wider as it gets shorter). [path] is scratch (the jeans, boots, hoodie, chain, cap brim, snout, slice).
 * Returns where the slice is (y), for the burst.
 */
private fun DrawScope.rat(pose: RatPose, now: Float, path: Path, tail: Path): Float {
    val fx = 110f; val fy = 178f
    val sq = pose.sq.coerceIn(-0.45f, 1.15f); val sqp = sq.coerceAtLeast(0f)
    val squashY = (1f - 0.32f * sq) * (1f + pose.breathe)
    val squashX = (1f / kotlin.math.sqrt(squashY)).coerceIn(0.9f, 1.3f)
    val legH = 24f * (1f - 0.38f * sqp) * (1f + 0.1f * (-sq).coerceAtLeast(0f))
    val hip = fy - 10f - legH + 2f * pose.brace                // knees give a little as he braces
    val torsoH = 40f * squashY; val torsoW = 40f * squashX
    val shoulderY = hip - torsoH
    val headY = shoulderY - 13f + pose.nod * 3f
    val headX = fx + 5f + pose.nod * 1.2f

    // tail: a long pink S out the back; its tip lags his moves (follow-through)
    tail.reset()
    tail.moveTo(fx - torsoW * 0.38f, hip - 4f)
    tail.cubicTo(fx - 48f, hip + 10f, fx - 62f, hip - 24f + pose.tail * 0.5f, fx - 76f, hip - 12f - pose.tail - sin(now * 3f) * 1.5f)
    drawPath(tail, PINK, style = Stroke(3.2f, cap = StrokeCap.Round))

    // baggy jeans: wide legs, knees pushing out as he squats, the hems stacked on the boots
    val spread = 11f + 8f * sqp
    for (s in floatArrayOf(-1f, 1f)) {
        val bx = fx + s * spread
        val kneeX = fx + s * (spread + 3f + 6f * sqp); val kneeY = (hip + fy - 12f) / 2f
        path.reset()
        path.moveTo(fx + s * 2f, hip - 4f)
        path.lineTo(fx + s * 15f * squashX, hip - 4f)
        path.quadraticTo(kneeX + s * 8f, kneeY, bx + s * 9f, fy - 12f)
        path.lineTo(bx - s * 9f, fy - 12f)
        path.quadraticTo(kneeX - s * 6f, kneeY, fx + s * 2f, hip - 4f)
        path.close()
        drawPath(path, DENIM)
        drawLine(DENIM_DK, Offset(fx + s * 8f, hip - 2f), Offset(kneeX, kneeY), 1f)          // the side seam
        drawLine(DENIM_DK, Offset(kneeX, kneeY), Offset(bx, fy - 13f), 1f)
        drawRoundRect(DENIM_LT, Offset(bx - 10f, fy - 15f), Size(20f, 4f), CornerRadius(2f))  // the stack
    }
    // Timbs: chunky wheat boots pointing his way, laces, padded collar, a dark lug sole
    for (s in floatArrayOf(-1f, 1f)) {
        val bx = fx + s * spread
        path.reset()
        path.moveTo(bx - 8f, fy - 12f)
        path.lineTo(bx + 6f, fy - 12f)
        path.quadraticTo(bx + 8f, fy - 8f, bx + 13f, fy - 6f)
        path.quadraticTo(bx + 16f, fy - 4f, bx + 15f, fy - 2f)
        path.lineTo(bx - 9f, fy - 2f)
        path.quadraticTo(bx - 10f, fy - 8f, bx - 8f, fy - 12f)
        path.close()
        drawPath(path, TIMB)
        drawRoundRect(TIMB_SOLE, Offset(bx - 10f, fy - 3f), Size(26f, 4f), CornerRadius(1.5f))
        drawLine(TIMB_DARK, Offset(bx - 1f, fy - 11f), Offset(bx + 4f, fy - 7f), 1.2f)
        drawLine(TIMB_DARK, Offset(bx + 1f, fy - 12f), Offset(bx + 6f, fy - 8f), 1f)
        drawRoundRect(Color(0xFF6B4A1C), Offset(bx - 9f, fy - 14f), Size(16f, 3f), CornerRadius(1.5f))
    }

    // the hoodie: boxy with dropped shoulders, a ribbed hem, a kangaroo pocket, the hood bunched at the neck
    val hw = torsoW / 2f; val hemW = hw * 0.92f
    path.reset()
    path.moveTo(fx - hemW, hip)
    path.lineTo(fx - hw - 1f, shoulderY + 12f)
    path.quadraticTo(fx - hw, shoulderY, fx - hw + 10f, shoulderY)
    path.lineTo(fx + hw - 10f, shoulderY)
    path.quadraticTo(fx + hw, shoulderY, fx + hw + 1f, shoulderY + 12f)
    path.lineTo(fx + hemW, hip)
    path.close()
    drawPath(path, HOOD)
    drawRoundRect(HOOD_DK, Offset(fx - hemW, hip - 5f), Size(hemW * 2f, 6f), CornerRadius(2f))
    for (i in 1 until 8) { val rx = fx - hemW + i * hemW / 4f; drawLine(Color.Black.copy(alpha = 0.25f), Offset(rx, hip - 4f), Offset(rx, hip), 0.6f) }
    val pocketTop = hip - 5f - torsoH * 0.36f
    path.reset()
    path.moveTo(fx - hw * 0.55f, hip - 5f); path.lineTo(fx - hw * 0.42f, pocketTop)
    path.lineTo(fx + hw * 0.42f, pocketTop); path.lineTo(fx + hw * 0.55f, hip - 5f)
    path.close()
    drawPath(path, HOOD_LT)
    drawOval(HOOD_DK, Offset(fx - 16f, shoulderY - 6f), Size(30f, 12f))
    // headphones round his neck: the band under his chin, the cup bounces on its own spring
    val cupY = shoulderY - 1f + pose.cupDy * 0.08f
    drawArc(INK, 8.6f, 162.8f, false, Offset(headX - 17f, shoulderY - 8f), Size(30f, 12f), style = Stroke(2.4f, cap = StrokeCap.Round))
    drawRoundRect(Tb.Tempo, Offset(headX + 9f, cupY - 3f), Size(7f, 11f), CornerRadius(3f))
    drawRoundRect(INK, Offset(headX + 9f, cupY - 3f), Size(7f, 11f), CornerRadius(3f), style = Stroke(1f))
    // a gold chain and the drawstrings; the pendant swings behind his moves (off the ears' spring)
    val swing = pose.ear * 0.10f
    path.reset()
    path.moveTo(fx - 11f, shoulderY + 3f); path.quadraticTo(fx + swing, shoulderY + 20f, fx + 11f, shoulderY + 3f)
    drawPath(path, GOLD, style = Stroke(1.6f, cap = StrokeCap.Round))
    drawCircle(GOLD, 2.6f, Offset(fx + swing, shoulderY + 15f))
    drawCircle(Color(0xFFB88A1E), 1.2f, Offset(fx + swing, shoulderY + 15f))
    drawLine(Color(0xFFECECEC), Offset(fx - 4f, shoulderY + 2f), Offset(fx - 5f + swing, shoulderY + 12f), 1.1f, cap = StrokeCap.Round)
    drawLine(Color(0xFFECECEC), Offset(fx + 4f, shoulderY + 2f), Offset(fx + 3f + swing, shoulderY + 12f), 1.1f, cap = StrokeCap.Round)

    // arms in sleeves up to the slice: shoulders → elbows (out wider under the weight) → paws under it
    val sliceY = headY - 33f + 9f * sqp - 4f * (-sq).coerceAtLeast(0f)
    val elbowOut = 14f + 10f * sqp
    for (s in floatArrayOf(-1f, 1f)) {
        val sx = fx + s * (hw - 4f); val sy = shoulderY + 6f
        val ex = fx + s * (hw + elbowOut); val ey = (sy + sliceY) / 2f + 4f + 6f * sqp
        val px = fx + s * 20f; val py = sliceY + 4f + s * pose.tilt * 0.12f
        drawLine(HOOD, Offset(sx, sy), Offset(ex, ey), 10f, cap = StrokeCap.Round)
        drawLine(HOOD, Offset(ex, ey), Offset(px, py + 5f), 8.5f, cap = StrokeCap.Round)
        val cx = px + (ex - px) * 0.12f; val cy = py + 5f + (ey - py - 5f) * 0.12f                 // the cuff
        drawLine(HOOD_DK, Offset(cx, cy), Offset(cx + (ex - px) * 0.08f, cy + (ey - py) * 0.08f), 8.5f, cap = StrokeCap.Round)
        val mx = sx + (ex - sx) * 0.55f; val my = sy + (ey - sy) * 0.55f                             // the theme stripe
        drawLine(Tb.Key, Offset(mx - (ex - sx) * 0.07f, my - (ey - sy) * 0.07f), Offset(mx + (ex - sx) * 0.07f, my + (ey - sy) * 0.07f), 10f)
        drawLine(Color.Black.copy(alpha = 0.3f), Offset(ex - s * 2f, ey - 3f), Offset(ex + s, ey + 2f), 0.8f)   // elbow crease
        drawCircle(PINK, 3.6f, Offset(px, py))
    }

    // ears: big and round, poking out under the cap; they droop under the weight and flop back a beat late
    for ((k, er) in listOf(-1f to 9f, 1f to 10f)) {
        val ex = headX + (if (k < 0f) -13f else 7f)
        val earY = headY - 12f + sqp * 2f
        rotate(k * pose.ear * 0.9f, Offset(ex, earY + er * 0.6f)) {
            drawCircle(FUR_DARK, er, Offset(ex, earY))
            drawCircle(PINK, er - 3.2f, Offset(ex, earY))
        }
    }
    drawCircle(FUR, 15f, Offset(headX, headY))
    // a fitted, worn backwards: the crown over his head, the brim out the back, a button in the theme colour
    drawArc(CAP, 183.6f, 172.8f, false, Offset(headX - 16.5f, headY - 17f), Size(31f, 22f))
    drawRect(CAP, Offset(headX - 16.5f, headY - 7.5f), Size(31f, 2.5f))
    path.reset()
    path.moveTo(headX - 14f, headY - 7f)
    path.quadraticTo(headX - 27f, headY - 9f, headX - 30f, headY - 3f)
    path.quadraticTo(headX - 22f, headY - 3f, headX - 13f, headY - 3.5f)
    path.close()
    drawPath(path, CAP)
    drawLine(Color(0xFF2E3D61), Offset(headX - 1f, headY - 17f), Offset(headX - 1f, headY - 7f), 0.8f)
    drawCircle(Tb.Key, 1.6f, Offset(headX - 1f, headY - 17f))
    // the snout (sniffs when it's quiet), whiskers, buck teeth
    path.reset()
    val sn = pose.sniff * 1.2f
    path.moveTo(headX + 8f, headY - 7f)
    path.quadraticTo(headX + 27f + sn, headY - 2f, headX + 26f + sn, headY + 3f)
    path.quadraticTo(headX + 18f, headY + 8f, headX + 6f, headY + 7f)
    path.close()
    drawPath(path, FUR)
    drawCircle(PINK, 3.2f, Offset(headX + 26f + sn, headY + 1.5f))
    for (k in -1..1) drawLine(INK.copy(alpha = 0.55f), Offset(headX + 20f, headY + 3f),
        Offset(headX + 34f, headY + 1f + k * 4f + sqp * 2f + pose.sniff * 1.5f * k), 0.8f)
    drawLine(Color.White, Offset(headX + 20f, headY + 7f), Offset(headX + 20f, headY + 10f), 2.2f)
    // eye: straining under a heavy weight; looking up at the slice as he braces; blinking otherwise
    val ex0 = headX + 8f; val ey0 = headY - 3f - 1.5f * pose.brace
    if (sq > 0.55f) {
        drawLine(INK, Offset(ex0 - 3f, ey0 - 2f), Offset(ex0 + 2f, ey0 + 0.5f), 1.8f, cap = StrokeCap.Round)
        drawLine(INK, Offset(ex0 + 2f, ey0 + 0.5f), Offset(ex0 - 3f, ey0 + 3f), 1.8f, cap = StrokeCap.Round)
    } else if (pose.blink > 0.5f) {
        drawLine(INK, Offset(ex0 - 3f, ey0), Offset(ex0 + 3f, ey0), 1.6f, cap = StrokeCap.Round)
    } else {
        drawOval(INK, Offset(ex0 - 3f, ey0 - 3f * (1f - pose.blink)), Size(6f, 6f * (1f - pose.blink)))
        drawCircle(Color.White, 1f, Offset(ex0 + 1f, ey0 - 1f - pose.brace))
    }
    // sweat when it's heavy
    if (sq > 0.6f) {
        val sy = headY - 14f + ((now * 1.8f) % 1f) * 12f
        drawCircle(Color(0xFF9FD8FF), 2.2f, Offset(headX - 15f, sy))
    }

    // the slice, held flat overhead like a barbell; it wobbles (a spring) after the weight lands
    rotate(pose.tilt, Offset(fx, sliceY)) {
        path.reset()
        path.moveTo(fx - 36f, sliceY + 1f)
        path.lineTo(fx + 30f, sliceY - 10f)
        path.lineTo(fx + 32f, sliceY + 9f)
        path.close()
        drawPath(path, CHEESE)
        drawRoundRect(CRUST, Offset(fx + 27f, sliceY - 12f), Size(9f, 23f), CornerRadius(3.5f))
        for ((px, py) in listOf(fx - 14f to sliceY + 1f, fx + 2f to sliceY - 1.5f, fx + 16f to sliceY + 1.5f))
            drawCircle(PEP, 2.6f, Offset(px, py))
        // a cheese drip that stretches as he sinks and swings with the wobble
        if (sq > 0.2f) drawLine(CHEESE, Offset(fx - 6f, sliceY + 2f), Offset(fx - 6f - pose.tilt * 0.15f, sliceY + 4f + 10f * sq), 2f, cap = StrokeCap.Round)
    }
    return sliceY
}
