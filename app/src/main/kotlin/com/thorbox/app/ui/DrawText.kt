// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText

/**
 * drawText that can't crash. Compose's drawText(measurer, text, topLeft, style) limits the text to
 * the space left between topLeft and the canvas edge, and throws when that space is negative (a
 * label placed past the edge: a slice starting at the very end, a short envelope stage...). This
 * measures without limits and lets the canvas clip instead.
 */
fun DrawScope.drawTextSafe(tm: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (topLeft.x >= size.width || topLeft.y >= size.height) return
    drawText(tm.measure(text, style), topLeft = topLeft)
}
