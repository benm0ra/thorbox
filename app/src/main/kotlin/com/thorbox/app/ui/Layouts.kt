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
fun RoleContent(role: ScreenRole, state: AppState) = NavWindow(state, intArrayOf(role.ordinal)) {
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
fun SingleScreenContent(role: ScreenRole, state: AppState) = NavWindow(state, intArrayOf(0, 1)) {
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

/**
 * Registers this window's NAV handle (its FocusManager + root view) for the screen role(s) it shows
 * and provides it to every navClickable / navAdjustable inside.
 */
@Composable
private fun NavWindow(state: AppState, roles: IntArray, content: @Composable () -> Unit) {
    val fm = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    val handle = androidx.compose.runtime.remember(fm, view) {
        com.thorbox.app.input.NavHandle(
            move = { fm.moveFocus(it) },
            wake = { view.isFocusable = true; view.isFocusableInTouchMode = true; if (!view.hasFocus()) view.requestFocus() },
            clear = { fm.clearFocus(force = true) },
        )
    }
    androidx.compose.runtime.DisposableEffect(handle, roles.contentToString()) {
        roles.forEach { state.input.registerNavHandle(it, handle) }
        onDispose { roles.forEach { state.input.unregisterNavHandle(it, handle) } }
    }
    androidx.compose.runtime.CompositionLocalProvider(com.thorbox.app.input.LocalNavHandle provides handle, content = content)
}
