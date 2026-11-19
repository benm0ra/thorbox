// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import java.util.Locale

/**
 * Field recorder sheet. One huge button: tap to record, tap to stop. Stopping saves a WAV to
 * Music/Thorbox/Field Recordings and drops it onto the current sampler track, auto-chopped.
 */
@Composable
fun MicSheet(state: AppState, modifier: Modifier = Modifier) {
    val mic = state.mic
    val rec = mic.recording.value
    Column(modifier.width(440.dp).background(Tb.Bg.copy(alpha = 0.97f), RoundedCornerShape(18.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FIELD RECORDER", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            Box(Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, enabled = !rec) { state.showMic.value = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // The button: red dot → square while recording, pulsing with the input level.
            Box(Modifier.size(110.dp).clip(CircleShape).background(if (rec) Tb.Warn else Tb.Raised)
                .clickable { Info.show("REC"); state.toggleMicRecording() }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    state.telemetry.frame.longValue
                    if (rec) drawRoundRect(Tb.Black, cornerRadius = CornerRadius(8.dp.toPx()))
                    else drawCircle(Tb.Warn, size.minDimension / 2f)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (rec) String.format(Locale.US, "%d:%04.1f", (mic.seconds.floatValue / 60).toInt(), mic.seconds.floatValue % 60f) else "READY",
                    color = if (rec) Tb.Warn else Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 30.sp)
                Canvas(Modifier.width(220.dp).height(14.dp)) {
                    state.telemetry.frame.longValue
                    val l = mic.level.floatValue.coerceIn(0f, 1f)
                    drawRoundRect(Tb.Raised, cornerRadius = CornerRadius(7.dp.toPx()))
                    drawRoundRect(if (l > 0.95f) Tb.Warn else Tb.Good, Offset.Zero, Size(size.width * l, size.height), CornerRadius(7.dp.toPx()))
                }
                Text(if (rec) "Tap ■ to stop and chop it" else "Tap ● to record from the Thor's mic", color = Tb.Muted, fontSize = 12.sp)
            }
        }
        Text("Saves to Music/Thorbox/Field Recordings and loads onto this sampler track's 16 pads. Up to 10 minutes.",
            color = Tb.Dim, fontSize = 11.sp)
    }
}
