// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable
import com.thorbox.app.input.navModal
import com.thorbox.app.input.navTarget
import com.thorbox.app.sampler.PadSlot
import com.thorbox.model.PlayMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * PAD EDIT: the selected pad's sound, in the style of a classic hardware sampler's backlit LCD,
 * with soft keys under it. Five pages:
 *
 *   PARAMS  tune · fine · level · pan · reverse · warp           (picture: the pad's slice, the way it plays)
 *   AMP     attack · decay · release · velocity → level           (picture: the envelope)
 *   FILTER  low-pass cutoff · resonance                            (picture: the response curve)
 *   MUTE    1 AT A TIME (track) · mute group · two mute targets                          (picture: the 16 pads; tap one to make it a target)
 *   PLAY    one shot / note on / loop · voice overlap mono / poly  (picture: how a second hit behaves)
 *
 * Every field: tap its left half for −, right half for +, drag sideways to sweep, or NAV it (A grabs,
 * ◀▶ fine, ▲▼ coarse, X resets). ◀ PAD ▶ in the title steps through the pads; playing a pad selects it too.
 * The LCD's colours are its own (it's the instrument's screen), the same on every theme.
 */

private val LCD_BG = Color(0xFFB3C7BC)
private val LCD_BG_DARK = Color(0xFF9DB3A8)
private val LCD_INK = Color(0xFF1B2830)
private val LCD_DIM = Color(0x801B2830)
private val CASE = Color(0xFF26262B)
private val CASE_EDGE = Color(0xFF3A3A41)
private val KEY = Color(0xFF55565E)
private val KEY_TOP = Color(0xFF6A6B74)

/** How wide a field's value is: narrower on small screens so the labels keep their room. */
private val LocalLcdValueW = androidx.compose.runtime.compositionLocalOf { 96.dp }

private enum class PadPage(val label: String) { PARAMS("PARAMS"), AMP("AMP"), FILTER("FILTER"), MUTE("MUTE"), PLAY("PLAY") }

private val ATTACKS = floatArrayOf(0f, 1f, 2f, 5f, 10f, 20f, 30f, 50f, 75f, 100f, 150f, 200f, 300f, 500f, 750f, 1000f, 1500f, 2000f, 3000f, 5000f)
private val DECAYS = floatArrayOf(0f, 20f, 40f, 60f, 80f, 100f, 150f, 200f, 300f, 400f, 500f, 750f, 1000f, 1500f, 2000f, 3000f, 5000f, 10000f, 20000f)
private val RELEASES = floatArrayOf(1f, 5f, 10f, 15f, 20f, 30f, 50f, 75f, 100f, 150f, 200f, 300f, 500f, 750f, 1000f, 1500f, 2000f, 3000f, 5000f)

/** The nearest entry of [table] to [v], moved [steps] along it. */
private fun stepTable(table: FloatArray, v: Float, steps: Int): Float {
    var i = 0; var best = Float.MAX_VALUE
    for (k in table.indices) { val d = abs(table[k] - v); if (d < best) { best = d; i = k } }
    return table[(i + steps).coerceIn(0, table.size - 1)]
}

private fun fmtMs(ms: Float) = when {
    ms >= 1000f -> String.format(Locale.US, "%.1f s", ms / 1000f)
    else -> "${ms.roundToInt()} ms"
}
private fun cutoffHz(c: Float) = 20f * 1000f.pow(c)
private fun fmtHz(hz: Float) = if (hz >= 1000f) String.format(Locale.US, "%.1f kHz", hz / 1000f) else "${hz.roundToInt()} Hz"
private fun fmtPan(p: Float): String { val v = (p * 100).roundToInt(); return if (v == 0) "C" else if (v < 0) "L${-v}" else "R$v" }
private fun modeName(m: PlayMode) = when (m) { PlayMode.ONE_SHOT -> "ONE SHOT"; PlayMode.GATE -> "NOTE ON"; PlayMode.LOOP -> "LOOP" }

@Composable
fun PadEditSheet(state: AppState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sm = state.sampler
    val page = state.padEditPage
    val sel = sm.selected.intValue
    val pad = sm.pads.value[sel]
    val peaks by state.peaks.collectAsState()
    fun edit(f: (PadSlot) -> PadSlot) = sm.updatePad(sel, f)

    Column(
        modifier.navModal(onClose).clip(RoundedCornerShape(16.dp)).background(CASE)
            .border(2.dp, CASE_EDGE, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- the LCD ----
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(LCD_BG)
                .border(3.dp, Color(0xFF15151A), RoundedCornerShape(6.dp))
                .drawBehind {                                           // faint pixel rows, like a real LCD
                    val step = 3.dp.toPx(); var y = 0f
                    while (y < size.height) { drawLine(LCD_BG_DARK.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // title bar, inverted: page · ◀ PAD nn ▶ · play mode
            Row(Modifier.fillMaxWidth().height(28.dp).background(LCD_INK).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LcdText(PadPage.entries[page.intValue].label, color = LCD_BG, size = 14, modifier = Modifier.weight(1f))
                LcdText("◀", color = LCD_BG, size = 16, modifier = Modifier.width(34.dp).fillMaxHeight()
                    .navClickable(label = "PREVIOUS PAD") { sm.selected.intValue = (sel + 15) % 16 }, center = true)
                LcdText(String.format(Locale.US, "PAD %02d", sel + 1), color = LCD_BG, size = 15, center = true, modifier = Modifier.width(76.dp))
                LcdText("▶", color = LCD_BG, size = 16, modifier = Modifier.width(34.dp).fillMaxHeight()
                    .navClickable(label = "NEXT PAD") { sm.selected.intValue = (sel + 1) % 16 }, center = true)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < 440.dp
            val fields: @Composable (Modifier) -> Unit = { fm ->
                Column(fm, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    when (PadPage.entries[page.intValue]) {
                        PadPage.PARAMS -> {
                            LcdField("TUNE", String.format(Locale.US, "%+d st", pad.pitch), reset = { edit { it.copy(pitch = 0) } }) { n -> edit { it.copy(pitch = it.pitch + n) } }
                            LcdField("FINE", String.format(Locale.US, "%+d ct", pad.fine), reset = { edit { it.copy(fine = 0) } }) { n -> edit { it.copy(fine = it.fine + n) } }
                            LcdField("LEVEL", String.format(Locale.US, "%+.1f dB", pad.gainDb), reset = { edit { it.copy(gainDb = 0f) } }) { n -> edit { it.copy(gainDb = it.gainDb + n * 0.5f) } }
                            LcdField("PAN", fmtPan(pad.pan), reset = { edit { it.copy(pan = 0f) } }) { n -> edit { it.copy(pan = ((it.pan * 50).roundToInt() + n) / 50f) } }
                            LcdField("REVERSE", if (pad.reverse) "ON" else "OFF", reset = { edit { it.copy(reverse = false) } }) { _ -> edit { it.copy(reverse = !it.reverse) } }
                            LcdField("WARP", if (pad.warp) "ON" else "OFF", reset = { if (pad.warp) sm.toggleWarp(sel) }) { _ -> sm.toggleWarp(sel) }
                        }
                        PadPage.AMP -> {
                            LcdField("ATTACK", fmtMs(pad.attackMs), reset = { edit { it.copy(attackMs = 0f) } }) { n -> edit { it.copy(attackMs = stepTable(ATTACKS, it.attackMs, n)) } }
                            LcdField("DECAY", if (pad.decayMs <= 0f) "HOLD" else fmtMs(pad.decayMs), reset = { edit { it.copy(decayMs = 0f) } }) { n -> edit { it.copy(decayMs = stepTable(DECAYS, it.decayMs, n)) } }
                            LcdField("RELEASE", fmtMs(pad.releaseMs), reset = { edit { it.copy(releaseMs = 15f) } }) { n -> edit { it.copy(releaseMs = stepTable(RELEASES, it.releaseMs, n)) } }
                            LcdField("VELO>LEVEL", "${(pad.velSens * 100).roundToInt()}%", reset = { edit { it.copy(velSens = 1f) } }) { n -> edit { it.copy(velSens = ((it.velSens * 20).roundToInt() + n) / 20f) } }
                            LcdHint(if (pad.decayMs <= 0f) "DECAY HOLD: plays to the end of its slice" else "fades out ${fmtMs(pad.decayMs)} after the hit")
                        }
                        PadPage.FILTER -> {
                            LcdField("CUTOFF", if (pad.cutoff >= 0.995f) "OFF" else fmtHz(cutoffHz(pad.cutoff)), reset = { edit { it.copy(cutoff = 1f) } }) { n -> edit { it.copy(cutoff = ((it.cutoff * 50).roundToInt() + n) / 50f) } }
                            LcdField("RESONANCE", "${(pad.reso * 100).roundToInt()}%", reset = { edit { it.copy(reso = 0f) } }) { n -> edit { it.copy(reso = ((it.reso * 20).roundToInt() + n) / 20f) } }
                            LcdHint("low-pass, per hit · OFF = untouched")
                        }
                        PadPage.MUTE -> {
                            // track-wide: every pad on this sampler track
                            LcdField("1 AT A TIME", if (sm.chokeAll.value) "ON" else "OFF", reset = { sm.setChokeAll(true) }) { _ -> sm.setChokeAll(!sm.chokeAll.value) }
                            LcdField("MUTE GROUP", if (pad.muteGroup == 0) "OFF" else "${pad.muteGroup}", reset = { edit { it.copy(muteGroup = 0) } }) { n -> edit { it.copy(muteGroup = (it.muteGroup + n).coerceIn(0, 16)) } }
                            LcdField("TARGET 1", if (pad.muteA < 0) "OFF" else "PAD ${pad.muteA + 1}", reset = { edit { it.copy(muteA = -1) } }) { n -> edit { it.copy(muteA = (it.muteA + n).coerceIn(-1, 15)) } }
                            LcdField("TARGET 2", if (pad.muteB < 0) "OFF" else "PAD ${pad.muteB + 1}", reset = { edit { it.copy(muteB = -1) } }) { n -> edit { it.copy(muteB = (it.muteB + n).coerceIn(-1, 15)) } }
                            LcdHint(if (sm.chokeAll.value) "1 AT A TIME (all pads): any pad cuts the one before it. OFF = pads layer; groups and targets still cut"
                                    else "pads layer · pads in one group cut each other (open / closed hat); a target is cut when this pad plays")
                        }
                        PadPage.PLAY -> {
                            LcdField("PLAY MODE", modeName(pad.mode), reset = { edit { it.copy(mode = PlayMode.ONE_SHOT) } }) { n ->
                                edit { it.copy(mode = PlayMode.entries[(it.mode.ordinal + (if (n > 0) 1 else 2)) % 3]) }
                            }
                            LcdField("OVERLAP", if (pad.poly) "POLY" else "MONO", reset = { edit { it.copy(poly = false) } }) { _ -> edit { it.copy(poly = !it.poly) } }
                            LcdHint(when (pad.mode) {
                                PlayMode.ONE_SHOT -> "ONE SHOT: plays the whole slice, however short the tap"
                                PlayMode.GATE -> "NOTE ON: plays while held, fades out over RELEASE"
                                PlayMode.LOOP -> "LOOP: loops while held"
                            })
                            LcdHint(if (pad.poly) "POLY: a new hit plays over the last one" else "MONO: a new hit cuts the last one")
                        }
                    }
                }
            }
            // the picture for this page
            val picture: @Composable (Modifier) -> Unit = { pm ->
                Box(pm.border(1.dp, LCD_DIM).padding(6.dp)) {
                    when (PadPage.entries[page.intValue]) {
                        PadPage.PARAMS -> SlicePicture(state, sel, pad, peaks)
                        PadPage.AMP -> EnvPicture(pad)
                        PadPage.FILTER -> FilterPicture(pad)
                        PadPage.MUTE -> MuteGrid(state, sel, pad)
                        PadPage.PLAY -> PlayPicture(pad)
                    }
                }
            }
            if (!narrow) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fields(Modifier.weight(1.25f).fillMaxHeight()); picture(Modifier.weight(1f).fillMaxHeight())
            } else androidx.compose.runtime.CompositionLocalProvider(LocalLcdValueW provides 76.dp) {
                // narrow: the fields on top, the picture under them (the MUTE grid keeps a usable size)
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields(Modifier.fillMaxWidth().weight(1.3f)); picture(Modifier.fillMaxWidth().weight(1f))
                }
            }
            }
            // soft-key labels along the bottom of the LCD, the current page inverted
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadPage.entries.forEachIndexed { i, p ->
                    val on = i == page.intValue
                    Box(Modifier.weight(1f).fillMaxHeight().background(if (on) LCD_INK else Color.Transparent).border(1.dp, LCD_INK),
                        contentAlignment = Alignment.Center) { LcdText(p.label, color = if (on) LCD_BG else LCD_INK, size = 12, center = true) }
                }
                Box(Modifier.weight(1f).fillMaxHeight().border(1.dp, LCD_INK), contentAlignment = Alignment.Center) { LcdText("EXIT", size = 12, center = true) }
            }
        }
        // ---- the soft keys under the LCD (F1…F6) ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PadPage.entries.forEachIndexed { i, p -> SoftKey("F${i + 1}", p.label, Modifier.weight(1f)) { page.intValue = i } }
            SoftKey("F6", "EXIT", Modifier.weight(1f)) { onClose() }
        }
    }
}

