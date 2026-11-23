// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.groove.GrooveController
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import java.util.Locale
import kotlin.math.sin

/**
 * SONG: build a track out of patterns, MPC-style.
 *
 *   header   SONG · PATTERN | SONG mode · LOOP · the project KEY (KEY LOCK) · where it is now
 *   left     16 pattern pads: tap one to launch it (playing: it waits for the end of this pattern and
 *            changes exactly on the beat; tap it again to cancel). Lit = playing, pulsing = up next,
 *            dots = which tracks have notes. COPY, CLEAR, and + SONG under them.
 *   right    the song: the patterns in order, each × its repeats. ▶ SONG plays it from the top; the
 *            step playing is lit. Edit a step: − / + repeats, ▲ ▼ to move it, ✕ to drop it.
 *
 * Every editor (STEPS, the drum grid, the chop lane, the piano roll) edits the current pattern, so a
 * song is: make pattern 1, COPY to 2 and change it, chain them here.
 */
@Composable
fun SongScreen(state: AppState) {
    val g = state.groove
    g.bankVersion.intValue                                     // recompose when patterns change
    val cur = g.patternIdx.intValue
    val queued = g.queuedPattern.intValue
    val songMode = g.songMode.value
    val steps = g.song.value
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Tb.Tempo))
            Text("SONG", style = Type.title, maxLines = 1)
            HwButton("PATTERN", Modifier.fillMaxHeight(), active = !songMode, accent = Tb.Tempo, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.setSongMode(false) }
            HwButton("SONG", Modifier.fillMaxHeight(), active = songMode, accent = Tb.Tempo, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.setSongMode(true) }
            HwButton(if (g.songLoop.value) "LOOP ↻" else "LOOP OFF", Modifier.fillMaxHeight(), active = g.songLoop.value, accent = Tb.EncWhite,
                minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.songLoop.value = !g.songLoop.value }
            KeyChip(state, Modifier.fillMaxHeight())
            Box(Modifier.weight(1f))
            val where = when {
                songMode && g.songPos.intValue >= 0 -> "STEP ${g.songPos.intValue + 1}/${steps.size} · ${g.patternName(cur)}"
                queued >= 0 -> "${g.patternName(cur)} → ${g.patternName(queued)}"
                else -> g.patternName(cur) + " · ${g.patternTicks(cur) / GrooveController.BAR} BAR" + (if (g.patternTicks(cur) > GrooveController.BAR) "S" else "")
            }
            Text(where, style = Type.value, maxLines = 1)
        }
        // ---- where we are in this pattern (and in the song) ----
        PositionBar(state, Modifier.fillMaxWidth().height(10.dp))

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- the 16 pattern pads ----
            Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PatternPads(state, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HwButton("COPY → NEW", Modifier.weight(1.2f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp) {
                        val to = g.firstEmptyPattern()
                        if (to < 0) Info.toast("ALL 16 USED", "Clear a pattern to copy into it")
                        else { g.copyPattern(cur, to); Info.toast("COPIED", "${g.patternName(cur)} → PAT ${to + 1}: tap it to work on the copy") }
                    }
                    HwButton("CLEAR", Modifier.weight(1f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp, accent = Tb.Warn) {
                        g.clearPattern(cur); Info.toast("CLEARED", "${g.patternName(cur)}: every track emptied (UNDO brings each back)")
                    }
                    HwButton("+ SONG", Modifier.weight(1f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp, accent = Tb.Tempo, active = true) {
                        g.addSongStep(cur)
                    }
                }
            }
            // ---- the song ----
            Column(Modifier.weight(1f).fillMaxHeight().glass(state, 12.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    HwButton(if (songMode && g.playing.value) "■ STOP" else "▶ SONG", Modifier.weight(1f).fillMaxHeight(), active = songMode && g.playing.value,
                        enabled = steps.isNotEmpty(), accent = Tb.Good, minHeight = 32.dp, fontSize = 12) {
                        if (songMode && g.playing.value) g.togglePlay() else g.playSong(0)
                    }
                    Text(if (steps.isEmpty()) "" else "${steps.size} STEPS · ${g.songBars()} BARS", style = Type.label, maxLines = 1)
                }
                if (steps.isEmpty())
                    Text("Your song is empty. Pick a pattern on the left and press + SONG; add as many as you like, in order.",
                        color = Tb.Muted, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    steps.forEachIndexed { i, st ->
                        val now = songMode && g.songPos.intValue == i
                        val c = Tb.Pads[st.pattern % Tb.Pads.size]
                        Row(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (now) c.copy(alpha = 0.35f) else Tb.Raised.copy(alpha = 0.6f))
                            .then(if (now) Modifier.border(2.dp, c, RoundedCornerShape(8.dp)) else Modifier)
                            .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${i + 1}", color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, modifier = Modifier.width(20.dp))
                            Box(Modifier.width(10.dp).height(18.dp).clip(RoundedCornerShape(3.dp)).background(c))
                            Text(g.patternName(st.pattern), color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1,
                                modifier = Modifier.weight(1f).padding(start = 4.dp))
                            HwButton("−", Modifier.width(36.dp).fillMaxHeight(), minHeight = 30.dp, fontSize = 14, hPad = 0.dp) { g.setRepeats(i, st.repeats - 1) }
                            Text("×${st.repeats}", color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 12.sp,
                                modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            HwButton("+", Modifier.width(36.dp).fillMaxHeight(), minHeight = 30.dp, fontSize = 14, hPad = 0.dp) { g.setRepeats(i, st.repeats + 1) }
                            HwButton("▲", Modifier.width(34.dp).fillMaxHeight(), enabled = i > 0, minHeight = 30.dp, fontSize = 11, hPad = 0.dp) { g.moveSongStep(i, -1) }
                            HwButton("▼", Modifier.width(34.dp).fillMaxHeight(), enabled = i < steps.size - 1, minHeight = 30.dp, fontSize = 11, hPad = 0.dp) { g.moveSongStep(i, 1) }
                            HwButton("✕", Modifier.width(34.dp).fillMaxHeight(), minHeight = 30.dp, fontSize = 12, hPad = 0.dp) { g.removeSongStep(i) }
                        }
                    }
                }
            }
        }
    }
}

