// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.io.AudioSetup
import com.thorbox.app.io.MidiController
import kotlinx.coroutines.delay

/**
 * SETTINGS → AUDIO
 *   OUTPUT  where the sound goes (speaker, headphones, a USB interface, Bluetooth: Bluetooth adds latency)
 *   INPUT   what the field recorder (MIC) records from
 *   CLICK   the metronome: sound, level, beat-1 accent, count-in before recording, only while recording
 */
@Composable
internal fun AudioPage(state: AppState) {
    val a = state.audioSetup
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("OUTPUT", "Where the sound goes. Bluetooth works but adds delay: use the speaker, headphones or USB to play live.")
        Choices(listOf("" to "SYSTEM") + a.outputs.value.map { it.key to "${it.kind}: ${it.name}" },
            a.outKey.value) { a.chooseOutput(it) }
        Section("INPUT", "What the field recorder (MIC) records from: the built-in mic, a headset or a USB interface.")
        Choices(listOf("" to "SYSTEM") + a.inputs.value.map { it.key to "${it.kind}: ${it.name}" }, a.inKey.value) { a.chooseInput(it) }

        Section("METRONOME", "CLK on STEPS (or Y on the controller) turns it on. Only you hear it: it's never recorded or exported.")
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AudioSetup.SOUNDS.forEachIndexed { i, n ->
                HwButton(n, Modifier.weight(1f).fillMaxHeight(), active = a.clickSound.intValue == i, accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) {
                    a.setClickSound(i)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LEVEL", style = Type.label, modifier = Modifier.width(64.dp))
            for (v in listOf(0.15f, 0.3f, 0.5f, 0.7f, 1f)) HwButton("${(v * 100).toInt()}%", Modifier.weight(1f).fillMaxHeight(),
                active = kotlin.math.abs(a.clickLevel.floatValue - v) < 0.01f, accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) { a.setClickLevel(v) }
            Box(Modifier.width(8.dp))
            HwButton(if (a.clickAccent.value) "ACCENT 1 ON" else "ACCENT 1 OFF", Modifier.weight(1.6f).fillMaxHeight(), active = a.clickAccent.value,
                accent = Tb.Good, minHeight = 40.dp, fontSize = 12) { a.setClickAccent(!a.clickAccent.value) }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("COUNT-IN", style = Type.label, modifier = Modifier.width(64.dp))
            for ((b, l) in listOf(0 to "OFF", 1 to "1 BAR", 2 to "2 BARS")) HwButton(l, Modifier.weight(1f).fillMaxHeight(),
                active = a.countInBars.intValue == b, accent = Tb.Warn, minHeight = 40.dp, fontSize = 12) { a.setCountIn(b) }
            Box(Modifier.width(8.dp))
            HwButton(if (a.clickOnlyRec.value) "ONLY WHILE ● REC" else "WHENEVER IT'S ON", Modifier.weight(1.6f).fillMaxHeight(), active = a.clickOnlyRec.value,
                accent = Tb.Warn, minHeight = 40.dp, fontSize = 12) { a.setClickOnlyRec(!a.clickOnlyRec.value); state.groove.pushClick() }
        }
        Text("COUNT-IN clicks the bars in before recording starts (press ● REC while stopped). The pattern starts on the next bar 1.",
            color = Tb.Muted, fontSize = 11.sp)
    }
}

/**
 * SETTINGS → MIDI
 *   DEVICES      what's plugged in (USB / Bluetooth MIDI), with an activity light
 *   PLAYS THE APP  which device's notes come in (ALL / one / none), and where they go: OMNI → the track
 *                you're on, or channels 1–8 → tracks 1–8. Pads and drums from C1 (36). CC 74 / 71 filter
 *                cutoff / resonance, CC 1 LFO depth, CC 7 / 10 the track's volume / pan.
 *   SYNC IN      follow outside MIDI clock (tempo) and START / STOP
 *   SEND TO      the device the app plays; CLOCK OUT; each track's MIDI channel; a timing offset
 */
