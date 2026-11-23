// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navTarget
import com.thorbox.app.input.NavGrid
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.FX_INFO
import com.thorbox.app.groove.RepeatRate
import com.thorbox.app.groove.SLICE_PATTERNS
import com.thorbox.app.groove.echoDivIndex
import com.thorbox.app.groove.fxReadout
import com.thorbox.app.groove.looperDivIndex
import com.thorbox.app.groove.looperSpeed
import com.thorbox.app.groove.slicerPattern
import com.thorbox.engine.NativeEngine.Fx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Layout: one row per job, each with one accent colour, like the function groups on a pocket
 * synth's panel. TONE (shape the sound) · TIME (cut it up) · SPACE (put it in a room) · MEDIA (play it off something).
 */
internal val FX_ROWS = listOf(
    "TONE" to listOf(Fx.FILTER, Fx.ISOLATOR, Fx.LOFI),
    "TIME" to listOf(Fx.LOOPER, Fx.SCATTER, Fx.SLICER),
    "SPACE" to listOf(Fx.ECHO, Fx.REVERB, Fx.RESONATOR),
    "MEDIA" to listOf(Fx.VINYL, Fx.CASSETTE, Fx.TAPE_STOP),
)
internal val FX_GRID = FX_ROWS.flatMap { it.second }
private fun rowAccent(row: Int): Color = when (row) { 0 -> Tb.EncOrange; 1 -> Tb.EncBlue; 2 -> Tb.EncGreen; else -> Tb.Key }

