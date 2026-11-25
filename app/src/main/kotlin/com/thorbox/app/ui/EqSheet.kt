// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.TrackEq
import com.thorbox.app.input.navClickable
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** The EQ's five handles, left to right on the curve. */
private enum class Band(val tag: String, val title: String) { HP("HP", "LOW CUT"), LOW("L", "LOW"), MID("M", "MID"), HIGH("H", "HIGH"), LP("LP", "HIGH CUT") }

private fun bandColor(b: Band) = when (b) {
    Band.HP -> Tb.EncBlue; Band.LOW -> Tb.EncGreen; Band.MID -> Tb.Key; Band.HIGH -> Tb.EncOrange; Band.LP -> Tb.Tempo
}

private const val F_LO = 20f
private const val F_HI = 20000f
private const val SPAN = 3f                      // decades on screen (20 Hz .. 20 kHz)
private const val DB_VIEW = 18f                  // ± dB shown

fun eqHz(f: Float): String = when {
    f >= 9950f -> String.format(Locale.US, "%.0fk", f / 1000f)
    f >= 1000f -> String.format(Locale.US, "%.1fk", f / 1000f)
    else -> String.format(Locale.US, "%.0f", f)
}
private fun eqDb(d: Float) = if (kotlin.math.abs(d) < 0.05f) "0 dB" else String.format(Locale.US, "%+.1f", d)
/** Multiply [f] by the drag: one unit = the whole 20 Hz – 20 kHz span. */
private fun glideHz(f: Float, units: Float) = f * exp(units * SPAN * ln(10f))

/** Starting points for a quick mix: each sets the whole EQ (still adjustable, and undoable). */
private val STARTS: List<Pair<String, TrackEq>> = listOf(
    "FLAT" to TrackEq(),
    "CLEAN LOWS" to TrackEq(hpHz = 35f, hp24 = true),
    "MAKE ROOM" to TrackEq(hpHz = 160f, lowDb = -2f, lowHz = 250f, midDb = -2f, midHz = 400f, midQ = 1f),
    "KICK" to TrackEq(hpHz = 30f, lowDb = 3f, lowHz = 60f, midDb = -4f, midHz = 350f, midQ = 1.4f, highDb = 2f, highHz = 5000f),
    "BASS" to TrackEq(hpHz = 30f, midDb = -3f, midHz = 250f, midQ = 1f, lpHz = 6000f),
    "HATS" to TrackEq(hpHz = 400f, hp24 = true, highDb = 2f, highHz = 9000f),
    "WARM" to TrackEq(lowDb = 2f, lowHz = 150f, highDb = -3f, highHz = 7000f),
    "AIR" to TrackEq(highDb = 3f, highHz = 11000f),
    "LO-FI" to TrackEq(hpHz = 250f, hp24 = true, midDb = 3f, midHz = 1500f, midQ = 0.8f, lpHz = 4000f, lp24 = true),
)

/**
 * A track's EQ, opened from its strip on the MIXER: the curve with five handles to drag (sideways =
 * frequency, up / down = boost or cut), double-tap a handle to reset that band, and the exact values
 * underneath. ‹ › steps through the tracks so a whole mix can be done from here.
 */
