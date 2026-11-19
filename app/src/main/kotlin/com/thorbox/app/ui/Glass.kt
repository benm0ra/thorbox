// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import android.graphics.RuntimeShader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thorbox.app.AppState

/**
 * Liquid-glass look, used where it helps and nowhere else:
 *  - GLASS (translucent, refracting) for floating chrome: the rail, deck tabs, transport bars,
 *    panels and overlays;
 *  - SOLID, high-contrast fills for anything you hit or read at speed: pads, encoders, faders,
 *    step cells. Legibility and touch confidence beat effect.
 *
 * How it works: one AGSL "aurora" shader (soft, slow colour fields that swell with the music)
 * is the backdrop. Each glass surface draws the SAME shader at its own on-screen position,
 * bent toward its edges like a thick lens, lifted and frosted, with a specular rim. That makes
 * true backdrop refraction without capturing or blurring the screen: cheap at 120 Hz, and it
 * allocates nothing per frame.
 */
enum class GlassLevel { FULL, SUBTLE, OFF }

private const val AURORA_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float4 iEnergy;     // bass, mid, high, beat
uniform float2 iOffset;     // this surface's top-left in screen px
uniform float2 iPanel;      // this surface's size in px
uniform float iGlass;       // 0 = backdrop, 1 = glass surface
uniform float iRadius;      // corner radius px
uniform float iMotion;      // 0..1 (SUBTLE runs slower and calmer)
uniform float3 iBase;       // theme background (void)
uniform float3 iT0; uniform float3 iT1; uniform float3 iT2; uniform float3 iT3;   // theme aurora tints
uniform float iLight;       // 1 = light theme: tint the paper instead of glowing on black

half3 aurora(float2 px, float t, float4 e) {
    float2 p = (px / iResolution) * 2.0 - 1.0;
    p.x *= iResolution.x / iResolution.y;
    half3 col = half3(0.0);
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float2 c = float2(sin(t * (0.07 + 0.03 * fi) + fi * 1.9) * 1.25,
                          cos(t * (0.05 + 0.02 * fi) + fi * 2.7) * 0.75);
        float r = 0.55 + (i == 0 ? 0.35 * e.x : 0.15 * e.y);
        float d = length(p - c);
        half3 tint = half3(i == 0 ? iT0 : i == 1 ? iT1 : i == 2 ? iT2 : iT3);
        col += tint * half(exp(-(d * d) / (r * r)) * 0.5);
    }
    // Keep it dark enough for white text and AMOLED blacks; brighten on energy and beats.
    return col * half(0.28 + 0.22 * e.y + 0.30 * e.w * iMotion);
}

// Compose the aurora with the theme: glow on top of a dark base, or a gentle wash on paper.
half3 onBase(half3 a) {
    half3 base = half3(iBase);
    if (iLight < 0.5) return base + a;
    half k = clamp(dot(a, half3(0.33)) * 2.2, 0.0, 0.55);
    return mix(base, base * 0.55 + a * 1.4, k);
}

