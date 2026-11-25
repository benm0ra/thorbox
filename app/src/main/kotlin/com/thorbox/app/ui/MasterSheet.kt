// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.MasterBus
import com.thorbox.app.input.navClickable
import com.thorbox.engine.NativeEngine
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

/** The MASTER page's live numbers, read once per frame however many things draw them. */
private class MasterMeters {
    private var frame = -1L
    private val gr = FloatArray(2)
    private val loud = FloatArray(6)
    var glue = 0f; var limit = 0f            // gain reduction, dB (falls back slowly, like a needle)
    var momentary = -100f; var shortTerm = -100f; var integrated = -100f; var peakDb = -100f
    fun poll(f: Long) {
        if (f == frame) return
        frame = f
        NativeEngine.masterGr(gr)
        glue = max(gr[0], glue * 0.88f); limit = max(gr[1], limit * 0.85f)
        NativeEngine.loudness(loud)
        momentary = loud[0]; shortTerm = loud[1]; integrated = loud[2]; peakDb = max(loud[3], loud[4])
    }
}

private enum class Stage(val title: String) { EQ("EQ"), GLUE("GLUE"), LIMIT("LIMIT"), LOUD("LOUDNESS") }

/**
 * MASTER: the master bus, in signal order: EQ → GLUE (bus compressor) → the master fader → LIMIT, and
 * LOUDNESS against a target. The chain across the top shows each stage working (its curve, its gain
 * reduction, the loudness) and picks which one is open underneath. Presets set the whole bus.
 */
@Composable
fun MasterSheet(state: AppState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val g = state.groove
    val m = g.masterBus.value
    val set: ((MasterBus) -> MasterBus) -> Unit = { f -> g.setMasterBus(f) }
    val meters = remember { MasterMeters() }
    var open by remember { mutableIntStateOf(Stage.GLUE.ordinal) }
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 32)

    Column(modifier.sheet(onClose = onDone).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- header: presets ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MASTER", style = Type.title)
            Box(Modifier.width(6.dp))
            MasterBus.PRESETS.forEach { (name, f) ->
                HwButton(name, Modifier.weight(1f), minHeight = 34.dp, fontSize = 11, hPad = 4.dp, active = f(m) == m, accent = Tb.EncWhite) { set(f) }
            }
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onDone)
        }
        // ---- the chain: each stage live, tap one to open it ----
        Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Stage.entries.forEachIndexed { i, st ->
                if (i > 0) Text(if (st == Stage.LOUD) "=" else "→", color = Tb.Muted, fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (st == Stage.LIMIT) {                        // the fader sits between GLUE and LIMIT
                    Text(String.format(Locale.US, "FADER\n%+.1f", g.masterDb.floatValue), color = Tb.Muted, fontSize = 10.sp, fontFamily = Tb.Mono,
                        lineHeight = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
                    Text("→", color = Tb.Muted, fontSize = 18.sp, fontWeight = FontWeight.Black)
                }
                val on = when (st) { Stage.EQ -> m.eq.on && m.eq.shaped; Stage.GLUE -> m.glue.on; Stage.LIMIT -> m.limit.on; Stage.LOUD -> true }
                val c = stageColor(st)
                Canvas(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(if (open == i) c.copy(alpha = 0.22f) else Tb.Raised)
                    .then(if (open == i) Modifier.border(2.dp, c, RoundedCornerShape(10.dp)) else Modifier)
                    .navClickable(label = "MASTER ${st.title}") { open = i; Info.show("MASTER ${st.title}") }) {
                    tel.frame.longValue
                    meters.poll(tel.frame.longValue)
                    chainTile(st, m, meters, on, c, tm)
                }
            }
        }
        // ---- the open stage ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (Stage.entries[open]) {
                Stage.EQ -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shape the whole mix: a low cut at 25–30 Hz clears rumble, a gentle high shelf adds air.", color = Tb.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        HwButton(if (m.eq.on) "EQ ON" else "BYPASSED", minHeight = 34.dp, fontSize = 12, active = m.eq.on, accent = stageColor(Stage.EQ)) {
                            set { it.copy(eq = it.eq.copy(on = !it.eq.on)) }
                        }
                    }
                    EqEditor(m.eq, stageColor(Stage.EQ), { f -> set { it.copy(eq = f(it.eq)) } }, Modifier.weight(1f).fillMaxWidth(), starts = false)
                }
                Stage.GLUE -> GluePanel(m.glue, meters, tel, tm) { f -> set { it.copy(glue = f(it.glue)) } }
                Stage.LIMIT -> LimitPanel(m.limit, meters, tel, tm) { f -> set { it.copy(limit = f(it.limit)) } }
                Stage.LOUD -> LoudPanel(m, meters, tel, tm, set)
            }
        }
    }
}

