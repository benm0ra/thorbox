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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable
import com.thorbox.app.groove.GrooveController
import com.thorbox.model.PitchClass
import com.thorbox.model.ScaleMode
import java.util.Locale
import kotlin.math.sin

/**
 * SONG: patterns are your loops; the song is the order they play in.
 *
 *   header    ▶ PLAYS: SONG | PATTERN (what the play button plays) · LOOP SONG / PLAY ONCE · KEY · EXPORT ·
 *             what's happening, in words ("▶ SONG · PART 2 OF 4 · PAT 2, 1st of 2")
 *   timeline  the song as blocks, each as long as it plays, with the playhead. Tap a block to pick that part.
 *   left      PATTERNS: 16 pads. The lit one is the pattern you're on (what SYNTH / DRUMS / STEPS edit), and
 *             it fills as it plays. Tap another to switch (playing: it waits for the bar and changes on the
 *             beat; NEXT shows it's waiting). DUPLICATE · CLEAR · ADD TO SONG →
 *   right     SONG: the parts top to bottom, how many times each plays. Tap a part: change its repeats,
 *             move it, play from it, remove it.
 *
 * Every editor (STEPS, the drum grid, the chop lane, the piano roll) edits the current pattern, so a
 * song is: make pattern 1, DUPLICATE it and change the copy, add them to the song in order.
 */
@Composable
fun SongScreen(state: AppState) {
    val g = state.groove
    g.bankVersion.intValue                                     // recompose when patterns change
    val cur = g.patternIdx.intValue
    val songMode = g.songMode.value
    val steps = g.song.value
    val sel = state.songPart.intValue.let { if (it in steps.indices) it else -1 }
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().height(Heights.header).glass(state, 12.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Tb.Tempo))
            Text("SONG", style = Type.title, maxLines = 1)
            Text("▶ PLAYS", style = Type.label, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            HwButton("THE SONG", Modifier.fillMaxHeight(), active = songMode, enabled = steps.isNotEmpty() || songMode, accent = Tb.Tempo,
                minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.setSongMode(true) }
            HwButton("ONE PATTERN", Modifier.fillMaxHeight(), active = !songMode, accent = Tb.Tempo, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.setSongMode(false) }
            HwButton(if (g.songLoop.value) "LOOP SONG ↻" else "PLAY ONCE", Modifier.fillMaxHeight(), active = g.songLoop.value, enabled = songMode,
                accent = Tb.EncWhite, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) { g.songLoop.value = !g.songLoop.value }
            KeyChip(state, Modifier.fillMaxHeight())
            HwButton("EXPORT", Modifier.fillMaxHeight(), accent = Tb.Good, minHeight = 30.dp, fontSize = 11, hPad = 8.dp) {
                state.exporter.result.value = null
                state.exporter.options.value = state.exporter.options.value.copy(
                    what = if (songMode && steps.isNotEmpty()) com.thorbox.app.export.Exporter.What.SONG else com.thorbox.app.export.Exporter.What.PATTERN)
                state.exporter.showSheet.value = true
            }
            Box(Modifier.weight(1f))
            NowPlaying(state)
        }
        // ---- the song as a timeline ----
        SongTimeline(state, Modifier.fillMaxWidth().height(58.dp))

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---- PATTERNS: the loops ----
            Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PATTERNS", style = Type.title, maxLines = 1)
                    Text("your loops · lit = the one you're on (SYNTH, DRUMS and STEPS edit it) · tap to switch",
                        color = Tb.Muted, fontSize = 11.sp, maxLines = 1)
                }
                PatternPads(state, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HwButton("DUPLICATE ${g.patternName(cur)}", Modifier.weight(1.3f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp) {
                        val to = g.firstEmptyPattern()
                        if (to < 0) Info.toast("ALL 16 USED", "Clear a pattern to make room")
                        else { g.copyPattern(cur, to); Info.toast("DUPLICATED", "${g.patternName(cur)} copied to PAT ${to + 1}. Tap PAT ${to + 1} to change the copy.") }
                    }
                    HwButton("CLEAR", Modifier.weight(0.8f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp, accent = Tb.Warn) {
                        g.clearPattern(cur); Info.toast("CLEARED", "${g.patternName(cur)} emptied (UNDO on each track brings it back)")
                    }
                    HwButton("ADD ${g.patternName(cur)} TO SONG →", Modifier.weight(1.5f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 4.dp,
                        accent = Tb.Tempo, active = true, enabled = steps.size < 64) {
                        val at = if (sel >= 0) sel + 1 else steps.size
                        g.insertSongStep(at, cur)
                        state.songPart.intValue = at
                        Info.toast("ADDED", "${g.patternName(cur)} is part ${at + 1} of the song")
                    }
                }
            }
            // ---- SONG: the parts, in order ----
            Column(Modifier.weight(1f).fillMaxHeight().glass(state, 12.dp).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SONG", style = Type.title, maxLines = 1)
                        Text(if (steps.isEmpty()) "empty" else "${steps.size} part${if (steps.size > 1) "s" else ""} · ${g.songBars()} bars · ${songTime(g)} · plays top to bottom",
                            color = Tb.Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    val playingSong = songMode && g.playing.value
                    HwButton(if (playingSong) "■ STOP" else "▶ PLAY SONG", Modifier.width(150.dp).fillMaxHeight(), active = playingSong,
                        enabled = steps.isNotEmpty(), accent = Tb.Good, minHeight = 34.dp, fontSize = 12) {
                        if (playingSong) g.togglePlay() else g.playSong(0)
                    }
                }
                if (steps.isEmpty()) EmptySongGuide(Modifier.weight(1f).fillMaxWidth())
                else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    steps.forEachIndexed { i, st -> SongPartRow(state, i, st, selected = i == sel) }
                }
                if (sel >= 0) PartEditor(state, sel, steps[sel])
                else if (steps.isNotEmpty()) Text("Tap a part to change how many times it plays, move it or play from it.",
                    color = Tb.Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
            }
        }
    }
}