/**
 * Deck 4: punch-in master FX (the sampler-workstation set, played from XY pads). Hold a pad to engage,
 * release to snap back dry. Where your finger sits on the pad is the effect's XY: slide to sweep.
 * Several fingers = stacked effects. LATCH, then tap, keeps one on hands-free. ALL DRY kills everything.
 *
 * Look: black tiles, hairline outlines, mono type, line glyphs; colour only where something is
 * happening. Engaged, a tile shows its XY field (dot grid + crosshair under the finger) and a
 * live readout of what the effect is doing (1/16 · REV ×0.7, LP 820, D#3 …).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FxDeck(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val latchMode = remember { mutableStateOf(false) }
    val xyMode = g.fxXyMode
    val latchedNow = g.fxLatched.intValue
    if (latchedNow == 0 && xyMode.value) xyMode.value = false            // nothing latched: back to the pads
    val dims = remember { IntArray(2) }
    val fingers = remember { HashMap<Int, Int>() }        // pointerId → fx id
    val tm = rememberTextMeasurer(cacheSize = 64)
    val idxStyle = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono) }
    val nameStyle = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 12.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
    val readStyle = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 10.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold) }
    val axisStyle = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 9.sp, fontFamily = Tb.Mono) }
    val nameFit = remember(Tb.palette) { floatArrayOf(-1f, 12f) }   // [tile width it was fitted for, font size]
    val tel = state.telemetry
    val cols = 3; val rows = FX_ROWS.size
    val xy = remember { FloatArray(Fx.COUNT * 2) { 0.5f } }       // last XY per effect (glyph + readout)
    val since = remember { FloatArray(Fx.COUNT) { -10f } }        // clock time each effect engaged
    val wasOn = remember { BooleanArray(Fx.COUNT) }

    fun cell(x: Float, y: Float): Int {
        val c = (x / dims[0].coerceAtLeast(1) * cols).toInt().coerceIn(0, cols - 1)
        val r = (y / dims[1].coerceAtLeast(1) * rows).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }
    fun local(x: Float, y: Float): Pair<Float, Float> {
        val cw = dims[0].toFloat() / cols; val ch = dims[1].toFloat() / rows
        return ((x % cw) / cw).coerceIn(0f, 1f) to (1f - (y % ch) / ch).coerceIn(0f, 1f)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HwButton("LATCH", Modifier.fillMaxHeight(), active = latchMode.value, accent = Tb.Key, minHeight = 40.dp, fontSize = 12) { latchMode.value = !latchMode.value }
            if (state.tilt.available)
                HwButton("TILT", Modifier.fillMaxHeight(), active = state.tilt.enabled.value, accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) {
                    state.tilt.setEnabled(!state.tilt.enabled.value) }
            // XY: one surface for every latched effect (lights up once something is latched)
            HwButton("XY", Modifier.fillMaxHeight(), active = xyMode.value, enabled = latchedNow != 0, accent = Tb.EncBlue, minHeight = 40.dp, fontSize = 12) {
                xyMode.value = !xyMode.value; if (xyMode.value) latchMode.value = false }
            Text(when {
                    xyMode.value -> ""
                    latchMode.value -> "tap to latch"
                    state.tilt.enabled.value -> "lean"
                    else -> ""
                },
                color = Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Mono, maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 6.dp, top = 12.dp))
            HwButton("ALL DRY", Modifier.fillMaxHeight(), accent = Tb.Warn, minHeight = 40.dp, fontSize = 12) { g.stopAll() }
        }
        // NAV: A enters the tiles, the D-pad picks one, A held = punch in, X = latch.
        val navGrid = NavGrid(cols, rows,
            get = { state.fxCursor.intValue },
            set = { c -> state.fxCursor.intValue = c; state.fxCursorShown.value = true },
            down = { c -> val id = FX_GRID[c]; state.fxCursorShown.value = true; g.fxDown(id, g.fxX[id], g.fxY[id]) },
            up = { c -> g.fxUp(FX_GRID[c]) },
            alt = { c -> val id = FX_GRID[c]; g.toggleLatch(id) },
            describe = { c -> FX_INFO[FX_GRID[c]]?.name ?: "" },
            verb = "punch in")
        if (xyMode.value) LatchedXY(state, Modifier.weight(1f).fillMaxWidth())
        else Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
                .navTarget("FX PADS", grid = navGrid, corner = 8f)
                .pointerInteropFilter { ev ->
                    val i = ev.actionIndex
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            state.fxCursorShown.value = false
                            val id = FX_GRID[cell(ev.getX(i), ev.getY(i))]
                            val (x, y) = local(ev.getX(i), ev.getY(i))
                            if (latchMode.value) { xy[2 * id] = x; xy[2 * id + 1] = y; g.toggleLatch(id, x, y) } else {
                                fingers[ev.getPointerId(i)] = id
                                xy[2 * id] = x; xy[2 * id + 1] = y
                                g.fxDown(id, x, y)
                            }
                        }
                        MotionEvent.ACTION_MOVE -> for (p in 0 until ev.pointerCount) {
                            val id = fingers[ev.getPointerId(p)] ?: continue
                            val (x, y) = local(ev.getX(p), ev.getY(p))
                            xy[2 * id] = x; xy[2 * id + 1] = y
                            g.fxMove(id, x, y)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingers.remove(ev.getPointerId(i))?.let(g::fxUp)
                        MotionEvent.ACTION_CANCEL -> { fingers.values.forEach(g::fxUp); fingers.clear() }
                    }
                    true
                }
        ) {
            tel.frame.longValue
            val active = g.fxActiveMask()
            val latched = g.fxLatched.intValue
            val cw = size.width / cols; val ch = size.height / rows
            val gap = 3.dp.toPx(); val rad = CornerRadius(8.dp.toPx())
            val pad = 9.dp.toPx(); val hair = 1.dp.toPx()
            val beat = ((g.transport[0] / com.thorbox.model.PPQN) % 1.0).toFloat()
            for (k in FX_GRID.indices) {
                val id = FX_GRID[k]
                val row = k / cols
                val accent = rowAccent(row)
                val o = Offset((k % cols) * cw + gap, row * ch + gap)
                val s = Size(cw - gap * 2, ch - gap * 2)
                val on = active and (1 shl id) != 0
                if (on && !wasOn[id]) since[id] = tel.timeSec
                wasOn[id] = on
                val ex = if (on) g.fxEffX(id) else xy[2 * id]
                val ey = if (on) g.fxEffY(id) else xy[2 * id + 1]
                val tail = !on && (id == Fx.ECHO && NativeEngineTails.echo(g) || id == Fx.REVERB && NativeEngineTails.reverb(g))

                // tile: near-black, hairline edge; engaged = accent edge + a faint dot field (the XY pad)
                drawRoundRect(Tb.Void.copy(alpha = 0.92f), o, s, rad)
                drawRoundRect(if (on) accent else Tb.Line, o, s, rad, style = Stroke(if (on) 2f * hair else hair))
                val fieldTop = o.y + 30.dp.toPx(); val fieldBot = o.y + s.height - 20.dp.toPx()
                if (on) {
                    val step = 12.dp.toPx()
                    var yy = fieldTop + step / 2
                    while (yy < fieldBot) {
                        var xx = o.x + pad + step / 2
                        while (xx < o.x + s.width - pad) { drawCircle(Tb.Muted.copy(alpha = 0.35f), hair, Offset(xx, yy)); xx += step }
                        yy += step
                    }
                    // crosshair at the effect's XY (y up), with a small square marker
                    val px = o.x + pad + ex * (s.width - 2 * pad)
                    val py = fieldBot - ey * (fieldBot - fieldTop)
                    drawLine(accent.copy(alpha = 0.45f), Offset(px, fieldTop), Offset(px, fieldBot), hair)
                    drawLine(accent.copy(alpha = 0.45f), Offset(o.x + pad, py), Offset(o.x + s.width - pad, py), hair)
                    val m = 4.dp.toPx()
                    drawRect(accent, Offset(px - m, py - m), Size(2 * m, 2 * m), style = Stroke(1.5f * hair))
                }

                // header: index + name, and the group dot (filled = engaged, ring = ready, pulsing = tail).
                // One size for every tile: the biggest at which the longest name (RESONATOR, TAPE STOP)
                // still fits between the index and the dot, worked out once per tile width.
                val name = FX_INFO[id]?.name ?: "?"
                val dotRoom = 14.dp.toPx()
                val nx = o.x + pad + 20.dp.toPx()
                val room = o.x + s.width - pad - dotRoom - nx
                if (nameFit[0] != s.width) {
                    nameFit[0] = s.width; nameFit[1] = 8f
                    for (fs in floatArrayOf(12f, 11f, 10f, 9f, 8f)) {
                        val st = nameStyle.copy(fontSize = fs.sp, letterSpacing = if (fs >= 12f) 1.sp else 0.4.sp)
                        if (FX_INFO.values.all { tm.measure(it.name, st).size.width <= room }) { nameFit[1] = fs; break }
                    }
                }
                val ns = nameStyle.copy(fontSize = nameFit[1].sp, letterSpacing = if (nameFit[1] >= 12f) 1.sp else 0.4.sp)
                drawTextSafe(tm, String.format(java.util.Locale.US, "%02d", k + 1), o + Offset(pad, pad - 1f), idxStyle)
                drawTextSafe(tm, name, Offset(nx, o.y + pad - 3f), ns)
                val dot = Offset(o.x + s.width - pad - 4.dp.toPx(), o.y + pad + 6.dp.toPx())
                if (on) drawCircle(accent, 4.dp.toPx(), dot)
                else if (tail) drawCircle(accent.copy(alpha = 0.4f + 0.4f * sin(tel.timeSec * 8f)), 4.dp.toPx(), dot)
                else drawCircle(accent, 4.dp.toPx(), dot, style = Stroke(hair * 1.2f))

                // glyph: centre band, line drawing; ink = text colour, engaged parts in the accent
                val gO = Offset(o.x + pad + 4.dp.toPx(), fieldTop + 2.dp.toPx())
                val gS = Size(s.width - 2 * pad - 8.dp.toPx(), (fieldBot - fieldTop - 4.dp.toPx()).coerceAtLeast(8f))
                fxGlyph(id, gO, gS, on || tail, ex, ey, tel.timeSec, tel.timeSec - since[id], beat, state.viz.bass,
                    ink = if (on) Tb.Text else Tb.Text.copy(alpha = 0.55f), accent = accent, hair = hair)

                // footer: live readout while on, the two axes when idle
                val (rx, ry) = fxReadout(id, ex, ey)
                val info = FX_INFO[id]
                val fy = o.y + s.height - pad - 10.dp.toPx()
                if (on) {
                    var rs = readStyle
                    if (tm.measure(rx, rs).size.width + tm.measure(ry, rs).size.width > s.width - 2 * pad - 6.dp.toPx()) rs = readStyle.copy(fontSize = 9.sp)
                    drawTextSafe(tm, rx, Offset(o.x + pad, fy), rs.copy(color = accent))
                    val w = tm.measure(ry, rs).size.width
                    drawTextSafe(tm, ry, Offset(o.x + s.width - pad - w, fy), rs)
                } else if (info != null) {
                    var a = "↔ ${info.xLabel}"; var b = "↕ ${info.yLabel}"
                    val room = s.width - 2 * pad - 6.dp.toPx()
                    if (tm.measure(a, axisStyle).size.width + tm.measure(b, axisStyle).size.width > room) { a = info.xLabel; b = info.yLabel }
                    drawTextSafe(tm, a, Offset(o.x + pad, fy), axisStyle)
                    val w = tm.measure(b, axisStyle).size.width
                    drawTextSafe(tm, b, Offset(o.x + s.width - pad - w, fy), axisStyle)
                }

                // the buttons' cursor (D-pad picks a tile): a bright outline just inside the edge
                if (state.fxCursorShown.value && k == state.fxCursor.intValue)
                    drawRoundRect(Tb.Text, o + Offset(3f, 3f), Size(s.width - 6f, s.height - 6f), CornerRadius(6.dp.toPx()), style = Stroke(2.5f * hair))

                // latched: corner brackets
                if (latched and (1 shl id) != 0) {
                    val L = 10.dp.toPx(); val sw = 2.5f * hair; val c = Tb.Text
                    val x0 = o.x + 2f; val y0 = o.y + 2f; val x1 = o.x + s.width - 2f; val y1 = o.y + s.height - 2f
                    drawLine(c, Offset(x0, y0), Offset(x0 + L, y0), sw); drawLine(c, Offset(x0, y0), Offset(x0, y0 + L), sw)
                    drawLine(c, Offset(x1, y0), Offset(x1 - L, y0), sw); drawLine(c, Offset(x1, y0), Offset(x1, y0 + L), sw)
                    drawLine(c, Offset(x0, y1), Offset(x0 + L, y1), sw); drawLine(c, Offset(x0, y1), Offset(x0, y1 - L), sw)
                    drawLine(c, Offset(x1, y1), Offset(x1 - L, y1), sw); drawLine(c, Offset(x1, y1), Offset(x1, y1 - L), sw)
                }
            }
        }
    }
}

/**
 * The XY surface for latched effects: the whole deck becomes one pad. Touch and slide: every latched
 * effect takes the same X/Y (each shown with its own marker and live readout, in its row colour).
 * Lift: they stay latched where you left them. Tap a name chip to unlatch that one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LatchedXY(state: AppState, modifier: Modifier) {
    val g = state.groove
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 64)
    val dims = remember { IntArray(2) }
    val touch = remember { FloatArray(3) { -1f } }                 // x, y, down (1/0)
    val chipStyle = remember(Tb.palette) { TextStyle(color = Tb.Text, fontSize = 11.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold) }
    val readStyle = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontSize = 10.sp, fontFamily = Tb.Mono) }
    val chipH = remember { FloatArray(1) }
    val chipRects = remember { ArrayList<Pair<Int, android.graphics.RectF>>() }

    Canvas(modifier
        .onSizeChanged { dims[0] = it.width; dims[1] = it.height }
        .pointerInteropFilter { ev ->
            val top = chipH[0]
            fun send(px: Float, py: Float) {
                val x = (px / dims[0].coerceAtLeast(1)).coerceIn(0f, 1f)
                val y = (1f - (py - top) / (dims[1] - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
                touch[0] = x; touch[1] = y; touch[2] = 1f
                g.fxMoveLatched(x, y)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = chipRects.firstOrNull { it.second.contains(ev.x, ev.y) }
                    if (hit != null) { g.toggleLatch(hit.first); touch[2] = 0f } else send(ev.x, ev.y)
                }
                MotionEvent.ACTION_MOVE -> if (touch[2] == 1f) send(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch[2] = 0f
            }
            true
        }) {
        tel.frame.longValue
        val hair = 1.dp.toPx(); val pad = 10.dp.toPx()
        val latched = g.fxLatched.intValue
        drawRoundRect(Tb.Void.copy(alpha = 0.92f), Offset.Zero, size, CornerRadius(10.dp.toPx()))
        drawRoundRect(Tb.EncBlue, Offset.Zero, size, CornerRadius(10.dp.toPx()), style = Stroke(1.5f * hair))

        // chips: every latched effect, name + what it's doing; tap to unlatch
        chipRects.clear()
        var cx = pad; var cy = pad
        val ch = 34.dp.toPx()
        val ids = (0 until Fx.COUNT).filter { latched and (1 shl it) != 0 }
        for (id in ids) {
            val row = FX_ROWS.indexOfFirst { id in it.second }
            val acc = rowAccent(row)
            val name = FX_INFO[id]?.name ?: "?"
            val (rx, ry) = fxReadout(id, g.fxEffX(id), g.fxEffY(id))
            val w = maxOf(tm.measure(name, chipStyle).size.width, tm.measure("$rx · $ry", readStyle).size.width) + 2 * pad
            if (cx + w > size.width - pad) { cx = pad; cy += ch + 6.dp.toPx() }
            drawRoundRect(acc, Offset(cx, cy), Size(w, ch), CornerRadius(6.dp.toPx()), style = Stroke(1.5f * hair))
            drawTextSafe(tm, name, Offset(cx + pad, cy + 3.dp.toPx()), chipStyle.copy(color = acc))
            drawTextSafe(tm, "$rx · $ry", Offset(cx + pad, cy + 18.dp.toPx()), readStyle)
            chipRects += id to android.graphics.RectF(cx, cy, cx + w, cy + ch)
            cx += w + 6.dp.toPx()
        }
        val top = cy + ch + 10.dp.toPx()
        chipH[0] = top

        // the field: dot grid, axes, each effect's marker, the finger's crosshair
        val fh = size.height - top - pad; val fw = size.width - 2 * pad
        val step = 16.dp.toPx()
        var yy = top + step / 2
        while (yy < top + fh) { var xx = pad + step / 2; while (xx < pad + fw) { drawCircle(Tb.Muted.copy(alpha = 0.3f), hair, Offset(xx, yy)); xx += step }; yy += step }
        for (id in ids) {
            val acc = rowAccent(FX_ROWS.indexOfFirst { id in it.second })
            val px = pad + g.fxEffX(id) * fw; val py = top + (1f - g.fxEffY(id)) * fh
            drawCircle(acc, 6.dp.toPx(), Offset(px, py), style = Stroke(2f * hair))
        }
        if (touch[2] == 1f) {
            val px = pad + touch[0] * fw; val py = top + (1f - touch[1]) * fh
            drawLine(Tb.Text.copy(alpha = 0.5f), Offset(px, top), Offset(px, top + fh), hair)
            drawLine(Tb.Text.copy(alpha = 0.5f), Offset(pad, py), Offset(pad + fw, py), hair)
            drawRect(Tb.Text, Offset(px - 6.dp.toPx(), py - 6.dp.toPx()), Size(12.dp.toPx(), 12.dp.toPx()), style = Stroke(1.5f * hair))
        } else {
            var hint = "touch + slide: all ${ids.size} move together · tap a name to unlatch"
            if (tm.measure(hint, readStyle).size.width > size.width - 2 * pad) hint = "slide: all ${ids.size} move · tap a name to unlatch"
            if (tm.measure(hint, readStyle).size.width > size.width - 2 * pad) hint = "slide to steer all ${ids.size}"
            val m = tm.measure(hint, readStyle)
            drawTextSafe(tm, hint, Offset((size.width - m.size.width) / 2f, size.height - pad - m.size.height), readStyle)
        }
    }
}

/** Tails (echo repeats / reverb wash after release) for the pad dot; read from the engine telemetry. */
private object NativeEngineTails {
    fun echo(g: com.thorbox.app.groove.GrooveController) = g.transport[15] > 1e-3
    fun reverb(g: com.thorbox.app.groove.GrooveController) = g.transport[19] > 1e-3
}

