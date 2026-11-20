package com.thorbox.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.navClickable

/**
 * The same "roll the notes" control on every beat grid (STEPS, the synth step deck, the drum grid,
 * the synth screen): a yellow die button that says what it rolls (NOTES / BEAT / PAD n), then ↶.
 * Same colour, same order, same gamepad X everywhere, so it is never a hunt.
 */
@Composable
fun RowScope.PatternDice(state: AppState, track: Int, height: Dp = 40.dp, fontSize: Int = 12) {
    val g = state.groove
    var rolls by remember { mutableIntStateOf(0) }
    val spin by animateFloatAsState(rolls * 90f, spring(dampingRatio = 0.45f, stiffness = 300f), label = "gridDie")
    val what = g.diceLabel(track)
    Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Tb.Key)
        .navClickable(label = "DICE ${what.substringBefore(' ')}") { rolls++; g.diceNotes(track) }
        .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Die(Modifier.size((height.value * 0.45f).coerceIn(12f, 20f).dp).rotate(spin))
        Text(what, color = Tb.Black, fontWeight = FontWeight.Black, fontSize = fontSize.sp, letterSpacing = 1.sp, maxLines = 1)
    }
    HwButton("↶", Modifier.fillMaxHeight().width(38.dp), enabled = g.canUndo.value, minHeight = height, fontSize = fontSize + 2, hPad = 4.dp) {
        g.undo(track)
    }
}
