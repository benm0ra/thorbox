// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.groove

import kotlin.math.log10

/**
 * The master bus (engine: MasterChain.h + a ChannelEq): EQ → GLUE → master fader → LIMIT.
 * A new project starts with only the limiter on, at −0.3 dB: transparent until the mix would clip.
 */
data class MasterBus(
    val eq: TrackEq = TrackEq(),
    val glue: Glue = Glue(),
    val limit: Limit = Limit(),
    /** The loudness the LOUDNESS readout aims at (LUFS, short-term). */
    val targetLufs: Float = -14f,
) {
    /** GLUE: a bus compressor. [releaseMs] 0 = AUTO. [scHpHz] 0 = the side-chain hears everything. */
    data class Glue(
        val on: Boolean = false, val thresholdDb: Float = -18f, val ratio: Float = 2f, val attackMs: Float = 10f,
        val releaseMs: Float = 0f, val makeupDb: Float = 0f, val mix: Float = 1f, val scHpHz: Float = 0f,
    ) {
        fun clamped() = copy(thresholdDb = thresholdDb.coerceIn(-48f, 0f), ratio = ratio.coerceIn(1f, 20f), attackMs = attackMs.coerceIn(0.1f, 100f),
            releaseMs = if (releaseMs < 20f) 0f else releaseMs.coerceAtMost(2000f), makeupDb = makeupDb.coerceIn(0f, 24f),
            mix = mix.coerceIn(0f, 1f), scHpHz = if (scHpHz < 20f) 0f else scHpHz.coerceAtMost(500f))
        /** Engine values in master::GlueParam order. */
        fun values() = floatArrayOf(if (on) 1f else 0f, thresholdDb, ratio, attackMs, releaseMs, makeupDb, mix, scHpHz)
        /** The static curve (dB in → dB out, before makeup), as drawn on the GLUE panel. Same soft knee as Ducker.h. */
        fun curveDb(inDb: Float): Float {
            val knee = 6f; val over = inDb - thresholdDb; val slope = 1f - 1f / ratio
            val red = when {
                2f * over <= -knee -> 0f
                2f * over < knee -> { val t = over + knee * 0.5f; slope * t * t / (2f * knee) }
                else -> slope * over
            }
            return inDb - red
        }
    }

    /** LIMIT: a look-ahead brickwall after the master fader. [inputDb] pushes into it. */
    data class Limit(val on: Boolean = true, val inputDb: Float = 0f, val ceilingDb: Float = -0.3f, val releaseMs: Float = 80f) {
        fun clamped() = copy(inputDb = inputDb.coerceIn(0f, 24f), ceilingDb = ceilingDb.coerceIn(-12f, 0f), releaseMs = releaseMs.coerceIn(5f, 1000f))
        fun values() = floatArrayOf(if (on) 1f else 0f, inputDb, ceilingDb, releaseMs)
    }

    fun clamped() = copy(eq = eq.clamped(), glue = glue.clamped(), limit = limit.clamped(), targetLufs = targetLufs.coerceIn(-24f, -6f))

    companion object {
        /** Starting points for the whole bus (the EQ is left as it is). */
        val PRESETS: List<Pair<String, (MasterBus) -> MasterBus>> = listOf(
            "CLEAN" to { m -> m.copy(glue = m.glue.copy(on = false), limit = Limit()) },
            "GENTLE GLUE" to { m -> m.copy(glue = Glue(on = true, thresholdDb = -16f, ratio = 2f, attackMs = 30f, releaseMs = 0f, makeupDb = 1.5f, scHpHz = 90f),
                limit = Limit(ceilingDb = -0.5f)) },
            "PUNCHY" to { m -> m.copy(glue = Glue(on = true, thresholdDb = -20f, ratio = 4f, attackMs = 10f, releaseMs = 100f, makeupDb = 3f, mix = 0.7f, scHpHz = 60f),
                limit = Limit(inputDb = 2f, ceilingDb = -0.5f)) },
            "LOUD" to { m -> m.copy(glue = Glue(on = true, thresholdDb = -18f, ratio = 2f, attackMs = 30f, releaseMs = 0f, makeupDb = 2f, scHpHz = 90f),
                limit = Limit(inputDb = 6f, ceilingDb = -0.8f, releaseMs = 60f), targetLufs = -9f) },
            "OFF" to { m -> m.copy(eq = m.eq.copy(on = false), glue = m.glue.copy(on = false), limit = m.limit.copy(on = false)) },
        )
        val TARGETS = listOf(-14f to "STREAMING", -11f to "LOUD", -9f to "CLUB")
        fun db(gain: Float) = 20f * log10(gain.coerceAtLeast(1e-9f))
    }
}

fun MasterBus.toDto() = com.thorbox.model.MasterBusDto(
    eq = eq.toDto(),
    glue = com.thorbox.model.GlueDto(glue.on, glue.thresholdDb, glue.ratio, glue.attackMs, glue.releaseMs, glue.makeupDb, glue.mix, glue.scHpHz),
    limit = com.thorbox.model.LimitDto(limit.on, limit.inputDb, limit.ceilingDb, limit.releaseMs),
    targetLufs = targetLufs,
)
/** From a saved project; anything non-finite (a hand-edited file) falls back to the defaults. */
fun com.thorbox.model.MasterBusDto.toMasterBus(): MasterBus {
    val g = glue; val l = limit
    val nums = listOf(g.thresholdDb, g.ratio, g.attackMs, g.releaseMs, g.makeupDb, g.mix, g.scHpHz, l.inputDb, l.ceilingDb, l.releaseMs, targetLufs)
    if (!nums.all { it.isFinite() }) return MasterBus(eq = eq.toTrackEq())
    return MasterBus(eq.toTrackEq(), MasterBus.Glue(g.on, g.thresholdDb, g.ratio, g.attackMs, g.releaseMs, g.makeupDb, g.mix, g.scHpHz),
        MasterBus.Limit(l.on, l.inputDb, l.ceilingDb, l.releaseMs), targetLufs).clamped()
}