/**
 * 16 LEVELS (MPC-style): spread the selected sound over all 16 pads, graded by VEL or TUNE (and on
 * the drum deck also DECAY or TONE). Pad 1 lowest, pad 16 highest. OFF puts the pads back.
 */
@Composable
fun LevelsStrip(state: AppState, drums: Boolean, modifier: Modifier = Modifier) {
    val lm = state.levelMode.value
    val src = state.levelSrc.intValue
    Row(modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // the label names the sound being spread while it's on (no extra text crammed at the end)
        val on = lm != com.thorbox.app.levels.LevelMode.OFF
        val src0 = if (drums) state.drums.kit.value.voices.getOrNull(src)?.name ?: "" else "PAD ${src + 1}"
        Text(if (on) src0 else "16 LEVELS", color = if (on) Tb.Key else Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black,
            letterSpacing = 1.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp).widthIn(max = 84.dp))
        com.thorbox.app.levels.LevelMode.entries.filter { drums || !it.drumsOnly }.forEach { m ->
            HwButton(m.label, Modifier.weight(1f).fillMaxHeight(), active = lm == m && m != com.thorbox.app.levels.LevelMode.OFF,
                accent = Tb.Key, minHeight = 32.dp, fontSize = 10, hPad = 1.dp) { state.setLevels(m) }
        }
    }
}