private fun stageColor(st: Stage) = when (st) { Stage.EQ -> Tb.EncBlue; Stage.GLUE -> Tb.EncGreen; Stage.LIMIT -> Tb.EncOrange; Stage.LOUD -> Tb.Tempo }

private fun DrawScope.chainTile(st: Stage, m: MasterBus, mt: MasterMeters, on: Boolean, c: Color, tm: TextMeasurer) {
    val w = size.width; val h = size.height; val p = 8.dp.toPx()
    val head = tm.measure(st.title + if (st != Stage.LOUD && !on) " · OFF" else "",
        TextStyle(color = if (on) Tb.Text else Tb.Dim, fontSize = 11.sp, fontFamily = Tb.Display, letterSpacing = 1.sp))
    drawText(head, topLeft = Offset(p, p * 0.6f))
    val top = p + head.size.height + 2.dp.toPx(); val bot = h - p
    when (st) {
        Stage.EQ -> {
            val path = Path()
            for (i in 0..32) {
                val f = 20f * Math.pow(1000.0, i / 32.0).toFloat()
                val y = (top + bot) / 2f - m.eq.responseDb(f).coerceIn(-18f, 18f) / 18f * (bot - top) / 2f
                val x = p + (w - 2 * p) * i / 32f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, if (on) c else Tb.Dim, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Stage.GLUE, Stage.LIMIT -> {
            val gr = if (st == Stage.GLUE) mt.glue else mt.limit
            val full = if (st == Stage.GLUE) 12f else 9f
            val bh = (bot - top) * 0.45f; val by = (top + bot) / 2f - bh / 2f
            drawRoundRect(Tb.Void, Offset(p, by), Size(w - 2 * p, bh), CornerRadius(bh / 2))
            // gain reduction grows from the right, like a needle falling back
            val f = (gr / full).coerceIn(0f, 1f) * (if (on) 1f else 0f)
            drawRoundRect(c, Offset(w - p - (w - 2 * p) * f, by), Size((w - 2 * p) * f, bh), CornerRadius(bh / 2))
            val t = tm.measure(if (!on) "—" else String.format(Locale.US, "−%.1f dB", gr), TextStyle(color = Tb.Text, fontSize = 10.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black))
            drawText(t, topLeft = Offset(w - p - t.size.width, h - p - t.size.height + 4.dp.toPx()))
        }
        Stage.LOUD -> {
            val v = mt.shortTerm
            val t = tm.measure(if (v <= -70f) "— LUFS" else String.format(Locale.US, "%.1f LUFS", v),
                TextStyle(color = Tb.Text, fontSize = 16.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black))
            drawText(t, topLeft = Offset(p, top))
            val d = v - m.targetLufs
            val s = tm.measure(if (v <= -70f) "target ${m.targetLufs.toInt()}" else String.format(Locale.US, "%+.1f vs %d", d, m.targetLufs.toInt()),
                TextStyle(color = if (v > -70f && kotlin.math.abs(d) <= 1f) Tb.Good else Tb.Muted, fontSize = 10.sp, fontFamily = Tb.Mono))
            drawText(s, topLeft = Offset(p, top + t.size.height))
        }
    }
}

// ------------------------------------------------------------------ GLUE
@Composable
private fun GluePanel(gl: MasterBus.Glue, mt: MasterMeters, tel: com.thorbox.app.SynthTelemetry, tm: TextMeasurer, set: ((MasterBus.Glue) -> MasterBus.Glue) -> Unit) {
    val c = stageColor(Stage.GLUE)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // the transfer curve (in → out), the threshold, and the gain reduction meter
        Canvas(Modifier.fillMaxHeight().aspectRatio(1.15f).clip(RoundedCornerShape(10.dp)).background(Tb.Void)) {
            tel.frame.longValue; mt.poll(tel.frame.longValue)
            val w = size.width; val h = size.height; val p = 10.dp.toPx()
            val meterW = 14.dp.toPx(); val gw = w - meterW - 3 * p; val gh = h - 2 * p
            fun x(db: Float) = p + (db + 48f) / 48f * gw
            fun y(db: Float) = p + gh - (db + 48f) / 48f * gh
            for (d in listOf(-36f, -24f, -12f)) {
                drawLine(Tb.Line, Offset(x(d), p), Offset(x(d), p + gh), 1.dp.toPx()); drawLine(Tb.Line, Offset(p, y(d)), Offset(p + gw, y(d)), 1.dp.toPx())
            }
            drawLine(Tb.Line, Offset(x(-48f), y(-48f)), Offset(x(0f), y(0f)), 1.dp.toPx())               // 1:1
            drawLine(c.copy(alpha = 0.5f), Offset(x(gl.thresholdDb), p), Offset(x(gl.thresholdDb), p + gh), 1.5.dp.toPx())
            val path = Path()
            for (i in 0..48) { val d = -48f + i; val o = (gl.curveDb(d) + gl.makeupDb).coerceIn(-48f, 0f); if (i == 0) path.moveTo(x(d), y(o)) else path.lineTo(x(d), y(o)) }
            drawPath(path, Tb.Keyline, style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, if (gl.on) c else Tb.Dim, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            // GR meter: down from the top, 0..12 dB
            val mx = w - p - meterW
            drawRoundRect(Tb.Raised, Offset(mx, p), Size(meterW, gh), CornerRadius(4.dp.toPx()))
            val f = if (gl.on) (mt.glue / 12f).coerceIn(0f, 1f) else 0f
            drawRoundRect(c, Offset(mx, p), Size(meterW, gh * f), CornerRadius(4.dp.toPx()))
            for (d in listOf(3f, 6f, 9f)) drawLine(Tb.Void, Offset(mx, p + gh * d / 12f), Offset(mx + meterW, p + gh * d / 12f), 1.dp.toPx())
            val t = tm.measure(String.format(Locale.US, "GR %.1f", if (gl.on) mt.glue else 0f), TextStyle(color = Tb.Text, fontSize = 10.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black))
            drawText(t, topLeft = Offset(p + 4.dp.toPx(), p + 2.dp.toPx()))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HwButton(if (gl.on) "GLUE ON" else "GLUE OFF", Modifier.weight(1f), minHeight = 38.dp, fontSize = 12, active = gl.on, accent = c) { set { it.copy(on = !it.on) } }
                for (r in listOf(2f, 4f, 10f)) HwButton("$r:1".replace(".0", ""), Modifier.weight(0.7f), minHeight = 38.dp, fontSize = 12, active = gl.ratio == r, accent = c) { set { it.copy(ratio = r) } }
            }
            val row = Modifier.fillMaxWidth().weight(1f)
            Row(row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DragValue("THRESHOLD", String.format(Locale.US, "%.1f dB", gl.thresholdDb), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(thresholdDb = -18f) } }, onDelta = { d -> set { it.copy(thresholdDb = it.thresholdDb + d * 24f) } })
                DragValue("MAKEUP", String.format(Locale.US, "+%.1f dB", gl.makeupDb), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(makeupDb = 0f) } }, onDelta = { d -> set { it.copy(makeupDb = it.makeupDb + d * 12f) } })
            }
            Row(row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DragValue("ATTACK", String.format(Locale.US, if (gl.attackMs < 1f) "%.1f ms" else "%.0f ms", gl.attackMs), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(attackMs = 10f) } }, onDelta = { d -> set { it.copy(attackMs = it.attackMs * exp(d * 3f)) } })
                DragValue("RELEASE", if (gl.releaseMs <= 0f) "AUTO" else String.format(Locale.US, "%.0f ms", gl.releaseMs), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(releaseMs = 0f) } }, onDelta = { d -> set { it.copy(releaseMs = (if (it.releaseMs <= 0f) 20f else it.releaseMs) * exp(d * 3f)) } })
            }
            Row(row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DragValue("MIX", "${(gl.mix * 100).toInt()} %", c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(mix = 1f) } }, onDelta = { d -> set { it.copy(mix = it.mix + d) } })
                DragValue("SIDECHAIN HP", if (gl.scHpHz <= 0f) "OFF" else "${gl.scHpHz.toInt()} Hz", c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(scHpHz = 0f) } }, onDelta = { d -> set { it.copy(scHpHz = (if (it.scHpHz <= 0f) 19f else it.scHpHz) * exp(d * 3f)) } })
            }
            Text("Gentle glue: 2:1, slow attack (30 ms), AUTO release, 1–3 dB of reduction on the loud hits. SIDECHAIN HP stops the kick's sub from pumping it.",
                color = Tb.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

// ------------------------------------------------------------------ LIMIT
@Composable
private fun LimitPanel(li: MasterBus.Limit, mt: MasterMeters, tel: com.thorbox.app.SynthTelemetry, tm: TextMeasurer, set: ((MasterBus.Limit) -> MasterBus.Limit) -> Unit) {
    val c = stageColor(Stage.LIMIT)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // output peak against the ceiling, and the gain reduction
        Canvas(Modifier.fillMaxHeight().aspectRatio(0.9f).clip(RoundedCornerShape(10.dp)).background(Tb.Void)) {
            tel.frame.longValue; mt.poll(tel.frame.longValue)
            val w = size.width; val h = size.height; val p = 10.dp.toPx()
            val gh = h - 2 * p - 16.dp.toPx()
            val bw = (w - 3 * p) / 2f
            fun yDb(db: Float) = p + gh * (-db / 24f).coerceIn(0f, 1f)        // 0 dB at the top, −24 at the bottom
            // OUT
            drawRoundRect(Tb.Raised, Offset(p, p), Size(bw, gh), CornerRadius(4.dp.toPx()))
            val pk = mt.peakDb
            if (pk > -24f) drawRoundRect(if (pk > li.ceilingDb + 0.05f) Tb.Warn else Tb.Good, Offset(p, yDb(pk)), Size(bw, p + gh - yDb(pk)), CornerRadius(4.dp.toPx()))
            drawLine(c, Offset(p - 3.dp.toPx(), yDb(li.ceilingDb)), Offset(p + bw + 3.dp.toPx(), yDb(li.ceilingDb)), 2.dp.toPx())
            // GR
            val gx = 2 * p + bw
            drawRoundRect(Tb.Raised, Offset(gx, p), Size(bw, gh), CornerRadius(4.dp.toPx()))
            val f = if (li.on) (mt.limit / 12f).coerceIn(0f, 1f) else 0f
            drawRoundRect(c, Offset(gx, p), Size(bw, gh * f), CornerRadius(4.dp.toPx()))
            val st = TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold)
            val a = tm.measure(String.format(Locale.US, "OUT %.1f", pk.coerceAtLeast(-99f)), st); drawText(a, topLeft = Offset(p, h - p - a.size.height))
            val b = tm.measure(String.format(Locale.US, "GR %.1f", if (li.on) mt.limit else 0f), st); drawText(b, topLeft = Offset(gx, h - p - b.size.height))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HwButton(if (li.on) "LIMIT ON" else "LIMIT OFF · the output can clip", Modifier.fillMaxWidth(), minHeight = 38.dp, fontSize = 12, active = li.on, accent = c) {
                set { it.copy(on = !it.on) }
            }
            val row = Modifier.fillMaxWidth().weight(1f)
            DragValue("INPUT (PUSH)", String.format(Locale.US, "+%.1f dB", li.inputDb), c, row, pxPerUnit = 300f,
                onReset = { set { it.copy(inputDb = 0f) } }, onDelta = { d -> set { it.copy(inputDb = it.inputDb + d * 12f) } })
            Row(row, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DragValue("CEILING", String.format(Locale.US, "%.1f dB", li.ceilingDb), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(ceilingDb = -0.3f) } }, onDelta = { d -> set { it.copy(ceilingDb = it.ceilingDb + d * 6f) } })
                DragValue("RELEASE", String.format(Locale.US, "%.0f ms", li.releaseMs), c, Modifier.weight(1f).fillMaxHeight(), pxPerUnit = 300f,
                    onReset = { set { it.copy(releaseMs = 80f) } }, onDelta = { d -> set { it.copy(releaseMs = it.releaseMs * exp(d * 3f)) } })
            }
            Text("Nothing gets past the CEILING. Push INPUT for loudness: a few dB of GR is clean, much more squashes the hits. Keep −0.3 to −1 dB for streaming.",
                color = Tb.Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

// ------------------------------------------------------------------ LOUDNESS
@Composable
private fun LoudPanel(m: MasterBus, mt: MasterMeters, tel: com.thorbox.app.SynthTelemetry, tm: TextMeasurer, set: ((MasterBus) -> MasterBus) -> Unit) {
    val c = stageColor(Stage.LOUD)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.weight(1.3f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Tb.Void)) {
            tel.frame.longValue; mt.poll(tel.frame.longValue)
            val p = 12.dp.toPx()
            fun lufs(v: Float) = if (v <= -70f) "—" else String.format(Locale.US, "%.1f", v)
            val big = tm.measure(lufs(mt.shortTerm), TextStyle(color = Tb.Text, fontSize = 44.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black))
            drawText(tm.measure("SHORT-TERM LUFS", TextStyle(color = Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Display, letterSpacing = 1.sp)), topLeft = Offset(p, p))
            drawText(big, topLeft = Offset(p, p + 16.dp.toPx()))
            val small = TextStyle(color = Tb.Text, fontSize = 13.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold)
            var y = p + 16.dp.toPx() + big.size.height + 6.dp.toPx()
            for ((k, v) in listOf("MOMENTARY" to lufs(mt.momentary), "INTEGRATED" to lufs(mt.integrated), "PEAK" to String.format(Locale.US, "%.1f dBFS", mt.peakDb.coerceAtLeast(-99f)))) {
                drawText(tm.measure("$k  $v", small), topLeft = Offset(p, y)); y += 20.dp.toPx()
            }
            // where it sits against the target: a bar from −24 to −6 LUFS
            val bw = size.width - 2 * p; val by = size.height - p - 14.dp.toPx()
            fun xOf(v: Float) = p + bw * ((v + 24f) / 18f).coerceIn(0f, 1f)
            drawRoundRect(Tb.Raised, Offset(p, by), Size(bw, 14.dp.toPx()), CornerRadius(7.dp.toPx()))
            if (mt.shortTerm > -70f) drawRoundRect(c, Offset(p, by), Size(xOf(mt.shortTerm) - p, 14.dp.toPx()), CornerRadius(7.dp.toPx()))
            drawLine(Tb.Text, Offset(xOf(m.targetLufs), by - 5.dp.toPx()), Offset(xOf(m.targetLufs), by + 19.dp.toPx()), 2.5.dp.toPx())
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TARGET", style = Type.label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MasterBus.TARGETS.forEach { (v, name) ->
                    HwButton("$name ${v.toInt()}", Modifier.weight(1f), minHeight = 40.dp, fontSize = 11, hPad = 4.dp, active = m.targetLufs == v, accent = c) {
                        set { it.copy(targetLufs = v) }
                    }
                }
            }
            val d = mt.shortTerm - m.targetLufs
            Text(when {
                mt.shortTerm <= -70f -> "Play the loudest part of the beat and read the SHORT-TERM number."
                d < -1f -> String.format(Locale.US, "%.1f LU under the target: push LIMIT's INPUT up (or GLUE's MAKEUP).", -d)
                d > 1f -> String.format(Locale.US, "%.1f LU over the target: streaming apps will turn it down anyway; ease LIMIT's INPUT back.", d)
                else -> "On target."
            }, color = Tb.Text, fontSize = 13.sp, lineHeight = 17.sp)
            Box(Modifier.weight(1f))
            HwButton("RESET INTEGRATED", Modifier.fillMaxWidth(), minHeight = 40.dp, fontSize = 12) { NativeEngine.resetLoudness() }
        }
    }
}
