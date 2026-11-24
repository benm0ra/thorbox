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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thorbox.app.AppState
import com.thorbox.app.input.Bindings
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * Hold SELECT: a map of the physical controls for the current view, so nobody has to memorise
 * the layout. Appears after a short hold (a quick tap is still just RECORD) and never takes touches.
 * The text comes from [Bindings], the same table the router implements.
 */
@Composable
fun BoxScope.ControllerHud(state: AppState) {
    val held = state.input.shiftHeld.value
    var show by remember { mutableStateOf(false) }
    val pinned = state.showControllerMap.value                 // SETTINGS → CONTROLS → SHOW
    LaunchedEffect(held, pinned) {
        if (pinned) show = true else if (held) { delay(com.thorbox.app.input.InputRouter.TAP_MS); show = true } else show = false
    }

    AnimatedVisibility(show, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
        val ctx = com.thorbox.app.input.playContext(state)
        Row(
            Modifier.padding(8.dp)
                .background(Tb.Bg.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
                .border(1.dp, Tb.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),       // compact: the whole map fits the 1080p top panel
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HudTitle("SELECT +", Tb.Key)
                Bindings.shift.forEach { (k, v) -> HudRow(k, v) }
                HudRow("SELECT", Bindings.SHIFT_TAP, dim = true)
            }
            if (state.input.navMode.value) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HudTitle("NAV", Tb.Key)
                Bindings.nav.forEach { (k, v) -> HudRow(k, v) }
            } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HudTitle(ctx.title, Tb.Text)
                Bindings.context(ctx).forEach { (k, v) -> HudRow(k, v) }
                HudTitle("ALWAYS", Tb.Muted)
                Bindings.always.forEach { (k, v) -> HudRow(k, v) }
            }
        }
    }
}

@Composable
private fun HudTitle(t: String, c: Color) =
    Text(t, color = c, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 2.dp, top = 4.dp))

@Composable
private fun HudRow(key: String, what: String, dim: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(108.dp)) {
            Text(key, color = if (dim) Tb.Muted else Tb.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Tb.Raised, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        // one line per binding, so the keys and what they do always line up
        Text(what, color = if (dim) Tb.Muted else Tb.Text.copy(alpha = 0.88f), fontSize = 12.sp, maxLines = 1, softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
