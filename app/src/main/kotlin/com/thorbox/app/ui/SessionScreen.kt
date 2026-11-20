// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.LoadedSample
import com.thorbox.app.TopView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.thorbox.model.PlayMode
import java.util.Locale

/**
 * Top (6", 16:9) screen for M1: sample browser | waveform with KEY/BPM header and pad settings |
 * a diagnostic footer (the M1 "probe"). In M4 the middle becomes the Session clip matrix and
 * this waveform moves into the contextual Detail View strip.
 */
/** The whole top screen: a slim TE-style mode rail plus the contextual view. */
@Composable
fun TopScreen(state: AppState) {
    val view = state.topView.value
    // Every touch on the top screen resets the visualizer's idle timer (observed, never consumed).
    AuroraBackground(state, Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); state.noteTopTouch() } }
    }) {
    Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(64.dp).fillMaxHeight().glass(state, 14.dp).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            HwButton("SAMPLE", Modifier.fillMaxWidth(), active = view == TopView.SAMPLER, minHeight = 40.dp, fontSize = 10, hPad = 2.dp) { state.selectTop(TopView.SAMPLER) }
            HwButton("SYNTH", Modifier.fillMaxWidth(), active = view == TopView.SYNTH, accent = Tb.EncOrange, minHeight = 40.dp, fontSize = 10, hPad = 2.dp) { state.selectTop(TopView.SYNTH) }
            HwButton("DRUMS", Modifier.fillMaxWidth(), active = view == TopView.DRUM, accent = Tb.Pads[1], minHeight = 40.dp, fontSize = 10, hPad = 2.dp) { state.selectTop(TopView.DRUM) }
            HwButton("STEPS", Modifier.fillMaxWidth(), active = view == TopView.SEQ, accent = Tb.Good, minHeight = 40.dp, fontSize = 10, hPad = 2.dp) { state.selectTop(TopView.SEQ) }
            HwButton("MIXER", Modifier.fillMaxWidth(), active = view == TopView.MIX, accent = Tb.EncWhite, minHeight = 40.dp, fontSize = 10, hPad = 2.dp) { state.selectTop(TopView.MIX) }
            HwButton("VISUAL", Modifier.fillMaxWidth(), active = view == TopView.VIZ, accent = Tb.EncBlue, minHeight = 40.dp, fontSize = 10, hPad = 2.dp) {
                if (view == TopView.VIZ) state.exitViz() else state.selectTop(TopView.VIZ)
            }
            // Transport: always visible whatever view you're in
            HwButton(if (state.groove.playing.value) "■" else "▶", Modifier.fillMaxWidth(), active = state.groove.playing.value, accent = Tb.Good, minHeight = 40.dp, fontSize = 14) { state.groove.togglePlay() }
            HwButton("THEME", Modifier.fillMaxWidth(), active = state.showThemes.value, accent = Tb.Pads[6], minHeight = 32.dp, fontSize = 9, hPad = 2.dp) {
                state.showThemes.value = !state.showThemes.value }
            Box(Modifier.weight(1f))
            LevelMeter(state.level, Modifier.fillMaxWidth().weight(2f)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { state.showDiagnostics.value = !state.showDiagnostics.value }) })
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))) {
            when (view) {
                TopView.SAMPLER -> ChopScreen(state)
                TopView.SYNTH -> SynthScreen(state)
                TopView.DRUM -> DrumScreen(state)
                TopView.SEQ -> SeqScreen(state)
                TopView.MIX -> MixScreen(state)
                TopView.VIZ -> VizScreen(state)
            }
            // OP-XY style: holding a step on the STEP deck opens its components here, over any view.
            if (state.groove.heldStep.intValue >= 0)
                StepDetail(state, Modifier.align(androidx.compose.ui.Alignment.Center).fillMaxWidth(0.96f))
        }
    }
    InfoLine(onTour = { state.showWelcome.value = true })   // plain-words explanation of whatever you just touched
    NavBadge(state, 0)
    HazeFilm(state)        // HAZE's pixel film, while it's on
    if (state.showWelcome.value) Welcome(state, Modifier.align(androidx.compose.ui.Alignment.Center))
    if (state.showMic.value) MicSheet(state, Modifier.align(androidx.compose.ui.Alignment.Center))
    if (state.showDiagnostics.value) DiagnosticsPanel(state, Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(10.dp))
    if (state.showThemes.value) ThemePicker(state, Modifier.padding(10.dp))
    ControllerHud(state)   // hold SELECT: map of the physical controls
    }
}

