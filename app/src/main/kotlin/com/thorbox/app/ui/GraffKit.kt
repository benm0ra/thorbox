// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.thorbox.app.R
import kotlin.math.cos
import kotlin.math.sin

/*
 * NIGHT LINE '79: the house style (docs/ART_STYLE.md). Late-'70s New York subway: silver and cascade
 * green, black keylines, one hard shadow, white shine. Every illustration (the rat, the cat, the dog, their
 * scenes) and the UI chrome draw through these helpers, so they can't drift apart.
 *
 * The five rules:
 *  1 KEYLINE  every shape is filled, then outlined in INK. Weight by depth: [Graff.kl] for characters and
 *             props, 0.6 × that for scenery. A scene sets kl once per frame so lines are the same thickness
 *             on screen whatever the scene's scale ([Graff.begin]).
 *  2 CEL      one hard shadow tone, light from the top-left: the shadow crescent sits bottom-right.
 *  3 SHINE    white ticks on round / glossy forms; a 4-point sparkle on chrome, gold and silver.
 *  4 DRIP     paint runs off pieces and tags.
 *  5 BLOCK    a hard 3D extrusion down-right on pieces, signs and buttons.
 * No allocation per frame: the helpers share scratch Paths (callers pass their own for custom shapes).
 */

/** The world's colours (the same on every theme: this is the art, not the UI). */
object Line79 {
    val INK = Color(0xFF16131B)
    val SILVER = Color(0xFFC9CED6); val SILVER_DK = Color(0xFF8C929C); val SILVER_LT = Color(0xFFF1F3F6)
    val CASCADE = Color(0xFF17A07D); val CASCADE_DK = Color(0xFF0D6C55); val CASCADE_LT = Color(0xFF69D9B6)
    val NIGHT = Color(0xFF1C2140); val NIGHT_DK = Color(0xFF11142A); val NIGHT_LT = Color(0xFF2D3560)
    val SODIUM = Color(0xFFF5AC45)
    val TILE = Color(0xFFE4DCC8); val TILE_DK = Color(0xFFCBC0A7)
    val BRICK = Color(0xFF7A3B2C); val BRICK_DK = Color(0xFF56291F)
    val RED = Color(0xFFD6412F); val RED_DK = Color(0xFF9E2C20)
    val YELLOW = Color(0xFFF4C631)
    val GOLD = Color(0xFFEDBB3B); val GOLD_DK = Color(0xFFB7862A)
    val DENIM = Color(0xFF40618D); val DENIM_DK = Color(0xFF2B4466); val DENIM_LT = Color(0xFF6687B4)
    val WHITE = Color(0xFFFBF9F4); val WHITE_DK = Color(0xFFC9C6CF)
    val TIMB = Color(0xFFD59A43); val TIMB_DK = Color(0xFFA5702A); val SOLE = Color(0xFF3B2A1A)
    /** Y2K shield-lens iridescence (cyan → violet → pink → amber). */
    val IRIDESCENT = listOf(Color(0xFF3FE0FF), Color(0xFF7A5CFF), Color(0xFFFF4FB8), Color(0xFFFFB23F))
}

/** The display faces: Anton (tall, condensed: signs, labels, headers) and Archivo Black (wide: blockbuster pieces). OFL, see NOTICE. */
val Anton = FontFamily(Font(R.font.anton))
val ArchivoBlack = FontFamily(Font(R.font.archivo_black))

object Graff {
    /** Keyline width for the scene being drawn, in its own units. Set per frame by [begin]. */
    @JvmField var kl = 2.4f
    /** Screen px per unit of the scene being drawn (set by [begin]). */
    @JvmField var scale = 1f
    internal val a = Path()
    internal val b = Path()
    internal val c = Path()
    internal val star = Path()
    // scratch for path intersections (cel shading and the eye are done with path ops, not per-frame clips:
    // an anti-aliased clipPath makes the GPU build a mask every draw)
    internal val opA = Path()
    internal val opB = Path()
    /** Lines ≈ [dp] dp on screen in a scene drawn at [scale] px per unit. */
    fun begin(density: Float, scale: Float, dp: Float = 1.6f) { kl = dp * density / scale.coerceAtLeast(0.01f); this.scale = scale }
}