@Composable
internal fun MidiPage(state: AppState) {
    val m = state.midi
    if (!m.available) { Text("This device has no MIDI support.", color = Tb.Muted, fontSize = 13.sp); return }
    val devs = m.devices.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- what's connected ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("DEVICES", if (devs.isEmpty()) "Nothing connected. Plug in a USB MIDI keyboard / interface, or pair a Bluetooth MIDI device."
                else devs.joinToString(" · ") { it.name }, Modifier.weight(1f))
            ActivityLight(state)
        }
        // ---- in ----
        Section("PLAYS THE APP", "Notes from this device play the app. Pads and drum sounds start at C1 (36).")
        Choices(listOf(MidiController.ALL to "ALL DEVICES", "" to "NONE") + devs.filter { it.outs > 0 }.map { it.key to it.name }, m.inKey.value) { m.chooseIn(it) }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HwButton("OMNI → TRACK YOU'RE ON", Modifier.weight(1f).fillMaxHeight(), active = !m.byChannel.value, accent = Tb.Key, minHeight = 40.dp, fontSize = 12) { m.setByChannel(false) }
            HwButton("CH 1–8 → TRACKS 1–8", Modifier.weight(1f).fillMaxHeight(), active = m.byChannel.value, accent = Tb.Key, minHeight = 40.dp, fontSize = 12) { m.setByChannel(true) }
            HwButton(if (m.syncIn.value) "SYNC IN: ON" else "SYNC IN: OFF", Modifier.weight(1f).fillMaxHeight(), active = m.syncIn.value, accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) {
                m.setSyncIn(!m.syncIn.value)
            }
        }
        Text("Knobs: CC 74 cutoff · CC 71 resonance · CC 1 LFO depth (the synth you're on) · CC 7 volume · CC 10 pan (the track)." +
            (m.externalBpm.value?.let { "  Following outside clock: $it BPM." } ?: if (m.syncIn.value) "  SYNC IN: waiting for clock…" else ""),
            color = Tb.Muted, fontSize = 11.sp)

        // ---- out ----
        Section("SENDS TO", "The device the app plays and keeps in time. Set a track's channel to send its notes.")
        Choices(listOf("" to "NONE") + devs.filter { it.ins > 0 }.map { it.key to it.name }, m.outKey.value) { m.chooseOut(it) }
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HwButton(if (m.clockOut.value) "CLOCK OUT: ON" else "CLOCK OUT: OFF", Modifier.weight(1.2f).fillMaxHeight(), active = m.clockOut.value,
                enabled = m.outKey.value.isNotEmpty(), accent = Tb.Tempo, minHeight = 40.dp, fontSize = 12) { m.setClockOut(!m.clockOut.value) }
            Text("TIMING", style = Type.label)
            HwButton("−", Modifier.width(44.dp).fillMaxHeight(), minHeight = 40.dp, fontSize = 14, hPad = 0.dp) { m.setOffset(m.offsetMs.intValue - 2) }
            Text("${if (m.offsetMs.intValue > 0) "+" else ""}${m.offsetMs.intValue} ms", color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black,
                fontSize = 13.sp, modifier = Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            HwButton("+", Modifier.width(44.dp).fillMaxHeight(), minHeight = 40.dp, fontSize = 14, hPad = 0.dp) { m.setOffset(m.offsetMs.intValue + 2) }
        }
        // each track's channel
        val tracks = state.tracks.value
        val chs = m.trackCh.value
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tracks.forEachIndexed { t, info ->
                if (info.kind == com.thorbox.model.TrackKind.EMPTY) return@forEachIndexed
                val ch = chs.getOrElse(t) { -1 }
                Column(Modifier.width(116.dp).background(Tb.Panel, RoundedCornerShape(10.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${t + 1} ${info.name}", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 11.sp, maxLines = 1)
                    Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        HwButton("−", Modifier.width(30.dp).fillMaxHeight(), minHeight = 32.dp, fontSize = 13, hPad = 0.dp) { m.setTrackChannel(t, if (ch <= 0) -1 else ch - 1) }
                        Text(if (ch < 0) "OFF" else "CH ${ch + 1}", color = if (ch < 0) Tb.Muted else Tb.Good, fontWeight = FontWeight.Black, fontSize = 12.sp,
                            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        HwButton("+", Modifier.width(30.dp).fillMaxHeight(), minHeight = 32.dp, fontSize = 13, hPad = 0.dp) { m.setTrackChannel(t, (ch + 1).coerceAtMost(15)) }
                    }
                }
            }
        }
        Text("A track with a channel sends what it plays (pattern and live) to the device: drums as General MIDI drums, pads from C1. " +
            "Mute it in the MIXER to hear only the outboard. TIMING nudges everything sent if the outboard runs early or late.",
            color = Tb.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Section(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 4.dp)) {
        Text(title, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
        Text(detail, color = Tb.Muted, fontSize = 11.sp)
    }
}

/** One choice from a list (wraps into rows of 4). */
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (k, label) ->
                    HwButton(label, Modifier.weight(1f).fillMaxHeight(), active = k == selected, accent = Tb.Key, minHeight = 38.dp, fontSize = 11, hPad = 4.dp) { onPick(k) }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Lights when MIDI comes in, with what it was. */
@Composable
private fun ActivityLight(state: AppState) {
    val m = state.midi
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { now.longValue = android.os.SystemClock.uptimeMillis(); delay(100) } }
    val lit = now.longValue - m.lastIn.longValue < 250
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (lit) Tb.Good else Tb.Raised))
        Text(m.lastInText.value.ifEmpty { "IN" }, color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1)
    }
}
