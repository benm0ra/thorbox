// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.project

import com.thorbox.app.groove.toDto
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.thorbox.app.AppState
import com.thorbox.model.AutoPointDto
import com.thorbox.model.ChannelDto
import com.thorbox.model.FeelDto
import com.thorbox.model.FocusDto
import com.thorbox.model.PadDto
import com.thorbox.model.ProjectCodec
import com.thorbox.model.ProjectFile
import com.thorbox.model.ProjectMeta
import com.thorbox.model.SamplerDto
import com.thorbox.model.ScaleDto
import com.thorbox.model.TrackKind
import com.thorbox.model.TransportDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Projects: a whole session saved to the app's private storage, one folder per project:
 *
 *   files/projects/<id>/project.json   tracks, patterns, automation, sounds, kits, mixer, sampler chops
 *   files/projects/<id>/meta.json      name, date, track count, tempo (for the browser)
 *   files/projects/<id>/samples/…      a copy of every sample the project uses
 *
 * Copying the samples in makes a project self-contained: it still opens after the original file is
 * moved, renamed or deleted, and a field recording isn't lost with it.
 *
 * There is always a current project. Work is autosaved every few seconds when something changed,
 * and when the app goes to the background, so a crash or a flat battery costs you seconds, not a
 * session. The last project reopens at launch, unless opening it is what crashed last time.
 */
class ProjectManager(private val app: AppState, private val ctx: Application, private val scope: CoroutineScope) {
    private val root = File(ctx.filesDir, "projects")
    private val prefs = ctx.getSharedPreferences("projects", android.content.Context.MODE_PRIVATE)
    private val ioLock = Mutex()

    val currentId = mutableStateOf<String?>(prefs.getString("current", null))
    val currentName = mutableStateOf(prefs.getString("currentName", null) ?: "Untitled")
    val list = mutableStateOf<List<ProjectMeta>>(emptyList())
    /** "Saved 10:42" / "Saving…" / "Not saved yet": shown in SET → PROJECTS. */
    val status = mutableStateOf("Not saved yet")

    private var lastSavedJson: String? = null
    private var autosaveJob: Job? = null

    // ================= startup / autosave =================

    /** Reopen the last project (once per launch). Skipped if opening it crashed the app last time. */
    fun restoreLastSession() {
        if (scratch) return
        val id = currentId.value ?: run { refreshList(); return }
        if (prefs.getBoolean("opening", false)) {
            prefs.edit().putBoolean("opening", false).apply()
            app.setError("The last project didn't open cleanly, so it wasn't reopened. Find it in SET → PROJECTS.")
            currentId.value = null; refreshList(); return
        }
        open(id, quiet = true)
    }

    /**
     * Scratch session (debug screenshot / demo tours): the current project is saved, then a blank
     * session runs with saving switched off, so nothing the tour does touches your projects. The
     * next launch reopens your project as usual.
     */
    fun enterScratch() {
        if (scratch) return
        saveIfChanged()
        scratch = true
        stopAutosave()
        app.applyProject(blank()) { null }
    }
    private var scratch = false

    fun startAutosave() {
        if (scratch) return
        if (autosaveJob?.isActive == true) return
        autosaveJob = scope.launch {
            while (isActive) {
                delay(AUTOSAVE_MS)
                saveIfChanged()
            }
        }
    }
    fun stopAutosave() { autosaveJob?.cancel(); autosaveJob = null }

    /** Save now if anything changed since the last save (app going to the background, autosave tick). */
    fun saveIfChanged() {
        if (scratch) return
        val snap = snapshot(currentName.value)
        val json = ProjectCodec.encode(snap.copy(savedAt = 0))
        if (json == lastSavedJson) return
        if (currentId.value == null && isBlank(snap)) { lastSavedJson = json; return }   // nothing made yet: no empty projects
        write(snap, json, auto = true)
    }

    // ================= user actions =================

    fun save() = write(snapshot(currentName.value), null, auto = false)

    /** Save the current session as a NEW project (the old one stays as it was last saved). */
    fun saveAs(name: String) {
        currentId.value = null
        currentName.value = name.trim().ifEmpty { defaultName() }
        save()
    }

