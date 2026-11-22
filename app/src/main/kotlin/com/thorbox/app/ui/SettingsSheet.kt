// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import java.util.Locale

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                SettingRow("METERS", "Master peak · RMS · spectrum · wave · scope, across the top") {
                    OnOff(state.miniMeters.value) { state.setMiniMeters(!state.miniMeters.value) }
                }
            }
            Box(Modifier.weight(1f)) {
                SettingRow("HINTS", "The strip that explains what you just touched") {
                    OnOff(Info.enabled.value) { state.setHints(!Info.enabled.value) }
                }
            }
            Box(Modifier.weight(1f)) {
                SettingRow("GLASS", "Frosted panels + the aurora behind them") {
                    HwButton(state.glass.value.name, Modifier.width(96.dp), minHeight = 38.dp, fontSize = 12, active = true, accent = Tb.EncBlue) { state.cycleGlass() }
                }
            }
            Box(Modifier.weight(1f)) {
                SettingRow("120 HZ", "Animate at the screen's full rate. Off: 60 fps, easier on the battery") {
                    OnOff(state.fullRateAnim.value) { state.setFullRateAnim(!state.fullRateAnim.value) }
                }
            }
        }
    }
}

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
        SettingRow("NAV CURSOR", "D-pad / L-stick move a ring over every control on both screens · A press · R-stick turns · B done") {
            OnOff(state.input.navMode.value) { state.input.setNav(!state.input.navMode.value) }
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