/** Vertical output meter, drawn in the draw phase so 120 Hz updates never recompose. */
@Composable
fun LevelMeter(level: androidx.compose.runtime.FloatState, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val l = level.floatValue.coerceIn(0f, 1f)
        val w = size.width * 0.35f
        val x = (size.width - w) / 2f
        drawRoundRect(Tb.Raised, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height),
            androidx.compose.ui.geometry.CornerRadius(w / 2))
        val h = size.height * l
        drawRoundRect(if (l > 0.98f) Tb.Warn else Tb.Good, androidx.compose.ui.geometry.Offset(x, size.height - h),
            androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(w / 2))
    }
}

@Composable
internal fun LibraryPanel(state: AppState, modifier: Modifier) {
    val root by state.library.root.collectAsState()
    val files by state.library.items.collectAsState()
    val scanning by state.library.scanning.collectAsState()
    val current by state.sample.collectAsState()
    val loading by state.loadingName.collectAsState()

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SAMPLES", style = Tb.Label, modifier = Modifier.weight(1f))
            HwButton("MIC", minHeight = 36.dp, fontSize = 12, accent = Tb.Warn) { state.openMic() }
            HwButton("FOLDER", minHeight = 36.dp, fontSize = 12) { state.requestFolder() }
        }
        when {
            root == null -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                HwButton("CHOOSE SAMPLES FOLDER", active = true, minHeight = 64.dp) { state.requestFolder() }
            }
            scanning && files.isEmpty() -> Text("Scanning…", color = Tb.Muted, modifier = Modifier.padding(12.dp))
            files.isEmpty() -> Text("No wav / mp3 files in that folder", color = Tb.Muted, modifier = Modifier.padding(12.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.uri.toString() }) { item ->
                    val isCurrent = current?.name == item.name || loading == item.name
                    Column(
                        Modifier.fillMaxWidth().height(52.dp)
                            .background(if (isCurrent) Tb.Raised.copy(alpha = 0.85f) else Color.Transparent)
                            .clickable { state.load(item) }       // one tap: load + analyse onto the pad
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(item.name, color = if (isCurrent) Tb.Pads[1] else Tb.Text, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.folder.isNotEmpty())
                            Text(item.folder, color = Tb.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

internal fun metaLine(s: LoadedSample) = String.format(
    Locale.US, "%.2f s · %.1f kHz · %s", s.seconds, s.sampleRate / 1000f, if (s.channels == 2) "stereo" else "mono")

@Composable
internal fun KeyBadge(s: LoadedSample?) {
    Column(Modifier.width(150.dp)) {
        Text("KEY", style = Tb.Label)
        Text(s?.key?.label() ?: "—", style = Tb.Readout, color = if (s?.key != null) Tb.Key else Tb.Dim, maxLines = 1)
        Bar(s?.keyConfidence ?: 0f, Tb.Key, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun BpmBadge(s: LoadedSample?, onHalf: () -> Unit, onDouble: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.width(110.dp)) {
            Text("BPM", style = Tb.Label)
            Text(s?.bpm?.let { String.format(Locale.US, if (it % 1f == 0f) "%.0f" else "%.1f", it) } ?: "—",
                style = Tb.Readout, color = if (s?.bpm != null) Tb.Tempo else Tb.Dim, maxLines = 1)
            Bar(s?.bpmConfidence ?: 0f, Tb.Tempo, Modifier.fillMaxWidth())
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HwButton("×2", minHeight = 36.dp, enabled = s?.bpm != null, onClick = onDouble)
            HwButton("÷2", minHeight = 36.dp, enabled = s?.bpm != null, onClick = onHalf)
        }
    }
}

@Composable
internal fun ProbeFooter(state: AppState) {
    val st by state.stats.collectAsState()
    val displays by state.displays.collectAsState()
    val mode by state.secondScreenMode.collectAsState()
    val input by state.lastInput.collectAsState()
    val audio = st?.let {
        String.format(Locale.US, "%s %s · %d Hz · burst %d · buf %d (%.1f ms) · out latency %s · xruns %d",
            if (it.aaudio) "AAudio" else "OpenSL", if (it.exclusive) "EXCLUSIVE" else "shared",
            it.sampleRate, it.framesPerBurst, it.bufferFrames, it.bufferMs,
            if (it.latencyMs >= 0) String.format(Locale.US, "%.1f ms", it.latencyMs) else "n/a", it.xruns)
    } ?: "audio: stopped"
    val disp = displays.joinToString("   ") {
        "#${it.id}${if (it.isDefault) "*" else ""} ${it.widthPx}×${it.heightPx} ${it.refreshHz.toInt()}Hz " +
            "${it.role}${if (it.presentationFlag) " [PRES]" else ""} ${it.state}"
    }
    Column {
        Text(audio, color = if ((st?.xruns ?: 0) > 0) Tb.Warn else Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1)
        Text("displays: $disp  ·  2nd: $mode  ·  input: $input", color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 2)
    }
}