    /** Start a blank session. The current one is saved first. */
    fun newProject() {
        saveIfChanged()
        app.applyProject(blank()) { null }
        currentId.value = null; currentName.value = defaultName()
        lastSavedJson = ProjectCodec.encode(snapshot(currentName.value).copy(savedAt = 0))
        prefs.edit().remove("current").putString("currentName", currentName.value).apply()
        status.value = "Not saved yet"
        app.postInfo("NEW PROJECT", "Blank session. It saves itself as soon as you make something.")
    }

    @android.annotation.SuppressLint("ApplySharedPref")   // the crash guard below must be on disk first
    fun open(id: String, quiet: Boolean = false) {
        if (id != currentId.value) saveIfChanged()
        scope.launch {
            val dir = File(root, id)
            val text = withContext(Dispatchers.IO) { runCatching { File(dir, "project.json").readText() }.getOrNull() }
            val p = text?.let(ProjectCodec::decode)
            if (p == null) { app.setError("Couldn't open that project (its file is missing or damaged)."); refreshList(); return@launch }
            // crash guard, cleared once the project is applied. commit(), not apply(): it must be on disk
            // before applyProject runs, or a crash while opening would leave no trace to skip it next launch.
            prefs.edit().putBoolean("opening", true).commit()
            runCatching { app.applyProject(p) { name -> File(File(dir, "samples"), name).takeIf { it.isFile } } }
                .onFailure { Log.e(TAG, "applying project failed", it); app.setError("That project couldn't be fully opened.") }
            prefs.edit().putBoolean("opening", false).putString("current", id).putString("currentName", p.name).apply()
            currentId.value = id; currentName.value = p.name
            lastSavedJson = ProjectCodec.encode(snapshot(p.name).copy(savedAt = 0))
            status.value = "Opened"
            if (!quiet) app.postInfo("OPENED", "${p.name}: ${p.tracks.count { it.kind != TrackKind.EMPTY }} tracks at ${p.transport.bpm} BPM.")
            refreshList()
        }
    }

    fun rename(id: String, name: String) {
        val n = name.trim().take(40).ifEmpty { return }
        if (id == currentId.value) { currentName.value = n; prefs.edit().putString("currentName", n).apply(); save(); return }
        scope.launch {
            ioLock.withLock {
                withContext(Dispatchers.IO) {
                    val f = File(File(root, id), "project.json")
                    val p = runCatching { ProjectCodec.decode(f.readText()) }.getOrNull() ?: return@withContext
                    writeAtomically(f, ProjectCodec.encode(p.copy(name = n)))
                    writeAtomically(File(File(root, id), "meta.json"), ProjectCodec.encodeMeta(meta(id, p.copy(name = n))))
                }
            }
            refreshList()
        }
    }

    fun duplicate(id: String) {
        scope.launch {
            ioLock.withLock {
                withContext(Dispatchers.IO) {
                    val src = File(root, id)
                    val p = runCatching { ProjectCodec.decode(File(src, "project.json").readText()) }.getOrNull() ?: return@withContext
                    val newId = newId(); val dst = File(root, newId)
                    src.copyRecursively(dst, overwrite = true)
                    val copy = p.copy(name = "${p.name} copy".take(40), savedAt = System.currentTimeMillis())
                    writeAtomically(File(dst, "project.json"), ProjectCodec.encode(copy))
                    writeAtomically(File(dst, "meta.json"), ProjectCodec.encodeMeta(meta(newId, copy)))
                }
            }
            refreshList()
        }
    }

    /** Deletes a project for good (folder and samples). The current project can't be deleted. */
    fun delete(id: String) {
        if (id == currentId.value) return
        scope.launch {
            ioLock.withLock { withContext(Dispatchers.IO) { File(root, id).deleteRecursively() } }
            refreshList()
        }
    }

    fun refreshList() {
        scope.launch {
            list.value = withContext(Dispatchers.IO) {
                (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
                    runCatching { ProjectCodec.decodeMeta(File(d, "meta.json").readText()) }.getOrNull()
                }.sortedByDescending { it.savedAt }
            }
        }
    }

