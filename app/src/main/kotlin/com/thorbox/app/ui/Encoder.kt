// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import com.thorbox.app.input.navAdjustable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.runtime.getValue

/**
 * A big, flat, colour-capped encoder. Drag up or right to increase; a 160 dp drag covers the
 * full range. Double-tap resets it. The cap colour matches the graphic it controls on the page above.
 */
@Composable
fun Encoder(
    label: String,
    display: String,
    value: Float,
    color: Color,
    active: Boolean,
    onDragStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rangePx = with(LocalDensity.current) { 160.dp.toPx() }
    val delta by rememberUpdatedState(onDelta)
    val start by rememberUpdatedState(onDragStart)
    val end by rememberUpdatedState(onDragEnd)
    val reset by rememberUpdatedState(onReset)
    val capPath = androidx.compose.runtime.remember { androidx.compose.ui.graphics.Path() }

    // NAV cursor: focus the knob, then the right stick (or A + D-pad) turns it.
    Column(modifier.navAdjustable(label, onReset = { reset() }, corner = com.thorbox.app.input.NavTarget.ROUND) { d -> start(); delta(d); end() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(
            // The dial takes whatever height the label + value leave, square and centred, so it can never
            // push them off the bottom of a short row (the 1080p top screen clipped them before).
            Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { Info.show(label); start() },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    ) { change, drag ->
                        change.consume()
                        delta((drag.x - drag.y) / rangePx)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
        ) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val track = r * 0.12f
            val arcR = r - track / 2f
            val tl = Offset(c.x - arcR, c.y - arcR)
            val sz = Size(arcR * 2, arcR * 2)
            // 270° travel from 7:30 to 4:30
            // NIGHT LINE '79: the track is keylined, the cap is a keylined disc with one hard shadow, a block and a shine
            val kl = 1.5.dp.toPx()
            drawArc(Tb.Keyline, 135f, 270f, false, tl, sz, style = Stroke(track + kl * 2f, cap = StrokeCap.Round))
            drawArc(Tb.Raised, 135f, 270f, false, tl, sz, style = Stroke(track, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * value.coerceIn(0f, 1f), false, tl, sz, style = Stroke(track, cap = StrokeCap.Round))
            // Cap
            val capR = r * (if (active) 0.66f else 0.6f)
            val bd = 3.dp.toPx()
            drawCircle(Tb.BlockShadow, capR, c + Offset(bd, bd))
            val cap = capPath.apply { reset(); addOval(androidx.compose.ui.geometry.Rect(c.x - capR, c.y - capR, c.x + capR, c.y + capR)) }
            clipPath(cap) {
                drawCircle(androidx.compose.ui.graphics.lerp(color, Color.Black, 0.28f), capR, c)
                drawCircle(color, capR, c - Offset(capR * 0.12f, capR * 0.1f))
            }
            drawCircle(Tb.Keyline, capR, c, style = Stroke(kl))
            drawLine(Color.White.copy(alpha = 0.9f), c + Offset(-capR * 0.55f, -capR * 0.12f), c + Offset(-capR * 0.42f, -capR * 0.45f), kl * 1.6f, cap = StrokeCap.Round)
            // Notch
            val a = Math.toRadians((135.0 + 270.0 * value.coerceIn(0f, 1f))).toFloat()
            val dir = Offset(cos(a), sin(a))
            drawLine(Tb.Black, c + dir * (capR * 0.25f), c + dir * (capR * 0.9f), strokeWidth = r * 0.1f, cap = StrokeCap.Round)
        }
        Text(label, style = Tb.Label, color = if (active) color else Tb.Muted, maxLines = 1)
        Text(display, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp, color = Tb.Text, maxLines = 1)
    }
}
