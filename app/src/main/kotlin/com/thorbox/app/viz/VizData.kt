// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.viz

import com.thorbox.engine.NativeEngine
import kotlin.math.max
import kotlin.math.pow

/**
 * Per-frame audio features for the visualizer and the glass aurora. Everything is preallocated, and
 * update() allocates nothing. Values are smoothed the way classic media-player visualizers feel:
 * fast attack, slow fall, and peak caps that hang briefly and then drop with gravity.
 */
class VizData {
    val bands = FloatArray(BANDS)        // smoothed 0..1
    val peaks = FloatArray(BANDS)        // falling peak caps
    private val peakVel = FloatArray(BANDS)
    private val peakHold = FloatArray(BANDS)
    private val raw = FloatArray(BANDS)
    val wave = FloatArray(WAVE)
    private val energyRaw = FloatArray(3)
    var bass = 0f; var mid = 0f; var high = 0f
    /** 1 on a detected beat (kick), decaying to 0 over ~250 ms. */
    var beat = 0f
    private var bassAvg = 0f
    private var sinceBeat = 1f
    /** Recent waveforms for trail effects (ring of TRAIL × TRAIL_LEN). */
    val trail = Array(TRAIL) { FloatArray(TRAIL_LEN) }
    var trailHead = 0
    private var trailTimer = 0f

    fun update(dt: Float) {
        if (dt <= 0f) return
        NativeEngine.visual(raw, wave, energyRaw)
        val fall = 0.06f.pow(dt)                                   // release: ~1/3 s to fade
        for (i in 0 until BANDS) {
            bands[i] = if (raw[i] > bands[i]) bands[i] + (raw[i] - bands[i]) * 0.7f else max(raw[i], bands[i] * fall)
            if (bands[i] >= peaks[i]) { peaks[i] = bands[i]; peakVel[i] = 0f; peakHold[i] = 0.35f }
            else if (peakHold[i] > 0f) peakHold[i] -= dt
            else { peakVel[i] += 2.2f * dt; peaks[i] = max(bands[i], peaks[i] - peakVel[i] * dt) }
        }
        val k = 1f - 0.0015f.pow(dt)
        bass += (energyRaw[0] - bass) * k; mid += (energyRaw[1] - mid) * k; high += (energyRaw[2] - high) * k
        // Beat: bass jumps clearly above its running average (with a short refractory window).
        bassAvg += (energyRaw[0] - bassAvg) * (1f - 0.2f.pow(dt))
        sinceBeat += dt
        if (energyRaw[0] > 0.45f && energyRaw[0] > bassAvg * 1.25f + 0.04f && sinceBeat > 0.18f) { beat = 1f; sinceBeat = 0f }
        beat *= 0.0001f.pow(dt * 0.25f)
        trailTimer += dt
        if (trailTimer > 1f / 30f) {
            trailTimer = 0f
            trailHead = (trailHead + 1) % TRAIL
            val dst = trail[trailHead]
            val step = WAVE / TRAIL_LEN
            for (i in 0 until TRAIL_LEN) dst[i] = wave[i * step]
        }
    }

    companion object { const val BANDS = 48; const val WAVE = 1024; const val TRAIL = 8; const val TRAIL_LEN = 256 }
}
