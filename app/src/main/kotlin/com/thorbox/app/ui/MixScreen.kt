// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui


import com.thorbox.app.input.navAdjustable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Fader law: 0 dB sits at 75% travel; the bottom quarter is a gentle fade to −60 (off). */
fun dbToFader(db: Float): Float = if (db >= 0f) 0.75f + db / 6f * 0.25f else 0.75f * (1f - sqrt((-db / 60f).coerceIn(0f, 1f)))
fun faderToDb(n: Float): Float = if (n >= 0.75f) (n - 0.75f) / 0.25f * 6f else -60f * (1f - n / 0.75f).let { it * it }
private fun meterNorm(peak: Float): Float = if (peak <= 1e-4f) 0f else dbToFader((20f * log10(peak)).coerceIn(-60f, 6f))

/** Colour of each instrument kind, used on strips, chips and the add-track picker. */
/** A track's colour is its section's colour (sampler = SAMPLE, …), the same on the rail, the deck tabs and here. */
fun kindColor(k: com.thorbox.model.TrackKind) = k.section?.color ?: Tb.Dim

/**
 * MIXER: one strip per track, then + (add a track) and MASTER. Tap a strip's name to open that
 * track in its editor; tap ⋯ for its menu (PUMP sidechain, remove). Drag a fader anywhere along
 * its height; double-tap it for 0 dB. Meters are post-fader peaks, drawn in the draw phase.
 */
