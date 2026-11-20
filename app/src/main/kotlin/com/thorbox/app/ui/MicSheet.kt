// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navModal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.MicRecorder
import com.thorbox.app.input.navClickable
import java.util.Locale

/**
 * Field recorder sheet. One huge button: tap to record, tap to stop. While it records you see the
 * sound arrive: a scrolling waveform (last few seconds), the input meter with a clip warning, and
 * the clock. Stopping saves a WAV to Music/Clamshell Beats/Field Recordings and drops it onto the
 * current sampler track, auto-chopped onto the 16 pads.
 */
@Composable
fun MicSheet(state: AppState, modifier: Modifier = Modifier) {
    val mic = state.mic
    val rec = mic.recording.value
    val saving = mic.saving.value
    val tel = state.telemetry
    val tm = rememberTextMeasurer()
    val clock = remember(Tb.palette) { TextStyle(color = Tb.Warn, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 30.sp) }
    val ready = remember(Tb.palette) { TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 30.sp) }

    Column(modifier.navModal().width(560.dp).background(Tb.Bg.copy(alpha = 0.97f), RoundedCornerShape(18.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FIELD RECORDER", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, enabled = !rec && !saving) { state.showMic.value = false }
        }

        // ---- live waveform: what the mic hears, scrolling right to left ----
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Tb.Void)) {
            tel.frame.longValue                                    // redraw every frame
            val w = size.width; val h = size.height; val mid = h / 2f
            drawLine(Tb.Line, Offset(0f, mid), Offset(w, mid), 1f)
            val count = mic.waveCount
            val cols = w.toInt().coerceAtMost(MicRecorder.WAVE_COLS)
            val shown = minOf(count, cols)
            val col = if (rec) Tb.Good else Tb.Muted
            for (k in 0 until shown) {
                val idx = (count - shown + k) % MicRecorder.WAVE_COLS
                val mn = mic.wave[2 * idx]; val mx = mic.wave[2 * idx + 1]
                val x = w - shown + k + 0.5f
                val loud = maxOf(-mn, mx) > 0.95f
                drawLine(if (loud) Tb.Warn else col, Offset(x, mid - mx * mid * 0.95f), Offset(x, mid - mn * mid * 0.95f + 1f), 1f)
            }
            if (count == 0) {
                val t = tm.measure(if (saving) "saving…" else "the waveform appears here as you record", TextStyle(color = Tb.Dim, fontSize = 12.sp))
                drawText(t, topLeft = Offset((w - t.size.width) / 2f, mid - t.size.height - 6.dp.toPx()))
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // The button: red dot → square while recording.
            Box(Modifier.size(96.dp).clip(CircleShape).background(if (rec) Tb.Warn else Tb.Raised)
                .navClickable(enabled = !saving) { Info.show("REC"); state.toggleMicRecording() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(48.dp)) {
                    if (rec) drawRoundRect(Tb.Black, cornerRadius = CornerRadius(8.dp.toPx()))
                    else drawCircle(Tb.Warn, size.minDimension / 2f)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Clock, drawn (not composed) so it ticks every frame without recomposing the sheet.
                Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                    tel.frame.longValue
                    val s = mic.seconds
                    val t = if (rec) tm.measure(String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60f), clock)
                        else tm.measure(if (saving) "SAVING…" else "READY", ready)
                    drawText(t, topLeft = Offset(0f, (size.height - t.size.height) / 2f))
                }
                // Input meter, with a red end when it clips.
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    tel.frame.longValue
                    val l = mic.level.coerceIn(0f, 1f)
                    drawRoundRect(Tb.Raised, cornerRadius = CornerRadius(7.dp.toPx()))
                    drawRoundRect(if (l > 0.95f) Tb.Warn else Tb.Good, Offset.Zero, Size(size.width * l, size.height), CornerRadius(7.dp.toPx()))
                    drawRoundRect(Tb.Line, cornerRadius = CornerRadius(7.dp.toPx()), style = Stroke(1f))
                }
                Text(when {
                    saving -> "Saving, then chopping it onto the pads…"
                    rec -> "Tap ■ to stop: it saves and chops onto the 16 pads"
                    else -> "Tap ● to record from the Thor's mic"
                }, color = Tb.Muted, fontSize = 12.sp)
            }
        }
        Text("Saves to Music/Clamshell Beats/Field Recordings and loads onto this sampler track's 16 pads. Up to 10 minutes. Red in the waveform means it clipped: move back a little.",
            color = Tb.Dim, fontSize = 11.sp)
    }
}
