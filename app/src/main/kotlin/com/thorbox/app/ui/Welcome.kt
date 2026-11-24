// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui


import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState

private data class Card(val title: String, val lines: List<String>, val accent: () -> androidx.compose.ui.graphics.Color)

private val CARDS = listOf(
    Card("TWO SCREENS, ONE GROOVEBOX", listOf(
        "TOP: what you're shaping. SAMPLE, SYNTH, DRUMS, STEPS, MIXER (left rail).",
        "BOTTOM: what you play. Pads, keys, drums and FX (tabs on top).",
        "Up to 8 tracks: MIXER → + TRACK adds a sampler, synth or drum machine.",
    )) { Tb.Pads[0] },
    Card("TOUCH ANYTHING TO LEARN IT", listOf(
        "Every button and knob explains itself in the strip at the bottom.",
        "Hold SELECT to see what every Thor button does right now.",
        "SELECT + A: drive the whole app from the buttons, no touching needed.",
    )) { Tb.Key },
    Card("YOUR FIRST BEAT IN 30 SECONDS", listOf(
        "DRUMS → DICE BEAT, and press START (or ▶) to hear it.",
        "SYNTH → tap the preset name → 808 → pick one, then play the KEYS deck.",
        "Turn on REC and play: you're recording. GROOVE gives it swing.",
    )) { Tb.Good },
)

/** First-run welcome (3 cards). Long-press the ? chip to see it again. */
@Composable
fun Welcome(state: AppState, modifier: Modifier = Modifier) {
    val i = remember { mutableIntStateOf(0) }
    Column(modifier.sheet { state.finishWelcome() }.width(520.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CLAMSHELL BEATS", color = Tb.Muted, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 3.sp)
            Box(Modifier.weight(1f))
            CARDS.indices.forEach { k ->
                Box(Modifier.padding(start = 5.dp).size(if (k == i.intValue) 9.dp else 6.dp).clip(CircleShape)
                    .background(if (k == i.intValue) CARDS[k].accent() else Tb.Dim))
            }
        }
        AnimatedContent(i.intValue, label = "welcome") { k ->
            val c = CARDS[k]
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(c.title, color = c.accent(), fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 1.sp)
                c.lines.forEachIndexed { n, l ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("${n + 1}", color = c.accent(), fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                        Text(l, color = Tb.Text, fontSize = 15.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HwButton("SKIP", minHeight = 48.dp, fontSize = 12) { state.finishWelcome() }
            Box(Modifier.weight(1f))
            if (i.intValue > 0) HwButton("BACK", minHeight = 48.dp, fontSize = 12) { i.intValue-- }
            val last = i.intValue == CARDS.lastIndex
            HwButton(if (last) "LET'S GO" else "NEXT", minHeight = 48.dp, fontSize = 14, active = true, accent = CARDS[i.intValue].accent()) {
                if (last) state.finishWelcome() else i.intValue++
            }
        }
    }
}
