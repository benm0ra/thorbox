// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.export

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.app.groove.Channel
import com.thorbox.engine.NativeEngine
import com.thorbox.model.Clip
import com.thorbox.model.MidiWriter
import com.thorbox.model.SongStep
import com.thorbox.model.TrackKind

/**
 * EXPORT: bounce the song (or the current pattern × N, or a stretch of the song) to WAV or FLAC, with
 * stems if you want them, faster than real time.
 *
 * The engine renders offline while the live output plays silence (NativeEngine.exportBegin). We drive
 * it from the frame callback, ~9 ms of rendering per vsync, so the screen keeps drawing the progress
 * bar. After every block the groove runs the same per-frame logic as live (scene changes, the next song
 * step, automation), so the file is what you'd hear playing it, sample for sample.
 *
 * Passes: one, with the mix and (optionally) dry stems captured together, or, with FX ON STEMS, the mix
 * then one solo pass per track, so each stem carries the master FX and echo / reverb tails it causes.
 * Before each pass everything is stopped and rendered silently until quiet, so passes start alike.
 *
 * Files go to Music/Clamshell Beats/Exports/<project>/ through MediaStore.
 */
class Exporter(private val app: AppState) {
    enum class What { SONG, PATTERN }
    enum class Format(val ext: String, val mime: String, val code: Int) { WAV("wav", "audio/wav", 0), FLAC("flac", "audio/flac", 1) }

    data class Options(
        val what: What = What.SONG,
        val loops: Int = 4,                 // PATTERN: how many times through
        val fromStep: Int = 0,              // SONG: first and last step (inclusive); -1 = the end
        val toStep: Int = -1,
        val format: Format = Format.WAV,
        val bits: Int = 24,
        val rate: Int = 48000,
        val stems: Boolean = false,
        val fxOnStems: Boolean = false,     // false: dry stems + the full mix with FX (sums to the dry mix)
        val tail: Boolean = true,           // let echoes and reverb ring out after the last note
        val midi: Boolean = false,
    )
    data class Result(val ok: Boolean, val title: String, val detail: String, val folder: String, val uris: List<Uri>)

    val options = mutableStateOf(Options())
    val showSheet = mutableStateOf(false)
    val active = mutableStateOf(false)
    val progress = mutableFloatStateOf(0f)
    val stage = mutableStateOf("")
    val result = mutableStateOf<Result?>(null)

    // ------------------------------------------------------------------ the job
    private class Out(val name: String, val uri: Uri, val slot: Int) { var frames = -2L }
    private class Pass(val label: String, val mixSlot: Int, val stems: IntArray, val solo: Int)
    private enum class Phase { SETTLE, PLAY, TAIL }
    private class Restore(val pattern: Int, val song: List<SongStep>, val songMode: Boolean, val songLoop: Boolean, val channels: List<Channel>)
    private inner class Job(val o: Options, val outs: List<Out>, val passes: List<Pass>, val restore: Restore,
                            val steps: List<SongStep>, val playFrames: Long, val folder: String, val base: String) {
        var pass = 0
        var phase = Phase.SETTLE
        var phaseFrames = 0L
        var quiet = 0L
        var cancelled = false
        var started = false
        val t0 = System.nanoTime()
        val tailGuess = if (o.tail) o.rate.toLong() else 0L
    }
    private var job: Job? = null