private val ROUND = StrokeJoin.Round

// ---------------------------------------------------------------- shapes into the shared scratch paths

fun ovalPath(p: Path, l: Float, t: Float, w: Float, h: Float): Path { p.reset(); p.addOval(Rect(l, t, l + w, t + h)); return p }
fun circlePath(p: Path, cx: Float, cy: Float, r: Float): Path { p.reset(); p.addOval(Rect(cx - r, cy - r, cx + r, cy + r)); return p }
fun rrectPath(p: Path, l: Float, t: Float, w: Float, h: Float, r: Float): Path {
    p.reset(); p.addRoundRect(RoundRect(l, t, l + w, t + h, CornerRadius(r))); return p
}

// ---------------------------------------------------------------- rule 1 + 2: keyline and cel

/** Stroke [p] in INK. */
fun DrawScope.keyline(p: Path, w: Float = Graff.kl) = drawPath(p, Line79.INK, style = Stroke(w, cap = StrokeCap.Round, join = ROUND))

/** Fill, then keyline. */
fun DrawScope.ink(p: Path, c: Color, w: Float = Graff.kl) { drawPath(p, c); keyline(p, w) }

/**
 * The hard shadow: [sh] fills the shape, then [c] fills where the shape overlaps itself shifted up-left by
 * [d] (a path intersection), leaving a crescent of shadow bottom-right.
 */
fun DrawScope.cel(p: Path, c: Color, sh: Color, d: Float) {
    drawPath(p, sh)
    val shifted = Graff.opA; shifted.reset(); shifted.addPath(p, Offset(-d, -d * 0.8f))
    val lit = Graff.opB; lit.reset()
    if (lit.op(p, shifted, androidx.compose.ui.graphics.PathOperation.Intersect)) drawPath(lit, c) else drawPath(p, c)
}

/** Cel + keyline: the house fill for anything round or solid. */
fun DrawScope.inkCel(p: Path, c: Color, sh: Color, d: Float = Graff.kl * 1.6f, w: Float = Graff.kl) { cel(p, c, sh, d); keyline(p, w) }

fun DrawScope.inkCircle(cx: Float, cy: Float, r: Float, c: Color, sh: Color? = null, d: Float = Graff.kl * 1.6f, w: Float = Graff.kl) {
    val p = circlePath(Graff.a, cx, cy, r); if (sh == null) ink(p, c, w) else inkCel(p, c, sh, d, w)
}
fun DrawScope.inkOval(l: Float, t: Float, wd: Float, ht: Float, c: Color, sh: Color? = null, d: Float = Graff.kl * 1.6f, w: Float = Graff.kl) {
    val p = ovalPath(Graff.a, l, t, wd, ht); if (sh == null) ink(p, c, w) else inkCel(p, c, sh, d, w)
}
fun DrawScope.inkRRect(l: Float, t: Float, wd: Float, ht: Float, r: Float, c: Color, sh: Color? = null, d: Float = Graff.kl * 1.6f, w: Float = Graff.kl) {
    val p = rrectPath(Graff.a, l, t, wd, ht, r); if (sh == null) ink(p, c, w) else inkCel(p, c, sh, d, w)
}
fun DrawScope.inkRect(l: Float, t: Float, wd: Float, ht: Float, c: Color, w: Float = Graff.kl) {
    drawRect(c, Offset(l, t), Size(wd, ht)); drawRect(Line79.INK, Offset(l, t), Size(wd, ht), style = Stroke(w, join = ROUND))
}