@Composable
private fun LcdText(text: String, modifier: Modifier = Modifier, color: Color = LCD_INK, size: Int = 14, center: Boolean = false, bold: Boolean = true) {
    Box(modifier, contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        Text(text, color = color, fontFamily = Tb.Mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Clip, textAlign = if (center) TextAlign.Center else TextAlign.Start, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun LcdHint(text: String) {
    Text(text, color = LCD_DIM, fontFamily = Tb.Mono, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp))
}

/**
 * One LCD field: LABEL ............ ◀ VALUE ▶. Tap the left half for −1, the right half for +1, drag
 * sideways to sweep (a step per 14 dp), or NAV it: A grabs, ◀▶ = 1 step, ▲▼ = 8 steps, X resets.
 */
@Composable
private fun LcdField(label: String, value: String, reset: () -> Unit, step: (Int) -> Unit) {
    val acc = remember { floatArrayOf(0f) }
    val drag = remember { floatArrayOf(0f) }
    Row(
        Modifier.fillMaxWidth().height(30.dp)
            .navTarget(label, adjust = { d ->
                acc[0] += d * 100f
                while (acc[0] >= 1f) { acc[0] -= 1f; step(+1) }
                while (acc[0] <= -1f) { acc[0] += 1f; step(-1) }
            }, reset = reset, corner = 2f)
            .pointerInput(label) {
                detectTapGestures(onDoubleTap = { reset() }) { o -> step(if (o.x < size.width / 2f) -1 else +1) }
            }
            .pointerInput(label + "drag") {
                val px = 14.dp.toPx()
                detectHorizontalDragGestures(onDragStart = { drag[0] = 0f }) { ch, dx ->
                    drag[0] += dx
                    while (drag[0] >= px) { drag[0] -= px; step(+1) }
                    while (drag[0] <= -px) { drag[0] += px; step(-1) }
                    ch.consume()
                }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LcdText(label, size = 13, modifier = Modifier.weight(1f))
        LcdText("◀", color = LCD_DIM, size = 12)
        LcdText(value, size = 15, center = true, modifier = Modifier.width(LocalLcdValueW.current))
        LcdText("▶", color = LCD_DIM, size = 12)
    }
}

/** A grey hardware key with its F-number and the label it triggers. */
@Composable
private fun SoftKey(fn: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(KEY)
            .drawBehind { drawRect(KEY_TOP, size = Size(size.width, size.height * 0.45f)) }
            .navClickable(label = label) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(fn, color = Color(0xFFE6E6EA), fontSize = 10.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color(0xFFBFC0C8), fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ----------------------------------------------------------------------------- pictures (ink on the LCD)

/** The pad's slice as LCD bars, the way it plays (reversed = drawn backwards, with an arrow), at its level; the pan marker below. */
@Composable
private fun SlicePicture(state: AppState, sel: Int, pad: PadSlot, peaks: FloatArray?) {
    val sm = state.sampler
    val r = sm.regions.value.getOrNull(sel)
    val frames = sm.originalFrames
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val mid = h * 0.5f
        drawLine(LCD_DIM, Offset(0f, mid), Offset(w, mid), 1f)
        if (r == null || peaks == null || frames <= 0) return@Canvas
        val buckets = peaks.size / 3
        val b0 = (r.start.toDouble() / frames * buckets).toInt().coerceIn(0, buckets - 1)
        val b1 = (r.end.toDouble() / frames * buckets).toInt().coerceIn(b0 + 1, buckets)
        val cols = (w / 3f).toInt().coerceAtLeast(1)
        var peak = 1e-6f
        for (b in b0 until b1) peak = maxOf(peak, -peaks[3 * b], peaks[3 * b + 1])
        val gain = 10f.pow(pad.gainDb / 20f)
        for (c in 0 until cols) {
            val src = if (pad.reverse) cols - 1 - c else c
            val a = b0 + (b1 - b0) * src / cols; val e = maxOf(a + 1, b0 + (b1 - b0) * (src + 1) / cols)
            var mx = 0f
            for (b in a until minOf(e, buckets)) mx = maxOf(mx, -peaks[3 * b], peaks[3 * b + 1])
            val v = (mx / peak * gain).coerceAtMost(1.1f) * h * 0.42f
            drawRect(LCD_INK, Offset(c * 3f, mid - v), Size(2f, maxOf(1f, 2 * v)))
        }
        if (pad.reverse) {                                   // ◀ arrow: it plays right to left
            val y = 8f
            drawLine(LCD_INK, Offset(w * 0.15f, y), Offset(w * 0.85f, y), 2f)
            drawLine(LCD_INK, Offset(w * 0.15f, y), Offset(w * 0.15f + 8f, y - 5f), 2f)
            drawLine(LCD_INK, Offset(w * 0.15f, y), Offset(w * 0.15f + 8f, y + 5f), 2f)
        }
        // pan: a marker along the bottom, C in the middle
        val px = w * (0.5f + pad.pan * 0.5f)
        drawLine(LCD_DIM, Offset(0f, h - 2f), Offset(w, h - 2f), 1f)
        drawRect(LCD_INK, Offset(px - 3f, h - 7f), Size(6f, 7f))
    }
}

/** Attack ramp, then hold (or a decay to silence), then the release after a note-off, to one time scale. */
@Composable
private fun EnvPicture(pad: PadSlot) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val a = maxOf(1f, pad.attackMs); val d = pad.decayMs; val rl = pad.releaseMs
        val hold = if (d > 0f) d else maxOf(300f, a)                 // a visible sustain when DECAY = HOLD
        val total = a + hold + (if (d > 0f) 0f else rl)
        fun x(ms: Float) = ms / total * w
        val top = h * 0.12f; val bot = h * 0.92f
        val p = Path().apply {
            moveTo(0f, bot); lineTo(x(a), top)
            if (d > 0f) lineTo(x(a + d), bot)
            else { lineTo(x(a + hold), top); lineTo(x(a + hold + rl), bot) }
        }
        drawLine(LCD_DIM, Offset(0f, bot), Offset(w, bot), 1f)
        drawPath(p, LCD_INK, style = Stroke(2.5f))
        if (d <= 0f) {                                               // where the note-off lands
            val nx = x(a + hold)
            var y = top; while (y < bot) { drawLine(LCD_DIM, Offset(nx, y), Offset(nx, y + 4f), 1f); y += 8f }
        }
    }
}

/** A 2-pole low-pass response on log axes (20 Hz … 20 kHz), with the resonance bump. */
@Composable
private fun FilterPicture(pad: PadSlot) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val fc = cutoffHz(pad.cutoff); val q = 0.5f + pad.reso * 9.5f
        val p = Path()
        val n = 80
        for (i in 0..n) {
            val f = 20f * 1000f.pow(i / n.toFloat())
            val g = if (pad.cutoff >= 0.995f) 1f else {
                val x = f / fc
                (1f / sqrt((1f - x * x) * (1f - x * x) + (x / q) * (x / q))).coerceAtMost(8f)
            }
            val db = (20f * ln(maxOf(g, 1e-4f)) / ln(10f)).coerceIn(-48f, 18f)
            val y = h * (0.25f - db / 66f * 0.75f)
            if (i == 0) p.moveTo(i / n.toFloat() * w, y) else p.lineTo(i / n.toFloat() * w, y)
        }
        drawLine(LCD_DIM, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), 1f)      // 0 dB
        drawPath(p, LCD_INK, style = Stroke(2.5f))
    }
}

