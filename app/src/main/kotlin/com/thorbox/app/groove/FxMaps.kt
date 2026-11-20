// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import com.thorbox.engine.NativeEngine.Fx
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The punch-in FX parameter maps, mirrored from MasterFx.h so every pad can say exactly what it is
 * doing ("1/16 · REV ×0.7", "LP 820 Hz", "D#3"). Keep in step with the engine.
 */

private val DIVS = listOf("1/4", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T")
private val ECHO_DIVS = listOf("1/16", "1/8T", "1/8", "1/8D", "1/4", "1/4D", "1/2")
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun resonatorNote(x: Float) = 36f + 48f * x.coerceIn(0f, 1f)
fun looperSpeed(y: Float) = when { y >= 0.6f -> 1f + (y - 0.6f) / 0.4f; y > 0.4f -> 1f; else -> -(0.5f + 0.5f * y / 0.4f) }
fun looperDivIndex(x: Float) = (x * 7f).toInt().coerceIn(0, 6)
fun echoDivIndex(x: Float) = (x * 7f).toInt().coerceIn(0, 6)
fun slicerPattern(x: Float) = (x * 8f).toInt().coerceIn(0, 7)
/** SLICER patterns, bit i = step i open (same table as the engine). */
val SLICE_PATTERNS = intArrayOf(0xFFFF, 0x5555, 0x6DB6, 0x4925, 0x7777, 0x3333, 0x1111, 0xEDB7)
fun noteName(midi: Int) = "${NOTE_NAMES[Math.floorMod(midi, 12)]}${midi / 12 - 1}"

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun hz(f: Float) = if (f >= 1000f) String.format(Locale.US, "%.1fk", f / 1000f) else "${f.roundToInt()}"

/** Name + the two axes, for the idle pad. */
data class FxInfo(val name: String, val xLabel: String, val yLabel: String)

val FX_INFO: Map<Int, FxInfo> = mapOf(
    Fx.LOOPER to FxInfo("LOOPER", "LENGTH", "SPEED"),
    Fx.FILTER to FxInfo("FILTER", "LP·HP", "RES·DRIVE"),
    Fx.TAPE_STOP to FxInfo("TAPE STOP", "STOP·SPIN", "TIME"),
    Fx.LOFI to FxInfo("LO-FI", "RATE", "BITS"),
    Fx.VINYL to FxInfo("VINYL", "WOW", "DUST"),
    Fx.SCATTER to FxInfo("SCATTER", "TYPE", "DEPTH"),
    Fx.SLICER to FxInfo("SLICER", "PATTERN", "DUTY"),
    Fx.REVERB to FxInfo("REVERB", "SIZE", "TONE"),
    Fx.ECHO to FxInfo("ECHO", "TIME", "FEEDBACK"),
    Fx.ISOLATOR to FxInfo("ISOLATOR", "BAND", "CUT"),
    Fx.CASSETTE to FxInfo("CASSETTE", "AGE", "DRIVE"),
    Fx.RESONATOR to FxInfo("RESONATOR", "NOTE", "RING"),
)

/** What the effect is doing at (x, y), in a few characters each side: shown on the pad while held. */
fun fxReadout(id: Int, x: Float, y: Float): Pair<String, String> = when (id) {
    Fx.LOOPER -> DIVS[looperDivIndex(x)] to looperSpeed(y).let { s ->
        if (s < 0f) String.format(Locale.US, "REV ×%.1f", -s) else String.format(Locale.US, "×%.1f", s) }
    Fx.FILTER -> {
        val d = (x - 0.5f) * 2f; val a = abs(d)
        val f = if (a < 1f / 6f) "OPEN" else if (d > 0f) "HP ${hz(20f * 400f.pow(a))}" else "LP ${hz(20000f * (80f / 20000f).pow(a))}"
        f to (if (y > 0.5f) "DRV ${pct((y - 0.5f) * 2f)}" else "RES ${pct(y)}")
    }
    Fx.TAPE_STOP -> (if (x >= 0.5f) "SPIN" else "STOP") to String.format(Locale.US, "%.2fs", 0.08f + 1.9f * y * y)
    Fx.LOFI -> "${(48f / (1 + (x * x * 30f).toInt())).let { if (it >= 10f) it.roundToInt().toString() else String.format(Locale.US, "%.1f", it) }}k" to
        "${(16f - 12f * y).roundToInt()} BIT"
    Fx.VINYL -> "WOW ${pct(x)}" to "DUST ${pct(y)}"
    Fx.SCATTER -> (if (x < 0.5f) "1/16" else "1/8") + " T${(x * 8f).toInt().coerceIn(0, 7) + 1}" to pct(0.15f + 0.8f * y)
    Fx.SLICER -> "PTN ${slicerPattern(x) + 1}" to "DUTY ${pct(0.15f + 0.8f * y)}"
    Fx.REVERB -> "SIZE ${pct(x)}" to (if (y < 0.35f) "DARK" else if (y > 0.65f) "BRIGHT" else "WARM")
    Fx.ECHO -> ECHO_DIVS[echoDivIndex(x)] to "FB ${pct((0.25f + 0.8f * y).coerceAtMost(1.05f))}"
    Fx.ISOLATOR -> (if (x < 1f / 3f) "LOW" else if (x < 2f / 3f) "MID" else "HIGH") to (y * 1.15f).coerceAtMost(1f).let { d ->
        if (d >= 0.999f) "−∞ dB" else "${(20f * log10((1f - d).coerceAtLeast(1e-3f))).roundToInt()} dB" }
    Fx.CASSETTE -> "AGE ${pct(x)}" to "DRIVE ${pct(y)}"
    Fx.RESONATOR -> noteName(resonatorNote(x).roundToInt()) to "RING ${pct(y)}"
    else -> "" to ""
}
