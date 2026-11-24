// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.sampler

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A two-bar boom-bap break (90 BPM), synthesized on the spot so the sampler has something to chop
 * before you've picked a folder or recorded anything, and so tests can exercise the whole load →
 * analyse → chop → pads path without any user files. Written as a plain 48 kHz stereo WAV in the
 * app's cache, then loaded like any other sample. Deterministic: same audio every time.
 */
object DemoBreak {
    private const val RATE = 48000
    private const val BPM = 90.0

    fun write(cacheDir: File): File {
        val f = File(cacheDir, "demo-break-90bpm.wav")
        if (f.exists() && f.length() > 44) return f
        val beat = 60.0 / BPM
        val frames = (beat * 8 * RATE).toInt()                       // 2 bars of 4/4
        val l = FloatArray(frames); val r = FloatArray(frames)
        var seed = 12345
        fun noise(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 32768f - 1f }
        fun at(beats: Double) = (beats * beat * RATE).toInt()

        fun kick(start: Int, vel: Float) {
            var ph = 0.0
            for (i in 0 until (0.45 * RATE).toInt()) {
                val n = start + i; if (n >= frames) break
                val t = i / RATE.toDouble()
                val f = 48 + 110 * exp(-t * 28)                         // pitch drop: the 808-ish thump
                ph += 2 * PI * f / RATE
                val v = (sin(ph) * exp(-t * 7.5) * vel).toFloat()
                l[n] += v; r[n] += v
            }
        }
        fun snare(start: Int, vel: Float) {
            for (i in 0 until (0.28 * RATE).toInt()) {
                val n = start + i; if (n >= frames) break
                val t = i / RATE.toDouble()
                val body = sin(2 * PI * 190 * t) * exp(-t * 22) * 0.6
                val v = ((noise() * exp(-t * 14) * 0.7 + body) * vel).toFloat()
                l[n] += v * 0.95f; r[n] += v
            }
        }
        fun hat(start: Int, vel: Float, open: Boolean) {
            var prev = 0f
            val len = if (open) 0.22 else 0.05
            for (i in 0 until (len * RATE).toInt()) {
                val n = start + i; if (n >= frames) break
                val t = i / RATE.toDouble()
                val x = noise(); val hp = x - prev; prev = x                 // crude high-pass: bright and thin
                val v = (hp * exp(-t * (if (open) 14 else 70)) * 0.35 * vel).toFloat()
                l[n] += v; r[n] += v * 0.8f
            }
        }

        for (bar in 0 until 2) {
            val b = bar * 4.0
            kick(at(b + 0.0), 1f); kick(at(b + 1.75), 0.7f); kick(at(b + 2.5), 0.9f)
            if (bar == 1) kick(at(b + 3.25), 0.6f)
            snare(at(b + 1.0), 0.9f); snare(at(b + 3.0), 1f)
            for (e in 0 until 8) hat(at(b + e * 0.5 + if (e % 2 == 1) 0.06 else 0.0), if (e % 2 == 0) 0.9f else 0.6f, e == 7)
        }

        var peak = 1e-6f
        for (i in 0 until frames) peak = maxOf(peak, kotlin.math.abs(l[i]), kotlin.math.abs(r[i]))
        val g = 0.89f / peak
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            pcm.putShort((l[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            pcm.putShort((r[i] * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + frames * 4); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(RATE); h.putInt(RATE * 4); h.putShort(4); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(frames * 4)
        val tmp = File(cacheDir, "demo-break.part")
        tmp.outputStream().use { it.write(h.array()); it.write(pcm.array()) }
        tmp.renameTo(f)
        return f
    }
}