/**
 * The 16 pads (bottom-left = PAD 1): this pad solid, pads in its mute group hatched, its targets
 * boxed with a T. Tap a pad to make it a target (fills TARGET 1, then 2); tap a target to clear it.
 */
@Composable
private fun MuteGrid(state: AppState, sel: Int, pad: PadSlot) {
    val sm = state.sampler
    val pads = sm.pads.value
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (rowTop in 0 until 4) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (col in 0 until 4) {
                val k = (3 - rowTop) * 4 + col
                val me = k == sel
                // 1 AT A TIME cuts every other pad: draw them all hatched, like one big group
                val grouped = !me && (sm.chokeAll.value || pad.muteGroup > 0 && pads[k].muteGroup == pad.muteGroup)
                val target = !me && (pad.muteA == k || pad.muteB == k)
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (me) LCD_INK else Color.Transparent)
                        .border(if (target) 2.5.dp else 1.dp, if (me) LCD_INK else if (target) LCD_INK else LCD_DIM)
                        .drawBehind {
                            if (grouped) { var x = -size.height; while (x < size.width) { drawLine(LCD_DIM, Offset(x, size.height), Offset(x + size.height, 0f), 1.5f); x += 6f } }
                        }
                        .navClickable(enabled = !me, label = "TARGET PAD ${k + 1}") {
                            sm.updatePad(sel) {
                                when {
                                    it.muteA == k -> it.copy(muteA = -1)
                                    it.muteB == k -> it.copy(muteB = -1)
                                    it.muteA < 0 -> it.copy(muteA = k)
                                    it.muteB < 0 -> it.copy(muteB = k)
                                    else -> it.copy(muteA = it.muteB, muteB = k)        // full: the oldest target makes room
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    LcdText(if (target) "T${k + 1}" else "${k + 1}", color = if (me) LCD_BG else LCD_INK, size = 11, center = true)
                }
            }
        }
    }
}

