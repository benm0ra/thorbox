// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import androidx.compose.runtime.mutableStateOf
import android.os.SystemClock

/**
 * Automation, OP-XY style: with REC armed and the transport running, moving a synth knob, a mixer
 * fader/pan or punching an FX writes a lane; on playback the lane moves the control by itself.
 * Touching a control overrides its lane for as long as you keep moving it (plus a short grace).
 *
 * Lanes are keyed by target:  "t{track}:syn:{page}:{knob}" (0..1 knob position) · "t{track}:vol" (dB)
 * · "t{track}:pan" (-1..1) · "fx:{id}" (0 off, else 1 + x; y in "fx:{id}:y").
 * Points are held (step) values on a 1/32-note grid, so playback is cheap and exact.
 */
class Automation {
    data class Point(val tick: Int, val value: Float)
    /** key → points sorted by tick. Snapshot state, so the AUTO sheet redraws on change. */
    val lanes = mutableStateOf<Map<String, List<Point>>>(emptyMap())
    private val touched = HashMap<String, Long>()
    private val lastApplied = HashMap<String, Float>()

    companion object {
        const val RES = STEP_TICKS / 2            // 1/32 note
        fun synthKey(track: Int, page: Int, knob: Int) = "t$track:syn:$page:$knob"
        fun volKey(track: Int) = "t$track:vol"
        fun panKey(track: Int) = "t$track:pan"
        fun fxKey(id: Int) = "fx:$id"
        fun fxYKey(id: Int) = "fx:$id:y"
    }

    /** A live control move: write it (when recording) and suspend that lane's playback briefly. */
    fun touch(key: String, recording: Boolean, absTick: Double, loopLen: Int, value: Float) {
        touched[key] = SystemClock.uptimeMillis()
        if (!recording || loopLen <= 0) return
        val t = ((absTick % loopLen).toInt() / RES) * RES
        val cur = lanes.value[key].orEmpty()
        // Overdub: this grid slot gets the new value; neighbours are kept.
        val pts = (cur.filterNot { it.tick == t } + Point(t, value)).sortedBy { it.tick }
        lanes.value = lanes.value + (key to pts)
    }

    fun clear(key: String) { lanes.value = lanes.value - key; lastApplied.remove(key) }
    fun clearTrack(track: Int) { lanes.value = lanes.value.filterKeys { !it.startsWith("t$track:") } }
    fun clearAll() { lanes.value = emptyMap(); lastApplied.clear() }

    /** Held value at [tick] within the loop: the last point at or before it (wrapping). */
    fun valueAt(key: String, tick: Int): Float? {
        val pts = lanes.value[key] ?: return null
        if (pts.isEmpty()) return null
        var v = pts.last().value
        for (p in pts) { if (p.tick <= tick) v = p.value else break }
        return v
    }

    /**
     * Per frame while playing: call [apply] for every lane whose value changed since last applied
     * and that isn't being touched right now.
     */
    fun play(absTick: Double, loopLen: (String) -> Int, apply: (String, Float) -> Unit) {
        val now = SystemClock.uptimeMillis()
        for ((key, _) in lanes.value) {
            if ((touched[key] ?: 0L) > now - 400) continue
            val len = loopLen(key).coerceAtLeast(RES)
            val v = valueAt(key, (absTick % len).toInt()) ?: continue
            if (lastApplied[key] == v) continue
            lastApplied[key] = v
            apply(key, v)
        }
    }
    fun resetPlayback() = lastApplied.clear()

    /** Human label for the AUTO sheet. */
    fun label(key: String, trackName: (Int) -> String, knobName: (Int, Int) -> String): String {
        val parts = key.split(":")
        return when {
            parts[0] == "fx" && parts.size == 2 -> "FX ${FX_NAMES.getOrElse(parts[1].toInt()) { "?" }}"
            parts[0] == "fx" -> "FX ${FX_NAMES.getOrElse(parts[1].toInt()) { "?" }} Y"
            parts[1] == "syn" -> "${trackName(parts[0].drop(1).toInt())} · ${knobName(parts[2].toInt(), parts[3].toInt())}"
            parts[1] == "vol" -> "${trackName(parts[0].drop(1).toInt())} · VOLUME"
            else -> "${trackName(parts[0].drop(1).toInt())} · PAN"
        }
    }
}

val FX_NAMES = listOf("STUTTER", "FILTER", "TAPE STOP", "CRUSH", "VINYL", "REVERSE", "GATE")
