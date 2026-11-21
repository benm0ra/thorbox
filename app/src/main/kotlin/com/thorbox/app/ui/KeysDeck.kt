// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.view.MotionEvent
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * Deck 2, the scale keyboard: a 4×4 grid laid out like MPC pads (bottom-left is lowest).
 * With LOCK on, every pad is in key (the loaded sample's detected key), so there are no wrong notes.
 * Pads light from the engine's real sounding-note mask, so release tails glow.
 *
 * Multi-touch: one pointer handler for the whole grid. Each finger remembers the notes it
 * started, so toggling chord/lock or the octave mid-press can never leave a stuck note.
 * Sliding a finger to another pad re-triggers there (glissando).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeysDeck(state: AppState, modifier: Modifier = Modifier) {
    val sample by state.sample.collectAsState()
    val key = state.lockKey()
    val lock = state.keyLock.value
    val chord = state.chordMode.value
    val octave = state.keyOctave.intValue
    val tel = state.telemetry
    val gridSize = remember { IntArray(2) }
    val fingers = remember { HashMap<Int, Pair<Int, IntArray>>() }   // pointerId → (cell, notes)

    fun cellAt(x: Float, y: Float): Int {
        val w = gridSize[0].coerceAtLeast(1); val h = gridSize[1].coerceAtLeast(1)
        val col = (x / w * 4).toInt().coerceIn(0, 3)
        val rowFromTop = (y / h * 4).toInt().coerceIn(0, 3)
        return (3 - rowFromTop) * 4 + col                          // bottom row = 0..3
    }
    fun velocityAt(y: Float): Float {
        val h = gridSize[1].coerceAtLeast(1) / 4f
        val inCell = (y % h) / h
        return 0.35f + 0.65f * (1f - inCell)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HwButton("OCT−", minHeight = 44.dp, fontSize = 12) { state.shiftOctave(-1) }
            Text(String.format(java.util.Locale.US, "%+d", octave), color = Tb.Text, fontFamily = Tb.Mono,
                fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            HwButton("OCT+", minHeight = 44.dp, fontSize = 12) { state.shiftOctave(+1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (sample?.key == null && lock) Text("no sample key · using ${key.label()}", color = Tb.Muted, fontSize = 11.sp,
                    maxLines = 1, modifier = Modifier.padding(end = 6.dp))
            }
            HwButton("CHORD", active = chord, accent = Tb.EncGreen, minHeight = 44.dp, fontSize = 12) { state.chordMode.value = !chord }
            HwButton(if (lock) key.label().uppercase() else "CHROM", active = lock, accent = Tb.Key, minHeight = 44.dp, fontSize = 12) {
                state.releaseAllKeys(); state.keyLock.value = !lock
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { s: IntSize -> gridSize[0] = s.width; gridSize[1] = s.height }
                .pointerInteropFilter { ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val i = ev.actionIndex
                            val cell = cellAt(ev.getX(i), ev.getY(i))
                            val notes = state.notesForKey(cell)
                            fingers[ev.getPointerId(i)] = cell to notes
                            state.keyDown(notes, velocityAt(ev.getY(i)))
                        }
                        MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) {
                            val id = ev.getPointerId(i)
                            val cur = fingers[id] ?: continue
                            val cell = cellAt(ev.getX(i), ev.getY(i))
                            if (cell != cur.first) {
                                state.keyUp(cur.second)
                                val notes = state.notesForKey(cell)
                                fingers[id] = cell to notes
                                state.keyDown(notes, velocityAt(ev.getY(i)))
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                            fingers.remove(ev.getPointerId(ev.actionIndex))?.let { state.keyUp(it.second) }
                        }
                        MotionEvent.ACTION_CANCEL -> { fingers.values.forEach { state.keyUp(it.second) }; fingers.clear() }
                    }
                    true
                }
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rowFromTop in 0 until 4) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0 until 4) {
                            val cell = (3 - rowFromTop) * 4 + col
                            val notes = state.notesForKey(cell)
                            val root = notes[0]
                            val isTonic = lock && ((root - key.root.ordinal) % 12 + 12) % 12 == 0
                            val base = if (isTonic) Tb.EncOrange.copy(alpha = 0.35f) else Tb.Raised
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                    .drawBehind {
                                        tel.frame.longValue                // light from engine state, draw-only
                                        val on = notes.any { tel.isNoteOn(it) }
                                        drawRoundRect(if (on) Tb.EncOrange else base, cornerRadius = CornerRadius(10.dp.toPx()))
                                    },
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text("${NOTE_NAMES[root % 12]}${root / 12 - 1}", color = Tb.Text, fontWeight = FontWeight.Black,
                                    fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
