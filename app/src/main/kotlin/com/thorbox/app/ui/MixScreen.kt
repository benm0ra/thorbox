// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Fader law: 0 dB sits at 75% travel; the bottom quarter is a gentle fade to −60 (off). */
fun dbToFader(db: Float): Float = if (db >= 0f) 0.75f + db / 6f * 0.25f else 0.75f * (1f - sqrt((-db / 60f).coerceIn(0f, 1f)))
fun faderToDb(n: Float): Float = if (n >= 0.75f) (n - 0.75f) / 0.25f * 6f else -60f * (1f - n / 0.75f).let { it * it }
private fun meterNorm(peak: Float): Float = if (peak <= 1e-4f) 0f else dbToFader((20f * log10(peak)).coerceIn(-60f, 6f))

/**
 * MIX view: one strip per track plus master. Drag a fader anywhere along its height; double-tap
 * it for 0 dB. Drag the pan bar sideways (double-tap to centre). Meters are post-fader peaks,
 * drawn in the draw phase.
 */
@Composable
fun MixScreen(state: AppState) {
    val g = state.groove
    val chans = g.channels.value
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chans.forEachIndexed { i, ch ->
            Strip(
                state, name = ch.name, color = if (i == 0) Tb.Pads[0] else Tb.EncOrange, selected = g.mixSelected.intValue == i,
                db = ch.volumeDb, pan = ch.pan, mute = ch.mute, solo = ch.solo, meterIdx = i * 2,
                onDb = { d -> g.mixSelected.intValue = i; g.setChannel(i) { it.copy(volumeDb = d) } },
                onPan = { p -> g.setChannel(i) { it.copy(pan = p) } },
                onMute = { g.setChannel(i) { it.copy(mute = !it.mute) } },
                onSolo = { g.setChannel(i) { it.copy(solo = !it.solo) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Strip(
            state, name = "MASTER", color = Tb.EncWhite, selected = g.mixSelected.intValue == 2,
            db = g.masterDb.value, pan = null, mute = null, solo = null, meterIdx = 4,
            onDb = { d -> g.mixSelected.intValue = 2; g.setMaster(d) }, onPan = {}, onMute = {}, onSolo = {},
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun Strip(
    state: AppState, name: String, color: Color, selected: Boolean, db: Float, pan: Float?, mute: Boolean?, solo: Boolean?,
    meterIdx: Int, onDb: (Float) -> Unit, onPan: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, modifier: Modifier,
) {
    val g = state.groove
    val tel = state.telemetry
    Column(
        modifier.glass(state, 14.dp).then(if (selected) Modifier.border(2.dp, color.copy(alpha = 0.8f), RoundedCornerShape(14.dp)) else Modifier).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, color = color, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
        Text(if (db <= -59.9f) "−∞" else String.format(Locale.US, "%+.1f dB", db), color = Tb.Text, fontFamily = Tb.Mono, fontSize = 13.sp, fontWeight = FontWeight.Black)

        // Fader + stereo meter
        val dbNow = db
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
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