    // ================= snapshot ⇄ disk =================

    private fun write(snap0: ProjectFile, json0: String?, auto: Boolean) {
        if (scratch) { if (!auto) app.setError("This is a scratch session (a demo tour): it isn't saved."); return }
        val id = currentId.value ?: newId().also { currentId.value = it }
        val name = currentName.value
        status.value = "Saving…"
        // Sampler sources that aren't in this project's folder yet get copied in (off the main thread).
        val sources = HashMap<Int, Uri>()
        for (t in 0 until com.thorbox.model.TRACK_COUNT) {
            if (app.trackKind(t) != TrackKind.SAMPLER) continue
            app.samplerFor(t).sourceUri?.let { sources[t] = it }
        }
        scope.launch {
            val dir = File(root, id)
            val result = ioLock.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val samples = File(dir, "samples").apply { mkdirs() }
                        val files = HashMap<Int, String>()
                        for ((t, uri) in sources) {
                            val existing = uri.path?.let(::File)?.takeIf { uri.scheme == "file" && it.parentFile?.canonicalPath == samples.canonicalPath && it.isFile }
                            val name = existing?.name ?: copyIn(uri, samples, t)
                            if (name != null) files[t] = name
                        }
                        val snap = snap0.copy(name = name, savedAt = System.currentTimeMillis(),
                            samplers = snap0.samplers.mapValues { (t, s) -> s.copy(file = files[t]) })
                        writeAtomically(File(dir, "project.json"), ProjectCodec.encode(snap))
                        writeAtomically(File(dir, "meta.json"), ProjectCodec.encodeMeta(meta(id, snap)))
                        // Drop samples this project no longer uses.
                        val used = files.values.toSet()
                        samples.listFiles()?.forEach { if (it.name !in used) it.delete() }
                        files
                    }
                }
            }
            result.onSuccess { files ->
                // Later saves see the project copies and don't copy again.
                for ((t, name) in files) app.samplerFor(t).sourceUri = Uri.fromFile(File(File(dir, "samples"), name))
                lastSavedJson = json0 ?: ProjectCodec.encode(snapshot(name).copy(savedAt = 0))
                prefs.edit().putString("current", id).putString("currentName", name).apply()
                status.value = (if (auto) "Autosaved " else "Saved ") + SimpleDateFormat("HH:mm", Locale.US).format(Date())
                if (!auto) app.postInfo("SAVED", "$name is saved. It also autosaves as you work.")
                refreshList()
            }.onFailure {
                Log.e(TAG, "save failed", it)
                status.value = "Save failed"
                if (!auto) app.setError("Couldn't save the project: ${it.message ?: "storage error"}")
            }
        }
    }

    private fun copyIn(uri: Uri, samples: File, t: Int): String? {
        val raw = (uri.lastPathSegment ?: "sample").substringAfterLast('/').substringAfterLast(':')
        val ext = raw.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "wav"
        val base = raw.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifEmpty { "sample" }
        val out = File(samples, "t${t + 1}-$base-${SystemClock.uptimeMillis() % 100000}.$ext")
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } } ?: error("can't read")
        }.isSuccess
        if (!ok) { out.delete(); return null }
        return out.name
    }

    private fun writeAtomically(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) error("rename failed") }
    }

    /** The live session as a ProjectFile (sampler files are filled in by write()). Main thread. */
    fun snapshot(name: String): ProjectFile {
        val g = app.groove
        val gv = g.groove.value; val sc = g.scale.value
        val tracks = app.tracks.value
        val samplers = HashMap<Int, SamplerDto>()
        val synths = HashMap<Int, com.thorbox.model.SynthPatch>()
        val kits = HashMap<Int, com.thorbox.model.DrumKit>()
        tracks.forEachIndexed { t, info ->
            when (info.kind) {
                TrackKind.SYNTH -> synths[t] = app.synthPatchFor(t)
                TrackKind.DRUMS -> kits[t] = app.drumsFor(t).kit.value
                TrackKind.SAMPLER -> {
                    val sm = app.samplerFor(t)
                    if (sm.originalId == 0 && sm.sourceUri == null) return@forEachIndexed
                    samplers[t] = SamplerDto(
                        file = null, sampleName = sm.info?.name,
                        regions = sm.regions.value.flatMap { listOf(it.start, it.end) },
                        pads = sm.pads.value.map {
                            PadDto(it.pitch, it.fine, it.mode.name, it.gainDb, it.warp, it.pan, it.attackMs, it.decayMs, it.releaseMs,
                                it.cutoff, it.reso, it.reverse, it.muteGroup, it.muteA, it.muteB, it.poly, it.velSens)
                        },
                        chopMode = sm.chopMode.value.name, sensitivity = sm.sensitivity.floatValue, equalCount = sm.equalCount.intValue,
                        keyMatch = sm.keyMatch.value, bpmSync = sm.bpmSync, stretch = sm.stretch.value.name, fitBars = sm.fitBars.intValue,
                        chokeAll = sm.chokeAll.value, transpose = sm.transpose.intValue)
                }
                TrackKind.EMPTY -> Unit
            }
        }
        return ProjectFile(
            name = name,
            transport = TransportDto(gv.bpm, gv.swing, gv.swing16, gv.quantize, gv.repeatRate.name, gv.metronome),
            scale = ScaleDto(sc.root.name, sc.mode.name, sc.followSample, app.keyLock.value, app.lockAll.value),
            masterDb = g.masterDb.floatValue,
            tracks = tracks,
            clips = g.clips.value,
            channels = g.channels.value.map { ChannelDto(it.name, it.volumeDb, it.pan, it.mute, it.solo, it.scSource, it.scAmount, it.eq.toDto()) },
            feels = g.feels.value.map { FeelDto(it.template.name, it.amount) },
            automation = g.auto.lanes.value.mapValues { (_, pts) -> pts.map { AutoPointDto(it.tick, it.value) } },
            synths = synths, kits = kits, samplers = samplers,
            focus = FocusDto(app.samplerTrack.intValue, app.synthTrack.intValue, app.drumTrack.intValue, g.seqTrack.intValue),
            duck = g.duck.value,
            patterns = g.patternSlots(), currentPattern = g.patternIdx.intValue,
            song = g.song.value, songMode = g.songMode.value, songLoop = g.songLoop.value,
        )
    }

    /** Nothing made yet: every pattern empty, no sample, no automation. Not worth a project. */
    private fun isBlank(p: ProjectFile) = p.clips.all { it.notes.isEmpty() } && p.samplers.isEmpty() && p.automation.isEmpty()

    private fun blank() = ProjectFile(
        name = defaultName(),
        tracks = listOf(
            com.thorbox.model.TrackInfo(TrackKind.SAMPLER, "SAMPLER"), com.thorbox.model.TrackInfo(TrackKind.SYNTH, "SYNTH"),
            com.thorbox.model.TrackInfo(TrackKind.DRUMS, "DRUMS"),
        ),
        transport = app.startDefaults()?.let { TransportDto(bpm = it.bpm, swing = it.swing, metronome = it.click) } ?: TransportDto(),
        scale = app.startDefaults()?.let { ScaleDto(it.root.name, it.mode.name, it.follow) } ?: ScaleDto(),
        kits = mapOf(2 to com.thorbox.model.DrumKit(name = "808", voices = com.thorbox.model.FactoryPresets.kits.first().kit.voices)),
    )

    private fun meta(id: String, p: ProjectFile) =
        ProjectMeta(id, p.name, p.savedAt, p.tracks.count { it.kind != TrackKind.EMPTY }, p.transport.bpm)

    private fun newId() = UUID.randomUUID().toString().take(12)
    private fun defaultName() = "Beat " + SimpleDateFormat("MMM d HH.mm", Locale.US).format(Date())

    companion object {
        private const val TAG = "ClamshellProjects"
        private const val AUTOSAVE_MS = 15_000L
    }
}
