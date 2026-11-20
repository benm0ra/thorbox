// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navModal

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Diagnostics (SELECT + B, or long-press the level meter): engine + input + motion sensors, live,
 * and SHARE LOG, which shares this app's own recent log as text (no adb needed; Android only lets
 * an app read its own log lines, nothing else on the device).
 */
@Composable
fun DiagnosticsPanel(state: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tel = state.telemetry
    val t = state.tilt
    val saved = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(modifier.navModal().width(420.dp).background(Tb.Bg.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("DIAGNOSTICS", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            HwButton(saved.value ?: "SAVE LOG", minHeight = 34.dp, fontSize = 11, accent = Tb.Key, active = true) {
                scope.launch {
                    val name = withContext(Dispatchers.IO) { com.thorbox.app.LogExport.saveToDownloads(ctx, "ClamshellBeats-log", collectLog(state)) }
                    saved.value = if (name != null) "SAVED ✓" else "SAVE FAILED"
                    if (name != null) state.lastInput.value = "Downloads/$name"
                }
            }
            HwButton("SHARE LOG", Modifier.padding(start = 6.dp), minHeight = 34.dp, fontSize = 11, accent = Tb.Good, active = true) {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { collectLog(state) }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Clamshell Beats log").putExtra(Intent.EXTRA_TEXT, text), "Share Thorbox log")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            HwButton("✕", Modifier.padding(start = 6.dp), minHeight = 34.dp, fontSize = 13) { state.showDiagnostics.value = false }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                tel.frame.longValue
                val last by state.lastInput.collectAsState()
                Line("last input", last)
                Line("tilt", when { !t.available -> "no motion sensor"; !t.enabled.value -> "off (FX deck → TILT)"; !t.running -> "on, not started"; else -> "running" })
                Line("sensors", t.sensorSummary())
                Line("events", t.events.toString())
                Line("roll / pitch", String.format(Locale.US, "%+.0f° / %+.0f°", t.rollDeg, t.pitchDeg))
                Line("steer x / y", String.format(Locale.US, "%+.2f / %+.2f", t.dx, t.dy))
                Line("shake peak", String.format(Locale.US, "%.1f m/s² (dice ≥ 13, twice)", t.lastShake))
                Line("FX held", Integer.toBinaryString(state.groove.fxHeld.intValue))
            }
            // Bubble level: where the device is leaning right now.
            Canvas(Modifier.size(96.dp)) {
                tel.frame.longValue
                val r = size.minDimension / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Tb.Raised, r, c)
                drawCircle(Tb.Line, r * 0.5f, c, style = Stroke(1.5f))
                drawLine(Tb.Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
                drawLine(Tb.Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
                val bx = (t.rollDeg / 45f).coerceIn(-1f, 1f) * r * 0.85f
                val by = (-t.pitchDeg / 45f).coerceIn(-1f, 1f) * r * 0.85f
                drawCircle(if (t.running) Tb.Good else Tb.Dim, r * 0.16f, Offset(c.x + bx, c.y + by))
            }
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row {
        Text(k, color = Tb.Muted, fontSize = 11.sp, modifier = Modifier.width(92.dp))
        Text(v, color = Tb.Text, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1)
    }
}

/** The app's own recent log (Android only exposes this process's lines to it), plus a header. */
private fun collectLog(state: AppState): String {
    val header = buildString {
        append("Clamshell Beats log · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.SOC_MODEL).append('\n')
        append("tilt: ").append(state.tilt.sensorSummary()).append(" enabled=").append(state.tilt.enabled.value)
        append(" running=").append(state.tilt.running).append(" events=").append(state.tilt.events).append("\n\n")
    }
    val body = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "1500", "--pid", android.os.Process.myPid().toString())
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "(couldn't read log: ${it.message})" }
    // Keep the share well under Android's intent size limit.
    return header + body.takeLast(400_000)
}