/** An outlined tube (an arm, a leg, a tail, a cable): INK under, colour on top, the shadow tone on its far edge if given. */
fun DrawScope.limb(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, c: Color, sh: Color? = null) {
    drawLine(Line79.INK, Offset(x0, y0), Offset(x1, y1), w + Graff.kl * 2f, cap = StrokeCap.Round)
    drawLine(sh ?: c, Offset(x0, y0), Offset(x1, y1), w, cap = StrokeCap.Round)
    if (sh != null) translate(-w * 0.12f, -w * 0.12f) { drawLine(c, Offset(x0, y0), Offset(x1, y1), w * 0.62f, cap = StrokeCap.Round) }
}
/** [limb] along a path. */
fun DrawScope.limbPath(p: Path, w: Float, c: Color, sh: Color? = null) {
    drawPath(p, Line79.INK, style = Stroke(w + Graff.kl * 2f, cap = StrokeCap.Round, join = ROUND))
    drawPath(p, sh ?: c, style = Stroke(w, cap = StrokeCap.Round, join = ROUND))
    if (sh != null) translate(-w * 0.12f, -w * 0.12f) { drawPath(p, c, style = Stroke(w * 0.62f, cap = StrokeCap.Round, join = ROUND)) }
}

// ---------------------------------------------------------------- rule 3: shine

/** A white shine tick: [len] long at [angDeg] (default up-right, the light's side). */
fun DrawScope.shine(x: Float, y: Float, len: Float, angDeg: Float = -40f, w: Float = Graff.kl * 0.9f, alpha: Float = 1f) {
    val a = angDeg * (Math.PI.toFloat() / 180f)
    drawLine(Line79.WHITE.copy(alpha = alpha), Offset(x, y), Offset(x + cos(a) * len, y + sin(a) * len), w, cap = StrokeCap.Round)
}
fun DrawScope.shineDot(x: Float, y: Float, r: Float, alpha: Float = 1f) = drawCircle(Line79.WHITE.copy(alpha = alpha), r, Offset(x, y))

/** The 4-point star sparkle on chrome, gold and silver. */
fun DrawScope.sparkle(x: Float, y: Float, r: Float, alpha: Float = 1f, c: Color = Line79.WHITE) {
    if (alpha <= 0.01f || r <= 0f) return
    val p = Graff.star; p.reset()
    p.moveTo(x, y - r); p.quadraticTo(x, y, x + r, y); p.quadraticTo(x, y, x, y + r); p.quadraticTo(x, y, x - r, y); p.quadraticTo(x, y, x, y - r); p.close()
    drawPath(p, c, alpha = alpha.coerceIn(0f, 1f))
}

// ---------------------------------------------------------------- rule 4 + 5: drip and block

fun DrawScope.drip(x: Float, y: Float, len: Float, w: Float, c: Color) {
    drawLine(c, Offset(x, y), Offset(x, y + len), w, cap = StrokeCap.Butt)
    drawCircle(c, w * 0.62f, Offset(x, y + len))
}

/** The hard 3D block: [p] drawn again and again down-right in [c], [depth] deep. */
fun DrawScope.block(p: Path, depth: Float, c: Color, step: Float = 0.75f) {
    var i = depth
    while (i > 0f) { translate(i, i) { drawPath(p, c) }; i -= step }
}

// ---------------------------------------------------------------- the crew eye

/**
 * The eye every character shares: a keylined white, a big pupil with a catchlight, and a heavy lid in
 * [lidColor] across the top ([lid] = how far down, 0.45 = the chill look). [look] glances sideways (-1…1),
 * [blink] ≥ 0.6 closes it to a line.
 */
