// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import com.thorbox.app.input.drawNavRing
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The app-wide click indication (installed in ThorboxTheme): a quick flash on press, and a bold
 * ring when the NAV cursor (gamepad focus) is on the control. Every clickable gets it for free,
 * so the whole UI is drivable from the buttons without per-screen work.
 */
object FocusRingIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = RingNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class RingNode(private val src: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var focused = false
    private var pressed = 0

    override fun onAttach() {
        coroutineScope.launch {
            src.interactions.collect { i ->
                when (i) {
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed > 0) drawRect(Tb.Text.copy(alpha = 0.10f))
        if (focused) drawNavRing(8f)     // the same cursor as NAV, everywhere
    }
}
