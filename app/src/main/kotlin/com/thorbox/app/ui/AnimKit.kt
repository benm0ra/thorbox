// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.ui

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

/**
 * Small animation physics shared by the three characters (the cat, BIG DAWG, the rat). Everything
 * here is allocation-free and stepped once per frame from the draw pass.
 *
 * Why springs: the characters used to map the music straight onto poses (a sine for a nod, a fixed
 * curve for an ear), so nothing carried momentum. A spring gives follow-through for free: the head
 * keeps going after the kick, the ears and jowls lag and settle, the tail swings past and back.
 */
class Spring(
    /** How fast it moves, in Hz (higher = snappier). */
    var freq: Float = 5f,
    /** 1 = no overshoot · 0.5 = one bounce · 0.2 = wobbly. */
    var damping: Float = 0.5f,
) {
    var x = 0f
    var v = 0f

    /** Advance toward [target] by [dt] seconds (sub-stepped, so a long frame can't blow it up). */
    fun step(target: Float, dt: Float): Float {
        val t = dt.coerceIn(0f, 0.05f)
        if (t <= 0f) return x
        val w = 2f * PI.toFloat() * freq
        val n = ceil(t / 0.004f).toInt().coerceAtLeast(1)
        val h = t / n
        repeat(n) {
            val a = -w * w * (x - target) - 2f * damping * w * v
            v += a * h
            x += v * h
        }
        return x
    }

    /** A hit: add [impulse] (units per second) to the velocity. */
    fun kick(impulse: Float) { v += impulse }

    fun snap(to: Float) { x = to; v = 0f }
}

/**
 * Natural blinking: random gaps (2.5 … 6 s), a quick close-open (~140 ms), and now and then a double
 * blink. [closed] is 0 open … 1 shut.
 */
class Blinker(private var seed: Int = 7) {
    private var next = 1.5f
    private var double = false

    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    fun closed(now: Float): Float {
        val u = (now - next) / 0.14f
        if (u > 1f) {
            if (double) { double = false; next = now + 0.12f }
            else { next = now + 2.5f + rnd() * 3.5f; double = rnd() < 0.2f }
            return 0f
        }
        return if (u < 0f) 0f else sin(u * PI.toFloat())
    }

    /** Force a blink now (a startle). */
    fun now(now: Float) { if (now > next + 0.14f || now < next) next = now }
}

/** 0 → 1 with a soft start and end. */
fun smooth01(u: Float): Float { val x = u.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

/** 0 → 1 that shoots a little past and settles (for pops and landings). */
fun easeOutBack(u: Float, s: Float = 1.7f): Float { val x = u.coerceIn(0f, 1f) - 1f; return 1f + (s + 1f) * x * x * x + s * x * x }

/** 1 at t = 0, decaying with [rate] per second; 0 before. */
fun decay(t: Float, rate: Float): Float = if (t < 0f || t > 6f) 0f else exp(-t * rate)