@Composable
fun MixScreen(state: AppState) {
    val g = state.groove
    val chans = g.channels.value
    val tracks = state.tracks.value
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var eqFor by state.mixEq
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    // header: name tag + what's here (same row height as every other screen's header)
    Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTag(state, Section.MIXER)
        val n = tracks.count { it.kind != com.thorbox.model.TrackKind.EMPTY }
        Text("$n TRACK${if (n == 1) "" else "S"} · name = open · EQ = tone · ⋯ = DUCK", style = Type.label, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(String.format(java.util.Locale.US, "MASTER %+.1f dB", g.masterDb.floatValue), style = Type.value, maxLines = 1)
    }
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tracks.forEachIndexed { i, tr ->
            if (tr.kind == com.thorbox.model.TrackKind.EMPTY) return@forEachIndexed
            val ch = chans[i]
            Strip(
                state, name = tr.name, color = kindColor(tr.kind), selected = g.mixSelected.intValue == i,
                db = ch.volumeDb, pan = ch.pan, mute = ch.mute, solo = ch.solo, meterIdx = i * 2,
                onDb = { d -> g.mixSelected.intValue = i; g.setChannel(i) { it.copy(volumeDb = d) } },
                onPan = { p -> g.setChannel(i) { it.copy(pan = p) } },
                onMute = { g.setChannel(i) { it.copy(mute = !it.mute) } },
                onSolo = { g.setChannel(i) { it.copy(solo = !it.solo) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onName = { state.selectTrack(i) }, onMenu = { menuFor = i },
                pump = g.duck.value.let { d -> if (d.on && d.isTarget(i)) d.mix else 0f }, scIdx = i,
                eq = ch.eq, onEq = { g.mixSelected.intValue = i; eqFor = i },
            )
        }
        Column(Modifier.width(56.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tracks.any { it.kind == com.thorbox.model.TrackKind.EMPTY }) {
            Box(Modifier.width(56.dp).weight(1f).clip(RoundedCornerShape(14.dp)).border(2.dp, Tb.Line, RoundedCornerShape(14.dp))
                .navClickable(label = "ADD TRACK") { Info.show("ADD TRACK"); adding = true }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = Tb.Text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("TRACK", color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
        HwButton("RESAMPLE", Modifier.fillMaxWidth(), minHeight = 52.dp, fontSize = 11, hPad = 2.dp, accent = Tb.Good) {
            state.exporter.resample.value = state.exporter.resample.value.copy(source = -1); state.exporter.showResample.value = true
        }
        HwButton("SAVE MIDI", Modifier.fillMaxWidth(), minHeight = 52.dp, fontSize = 11, hPad = 2.dp, accent = Tb.Tempo) { state.exportMidi() }
        }
        Strip(
            state, name = "MASTER", color = Tb.EncWhite, selected = g.mixSelected.intValue == MASTER_STRIP,
            db = g.masterDb.floatValue, pan = null, mute = null, solo = null, meterIdx = 2 * com.thorbox.model.TRACK_COUNT,
            onDb = { d -> g.mixSelected.intValue = MASTER_STRIP; g.setMaster(d) }, onPan = {}, onMute = {}, onSolo = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onName = { state.showMaster.value = true },
            eq = g.masterBus.value.eq, onEq = { state.showMaster.value = true }, eqTag = "BUS",
        )
    }
    }
    if (adding) AddTrackSheet(state, onDone = { adding = false }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
    if (menuFor >= 0) TrackMenu(state, menuFor, onDone = { menuFor = -1 }, onEq = { eqFor = menuFor; menuFor = -1 },
        modifier = Modifier.align(Alignment.Center).padding(16.dp))
    // a removed track can't keep its EQ page open
    val eqShown = eqFor.takeIf { it >= 0 && tracks.getOrNull(it)?.kind.let { k -> k != null && k != com.thorbox.model.TrackKind.EMPTY } } ?: -1
    if (state.showMaster.value) MasterSheet(state, onDone = { state.showMaster.value = false }, modifier = Modifier.align(Alignment.Center).padding(8.dp))
    if (eqShown >= 0) EqSheet(state, eqShown, onTrack = { eqFor = it; g.mixSelected.intValue = it }, onDone = { eqFor = -1 },
        modifier = Modifier.align(Alignment.Center).padding(8.dp))
    }
}

/** "+": pick what the new track plays. Big, picture-first cards. */
@Composable
fun AddTrackSheet(state: AppState, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val kinds = listOf(
        Triple(com.thorbox.model.TrackKind.SAMPLER, "SAMPLER", "Chop a sample onto 16 pads"),
        Triple(com.thorbox.model.TrackKind.SYNTH, "SYNTH", "Basslines, 808s, leads, keys"),
        Triple(com.thorbox.model.TrackKind.DRUMS, "DRUMS", "A drum machine with its own kit"),
    )
    Column(modifier.sheet(onClose = onDone).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ADD A TRACK", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            Text("${state.tracks.value.count { it.kind == com.thorbox.model.TrackKind.EMPTY }} of 8 free", color = Tb.Muted, fontSize = 11.sp,
                modifier = Modifier.padding(end = 10.dp))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onDone)
        }
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            kinds.forEach { (k, title, sub) ->
                val c = kindColor(k)
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(c)
                    .navClickable(label = "ADD $title") { state.addTrack(k); onDone() }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    KindIcon(k, Modifier.size(44.dp))
                    Column {
                        Text(title, color = Tb.Black, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                        Text(sub, color = Tb.Black.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Tiny pictograms: 4×4 pads, a keyboard, a drum. Drawn, so every theme recolours them. */
@Composable
fun KindIcon(k: com.thorbox.model.TrackKind, modifier: Modifier) {
    Canvas(modifier) {
        val ink = Tb.Black
        val w = size.width; val h = size.height
        when (k) {
            com.thorbox.model.TrackKind.SAMPLER -> for (r in 0 until 4) for (c in 0 until 4)
                drawRoundRect(ink, Offset(c * w / 4 + 1.5f, r * h / 4 + 1.5f), Size(w / 4 - 3f, h / 4 - 3f), CornerRadius(3f))
            com.thorbox.model.TrackKind.SYNTH -> {
                drawRoundRect(ink, Offset(0f, h * 0.25f), Size(w, h * 0.6f), CornerRadius(4f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                for (i in 1 until 6) drawLine(ink, Offset(w * i / 6, h * 0.25f), Offset(w * i / 6, h * 0.85f), 2.5f)
                for (i in listOf(1, 2, 4, 5)) drawRect(ink, Offset(w * i / 6 - w * 0.05f, h * 0.25f), Size(w * 0.1f, h * 0.33f))
            }
            com.thorbox.model.TrackKind.DRUMS -> {
                drawOval(ink, Offset(w * 0.1f, h * 0.2f), Size(w * 0.8f, h * 0.3f))
                drawRect(ink, Offset(w * 0.1f, h * 0.35f), Size(w * 0.8f, h * 0.35f))
                drawLine(ink, Offset(w * 0.2f, h * 0.05f), Offset(w * 0.55f, h * 0.3f), 4f)
                drawLine(ink, Offset(w * 0.85f, h * 0.0f), Offset(w * 0.5f, h * 0.3f), 4f)
            }
            else -> Unit
        }
    }
}

/** Strip menu: DUCK target on/off (and a way to the DUCK page), open in editor, remove. */
@Composable
fun TrackMenu(state: AppState, t: Int, onDone: () -> Unit, modifier: Modifier = Modifier, onEq: (() -> Unit)? = null) {
    val g = state.groove
    val ch = g.channels.value[t]
    val tracks = state.tracks.value
    Column(modifier.sheet(onClose = onDone).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(kindColor(tracks[t].kind)))
            Text(tracks[t].name, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.sp)
            Text("TRACK ${t + 1}", color = Tb.Muted, fontSize = 11.sp)
            Box(Modifier.weight(1f))
            if (onEq != null) HwButton("EQ", minHeight = 34.dp, fontSize = 12, accent = kindColor(tracks[t].kind)) { onEq() }
            HwButton("OPEN", minHeight = 34.dp, fontSize = 12, accent = kindColor(tracks[t].kind), active = true) { state.selectTrack(t); onDone() }
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onDone)
        }
        // DUCK (the sidechain page) decides what pumps: here, just whether this track is one of its targets
        val d = g.duck.value
        val isKey = d.source == t
        Text(when {
            isKey -> "DUCK: this track is the KEY (it makes the others dip)"
            d.source < 0 -> "DUCK: pick a KEY on the DUCK page (the kick, a track or the beat), then the tracks that dip"
            else -> "DUCK: dip this track whenever the key hits (kick → sample / bass sidechain)"
        }, color = Tb.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HwButton(if (d.isTarget(t)) "DUCKED ✓" else "DUCK THIS TRACK", Modifier.weight(1f), minHeight = 40.dp, fontSize = 12,
                active = d.isTarget(t), enabled = !isKey, accent = Tb.Key) { g.setDuck { it.withTarget(t, !it.isTarget(t)) } }
            HwButton("OPEN DUCK PAGE", Modifier.weight(1f), minHeight = 40.dp, fontSize = 12, accent = Tb.Tempo, active = true) {
                state.selectTop(com.thorbox.app.TopView.DUCK); onDone()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton("RESAMPLE", minHeight = 40.dp, fontSize = 11, accent = Tb.Good) {
                state.exporter.resample.value = state.exporter.resample.value.copy(source = t); state.exporter.showResample.value = true; onDone()
            }
            HwButton("CLEAR PATTERN", minHeight = 40.dp, fontSize = 11) { g.clear(t) }
            Box(Modifier.weight(1f))
            HwButton(if (state.canRemoveTrack(t)) "REMOVE TRACK" else "LAST ${tracks[t].kind.label} · CAN'T REMOVE", minHeight = 40.dp, fontSize = 11,
                enabled = state.canRemoveTrack(t), accent = Tb.Warn, active = state.canRemoveTrack(t)) { state.removeTrack(t); onDone() }
        }
    }
}

/**
 * One channel strip, laid out like a console: name, PAN (above the fader, as on a desk), PUMP, the
 * level readout, then the fader beside its dB scale and LED meters, and MUTE / SOLO at the bottom.
 *
 * Touch rules (the old strip jumped controls to wherever a finger landed, so pulling a fader up from
 * −∞ started on the pan slider underneath and threw the pan):
 *  - the fader moves by how far you drag (relative), never jumps; double-tap = 0 dB
 *  - pan only moves on a sideways drag (a vertical swipe across it does nothing); double-tap = centre
 */
@Composable
private fun Strip(
    state: AppState, name: String, color: Color, selected: Boolean, db: Float, pan: Float?, mute: Boolean?, solo: Boolean?,
    meterIdx: Int, onDb: (Float) -> Unit, onPan: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, modifier: Modifier,
    onName: (() -> Unit)? = null, onMenu: (() -> Unit)? = null, pump: Float = 0f, scIdx: Int = -1,
    eq: com.thorbox.app.groove.TrackEq? = null, onEq: (() -> Unit)? = null, eqTag: String = "EQ",
) {
    val g = state.groove
    val tel = state.telemetry
    val tm = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 32)
    val scaleStyle = androidx.compose.runtime.remember(Tb.palette) {
        androidx.compose.ui.text.TextStyle(color = Tb.Muted, fontSize = 8.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Bold)
    }
    val panStyle = androidx.compose.runtime.remember(Tb.palette) {
        androidx.compose.ui.text.TextStyle(color = Tb.Text, fontSize = 9.sp, fontFamily = Tb.Mono, fontWeight = FontWeight.Black)
    }
    // peak hold per side: level + when it was set (held 1.2 s, then falls); last clip time
    val hold = androidx.compose.runtime.remember { FloatArray(4) }        // [peakL, peakR, timeL, timeR]
    val clipAt = androidx.compose.runtime.remember { floatArrayOf(-10f) }
    // the latest values for gesture lambdas that outlive recomposition
    val dbNow = androidx.compose.runtime.rememberUpdatedState(db)
    val panNow = androidx.compose.runtime.rememberUpdatedState(pan ?: 0f)
    val faderPos = androidx.compose.runtime.remember { floatArrayOf(0f) }
    val setDb = androidx.compose.runtime.rememberUpdatedState(onDb)
    val setPan = androidx.compose.runtime.rememberUpdatedState(onPan)
    Column(
        modifier.glass(state, 14.dp).then(if (selected) Modifier.border(2.dp, color.copy(alpha = 0.85f), RoundedCornerShape(14.dp)) else Modifier)
            .clip(RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(color))                       // the track's colour band
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // fixed-height header so MASTER (no ⋯) lines up with the track strips; long names shrink
        // to fit rather than turning into "SAMPL…"
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            FitText(name, androidx.compose.ui.text.TextStyle(color = color, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f, fill = false).then(if (onName != null) Modifier.navClickable(label = "OPEN $name") { Info.show("OPEN TRACK"); onName() } else Modifier),
                minSize = 8f)
            if (onMenu != null) Text(" ⋯", color = Tb.Muted, fontWeight = FontWeight.Black, fontSize = 14.sp,
                modifier = Modifier.navClickable(label = "$name MENU") { Info.show("TRACK MENU"); onMenu() })
        }

        // ---- EQ: its curve in miniature; tap for the EQ page ----
        if (eq != null && onEq != null) EqThumb(eq, color, name, Modifier.fillMaxWidth().height(30.dp), eqTag, onEq)
        else Box(Modifier.height(30.dp))
        // ---- PAN: above the fader, sideways drags only ----
        if (pan != null) {
            Canvas(
                Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).background(Tb.Raised)
                    .navAdjustable("$name PAN", onReset = { onPan(0f) }) { d -> onPan((pan + d * 2f).coerceIn(-1f, 1f)) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            setPan.value((panNow.value + dx / size.width * 2f).coerceIn(-1f, 1f))
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { setPan.value(0f) }) }
            ) {
                val w = size.width; val h = size.height; val mid = w / 2f
                val x = mid + pan * (w / 2f - h / 2f)
                // fill from the centre to the knob, in the track's colour
                drawRect(color.copy(alpha = 0.35f), Offset(minOf(mid, x), 0f), Size(kotlin.math.abs(x - mid), h))
                drawLine(Tb.Dim, Offset(mid, 4f), Offset(mid, h - 4f), 1.dp.toPx())
                drawCircle(color, h / 2f - 3.dp.toPx(), Offset(x, h / 2f))
                val label = if (kotlin.math.abs(pan) < 0.005f) "C" else if (pan < 0) "L${(-pan * 100).toInt()}" else "R${(pan * 100).toInt()}"
                val t = tm.measure(label, panStyle)
                val tx = if (pan <= 0f) w - t.size.width - 6.dp.toPx() else 6.dp.toPx()        // on the side away from the knob
                drawText(t, topLeft = Offset(tx, (h - t.size.height) / 2f))
            }
        } else Box(Modifier.height(26.dp))
        if (pump > 0f && scIdx >= 0) {
            // PUMP: live ducking bar (how far the sidechain is pulling this track down right now)
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                tel.frame.longValue
                val duck = (1f - g.scGain[scIdx]).coerceIn(0f, 1f)
                drawRoundRect(Tb.Raised, Offset.Zero, size, CornerRadius(3f))
                drawRoundRect(Tb.Key, Offset.Zero, Size(size.width * duck, size.height), CornerRadius(3f))
            }
        }
        Text(if (db <= -59.9f) "−∞" else String.format(Locale.US, "%+.1f dB", db), color = Tb.Text, fontFamily = Tb.Mono, fontSize = 13.sp,
            fontWeight = FontWeight.Black, maxLines = 1)

        // ---- fader + scale + LED meters ----
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .navAdjustable("$name VOLUME", onReset = { onDb(0f) }) { d -> onDb(faderToDb((dbToFader(db) + d).coerceIn(0f, 1f))) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { faderPos[0] = dbToFader(dbNow.value) },
                    ) { change, dy ->
                        change.consume()
                        faderPos[0] = (faderPos[0] - dy / size.height).coerceIn(0f, 1f)
                        setDb.value(faderToDb(faderPos[0]))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { setDb.value(0f) }) }
        ) {
            tel.frame.longValue
            val w = size.width; val h = size.height
            val now = tel.timeSec
            val pad = 9.dp.toPx()                               // the cap's half-height: the scale runs inside it
            val top = pad; val bot = h - pad; val span = bot - top
            fun yOf(n: Float) = bot - n * span
            // dB scale on the left
            val scaleW = w * 0.26f
            for (mark in SCALE) {
                val y = yOf(dbToFader(mark))
                val t = tm.measure(if (mark <= -60f) "−∞" else if (mark > 0f) "+${mark.toInt()}" else "${mark.toInt()}", scaleStyle)
                drawText(t, topLeft = Offset(scaleW - t.size.width - 3.dp.toPx(), y - t.size.height / 2f))
                drawLine(if (mark == 0f) Tb.Muted else Tb.Line, Offset(scaleW, y), Offset(scaleW + 5.dp.toPx(), y), 1.dp.toPx())
            }
            // fader track + cap
            val cx = scaleW + (w * 0.62f - scaleW) / 2f + 3.dp.toPx()
            val trackW = 6.dp.toPx()
            drawRoundRect(Tb.Void, Offset(cx - trackW / 2, top), Size(trackW, span), CornerRadius(trackW / 2))
            val fy = yOf(dbToFader(db))
            drawRoundRect(color.copy(alpha = 0.5f), Offset(cx - trackW / 2, fy), Size(trackW, bot - fy), CornerRadius(trackW / 2))   // level fill
            val capW = minOf(38.dp.toPx(), (w * 0.62f - scaleW) - 2.dp.toPx()); val capH = 2 * pad
            drawRoundRect(Tb.Black.copy(alpha = 0.35f), Offset(cx - capW / 2 + 1.5f, fy - pad + 2f), Size(capW, capH), CornerRadius(4.dp.toPx()))   // shadow
            drawRoundRect(color, Offset(cx - capW / 2, fy - pad), Size(capW, capH), CornerRadius(4.dp.toPx()))
            for (k in -1..1) drawLine(Tb.Black.copy(alpha = if (k == 0) 0.85f else 0.35f), Offset(cx - capW * 0.32f, fy + k * 4.dp.toPx()),
                Offset(cx + capW * 0.32f, fy + k * 4.dp.toPx()), if (k == 0) 2.dp.toPx() else 1.dp.toPx())   // grip lines, centre = the value
            // LED meters, L and R, with peak hold and a clip light on top
            val mw = minOf(7.dp.toPx(), w * 0.12f); val mx0 = w * 0.68f; val gapM = 3.dp.toPx()
            val segs = 28; val segH = span / segs
            for (c in 0..1) {
                val p = g.meters[meterIdx + c]
                val n = meterNorm(p)
                if (p >= 0.99f) clipAt[0] = now
                if (n >= hold[c] || now - hold[2 + c] > 1.2f) {
                    if (n >= hold[c]) { hold[c] = n; hold[2 + c] = now } else hold[c] = maxOf(n, hold[c] - 0.012f)
                }
                val x = mx0 + c * (mw + gapM)
                for (sIdx in 0 until segs) {
                    val sn = (sIdx + 0.5f) / segs
                    val y = bot - (sIdx + 1) * segH
                    val lit = sn <= n
                    val sdb = faderToDb(sn)
                    val col = if (sdb > -3f) Tb.Warn else if (sdb > -12f) Tb.Key else Tb.Good
                    drawRect(if (lit) col else col.copy(alpha = 0.10f), Offset(x, y + 0.75f), Size(mw, segH - 1.5f))
                }
                if (hold[c] > 0.02f) drawRect(Tb.Text, Offset(x, yOf(hold[c]) - 1f), Size(mw, 2f))
            }
            val clip = now - clipAt[0] < 2f
            drawRoundRect(if (clip) Tb.Warn else Tb.Warn.copy(alpha = 0.15f), Offset(mx0, 0f), Size(2 * mw + gapM, 5.dp.toPx()), CornerRadius(2.dp.toPx()))
        }

        if (mute != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HwButton("M", Modifier.weight(1f), active = mute == true, accent = Tb.Warn, hPad = 2.dp, minWidth = 20.dp, onClick = onMute)
                HwButton("S", Modifier.weight(1f), active = solo == true, accent = Tb.Key, hPad = 2.dp, minWidth = 20.dp, onClick = onSolo)
            }
        } else {
            Box(Modifier.height(44.dp))   // keep faders aligned with the track strips
        }
        }
    }
}

/** Where the fader's scale is printed. */
private val SCALE = floatArrayOf(6f, 0f, -6f, -12f, -24f, -48f, -60f)

/** mixSelected index of the master strip (after the three track strips). */
const val MASTER_STRIP = com.thorbox.model.TRACK_COUNT
