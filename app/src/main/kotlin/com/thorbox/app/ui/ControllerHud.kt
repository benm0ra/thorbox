// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.TopView
import kotlinx.coroutines.delay

/**
 * Hold SELECT: a map of the physical controls for the current view, so nobody has to memorise
 * the layout. Appears after a short hold (a quick tap is still just RECORD) and never takes touches.
 */
@Composable
fun BoxScope.ControllerHud(state: AppState) {
    val held = state.input.shiftHeld.value
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(held) { if (held) { delay(280); show = true } else show = false }

    AnimatedVisibility(show, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
        val view = state.topView.value
        Row(
            Modifier.padding(16.dp)
                .background(Tb.Bg.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                .border(1.dp, Tb.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                HudTitle("SELECT +", Tb.Key)
                HudRow("◀ ▶", "view  ${TopView.entries.joinToString(" · ") { short(it) }}")
                HudRow("▲ ▼", "deck  PADS · KEYS · DRUMS · STEP · FX")
                HudRow("L1 R1", "tempo −1 / +1")
                HudRow("A", "tap tempo")
                HudRow("X", "metronome")
                HudRow("Y", "glass look")
                HudRow("L3", "theme  (${Tb.palette.label})")
                HudRow("B", "diagnostics")
                HudRow("release", "on its own = RECORD", dim = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                HudTitle(short(view), accent(view))
                contextRows(view).forEach { (k, v) -> HudRow(k, v) }
                HudTitle("ALWAYS", Tb.Muted)
                HudRow("START", "play / stop")
                HudRow("B", "back")
                HudRow("L1 hold", "note repeat")
                HudRow("L2 / R2", "filter / stutter punch-in")
            }
        }
    }
}

private fun short(v: TopView) = when (v) {
    TopView.SAMPLER -> "SAMPLE"; TopView.SYNTH -> "SYNTH"; TopView.DRUM -> "DRUMS"; TopView.SEQ -> "STEPS"; TopView.MIX -> "MIXER"; TopView.VIZ -> "VISUAL"
}

private fun accent(v: TopView) = when (v) {
    TopView.SAMPLER -> Tb.Pads[0]; TopView.SYNTH -> Tb.EncOrange; TopView.DRUM -> Tb.Pads[1]; TopView.SEQ -> Tb.Good
    TopView.MIX -> Tb.EncWhite; TopView.VIZ -> Tb.EncBlue
}

private fun contextRows(v: TopView): List<Pair<String, String>> = when (v) {
    TopView.SAMPLER -> listOf(
        "D-pad" to "choose pad", "A / R1" to "play pad", "L-stick ◀▶" to "pitch ±1",
        "L-stick ▲▼" to "pitch ±12", "X" to "play mode", "Y" to "reset pitch")
    TopView.SYNTH -> listOf(
        "sticks" to "the 4 encoders", "◀ ▶" to "page", "▲ ▼" to "octave",
        "A / R1" to "play root", "X" to "chord mode", "Y" to "reset page", "L3 / R3" to "next preset / DICE")
    TopView.DRUM -> listOf(
        "◀ ▶" to "step", "▲ ▼" to "sound", "A" to "toggle step", "R1" to "play sound",
        "sticks" to "the 4 encoders", "X / Y" to "dice beat / undo", "L3 / R3" to "next kit / dice kit")
    TopView.SEQ -> listOf(
        "◀ ▶" to "step cursor", "▲ ▼" to "edit lane", "A" to "toggle step", "X" to "dice", "Y" to "undo")
    TopView.MIX -> listOf(
        "◀ ▶" to "select strip", "▲ ▼ / L-stick" to "volume", "R-stick" to "pan", "A" to "mute", "Y" to "solo")
    TopView.VIZ -> listOf("B" to "leave visualizer")
}

@Composable
private fun HudTitle(t: String, c: Color) =
    Text(t, color = c, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 2.dp, top = 4.dp))

@Composable
private fun HudRow(key: String, what: String, dim: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(92.dp)) {
            Text(key, color = if (dim) Tb.Muted else Tb.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Tb.Raised, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text(what, color = if (dim) Tb.Muted else Tb.Text.copy(alpha = 0.88f), fontSize = 12.sp)
    }
}
