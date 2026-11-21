// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.input

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * NAV cursor: every tappable control on both screens is reachable with the Thor's buttons.
 *
 * It doesn't use Android focus at all. Android gives key input to one window at a time (the screen
 * you touched last), and Compose focus doesn't move in a window that doesn't have it, which is why
 * the old cursor couldn't reach the top screen. Instead, every control registers itself here with
 * its screen and on-screen rectangle, and the cursor moves by geometry: D-pad right picks the
 * nearest control to the right, and so on. Off the top edge of the bottom screen, it jumps up to
 * the top screen (and back down), the way the two panels sit on the Thor.
 *
 * All of this runs on the main thread (composition, layout and key events).
 */
class NavTarget internal constructor(val id: Int) {
    var screen = 0
    var bounds = Rect.Zero
    var enabled = true
    var label: String? = null
    var action: (() -> Unit)? = null
    var adjust: ((Float) -> Unit)? = null
}

/** A sheet / dialog: while one is open on a screen, the cursor stays inside it. */
class NavModal internal constructor(val id: Int) {
    var screen = 0
    var bounds = Rect.Zero
}

enum class NavDir { UP, DOWN, LEFT, RIGHT }

object Nav {
    private var nextId = 1
    private val targets = LinkedHashMap<Int, NavTarget>()
    private val modals = LinkedHashMap<Int, NavModal>()      // insertion order = stacking order
    /** Window size per screen (0 = top, 1 = bottom), for the on-screen check and screen hops. */
    val screenSize = arrayOf(Size.Zero, Size.Zero)

    val on = mutableStateOf(false)
    /** Id of the control under the cursor (0 = none). */
    val current = mutableIntStateOf(0)
    /** Screen the cursor is on. */
    val screen = mutableIntStateOf(0)
    private var lastCenter = Offset.Zero

    internal fun newId() = nextId++
    internal fun add(t: NavTarget) { targets[t.id] = t }
    internal fun remove(t: NavTarget) {
        targets.remove(t.id)
        if (current.intValue == t.id) {
            // The control went away (view switched, sheet closed): land on whatever is now nearest.
            current.intValue = 0
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (on.value && current.intValue == 0) land(screen.intValue, lastCenter) }
        }
    }
    internal fun addModal(m: NavModal) { modals[m.id] = m }
    internal fun removeModal(m: NavModal) {
        modals.remove(m.id)
        android.os.Handler(android.os.Looper.getMainLooper()).post { if (on.value && cur() == null) land(screen.intValue, lastCenter) }
    }

    private fun topModal(s: Int) = modals.values.lastOrNull { it.screen == s && it.bounds.width > 1f }

    private fun eligible(t: NavTarget): Boolean {
        if (!t.enabled || t.bounds.width < 2f || t.bounds.height < 2f) return false
        if (t.action == null && t.adjust == null) return false
        val sz = screenSize[t.screen]
        val c = t.bounds.center
        if (sz.width > 0f && (c.x < 0f || c.y < 0f || c.x > sz.width || c.y > sz.height)) return false
        val m = topModal(t.screen)
        return m == null || m.bounds.contains(c)
    }

    private fun candidates(s: Int) = targets.values.filter { it.screen == s && eligible(it) }
    private fun hasScreen(s: Int) = targets.values.any { it.screen == s && eligible(it) }

    fun cur(): NavTarget? = targets[current.intValue]?.takeIf { eligible(it) }

    private fun select(t: NavTarget) {
        current.intValue = t.id; screen.intValue = t.screen; lastCenter = t.bounds.center
        t.label?.let { com.thorbox.app.ui.Info.show(it) }
    }

    /** Put the cursor on screen [s], on the control nearest [near] (default: top-left). */
    fun land(s: Int, near: Offset = Offset.Zero): Boolean {
        val target = if (hasScreen(s)) s else if (hasScreen(1 - s)) 1 - s else return false
        val best = candidates(target).minByOrNull { (it.bounds.center - near).getDistanceSquared() } ?: return false
        select(best)
        return true
    }

    /** Move the cursor one control in [dir]; hops between the screens at their facing edges. */
    fun move(dir: NavDir): Boolean {
        val c = cur() ?: return land(screen.intValue, lastCenter)
        val f = c.bounds
        var best: NavTarget? = null
        var bestScore = Float.MAX_VALUE
        for (t in candidates(c.screen)) {
            if (t === c) continue
            val b = t.bounds
            // gap along the direction (must be ahead of us), and how far off-axis it sits
            val (ahead, gap, off) = when (dir) {
                NavDir.RIGHT -> Triple(b.center.x > f.center.x + 1f, max(0f, b.left - f.right), axisGap(f.top, f.bottom, b.top, b.bottom))
                NavDir.LEFT -> Triple(b.center.x < f.center.x - 1f, max(0f, f.left - b.right), axisGap(f.top, f.bottom, b.top, b.bottom))
                NavDir.DOWN -> Triple(b.center.y > f.center.y + 1f, max(0f, b.top - f.bottom), axisGap(f.left, f.right, b.left, b.right))
                NavDir.UP -> Triple(b.center.y < f.center.y - 1f, max(0f, f.top - b.bottom), axisGap(f.left, f.right, b.left, b.right))
            }
            if (!ahead) continue
            val centreOff = if (dir == NavDir.LEFT || dir == NavDir.RIGHT) abs(b.center.y - f.center.y) else abs(b.center.x - f.center.x)
            val score = gap + off * 3f + centreOff * 0.15f
            if (score < bestScore) { bestScore = score; best = t }
        }
        best?.let { select(it); return true }
        // Off the facing edges: bottom screen ↑ → top screen, top screen ↓ → bottom screen.
        val other = when {
            dir == NavDir.UP && c.screen == 1 -> 0
            dir == NavDir.DOWN && c.screen == 0 -> 1
            else -> return false
        }
        if (!hasScreen(other)) return false
        val from = screenSize[c.screen]; val to = screenSize[other]
        val xn = if (from.width > 0f) f.center.x / from.width else 0.5f
        val y = if (other == 0) to.height else 0f
        return land(other, Offset(xn * to.width, y))
    }

    private fun axisGap(a0: Float, a1: Float, b0: Float, b1: Float) = when {
        b1 < a0 -> a0 - b1
        b0 > a1 -> b0 - a1
        else -> 0f
    }

    fun press(): Boolean { val t = cur() ?: return false; t.action?.invoke() ?: return false; return true }
    fun adjust(d: Float): Boolean { val t = cur() ?: return false; t.adjust?.invoke(d) ?: return false; return true }
    fun canAdjust() = cur()?.adjust != null
}

