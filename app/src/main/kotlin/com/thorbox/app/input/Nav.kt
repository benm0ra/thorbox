// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection

/**
 * NAV cursor plumbing, one handle per window (top screen, bottom screen, or both when squeezed onto
 * one). The router drives focus through the window's own FocusManager (works whichever display has
 * Android's input focus) and presses / turns whatever control is focused via the actions that
 * control registered when it gained focus. No synthetic key events, no window-focus dependency.
 */
class NavHandle(
    val move: (FocusDirection) -> Boolean,
    val wake: () -> Unit,          // give this window's Compose root focus (so a first move can land)
    val clear: () -> Unit,
) {
    /** What A does: the focused control's click. */
    var action: (() -> Unit)? = null
    /** What the right stick / A-hold does: the focused control's adjust (knob, slider, fader). */
    var adjust: ((Float) -> Unit)? = null
    /** Label of the focused control (for the info line). */
    var label: String? = null
}

val LocalNavHandle = compositionLocalOf<NavHandle?> { null }

/**
 * Drop-in for clickable / combinedClickable that the NAV cursor can press. Shows the app's focus
 * ring (LocalIndication) and registers its click as the window's A-action while focused.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.navClickable(enabled: Boolean = true, label: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val handle = LocalNavHandle.current
    val click by rememberUpdatedState(onClick)
    DisposableEffect(focused, handle, enabled) {
        val a: () -> Unit = { if (enabled) click() }
        if (focused && handle != null) { handle.action = a; handle.adjust = null; handle.label = label; label?.let { com.thorbox.app.ui.Info.show(it) } }
        onDispose { if (handle != null && handle.action === a) handle.action = null }
    }
    combinedClickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
}

/**
 * Makes a knob / slider / fader reachable by the NAV cursor: it takes focus (with the ring), the
 * right stick (or D-pad while A is held) turns it via [onDelta] (units: full range = 1.0).
 */
fun Modifier.navAdjustable(label: String, onDelta: (Float) -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val handle = LocalNavHandle.current
    val delta by rememberUpdatedState(onDelta)
    DisposableEffect(focused, handle) {
        val f: (Float) -> Unit = { delta(it) }
        if (focused && handle != null) { handle.adjust = f; handle.action = null; handle.label = label; com.thorbox.app.ui.Info.show(label) }
        onDispose { if (handle != null && handle.adjust === f) handle.adjust = null }
    }
    focusable(interactionSource = src).indication(src, LocalIndication.current)
}