@Composable
fun EqSheet(state: AppState, track: Int, onTrack: (Int) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val g = state.groove
    val tracks = state.tracks.value
    val tr = tracks[track]
    val eq = g.channels.value[track].eq
    val color = kindColor(tr.kind)
    var sel by remember { mutableIntStateOf(Band.MID.ordinal) }
    val set: ((TrackEq) -> TrackEq) -> Unit = { f -> g.setEq(track) { f(it) } }
    val order = tracks.indices.filter { tracks[it].kind != com.thorbox.model.TrackKind.EMPTY }

    Column(modifier.sheet(onClose = onDone).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- header: which track, step to the others, ON / bypass, close ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EQ", style = Type.title)
            HwButton("‹", Modifier.width(44.dp), minHeight = 36.dp, fontSize = 16, enabled = order.size > 1) {
                onTrack(order[(order.indexOf(track) - 1 + order.size) % order.size])
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            FitText(tr.name, TextStyle(color = color, fontFamily = Tb.Display, fontSize = 16.sp, letterSpacing = 1.sp), Modifier.width(150.dp))
            HwButton("›", Modifier.width(44.dp), minHeight = 36.dp, fontSize = 16, enabled = order.size > 1) {
                onTrack(order[(order.indexOf(track) + 1) % order.size])
            }
            Box(Modifier.weight(1f))
            HwButton(if (eq.on) "EQ ON" else "BYPASSED", minHeight = 36.dp, fontSize = 12, active = eq.on, accent = color) { set { it.copy(on = !it.on) } }
            HwButton("✕", Modifier.width(44.dp), minHeight = 36.dp, fontSize = 14, onClick = onDone)
        }
        // ---- the curve ----
        EqCurve(eq, color, Band.entries[sel], onSelect = { sel = it.ordinal }, set = set,
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tb.Void))
        // ---- starting points ----
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("START", style = Type.label)
            STARTS.forEach { (name, preset) ->
                HwButton(name, Modifier.weight(1f), minHeight = 32.dp, fontSize = 10, hPad = 4.dp,
                    active = eq.copy(on = true) == preset, accent = color) { set { preset.copy(on = true) } }
            }
        }
        // ---- the exact values, one column per band ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Band.entries.forEach { b ->
                val c = bandColor(b)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (sel == b.ordinal) c.copy(alpha = 0.28f) else Color.Transparent)
                        .navClickable(label = b.title) { sel = b.ordinal }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                        FitText(b.title, Tb.Label.copy(color = Tb.Text), minSize = 7f)
                    }
                    val row = Modifier.fillMaxWidth().height(34.dp)
                    when (b) {
                        Band.HP -> {
                            DragValue("FREQ", if (eq.hpOn) eqHz(eq.hpHz) else "OFF", c, row, pxPerUnit = 700f, compact = true,
                                onReset = { set { it.copy(hpHz = TrackEq.HP_OFF) } }, onDelta = { d -> sel = b.ordinal; set { it.copy(hpHz = glideHz(it.hpHz, d)) } })
                            HwButton(if (eq.hp24) "24 dB/OCT" else "12 dB/OCT", row, minHeight = 34.dp, fontSize = 10, hPad = 4.dp) { set { it.copy(hp24 = !it.hp24) } }
                        }
                        Band.LOW -> {
                            DragValue("GAIN", eqDb(eq.lowDb), c, row, pxPerUnit = 300f, compact = true, onReset = { set { it.copy(lowDb = 0f) } }, onDelta = { d -> sel = b.ordinal; set { it.copy(lowDb = it.lowDb + d * 12f) } })
                            DragValue("FREQ", eqHz(eq.lowHz), c, row, pxPerUnit = 700f, compact = true, onReset = { set { it.copy(lowHz = 100f) } }, onDelta = { d -> set { it.copy(lowHz = glideHz(it.lowHz, d)) } })
                        }
                        Band.MID -> {
                            DragValue("GAIN", eqDb(eq.midDb), c, row, pxPerUnit = 300f, compact = true, onReset = { set { it.copy(midDb = 0f) } }, onDelta = { d -> sel = b.ordinal; set { it.copy(midDb = it.midDb + d * 12f) } })
                            DragValue("FREQ", eqHz(eq.midHz), c, row, pxPerUnit = 700f, compact = true, onReset = { set { it.copy(midHz = 1000f) } }, onDelta = { d -> set { it.copy(midHz = glideHz(it.midHz, d)) } })
                            DragValue("Q", String.format(Locale.US, "%.1f", eq.midQ), c, row, pxPerUnit = 400f, compact = true, onReset = { set { it.copy(midQ = 0.7f) } }, onDelta = { d -> set { it.copy(midQ = it.midQ * exp(d * 2f)) } })
                        }
                        Band.HIGH -> {
                            DragValue("GAIN", eqDb(eq.highDb), c, row, pxPerUnit = 300f, compact = true, onReset = { set { it.copy(highDb = 0f) } }, onDelta = { d -> sel = b.ordinal; set { it.copy(highDb = it.highDb + d * 12f) } })
                            DragValue("FREQ", eqHz(eq.highHz), c, row, pxPerUnit = 700f, compact = true, onReset = { set { it.copy(highHz = 8000f) } }, onDelta = { d -> set { it.copy(highHz = glideHz(it.highHz, d)) } })
                        }
                        Band.LP -> {
                            DragValue("FREQ", if (eq.lpOn) eqHz(eq.lpHz) else "OFF", c, row, pxPerUnit = 700f, compact = true,
                                onReset = { set { it.copy(lpHz = TrackEq.LP_OFF) } }, onDelta = { d -> sel = b.ordinal; set { it.copy(lpHz = glideHz(it.lpHz, d)) } })
                            HwButton(if (eq.lp24) "24 dB/OCT" else "12 dB/OCT", row, minHeight = 34.dp, fontSize = 10, hPad = 4.dp) { set { it.copy(lp24 = !it.lp24) } }
                        }
                    }
                }
            }
        }
    }
}

