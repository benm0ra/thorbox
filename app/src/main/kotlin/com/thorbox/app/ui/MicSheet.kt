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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.MicRecorder
import com.thorbox.app.input.navClickable
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

private fun db(v: Float) = if (v <= 1e-5f) -100f else 20f * log10(v)
private fun dbNorm(d: Float) = ((d + 60f) / 60f).coerceIn(0f, 1f)        // meter scale: −60 … 0 dBFS
private fun clock(s: Float) = String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60f)

/**
 * Field recorder sheet. READY → tap ● → RECORDING (live waveform, dB meter, input GAIN) → tap ■ →
 * REVIEW: the whole take, with DISCARD · REDO · KEEP. Only KEEP saves the WAV to
 * Music/Clamshell Beats/Field Recordings and chops it onto this sampler track's 16 pads.
 *
 * The waveform scales itself to what's coming in (quiet sources still read clearly; the zoom is
 * shown), and the meter is in dB, so "is it hearing me?" is always answerable at a glance.
 */
@Composable
fun MicSheet(state: AppState, modifier: Modifier = Modifier) {
    val mic = state.mic
    val rec = mic.recording.value
    val saving = mic.saving.value
    val take = mic.take.value
    val tel = state.telemetry
    val tm = rememberTextMeasurer(cacheSize = 64)
    val big = remember(Tb.palette) { TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 30.sp) }
    val small = remember(Tb.palette) { TextStyle(color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 10.sp) }

    Column(modifier.navModal { if (!mic.recording.value && !mic.saving.value) state.showMic.value = false }.width(580.dp).background(Tb.Bg.copy(alpha = 0.97f), RoundedCornerShape(18.dp))
        .border(1.dp, Tb.Line, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FIELD RECORDER", color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
            if (rec || take != null) Text(if (rec) mic.source else take?.source ?: "", color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp,
                modifier = Modifier.border(1.dp, Tb.Line, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            Box(Modifier.weight(1f))
            // GAIN: lifts a quiet mic (applied to the take, soft-limited so it can't wrap)
            DragValue("GAIN", "+${mic.gainDb.floatValue.roundToInt()} dB", Tb.Key, Modifier.width(120.dp).height(34.dp), pxPerUnit = 8f, compact = true, navFine = 1f,
                onDelta = { mic.gainDb.floatValue = (mic.gainDb.floatValue + it).coerceIn(0f, 24f) }, onReset = { mic.gainDb.floatValue = 0f })
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, enabled = !rec && !saving) { state.showMic.value = false }
        }

        // ---- waveform: live (scrolling) while recording, the whole take when reviewing ----
        Canvas(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp)).background(Tb.Void)) {
            tel.frame.longValue                                    // redraw every frame
            val w = size.width; val h = size.height; val mid = h / 2f
            drawLine(Tb.Line, Offset(0f, mid), Offset(w, mid), 1f)
            if (take != null && !rec) {
                // REVIEW: the take's envelope, mirrored, scaled to its own peak
                val n = mic.overviewCount
                if (n > 0) {
                    var pk = 0.02f; for (i in 0 until n) pk = maxOf(pk, mic.overview[i])
                    val scale = 0.92f / pk
                    for (px in 0 until w.toInt()) {
                        val a = (px.toFloat() / w * n).toInt(); val b = (((px + 1).toFloat() / w * n).toInt()).coerceAtLeast(a + 1).coerceAtMost(n)
                        var v = 0f; for (i in a until b) v = maxOf(v, mic.overview[i])
                        val y = v * scale * mid
                        drawLine(if (v > 0.98f) Tb.Warn else Tb.Text, Offset(px + 0.5f, mid - y), Offset(px + 0.5f, mid + y + 1f), 1f)
                    }
                    if (scale > 1.6f) drawTextSafe(tm, String.format(Locale.US, "display ×%.0f", scale), Offset(8.dp.toPx(), 6.dp.toPx()), small)
                }
            } else {
                val count = mic.waveCount
                val cols = w.toInt().coerceAtMost(MicRecorder.WAVE_COLS)
                val shown = minOf(count, cols)
                // auto-zoom: the loudest thing on screen fills ~90% (never more than ×40), so quiet input is visible
                var pk = 0.025f
                for (k in 0 until shown) { val idx = (count - shown + k) % MicRecorder.WAVE_COLS; pk = maxOf(pk, -mic.wave[2 * idx], mic.wave[2 * idx + 1]) }
                val zoom = (0.9f / pk).coerceIn(1f, 40f)
                for (k in 0 until shown) {
                    val idx = (count - shown + k) % MicRecorder.WAVE_COLS
                    val mn = mic.wave[2 * idx]; val mx = mic.wave[2 * idx + 1]
                    val x = w - shown + k + 0.5f
                    val loud = maxOf(-mn, mx) > 0.98f
                    drawLine(if (loud) Tb.Warn else if (rec) Tb.Good else Tb.Muted,
                        Offset(x, mid - (mx * zoom).coerceIn(-1f, 1f) * mid * 0.95f), Offset(x, mid - (mn * zoom).coerceIn(-1f, 1f) * mid * 0.95f + 1f), 1f)
                }
                if (rec && zoom > 1.6f) drawTextSafe(tm, String.format(Locale.US, "display ×%.0f", zoom), Offset(8.dp.toPx(), 6.dp.toPx()), small)
                if (count == 0) {
                    val t = tm.measure(if (rec) "listening…" else "the waveform appears here as you record", TextStyle(color = Tb.Dim, fontSize = 12.sp))
                    drawTextSafe(tm, if (rec) "listening…" else "the waveform appears here as you record",
                        Offset((w - t.size.width) / 2f, mid - t.size.height - 6.dp.toPx()), TextStyle(color = Tb.Dim, fontSize = 12.sp))
                }
            }
        }

        if (take != null && !rec) {
            // ---- REVIEW ----
            androidx.compose.runtime.LaunchedEffect(take) { android.util.Log.i(MicRecorder.TAG, "ui: review shown (${take.name})") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(clock(take.seconds), color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    Text("peak ${take.peakDb.roundToInt()} dBFS" + if (take.peakDb < -24f) " · quiet: KEEP, then NORMALIZE on the pads, or raise GAIN and REDO" else "",
                        color = if (take.peakDb < -24f) Tb.Key else Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Mono)
                }
                HwButton("DISCARD", Modifier.height(56.dp), accent = Tb.Warn, minHeight = 56.dp, fontSize = 13, enabled = !saving) { mic.discard() }
                HwButton("REDO", Modifier.height(56.dp), minHeight = 56.dp, fontSize = 13, enabled = !saving) { state.redoMicTake() }
                HwButton(if (saving) "SAVING…" else "KEEP · CHOP", Modifier.height(56.dp), active = true, accent = Tb.Good, minHeight = 56.dp, fontSize = 13, enabled = !saving) {
                    state.keepMicTake() }
            }
            Text("KEEP saves ${take.name}.wav to Music/Clamshell Beats/Field Recordings and chops it onto this sampler track's 16 pads. DISCARD deletes it.",
                color = Tb.Dim, fontSize = 11.sp)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // The button: red dot → square while recording.
                Box(Modifier.size(96.dp).clip(CircleShape).background(if (rec) Tb.Warn else Tb.Raised)
                    .navClickable(label = if (rec) "STOP" else "REC") { Info.show("REC"); state.toggleMicRecording() }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(48.dp)) {
                        if (rec) drawRoundRect(Tb.Black, cornerRadius = CornerRadius(8.dp.toPx()))
                        else drawCircle(Tb.Warn, size.minDimension / 2f)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Clock + live peak readout, drawn (not composed) so they tick without recomposing.
                    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                        tel.frame.longValue
                        val t = if (rec) clock(mic.seconds) else "READY"
                        drawTextSafe(tm, t, Offset(0f, 0f), if (rec) big.copy(color = Tb.Warn) else big)
                        if (rec) {
                            val d = db(mic.peakHold)
                            val s = if (d < -90f) "−∞ dB" else "${d.roundToInt()} dB"
                            val m = tm.measure(s, big.copy(fontSize = 16.sp))
                            drawTextSafe(tm, s, Offset(size.width - m.size.width, (size.height - m.size.height) / 2f),
                                big.copy(fontSize = 16.sp, color = if (d > -0.5f) Tb.Warn else if (d < -40f) Tb.Key else Tb.Good))
                        }
                    }
                    // dB meter: −60 … 0 with ticks; amber when barely anything is coming in, red at clipping
                    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                        tel.frame.longValue
                        val barH = 12.dp.toPx(); val r = CornerRadius(6.dp.toPx())
                        val l = dbNorm(db(mic.level)); val hold = dbNorm(db(mic.peakHold))
                        drawRoundRect(Tb.Raised, Offset.Zero, Size(size.width, barH), r)
                        val col = if (hold > 0.99f) Tb.Warn else if (rec && hold < 0.33f) Tb.Key else Tb.Good
                        drawRoundRect(col, Offset.Zero, Size(size.width * l, barH), r)
                        if (hold > 0f) drawLine(Tb.Text, Offset(size.width * hold, 0f), Offset(size.width * hold, barH), 2f)
                        drawRoundRect(Tb.Line, Offset.Zero, Size(size.width, barH), r, style = Stroke(1f))
                        for (d in intArrayOf(-48, -36, -24, -12, -6, 0)) {
                            val x = size.width * dbNorm(d.toFloat())
                            drawLine(Tb.Muted, Offset(x, barH), Offset(x, barH + 3.dp.toPx()), 1f)
                        }
                    }
                    Text(when {
                        rec && db(mic.peakHold) < -40f -> "Very quiet: get closer, or turn GAIN up"
                        rec -> "Tap ■ to stop, then keep it or toss it"
                        else -> "Tap ● to record from the Thor's mic"
                    }, color = if (rec && db(mic.peakHold) < -40f) Tb.Key else Tb.Muted, fontSize = 12.sp)
                }
            }
            Text("Nothing is saved until you KEEP the take. Up to 10 minutes. Red means it clipped: back off a little.",
                color = Tb.Dim, fontSize = 11.sp)
            // live diagnostics: what the recorder is actually doing (source, frames, level), for when it misbehaves
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                tel.frame.longValue
                drawTextSafe(tm, "mic: ${mic.status}", Offset(0f, 0f), small)
            }
        }
    }
}