/** Two hits on a time line: mono cuts the first where the second lands, poly lets them overlap; loop / note on drawn as such. */
@Composable
private fun PlayPicture(pad: PadSlot) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        fun hit(x0: Float, x1: Float, y: Float, cut: Float?) {
            val end = cut ?: x1
            var x = x0
            while (x < end) {
                val env = exp(-(x - x0) / (w * 0.35f))
                val a = h * 0.13f * env
                drawLine(LCD_INK, Offset(x, y - a), Offset(x, y + a), 1.6f); x += 3f
            }
            drawLine(LCD_INK, Offset(x0, y - h * 0.16f), Offset(x0, y + h * 0.16f), 2f)        // the hit
        }
        val y1 = h * 0.3f; val y2 = h * 0.7f
        val second = w * 0.45f
        when (pad.mode) {
            PlayMode.LOOP -> {                                       // a loop arrow over a repeating strip
                for (k in 0 until 3) hit(k * w / 3f, (k + 1) * w / 3f - 2f, y1, null)
                drawLine(LCD_INK, Offset(w * 0.1f, h * 0.06f), Offset(w * 0.9f, h * 0.06f), 1.5f)
            }
            else -> hit(0f, w * 0.95f, y1, if (pad.poly) null else second)
        }
        hit(second, w * 0.98f, if (pad.poly) y2 else y1, null)
        if (pad.mode == PlayMode.GATE) {                               // held → released
            drawLine(LCD_DIM, Offset(0f, h - 4f), Offset(w * 0.3f, h - 4f), 4f)
        }
    }
}

