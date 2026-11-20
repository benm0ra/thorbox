// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navAdjustable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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

/** Fader law: 0 dB sits at 75% travel; the bottom quarter is a gentle fade to −60 (off). */
fun dbToFader(db: Float): Float = if (db >= 0f) 0.75f + db / 6f * 0.25f else 0.75f * (1f - sqrt((-db / 60f).coerceIn(0f, 1f)))
fun faderToDb(n: Float): Float = if (n >= 0.75f) (n - 0.75f) / 0.25f * 6f else -60f * (1f - n / 0.75f).let { it * it }
private fun meterNorm(peak: Float): Float = if (peak <= 1e-4f) 0f else dbToFader((20f * log10(peak)).coerceIn(-60f, 6f))

/** Colour of each instrument kind, used on strips, chips and the add-track picker. */
fun kindColor(k: com.thorbox.model.TrackKind) = when (k) {
    com.thorbox.model.TrackKind.SAMPLER -> Tb.Pads[0]
    com.thorbox.model.TrackKind.SYNTH -> Tb.EncOrange
    com.thorbox.model.TrackKind.DRUMS -> Tb.Pads[1]
    com.thorbox.model.TrackKind.EMPTY -> Tb.Dim
}

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
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                pump = if (ch.scSource >= 0 && ch.scAmount > 0f) ch.scAmount else 0f, scIdx = i,
            )
        }
        Column(Modifier.width(56.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tracks.any { it.kind == com.thorbox.model.TrackKind.EMPTY }) {
            Box(Modifier.width(56.dp).weight(1f).clip(RoundedCornerShape(14.dp)).border(2.dp, Tb.Line, RoundedCornerShape(14.dp))
                .navClickable { Info.show("ADD TRACK"); adding = true }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = Tb.Text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("TRACK", color = Tb.Muted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
        }
        HwButton("MIDI", Modifier.fillMaxWidth(), minHeight = 52.dp, fontSize = 11, hPad = 2.dp, accent = Tb.Tempo) { state.exportMidi() }
        }
        Strip(
            state, name = "MASTER", color = Tb.EncWhite, selected = g.mixSelected.intValue == MASTER_STRIP,
            db = g.masterDb.value, pan = null, mute = null, solo = null, meterIdx = 2 * com.thorbox.model.TRACK_COUNT,
            onDb = { d -> g.mixSelected.intValue = MASTER_STRIP; g.setMaster(d) }, onPan = {}, onMute = {}, onSolo = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    if (adding) AddTrackSheet(state, onDone = { adding = false }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
    if (menuFor >= 0) TrackMenu(state, menuFor, onDone = { menuFor = -1 }, modifier = Modifier.align(Alignment.Center).padding(16.dp))
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
    Column(modifier.fillMaxWidth().background(Tb.Bg.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    .navClickable { state.addTrack(k); onDone() }.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
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

/** Strip menu: PUMP (sidechain) source + amount, open in editor, remove. */
@Composable
fun TrackMenu(state: AppState, t: Int, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val g = state.groove
    val ch = g.channels.value[t]
    val tracks = state.tracks.value
    Column(modifier.fillMaxWidth().background(Tb.Bg.copy(alpha = 0.97f), RoundedCornerShape(16.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(kindColor(tracks[t].kind)))
            Text(tracks[t].name, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.sp)
            Text("TRACK ${t + 1}", color = Tb.Muted, fontSize = 11.sp)
            Box(Modifier.weight(1f))
            HwButton("OPEN", minHeight = 34.dp, fontSize = 12, accent = kindColor(tracks[t].kind), active = true) { state.selectTrack(t); onDone() }
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = onDone)
        }
        Text("PUMP: duck this track whenever another one hits (classic kick → bass sidechain)", color = Tb.Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HwButton("OFF", minHeight = 40.dp, fontSize = 11, active = ch.scSource < 0, accent = Tb.Muted) { g.setChannel(t) { it.copy(scSource = -1) } }
            tracks.forEachIndexed { k, tr ->
                if (k == t || tr.kind == com.thorbox.model.TrackKind.EMPTY) return@forEachIndexed
                HwButton(tr.name.take(8), minHeight = 40.dp, fontSize = 11, active = ch.scSource == k, accent = kindColor(tr.kind)) {
                    g.setChannel(t) { it.copy(scSource = k, scAmount = if (it.scAmount <= 0f) 0.6f else it.scAmount) }
                }
            }
        }
        if (ch.scSource >= 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AMOUNT", color = Tb.Muted, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(64.dp))
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { a ->
                HwButton("${(a * 100).toInt()}%", Modifier.weight(1f), minHeight = 40.dp, fontSize = 12, active = kotlin.math.abs(ch.scAmount - a) < 0.01f, accent = Tb.Key) {
                    g.setChannel(t) { it.copy(scAmount = a) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton("CLEAR PATTERN", minHeight = 40.dp, fontSize = 11) { g.clear(t) }
            Box(Modifier.weight(1f))
            HwButton(if (state.canRemoveTrack(t)) "REMOVE TRACK" else "LAST ${tracks[t].kind.label} · CAN'T REMOVE", minHeight = 40.dp, fontSize = 11,
                enabled = state.canRemoveTrack(t), accent = Tb.Warn, active = state.canRemoveTrack(t)) { state.removeTrack(t); onDone() }
        }
    }
}

@Composable
private fun Strip(
    state: AppState, name: String, color: Color, selected: Boolean, db: Float, pan: Float?, mute: Boolean?, solo: Boolean?,
    meterIdx: Int, onDb: (Float) -> Unit, onPan: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, modifier: Modifier,
    onName: (() -> Unit)? = null, onMenu: (() -> Unit)? = null, pump: Float = 0f, scIdx: Int = -1,
) {
    val g = state.groove
    val tel = state.telemetry
    Column(
        modifier.glass(state, 14.dp).then(if (selected) Modifier.border(2.dp, color.copy(alpha = 0.8f), RoundedCornerShape(14.dp)) else Modifier).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = color, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).then(if (onName != null) Modifier.navClickable { Info.show("OPEN TRACK"); onName() } else Modifier))
            if (onMenu != null) Text(" ⋯", color = Tb.Muted, fontWeight = FontWeight.Black, fontSize = 14.sp,
                modifier = Modifier.navClickable { Info.show("TRACK MENU"); onMenu() })
        }
        if (pump > 0f && scIdx >= 0) {
            // PUMP chip: live ducking bar (how far the sidechain is pulling this track down right now)
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                tel.frame.longValue
                val duck = (1f - g.scGain[scIdx]).coerceIn(0f, 1f)
                drawRoundRect(Tb.Raised, Offset.Zero, size, CornerRadius(3f))
                drawRoundRect(Tb.Key, Offset.Zero, Size(size.width * duck, size.height), CornerRadius(3f))
            }
        }
        Text(if (db <= -59.9f) "−∞" else String.format(Locale.US, "%+.1f dB", db), color = Tb.Text, fontFamily = Tb.Mono, fontSize = 13.sp, fontWeight = FontWeight.Black)

        // Fader + stereo meter
        val dbNow = db
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .navAdjustable("$name VOLUME") { d -> onDb(faderToDb((dbToFader(db) + d).coerceIn(0f, 1f))) }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onDb(faderToDb((1f - change.position.y / size.height).coerceIn(0f, 1f)))
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDb(0f) }) }
        ) {
            tel.frame.longValue
            val w = size.width; val h = size.height
            val trackW = 8.dp.toPx()
            val cx = w * 0.36f
            drawRoundRect(Tb.Raised, Offset(cx - trackW / 2, 0f), Size(trackW, h), CornerRadius(trackW / 2))
            val zeroY = h * (1f - 0.75f)
            drawLine(Tb.Dim, Offset(cx - 16.dp.toPx(), zeroY), Offset(cx + 16.dp.toPx(), zeroY), 1.dp.toPx())
            val fy = h * (1f - dbToFader(dbNow))
            drawRoundRect(color, Offset(cx - 18.dp.toPx(), fy - 9.dp.toPx()), Size(36.dp.toPx(), 18.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawLine(Tb.Black, Offset(cx - 12.dp.toPx(), fy), Offset(cx + 12.dp.toPx(), fy), 2.dp.toPx())
            // meters (L, R)
            val mw = 6.dp.toPx(); val mx = w * 0.72f
            for (c in 0..1) {
                val p = g.meters[meterIdx + c]
                val n = meterNorm(p)
                val x = mx + c * (mw + 3.dp.toPx())
                drawRoundRect(Tb.Raised, Offset(x, 0f), Size(mw, h), CornerRadius(mw / 2))
                drawRoundRect(if (p >= 0.99f) Tb.Warn else Tb.Good, Offset(x, h * (1f - n)), Size(mw, h * n), CornerRadius(mw / 2))
            }
        }

        if (pan != null) {
            Canvas(
                Modifier.fillMaxWidth().height(28.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ -> change.consume(); onPan(((change.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f)) }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onPan(0f) }) }
            ) {
                val y = size.height / 2
                drawLine(Tb.Raised, Offset(0f, y), Offset(size.width, y), 6.dp.toPx())
                drawLine(Tb.Dim, Offset(size.width / 2, y - 8.dp.toPx()), Offset(size.width / 2, y + 8.dp.toPx()), 1.dp.toPx())
                drawCircle(color, 9.dp.toPx(), Offset((pan + 1f) / 2f * size.width, y))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HwButton("M", Modifier.weight(1f), active = mute == true, accent = Tb.Warn, onClick = onMute)
                HwButton("S", Modifier.weight(1f), active = solo == true, accent = Tb.Key, onClick = onSolo)
            }
        } else {
            Box(Modifier.height(28.dp + 6.dp + 44.dp))   // keep faders aligned with the track strips
        }
    }
}

/** mixSelected index of the master strip (after the three track strips). */
const val MASTER_STRIP = com.thorbox.model.TRACK_COUNT
