// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.export.Exporter
import com.thorbox.app.export.Exporter.Format
import com.thorbox.app.export.Exporter.What

/**
 * EXPORT sheet (from the SONG page): what to bounce, the file format, stems. Then the files land in
 * Music/Clamshell Beats/Exports/<project>/ and SHARE sends them anywhere.
 *
 *   WHAT     SONG (all of it, or FROM / TO a stretch of steps) · PATTERN × loops
 *   FILE     WAV / FLAC · 24 / 16-bit · 48 / 44.1 kHz
 *   STEMS    off / a file per track · master FX on the mix only, or printed on each stem · TAIL · + MIDI
 */
@Composable
fun ExportSheet(state: AppState, modifier: Modifier = Modifier) {
    val ex = state.exporter
    val g = state.groove
    val o = ex.options.value
    fun set(f: (Exporter.Options) -> Exporter.Options) { ex.options.value = f(ex.options.value) }
    val close = { ex.showSheet.value = false; ex.result.value = null }
    Column(modifier.sheet(onClose = close).width(640.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EXPORT", style = Type.title)
            Text(state.projects.currentName.value.uppercase(), style = Type.value, maxLines = 1, modifier = Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = close)
        }
        val r = ex.result.value
        if (r != null) {
            // ---- done (or failed) ----
            Text(r.title, color = if (r.ok) Tb.Good else Tb.Warn, style = Type.title)
            Text(r.detail, color = Tb.Text, fontSize = 13.sp, lineHeight = 17.sp)
            if (r.ok) Text("Saved in ${r.folder}/ (any file manager or DAW finds them under Music).", color = Tb.Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.ok) HwButton("SHARE", Modifier.weight(1f).fillMaxHeight(), active = true, accent = Tb.Good, minHeight = 40.dp, fontSize = 13) { ex.share() }
                HwButton(if (r.ok) "DONE" else "BACK", Modifier.weight(1f).fillMaxHeight(), minHeight = 40.dp, fontSize = 13) {
                    if (r.ok) close() else ex.result.value = null
                }
            }
            return@Column
        }
        val steps = g.song.value
        val songOk = steps.isNotEmpty()
        val what = if (!songOk) What.PATTERN else o.what
        // ---- what ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WHAT", style = Type.label, modifier = Modifier.width(58.dp))
            HwButton("SONG", Modifier.weight(1f).fillMaxHeight(), active = what == What.SONG, enabled = songOk, accent = Tb.Tempo, minHeight = 34.dp, fontSize = 12) { set { it.copy(what = What.SONG) } }
            HwButton("PATTERN", Modifier.weight(1f).fillMaxHeight(), active = what == What.PATTERN, accent = Tb.Tempo, minHeight = 34.dp, fontSize = 12) { set { it.copy(what = What.PATTERN) } }
            if (what == What.SONG) {
                val last = steps.size - 1
                val a = o.fromStep.coerceIn(0, last); val b = (if (o.toStep < 0) last else o.toStep).coerceIn(a, last)
                Stepper("FROM", "${a + 1}", Modifier.weight(1.4f).fillMaxHeight(), { set { it.copy(fromStep = (a - 1).coerceAtLeast(0)) } }) {
                    set { it.copy(fromStep = (a + 1).coerceAtMost(b)) }
                }
                Stepper("TO", if (b == last) "END" else "${b + 1}", Modifier.weight(1.4f).fillMaxHeight(), { set { it.copy(toStep = (b - 1).coerceAtLeast(a)) } }) {
                    set { it.copy(toStep = if (b + 1 >= last) -1 else b + 1) }
                }
            } else {
                Stepper("LOOPS", "×${o.loops}", Modifier.weight(2.8f).fillMaxHeight(), { set { it.copy(loops = (o.loops - 1).coerceAtLeast(1)) } }) {
                    set { it.copy(loops = (o.loops + 1).coerceAtMost(64)) }
                }
            }
        }
        // ---- file ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FILE", style = Type.label, modifier = Modifier.width(58.dp))
            for (f in Format.entries) HwButton(f.name, Modifier.weight(1f).fillMaxHeight(), active = o.format == f, accent = Tb.Key, minHeight = 34.dp, fontSize = 12) { set { it.copy(format = f) } }
            Box(Modifier.width(6.dp))
            for (b in listOf(24, 16)) HwButton("$b-BIT", Modifier.weight(1f).fillMaxHeight(), active = o.bits == b, accent = Tb.Key, minHeight = 34.dp, fontSize = 12) { set { it.copy(bits = b) } }
            Box(Modifier.width(6.dp))
            for ((sr, l) in listOf(48000 to "48 kHz", 44100 to "44.1")) HwButton(l, Modifier.weight(1f).fillMaxHeight(), active = o.rate == sr, accent = Tb.Key, minHeight = 34.dp, fontSize = 12) { set { it.copy(rate = sr) } }
        }
        // ---- stems ----
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STEMS", style = Type.label, modifier = Modifier.width(58.dp))
            HwButton(if (o.stems) "STEMS ON" else "STEMS OFF", Modifier.weight(1.1f).fillMaxHeight(), active = o.stems, accent = Tb.Good, minHeight = 34.dp, fontSize = 12) { set { it.copy(stems = !o.stems) } }
            HwButton(if (o.fxOnStems) "FX ON STEMS" else "FX ON MIX", Modifier.weight(1.2f).fillMaxHeight(), active = o.stems && o.fxOnStems, enabled = o.stems,
                accent = Tb.Warn, minHeight = 34.dp, fontSize = 12) { set { it.copy(fxOnStems = !o.fxOnStems) } }
            HwButton(if (o.tail) "TAIL ↘" else "TAIL CUT", Modifier.weight(1f).fillMaxHeight(), active = o.tail, accent = Tb.EncWhite, minHeight = 34.dp, fontSize = 12) { set { it.copy(tail = !o.tail) } }
            HwButton("+ MIDI", Modifier.weight(0.9f).fillMaxHeight(), active = o.midi, accent = Tb.EncWhite, minHeight = 34.dp, fontSize = 12) { set { it.copy(midi = !o.midi) } }
        }
        // ---- what you'll get ----
        val list = if (what == What.SONG) {
            val last = steps.size - 1; val a = o.fromStep.coerceIn(0, last); val b = (if (o.toStep < 0) last else o.toStep).coerceIn(a, last)
            steps.subList(a, b + 1)
        } else listOf(com.thorbox.model.SongStep(g.patternIdx.intValue, o.loops))
        val ticks = list.sumOf { g.patternTicks(it.pattern).toLong() * it.repeats }
        val secs = ticks / (g.groove.value.bpm * com.thorbox.model.PPQN / 60.0)
        val nStems = if (!o.stems) 0 else {
            val chans = g.channels.value; val solo = chans.any { it.solo }
            state.tracks.value.indices.count { t -> state.tracks.value[t].kind != com.thorbox.model.TrackKind.EMPTY && !chans[t].mute && (!solo || chans[t].solo) &&
                list.any { st -> g.clipsOf(st.pattern).getOrNull(t)?.notes?.isNotEmpty() == true } }
        }
        val desc = buildString {
            append(Exporter.fmtTime(secs)); if (o.tail) append(" + tail")
            append(" · mix"); if (nStems > 0) append(" + $nStems stem${if (nStems > 1) "s" else ""}")
            if (o.midi) append(" + MIDI")
            append(" · ${o.format.name} ${o.bits}-bit")
        }
        Text(desc, style = Type.value)
        Text(when {
            !o.stems -> "One stereo file of the whole mix, master FX and all."
            o.fxOnStems -> "Each stem is rendered on its own with the master FX on it (echo and reverb tails included), plus the full mix. Takes ${nStems + 1} passes."
            else -> "Stems are each track after its fader, pan and DUCK, before the master FX: line them up at 0 and they add up to the dry mix. The mix file has the FX."
        }, color = Tb.Muted, fontSize = 12.sp, lineHeight = 15.sp)
        HwButton("EXPORT", Modifier.fillMaxWidth().height(52.dp), active = true, accent = Tb.Good, minHeight = 46.dp, fontSize = 15) {
            ex.options.value = o.copy(what = what)
            ex.start(ex.options.value)?.let { Info.toast("EXPORT", it) }
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, modifier: Modifier, minus: () -> Unit, plus: () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.label, maxLines = 1)
        HwButton("−", Modifier.width(34.dp).fillMaxHeight(), minHeight = 30.dp, fontSize = 14, hPad = 0.dp, onClick = minus)
        Text(value, style = Type.value, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HwButton("+", Modifier.width(34.dp).fillMaxHeight(), minHeight = 30.dp, fontSize = 14, hPad = 0.dp, onClick = plus)
    }
}

