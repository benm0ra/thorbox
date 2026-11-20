// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import java.io.ByteArrayOutputStream

/**
 * Standard MIDI File (type 1) writer. Thorbox's sequencer runs at 960 PPQN, so ticks map 1:1.
 * Track 0 carries tempo + time signature; each musical track gets a name and its own channel.
 */
object MidiWriter {
    const val PPQ = 960

    data class Note(val tick: Int, val length: Int, val note: Int, val velocity: Int)
    data class Track(val name: String, val channel: Int, val notes: List<Note>)

    /** [loops]: how many times each pattern is written back to back (so a 1-bar loop becomes a usable clip). */
    fun write(tracks: List<Track>, bpm: Float, loops: Int = 1, lengths: List<Int>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v ushr 24 and 0xFF); out.write(v ushr 16 and 0xFF); out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        out.write("MThd".toByteArray()); u32(6); u16(1); u16(tracks.size + 1); u16(PPQ)

        // Conductor track: tempo + 4/4
        chunk(out) { b ->
            val us = (60_000_000.0 / bpm.coerceIn(20f, 400f)).toInt()
            vlq(b, 0); b.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03, (us ushr 16).toByte(), (us ushr 8).toByte(), us.toByte()))
            vlq(b, 0); b.write(byteArrayOf(0xFF.toByte(), 0x58, 0x04, 4, 2, 24, 8))
            vlq(b, 0); b.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        }
        tracks.forEachIndexed { ti, t ->
            val len = lengths.getOrElse(ti) { 4 * PPQ * 4 }.coerceAtLeast(1)
            chunk(out) { b ->
                val name = t.name.toByteArray(Charsets.UTF_8).take(64).toByteArray()
                vlq(b, 0); b.write(0xFF); b.write(0x03); vlq(b, name.size); b.write(name)
                // (tick, isOff, note, vel) sorted: offs before ons at the same tick, so retriggers work
                val ev = ArrayList<IntArray>()
                for (l in 0 until loops.coerceIn(1, 64)) for (n in t.notes) {
                    val on = l * len + n.tick.coerceIn(0, len - 1)
                    val off = on + n.length.coerceIn(1, len)
                    ev += intArrayOf(on, 1, n.note.coerceIn(0, 127), n.velocity.coerceIn(1, 127))
                    ev += intArrayOf(off, 0, n.note.coerceIn(0, 127), 0)
                }
                ev.sortWith(compareBy<IntArray>({ it[0] }, { it[1] }))
                var last = 0
                val ch = t.channel.coerceIn(0, 15)
                for (e in ev) {
                    vlq(b, e[0] - last); last = e[0]
                    if (e[1] == 1) { b.write(0x90 or ch); b.write(e[2]); b.write(e[3]) } else { b.write(0x80 or ch); b.write(e[2]); b.write(0) }
                }
                // End of track exactly at the loop end, so the clip length is right in a DAW.
                vlq(b, (loops.coerceIn(1, 64) * len - last).coerceAtLeast(0)); b.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
            }
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, body: (ByteArrayOutputStream) -> Unit) {
        val b = ByteArrayOutputStream(); body(b)
        val bytes = b.toByteArray()
        out.write("MTrk".toByteArray())
        out.write(bytes.size ushr 24 and 0xFF); out.write(bytes.size ushr 16 and 0xFF); out.write(bytes.size ushr 8 and 0xFF); out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun vlq(b: ByteArrayOutputStream, value: Int) {
        var v = value.coerceAtLeast(0)
        val buf = IntArray(5); var n = 0
        buf[n++] = v and 0x7F; v = v ushr 7
        while (v > 0) { buf[n++] = (v and 0x7F) or 0x80; v = v ushr 7 }
        for (i in n - 1 downTo 0) b.write(buf[i])
    }

    /** General MIDI drum note for a drum model (any General MIDI kit). */
    fun gmDrum(model: DrumModel, decayMs: Float): Int = when (model) {
        DrumModel.KICK -> 36; DrumModel.SNARE -> 38; DrumModel.CLAP -> 39
        DrumModel.HAT -> if (decayMs < 150f) 42 else 46
        DrumModel.TOM -> 45; DrumModel.RIM -> 37; DrumModel.COWBELL -> 56
        DrumModel.CYMBAL -> 49; DrumModel.ZAP -> 54; DrumModel.SHAKER -> 70
    }
}
