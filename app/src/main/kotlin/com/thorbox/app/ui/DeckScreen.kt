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
    Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = if (state.input.navMode.value) 8.dp + NAV_BAR else 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckTabs(deck, state::selectDeck, Modifier.glass(state, 12.dp).padding(4.dp))
        when (deck) {
            Deck.KEYS -> { KeysDeck(state, Modifier.weight(1f).fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
            Deck.DRUMS -> { DrumsDeck(state, Modifier.weight(1f).fillMaxWidth()); LevelsStrip(state, drums = true, Modifier.fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
            Deck.STEP -> StepDeck(state, Modifier.weight(1f).fillMaxWidth())
            Deck.FX -> FxDeck(state, Modifier.weight(1f).fillMaxWidth())
            else -> { Pad16Deck(state, Modifier.weight(1f).fillMaxWidth()); LevelsStrip(state, drums = false, Modifier.fillMaxWidth()); RepeatStrip(state, Modifier.fillMaxWidth()) }
        }
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