private fun songTime(g: GrooveController): String {
    val secs = g.song.value.sumOf { g.patternTicks(it.pattern).toLong() * it.repeats } / (g.groove.value.bpm * com.thorbox.model.PPQN / 60.0)
    val t = secs.toInt(); return "%d:%02d".format(t / 60, t % 60)
}

/** What's happening, in words (redrawn per frame: the repeat count moves on its own). */
@Composable
private fun NowPlaying(state: AppState) {
    val g = state.groove
    state.telemetry.frame.longValue
    val cur = g.patternIdx.intValue
    val steps = g.song.value
    val pos = g.songPos.intValue
    val queued = g.queuedPattern.intValue
    val txt = when {
        g.playing.value && g.songMode.value && pos in steps.indices -> {
            val st = steps[pos]
            val rep = (g.loopTick / g.patternTicks(st.pattern).coerceAtLeast(1)).toInt().coerceIn(0, st.repeats - 1) + 1
            "▶ SONG · PART ${pos + 1} OF ${steps.size} · ${g.patternName(st.pattern)}" + if (st.repeats > 1) " ($rep of ${st.repeats})" else ""
        }
        g.playing.value && queued >= 0 -> "▶ ${g.patternName(cur)} · ${g.patternName(queued)} NEXT"
        g.playing.value -> "▶ LOOPING ${g.patternName(cur)}"
        g.songMode.value && steps.isNotEmpty() -> "■ STOPPED · ▶ plays the song"
        else -> "■ STOPPED · ▶ loops ${g.patternName(cur)}"
    }
    Text(txt, style = Type.value, maxLines = 1)
}

/**
 * The song as a strip of blocks, each as wide as it plays (a bar line per repeat), the part playing
 * lit, the playhead moving through. Tap a block to pick that part.
 */
