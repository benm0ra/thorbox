// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thorbox.app.AppState
import com.thorbox.app.ScreenRole

/** What a screen shows, by role (Session = wide top panel, Deck = near-square bottom panel). */
@Composable
fun RoleContent(role: ScreenRole, state: AppState) {
    Box(Modifier.fillMaxSize().background(Tb.Void)) {
        when (role) {
            ScreenRole.SESSION -> TopScreen(state)
            ScreenRole.DECK -> DeckScreen(state)
        }
        Scanlines()
    }
}

/** No second panel available (lid half-closed, display off, emulator): squeeze both onto one. */
@Composable
fun SingleScreenContent(role: ScreenRole, state: AppState) {
    Box(Modifier.fillMaxSize()) {
    if (role == ScreenRole.SESSION) {
        Row(Modifier.fillMaxSize().background(Tb.Bg)) {
            Box(Modifier.weight(1.6f).fillMaxHeight()) { TopScreen(state) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tb.Line))
            Box(Modifier.weight(1f).fillMaxHeight()) { DeckScreen(state) }
        }
        Scanlines()
    } else {
        Column(Modifier.fillMaxSize().background(Tb.Bg)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { TopScreen(state) }
            Box(Modifier.weight(1f).fillMaxWidth()) { DeckScreen(state) }
        }
        Scanlines()
    }
}
}