/** The 16 patterns as pads (4 × 4, pattern 1 top-left, like reading). */
@Composable
private fun PatternPads(state: AppState, modifier: Modifier) {
    val g = state.groove
    val cur = g.patternIdx.intValue
    val queued = g.queuedPattern.intValue
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (r in 0 until 4) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (c in 0 until 4) {
                val p = r * 4 + c
                val has = g.hasNotes(p)
                val col = Tb.Pads[p % Tb.Pads.size]
                val tracks = g.tracksIn(p)
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                    .background(when { p == cur -> col; has -> col.copy(alpha = 0.32f); else -> Tb.Raised.copy(alpha = 0.55f) })
                    // PLAY mode: the controller's D-pad cursor
                    .then(if (p == state.songCursor.intValue && !state.input.navMode.value) Modifier.border(2.dp, Tb.Text, RoundedCornerShape(10.dp)) else Modifier)
                    .navClickable(label = g.patternName(p)) { g.selectPattern(p); Info.show("PATTERN") }) {
                    // up next: a pulsing outline (redrawn per frame, no recomposition)
                    if (p == queued) Canvas(Modifier.fillMaxSize()) {
                        state.telemetry.frame.longValue
                        val a = 0.5f + 0.5f * sin(state.telemetry.timeSec * 9f)
                        drawRoundRect(Tb.Text.copy(alpha = a), Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
                    }
                    val ink = if (p == cur) Tb.on(col) else if (has) Tb.Text else Tb.Dim
                    Text("${p + 1}", color = ink, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp))
                    if (has) Text("${g.patternTicks(p) / GrooveController.BAR}B", color = ink.copy(alpha = 0.75f), fontFamily = Tb.Mono, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 6.dp))
                    // a dot per track with notes, in the track's colour
                    if (tracks != 0) Row(Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        state.tracks.value.forEachIndexed { t, tr ->
                            if (tracks and (1 shl t) != 0) Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (p == cur) Tb.on(col) else kindColor(tr.kind)))
                        }
                    }
                }
            }
        }
    }
}

