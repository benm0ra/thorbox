// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app

/**
 * One undo for the whole instrument (SELECT + B), like a hardware UNDO key: it takes back the most
 * recent change wherever it was made (a pattern edit, a synth sound, a drum kit). Each editor keeps
 * its own history; this only remembers the order they happened in. Entries whose history has since
 * been cleared (e.g. switching synth tracks) are skipped.
 */
class UndoLog(private val limit: Int = 64) {
    private val order = ArrayDeque<Long>()

    fun note(kind: Int, track: Int) {
        order.addLast(kind.toLong() shl 32 or (track.toLong() and 0xffffffffL))
        while (order.size > limit) order.removeFirst()
    }

    /**
     * Undoes the newest change that [canUndo] says is still there, via [undo].
     * Returns the kind it undid, or -1 when there was nothing.
     */
    fun undoLast(canUndo: (kind: Int, track: Int) -> Boolean, undo: (kind: Int, track: Int) -> Unit): Int {
        while (order.isNotEmpty()) {
            val e = order.removeLast()
            val kind = (e ushr 32).toInt(); val track = e.toInt()
            if (canUndo(kind, track)) { undo(kind, track); return kind }
        }
        return -1
    }

    fun clear() = order.clear()

    companion object {
        const val PATTERN = 0
        const val PATCH = 1
        const val KIT = 2
        const val EQ = 3
        fun label(kind: Int) = when (kind) { PATTERN -> "pattern"; PATCH -> "synth sound"; KIT -> "drum kit"; EQ -> "EQ"; else -> "" }
    }
}
