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
    /** A: press (fires on the button going down, so it's as quick as a tap). */
    var action: (() -> Unit)? = null
    /** Turned by the right stick, or by the D-pad once grabbed with A. */
    var adjust: ((Float) -> Unit)? = null
    /** X while grabbed: back to the default (a double-tap on screen does the same). */
    var reset: (() -> Unit)? = null
    /** Called when A comes back up, for controls you hold (pads, keys, momentary buttons). */
    var release: (() -> Unit)? = null
    /** A held [Nav.LONG_MS]: the long-press action (then the short press doesn't fire). */
    var longAction: (() -> Unit)? = null
    /** An area with its own cells (pads, keys, FX tiles, steps): A enters it, then the D-pad walks the cells. */
    var grid: NavGrid? = null
    /** Ring shape: corner radius in dp, or [ROUND] for a knob. */
    var corner = 8f

    companion object { const val ROUND = -1f }
}

/**
 * The inside of a grid target. Cells are numbered row by row from the top-left; each grid maps
 * that onto its own order (MPC pads count from the bottom-left). The grid draws its own cursor
 * (the selected pad, the FX outline …) from [get].
 */
class NavGrid(
    val cols: Int,
    val rows: Int,
    val get: () -> Int,
    val set: (Int) -> Unit,
    /** A down on a cell. */
    val down: (Int) -> Unit,
    /** A up (for cells you hold: pads, keys, FX punch-ins). */
    val up: ((Int) -> Unit)? = null,
    /** X on a cell (latch an effect, …). */
    val alt: ((Int) -> Unit)? = null,
    /** What the status line says about a cell. */
    val describe: (Int) -> String = { "${it + 1}" },
    /** What A does in this grid, for the status line. */
    val verb: String = "play",
    /** Lets the grid know it's the one entered ([Nav.enteredTag]), to draw its cell cursor. */
    val tag: String = "",
    /** What X does, for the status line. */
    val altVerb: String = "latch",
    /** ▲▼ on a one-row grid (lanes): edit the cell's value instead of moving (+1 up, −1 down). */
    val vertical: ((cell: Int, dir: Int) -> Unit)? = null,
)