/** Which screen (0 top, 1 bottom) the controls inside belong to. Set per window by the layout. */
val LocalNavScreen = compositionLocalOf { 0 }

/**
 * Registers this control with the NAV cursor (its screen, rectangle and what A / the right stick
 * do) and draws the cursor ring on it while it's selected. Cheap: nothing recomposes when the
 * cursor moves; only the ring redraws.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.navTarget(label: String?, enabled: Boolean = true, action: (() -> Unit)? = null, adjust: ((Float) -> Unit)? = null): Modifier = composed {
    val screen = LocalNavScreen.current
    val t = remember { NavTarget(Nav.newId()) }
    SideEffect { t.screen = screen; t.label = label; t.enabled = enabled; t.action = action; t.adjust = adjust }
    DisposableEffect(t) { Nav.add(t); onDispose { Nav.remove(t) } }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(t) {
        snapshotFlow { Nav.on.value && Nav.current.intValue == t.id }.collect { if (it) runCatching { bring.bringIntoView() } }
    }
    this.bringIntoViewRequester(bring)
        .onGloballyPositioned { t.bounds = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            if (Nav.on.value && Nav.current.intValue == t.id) {
                // Drawn inside the control's own bounds, so a clip on the control can't hide it.
                val w = 3.dp.toPx()
                val r = CornerRadius(8.dp.toPx())
                drawRoundRect(com.thorbox.app.ui.Tb.Black, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w), r, style = Stroke(w * 2.4f))
                drawRoundRect(com.thorbox.app.ui.Tb.Key, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w), r, style = Stroke(w))
            }
        }
}

/** Marks a sheet / dialog: while it's open, the NAV cursor stays inside it on that screen. */
fun Modifier.navModal(): Modifier = composed {
    val screen = LocalNavScreen.current
    val m = remember { NavModal(Nav.newId()) }
    SideEffect { m.screen = screen }
    DisposableEffect(m) { Nav.addModal(m); onDispose { Nav.removeModal(m) } }
    onGloballyPositioned { m.bounds = it.boundsInWindow() }
}

/** Drop-in for clickable / combinedClickable that the NAV cursor can reach and press with A. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.navClickable(enabled: Boolean = true, label: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    navTarget(label, enabled, action = onClick)
        .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

/** Makes a knob / slider / fader reachable by the NAV cursor: the right stick (or A + D-pad) turns it. */
fun Modifier.navAdjustable(label: String, onDelta: (Float) -> Unit): Modifier = navTarget(label, adjust = onDelta)