/** Where each band's handle sits: (frequency, dB). Filters sit on the 0 dB line at their corner. */
private fun handle(eq: TrackEq, b: Band): Pair<Float, Float> = when (b) {
    Band.HP -> eq.hpHz to 0f
    Band.LOW -> eq.lowHz to eq.lowDb
    Band.MID -> eq.midHz to eq.midDb
    Band.HIGH -> eq.highHz to eq.highDb
    Band.LP -> eq.lpHz to 0f
}

@Composable
private fun EqCurve(eq: TrackEq, color: Color, selected: Band, onSelect: (Band) -> Unit, set: ((TrackEq) -> TrackEq) -> Unit, modifier: Modifier) {
    val now by rememberUpdatedState(eq)
    val select by rememberUpdatedState(onSelect)
    val tm = rememberTextMeasurer(cacheSize = 24)
    val axis = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold) }
    val tagStyle = remember(Tb.palette) { TextStyle(color = Tb.Black, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black) }
    val drag = remember { intArrayOf(-1) }                     // the handle being dragged
    val work = remember { arrayOf(TrackEq()) }                 // the drag's own copy, so no move is lost between frames
    Canvas(modifier
        .pointerInput(Unit) {
            fun nearest(p: Offset): Band? {
                var best: Band? = null; var bd = Float.MAX_VALUE
                for (b in Band.entries) {
                    val (f, d) = handle(now, b)
                    val q = Offset(xOf(f, size.width.toFloat()), yOf(d, size.height.toFloat(), 14.dp.toPx()))
                    val dist = (q - p).getDistance()
                    if (dist < bd) { bd = dist; best = b }
                }
                return if (bd < 56.dp.toPx()) best else null
            }
            detectTapGestures(
                onTap = { p -> nearest(p)?.let { select(it) } },
                onDoubleTap = { p ->
                    when (nearest(p)) {
                        Band.HP -> set { it.copy(hpHz = TrackEq.HP_OFF) }
                        Band.LOW -> set { it.copy(lowDb = 0f) }
                        Band.MID -> set { it.copy(midDb = 0f) }
                        Band.HIGH -> set { it.copy(highDb = 0f) }
                        Band.LP -> set { it.copy(lpHz = TrackEq.LP_OFF) }
                        null -> Unit
                    }
                },
            )
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { p ->
                    var best = -1; var bd = Float.MAX_VALUE
                    for (b in Band.entries) {
                        val (f, d) = handle(now, b)
                        val dist = (Offset(xOf(f, size.width.toFloat()), yOf(d, size.height.toFloat(), 14.dp.toPx())) - p).getDistance()
                        if (dist < bd) { bd = dist; best = b.ordinal }
                    }
                    drag[0] = if (bd < 64.dp.toPx()) best else -1
                    work[0] = now
                    if (drag[0] >= 0) { select(Band.entries[drag[0]]); Info.show(Band.entries[drag[0]].title) }
                },
                onDragEnd = { drag[0] = -1 }, onDragCancel = { drag[0] = -1 },
            ) { change, d ->
                if (drag[0] < 0) return@detectDragGestures
                change.consume()
                val fu = d.x / size.width                               // fraction of the span
                val du = -d.y / ((size.height - 28.dp.toPx()) / 2f) * DB_VIEW
                val e = work[0]
                work[0] = when (Band.entries[drag[0]]) {
                    Band.HP -> e.copy(hpHz = glideHz(e.hpHz, fu))
                    Band.LOW -> e.copy(lowHz = glideHz(e.lowHz, fu), lowDb = e.lowDb + du)
                    Band.MID -> e.copy(midHz = glideHz(e.midHz, fu), midDb = e.midDb + du)
                    Band.HIGH -> e.copy(highHz = glideHz(e.highHz, fu), highDb = e.highDb + du)
                    Band.LP -> e.copy(lpHz = glideHz(e.lpHz, fu))
                }.clamped()
                val w = work[0]
                set { w }
            }
        }
    ) {
        val w = size.width; val h = size.height; val pad = 14.dp.toPx()
        // grid: decades bold, the rest faint; dB lines every 6
        for (dec in 0..3) for (m in 1..9) {
            val f = 10f.pow(dec + 1) * m
            if (f < F_LO || f > F_HI) continue
            val x = xOf(f, w)
            drawLine(if (m == 1) Tb.Line else Tb.Line.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = yOf(db, h, pad)
            drawLine(if (db == 0f) Tb.Muted.copy(alpha = 0.6f) else Tb.Line.copy(alpha = 0.6f), Offset(0f, y), Offset(w, y), 1.dp.toPx())
            val t = tm.measure(if (db == 0f) "0" else String.format(Locale.US, "%+.0f", db), axis)
            drawText(t, topLeft = Offset(4.dp.toPx(), y - t.size.height - 1f))
        }
        for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
            val t = tm.measure(eqHz(f), axis)
            drawText(t, topLeft = Offset(xOf(f, w) - t.size.width / 2f, h - t.size.height - 2.dp.toPx()))
        }
        // the response: filled from 0 dB, keylined, in the track's colour (grey when bypassed)
        val ink = if (eq.on) color else Tb.Dim
        val n = (w / 3f).toInt().coerceIn(64, 600)
        val y0 = yOf(0f, h, pad)
        val line = Path(); val fill = Path()
        fill.moveTo(0f, y0)
        for (i in 0..n) {
            val x = w * i / n
            val f = F_LO * 10f.pow(SPAN * i / n)
            val y = yOf(eq.responseDb(f).coerceIn(-60f, 30f), h, pad).coerceIn(-4f, h + 4f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(w, y0); fill.close()
        drawPath(fill, ink.copy(alpha = 0.22f))
        drawPath(line, Tb.Keyline, style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // handles
        for (b in Band.entries) {
            val (f, d) = handle(eq, b)
            val active = when (b) { Band.HP -> eq.hpOn; Band.LP -> eq.lpOn; Band.LOW -> eq.lowDb != 0f; Band.MID -> eq.midDb != 0f; Band.HIGH -> eq.highDb != 0f }
            handleDot(Offset(xOf(f, w).coerceIn(12.dp.toPx(), w - 12.dp.toPx()), yOf(d, h, pad)), bandColor(b), active && eq.on, b == selected)
            val t = tm.measure(b.tag, tagStyle)
            val c = Offset(xOf(f, w).coerceIn(12.dp.toPx(), w - 12.dp.toPx()), yOf(d, h, pad))
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
        }
    }
}

private fun xOf(f: Float, w: Float) = w * (log10(f / F_LO) / SPAN)
private fun yOf(db: Float, h: Float, pad: Float) = h / 2f - db / DB_VIEW * (h / 2f - pad)

private fun DrawScope.handleDot(c: Offset, col: Color, lit: Boolean, selected: Boolean) {
    val r = 11.dp.toPx()
    if (selected) drawCircle(col.copy(alpha = 0.35f), r * 1.75f, c)
    drawCircle(Tb.Keyline, r + 1.5.dp.toPx(), c)
    drawCircle(if (lit) col else col.copy(alpha = 0.45f), r, c)
    drawCircle(Color.White.copy(alpha = 0.5f), r * 0.28f, Offset(c.x - r * 0.4f, c.y - r * 0.42f))    // the house shine
}

/** The strip's EQ button: a small picture of its curve (a flat line when untouched). */
@Composable
fun EqThumb(eq: TrackEq, color: Color, label: String, modifier: Modifier, onOpen: () -> Unit) {
    val tm = rememberTextMeasurer(cacheSize = 4)
    val style = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 8.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black) }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Tb.Raised).navClickable(label = "$label EQ") { Info.show("TRACK EQ"); onOpen() }) {
        val w = size.width; val h = size.height
        val pad = 4.dp.toPx()
        val ink = if (!eq.on) Tb.Dim else if (eq.shaped) color else Tb.Muted
        val path = Path()
        val n = 40
        for (i in 0..n) {
            val f = F_LO * 10f.pow(SPAN * i / n)
            val x = w * i / n
            val y = (h / 2f - eq.responseDb(f).coerceIn(-DB_VIEW, DB_VIEW) / DB_VIEW * (h / 2f - pad))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val t = tm.measure(if (!eq.on) "EQ OFF" else "EQ", style)
        drawRoundRect(Tb.Raised.copy(alpha = 0.8f), Offset(2.dp.toPx(), 1.dp.toPx()), androidx.compose.ui.geometry.Size(t.size.width + 4.dp.toPx(), t.size.height.toFloat()), CornerRadius(3.dp.toPx()))
        drawText(t, topLeft = Offset(4.dp.toPx(), 1.dp.toPx()))
    }
}
