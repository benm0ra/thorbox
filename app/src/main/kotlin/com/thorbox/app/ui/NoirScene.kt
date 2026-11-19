// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.thorbox.engine.NativeEngine
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * HAZE's little film, in 16-bit pixel art: a woman in a rainy neon alley lights a cigarette.
 * Engage HAZE → she raises the lighter, it sparks, the flame lights her face; the ember pulses
 * with the bass and smoke curls up (X = how far it spreads, Y = how thick). Release → the flame
 * goes out and the smoke drifts away. Everything is procedural and allocation-free per frame.
 */

// 24 × 26 sprite, profile facing right. Legend:
//  h hair · H hair rim-light (neon) · s skin · e eye · l lips · w cigarette · c coat · C coat rim-light
//  Hand + lighter + flame are drawn separately so they can move.
private val SPRITE = arrayOf(
    "......hhhhhh............",
    "....hhhhhhhhhh..........",
    "...hhhhhhhhhhhh.........",
    "..hhhhhhhhhhhhhh........",
    "..hhhhhhhhhssssh........",
    ".hhhhhhhhhsssssss.......",
    ".hhhhhhhhssssssss.......",
    ".hhhhhhhhssesssss.......",
    ".hhhhhhhhsssssssss......",
    ".Hhhhhhhhssssssssss.....",
    ".Hhhhhhhhhsssssss.......",
    "..Hhhhhhhhssssllwwww....",
    "..Hhhhhhhhhssss.........",
    "...Hhhhhhhhsss..........",
    "....Hhhhhhhsss..........",
    ".....hhhhhhsss..........",
    "......cccccsss..........",
    ".....cccccccccc.........",
    "....cccccccccccccc......",
    "...Ccccccccccccccc......",
    "...Cccccccccccccc.......",
    "..Cccccccccccccccc......",
    "..Ccccccccccccccccc.....",
    "..Cccccccccccccccccc....",
    "..Ccccccccccccccccccc...",
    "..Cccccccccccccccccccc..",
)
private const val SW = 24
private const val SH = 26

// Hand holding the lighter, relative to its anchor (x, y) = lighter top. k skin, g lighter.
private val HAND = arrayOf(
    ".g.",
    ".gk",
    "kkk",
    "kk.",
    "k..",
)

private val HAIR = Color(0xFF120E18)
private val SKIN_DARK = Color(0xFF2A1E22)
private val SKIN_LIT = Color(0xFFFFB37A)
private val EYE = Color(0xFF07060A)
private val LIPS = Color(0xFFB0223A)
private val PAPER = Color(0xFFEDEDED)
private val COAT = Color(0xFF0D0B12)
private val GOLD = Color(0xFFC9A227)
private val FLAME_IN = Color(0xFFFFF2A8)
private val FLAME_OUT = Color(0xFFFF8A00)
private val EMBER = Color(0xFFFF4A1A)
private val SMOKE = Color(0xFFB8B4C8)
private val WALL = Color(0xFF16121E)
private val BRICK = Color(0xFF1F1929)
private val NIGHT = Color(0xFF07060C)

private fun mix(a: Color, b: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, 1f)
}

/**
 * @param held seconds since HAZE engaged (negative = not engaged); [released] seconds since release.
 * @param full true = whole alley scene (top-screen panel); false = just her, for the FX pad.
 */