/**
 * While an export renders: covers the whole screen (both screens), so no touch plays into the bounce.
 * [compact] for the bottom screen.
 */
@Composable
fun ExportProgress(state: AppState, compact: Boolean = false) {
    val ex = state.exporter
    Box(Modifier.fillMaxSize().background(Tb.Void.copy(alpha = 0.82f))
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }) {
        Column(Modifier.align(Alignment.Center).width(if (compact) 420.dp else 560.dp).sheet(onClose = null).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ex.resampling) "RESAMPLING" else "EXPORTING", style = Type.title, modifier = Modifier.weight(1f))
                Text("${(ex.progress.floatValue * 100).toInt()} %", style = Type.valueLg)
            }
            Text(ex.stage.value, style = Type.value, maxLines = 1)
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                state.telemetry.frame.longValue
                val f = ex.progress.floatValue.coerceIn(0f, 1f)
                drawRoundRect(Tb.Raised, Offset.Zero, size, CornerRadius(7f))
                drawRoundRect(Tb.Good, Offset.Zero, Size(size.width * f, size.height), CornerRadius(7f))
            }
            if (!compact) Text("Rendering faster than real time. The speakers stay quiet until it's done.", color = Tb.Muted, fontSize = 12.sp)
            HwButton("CANCEL (B)", Modifier.fillMaxWidth().height(44.dp), accent = Tb.Warn, minHeight = 40.dp, fontSize = 13) { ex.cancel() }
        }
    }
}
