// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.export.Exporter.Resample
import com.thorbox.model.TrackKind
import java.util.Locale

/**
 * RESAMPLE: bounce the mix (or one track) onto a sampler track's 16 pads, to chop and flip. A pattern
 * bounce lands playing back in order on its new track, so nothing changes until you start flipping.
 */
@Composable
fun ResampleSheet(state: AppState, modifier: Modifier = Modifier) {
    val ex = state.exporter
    val g = state.groove
    val r = ex.resample.value
    fun set(f: (Resample) -> Resample) { ex.resample.value = f(ex.resample.value) }
    val close = { ex.showResample.value = false }
    val tracks = state.tracks.value
    val used = tracks.indices.filter { tracks[it].kind != TrackKind.EMPTY }
    val samplers = used.filter { tracks[it].kind == TrackKind.SAMPLER }
    val free = tracks.any { it.kind == TrackKind.EMPTY }
    val songOk = g.song.value.isNotEmpty()
    val err = remember { mutableStateOf<String?>(null) }
    // keep the choices valid as tracks come and go
    val source = r.source.takeIf { it in used } ?: -1
    val target = r.target.takeIf { it in samplers } ?: if (free) Resample.NEW else samplers.firstOrNull() ?: Resample.NEW
    val song = r.song && songOk

    Column(modifier.sheet(onClose = close).width(700.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RESAMPLE", style = Type.title)
            Text("bounce it onto 16 pads, then chop and flip it", color = Tb.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14, onClick = close)
        }
        // ---- what to bounce ----
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("FROM", style = Type.label, modifier = Modifier.width(64.dp))
            HwButton("MIX", Modifier.weight(1f).fillMaxHeight(), active = source < 0, accent = Tb.EncWhite, minHeight = 36.dp, fontSize = 12, hPad = 4.dp) {
                set { it.copy(source = -1, muteSource = it.muteSource) }
            }
            used.forEach { t ->
                HwButton(tracks[t].name, Modifier.weight(1f).fillMaxHeight(), active = source == t, accent = kindColor(tracks[t].kind),
                    minHeight = 36.dp, fontSize = 11, hPad = 4.dp) { set { it.copy(source = t) } }
            }
        }
        // ---- how long ----
        val bars = g.patternTicks(g.patternIdx.intValue) / (4f * com.thorbox.model.PPQN)
        fun barsTxt(b: Float) = if (b == b.toInt().toFloat()) "${b.toInt()} BAR${if (b == 1f) "" else "S"}" else String.format(Locale.US, "%.1f BARS", b)
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LENGTH", style = Type.label, modifier = Modifier.width(64.dp))
            for (n in listOf(1, 2, 4)) {
                HwButton(if (n == 1) "LOOP · ${barsTxt(bars)}" else "×$n · ${barsTxt(bars * n)}", Modifier.weight(1.3f).fillMaxHeight(),
                    active = !song && r.loops == n, accent = Tb.Tempo, minHeight = 36.dp, fontSize = 11, hPad = 4.dp,
                    enabled = bars * n <= 64f) { set { it.copy(song = false, loops = n) } }
            }
            HwButton("SONG", Modifier.weight(1f).fillMaxHeight(), active = song, enabled = songOk, accent = Tb.Tempo, minHeight = 36.dp, fontSize = 12) {
                set { it.copy(song = true) }
            }
        }
        // ---- where it lands ----
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ONTO", style = Type.label, modifier = Modifier.width(64.dp))
            HwButton(if (free) "NEW TRACK" else "NO FREE TRACK", Modifier.weight(1.2f).fillMaxHeight(), active = target == Resample.NEW && free, enabled = free,
                accent = Tb.Good, minHeight = 36.dp, fontSize = 12, hPad = 4.dp) { set { it.copy(target = Resample.NEW) } }
            samplers.forEach { t ->
                HwButton(if (t == source) "${tracks[t].name} (IN PLACE)" else tracks[t].name, Modifier.weight(1f).fillMaxHeight(), active = target == t,
                    accent = kindColor(TrackKind.SAMPLER), minHeight = 36.dp, fontSize = 11, hPad = 4.dp) { set { it.copy(target = t) } }
            }
        }
        Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HwButton(if (r.muteSource) "MUTE WHAT I BOUNCED ✓" else "KEEP THE ORIGINAL PLAYING", Modifier.weight(1f).fillMaxHeight(), active = r.muteSource,
                accent = Tb.Warn, minHeight = 36.dp, fontSize = 12) { set { it.copy(muteSource = !it.muteSource) } }
        }
        Text(buildString {
            append(if (source < 0) "The mix as you hear it now" else "${tracks[source].name} on its own (even if it's muted)")
            append(if (song) ", the whole song," else if (r.loops == 1) ", one time through this pattern," else ", ${r.loops} times through this pattern,")
            append(" bounced to a WAV in Music/Clamshell Beats/Resamples and cut into 16 equal slices")
            append(if (target == Resample.NEW) " on a new sampler track." else " on ${tracks[target].name} (its sample is replaced: UNDO brings back its pattern).")
            if (!song) append(" The new track plays the slices in order, so it sounds the same until you flip it.")
            if (r.muteSource) append(if (source < 0) " Every track in the bounce gets muted." else " ${tracks[source].name} gets muted.")
        }, color = Tb.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        err.value?.let { Text(it, color = Tb.Warn, fontSize = 12.sp) }
        HwButton("RESAMPLE", Modifier.fillMaxWidth().height(52.dp).padding(top = 2.dp), active = true, accent = Tb.Good, minHeight = 48.dp, fontSize = 15) {
            val pick = Resample(source = source, song = song, loops = r.loops, target = target, muteSource = r.muteSource)
            ex.resample.value = pick
            val e = ex.startResample(pick)
            err.value = e
            if (e == null) ex.showResample.value = false
        }
    }
}