/** A sheet / dialog: while one is open on a screen, the cursor stays inside it, and B closes it. */
class NavModal internal constructor(val id: Int) {
    var screen = 0
    var bounds = Rect.Zero
    var onClose: (() -> Unit)? = null
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
    /** Id of the knob / slider grabbed with A (0 = none): the D-pad turns it until A or B lets go. */
    val grabbed = mutableIntStateOf(0)
    /** Id of the grid entered with A (0 = none): the D-pad walks its cells until B. */
    val entered = mutableIntStateOf(0)
    /** Bumped when the status line should re-read (cell moved, value turned). */
    val tick = mutableIntStateOf(0)

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
        if (t.action == null && t.adjust == null && t.grid == null && t.longAction == null) return false
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
        if (current.intValue != t.id) { grabbed.intValue = 0; entered.intValue = 0; cancelLong() }
        current.intValue = t.id; screen.intValue = t.screen; lastCenter = t.bounds.center
        tick.intValue++
    }

    /** The status line while NAV is on: what's under the cursor and what A does to it. */
    fun status(): Pair<String, String>? {
        val t = cur() ?: return null
        val name = t.label ?: "control"
        val g = t.grid
        return when {
            g != null && entered.intValue == t.id ->
                "$name · ${g.describe(g.get())}" to "${if (g.vertical != null) "◀▶ step · ▲▼ value" else "D-pad move"} · A ${g.verb}${if (g.alt != null) " · X ${g.altVerb}" else ""} · B out"
            g != null -> name to "A enter · then the D-pad picks"
            grabbed.intValue == t.id -> "$name ◆" to "◀▶ fine · ▲▼ coarse${if (t.reset != null) " · X reset" else ""} · A/B let go"
            t.adjust != null && t.action == null -> name to "A grab · R-stick turn"
            t.longAction != null -> name to "A press · hold for more"
            t.release != null -> name to "A hold to play"
            else -> name to "A press"
        }
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

    fun adjust(d: Float): Boolean { val t = cur() ?: return false; t.adjust?.invoke(d) ?: return false; tick.intValue++; return true }
    fun canAdjust() = cur()?.adjust != null && entered.intValue == 0

    // ---- A / B / X / D-pad, as the router hands them over ----
    private var holding: NavTarget? = null
    private var gridHeld = -1
    private var longPending: NavTarget? = null
    private var longFired = false
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val longRunnable = Runnable {
        val t = longPending ?: return@Runnable
        longFired = true; longPending = null
        t.longAction?.invoke(); tick.intValue++
    }
    private fun cancelLong() { longPending = null; handler.removeCallbacks(longRunnable) }

    /** A went down. */
    fun aDown() {
        val t = cur() ?: return
        val g = t.grid
        when {
            g != null && entered.intValue == t.id -> { val c = g.get(); gridHeld = c; g.down(c) }
            g != null -> entered.intValue = t.id
            grabbed.intValue == t.id -> grabbed.intValue = 0
            t.adjust != null && t.action == null -> grabbed.intValue = t.id
            t.longAction != null -> { longPending = t; longFired = false; handler.postDelayed(longRunnable, LONG_MS) }
            else -> { t.action?.invoke(); if (t.release != null) holding = t }
        }
        tick.intValue++
    }

    /** A came back up. */
    fun aUp() {
        val t = cur()
        if (gridHeld >= 0) { val c = gridHeld; gridHeld = -1; t?.grid?.up?.invoke(c) }
        longPending?.let { p -> cancelLong(); if (!longFired) p.action?.invoke() }
        holding?.let { it.release?.invoke(); holding = null }
        tick.intValue++
    }

    /** B: let go of a grabbed control, or step out of a grid. False when there was nothing to step out of. */
    fun back(): Boolean = when {
        grabbed.intValue != 0 -> { grabbed.intValue = 0; tick.intValue++; true }
        entered.intValue != 0 -> { releaseGrid(); entered.intValue = 0; tick.intValue++; true }
        else -> false
    }

    /** X: reset a grabbed control, or the grid's second action. False when X means nothing here. */
    fun x(): Boolean {
        val t = cur() ?: return false
        return when {
            grabbed.intValue == t.id -> { t.reset?.invoke(); tick.intValue++; true }
            entered.intValue == t.id -> { t.grid?.alt?.let { it(t.grid!!.get()); tick.intValue++; true } ?: true }
            else -> false
        }
    }

    /** D-pad: walks the grid / turns the grabbed control / moves the cursor. */
    fun dpad(dir: NavDir): Boolean {
        val t = cur()
        if (t != null && grabbed.intValue == t.id) {
            adjust(when (dir) { NavDir.RIGHT -> FINE; NavDir.LEFT -> -FINE; NavDir.UP -> COARSE; NavDir.DOWN -> -COARSE })
            return true
        }
        val g = t?.grid
        if (t != null && g != null && entered.intValue == t.id) {
            val c = g.get(); val col = c % g.cols; val row = c / g.cols
            val v = g.vertical
            if (v != null && (dir == NavDir.UP || dir == NavDir.DOWN)) { v(c, if (dir == NavDir.UP) 1 else -1); tick.intValue++; return true }
            val n = when (dir) {
                NavDir.LEFT -> if (col > 0) c - 1 else c
                NavDir.RIGHT -> if (col < g.cols - 1) c + 1 else c
                NavDir.UP -> if (row > 0) c - g.cols else c
                NavDir.DOWN -> if (row < g.rows - 1) c + g.cols else c
            }
            if (n != c) {
                if (gridHeld >= 0) { g.up?.invoke(gridHeld); gridHeld = n; g.down(n) }   // slide while held, like a finger
                g.set(n)
            }
            tick.intValue++
            return true
        }
        return move(dir)
    }

    private fun releaseGrid() {
        if (gridHeld >= 0) { val c = gridHeld; gridHeld = -1; targets[entered.intValue]?.grid?.up?.invoke(c) }
    }

    /** B with nothing grabbed: close the top sheet (on the cursor's screen first). */
    fun closeTop(): Boolean {
        val m = modals.values.lastOrNull { it.screen == screen.intValue && it.onClose != null && it.bounds.width > 1f }
            ?: modals.values.lastOrNull { it.onClose != null && it.bounds.width > 1f } ?: return false
        m.onClose?.invoke()
        return true
    }

    /** Tag of the grid the cursor is inside (for grids that draw their own cell cursor), or null. */
    fun enteredTag(): String? = if (on.value) targets[entered.intValue]?.grid?.tag else null

    /** NAV switched off: let go of everything held. */
    fun reset() { aUp(); grabbed.intValue = 0; releaseGrid(); entered.intValue = 0 }

    const val LONG_MS = 500L
    const val FINE = 0.01f
    const val COARSE = 0.08f
}

/** Which screen (0 top, 1 bottom) the controls inside belong to. Set per window by the layout. */
val LocalNavScreen = compositionLocalOf { 0 }

