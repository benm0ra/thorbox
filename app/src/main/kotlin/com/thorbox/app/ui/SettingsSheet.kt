// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import java.util.Locale
import androidx.compose.runtime.getValue

private val defaultsRev = androidx.compose.runtime.mutableIntStateOf(0)

/**
 * SETTINGS (the rail's SET button): everything that isn't music, in five pages.
 *   PROJECTS  the current project (save, save as new, new) and every saved one (open, rename, copy, delete)
 *   LOOK      theme, glass, the meters strip, hints
 *   DEFAULTS  the tempo / swing / key / click a fresh launch starts with
 *   CONTROLS  the NAV cursor, tilt, the controller map, the welcome tour
 *   SYSTEM    audio engine status, diagnostics + logs, version
 * Every row is a big button or a plain read-out: no nested menus, nothing hidden behind a long-press.
 */
@Composable
fun SettingsSheet(state: AppState, modifier: Modifier = Modifier) {
    val tab = state.settingsTab.intValue
    Column(modifier.sheet { state.showSettings.value = false }.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (LocalWide.current) Text("SETTINGS", style = Type.title, modifier = Modifier.padding(end = 6.dp))
            // tabs share the row (and shrink their text) so the last one is never cut off
            listOf("PROJECTS", "LOOK", "DEFAULTS", "CONTROLS", "SYSTEM").forEachIndexed { i, name ->
                HwButton(name, Modifier.weight(1f), minHeight = 38.dp, fontSize = 12, hPad = 4.dp, active = tab == i, accent = Tb.Key) { state.settingsTab.intValue = i }
            }
            HwButton("✕", Modifier.width(48.dp), minHeight = 38.dp, fontSize = 14) { state.showSettings.value = false }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ProjectsPage(state)
                1 -> LookPage(state)
                2 -> DefaultsPage(state)
                3 -> ControlsPage(state)
                else -> SystemPage(state)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).background(Tb.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
            Text(detail, color = Tb.Muted, fontSize = 11.sp, maxLines = 1)
        }
        content()
    }
}

@Composable
private fun OnOff(on: Boolean, label: String = if (on) "ON" else "OFF", onToggle: () -> Unit) =
    HwButton(label, Modifier.width(96.dp), minHeight = 38.dp, fontSize = 12, active = on, accent = Tb.Good, onClick = onToggle)

@Composable
private fun LookPage(state: AppState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeGrid(state, Modifier.fillMaxWidth().weight(1f))
        // four compact tiles (title over its button): side-by-side rows squeezed the titles to nothing on narrow screens
        Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LookTile("METERS", "Master peak · RMS · spectrum · wave · scope, across the top", Modifier.weight(1f)) {
                OnOffFill(state.miniMeters.value) { state.setMiniMeters(!state.miniMeters.value) }
            }
            LookTile("HINTS", "The strip that explains what you just touched", Modifier.weight(1f)) {
                OnOffFill(Info.enabled.value) { state.setHints(!Info.enabled.value) }
            }
            LookTile("GLASS", "Frosted panels + the aurora behind them: FULL · SUBTLE · OFF", Modifier.weight(1f)) {
                HwButton(state.glass.value.name, Modifier.fillMaxWidth(), minHeight = 36.dp, fontSize = 12, active = true, accent = Tb.EncBlue) { state.cycleGlass() }
            }
            LookTile("120 HZ", "Animate at the screen's full rate. Off: 60 fps, easier on the battery", Modifier.weight(1f)) {
                OnOffFill(state.fullRateAnim.value) { state.setFullRateAnim(!state.fullRateAnim.value) }
            }
        }
    }
}

/** A LOOK setting: its name over its control; the long explanation goes to the hint strip when you touch it. */
@Composable
private fun LookTile(title: String, detail: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().background(Tb.Panel, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FitText(title, androidx.compose.ui.text.TextStyle(color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp), minSize = 8f)
        Box(Modifier.fillMaxWidth().pointerInput(detail) {
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); if (e.changes.any { it.pressed && !it.previousPressed }) Info.toast(title, detail) } }
        }) { content() }
    }
}

@Composable
private fun OnOffFill(on: Boolean, onToggle: () -> Unit) =
    HwButton(if (on) "ON" else "OFF", Modifier.fillMaxWidth(), minHeight = 36.dp, fontSize = 12, active = on, accent = Tb.Good, onClick = onToggle)