    /** Validate, open the files, park the live output. Returns an error to show, or null when it's running. */
    fun start(o: Options): String? {
        if (job != null) return "Already exporting"
        val g = app.groove
        val song = g.song.value
        val steps: List<SongStep> = when (o.what) {
            What.SONG -> {
                if (song.isEmpty()) return "The song is empty: add patterns on the SONG page first"
                val a = o.fromStep.coerceIn(0, song.size - 1)
                val b = (if (o.toStep < 0) song.size - 1 else o.toStep).coerceIn(a, song.size - 1)
                song.subList(a, b + 1).toList()
            }
            What.PATTERN -> listOf(SongStep(g.patternIdx.intValue, o.loops.coerceIn(1, 64)))
        }
        val patterns = steps.map { it.pattern }.distinct()
        if (patterns.none { g.hasNotes(it) }) return "Nothing to play: ${if (o.what == What.SONG) "those song steps are" else "this pattern is"} empty"
        val ticks = steps.sumOf { g.patternTicks(it.pattern).toLong() * it.repeats }
        val tpf = g.groove.value.bpm.coerceIn(30f, 300f) * PPQN / (60.0 * o.rate)        // ticks per frame
        val playFrames = (ticks / tpf - 0.25).toLong().coerceAtLeast(1)

        // which tracks make it into the stems: audible now and with notes somewhere in what's exported
        val chans = g.channels.value
        val anySolo = chans.any { it.solo }
        val tracks = app.tracks.value
        val used = (0 until com.thorbox.model.TRACK_COUNT).filter { t ->
            tracks.getOrNull(t)?.kind != TrackKind.EMPTY && !chans[t].mute && (!anySolo || chans[t].solo) &&
                patterns.any { p -> g.clipsOf(p).getOrNull(t)?.notes?.isNotEmpty() == true }
        }

        val project = sanitize(app.projects.currentName.value).ifEmpty { "Untitled" }
        val what = if (o.what == What.SONG) {
            if (steps.size == song.size) "song" else "song ${o.fromStep + 1}-${o.fromStep + steps.size}"
        } else "${g.patternName(steps[0].pattern)} x${steps[0].repeats}"
        val base = "$project - $what"
        val folder = "Music/Clamshell Beats/Exports/$project"

        // files: slot 0 the mix, then a slot per stem
        val outs = ArrayList<Out>()
        fun create(name: String, slot: Int): Out? {
            val uri = newFile(folder, "$name.${o.format.ext}", o.format.mime) ?: return null
            val fd = runCatching { app.appContext.contentResolver.openFileDescriptor(uri, "rw")?.detachFd() }.getOrNull()
            if (fd == null) { delete(uri); return null }
            return Out("$name.${o.format.ext}", uri, slot).also { outs += it; it.frames = -1L; pendingFds += slot to fd }
        }
        pendingFds.clear()
        if (create(base, 0) == null) { outs.forEach { delete(it.uri) }; return "Couldn't create the file in $folder" }
        val stemSlot = IntArray(com.thorbox.model.TRACK_COUNT) { -1 }
        if (o.stems) for ((i, t) in used.withIndex()) {
            val nm = "$base - %02d %s".format(t + 1, sanitize(tracks[t].name.ifBlank { "T${t + 1}" }))
            if (create(nm, 1 + i) == null) { outs.forEach { delete(it.uri) }; closePending(); return "Couldn't create the stem files" }
            stemSlot[t] = 1 + i
        }
        val none = IntArray(com.thorbox.model.TRACK_COUNT) { -1 }
        val passes = ArrayList<Pass>()
        if (o.stems && o.fxOnStems) {
            passes += Pass("MIX", 0, none, -1)
            for (t in used) passes += Pass(tracks[t].name.ifBlank { "T${t + 1}" }, stemSlot[t], none, t)
        } else passes += Pass(if (o.stems) "MIX + STEMS" else "MIX", 0, stemSlot, -1)

        val restore = Restore(g.patternIdx.intValue, song, g.songMode.value, g.songLoop.value, chans)
        if (!NativeEngine.exportBegin(o.rate)) { closePending(); outs.forEach { delete(it.uri) }; return "The audio engine is busy" }
        for ((slot, fd) in pendingFds) NativeEngine.exportOpen(slot, fd, o.format.code, o.bits)
        pendingFds.clear()
        g.exporting = true
        job = Job(o, outs, passes, restore, steps, playFrames, folder, base)
        result.value = null; progress.floatValue = 0f; stage.value = "GETTING READY"
        active.value = true; showSheet.value = false
        return null
    }
    private val pendingFds = ArrayList<Pair<Int, Int>>()
    private fun closePending() { for ((_, fd) in pendingFds) runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }; pendingFds.clear() }

    fun cancel() { job?.cancelled = true }

    /** From the frame callback while [active]: render for a few ms, then let the frame draw. */
    fun frame() {
        val j = job ?: return
        val until = System.nanoTime() + BUDGET_NS
        while (job === j && System.nanoTime() < until) step(j)
    }

    private fun step(j: Job) {
        val g = app.groove
        if (j.cancelled) { finish(j, cancelled = true); return }
        val pass = j.passes[j.pass]
        when (j.phase) {
            Phase.SETTLE -> {
                if (!j.started) {
                    j.started = true
                    g.silenceForExport()
                    g.soloOnly(pass.solo, j.restore.channels)
                    NativeEngine.exportRoute(-1, null)
                    j.phaseFrames = 0; j.quiet = 0
                    stage.value = if (j.passes.size > 1) "${pass.label} · ${j.pass + 1}/${j.passes.size}" else pass.label
                }
                val peak = NativeEngine.exportRender(SETTLE_BLOCK); g.exportFrame()
                j.phaseFrames += SETTLE_BLOCK
                j.quiet = if (peak < QUIET) j.quiet + SETTLE_BLOCK else 0
                if (j.quiet >= j.o.rate / 4 || j.phaseFrames >= 8L * j.o.rate) {
                    // go: route this pass's files, play from the top
                    NativeEngine.exportRoute(pass.mixSlot, pass.stems)
                    if (j.o.what == What.SONG) { g.song.value = j.steps; g.songLoop.value = false; g.playSong(0) }
                    else { g.playPatternFromTop() }
                    j.phase = Phase.PLAY; j.phaseFrames = 0
                }
            }
            Phase.PLAY -> {
                val left = j.playFrames - j.phaseFrames
                if (left <= 0) {
                    if (g.playing.value) g.togglePlay()            // stop exactly here: the notes release into the tail
                    j.phase = Phase.TAIL; j.phaseFrames = 0; j.quiet = 0
                    if (!j.o.tail) endPass(j)
                    return
                }
                val n = minOf(PLAY_BLOCK.toLong(), left).toInt()
                NativeEngine.exportRender(n); g.exportFrame()
                j.phaseFrames += n
                report(j, j.phaseFrames)
            }
            Phase.TAIL -> {
                val peak = NativeEngine.exportRender(SETTLE_BLOCK); g.exportFrame()
                j.phaseFrames += SETTLE_BLOCK
                j.quiet = if (peak < QUIET) j.quiet + SETTLE_BLOCK else 0
                report(j, j.playFrames + minOf(j.phaseFrames, j.tailGuess))
                if (j.quiet >= (j.o.rate * 3L) / 10 || j.phaseFrames >= 12L * j.o.rate) endPass(j)
            }
        }
    }

    private fun report(j: Job, framesInPass: Long) {
        val per = (j.playFrames + j.tailGuess).toFloat()
        progress.floatValue = ((j.pass * per + framesInPass) / (j.passes.size * per)).coerceIn(0f, 0.99f)
    }

    private fun endPass(j: Job) {
        val pass = j.passes[j.pass]
        val slots = buildList { if (pass.mixSlot >= 0) add(pass.mixSlot); pass.stems.forEach { if (it >= 0) add(it) } }
        for (s in slots) j.outs.firstOrNull { it.slot == s }?.let { it.frames = NativeEngine.exportClose(s) }
        NativeEngine.exportRoute(-1, null)
        j.pass++
        if (j.pass >= j.passes.size) { finish(j, cancelled = false); return }
        j.phase = Phase.SETTLE; j.started = false
    }

    private fun finish(j: Job, cancelled: Boolean) {
        val g = app.groove
        NativeEngine.exportEnd()                               // any file still open is just closed
        g.exporting = false
        job = null
        g.silenceForExport()
        g.restoreChannels(j.restore.channels)
        g.returnTo(j.restore.pattern, j.restore.song, j.restore.songMode, j.restore.songLoop)
        active.value = false; stage.value = ""
        val failed = j.outs.filter { it.frames < 0 }
        if (cancelled || failed.isNotEmpty()) {
            j.outs.forEach { delete(it.uri) }
            result.value = if (cancelled) Result(false, "CANCELLED", "Nothing was saved.", j.folder, emptyList())
                else Result(false, "EXPORT FAILED", "Couldn't write ${failed.first().name} (is the storage full?). Nothing was kept.", j.folder, emptyList())
            showSheet.value = true
            android.util.Log.w("Thorbox", "export: ${result.value?.title}: ${result.value?.detail}")
            return
        }
        j.outs.forEach { publish(it.uri) }
        val uris = j.outs.map { it.uri }.toMutableList()
        if (j.o.midi) midi(j)?.let { uris += it }
        val secs = j.outs.first().frames.toDouble() / j.o.rate
        val took = (System.nanoTime() - j.t0) / 1e9
        val what = "${j.o.format.name} ${j.o.bits}-bit · ${j.o.rate / 1000.0}".trimEnd('0').trimEnd('.') + " kHz"
        val stemsTxt = if (j.outs.size > 1) " + ${j.outs.size - 1} stem" + (if (j.outs.size > 2) "s" else "") else ""
        result.value = Result(true, "EXPORTED", "${j.base}: ${fmtTime(secs)}$stemsTxt, $what" +
            (if (j.o.midi && uris.size > j.outs.size) " + MIDI" else "") + ". Rendered in ${"%.1f".format(took)} s.", j.folder, uris)
        progress.floatValue = 1f
        showSheet.value = true
        android.util.Log.i("Thorbox", "export: EXPORTED ${j.outs.size} files (${j.outs.joinToString { "${it.name} ${it.frames} fr" }}) in ${"%.2f".format(took)} s for ${"%.2f".format(secs)} s of audio")
    }

    /** SHARE the files from the last export (any app that takes audio). */
    fun share() {
        val r = result.value ?: return
        if (r.uris.isEmpty()) return
        val send = if (r.uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, r.uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(r.uris))
        send.type = "audio/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Share export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { app.appContext.startActivity(chooser) }
    }

    // ------------------------------------------------------------------ MIDI of the same stretch
    private fun midi(j: Job): Uri? {
        val g = app.groove
        val tracks = app.tracks.value
        val out = ArrayList<MidiWriter.Track>()
        var synthCh = 0
        var total = 0
        for (st in j.steps) total += g.patternTicks(st.pattern) * st.repeats
        tracks.forEachIndexed { t, info ->
            if (info.kind == TrackKind.EMPTY) return@forEachIndexed
            val notes = ArrayList<MidiWriter.Note>()
            var at = 0
            for (st in j.steps) {
                val span = g.patternTicks(st.pattern)
                val c: Clip? = g.clipsOf(st.pattern).getOrNull(t)
                repeat(st.repeats) {
                    if (c != null && c.notes.isNotEmpty()) {
                        val len = c.lengthTicks.coerceAtLeast(1)
                        var o = 0
                        while (o < span) {                         // a track shorter than its pattern loops inside it
                            for (e in c.notes) if (o + e.tick < span) notes += MidiWriter.Note(at + o + e.tick, e.lengthTicks, app.midiNote(t, e.note), (e.velocity * 127f).toInt())
                            o += len
                        }
                    }
                    at += span
                }
            }
            if (notes.isEmpty()) return@forEachIndexed
            val ch = if (info.kind == TrackKind.DRUMS) 9 else (synthCh++).let { if (it >= 9) it + 1 else it }
            out += MidiWriter.Track(info.name, ch, notes)
        }
        if (out.isEmpty()) return null
        val bytes = MidiWriter.write(out, g.groove.value.bpm, 1, List(out.size) { total.coerceAtLeast(1) })
        val uri = newFile(j.folder, "${j.base}.mid", "audio/midi") ?: return null
        val ok = runCatching { app.appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }.isSuccess
        if (!ok) { delete(uri); return null }
        publish(uri)
        return uri
    }

    // ------------------------------------------------------------------ MediaStore
    private fun newFile(folder: String, name: String, mime: String): Uri? = runCatching {
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$folder/")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        app.appContext.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
    }.getOrNull()
    private fun publish(uri: Uri) { runCatching { app.appContext.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) } }
    private fun delete(uri: Uri) { runCatching { app.appContext.contentResolver.delete(uri, null, null) } }

    companion object {
        private val PPQN = com.thorbox.model.PPQN.toDouble()
        private const val BUDGET_NS = 9_000_000L
        private const val PLAY_BLOCK = 1024            // ≈21 ms: automation and song steps follow at this grain, like a live frame
        private const val SETTLE_BLOCK = 2048
        private const val QUIET = 1e-4f                // −80 dBFS
        fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60)
        fun fmtTime(s: Double): String { val t = s.toInt(); return "%d:%02d".format(t / 60, t % 60) }
    }
}