@Composable
private fun SongTimeline(state: AppState, modifier: Modifier) {
    val g = state.groove
    val tm = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 64)
    val steps = g.song.value
    val lbl = androidx.compose.runtime.remember(Tb.palette) { androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Black, fontSize = 11.sp) }
    val hint = androidx.compose.runtime.remember(Tb.palette) { androidx.compose.ui.text.TextStyle(color = Tb.Muted, fontSize = 12.sp) }
    Canvas(modifier.glass(state, 10.dp).padding(5.dp)
        .pointerInput(steps) {
            detectTapGestures { o ->
                val total = steps.sumOf { g.patternTicks(it.pattern).toLong() * it.repeats }.toFloat()
                if (total <= 0f) return@detectTapGestures
                var acc = 0f
                for ((i, st) in steps.withIndex()) {
                    acc += g.patternTicks(st.pattern) * st.repeats
                    if (o.x <= size.width * acc / total) { state.songPart.intValue = i; break }
                }
            }
        }) {
        state.telemetry.frame.longValue
        val w = size.width; val h = size.height
        if (steps.isEmpty()) {
            val t = tm.measure("SONG TIMELINE · empty: pick a pattern below and press ADD TO SONG →", hint)
            drawText(t, topLeft = Offset((w - t.size.width) / 2f, (h - t.size.height) / 2f))
            return@Canvas
        }
        val lens = steps.map { g.patternTicks(it.pattern).toFloat() * it.repeats }
        val total = lens.sum()
        val pos = g.songPos.intValue
        val inSong = g.songMode.value && g.playing.value && pos in steps.indices
        val sel = state.songPart.intValue
        var x = 0f
        val gap = 3.dp.toPx()
        for ((i, st) in steps.withIndex()) {
            val bw = w * lens[i] / total
            val c = Tb.Pads[st.pattern % Tb.Pads.size]
            val lit = inSong && i == pos
            drawRoundRect(c.copy(alpha = if (lit) 1f else if (g.songMode.value) 0.55f else 0.3f), Offset(x, 0f), Size((bw - gap).coerceAtLeast(2f), h), CornerRadius(7.dp.toPx()))
            // a line per repeat
            for (r in 1 until st.repeats) { val rx = x + bw * r / st.repeats; drawLine(Tb.Void.copy(alpha = 0.5f), Offset(rx, h * 0.55f), Offset(rx, h), 2f) }
            if (i == sel) drawRoundRect(Tb.Text, Offset(x + 1f, 1f), Size((bw - gap - 2f).coerceAtLeast(2f), h - 2f), CornerRadius(7.dp.toPx()), style = Stroke(2.5f.dp.toPx()))
            val name = tm.measure(g.patternName(st.pattern) + if (st.repeats > 1) " ×${st.repeats}" else "", lbl.copy(color = if (lit) Tb.on(c) else Tb.Text))
            if (name.size.width < bw - 10.dp.toPx()) drawText(name, topLeft = Offset(x + 6.dp.toPx(), 4.dp.toPx()))
            else { val n = tm.measure("${st.pattern + 1}", lbl.copy(color = if (lit) Tb.on(c) else Tb.Text)); if (n.size.width < bw - 4f) drawText(n, topLeft = Offset(x + 3.dp.toPx(), 4.dp.toPx())) }
            x += bw
        }
        // the playhead
        if (inSong) {
            var done = 0f
            for (i in 0 until pos) done += lens[i]
            done += g.loopTick.toFloat().coerceIn(0f, lens[pos])
            val px = w * (done / total).coerceIn(0f, 1f)
            drawLine(Tb.Text, Offset(px, -2f), Offset(px, h + 2f), 3.dp.toPx())
        }
    }
}

