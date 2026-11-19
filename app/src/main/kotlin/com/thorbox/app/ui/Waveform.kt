// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Overview waveform plus playhead. The path is built once per (peaks, size) in drawWithCache.
 * The playhead is read inside the draw lambda, so a 120 Hz playhead only re-draws and never
 * recomposes.
 */
@Composable
fun Waveform(peaks: FloatArray?, playhead: State<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier.drawWithCache {
            val path = Path()
            val p = peaks
            if (p != null && p.size >= 2 && size.width > 0f) {
                val buckets = p.size / 2
                val cols = size.width.toInt().coerceAtLeast(1)
                val mid = size.height / 2f
                val amp = size.height / 2f * 0.95f
                for (x in 0 until cols) {
                    val b0 = x * buckets / cols
                    val b1 = ((x + 1) * buckets / cols).coerceAtLeast(b0 + 1).coerceAtMost(buckets)
                    var mn = 0f; var mx = 0f
                    for (b in b0 until b1) { mn = minOf(mn, p[2 * b]); mx = maxOf(mx, p[2 * b + 1]) }
                    path.moveTo(x + 0.5f, mid - mx * amp)
                    path.lineTo(x + 0.5f, mid - mn * amp + 1f)
                }
            }
            onDrawBehind {
                drawLine(Tb.Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                drawPath(path, color, style = Stroke(width = 1f))
            }
        }
    ) {
        val ph = playhead.value
        if (ph in 0f..1f) {
            val x = ph * size.width
            drawLine(Tb.Text, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}
