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
        SQUASH -> if (d.ratio >= 19.95f) "∞:1" else String.format(Locale.US, if (d.ratio < 10f) "%.1f:1" else "%.0f:1", d.ratio)
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
        if (cBot - cTop > 20f) {
            val span = cBot - cTop
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
            drawText(tm.measure("BOUNCE (last 3 s)", small), topLeft = Offset(0f, cBot - 12.dp.toPx()))
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
 * every kick drops its weight on him and he squashes exactly as far as the mix ducks; then he pushes
 * it back up on the BOUNCE (release) curve. Between kicks he nods to the tempo. Fresh Timbs, big
 * headphones, a hoodie in the theme's colour; the boombox thumps with the key, the grate puffs steam
 * on every trigger, and the train's lights sweep through the tunnel behind him.
 */
@Composable
fun SubwayRat(state: AppState, modifier: Modifier) {
    val g = state.groove
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 16)
    val sign = remember { TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
    val bullet = remember { TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black) }
    val boom = remember { TextStyle(color = Color(0xFFFFE27A), fontSize = 12.sp, fontWeight = FontWeight.Black) }
    val rs = remember { RatState() }
    val slice = remember { Path() }; val tail = remember { Path() }
    Canvas(modifier) {
        tel.frame.longValue
        val d = g.duck.value
        val now = tel.timeSec
        val dt = (now - rs.lastT).coerceIn(0f, 0.1f); rs.lastT = now
        // squash = the real gain reduction, 0 … 1 over 0 … 24 dB (deeper still reads as fully flat)
        val sq = if (d.on) (g.duckGrDb / 24f).coerceIn(0f, 1f) else 0f
        // a new trigger: puff of steam + "BOOM"
        if (g.duckTriggers != rs.lastTrig) { if (d.on && g.duckTriggers > rs.lastTrig) rs.hitAt = now; rs.lastTrig = g.duckTriggers }
        val since = now - rs.hitAt
        val playing = g.transport[1] != 0.0
        val beat = if (playing) ((g.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat() else (now * 0.5f) % 1f
        // nod to the tempo, less while he's under the weight
        val nod = sin(beat * 2f * PI.toFloat()).let { it * it } * (1f - sq) * (if (playing) 1f else 0.35f)
        val keyLvl = ((g.duckKeyDb + 50f) / 50f).coerceIn(0f, 1f)
        rs.boomPulse = maxOf(keyLvl, rs.boomPulse - dt * 4f)

        val sc = min(size.width / 240f, size.height / 200f)
        val ox = (size.width - 240f * sc) / 2f; val oy = (size.height - 200f * sc) / 2f
        // the scene's backdrop runs edge to edge (beyond the 240 × 200 stage, the tiles just continue)
        drawRect(TILE, Offset.Zero, size)
        withTransform({ translate(ox, oy); scale(sc, sc, Offset.Zero) }) {
            platform(now, since, tm, sign, bullet, d.source)
            boombox(rs.boomPulse)
            pigeon(rs.boomPulse, since, now)
            steam(since)
            rat(sq, nod, now, since, slice, tail)
            if (since in 0f..0.45f && d.on) {
                val a = 1f - since / 0.45f
                val t = tm.measure("BOOM", boom)
                withTransform({ translate(150f, 40f - since * 30f); scale(0.9f + since, 0.9f + since, Offset.Zero) }) {
                    drawText(t, topLeft = Offset(-t.size.width / 2f, 0f), alpha = a)
                }
            }
        }
        if (!d.on) {
            drawRect(Color.Black.copy(alpha = 0.35f), Offset.Zero, size)
            val t = tm.measure(when { d.source < 0 -> "PICK A KEY ↓"; d.targets == 0 -> "PICK WHAT DIPS ↓"; else -> "DUCK IS OFF" }, sign)
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, size.height * 0.12f))
        }
    }
}

private class RatState { var lastT = 0f; var hitAt = -10f; var lastTrig = 0; var boomPulse = 0f }

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
private val BELLY = Color(0xFFBDBBC4)
private val PINK = Color(0xFFF2A0B0)
private val TIMB = Color(0xFFC98E3A)
private val TIMB_SOLE = Color(0xFF3B2A1A)
private val CHEESE = Color(0xFFF6C445)
private val CRUST = Color(0xFFC0772E)
private val PEP = Color(0xFFC63A2E)
private val INK = Color(0xFF17151A)

