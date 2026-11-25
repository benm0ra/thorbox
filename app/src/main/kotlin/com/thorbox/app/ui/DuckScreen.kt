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
 * back up on the BOUNCE (release) curve. Drawn in the house style (GraffKit.kt, docs/ART_STYLE.md); the
 * crew fit: a cascade hoodie, baggy denim stacked on wheat Timbs, a backwards fitted, a gold chain,
 * silver headphones round his neck.
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
    val sign = remember(dens) { TextStyle(color = Color.White, fontSize = unitSp(17f), fontFamily = Anton, letterSpacing = unitSp(0.5f)) }
    val bullet = remember(dens) { TextStyle(color = Color.White, fontSize = unitSp(12f), fontFamily = Anton) }
    val tag = remember(dens) { TextStyle(fontSize = unitSp(12f), fontFamily = Anton, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
    val boom = remember(dens) { TextStyle(fontSize = unitSp(11f), fontFamily = Anton) }
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
        Graff.begin(density, sc)                                                 // house-style line weights for this scale
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            // the platform shakes when the weight lands (a fast, decaying wobble)
            val shake = if (fxSince in 0f..0.35f) 2.6f * hit * exp(-fxSince * 14f) * sin(fxSince * 95f) else 0f
            translate(0f, shake) {
                platform(now, tm, sign, bullet, tag, d.source)
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
            // on a dark pill mid-wall (clear of the station sign)
            val tl = Offset((size.width - t.size.width) / 2f, size.height * 0.42f - t.size.height / 2f)
            val pad = 12.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.7f), tl - Offset(pad, pad * 0.6f), Size(t.size.width + 2 * pad, t.size.height + 1.2f * pad), CornerRadius(pad))
            drawText(t, topLeft = tl)
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

// the rat's own colours (the world's colours are Line79's)
private val FUR = Color(0xFF9C99A6)
private val FUR_DK = Color(0xFF6E6B7A)
private val PINK = Color(0xFFF2A0B0)
private val PINK_DK = Color(0xFFCF7D90)
private val CAP = Color(0xFF262833)
private val CAP_DK = Color(0xFF15161E)
private val CHEESE = Color(0xFFF7C948)
private val CHEESE_DK = Color(0xFFD9A126)
private val CRUST = Color(0xFFC9792F)
private val CRUST_DK = Color(0xFF9C5A20)
private val PEP = Color(0xFFC63A2E)
private val CONCRETE = Color(0xFF8F8B83)
private val ARCH = Color(0xFF4A433D)
private val HEADLIGHT = Color(0xFFFFF2B0)
private val DUST = Color(0xFFE2DACB)
private val TILE = Line79.TILE
private val INK = Line79.INK

/** Tiled wall with two marker tags, the station band (DUCK ST + a route bullet), the platform, the tunnel. */
private fun DrawScope.platform(now: Float, tm: androidx.compose.ui.text.TextMeasurer, sign: TextStyle, bullet: TextStyle, tag: TextStyle, source: Int) {
    val kl = Graff.kl * 0.6f                                                // scenery: thin keylines
    // running-bond subway tile
    var y = 0f; var row = 0
    while (y < 150f) {
        drawLine(Line79.TILE_DK, Offset(-60f, y), Offset(300f, y), 0.7f)
        var x = if (row % 2 == 0) -60f else -52f
        while (x < 300f) { drawLine(Line79.TILE_DK, Offset(x, y), Offset(x, y + 8f), 0.7f); x += 16f }
        y += 8f; row++
    }
    drawRect(INK.copy(alpha = 0.12f), Offset(-60f, 48f), Size(360f, 5f))              // the band's shadow on the tiles
    // the station band: red trim, the black band, a route bullet, the name in Anton
    inkRect(-62f, 14f, 364f, 34f, Line79.RED, kl)
    inkRect(-62f, 18f, 364f, 26f, INK, kl)
    val routeCol = when (source) { DuckSettings.BEAT -> Color(0xFF2E8BD6); -1 -> Color(0xFF808080); else -> Line79.CASCADE }
    inkCircle(30f, 31f, 9f, routeCol, w = kl)
    val bl = tm.measure(if (source == DuckSettings.BEAT) "B" else "K", bullet)
    drawText(bl, topLeft = Offset(30f - bl.size.width / 2f, 31f - bl.size.height / 2f))
    val st = tm.measure("DUCK ST", sign)
    drawText(st, topLeft = Offset(45f, 31f - st.size.height / 2f))
    // the regulars' marker tags (outlined strokes, a drip off each)
    tagAt(TAG_NITE, tm, "NITE", tag, 132f, 27f, Line79.SILVER, 0.8f)
    tagAt(TAG_RAT, tm, "RAT 79", tag, -24f, 70f, INK, 1f)
    // the tunnel: an arched portal (stone, keylined), a signal lamp inside, a train's lights now and then
    val tx = 198f; val tw = 76f; val ty = 66f; val tr = tw / 2f
    archPath(Graff.a, tx, ty, tw, 6f); ink(Graff.a, ARCH, kl)
    archPath(Graff.a, tx, ty, tw, 0f); ink(Graff.a, Line79.NIGHT_DK, kl)
    for (k in 1 until 8) {                                                  // the voussoir joints
        val a = PI.toFloat() * (1f + k / 8f); val c = kotlin.math.cos(a); val sn = sin(a)
        drawLine(INK, Offset(tx + tr + c * tr, ty + tr + sn * tr), Offset(tx + tr + c * (tr + 6f), ty + tr + sn * (tr + 6f)), kl)
    }
    inkCircle(tx + 10f, ty + 44f, 3.4f, Color(0xFF2A2A2A), w = kl * 0.8f)
    drawCircle(if ((now * 0.7f) % 1f < 0.5f) Line79.RED else Color(0xFF5A2420), 2f, Offset(tx + 10f, ty + 44f))
    val sweep = ((now * 0.12f) % 1f)
    if (sweep < 0.25f) {
        val a = sin(sweep / 0.25f * PI.toFloat())
        drawRect(Brush.radialGradient(listOf(HEADLIGHT.copy(alpha = 0.35f * a), HEADLIGHT.copy(alpha = 0f)), Offset(tx + tr, 128f), 34f),
            Offset(tx, 94f), Size(tw, 56f))
        drawCircle(HEADLIGHT.copy(alpha = 0.9f * a), 4f, Offset(tx + tr - 10f, 126f))
        drawCircle(HEADLIGHT.copy(alpha = 0.9f * a), 4f, Offset(tx + tr + 10f, 126f))
    }
    // the platform, its tactile yellow edge, the drop to the tracks
    inkRect(-62f, 150f, 364f, 30f, CONCRETE, kl)
    drawRect(Color(0xFFA8A49B), Offset(-60f, 151f), Size(360f, 3f))
    inkRect(-62f, 178f, 364f, 7f, Line79.YELLOW, kl)
    var dx = -56f
    while (dx < 300f) { drawCircle(Color(0xFFB8921A), 1.05f, Offset(dx, 181.5f)); dx += 5f }
    drawRect(Line79.NIGHT_DK, Offset(-60f, 185.5f), Size(360f, 20f))
    drawLine(Line79.SILVER_DK, Offset(-60f, 194f), Offset(300f, 194f), 1.6f)
    // the grate the steam comes up through
    inkRRect(22f, 160f, 40f, 9f, 1.5f, Color(0xFF5A5751), w = kl)
    for (i in 0 until 7) drawLine(INK, Offset(25f + i * 5.5f, 161f), Offset(25f + i * 5.5f, 168f), 1.2f)
}

/** The tunnel mouth: walls up to [ty]+r, a half-circle over the top, [inset] grown all round (for the stone ring). */
private fun archPath(p: Path, tx: Float, ty: Float, tw: Float, inset: Float) {
    val r = tw / 2f
    p.reset()
    p.moveTo(tx - inset, 150f)
    p.lineTo(tx - inset, ty + r)
    p.arcTo(androidx.compose.ui.geometry.Rect(tx - inset, ty - inset, tx + tw + inset, ty + tw + inset), 180f, 180f, false)
    p.lineTo(tx + tw + inset, 150f)
    p.close()
}

/** A marker tag: the word as a slanted outline stroke (the tag style is fake-italic Anton), drips off two downstrokes. */
private fun DrawScope.tagAt(cache: PieceCache, tm: androidx.compose.ui.text.TextMeasurer, word: String, style: TextStyle, x: Float, y: Float, c: Color, alpha: Float) {
    val t = tm.measure(word, style)
    val th = t.size.height.toFloat(); val pad = th * 0.1f
    // painted once into a bitmap (a stroked word every frame is wasted work)
    cachedArt(cache, word.hashCode().toLong() * 31 + c.value.toLong() + (th * 10f).toLong(), t.size.width + pad * 2f, th * 1.5f + pad * 2f, Graff.scale, x - pad, y - pad) {
        drawText(t, c, Offset(pad, pad), alpha = alpha, drawStyle = Stroke(th * 0.08f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val base = pad + th * 0.86f
        drip(pad + t.size.width * 0.2f, base, th * 0.45f, th * 0.06f, c.copy(alpha = alpha))
        drip(pad + t.size.width * 0.72f, base, th * 0.28f, th * 0.05f, c.copy(alpha = alpha))
    }
}
private val TAG_NITE = PieceCache()
private val TAG_RAT = PieceCache()

/** A silver boombox on the platform; the woofers push out with the key (a spring, so they flex past and back). */
private fun DrawScope.boombox(cone: Float) {
    val x = 156f; val y = 150f
    limb(x + 6f, y - 4f, x + 34f, y - 4f, 2f, Line79.SILVER, Line79.SILVER_DK)            // handle
    inkRRect(x, y, 40f, 22f, 3f, Line79.SILVER, Line79.SILVER_DK, 2.2f)
    inkRRect(x + 15f, y + 3f, 10f, 6f, 1f, Line79.NIGHT, w = Graff.kl * 0.6f)              // tape deck
    for (cx in floatArrayOf(x + 8f, x + 32f)) {
        inkCircle(cx, y + 14f, 6.5f + cone * 0.8f, INK, w = Graff.kl * 0.6f)
        drawCircle(Color(0xFF55555E), (2.6f + cone * 1.1f).coerceAtLeast(1f), Offset(cx, y + 14f))
    }
    sparkle(x + 6f, y + 3f, 2.4f)
}

/** An NYC pigeon perched on the boombox: head-bobs like pigeons do (a jerk forward, a hold), and startles
 *  on a big hit: a flap and a hop (a spring) that lands back on the box. */
private fun DrawScope.pigeon(hop: Float, sinceStartle: Float, now: Float) {
    val x = 182f; val y = 143f + hop
    val flap = if (sinceStartle in 0f..0.5f) sin(sinceStartle / 0.5f * PI.toFloat() * 4f) * (1f - sinceStartle / 0.5f) else 0f
    val bp = (now * 1.6f) % 1f
    val bob = if (bp < 0.12f) -3f * smooth01(bp / 0.12f) else if (bp < 0.55f) -3f else -3f * (1f - smooth01((bp - 0.55f) / 0.12f))
    val kl = Graff.kl * 0.6f
    limb(x - 3f, y + 4f, x - 3f, 150f, 1f, Color(0xFFE07A5F)); limb(x + 2f, y + 4f, x + 2f, 150f, 1f, Color(0xFFE07A5F))
    inkOval(x + 3f, y - 3.5f, 9f, 5f, Color(0xFF5E6472), w = kl)                          // tail
    inkOval(x - 9f, y - 4f, 16f, 9f, Color(0xFF8D93A0), Color(0xFF6E7482), 1.6f, kl)       // body
    rotate(-20f - flap * 70f, Offset(x - 2f, y - 2f)) { inkOval(x - 6f, y - 4f, 11f, 5f, Color(0xFF6E7482), w = kl) }
    drawCircle(Color(0xFF5DA28C), 3.2f, Offset(x - 8f, y - 4f))                            // the green-purple neck
    drawCircle(Color(0xFF8A5BA8).copy(alpha = 0.6f), 2.2f, Offset(x - 7f, y - 3f))
    inkCircle(x - 11f + bob, y - 8f, 3.4f, Color(0xFF8D93A0), w = kl)                      // head
    drawCircle(Color(0xFFF08A24), 0.9f, Offset(x - 12f + bob, y - 9f))
    drawLine(INK, Offset(x - 14f + bob, y - 7.5f), Offset(x - 16.5f + bob, y - 6.8f), 1.3f)
}

/**
 * Steam off the grate: a lazy wisp that's always drifting up, and on each kick a keylined cloud pushed
 * through, as big as the kick. [since] < 0 = no kick to show.
 */
private fun DrawScope.steam(now: Float, since: Float, hit: Float) {
    for (k in 0 until 4) {
        val t = (now * 0.35f + k * 0.25f) % 1f
        val a = 0.16f * sin(t * PI.toFloat())
        drawCircle(Color.White.copy(alpha = a), 4f + t * 9f, Offset(34f + k * 6f + sin(now * 1.3f + k * 2f) * 4f * t, 160f - t * 40f))
    }
    if (since !in 0f..1.1f) return
    val t = since / 1.1f
    val grow = 0.35f + t * (0.8f + 0.6f * hit)
    val a = (1f - smooth01((t - 0.35f) / 0.65f)) * (0.6f + 0.4f * hit)
    val p = Graff.c; p.reset()
    for (i in CLOUD.indices step 3) {
        val cx = 42f + CLOUD[i] * (1f + t); val cy = 156f + CLOUD[i + 1] * (1f + t) - t * 34f; val r = CLOUD[i + 2] * grow
        p.addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
    }
    drawPath(p, INK, alpha = a, style = Stroke(Graff.kl * 1.2f))
    drawPath(p, Line79.WHITE, alpha = a)
}
/** The steam cloud's puffs: x, y, radius. */
private val CLOUD = floatArrayOf(0f, 0f, 9f, 10f, -6f, 8f, -8f, -9f, 7f, 4f, -16f, 8f, 14f, -20f, 6f)

/** Dust kicked out along the platform at his feet when the weight lands: keylined puffs rolling outward. */
private fun DrawScope.dust(fx: Float, fy: Float, since: Float, hit: Float) {
    if (since !in 0f..0.55f) return
    for (s in floatArrayOf(-1f, 1f)) for (k in 0 until 3) {
        val t = since - k * 0.03f
        if (t < 0f) continue
        val p = smooth01(t / 0.55f)
        val a = ((1f - t / 0.55f) * 0.9f * hit).coerceIn(0f, 1f)
        val r = 2.5f + p * (6f + k * 2f); val cx = fx + s * (14f + p * (30f + k * 10f)); val cy = fy - 2f - p * (5f + k * 3f)
        drawCircle(INK.copy(alpha = a), r + Graff.kl * 0.3f, Offset(cx, cy))
        drawCircle(DUST.copy(alpha = a), r - Graff.kl * 0.3f, Offset(cx, cy))
    }
}

/** A comic BOOM burst on the slice as it lands: red star with a yellow core, hard block, pops past full size, fades. */
private fun DrawScope.burst(cx: Float, cy: Float, since: Float, hit: Float, star: Path, tm: androidx.compose.ui.text.TextMeasurer, style: TextStyle) {
    if (since !in 0f..0.28f || hit < 0.15f) return
    val t = since / 0.28f
    val sc = 0.55f + 0.6f * smooth01(t * 2.2f)
    val a = 1f - smooth01((t - 0.45f) / 0.55f)
    withTransform({ translate(cx, cy); scale(sc, sc, Offset.Zero); rotate(-7f, Offset.Zero) }) {
        starPath(star, 25f, 14f)
        translate(3f, 3f) { drawPath(star, INK, alpha = a) }
        drawPath(star, Line79.RED, alpha = a); drawPath(star, INK, alpha = a, style = Stroke(Graff.kl))
        starPath(star, 20f, 11f); drawPath(star, Line79.YELLOW, alpha = a)
        val l = tm.measure("BOOM", style)
        val o = Offset(-l.size.width / 2f, -l.size.height / 2f)
        drawText(l, INK, o + Offset(1.2f, 1.2f), alpha = a)
        drawText(l, INK, o, alpha = a, drawStyle = Stroke(l.size.height * 0.16f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawText(l, Line79.WHITE, o, alpha = a)
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
 * The rat, in the house style. [RatPose.sq] 0 = standing tall, slice held high · 1 = squatting under it ·
 * below 0 = stretched taller than rest (the overshoot on the way back up). Feet at (110, 178).
 * His knees bend and his stance widens as he sinks; the hoodie takes the rest of the squash.
 * The crew fit: a cascade hoodie, baggy denim stacked on Timbs, a backwards fitted, a gold chain,
 * silver headphones round his neck. [path] is scratch. Returns where the slice is (y), for the burst.
 */
private fun DrawScope.rat(pose: RatPose, now: Float, path: Path, tail: Path): Float {
    val kl = Graff.kl
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
    limbPath(tail, 3.2f, PINK, PINK_DK)

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
        inkCel(path, Line79.DENIM, Line79.DENIM_DK, 2.6f)
        drawLine(Line79.DENIM_DK, Offset(kneeX, kneeY), Offset(bx, fy - 13f), kl * 0.5f)      // the side seam
        inkRRect(bx - 10f, fy - 15.5f, 20f, 4.5f, 2f, Line79.DENIM_LT, w = kl * 0.8f)          // the stack
    }
    // Timbs: chunky wheat boots pointing his way, a padded collar, a dark lug sole, a shine on the toe
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
        inkCel(path, Line79.TIMB, Line79.TIMB_DK, 2f)
        inkRRect(bx - 10f, fy - 3f, 26f, 4f, 1.5f, Line79.SOLE, w = kl * 0.8f)
        inkRRect(bx - 9f, fy - 14f, 16f, 3.2f, 1.5f, Color(0xFF6B4A1C), w = kl * 0.7f)
        drawLine(INK, Offset(bx - 1f, fy - 11f), Offset(bx + 4f, fy - 7f), kl * 0.45f, cap = StrokeCap.Round)
        shine(bx + 7f, fy - 7f, 3f, -20f, kl * 0.6f)
    }

    // the hoodie, in the crew's cascade: boxy, a ribbed hem, a kangaroo pocket, the hood at the neck
    val hw = torsoW / 2f; val hemW = hw * 0.92f
    path.reset()
    path.moveTo(fx - hemW, hip)
    path.lineTo(fx - hw - 1f, shoulderY + 12f)
    path.quadraticTo(fx - hw, shoulderY, fx - hw + 10f, shoulderY)
    path.lineTo(fx + hw - 10f, shoulderY)
    path.quadraticTo(fx + hw, shoulderY, fx + hw + 1f, shoulderY + 12f)
    path.lineTo(fx + hemW, hip)
    path.close()
    inkCel(path, Line79.CASCADE, Line79.CASCADE_DK, 4f)
    inkRRect(fx - hemW, hip - 5f, hemW * 2f, 6f, 2f, Line79.CASCADE_DK, w = kl * 0.8f)
    val pocketTop = hip - 5f - torsoH * 0.36f
    path.reset()
    path.moveTo(fx - hw * 0.55f, hip - 5f); path.lineTo(fx - hw * 0.42f, pocketTop)
    path.lineTo(fx + hw * 0.42f, pocketTop); path.lineTo(fx + hw * 0.55f, hip - 5f)
    path.close()
    ink(path, Line79.CASCADE_LT, kl * 0.6f)
    inkOval(fx - 16f, shoulderY - 6f, 30f, 12f, Line79.CASCADE_DK, w = kl * 0.8f)
    // headphones round his neck: the band under his chin, a silver cup that bounces on its own spring
    drawArc(INK, 8.6f, 162.8f, false, Offset(headX - 17f, shoulderY - 8f), Size(30f, 12f), style = Stroke(2.4f + kl, cap = StrokeCap.Round))
    drawArc(Line79.SILVER, 8.6f, 162.8f, false, Offset(headX - 17f, shoulderY - 8f), Size(30f, 12f), style = Stroke(2.4f, cap = StrokeCap.Round))
    val cupY = shoulderY - 1f + pose.cupDy * 0.08f
    inkRRect(headX + 9f, cupY - 3f, 7f, 11f, 3f, Line79.SILVER, Line79.SILVER_DK, 1.4f)
    // a gold chain; the pendant swings behind his moves (off the ears' spring)
    val swing = pose.ear * 0.10f
    path.reset()
    path.moveTo(fx - 11f, shoulderY + 3f); path.quadraticTo(fx + swing, shoulderY + 20f, fx + 11f, shoulderY + 3f)
    limbPath(path, 1.6f, Line79.GOLD)
    inkCircle(fx + swing, shoulderY + 15f, 2.8f, Line79.GOLD, w = kl * 0.7f)
    sparkle(fx + swing - 1f, shoulderY + 14f, 2.6f)

    // arms in sleeves up to the slice: shoulders → elbows (out wider under the weight) → paws under it
    val sliceY = headY - 33f + 9f * sqp - 4f * (-sq).coerceAtLeast(0f)
    val elbowOut = 14f + 10f * sqp
    for (s in floatArrayOf(-1f, 1f)) {
        val sx = fx + s * (hw - 4f); val sy = shoulderY + 6f
        val ex = fx + s * (hw + elbowOut); val ey = (sy + sliceY) / 2f + 4f + 6f * sqp
        val px = fx + s * 20f; val py = sliceY + 4f + s * pose.tilt * 0.12f
        path.reset(); path.moveTo(sx, sy); path.lineTo(ex, ey); path.lineTo(px, py + 5f)
        limbPath(path, 9f, Line79.CASCADE, Line79.CASCADE_DK)
        inkCircle(px, py, 3.8f, PINK, w = kl * 0.8f)
    }

    // ears: big and round under the cap; they droop under the weight and flop back a beat late
    for (k in 0..1) {
        val sd = if (k == 0) -1f else 1f; val er = if (k == 0) 9f else 10f
        val ex = headX + (if (k == 0) -13f else 7f); val ey = headY - 12f + sqp * 2f
        rotate(sd * pose.ear * 0.9f, Offset(ex, ey + er * 0.6f)) {
            inkCircle(ex, ey, er, FUR, FUR_DK, 2f)
            drawCircle(PINK, er - 3.6f, Offset(ex + 0.5f, ey + 0.5f))
        }
    }
    // head and snout: one keylined silhouette (outline both, then fill both, so there's no seam)
    val sn = pose.sniff * 1.2f
    path.reset()
    path.moveTo(headX + 6f, headY - 8f)
    path.quadraticTo(headX + 27f + sn, headY - 3f, headX + 26f + sn, headY + 3f)
    path.quadraticTo(headX + 18f, headY + 9f, headX + 4f, headY + 8f)
    path.close()
    drawCircle(INK, 15f, Offset(headX, headY), style = Stroke(kl * 2f)); keyline(path, kl * 2f)
    drawCircle(FUR_DK, 15f, Offset(headX, headY)); drawCircle(FUR, 15f - 1.9f, Offset(headX - 1.5f, headY - 1.2f))
    cel(path, FUR, FUR_DK, 2f)
    inkCircle(headX + 26f + sn, headY + 1.5f, 3.4f, PINK, w = kl * 0.8f)
    shineDot(headX + 25f + sn, headY + 0.2f, 0.9f)
    for (k in -1..1) drawLine(INK, Offset(headX + 21f, headY + 4f), Offset(headX + 35f, headY + 2f + k * 4f + sqp * 2f + pose.sniff * 1.5f * k), kl * 0.35f)
    inkRRect(headX + 18.5f, headY + 7.5f, 3.6f, 4f, 0.8f, Line79.WHITE, w = kl * 0.5f)      // buck tooth
    // the crew eye: chill; heavier lid when it's heavy, looking up at the slice as he braces
    crewEye(headX + 8f, headY - 3f - 1.5f * pose.brace, 9f, 8.5f, FUR, if (sq > 0.55f) 0.62f else 0.4f, 0.7f, pose.blink)
    // a fitted worn backwards: the brim out the back, the crown, a silver button, a shine
    path.reset()
    path.moveTo(headX - 13f, headY - 7.5f)
    path.quadraticTo(headX - 27f, headY - 9.5f, headX - 31f, headY - 3f)
    path.quadraticTo(headX - 22f, headY - 2.5f, headX - 13f, headY - 3.5f)
    path.close()
    inkCel(path, CAP, CAP_DK, 1.5f)
    path.reset()
    path.arcTo(androidx.compose.ui.geometry.Rect(headX - 16.5f, headY - 17f, headX + 14.5f, headY + 5f), 183.6f, 172.8f, true)
    path.lineTo(headX + 14.5f, headY - 5f); path.lineTo(headX - 16.5f, headY - 5f); path.close()
    inkCel(path, CAP, CAP_DK, 2.2f)
    inkCircle(headX - 1f, headY - 17f, 1.8f, Line79.SILVER, w = kl * 0.6f)
    shine(headX - 9f, headY - 12f, 4f, -50f, kl * 0.7f)
    // sweat when it's heavy
    if (sq > 0.6f) {
        val yy = headY - 14f + ((now * 1.8f) % 1f) * 12f
        path.reset(); path.moveTo(headX - 15f, yy - 3f)
        path.quadraticTo(headX - 12f, yy + 1f, headX - 15f, yy + 2f); path.quadraticTo(headX - 18f, yy + 1f, headX - 15f, yy - 3f); path.close()
        ink(path, Color(0xFFA8DCFF), kl * 0.5f)
    }

    // the slice, held flat overhead like a barbell; it wobbles (a spring) after the weight lands
    rotate(pose.tilt, Offset(fx, sliceY)) {
        path.reset()
        path.moveTo(fx - 36f, sliceY + 1f); path.lineTo(fx + 30f, sliceY - 10f); path.lineTo(fx + 32f, sliceY + 9f); path.close()
        inkCel(path, CHEESE, CHEESE_DK, 2f)
        inkRRect(fx + 27f, sliceY - 12f, 9f, 23f, 3.5f, CRUST, CRUST_DK, 1.5f)
        inkCircle(fx - 14f, sliceY + 1f, 2.7f, PEP, w = kl * 0.5f)
        inkCircle(fx + 2f, sliceY - 1.5f, 2.7f, PEP, w = kl * 0.5f)
        inkCircle(fx + 16f, sliceY + 1.5f, 2.7f, PEP, w = kl * 0.5f)
        // a cheese drip that stretches as he sinks and swings with the wobble
        if (sq > 0.2f) limb(fx - 6f, sliceY + 2f, fx - 6f - pose.tilt * 0.15f, sliceY + 4f + 10f * sq, 2f, CHEESE)
    }
    return sliceY
}
