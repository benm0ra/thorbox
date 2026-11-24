// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON for user presets. Tolerant of unknown/missing fields, so files survive app updates both ways. */
object PresetCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; coerceInputValues = true }
    private val synthList = ListSerializer(SynthPreset.serializer())
    private val kitList = ListSerializer(KitPreset.serializer())

    fun encodeSynth(list: List<SynthPreset>): String = json.encodeToString(synthList, list)
    fun decodeSynth(text: String): List<SynthPreset> = runCatching { json.decodeFromString(synthList, text) }.getOrDefault(emptyList())
    fun encodeKits(list: List<KitPreset>): String = json.encodeToString(kitList, list)
    fun decodeKits(text: String): List<KitPreset> = runCatching { json.decodeFromString(kitList, text) }.getOrDefault(emptyList())
}