fun DrawScope.crewEye(cx: Float, cy: Float, w: Float, h: Float, lidColor: Color, lid: Float = 0.45f, look: Float = 0f, blink: Float = 0f) {
    val l = cx - w / 2f; val t = cy - h / 2f
    if (blink >= 0.6f) { drawLine(Line79.INK, Offset(l, cy), Offset(l + w, cy), Graff.kl, cap = StrokeCap.Round); return }
    val ball = ovalPath(Graff.b, l, t, w, h)
    drawPath(ball, Line79.WHITE)
    // the pupil and the lid are cut to the eyeball with path intersections (no clip)
    val pr = minOf(w, h) * 0.36f
    val px = cx + look * w * 0.18f; val py = cy + h * 0.08f
    val cut = Graff.opB
    circlePath(Graff.opA, px, py, pr)
    if (cut.op(Graff.opA, ball, androidx.compose.ui.graphics.PathOperation.Intersect)) drawPath(cut, Line79.INK)
    drawCircle(Line79.WHITE, pr * 0.32f, Offset(px + pr * 0.35f, py - pr * 0.4f))
    val ly = t + h * (lid + 0.5f * blink).coerceIn(0f, 1f)
    if (ly > t) {
        rrectPath(Graff.opA, l - 2f, t - 2f, w + 4f, ly - t + 2f, 0f)
        if (cut.op(Graff.opA, ball, androidx.compose.ui.graphics.PathOperation.Intersect)) drawPath(cut, lidColor)
        // the lid's edge: a chord across the eyeball at ly
        val ry = h / 2f; val dy = (ly - cy) / ry
        if (dy > -1f && dy < 1f) {
            val hx = w / 2f * kotlin.math.sqrt(1f - dy * dy)
            drawLine(Line79.INK, Offset(cx - hx, ly), Offset(cx + hx, ly), Graff.kl * 0.9f, cap = StrokeCap.Round)
        }
    }
    keyline(ball)
}

// ---------------------------------------------------------------- lettering

/**
 * A blockbuster piece from a measured layout: the 3D block in [block] (keylined), the face fading [fill] →
 * [fill2] down the letters, an optional two-tone [cut] across the lower part, and sparkles on top.
 * [t] must be measured with the outline-free style (any colour); [outline] is in the layout's px.
 */
fun DrawScope.blockbuster(
    t: TextLayoutResult, x: Float, y: Float,
    fill: Color, fill2: Color = fill, cut: Color? = null, block: Color, depth: Float, outline: Float,
    sparkles: Boolean = false,
) {
    val w = t.size.width.toFloat(); val h = t.size.height.toFloat()
    val stroke = Stroke(outline * 2f, join = ROUND)
    var i = depth
    while (i > 0f) { drawText(t, block, Offset(x + i, y + i)); i -= 0.75f }
    i = depth
    while (i >= 0f) { drawText(t, Line79.INK, Offset(x + i, y + i), drawStyle = stroke); i -= maxOf(0.75f, depth / 6f) }
    drawText(t, Line79.INK, Offset(x, y), drawStyle = stroke)
    drawText(t, Brush.verticalGradient(0f to fill, 0.6f to fill, 1f to fill2, startY = y, endY = y + h), Offset(x, y))
    if (cut != null) clipPath(rrectPath(Graff.c, x - 4f, y + h * 0.58f, w + 8f, h, 0f)) { drawText(t, cut, Offset(x, y)) }
    if (sparkles) {
        sparkle(x + w * 0.04f, y + h * 0.18f, h * 0.13f)
        sparkle(x + w * 0.5f, y + h * 0.8f, h * 0.09f)
        sparkle(x + w * 0.96f, y + h * 0.36f, h * 0.11f)
    }
}

// ---------------------------------------------------------------- chrome drawn on a canvas (pads, keys, step keys)

/**
 * A pad / key / step cell in the house style, for canvases that draw their own grids: a hard block
 * down-right (dropped onto when [pressed]), a solid backing so translucent [c] doesn't show the block
 * through, the fill, a keyline, and a shine tick when [active]. Sizes in px.
 */
