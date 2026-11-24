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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

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
    // After a roll, a tag pops over the die naming what came out (TRAP, HOOK, EUCLID 5/16 …), so
    // you can hear a style you like and know to keep rolling. It floats, so the row never jumps.
    var seen by remember { mutableIntStateOf(g.rollStamp.intValue) }
    var flash by remember { mutableStateOf("") }
    LaunchedEffect(g.rollStamp.intValue) {
        if (g.rollStamp.intValue != seen) { seen = g.rollStamp.intValue; flash = g.rollName.value; delay(1600); flash = "" }
    }
    Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Tb.Key)
        .navClickable(label = "DICE ${what.substringBefore(' ')}") { rolls++; g.diceNotes(track) }
        .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Die(Modifier.size((height.value * 0.45f).coerceIn(12f, 20f).dp).rotate(spin))
        Text(what, color = Tb.Black, fontWeight = FontWeight.Black, fontSize = fontSize.sp, letterSpacing = 1.sp, maxLines = 1)
        if (flash.isNotEmpty()) {
            val up = with(LocalDensity.current) { (height + 6.dp).roundToPx() }
            Popup(alignment = Alignment.TopStart, offset = IntOffset(0, -up), properties = PopupProperties(focusable = false)) {
                Text(flash, color = Tb.Black, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tb.Key).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
    HwButton("↶", Modifier.fillMaxHeight().width(38.dp), enabled = g.canUndo.value, minHeight = height, fontSize = fontSize + 2, hPad = 4.dp) {
        g.undo(track)
    }
}
