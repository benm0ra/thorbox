// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Flat, finger-sized button. Fires on tap, with no long-press delays or ripples to wait for. */
@Composable
fun HwButton(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Tb.Pads[0],
    minHeight: Dp = 44.dp,
    fontSize: Int = 14,
    hPad: Dp = 12.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = minHeight)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent else Tb.Raised)
            .clickable(enabled = enabled) { Info.show(text); onClick() }
            .padding(horizontal = hPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when { !enabled -> Tb.Dim; active -> Tb.Black; else -> Tb.Text },
            fontWeight = FontWeight.Black, fontSize = fontSize.sp, maxLines = 1,
        )
    }
}

/** Segmented control. Every option is visible and one tap away. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, accent: Color, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            HwButton(label(o), Modifier.weight(1f).fillMaxHeight(), active = o == selected, accent = accent) { onSelect(o) }
        }
    }
}

/** Thin horizontal confidence / level bar. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tb.Raised)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/** A value you set by dragging sideways (or up), TE-style, with no popups. Double-tap to reset. */
@Composable
fun DragValue(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    pxPerUnit: Float = 300f,
    onDelta: (Float) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    val delta by androidx.compose.runtime.rememberUpdatedState(onDelta)
    val reset by androidx.compose.runtime.rememberUpdatedState(onReset)
    androidx.compose.foundation.layout.Column(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Tb.Raised)
            .androidxPointer(pxPerUnit, { Info.show(label); delta(it) }, { reset?.invoke() })
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = Tb.Label, color = accent, maxLines = 1)
        Text(value, color = Tb.Text, fontFamily = Tb.Mono, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1)
    }
}

private fun Modifier.androidxPointer(pxPerUnit: Float, onDelta: (Float) -> Unit, onDoubleTap: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume(); onDelta((drag.x - drag.y) / pxPerUnit)
        }
    }).then(Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    })

/** Momentary button: active only while touched (fires on touch-down, with no tap delay). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HoldButton(text: String, held: Boolean, accent: Color, modifier: Modifier = Modifier, onHold: (Boolean) -> Unit) {
    val cb by androidx.compose.runtime.rememberUpdatedState(onHold)
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp))
            .background(if (held) accent else Tb.Raised)
            .pointerInteropFilter { ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { Info.show(text); cb(true) }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> cb(false)
                }
                true
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (held) Tb.Black else Tb.Text, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
    }
}