/** A thin bar: where we are in the current pattern; in a song, the song's progress underneath. */
@Composable
private fun PositionBar(state: AppState, modifier: Modifier) {
    val g = state.groove
    Canvas(modifier) {
        state.telemetry.frame.longValue
        val len = g.patternTicks(g.patternIdx.intValue).toDouble()
        val playing = g.transport[1] != 0.0
        val f = if (playing && len > 0) ((g.loopTick % len) / len).toFloat() else 0f
        val bars = (len / GrooveController.BAR).toInt().coerceAtLeast(1)
        drawRoundRect(Tb.Raised, Offset.Zero, Size(size.width, size.height * 0.6f), CornerRadius(4f))
        drawRoundRect(Tb.Tempo, Offset.Zero, Size(size.width * f, size.height * 0.6f), CornerRadius(4f))
        for (b in 1 until bars) { val x = size.width * b / bars; drawLine(Tb.Void, Offset(x, 0f), Offset(x, size.height * 0.6f), 2f) }
        val steps = g.song.value
        val pos = g.songPos.intValue
        if (g.songMode.value && steps.isNotEmpty() && pos >= 0) {
            val total = steps.sumOf { g.patternTicks(it.pattern) * it.repeats }.toFloat()
            var done = 0f
            for (i in 0 until pos) done += g.patternTicks(steps[i].pattern) * steps[i].repeats
            done += g.loopTick.toFloat().coerceAtMost(g.patternTicks(steps[pos].pattern) * steps[pos].repeats.toFloat())
            drawRect(Tb.Good, Offset(0f, size.height * 0.75f), Size(size.width * (done / total).coerceIn(0f, 1f), size.height * 0.25f))
        }
    }
}

/** The project key, with the KEY LOCK state: tap for the key sheet. */
@Composable
fun KeyChip(state: AppState, modifier: Modifier = Modifier) {
    val k = state.lockKey()
    val locked = state.lockAll.value
    HwButton((if (locked) "🔒 " else "KEY ") + k.label().uppercase(), modifier, active = locked, accent = Tb.Warn, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) {
        state.showKeySheet.value = true
    }
}

/**
 * The key sheet: pick the project's root and scale, and KEY LOCK (hold every track in it). Locked: the
 * samples are key-matched to it, synth notes snap into it as they play, the keys stay in scale, and the
 * key stops following whatever sample you load.
 */
@Composable
fun KeySheet(state: AppState, modifier: Modifier = Modifier) {
    val g = state.groove
    val s = g.scale.value
    val k = state.lockKey()
    val locked = state.lockAll.value
    Column(modifier.sheet(onClose = { state.showKeySheet.value = false }).width(560.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("KEY", style = Type.title)
            Text(k.label().uppercase() + if (s.followSample && !locked) " · follows the sample" else "", style = Type.value, modifier = Modifier.weight(1f))
            HwButton("✕", Modifier.width(44.dp), minHeight = 34.dp, fontSize = 14) { state.showKeySheet.value = false }
        }
        // roots
        for (row in 0 until 2) Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in row * 6 until row * 6 + 6) {
                val pc = PitchClass.entries[i]
                HwButton(NOTE_LABELS[i], Modifier.weight(1f).fillMaxHeight(), active = k.root == pc, accent = Tb.Key, minHeight = 34.dp, fontSize = 13, hPad = 2.dp) {
                    state.setProjectKey(pc, k.mode)
                }
            }
        }
        // scales
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in listOf(ScaleMode.MAJOR, ScaleMode.MINOR, ScaleMode.DORIAN, ScaleMode.MIXOLYDIAN, ScaleMode.PENTATONIC_MINOR)) {
                HwButton(com.thorbox.app.groove.Scales.label(m), Modifier.weight(1f).fillMaxHeight(), active = k.mode == m, accent = Tb.Key, minHeight = 34.dp, fontSize = 11, hPad = 2.dp) {
                    state.setProjectKey(k.root, m)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton(if (locked) "🔒 KEY LOCK ON" else "KEY LOCK OFF", Modifier.weight(1.4f).fillMaxHeight(), active = locked, accent = Tb.Warn, minHeight = 40.dp, fontSize = 13) {
                state.setLockAll(!locked)
            }
            HwButton("FOLLOW SAMPLE", Modifier.weight(1f).fillMaxHeight(), active = s.followSample && !locked, enabled = !locked, minHeight = 40.dp, fontSize = 11) {
                g.setScale(s.copy(followSample = !s.followSample, root = k.root, mode = k.mode))
            }
        }
        Text(if (locked) "Every track is held in ${k.label()}: samples are key-matched to it (new ones too), synth patterns snap into it as they play (your notes are kept), the keys stay in scale. Change the key above and everything moves with it."
             else "KEY LOCK holds every track in one key: samples key-matched, synth notes snapped into the scale, the keys in scale.",
            color = Tb.Muted, fontSize = 12.sp, lineHeight = 15.sp)
    }
}

private val NOTE_LABELS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

@Suppress("unused") private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)