/** Tiled wall, the station band (DUCK ST + a route bullet), the platform and its yellow edge, the tunnel. */
private fun DrawScope.platform(now: Float, since: Float, tm: androidx.compose.ui.text.TextMeasurer, sign: TextStyle, bullet: TextStyle, source: Int) {
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
    // a tunnel mouth on the right with the train's lights sweeping by (a slow loop)
    drawRect(PIT, Offset(196f, 62f), Size(80f, 92f))
    drawRect(Color(0xFF2A2A30), Offset(196f, 62f), Size(4f, 92f))
    val sweep = ((now * 0.12f) % 1f)
    if (sweep < 0.25f) {
        val a = sin(sweep / 0.25f * PI.toFloat())
        drawCircle(Color(0xFFFFF2B0).copy(alpha = 0.55f * a), 7f, Offset(214f, 120f))
        drawCircle(Color(0xFFFFF2B0).copy(alpha = 0.55f * a), 7f, Offset(234f, 120f))
    }
    // platform, tactile yellow edge, the drop to the tracks
    drawRect(CONCRETE, Offset(-60f, 150f), Size(360f, 32f))
    drawRect(EDGE, Offset(-60f, 178f), Size(360f, 7f))
    var dx = -56f
    while (dx < 300f) { drawCircle(Color(0xFFC9A512), 1.1f, Offset(dx, 181.5f)); dx += 5f }
    drawRect(PIT, Offset(-60f, 185f), Size(360f, 20f))
    drawLine(Color(0xFF8C8C92), Offset(-60f, 194f), Offset(300f, 194f), 1.5f)
    // the grate the steam comes up through
    drawRect(Color(0xFF55534F), Offset(22f, 160f), Size(40f, 9f))
    for (i in 0 until 7) drawLine(Color(0xFF2E2D2B), Offset(25f + i * 5.5f, 161f), Offset(25f + i * 5.5f, 168f), 1.2f)
}

/** A boombox on the platform; the woofers push out with the key. */
private fun DrawScope.boombox(pulse: Float) {
    val x = 156f; val y = 150f
    drawLine(Color(0xFF2A2A2E), Offset(x + 6f, y - 4f), Offset(x + 34f, y - 4f), 2.5f)                // handle
    drawRoundRect(Color(0xFF2C2C33), Offset(x, y), Size(40f, 22f), CornerRadius(3f))
    drawRoundRect(Color(0xFF45454E), Offset(x + 15f, y + 3f), Size(10f, 6f), CornerRadius(1f))         // tape deck
    for (cx in floatArrayOf(x + 8f, x + 32f)) {
        drawCircle(Color(0xFF101013), 6.5f + pulse * 1.2f, Offset(cx, y + 14f))
        drawCircle(Color(0xFF5B5B66), 2.5f + pulse * 1.5f, Offset(cx, y + 14f))
    }
}

/** An NYC pigeon perched on the boombox: bobs its head, and flaps when the woofers thump. */
private fun DrawScope.pigeon(pulse: Float, since: Float, now: Float) {
    val x = 182f; val y = 143f
    val flap = if (since in 0f..0.35f) sin(since / 0.35f * PI.toFloat() * 3f) else 0f
    val hop = if (since in 0f..0.35f) -3f * sin(since / 0.35f * PI.toFloat()) else 0f
    val bob = sin(now * 5f) * 1.2f
    drawLine(Color(0xFFE07A5F), Offset(x - 3f, y + 4f + hop), Offset(x - 3f, y + 7f), 1.2f)             // feet
    drawLine(Color(0xFFE07A5F), Offset(x + 2f, y + 4f + hop), Offset(x + 2f, y + 7f), 1.2f)
    drawOval(Color(0xFF8D93A0), Offset(x - 9f, y - 4f + hop), Size(16f, 9f))                           // body
    drawOval(Color(0xFF5E6472), Offset(x + 4f, y - 3f + hop), Size(8f, 4f))                            // tail
    rotate(-25f - flap * 45f, Offset(x - 2f, y - 2f + hop)) {                                          // wing
        drawOval(Color(0xFF6E7482), Offset(x - 6f, y - 4f + hop), Size(11f, 5f))
    }
    drawCircle(Color(0xFF5DA28C), 3.2f, Offset(x - 8f, y - 4f + hop))                                  // the green-purple neck
    drawCircle(Color(0xFF8A5BA8).copy(alpha = 0.6f), 2.2f, Offset(x - 7f, y - 3f + hop))
    drawCircle(Color(0xFF8D93A0), 3.4f, Offset(x - 11f + bob, y - 8f + hop))                           // head
    drawCircle(Color(0xFFF08A24), 0.9f, Offset(x - 12f + bob, y - 9f + hop))                           // eye
    drawLine(Color(0xFF3A3A3A), Offset(x - 14f + bob, y - 7.5f + hop), Offset(x - 16.5f + bob, y - 6.8f + hop), 1.3f)   // beak
}