/** Note repeat: tap RPT to latch it on (tap again: off), or hold it while you play; hold L1 on the controller. */
@Composable
fun RepeatStrip(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val rate = g.groove.value.repeatRate
    Row(modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val downAt = androidx.compose.runtime.remember { longArrayOf(0L) }
        val wasLatched = androidx.compose.runtime.remember { booleanArrayOf(false) }
        HoldButton(if (g.repeatLatched.value) "RPT ●" else "RPT", g.repeatHeld.value, Tb.EncOrange, Modifier.width(64.dp).fillMaxHeight()) { down ->
            val now = android.os.SystemClock.uptimeMillis()
            if (down) { downAt[0] = now; wasLatched[0] = g.repeatLatched.value; g.setRepeatHeld(true) }
            else {
                g.setRepeatHeld(false)
                if (now - downAt[0] < 250) g.setRepeatLatched(!wasLatched[0])   // a tap latches / unlatches; a hold was momentary
            }
        }
        RepeatRate.entries.forEach { r ->
            HwButton(r.label, Modifier.weight(1f).fillMaxHeight(), active = r == rate, accent = Tb.EncWhite, fontSize = 11, hPad = 1.dp) { g.setRepeatRate(r) }
        }
    }
}

/**
 * One line drawing per effect. Idle: quiet ink, slow loop. Engaged: driven by the XY, the clock and
 * the music, with the moving part in the row's accent. Thin strokes, round caps, simple geometry.
 */