fun DrawScope.noirScene(o: Offset, s: Size, t: Float, held: Float, released: Float, x: Float, y: Float, bass: Float, neon: Color, full: Boolean) {
    val engaged = held >= 0f
    // Scene grid: full = 64 × 40 virtual pixels; pad = just the sprite (24 × 26).
    val gw = if (full) 64 else SW; val gh = if (full) 40 else SH
    val px = min(s.width / gw, s.height / gh)
    val ox = o.x + (s.width - px * gw) / 2f; val oy = o.y + (s.height - px * gh) / 2f
    fun cell(cx: Int, cy: Int, c: Color, a: Float = 1f) {
        if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) return
        drawRect(c.copy(alpha = c.alpha * a), Offset(ox + cx * px, oy + cy * px), Size(px + 0.5f, px + 0.5f))
    }

    // ---- timeline ----
    val raise = if (engaged) min(1f, held / 0.25f) else max(0f, 1f - released / 0.35f)     // hand up
    val spark = engaged && held in 0.25f..0.36f
    val flame = if (engaged && held >= 0.33f) 1f else if (!engaged) max(0f, 1f - released / 0.08f) * (if (released < 0.08f) 1f else 0f) else 0f
    val lit = engaged && held >= 0.6f || (!engaged && released < 30f && held < 0f && lastLit(released))
    val flicker = 0.75f + 0.25f * sin(t * 37f) * sin(t * 13.3f)
    val faceLight = flame * flicker

    // ---- backdrop (full scene) ----
    val sx = if (full) 34 else 0; val sy = if (full) 14 else 0      // where the sprite sits
    if (full) {
        drawRect(NIGHT, Offset(ox, oy), Size(px * gw, px * gh))
        for (by in 0 until 30) for (bx in 0 until gw) {                              // brick wall
            val off = if ((by / 3) % 2 == 0) 0 else 3
            val mortar = by % 3 == 2 || (bx + off) % 6 == 5
            cell(bx, by, if (mortar) WALL else BRICK)
        }
        // neon sign, flickering like a cheap one
        val buzz = if (sin(t * 3.1f) > 0.97f || sin(t * 41f) > 0.995f) 0.35f else 1f
        for (nx in 6 until 26) { cell(nx, 4, neon, buzz); cell(nx, 11, neon, buzz) }
        for (ny in 4..11) { cell(6, ny, neon, buzz); cell(25, ny, neon, buzz) }
        drawHazeWord(::cell, 9, 6, neon, buzz)
        // glow on the wall around the sign
        for (gy in 2..14) for (gx in 3..28) cell(gx, gy, neon, 0.05f * buzz)
        // wet street + reflection
        for (fy in 30 until gh) for (fx in 0 until gw) cell(fx, fy, if ((fx + fy) % 7 == 0) Color(0xFF14111C) else Color(0xFF0B0911))
        for (rx in 6 until 26) cell(rx, 31 + ((rx * 7) % 3), neon, 0.35f * buzz)
        // rain: diagonal streaks
        for (k in 0 until 26) {
            val rx = ((k * 37 + (t * 40f).toInt() * (1 + k % 3)) % (gw + 10)) - 5
            val ry = ((k * 53 + (t * 70f).toInt() * (2 + k % 2)) % gh)
            cell(rx, ry, Color(0xFF8C8AA8), 0.45f); cell(rx - 1, ry - 1, Color(0xFF8C8AA8), 0.25f)
        }
    }

    // ---- her ----
    for (r in 0 until SH) {
        val row = SPRITE[r]
        for (c in 0 until SW) {
            val col = when (row[c]) {
                'h' -> HAIR
                'H' -> mix(HAIR, neon, 0.55f)
                's' -> mix(SKIN_DARK, SKIN_LIT, faceLight * (0.55f + 0.45f * (c - 8) / 11f).coerceIn(0f, 1f) + (if (lit) 0.08f + 0.12f * bass else 0f))
                'e' -> EYE
                'l' -> mix(Color(0xFF3A1018), LIPS, 0.3f + 0.7f * faceLight)
                'w' -> PAPER
                'c' -> COAT
                'C' -> mix(COAT, neon, 0.45f)
                else -> null
            } ?: continue
            cell(sx + c, sy + r, col)
        }
    }
    // ember at the cigarette tip
    val emberGlow = if (lit) 0.55f + 0.45f * bass.coerceIn(0f, 1f) else 0f
    if (emberGlow > 0f) { cell(sx + 20, sy + 11, EMBER, emberGlow); cell(sx + 21, sy + 11, EMBER, emberGlow * 0.35f) }

    // hand + lighter (rises from below the frame)
    val handX = sx + 18; val handY = sy + 13 + ((1f - raise) * 9f).toInt()
    if (raise > 0f) {
        for (r in HAND.indices) for (c in HAND[r].indices) {
            val col = when (HAND[r][c]) { 'k' -> mix(SKIN_DARK, SKIN_LIT, faceLight * 0.9f); 'g' -> GOLD; else -> null } ?: continue
            cell(handX + c, handY + r, col)
        }
        if (spark) { cell(handX + 1, handY - 1, Color.White); cell(handX + 2, handY - 2, Color.White, 0.6f); cell(handX, handY - 2, Color.White, 0.6f) }
        if (flame > 0f) {
            cell(handX + 1, handY - 1, FLAME_IN, flame)
            cell(handX + 1, handY - 2, FLAME_OUT, flame * flicker)
            if (flicker > 0.9f) cell(handX + 1, handY - 3, FLAME_OUT, flame * 0.5f)
        }
    }

    // ---- smoke: particles curling up from the ember (procedural: age = phase of each particle) ----
    val smokeAmt = when {
        engaged && held >= 0.6f -> (0.35f + 0.65f * y)
        !engaged -> (0.35f + 0.65f * y) * exp(-released * 0.6f)
        else -> 0f
    }
    if (smokeAmt > 0.02f) {
        val n = (10 + 26 * smokeAmt).toInt()
        val spread = 2f + 7f * x
        for (k in 0 until n) {
            val age = ((t * 0.22f + k.toFloat() / n) % 1f)
            val rise = age * (if (full) 22f else 11f)
            val drift = sin(age * 6.2f + k * 1.7f + t * 0.8f) * spread * age + age * 5f
            val ax = sx + 20 + drift; val ay = sy + 10 - rise
            val a = smokeAmt * (1f - age) * (0.35f + 0.25f * sin(k * 2.3f))
            val r = 1 + (age * 2.5f).toInt()
            for (dy in 0 until r) for (dx in 0 until r) cell(floor(ax).toInt() + dx, floor(ay).toInt() + dy, SMOKE, a / r)
        }
    }
}

