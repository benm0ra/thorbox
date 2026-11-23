// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A saved session, as written to disk: everything needed to come back to exactly where you were.
 * Plain values only (enums by name, slices as frame pairs), so the format is easy to read and
 * survives app updates: unknown fields are ignored, missing ones take their defaults.
 *
 * Samples are NOT inlined: each sampler track names an audio file kept next to project.json, in
 * the project's own folder, so a project still opens after the original file is moved or deleted.
 */
@Serializable
data class ProjectFile(
    val version: Int = 1,
    val name: String = "Untitled",
    val savedAt: Long = 0L,
    val transport: TransportDto = TransportDto(),
    val scale: ScaleDto = ScaleDto(),
    val masterDb: Float = 0f,
    val tracks: List<TrackInfo> = emptyList(),
    val clips: List<Clip> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val feels: List<FeelDto> = emptyList(),
    val automation: Map<String, List<AutoPointDto>> = emptyMap(),
    /** Per track index, for SYNTH tracks. */
    val synths: Map<Int, SynthPatch> = emptyMap(),
    /** Per track index, for DRUMS tracks. */
    val kits: Map<Int, DrumKit> = emptyMap(),
    /** Per track index, for SAMPLER tracks. */
    val samplers: Map<Int, SamplerDto> = emptyMap(),
    val focus: FocusDto = FocusDto(),
)

@Serializable data class TransportDto(
    val bpm: Float = 90f, val swing: Float = 0.5f, val swing16: Boolean = true, val quantize: Float = 1f,
    val repeatRate: String = "SIXTEENTH", val metronome: Boolean = false,
)
@Serializable data class ScaleDto(val root: String = "C", val mode: String = "MINOR", val followSample: Boolean = true, val keyLock: Boolean = true)
@Serializable data class ChannelDto(
    val name: String, val volumeDb: Float = 0f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false,
    val scSource: Int = -1, val scAmount: Float = 0f,
)
@Serializable data class FeelDto(val template: String = "STRAIGHT", val amount: Float = 1f)
@Serializable data class AutoPointDto(val tick: Int, val value: Float)
@Serializable data class PadDto(
    val pitch: Int = 0, val fine: Int = 0, val mode: String = "ONE_SHOT", val gainDb: Float = 0f, val warp: Boolean = false,
    // PAD EDIT (added later: defaults = how pads always sounded, so older projects open unchanged)
    val pan: Float = 0f, val attackMs: Float = 0f, val decayMs: Float = 0f, val releaseMs: Float = 15f,
    val cutoff: Float = 1f, val reso: Float = 0f, val reverse: Boolean = false,
    val muteGroup: Int = 0, val muteA: Int = -1, val muteB: Int = -1, val poly: Boolean = false, val velSens: Float = 1f,
)
@Serializable data class SamplerDto(
    /** File name inside the project folder (samples/…), or null when the track has no sample. */
    val file: String? = null,
    val sampleName: String? = null,
    /** Slices in ORIGINAL-sample frames: [start0, end0, start1, end1, …]. */
    val regions: List<Long> = emptyList(),
    val pads: List<PadDto> = emptyList(),
    val chopMode: String = "AUTO",
    val sensitivity: Float = 0.6f,
    val equalCount: Int = 16,
    val keyMatch: Boolean = false,
    /** Older projects: true = SYNC. Newer ones use [stretch] (OFF / SYNC / HALF / DOUBLE / FIT) + [fitBars]. */
    val bpmSync: Boolean = false,
    val stretch: String = "",
    val fitBars: Int = 2,
    /** ONE AT A TIME: a pad cuts whatever else this track is playing (true, the default) or pads layer (false). */
    val chokeAll: Boolean = true,
    /** TRANSPOSE: semitones on every pad, on top of each pad's own pitch. */
    val transpose: Int = 0,
)
@Serializable data class FocusDto(val samplerTrack: Int = 0, val synthTrack: Int = 1, val drumTrack: Int = 2, val seqTrack: Int = 0)

/** Small listing entry for the project browser (read without parsing whole projects). */
@Serializable data class ProjectMeta(val id: String, val name: String, val savedAt: Long, val tracks: Int, val bpm: Float)

object ProjectCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true; allowSpecialFloatingPointValues = true }
    fun encode(p: ProjectFile): String = json.encodeToString(ProjectFile.serializer(), p)
    /** Null when the text isn't a project (corrupt or truncated): callers say so instead of crashing. */
    fun decode(text: String): ProjectFile? = runCatching { json.decodeFromString(ProjectFile.serializer(), text) }.getOrNull()
    fun encodeMeta(m: ProjectMeta): String = json.encodeToString(ProjectMeta.serializer(), m)
    fun decodeMeta(text: String): ProjectMeta? = runCatching { json.decodeFromString(ProjectMeta.serializer(), text) }.getOrNull()
}