@Composable
private fun DefaultsPage(state: AppState) {
    val g = state.groove.groove.value
    val k = state.groove.scale.value
    defaultsRev.intValue                                   // re-read after SAVE / CLEAR
    val saved = state.startDefaults()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What a fresh launch starts with. Set the tempo, swing, key and click the way you like them, then save.",
            color = Tb.Muted, fontSize = 12.sp)
        SettingRow("NOW", String.format(Locale.US, "%.1f BPM · swing %d%% · %s %s%s · click %s", g.bpm, (g.swing * 100).toInt(),
            k.root.name.replace("s", "#"), k.mode.name.lowercase(), if (k.followSample) " (follows samples)" else "", if (g.metronome) "on" else "off")) {
            HwButton("SAVE AS DEFAULT", minHeight = 38.dp, fontSize = 12, active = true, accent = Tb.Good) { state.saveStartDefaults(); defaultsRev.intValue++ }
        }
        SettingRow("SAVED", saved?.let {
            String.format(Locale.US, "%.1f BPM · swing %d%% · %s %s%s · click %s", it.bpm, (it.swing * 100).toInt(),
                it.root.name.replace("s", "#"), it.mode.name.lowercase(), if (it.follow) " (follows samples)" else "", if (it.click) "on" else "off")
        } ?: "Nothing saved: starts at 90 BPM, straight, C minor") {
            HwButton("CLEAR", minHeight = 38.dp, fontSize = 12, enabled = saved != null) { state.clearStartDefaults(); defaultsRev.intValue++ }
        }
    }
}

@Composable
private fun ControlsPage(state: AppState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("NAV CURSOR", "SELECT + A. D-pad moves a cursor over every control on both screens · A press (hold = hold) · A on a knob grabs it, on a pad grid enters it · B back") {
            OnOff(state.input.navMode.value) { state.input.setNav(!state.input.navMode.value) }
        }
        SettingRow("START IN NAV", "On: the buttons drive the screens when the app opens. Off: they start in PLAY (the instrument)") {
            OnOff(state.navDefault.value) { state.setNavDefault(!state.navDefault.value) }
        }
        SettingRow("TILT", "Lean the Thor to steer the effect you hold; shake to roll the dice") {
            OnOff(state.tilt.enabled.value) { state.tilt.setEnabled(!state.tilt.enabled.value) }
        }
        SettingRow("CONTROLLER MAP", "Hold SELECT anywhere to see what every button does right now") {
            OnOff(state.showControllerMap.value, if (state.showControllerMap.value) "SHOWN" else "SHOW") {
                state.showControllerMap.value = !state.showControllerMap.value
                if (state.showControllerMap.value) state.showSettings.value = false
            }
        }
        SettingRow("WELCOME TOUR", "The three-card intro from the first launch") {
            HwButton("OPEN", Modifier.width(96.dp), minHeight = 38.dp, fontSize = 12) { state.showSettings.value = false; state.showWelcome.value = true }
        }
    }
}

@Composable
private fun SystemPage(state: AppState) {
    val stats by state.stats.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow("AUDIO", stats?.let {
            val bufMs = it.bufferFrames * 1000.0 / it.sampleRate.coerceAtLeast(1)
            String.format(Locale.US, "%d Hz · buffer %d frames (%.1f ms) · %s · %d dropouts%s", it.sampleRate, it.bufferFrames, bufMs,
                if (it.exclusive) "exclusive (fast path)" else "shared", it.xruns,
                if (it.latencyMs > 0) String.format(Locale.US, " · %.0f ms out", it.latencyMs) else "")
        } ?: "engine not running") {}
        SettingRow("DIAGNOSTICS", "Live engine, input and sensor read-outs, with SAVE LOG / SHARE LOG") {
            OnOff(state.showDiagnostics.value, if (state.showDiagnostics.value) "SHOWN" else "SHOW") {
                state.showDiagnostics.value = !state.showDiagnostics.value
                if (state.showDiagnostics.value) state.showSettings.value = false
            }
        }
        SettingRow("VERSION", "${com.thorbox.app.BuildConfig.VERSION_NAME}${if (com.thorbox.app.BuildConfig.DEBUG) " · debug build" else ""}") {}
    }
}
