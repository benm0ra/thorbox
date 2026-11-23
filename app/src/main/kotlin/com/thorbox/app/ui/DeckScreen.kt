// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.view.MotionEvent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.model.Deck
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bottom (3.92", near-square) Touch Deck for M1: deck tabs (only PADS is live), one giant pad,
 * and a pitch strip. In M2 the giant pad becomes the 4×4 grid in the same space.
 */
@Composable
fun DeckScreen(state: AppState) {
    val deck = state.deck.value
    AuroraBackground(state) {
    Box(Modifier.fillMaxSize()) {
    // The bottom strip (NAV_BAR tall) is always a status line now: the deck's track, what's selected and
    // the bar / beat. While the NAV cursor is on this screen its status draws over it, in the same place,
    // so the pads never move (it used to be an empty band kept free for the cursor).
    Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DeckTabs(deck, state::selectDeck, Modifier.glass(state, 12.dp).padding(4.dp))
        when (deck) {
            Deck.KEYS -> { KeysDeck(state, Modifier.weight(1f).fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
            Deck.DRUMS -> { DrumsDeck(state, Modifier.weight(1f).fillMaxWidth()); LevelsStrip(state, drums = true, Modifier.fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
            Deck.STEP -> StepDeck(state, Modifier.weight(1f).fillMaxWidth())
            Deck.FX -> FxDeck(state, Modifier.weight(1f).fillMaxWidth())
            else -> { Pad16Deck(state, Modifier.weight(1f).fillMaxWidth()); LevelsStrip(state, drums = false, Modifier.fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
        }
        DeckStatus(state, deck, Modifier.fillMaxWidth())
    }
    NavBadge(state, 1)
    }
    }
}

/** Tabs along the top bezel. Switching decks also switches the top screen's contextual view. */
@Composable
private fun DeckTabs(current: Deck, onSelect: (Deck) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Deck.PADS to true, Deck.KEYS to true, Deck.DRUMS to true, Deck.STEP to true, Deck.FX to true).forEach { (d, live) ->
            HwButton(d.name, Modifier.weight(1f).fillMaxHeight(), active = d == current, enabled = live,
                accent = d.section.color, minHeight = 40.dp, fontSize = 12) { onSelect(d) }
        }
    }
}

/**
 * The deck's status line (bottom strip): the track this deck plays in its colour, what's selected on it,
 * and the bar · beat with four beat lights. Same geometry as the NAV status, which covers it while
 * the cursor is on this screen.
 */
@Composable
private fun DeckStatus(state: AppState, deck: Deck, modifier: Modifier = Modifier) {
    val t = when (deck) {
        Deck.KEYS -> state.synthTrack.intValue
        Deck.DRUMS -> state.drumTrack.intValue
        Deck.STEP -> state.groove.seqTrack.intValue
        Deck.FX -> -1
        else -> state.samplerTrack.intValue
    }
    val tr = state.tracks.value.getOrNull(t)
    val loaded by state.sample.collectAsState()                     // the sampler track's sample (state, so this updates)
    val color = if (tr != null) kindColor(tr.kind) else Tb.Key
    val detail = when (deck) {
        Deck.PADS -> {
            val sm = state.sampler
            val sel = sm.selected.intValue
            val pad = sm.pads.value[sel]
            val name = loaded?.name
            if (name == null) "no sample yet · SAMPLE → FOLDER or MIC"
            else buildString {
                append(name); append("  ·  PAD ").append(sel + 1).append(' ')
                append(when (pad.mode) { com.thorbox.model.PlayMode.ONE_SHOT -> "ONE SHOT"; com.thorbox.model.PlayMode.GATE -> "NOTE ON"; com.thorbox.model.PlayMode.LOOP -> "LOOP" })
                append(if (sm.chokeAll.value) "  ·  1 AT A TIME" else "  ·  LAYERED")
                if (sm.transpose.intValue != 0) append(String.format(Locale.US, "  ·  ALL %+d st", sm.transpose.intValue))
            }
        }
        Deck.KEYS -> "${state.lockKey().label()}  ·  OCT ${String.format(Locale.US, "%+d", state.keyOctave.intValue)}${if (state.chordMode.value) "  ·  CHORDS" else ""}"
        Deck.DRUMS -> state.drums.kit.value.name
        Deck.STEP -> "step editor"
        Deck.FX -> "${Integer.bitCount(state.groove.fxActiveMask())} effect${if (Integer.bitCount(state.groove.fxActiveMask()) == 1) "" else "s"} on"
        else -> ""
    }
    Row(modifier.height(NAV_BAR).padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(Tb.Panel.copy(alpha = 0.7f))
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(4.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(tr?.name ?: "FX", color = color, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
        Text(detail, color = Tb.Muted, fontFamily = Tb.Mono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        BeatLights(state, Modifier.width(92.dp).fillMaxHeight())
    }
}

/** "3.2" (bar.beat) and four lights, the downbeat bigger; dim while stopped. Redrawn per frame, no recomposition. */
@Composable
private fun BeatLights(state: AppState, modifier: Modifier) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 32)
    val style = remember(Tb.palette) { androidx.compose.ui.text.TextStyle(color = Tb.Text, fontFamily = Tb.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    androidx.compose.foundation.Canvas(modifier) {
        state.telemetry.frame.longValue
        val tr = state.groove.transport
        val playing = tr[1] != 0.0
        val beats = (tr[0] / com.thorbox.model.PPQN).coerceAtLeast(0.0)
        val bar = (beats / 4).toInt() + 1; val beat = (beats % 4).toInt()
        val frac = (beats % 1.0).toFloat()
        val label = if (playing) "$bar.${beat + 1}" else "■"
        val lay = tm.measure(label, style)
        drawText(lay, color = if (playing) Tb.Text else Tb.Dim, topLeft = androidx.compose.ui.geometry.Offset(0f, (size.height - lay.size.height) / 2f))
        val r = 3.5f * density; val gap = 13f * density
        for (i in 0 until 4) {
            val cx = size.width - (3 - i) * gap - r - 2f
            val lit = playing && i == beat
            val c = if (i == 0) Tb.Key else Tb.Text
            val a = if (lit) 1f - 0.6f * frac else 0.22f
            drawCircle(c.copy(alpha = a), if (i == 0) r * 1.25f else r, androidx.compose.ui.geometry.Offset(cx, size.height / 2f))
        }
    }
}