/** Ember keeps glowing for a while after release (she's still smoking). */
private fun lastLit(released: Float) = released < 6f

/** "HAZE" in a 3×5 pixel font. */
private fun drawHazeWord(cell: (Int, Int, Color, Float) -> Unit, x0: Int, y0: Int, c: Color, a: Float) {
    val glyphs = listOf(
        arrayOf("1.1", "1.1", "111", "1.1", "1.1"),   // H
        arrayOf(".1.", "1.1", "111", "1.1", "1.1"),   // A
        arrayOf("111", "..1", ".1.", "1..", "111"),   // Z
        arrayOf("111", "1..", "11.", "1..", "111"),   // E
    )
    glyphs.forEachIndexed { gi, g -> for (r in 0 until 5) for (cc in 0 until 3) if (g[r][cc] == '1') cell(x0 + gi * 4 + cc, y0 + r, c, a) }
}

/** Top-screen panel for HAZE's film: appears while HAZE is on (and while her smoke drifts away). */
@androidx.compose.runtime.Composable
fun androidx.compose.foundation.layout.BoxScope.HazeFilm(state: com.thorbox.app.AppState) {
    val tel = state.telemetry
    val g = state.groove
    val times = androidx.compose.runtime.remember { FloatArray(3) { -1000f } }   // [0] engaged at, [1] released at, [2] was on (1/0)
    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
        .padding(end = 10.dp, bottom = 52.dp).size(width = 320.dp, height = 200.dp)) {
        tel.frame.longValue
        val now = tel.timeSec
        val on = g.fxActiveMask() and (1 shl NativeEngine.Fx.HAZE) != 0 && g.fxHeldOrLatched(NativeEngine.Fx.HAZE)
        if (on && times[2] == 0f) times[0] = now
        if (!on && times[2] == 1f) times[1] = now
        times[2] = if (on) 1f else 0f
        val since = now - times[1]
        if (!on && since > 6f) return@Canvas
        val fade = if (on) 1f else (1f - (since - 4f) / 2f).coerceIn(0f, 1f)
        val r = 14.dp.toPx()
        drawRoundRect(Tb.Black.copy(alpha = 0f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        clipPath(androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r)))
        }) {
            noirScene(Offset.Zero, size, now, if (on) now - times[0] else -1f, since, g.fxEffX(NativeEngine.Fx.HAZE), g.fxEffY(NativeEngine.Fx.HAZE),
                state.viz.bass, Tb.Pads[7], full = true)
            if (fade < 1f) drawRect(Tb.Void.copy(alpha = 1f - fade))
        }
    }
}