/**
 * Registers this control with the NAV cursor (its screen, rectangle and what A / the right stick
 * do) and draws the cursor ring on it while it's selected. Cheap: nothing recomposes when the
 * cursor moves; only the ring redraws.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.navTarget(
    label: String?,
    enabled: Boolean = true,
    action: (() -> Unit)? = null,
    adjust: ((Float) -> Unit)? = null,
    reset: (() -> Unit)? = null,
    release: (() -> Unit)? = null,
    longAction: (() -> Unit)? = null,
    grid: NavGrid? = null,
    corner: Float = 8f,
): Modifier = composed {
    val screen = LocalNavScreen.current
    val t = remember { NavTarget(Nav.newId()) }
    SideEffect {
        t.screen = screen; t.label = label; t.enabled = enabled; t.action = action; t.adjust = adjust
        t.reset = reset; t.release = release; t.longAction = longAction; t.grid = grid; t.corner = corner
    }
    DisposableEffect(t) { Nav.add(t); onDispose { Nav.remove(t) } }
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(t) {
        // Scroll it into view with some of its neighbours, so the next row of a list is on screen
        // (and reachable) too: a row that's fully scrolled away isn't laid out yet.
        snapshotFlow { Nav.on.value && Nav.current.intValue == t.id }.collect {
            if (it) runCatching {
                val h = t.bounds.height
                bring.bringIntoView(Rect(0f, -h * 0.8f, t.bounds.width, h * 1.8f))
            }
        }
    }
    this.bringIntoViewRequester(bring)
        .onGloballyPositioned { t.bounds = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            if (Nav.on.value && Nav.current.intValue == t.id)
                drawNavRing(t.corner, grabbed = Nav.grabbed.intValue == t.id, entered = Nav.entered.intValue == t.id)
        }
}

/**
 * The cursor: a hairline frame and bold corner brackets in the text colour over a dark halo, so it
 * reads on every theme, on lit buttons and on glass. Grabbed = solid frame; inside a grid = the
 * brackets stay and the grid shows its own cell. Drawn inside the control's bounds so no clip hides it.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavRing(cornerDp: Float, grabbed: Boolean = false, entered: Boolean = false) {
    val ink = com.thorbox.app.ui.Tb.Text
    val halo = com.thorbox.app.ui.Tb.Void
    val w = 2.5.dp.toPx()
    val inset = w * 1.5f
    val o = Offset(inset, inset)
    val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
    if (sz.width <= 0f || sz.height <= 0f) return
    if (cornerDp == NavTarget.ROUND) {
        val r = minOf(sz.width, sz.height) / 2f
        drawCircle(halo, r, center, style = Stroke(w * 2.6f))
        drawCircle(ink, r, center, style = Stroke(if (grabbed) w * 1.4f else w))
        if (grabbed) drawCircle(ink.copy(alpha = 0.18f), r, center)
        return
    }
    val cr = CornerRadius(cornerDp.dp.toPx())
    drawRoundRect(halo, o, sz, cr, style = Stroke(w * 2.6f))
    drawRoundRect(ink.copy(alpha = if (grabbed) 1f else 0.55f), o, sz, cr, style = Stroke(if (grabbed) w * 1.4f else w * 0.6f))
    // corner brackets
    val L = minOf(14.dp.toPx(), sz.width / 3f, sz.height / 3f)
    val x0 = o.x; val y0 = o.y; val x1 = o.x + sz.width; val y1 = o.y + sz.height
    val bw = if (entered) w * 0.9f else w * 1.3f
    fun br(ax: Float, ay: Float, dx: Float, dy: Float) {
        drawLine(ink, Offset(ax, ay), Offset(ax + dx * L, ay), bw)
        drawLine(ink, Offset(ax, ay), Offset(ax, ay + dy * L), bw)
    }
    br(x0, y0, 1f, 1f); br(x1, y0, -1f, 1f); br(x0, y1, 1f, -1f); br(x1, y1, -1f, -1f)
}

/** Marks a sheet / dialog: while it's open, the NAV cursor stays inside it on that screen, and B runs [onClose]. */
fun Modifier.navModal(onClose: (() -> Unit)? = null): Modifier = composed {
    val screen = LocalNavScreen.current
    val m = remember { NavModal(Nav.newId()) }
    SideEffect { m.screen = screen; m.onClose = onClose }
    DisposableEffect(m) { Nav.addModal(m); onDispose { Nav.removeModal(m) } }
    onGloballyPositioned { m.bounds = it.boundsInWindow() }
}

/** Drop-in for clickable / combinedClickable that the NAV cursor can reach and press with A. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.navClickable(enabled: Boolean = true, label: String? = null, corner: Float = 8f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    navTarget(label, enabled, action = onClick, longAction = onLongClick, corner = corner)
        .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

/** Makes a knob / slider / fader reachable by the NAV cursor: A grabs it, then the D-pad (or the right stick) turns it. */
fun Modifier.navAdjustable(label: String, onReset: (() -> Unit)? = null, corner: Float = 8f, onDelta: (Float) -> Unit): Modifier =
    navTarget(label, adjust = onDelta, reset = onReset, corner = corner)
