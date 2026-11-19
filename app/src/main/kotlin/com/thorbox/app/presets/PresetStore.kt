// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.presets

import android.content.Context
import com.thorbox.model.FactoryPresets
import com.thorbox.model.KitPreset
import com.thorbox.model.PresetCodec
import com.thorbox.model.SynthPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User presets (synth patches and drum kits) in the app's private storage, as JSON. Factory
 * presets live in code and are never written. Files are replaced atomically (write temp → rename),
 * so a crash mid-save can't corrupt the bank. Main-thread API; file IO runs on Dispatchers.IO.
 */
class PresetStore(context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "presets").apply { mkdirs() }
    private val synthFile = File(dir, "synth.json")
    private val kitFile = File(dir, "kits.json")

    private val _userSynth = MutableStateFlow<List<SynthPreset>>(emptyList())
    val userSynth = _userSynth.asStateFlow()
    private val _userKits = MutableStateFlow<List<KitPreset>>(emptyList())
    val userKits = _userKits.asStateFlow()

    val factorySynth: List<SynthPreset> get() = FactoryPresets.synth
    val factoryKits: List<KitPreset> get() = FactoryPresets.kits

    /** Every synth preset in browse order: factory first, then yours. */
    fun allSynth(): List<SynthPreset> = factorySynth + _userSynth.value
    fun allKits(): List<KitPreset> = factoryKits + _userKits.value

    init {
        scope.launch {
            val (s, k) = withContext(Dispatchers.IO) {
                (if (synthFile.exists()) PresetCodec.decodeSynth(synthFile.readText()) else emptyList()) to
                    (if (kitFile.exists()) PresetCodec.decodeKits(kitFile.readText()) else emptyList())
            }
            _userSynth.value = s.map { it.copy(user = true) }
            _userKits.value = k.map { it.copy(user = true) }
        }
    }

    /** Saves (or overwrites a user preset with the same name). Returns the stored name. */
    fun saveSynth(p: SynthPreset): String {
        val name = uniqueName(p.name, factorySynth.map { it.name })
        val list = _userSynth.value.filterNot { it.name == name } + p.copy(name = name, user = true, patch = p.patch.copy(name = name))
        _userSynth.value = list
        write(synthFile, PresetCodec.encodeSynth(list))
        return name
    }
    fun deleteSynth(name: String) {
        val list = _userSynth.value.filterNot { it.name == name }
        _userSynth.value = list; write(synthFile, PresetCodec.encodeSynth(list))
    }
    fun saveKit(k: KitPreset): String {
        val name = uniqueName(k.name, factoryKits.map { it.name })
        val list = _userKits.value.filterNot { it.name == name } + k.copy(name = name, user = true, kit = k.kit.copy(name = name))
        _userKits.value = list
        write(kitFile, PresetCodec.encodeKits(list))
        return name
    }
    fun deleteKit(name: String) {
        val list = _userKits.value.filterNot { it.name == name }
        _userKits.value = list; write(kitFile, PresetCodec.encodeKits(list))
    }

    /** Factory names are reserved: saving "TR-808" makes "TR-808 2". Names are trimmed and capped. */
    private fun uniqueName(raw: String, reserved: List<String>): String {
        val base = raw.trim().uppercase().take(24).ifEmpty { "MY SOUND" }
        if (base !in reserved) return base
        var i = 2
        while ("$base $i" in reserved) i++
        return "$base $i"
    }

    // Saves can overlap (IO runs in parallel): a per-file version number makes sure only the newest
    // text is written, whatever order the jobs actually run in.
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val versions = HashMap<String, Int>()

    private fun write(f: File, text: String) {
        val v = synchronized(versions) { ((versions[f.name] ?: 0) + 1).also { versions[f.name] = it } }
        scope.launch(Dispatchers.IO) {
            lock.lock()
            try {
                if (synchronized(versions) { versions[f.name] } != v) return@launch
                runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                }
            } finally { lock.unlock() }
        }
    }
}