// Signed distance to a rounded rectangle centred at 0.
float sdRound(float2 q, float2 halfSize, float r) {
    float2 d = abs(q) - halfSize + r;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float t = iTime * mix(0.35, 1.0, iMotion);
    if (iGlass < 0.5) return half4(onBase(aurora(fragCoord, t, iEnergy)), 1.0);

    float2 q = fragCoord - iPanel * 0.5;
    float sd = sdRound(q, iPanel * 0.5, iRadius);          // ≤ 0 inside
    float rim = smoothstep(-22.0, 0.0, sd);                // 0 centre → 1 at the edge
    float2 n = normalize(q + 0.0001);
    // Lens: near the rim, sample the backdrop from further inside, so edges bend the colours.
    float2 src = iOffset + fragCoord - n * rim * rim * 26.0;
    half3 c = onBase(aurora(src, t, iEnergy));
    if (iLight < 0.5) {
        c = c * 1.45 + half3(0.045, 0.05, 0.06);            // frosted lift
        c += half3(0.10, 0.11, 0.13) * half(rim * rim);    // edge glow
    } else {
        c = mix(c, half3(1.0), 0.45);                       // frosted paper: lighter than the page
        c -= half3(0.05) * half(rim * rim);                 // a soft edge shadow instead of a glow
    }
    // Specular sheen on the top edge, like light caught in the glass
    float sheen = smoothstep(0.0, 1.0, 1.0 - fragCoord.y / max(iPanel.y, 1.0)) * 0.06;
    c += half3(sheen);
    return half4(c, 1.0);
}
"""

/** Holds the shared clock + audio energy every glass surface reads. Updated by the frame ticker. */
object GlassClock {
    @JvmField var time = 0f
    @JvmField var bass = 0f
    @JvmField var mid = 0f
    @JvmField var high = 0f
    @JvmField var beat = 0f
}

private fun RuntimeShader.common(w: Float, h: Float, level: GlassLevel) {
    val p = Tb.palette
    setFloatUniform("iBase", p.void.red, p.void.green, p.void.blue)
    setFloatUniform("iT0", p.aurora[0].red, p.aurora[0].green, p.aurora[0].blue)
    setFloatUniform("iT1", p.aurora[1].red, p.aurora[1].green, p.aurora[1].blue)
    setFloatUniform("iT2", p.aurora[2].red, p.aurora[2].green, p.aurora[2].blue)
    setFloatUniform("iT3", p.aurora[3].red, p.aurora[3].green, p.aurora[3].blue)
    setFloatUniform("iLight", if (p.light) 1f else 0f)
    setFloatUniform("iResolution", w, h)
    setFloatUniform("iTime", GlassClock.time)
    val react = if (level == GlassLevel.FULL) 1f else 0.35f
    setFloatUniform("iEnergy", GlassClock.bass * react, GlassClock.mid * react, GlassClock.high * react, GlassClock.beat * react)
    setFloatUniform("iMotion", if (level == GlassLevel.FULL) 1f else 0.3f)
}

/** Full-screen backdrop. With glass OFF it's plain AMOLED black. */
@Composable
fun AuroraBackground(state: AppState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val level = state.glass.value
    if (level == GlassLevel.OFF) { Box(modifier.fillMaxSize().background(Tb.Void), content = content); return }
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val tel = state.telemetry
    Box(
        modifier.fillMaxSize().drawBehind {
            tel.glassFrame.longValue
            shader.common(size.width, size.height, level)
            shader.setFloatUniform("iOffset", 0f, 0f)
            shader.setFloatUniform("iPanel", size.width, size.height)
            shader.setFloatUniform("iGlass", 0f)
            shader.setFloatUniform("iRadius", 0f)
            drawRect(brush)
        },
        content = content,
    )
}

/**
 * Makes a surface glass. Pass [corner] matching any clip on the same element. With glass OFF
 * it falls back to the flat [fallback] panel colour, so layouts never depend on the effect.
 */
fun Modifier.glass(state: AppState, corner: Dp = 12.dp, fallback: Color = Tb.Panel): Modifier = composed {
    val level = state.glass.value
    if (level == GlassLevel.OFF) return@composed this.background(fallback, androidx.compose.foundation.shape.RoundedCornerShape(corner))
    val shader = remember { RuntimeShader(AURORA_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val origin = remember { FloatArray(2) }
    val root = LocalView.current
    val tel = state.telemetry
    this
        .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
        .drawWithCache {
            // Built once per size (not per frame): rim gradient, stroke, radius.
            val r = corner.toPx()
            val cr = CornerRadius(r)
            val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.14f)),
                start = Offset.Zero, end = Offset(size.width, size.height))
            val stroke = Stroke(1.dp.toPx())
            onDrawBehind {
                tel.glassFrame.longValue
                shader.common(root.width.toFloat().coerceAtLeast(1f), root.height.toFloat().coerceAtLeast(1f), level)
                shader.setFloatUniform("iOffset", origin[0], origin[1])
                shader.setFloatUniform("iPanel", size.width, size.height)
                shader.setFloatUniform("iGlass", 1f)
                shader.setFloatUniform("iRadius", r)
                drawRoundRect(brush, cornerRadius = cr)
                drawRoundRect(rim, cornerRadius = cr, style = stroke)   // light caught on the bevel
            }
        }
}