/** Steam up through the grate on each trigger: three puffs that rise and fade. */
private fun DrawScope.steam(since: Float) {
    if (since !in 0f..1.2f) return
    for (k in 0 until 3) {
        val t = since - k * 0.08f
        if (t < 0f) continue
        val a = (1f - t / 1.2f).coerceIn(0f, 1f) * 0.6f
        drawCircle(Color.White.copy(alpha = a), 6f + t * 14f, Offset(36f + k * 7f + sin(t * 5f + k) * 3f, 158f - t * 55f))
    }
}

/**
 * The rat. [sq] 0 = standing tall, slice held high · 1 = flattened under it. [nod] 0…1 head bob.
 * Feet at (110, 178); everything above them scales with the squash, so the pivot is the floor.
 */
private fun DrawScope.rat(sq: Float, nod: Float, now: Float, since: Float, slice: Path, tail: Path) {
    val fx = 110f; val fy = 178f
    val squashY = 1f - 0.36f * sq; val squashX = 1f + 0.2f * sq
    val bodyH = 58f * squashY; val bodyW = 40f * squashX
    val hip = fy - 14f                                     // top of the boots
    val shoulderY = hip - bodyH * 0.92f
    val headY = shoulderY - 16f * (1f - 0.15f * sq) + nod * 2.2f
    val headX = fx + 6f + nod * 1.5f

    // tail: a long pink S out the back, flicking a little with the beat
    tail.reset()
    tail.moveTo(fx - bodyW * 0.42f, hip - 6f)
    tail.cubicTo(fx - 48f, hip + 6f, fx - 62f, hip - 30f + nod * 4f, fx - 74f, hip - 18f - sin(now * 3f) * 3f)
    drawPath(tail, PINK, style = Stroke(3.2f, cap = StrokeCap.Round))

    // Timbs: tan work boots, dark soles, a lace line (they spread a bit as he squats)
    val spread = 9f + 7f * sq
    for (s in floatArrayOf(-1f, 1f)) {
        val bx = fx + s * spread
        drawRoundRect(TIMB, Offset(bx - 8f, fy - 14f), Size(17f, 13f), CornerRadius(4f))
        drawRoundRect(TIMB_SOLE, Offset(bx - 9f, fy - 3f), Size(19f, 4f), CornerRadius(1.5f))
        drawLine(Color(0xFF8A5E22), Offset(bx - 2f, fy - 13f), Offset(bx + 3f, fy - 6f), 1.2f)
        drawRect(Color(0xFF6B4A1C), Offset(bx - 8f, fy - 15f), Size(17f, 2.5f))                // padded collar
    }

    // body in a hoodie (the theme's key colour), grey fur at the belly
    val hood = Tb.Key
    drawOval(hood, Offset(fx - bodyW / 2f, hip - bodyH), Size(bodyW, bodyH))
    drawOval(BELLY, Offset(fx - bodyW * 0.28f, hip - bodyH * 0.62f), Size(bodyW * 0.56f, bodyH * 0.5f))
    drawRoundRect(hood.copy(alpha = 0.85f), Offset(fx - bodyW * 0.3f, hip - bodyH * 0.4f), Size(bodyW * 0.6f, bodyH * 0.18f), CornerRadius(4f))   // kangaroo pocket
    drawLine(INK.copy(alpha = 0.5f), Offset(fx - 3f, shoulderY + 3f), Offset(fx - 4f, shoulderY + 12f), 1f)    // drawstrings
    drawLine(INK.copy(alpha = 0.5f), Offset(fx + 3f, shoulderY + 3f), Offset(fx + 4f, shoulderY + 12f), 1f)

    // arms up to the slice: shoulders → elbows (out wider as he's pushed down) → paws under the slice
    // the slice rides above his ears, and comes to rest on them at the bottom of the squash
    val sliceY = headY - 33f + 9f * sq + (if (since in 0f..0.12f) since * 20f else 0f)
    val elbowOut = 18f + 10f * sq
    for (s in floatArrayOf(-1f, 1f)) {
        val sx = fx + s * bodyW * 0.42f; val sy = shoulderY + 6f
        val ex = fx + s * (bodyW * 0.42f + elbowOut); val ey = (sy + sliceY) / 2f + 6f * sq
        val px = fx + s * 22f; val py = sliceY + 4f
        drawLine(hood, Offset(sx, sy), Offset(ex, ey), 7f, cap = StrokeCap.Round)
        drawLine(FUR, Offset(ex, ey), Offset(px, py), 5f, cap = StrokeCap.Round)
        drawCircle(PINK, 3.4f, Offset(px, py))
    }

    // head: big round ears, then the head, snout to the right
    for ((ex, er) in listOf(headX - 11f to 9f, headX + 5f to 10f)) {
        val earY = headY - 13f + sq * 3f
        drawCircle(FUR_DARK, er, Offset(ex, earY))
        drawCircle(PINK, er - 3.2f, Offset(ex, earY))
    }
    drawCircle(FUR, 15f, Offset(headX, headY))
    slice.reset()                                       // (the slice's path, borrowed for the snout first)
    slice.moveTo(headX + 8f, headY - 7f)
    slice.quadraticBezierTo(headX + 27f, headY - 2f, headX + 26f, headY + 3f)
    slice.quadraticBezierTo(headX + 18f, headY + 8f, headX + 6f, headY + 7f)
    slice.close()
    drawPath(slice, FUR)
    drawCircle(PINK, 3.2f, Offset(headX + 26f, headY + 1.5f))                                           // nose
    for (k in -1..1) drawLine(INK.copy(alpha = 0.55f), Offset(headX + 20f, headY + 3f), Offset(headX + 34f, headY + 1f + k * 4f + sq * 2f), 0.8f)
    drawLine(Color.White, Offset(headX + 20f, headY + 7f), Offset(headX + 20f, headY + 10f), 2.2f)        // buck teeth
    // eye: open with a glint; straining (a squeezed ">") when the weight is heavy
    if (sq > 0.55f) {
        drawLine(INK, Offset(headX + 5f, headY - 6f), Offset(headX + 10f, headY - 3.5f), 1.8f, cap = StrokeCap.Round)
        drawLine(INK, Offset(headX + 10f, headY - 3.5f), Offset(headX + 5f, headY - 1f), 1.8f, cap = StrokeCap.Round)
    } else {
        drawCircle(INK, 3f, Offset(headX + 8f, headY - 4f))
        drawCircle(Color.White, 1f, Offset(headX + 9f, headY - 5f))
    }
    // headphones: band over the top, cups over the ears (the second accent colour)
    val cup = Tb.Tempo
    drawArc(INK, 200f, 140f, false, Offset(headX - 17f, headY - 20f), Size(34f, 30f), style = Stroke(3f))
    drawRoundRect(cup, Offset(headX - 19f, headY - 7f), Size(8f, 13f), CornerRadius(3f))
    drawRoundRect(INK, Offset(headX - 19f, headY - 7f), Size(8f, 13f), CornerRadius(3f), style = Stroke(1f))
    // sweat when it's heavy
    if (sq > 0.6f) {
        val sy = headY - 14f + ((now * 1.8f) % 1f) * 12f
        drawCircle(Color(0xFF9FD8FF), 2.2f, Offset(headX - 15f, sy))
    }

    // the slice, held flat overhead like a barbell: crust on the right, cheese, pepperoni
    slice.reset()
    slice.moveTo(fx - 36f, sliceY + 1f)
    slice.lineTo(fx + 30f, sliceY - 10f)
    slice.lineTo(fx + 32f, sliceY + 9f)
    slice.close()
    drawPath(slice, CHEESE)
    drawRoundRect(CRUST, Offset(fx + 27f, sliceY - 12f), Size(9f, 23f), CornerRadius(3.5f))
    for ((px, py) in listOf(fx - 14f to sliceY + 1f, fx + 2f to sliceY - 1.5f, fx + 16f to sliceY + 1.5f))
        drawCircle(PEP, 2.6f, Offset(px, py))
    // a cheese drip that stretches as he sinks
    if (sq > 0.2f) drawLine(CHEESE, Offset(fx - 6f, sliceY + 2f), Offset(fx - 6f, sliceY + 4f + 10f * sq), 2f, cap = StrokeCap.Round)
    // a little "weight" mark on the slice when it lands
    if (since in 0f..0.25f) rotate(-8f, Offset(fx, sliceY - 14f)) {
        drawLine(INK, Offset(fx - 6f, sliceY - 20f), Offset(fx - 6f, sliceY - 12f), 1.5f)
        drawLine(INK, Offset(fx + 6f, sliceY - 20f), Offset(fx + 6f, sliceY - 12f), 1.5f)
    }
}