private fun DrawScope.fxGlyph(
    id: Int, o: Offset, s: Size, on: Boolean, x: Float, y: Float, t: Float, held: Float, beat: Float, bass: Float,
    ink: Color, accent: Color, hair: Float,
) {
    val w = s.width; val h = s.height
    val cx = o.x + w / 2f; val cy = o.y + h / 2f
    val sw = (if (on) 1.8f else 1.4f) * hair
    val hot = if (on) accent else ink
    val p = glyphPath
    when (id) {
        Fx.FILTER -> {
            // response curve: LP left of centre, HP right; Y = resonance bump
            p.reset()
            val hp = x > 0.5f
            val fc = if (hp) (x - 0.5f) * 2f else x * 2f
            val q = 0.15f + 0.85f * y
            val wob = if (on) 0.03f * bass * sin(t * 9f) else 0.015f * sin(t * 1.3f)
            for (i in 0..48) {
                val u = i / 48f
                val d = (u - (fc + wob)) * 7f
                val shelf = if (hp) 1f / (1f + exp(-d)) else 1f / (1f + exp(d))
                val bump = q * exp(-d * d * 2f) * 0.6f
                val yy = o.y + h * (0.92f - 0.7f * (shelf + bump).coerceAtMost(1.3f) / 1.3f)
                if (i == 0) p.moveTo(o.x + w * u, yy) else p.lineTo(o.x + w * u, yy)
            }
            drawLine(ink.copy(alpha = 0.3f), Offset(o.x, o.y + h * 0.92f), Offset(o.x + w, o.y + h * 0.92f), hair)
            drawPath(p, hot, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Fx.ISOLATOR -> {
            // three bands as level bars; the cut one drops away
            val pos = x * 2f; val depth = (y * 1.15f).coerceAtMost(1f)
            val k = floatArrayOf((1f - pos).coerceIn(0f, 1f), (1f - abs(pos - 1f)).coerceIn(0f, 1f), (pos - 1f).coerceIn(0f, 1f))
            val bw = w / 3f
            for (b in 0 until 3) {
                val gain = if (on) 1f - depth * k[b] else 1f
                val lvl = (0.55f + 0.35f * (if (on) bass else 0.3f + 0.2f * sin(t * 1.5f + b))) * gain
                val bx = o.x + b * bw + bw * 0.22f; val bwid = bw * 0.56f
                drawRect(ink.copy(alpha = 0.5f), Offset(bx, o.y), Size(bwid, h), style = Stroke(hair))
                drawRect(if (on && k[b] > 0.3f) accent else ink, Offset(bx + 2f * hair, o.y + h * (1f - lvl)), Size(bwid - 4f * hair, h * lvl - 2f * hair))
            }
        }
        Fx.LOFI -> {
            // a sine turned into stairs: X = rate (wider steps), Y = bits (taller steps)
            val levels = 2 + ((1f - y) * 12f).toInt()
            val stepW = w * (0.025f + 0.16f * x)
            val ph = t * (if (on) 2.5f else 0.6f)
            p.reset()
            var u = 0f; var first = true
            while (u < w) {
                val v = sin((u / w) * 6.283f * 1.5f + ph)
                val q = kotlin.math.round(v * levels) / levels
                val yy = cy - q * h * 0.4f
                if (first) { p.moveTo(o.x + u, yy); first = false } else p.lineTo(o.x + u, yy)
                p.lineTo(o.x + minOf(u + stepW, w), yy)
                u += stepW
            }
            drawPath(p, hot, style = Stroke(sw))
        }
        Fx.LOOPER -> {
            // a ring with the loop divisions marked; a dot runs round it (backwards in reverse)
            val r = minOf(w, h) * 0.42f
            val divs = intArrayOf(1, 2, 3, 4, 6, 8, 12)[looperDivIndex(x)]
            drawCircle(ink, r, Offset(cx, cy), style = Stroke(sw))
            for (i in 0 until divs.coerceAtLeast(1) * 2) {
                val a = (i / (divs * 2f)) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val l = if (i % 2 == 0) 0.2f else 0.1f
                drawLine(ink, Offset(cx + cos(a) * r, cy + sin(a) * r), Offset(cx + cos(a) * r * (1f - l), cy + sin(a) * r * (1f - l)), hair)
            }
            val sp = if (on) looperSpeed(y) else 0.25f
            val ph = (if (on) held else t) * sp * 1.4f
            val a = ph * 2f * PI.toFloat() - PI.toFloat() / 2f
            drawCircle(hot, 3.5f * hair, Offset(cx + cos(a) * r, cy + sin(a) * r))
            if (on && sp < 0f) { // reverse chevron in the middle
                drawLine(accent, Offset(cx + 5 * hair, cy - 5 * hair), Offset(cx - 3 * hair, cy), sw, cap = StrokeCap.Round)
                drawLine(accent, Offset(cx - 3 * hair, cy), Offset(cx + 5 * hair, cy + 5 * hair), sw, cap = StrokeCap.Round)
            }
        }
        Fx.SCATTER -> {
            // 8 slices; engaged, they shuffle every beat (a new order each time)
            val n = if (x < 0.5f) 8 else 6
            val bw = w / n
            val seed = if (on) (floor(held * 2f).toInt() + 1) * 7919 else 0
            for (i in 0 until n) {
                var j = i
                if (on) { val hsh = ((i + 1) * 2654435761L + seed).toInt(); if (((hsh ushr 8) and 0xFF) / 255f < 0.15f + 0.8f * y) j = Math.floorMod(hsh ushr 12, n) }
                val hh = h * (0.3f + 0.6f * ((j * 37 % n) + 1f) / n)
                val col = if (on && j != i) accent else ink
                drawRect(col, Offset(o.x + i * bw + bw * 0.15f, cy - hh / 2f), Size(bw * 0.7f, hh), style = if (on && j != i) androidx.compose.ui.graphics.drawscope.Fill else Stroke(hair))
            }
        }
        Fx.SLICER -> {
            // the 16-step gate pattern; the current step lights while engaged
            val pat = SLICE_PATTERNS[slicerPattern(x)]
            val duty = 0.15f + 0.8f * y
            val bw = w / 16f
            val cur = ((beat * 4f).toInt()) % 16    // 1/16 within the beat; good enough for the glyph
            for (i in 0 until 16) {
                val open = (pat shr i) and 1 == 1
                val bx = o.x + i * bw + bw * 0.12f
                if (open) drawRect(if (on && i % 4 == cur % 4) accent else ink, Offset(bx, cy - h * 0.3f), Size(bw * 0.76f * duty + bw * 0.1f, h * 0.6f))
                else drawLine(ink.copy(alpha = 0.4f), Offset(bx, cy), Offset(bx + bw * 0.76f, cy), hair)
            }
        }
        Fx.ECHO -> {
            // a dot and its repeats, spaced by the time, fading by the feedback
            val spacing = w * (0.08f + 0.06f * echoDivIndex(x))
            val fb = 0.25f + 0.8f * y
            var a = 1f; var i = 0
            val shift = if (on) (held * 1.5f) % 1f else (t * 0.2f) % 1f
            while (a > 0.08f && i < 10) {
                val px = o.x + 6 * hair + (i + shift) * spacing
                if (px > o.x + w - 4 * hair) break
                drawCircle((if (i == 0) hot else ink).copy(alpha = a), (5.5f - i * 0.35f).coerceAtLeast(1.5f) * hair, Offset(px, cy), style = if (i == 0) androidx.compose.ui.graphics.drawscope.Fill else Stroke(hair * 1.2f))
                a *= fb.coerceAtMost(0.95f); i++
            }
        }
        Fx.REVERB -> {
            // ripples spreading from the centre; bigger room = wider rings
            val maxR = minOf(w, h) * (0.25f + 0.3f * x)
            val speed = if (on) 0.9f else 0.25f
            for (i in 0 until 4) {
                val ph = ((t * speed + i / 4f) % 1f)
                drawCircle((if (on) accent else ink).copy(alpha = (1f - ph) * (if (on) 0.9f else 0.55f)), maxR * (0.15f + 0.85f * ph), Offset(cx, cy), style = Stroke(hair * 1.3f))
            }
            drawCircle(hot, 2.5f * hair, Offset(cx, cy))
        }
        Fx.RESONATOR -> {
            // a string between two posts ringing at a harmonic of the note; Y = how long it rings
            val n = 1 + (x * 4f).toInt()
            val amp = h * 0.38f * (if (on) (0.4f + 0.6f * y) * (0.6f + 0.4f * sin(t * 30f)) else 0.25f * sin(t * 2f))
            drawLine(ink, Offset(o.x, o.y + h * 0.1f), Offset(o.x, o.y + h * 0.9f), sw)
            drawLine(ink, Offset(o.x + w, o.y + h * 0.1f), Offset(o.x + w, o.y + h * 0.9f), sw)
            p.reset()
            for (i in 0..40) { val u = i / 40f; val yy = cy + sin(u * PI.toFloat() * n) * amp; if (i == 0) p.moveTo(o.x + w * u, yy) else p.lineTo(o.x + w * u, yy) }
            drawPath(p, hot, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Fx.VINYL -> {
            // a record (X = wow: it wobbles), tonearm, dust specks while engaged
            val r = minOf(w, h) * 0.46f
            val wob = (if (on) x else 0.1f) * r * 0.1f * sin(t * 3f)
            val rc = Offset(cx - w * 0.08f + wob, cy)
            drawCircle(ink, r, rc, style = Stroke(sw))
            drawCircle(ink.copy(alpha = 0.4f), r * 0.68f, rc, style = Stroke(hair))
            drawCircle(hot, r * 0.2f, rc, style = Stroke(sw))
            val a = t * (if (on) 4f else 1f)
            drawCircle(hot, 1.8f * hair, Offset(rc.x + cos(a) * r * 0.2f, rc.y + sin(a) * r * 0.2f))
            drawLine(ink, Offset(o.x + w * 0.95f, o.y + h * 0.05f), Offset(rc.x + r * 0.55f, rc.y - r * 0.15f), sw, cap = StrokeCap.Round)
            if (on) for (i in 0 until (y * 10f).toInt()) {
                val hsh = ((i + 1) * 2654435761L + (t * 12f).toInt() * 40503L).toInt()
                val ra = ((hsh ushr 8) and 0xFFF) / 4095f * 6.283f; val rd = ((hsh ushr 20) and 0xFFF) / 4095f * r
                drawCircle(accent, 1.4f * hair, Offset(rc.x + cos(ra) * rd, rc.y + sin(ra) * rd))
            }
        }
        Fx.CASSETTE -> {
            // shell, window and two reels; the reels wobble with age
            val bx = o.x + w * 0.05f; val bwid = w * 0.9f
            val top = cy - h * 0.42f; val bh = h * 0.84f
            drawRoundRect(ink, Offset(bx, top), Size(bwid, bh), CornerRadius(4f * hair), style = Stroke(sw))
            drawRoundRect(ink.copy(alpha = 0.5f), Offset(bx + bwid * 0.2f, top + bh * 0.25f), Size(bwid * 0.6f, bh * 0.45f), CornerRadius(3f * hair), style = Stroke(hair))
            val rr = bh * 0.16f
            val wob = if (on) x * 0.6f * sin(t * 5.7f) else 0f
            val ang = t * (if (on) 5f + wob else 1.2f)
            for (side in floatArrayOf(-1f, 1f)) {
                val rc = Offset(cx + side * bwid * 0.18f, top + bh * 0.47f)
                drawCircle(hot, rr, rc, style = Stroke(sw))
                for (k in 0 until 3) { val a = ang + k * 2.094f; drawLine(hot, rc, Offset(rc.x + cos(a) * rr * 0.8f, rc.y + sin(a) * rr * 0.8f), hair) }
            }
            drawLine(ink.copy(alpha = 0.5f), Offset(bx + bwid * 0.3f, top + bh), Offset(bx + bwid * 0.38f, top + bh * 0.82f), hair)
            drawLine(ink.copy(alpha = 0.5f), Offset(bx + bwid * 0.7f, top + bh), Offset(bx + bwid * 0.62f, top + bh * 0.82f), hair)
        }
        Fx.TAPE_STOP -> {
            // a waveform that stretches out and flattens as it stops (or whips backwards for SPIN)
            val spin = x >= 0.5f
            val dur = 0.08f + 1.9f * y * y
            val k = if (on) (held / dur).coerceIn(0f, 1f) else 0f
            val freq = 3f * (1f - 0.85f * k)
            val amp = h * 0.38f * (1f - k)
            val ph = if (on) (if (spin) -held * 12f else held * 4f * (1f - k)) else t
            p.reset()
            for (i in 0..48) { val u = i / 48f; val yy = cy + sin(u * 6.283f * freq + ph) * (if (on) amp else h * 0.3f); if (i == 0) p.moveTo(o.x + w * u, yy) else p.lineTo(o.x + w * u, yy) }
            drawPath(p, hot, style = Stroke(sw, cap = StrokeCap.Round))
            // ■ stop / ◀◀ spin mark
            val m = 5f * hair; val mx = o.x + w - m * 2.5f; val my = o.y + m
            if (spin) {
                for (d in 0..1) { val ox = mx - d * m * 1.2f
                    drawLine(ink, Offset(ox + m, my), Offset(ox, my + m), hair * 1.3f); drawLine(ink, Offset(ox, my + m), Offset(ox + m, my + 2 * m), hair * 1.3f) }
            } else drawRect(ink, Offset(mx - m / 2, my), Size(m * 1.6f, m * 1.6f), style = Stroke(hair * 1.3f))
        }
    }
}

// Reused every frame by the glyphs (UI thread only): no per-frame allocation.
private val glyphPath = androidx.compose.ui.graphics.Path()
