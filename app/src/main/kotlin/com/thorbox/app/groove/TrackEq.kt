// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mixer strip's EQ (engine: ChannelEq.h): HP and LP filters (12 or 24 dB/oct), a low shelf,
 * a mid bell and a high shelf. Defaults are flat, so a project from before the EQ opens unchanged.
 */
data class TrackEq(
    val on: Boolean = true,
    val hpHz: Float = HP_OFF, val hp24: Boolean = false,
    val lpHz: Float = LP_OFF, val lp24: Boolean = false,
    val lowDb: Float = 0f, val lowHz: Float = 100f,
    val midDb: Float = 0f, val midHz: Float = 1000f, val midQ: Float = 0.7f,
    val highDb: Float = 0f, val highHz: Float = 8000f,
) {
    val hpOn get() = hpHz > HP_OFF * 1.01f
    val lpOn get() = lpHz < LP_OFF * 0.99f
    /** Anything other than flat (drawn on the strip; OFF still counts, it has settings to come back to). */
    val shaped get() = hpOn || lpOn || lowDb != 0f || midDb != 0f || highDb != 0f

    fun clamped() = copy(
        hpHz = hpHz.coerceIn(HP_OFF, 4000f), lpHz = lpHz.coerceIn(100f, LP_OFF),
        lowDb = lowDb.coerceIn(-MAX_DB, MAX_DB), lowHz = lowHz.coerceIn(30f, 1000f),
        midDb = midDb.coerceIn(-MAX_DB, MAX_DB), midHz = midHz.coerceIn(100f, 12000f), midQ = midQ.coerceIn(0.2f, 10f),
        highDb = highDb.coerceIn(-MAX_DB, MAX_DB), highHz = highHz.coerceIn(1000f, 18000f),
    )

    /** The engine's parameter values, in Param order. */
    fun values() = floatArrayOf(if (on) 1f else 0f, hpHz, if (hp24) 24f else 12f, lpHz, if (lp24) 24f else 12f,
        lowDb, lowHz, midDb, midHz, midQ, highDb, highHz)

    /** The curve the engine applies, in dB at [hz] (for drawing; the same RBJ designs as ChannelEq.h). */
    fun responseDb(hz: Float, sr: Float = 48000f): Float {
        if (!on) return 0f
        var db = 0.0
        if (hpOn) {
            if (hp24) { db += mag(highpass(hpHz, 0.5411961, sr), hz, sr) + mag(highpass(hpHz, 1.3065630, sr), hz, sr) }
            else db += mag(highpass(hpHz, 0.7071068, sr), hz, sr)
        }
        if (lowDb != 0f) db += mag(lowShelf(lowHz, lowDb, sr), hz, sr)
        if (midDb != 0f) db += mag(bell(midHz, midQ, midDb, sr), hz, sr)
        if (highDb != 0f) db += mag(highShelf(highHz, highDb, sr), hz, sr)
        if (lpOn) {
            if (lp24) { db += mag(lowpass(lpHz, 0.5411961, sr), hz, sr) + mag(lowpass(lpHz, 1.3065630, sr), hz, sr) }
            else db += mag(lowpass(lpHz, 0.7071068, sr), hz, sr)
        }
        return db.toFloat()
    }

    /** Engine ids (eq::Param), sent as NativeEngine.Ch.EQ_BASE + id; [values] is in this order. */
    object Param { const val ON = 0; const val HP_HZ = 1; const val HP_SLOPE = 2; const val LP_HZ = 3; const val LP_SLOPE = 4
        const val LOW_DB = 5; const val LOW_HZ = 6; const val MID_DB = 7; const val MID_HZ = 8; const val MID_Q = 9
        const val HIGH_DB = 10; const val HIGH_HZ = 11; const val COUNT = 12 }

    companion object {
        const val HP_OFF = 20f
        const val LP_OFF = 20000f
        const val MAX_DB = 18f


        // b0 b1 b2 a1 a2
        private fun highpass(f: Float, q: Double, sr: Float): DoubleArray {
            val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
            return doubleArrayOf((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
        private fun lowpass(f: Float, q: Double, sr: Float): DoubleArray {
            val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
            return doubleArrayOf((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        }
        private fun bell(f: Float, q: Float, db: Float, sr: Float): DoubleArray {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al / a
            return doubleArrayOf((1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0)
        }
        private fun lowShelf(f: Float, db: Float, sr: Float): DoubleArray {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
            val a0 = (a + 1) + (a - 1) * c + sa
            return doubleArrayOf(a * ((a + 1) - (a - 1) * c + sa) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0, a * ((a + 1) - (a - 1) * c - sa) / a0,
                -2 * ((a - 1) + (a + 1) * c) / a0, ((a + 1) + (a - 1) * c - sa) / a0)
        }
        private fun highShelf(f: Float, db: Float, sr: Float): DoubleArray {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * al
            val a0 = (a + 1) - (a - 1) * c + sa
            return doubleArrayOf(a * ((a + 1) + (a - 1) * c + sa) / a0, -2 * a * ((a - 1) + (a + 1) * c) / a0, a * ((a + 1) + (a - 1) * c - sa) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0, ((a + 1) - (a - 1) * c - sa) / a0)
        }
        /** |H(e^jw)| in dB. */
        private fun mag(k: DoubleArray, hz: Float, sr: Float): Double {
            val w = 2 * PI * hz / sr
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = k[0] + k[1] * c1 + k[2] * c2; val ni = -(k[1] * s1 + k[2] * s2)
            val dr = 1 + k[3] * c1 + k[4] * c2; val di = -(k[3] * s1 + k[4] * s2)
            val m2 = (nr * nr + ni * ni) / (dr * dr + di * di)
            return 10 * log10(m2.coerceAtLeast(1e-12))
        }
    }
}

fun TrackEq.toDto() = com.thorbox.model.EqDto(on, hpHz, hp24, lpHz, lp24, lowDb, lowHz, midDb, midHz, midQ, highDb, highHz)
/** From a saved project; anything out of range (a hand-edited file) is pulled back in. */
fun com.thorbox.model.EqDto.toTrackEq() = TrackEq(on, hpHz, hp24, lpHz, lp24, lowDb, lowHz, midDb, midHz, midQ, highDb, highHz).let {
    if (listOf(it.hpHz, it.lpHz, it.lowDb, it.lowHz, it.midDb, it.midHz, it.midQ, it.highDb, it.highHz).all { v -> v.isFinite() }) it.clamped() else TrackEq()
}