/** One part of the song: number, pattern, how long it plays; lit while it plays, outlined when picked. */
@Composable
private fun SongPartRow(state: AppState, i: Int, st: com.thorbox.model.SongStep, selected: Boolean) {
    val g = state.groove
    val now = g.songMode.value && g.playing.value && g.songPos.intValue == i
    val c = Tb.Pads[st.pattern % Tb.Pads.size]
    val bars = g.patternTicks(st.pattern) / GrooveController.BAR
    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
        .background(if (now) c.copy(alpha = 0.35f) else Tb.Raised.copy(alpha = 0.6f))
        .then(if (selected) Modifier.border(2.dp, Tb.Text, RoundedCornerShape(8.dp)) else if (now) Modifier.border(2.dp, c, RoundedCornerShape(8.dp)) else Modifier)
        .navClickable(label = "PART ${i + 1}") { state.songPart.intValue = if (selected) -1 else i }
        .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (now) "▶" else "${i + 1}", color = if (now) Tb.Good else Tb.Muted, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Box(Modifier.width(10.dp).height(20.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(g.patternName(st.pattern), color = Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(if (st.repeats > 1) "plays ${st.repeats}× · ${bars * st.repeats} bars" else "plays once · $bars bar${if (bars > 1) "s" else ""}",
            color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1)
    }
}

/** The picked part's controls. */
@Composable
private fun PartEditor(state: AppState, i: Int, st: com.thorbox.model.SongStep) {
    val g = state.groove
    val steps = g.song.value
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tb.Raised.copy(alpha = 0.5f)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("PART ${i + 1} · ${g.patternName(st.pattern)}", style = Type.label, maxLines = 1)
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYS", style = Type.label)
            HwButton("−", Modifier.width(40.dp).fillMaxHeight(), enabled = st.repeats > 1, minHeight = 34.dp, fontSize = 15, hPad = 0.dp) { g.setRepeats(i, st.repeats - 1) }
            Text("${st.repeats}×", color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 14.sp,
                modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            HwButton("+", Modifier.width(40.dp).fillMaxHeight(), enabled = st.repeats < 16, minHeight = 34.dp, fontSize = 15, hPad = 0.dp) { g.setRepeats(i, st.repeats + 1) }
            Box(Modifier.width(4.dp))
            HwButton("◀ EARLIER", Modifier.weight(1f).fillMaxHeight(), enabled = i > 0, minHeight = 34.dp, fontSize = 10, hPad = 2.dp) { g.moveSongStep(i, -1); state.songPart.intValue = i - 1 }
            HwButton("LATER ▶", Modifier.weight(1f).fillMaxHeight(), enabled = i < steps.size - 1, minHeight = 34.dp, fontSize = 10, hPad = 2.dp) { g.moveSongStep(i, 1); state.songPart.intValue = i + 1 }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HwButton("▶ PLAY FROM HERE", Modifier.weight(1.4f).fillMaxHeight(), accent = Tb.Good, minHeight = 34.dp, fontSize = 11, hPad = 2.dp) { g.playSong(i) }
            HwButton("EDIT ${g.patternName(st.pattern)}", Modifier.weight(1f).fillMaxHeight(), minHeight = 34.dp, fontSize = 11, hPad = 2.dp) {
                g.selectPattern(st.pattern); Info.toast(g.patternName(st.pattern), "Now on ${g.patternName(st.pattern)}: SYNTH, DRUMS and STEPS edit it (every part using it changes)")
            }
            HwButton("REMOVE", Modifier.weight(0.9f).fillMaxHeight(), accent = Tb.Warn, minHeight = 34.dp, fontSize = 11, hPad = 2.dp) {
                g.removeSongStep(i); state.songPart.intValue = -1
            }
        }
    }
}

/** An empty song: how a song gets made, in three moves. */
@Composable
private fun EmptySongGuide(modifier: Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((n, t) in listOf(
            "1" to "Make a beat in pattern 1 on SYNTH, DRUMS or STEPS. That's a loop.",
            "2" to "DUPLICATE it and change the copy: a chorus, a break, a drop.",
            "3" to "Tap a pattern, press ADD TO SONG →, and repeat in the order you want them. Then ▶ PLAY SONG.")) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(26.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(Tb.Tempo), contentAlignment = Alignment.Center) {
                    Text(n, color = Tb.on(Tb.Tempo), fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
                Text(t, color = Tb.Text, fontSize = 13.sp, lineHeight = 17.sp)
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
    val inSong = g.song.value.map { it.pattern }.toSet()
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
                    .navClickable(label = g.patternName(p)) {
                        val leaving = g.songMode.value && g.playing.value && p != cur
                        g.selectPattern(p)
                        if (leaving) Info.toast("LEFT THE SONG", "${g.patternName(p)} loops from the next bar. ▶ PLAY SONG goes back to the song.")
                        else Info.show("PATTERN")
                    }) {
                    val ink = if (p == cur) Tb.on(col) else if (has) Tb.Text else Tb.Dim
                    // the one playing fills as it goes; the one waiting pulses
                    Canvas(Modifier.fillMaxSize()) {
                        state.telemetry.frame.longValue
                        if (p == cur && g.playing.value) {
                            val len = g.patternTicks(p).toDouble()
                            val f = if (len > 0) ((g.loopTick % len) / len).toFloat() else 0f
                            drawRect(Tb.on(col).copy(alpha = 0.16f), Offset.Zero, Size(size.width * f, size.height))
                        }
                        if (p == queued) {
                            val a = 0.5f + 0.5f * sin(state.telemetry.timeSec * 9f)
                            drawRoundRect(Tb.Text.copy(alpha = a), Offset(2f, 2f), Size(size.width - 4f, size.height - 4f), CornerRadius(10.dp.toPx()), style = Stroke(3.dp.toPx()))
                        }
                    }
                    Text("${p + 1}", color = ink, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp))
                    val tag = when {
                        p == queued -> "NEXT"
                        p == cur && g.playing.value -> "▶ PLAYING"
                        p == cur -> "YOU'RE HERE"
                        else -> null
                    }
                    if (tag != null) Text(tag, color = ink, fontWeight = FontWeight.Black, fontSize = 9.sp, letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 6.dp))
                    else if (has) Text("${g.patternTicks(p) / GrooveController.BAR} BAR" + if (g.patternTicks(p) > GrooveController.BAR) "S" else "",
                        color = ink.copy(alpha = 0.75f), fontFamily = Tb.Mono, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(end = 7.dp, top = 6.dp))
                    if (!has && p != cur) Text("empty", color = Tb.Dim, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
                    // a dot per track with notes, in the track's colour; IN SONG when the song uses it
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 7.dp, end = 7.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tracks != 0) state.tracks.value.forEachIndexed { t, tr ->
                            if (tracks and (1 shl t) != 0) Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (p == cur) Tb.on(col) else kindColor(tr.kind)))
                        }
                        Box(Modifier.weight(1f))
                        if (p in inSong) Text("IN SONG", color = ink.copy(alpha = 0.8f), fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }
            }
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
            for ((m, name) in listOf(ScaleMode.MAJOR to "MAJOR", ScaleMode.MINOR to "MINOR", ScaleMode.DORIAN to "DORIAN",
                    ScaleMode.MIXOLYDIAN to "MIXO", ScaleMode.PENTATONIC_MINOR to "PENTA")) {
                HwButton(name, Modifier.weight(1f).fillMaxHeight(), active = k.mode == m, accent = Tb.Key, minHeight = 34.dp, fontSize = 11, hPad = 2.dp) {
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