fun DrawScope.chromeCell(c: Color, o: Offset, s: Size, r: CornerRadius, pressed: Boolean = false, active: Boolean = false) {
    val b = 3f * density; val lw = 1.5f * density
    val d = if (pressed) b * 0.8f else 0f
    if (!pressed) drawRoundRect(Tb.BlockShadow, o + Offset(b, b), s, r)
    val p = o + Offset(d, d)
    drawRoundRect(Tb.Panel, p, s, r)
    drawRoundRect(c, p, s, r)
    drawRoundRect(Tb.Keyline, p + Offset(lw / 2f, lw / 2f), Size(s.width - lw, s.height - lw), CornerRadius((r.x - lw / 2f).coerceAtLeast(0f)), style = Stroke(lw))
    if (active && s.minDimension > 20f * density) {
        val x = p.x + 8f * density; val y = p.y + 10f * density; val l = 7f * density
        drawLine(Color.White.copy(alpha = 0.9f), Offset(x, y), Offset(x + l * 0.77f, y - l * 0.64f), 2f * density, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- cached pieces

/**
 * A blockbuster is ~20 text draws (the block, the keyline, the face); painting that every frame is wasted
 * work (and stroked text under a changing transform churns the GPU's glyph cache). [cachedBlockbuster]
 * paints it once into a bitmap at the on-screen resolution and reuses it until the text, colours or scale
 * change. One cache per piece.
 */
class PieceCache {
    var key = 0L
    var img: androidx.compose.ui.graphics.ImageBitmap? = null
    var pad = 0f
    var q = 1f
}

/** [blockbuster] through [cache]. [pxPerUnit] = how many screen px one unit of the current transform is (for a sharp bitmap). */
fun DrawScope.cachedBlockbuster(
    cache: PieceCache, t: TextLayoutResult, x: Float, y: Float, pxPerUnit: Float,
    fill: Color, fill2: Color = fill, cut: Color? = null, block: Color, depth: Float, outline: Float, sparkles: Boolean = false,
) {
    val q = (kotlin.math.round(pxPerUnit * 4f) / 4f).coerceIn(0.25f, 8f)
    var k = t.layoutInput.text.text.hashCode().toLong()
    k = k * 31 + t.size.width; k = k * 31 + t.size.height
    k = k * 31 + (q * 100f).toLong(); k = k * 31 + fill.value.toLong(); k = k * 31 + fill2.value.toLong()
    k = k * 31 + (cut?.value?.toLong() ?: 7L); k = k * 31 + block.value.toLong()
    k = k * 31 + (depth * 100f).toLong(); k = k * 31 + (outline * 100f).toLong(); k = k * 31 + (if (sparkles) 1 else 0)
    if (cache.img == null || cache.key != k) {
        val pad = outline * 2f + t.size.height * 0.15f + 2f
        val w = ((t.size.width + depth + pad * 2f) * q).toInt().coerceIn(1, 4096)
        val h = ((t.size.height + depth + pad * 2f) * q).toInt().coerceIn(1, 4096)
        val img = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), Size(w.toFloat(), h.toFloat())) {
            scale(q, q, Offset.Zero) {
                blockbuster(t, pad, pad, fill, fill2, cut, block, depth, outline, sparkles)
            }
        }
        cache.img = img; cache.key = k; cache.pad = pad; cache.q = q
    }
    val img = cache.img ?: return
    translate(x - cache.pad, y - cache.pad) {
        scale(1f / cache.q, 1f / cache.q, Offset.Zero) { drawImage(img) }
    }
}

/**
 * Any static bit of art (a tag, a sign) painted once into a bitmap and reused: [draw] paints it with its
 * top-left at (0, 0), [w] × [h] units; it lands at ([x], [y]). Repainted only when [key] or the scale changes.
 */
inline fun DrawScope.cachedArt(cache: PieceCache, key: Long, w: Float, h: Float, pxPerUnit: Float, x: Float, y: Float, crossinline draw: DrawScope.() -> Unit) {
    val q = (kotlin.math.round(pxPerUnit * 4f) / 4f).coerceIn(0.25f, 8f)
    val k = key * 31 + (q * 100f).toLong()
    if (cache.img == null || cache.key != k) {
        val iw = (w * q).toInt().coerceIn(1, 4096); val ih = (h * q).toInt().coerceIn(1, 4096)
        val img = androidx.compose.ui.graphics.ImageBitmap(iw, ih)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), Size(iw.toFloat(), ih.toFloat())) {
            scale(q, q, Offset.Zero) { draw() }
        }
        cache.img = img; cache.key = k; cache.pad = 0f; cache.q = q
    }
    val img = cache.img ?: return
    translate(x, y) { scale(1f / cache.q, 1f / cache.q, Offset.Zero) { drawImage(img) } }
}
