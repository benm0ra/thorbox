// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.LoadJob
import com.thorbox.engine.NativeEngine
import java.util.Locale

/** Where a load is: the four steps the card shows, in order. */
enum class LoadStep(val label: String) { OPEN("OPEN"), DECODE("DECODE"), ANALYSE("KEY + TEMPO"), CHOP("CHOP") }

/** What the card shows for [job] right now, from the app's side and the engine's progress. */
data class LoadView(val step: LoadStep, val fraction: Float, val status: String)

/** [sawOwn]: the engine has already been seen working on this job (so idle afterwards = it's done, chopping). */
fun loadView(job: LoadJob, p: NativeEngine.LoadProgress, sawOwn: Boolean): LoadView = when {
    !job.opened -> LoadView(LoadStep.OPEN, -1f, "opening the file…")
    p.requestId == job.requestId && p.stage == 1 ->
        LoadView(LoadStep.DECODE, p.fraction, if (p.fraction >= 0f) "decoding ${(p.fraction * 100).toInt()}%" else "decoding…")
    p.requestId == job.requestId && p.stage == 2 -> LoadView(LoadStep.ANALYSE, -1f, "finding the key and tempo…")
    sawOwn -> LoadView(LoadStep.CHOP, -1f, "chopping onto 16 pads…")
    p.requestId != 0 -> LoadView(LoadStep.OPEN, -1f, "waiting for the file before it…")
    else -> LoadView(LoadStep.OPEN, -1f, "starting…")
}

/**
 * The loading card: what's loading, which step it's on (OPEN → DECODE → KEY + TEMPO → CHOP), a real
 * progress bar while decoding (a sweeping one while the length isn't known), elapsed time and an
 * estimate, and CANCEL. Sits over the waveform window while a sample loads.
 */
@Composable
fun LoadingCard(state: AppState, job: LoadJob, modifier: Modifier = Modifier) {
    // re-read the engine's progress every frame while the card is up
    var prog by remember { mutableStateOf(NativeEngine.LoadProgress(0, 0, -1f)) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var sawOwn by remember(job.requestId) { mutableStateOf(false) }
    LaunchedEffect(job.requestId) {
        while (true) {
            withFrameNanos { }
            prog = NativeEngine.loadProgress()
            if (prog.requestId == job.requestId) sawOwn = true
            now = android.os.SystemClock.uptimeMillis()
        }
    }
    val v = loadView(job, prog, sawOwn)
    val elapsed = (now - job.startedMs).coerceAtLeast(0L) / 1000.0
    val eta = if (v.step == LoadStep.DECODE && v.fraction > 0.04f) elapsed * (1 - v.fraction) / v.fraction else -1.0
    val ext = job.name.substringAfterLast('.', "").uppercase().take(5)

    Column(
        modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(Radius.panel)).background(Tb.Bg.copy(alpha = 0.94f))
            .border(1.dp, Tb.Line, RoundedCornerShape(Radius.panel)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spinner(Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text("LOADING", style = Type.label.copy(color = Section.SAMPLE.color))
                Text(job.name.substringBeforeLast('.'), style = Type.title.copy(letterSpacing = 0.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(ext.takeIf { it.isNotEmpty() }, job.sizeBytes.takeIf { it > 0 }?.let(::sizeText),
                    "track ${job.track + 1}").joinToString(" · "), color = Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Mono, maxLines = 1)
            }
            HwButton("CANCEL", minHeight = 40.dp, fontSize = 12, accent = Tb.Warn) { state.cancelLoad() }
        }

        // the four steps
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LoadStep.entries.forEach { s ->
                val done = s.ordinal < v.step.ordinal
                val now0 = s == v.step
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (done) Section.SAMPLE.color else if (now0) Tb.Text else Tb.Raised))
                    Text(s.label, color = if (done || now0) Tb.Text else Tb.Dim, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
                }
            }
        }

        ProgressBar(v.fraction, Modifier.fillMaxWidth().height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.status, color = Tb.Text, fontSize = 12.sp, fontFamily = Tb.Mono, modifier = Modifier.weight(1f), maxLines = 1)
            Text(buildString {
                append(timeText(elapsed))
                if (eta >= 0) append(" · ~").append(timeText(eta)).append(" left")
            }, color = Tb.Muted, fontSize = 11.sp, fontFamily = Tb.Mono, maxLines = 1)
        }
        if (elapsed > 8 && v.step == LoadStep.DECODE)
            Text("Long files take a while: the sampler keeps up to the first 10 minutes.", color = Tb.Dim, fontSize = 11.sp)
    }
}

/** A thin progress line, e.g. under a library row that's loading. */
@Composable
fun LoadLine(state: AppState, job: LoadJob, modifier: Modifier = Modifier) {
    var prog by remember { mutableStateOf(NativeEngine.LoadProgress(0, 0, -1f)) }
    var sawOwn by remember(job.requestId) { mutableStateOf(false) }
    LaunchedEffect(job.requestId) { while (true) { withFrameNanos { }; prog = NativeEngine.loadProgress(); if (prog.requestId == job.requestId) sawOwn = true } }
    ProgressBar(loadView(job, prog, sawOwn).fraction, modifier, thin = true)
}

/** Determinate when [fraction] is 0..1; a sweeping block when it's unknown (< 0). */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, thin: Boolean = false) {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t = it } }
    val accent = Section.SAMPLE.color
    Canvas(modifier.clip(RoundedCornerShape(if (thin) 1.dp else 5.dp)).background(Tb.Raised)) {
        t                                                        // redraw every frame (the sweep)
        val r = CornerRadius(if (thin) 1.dp.toPx() else 5.dp.toPx())
        if (fraction >= 0f) {
            drawRoundRect(accent, Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height), r)
        } else {
            val w = size.width * 0.28f
            val phase = ((t / 1_000_000L) % 1400L) / 1400f
            val x = -w + (size.width + w) * phase
            drawRoundRect(accent.copy(alpha = 0.85f), Offset(x, 0f), Size(w, size.height), r)
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier) {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t = it } }
    val c = Section.SAMPLE.color
    Canvas(modifier) {
        val a = ((t / 1_000_000L) % 900L) / 900f * 360f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawArc(Tb.Raised, 0f, 360f, false, style = stroke)
        drawArc(c, a, 100f, false, style = stroke)
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", (bytes / 1024).coerceAtLeast(1))
}

private fun timeText(s: Double) = if (s < 60) String.format(Locale.US, "%.0f s", s) else com.thorbox.app.clock(s)
