// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectCodecTest {
    private fun sample() = ProjectFile(
        name = "Night Drive", savedAt = 1_795_000_000_000,
        transport = TransportDto(bpm = 87.5f, swing = 0.58f, swing16 = false, metronome = true),
        scale = ScaleDto("F_SHARP", "MAJOR", followSample = false, keyLock = false),
        masterDb = -2.5f,
        tracks = listOf(TrackInfo(TrackKind.SAMPLER, "CHOPS"), TrackInfo(TrackKind.SYNTH, "808"), TrackInfo(TrackKind.DRUMS, "KIT")),
        clips = listOf(Clip(ClipId("t0"), "T1", color = 0, lengthTicks = 3840, notes = listOf(NoteEvent(tick = 240, lengthTicks = 120, note = 3, velocity = 0.8f)))),
        channels = listOf(ChannelDto("CHOPS", volumeDb = -3f, pan = 0.25f, scSource = 2, scAmount = 0.6f)),
        feels = listOf(FeelDto("DILLA", 0.7f)),
        automation = mapOf("t1:vol" to listOf(AutoPointDto(0, -6f), AutoPointDto(960, 0f))),
        synths = mapOf(1 to SynthPatch(name = "SUB 808")),
        kits = mapOf(2 to DrumKit(name = "SP KIT", bus = DrumBus(crunch = 0.5f, muffle = 0.3f, tape = 0.2f, dust = 0.4f))),
        samplers = mapOf(0 to SamplerDto(file = "t1-break.wav", sampleName = "break", regions = listOf(0L, 1000L, 1000L, 5000L),
            pads = listOf(PadDto(pitch = -2, mode = "LOOP")), chopMode = "EQUAL", equalCount = 8, keyMatch = true)),
        focus = FocusDto(samplerTrack = 0, synthTrack = 1, drumTrack = 2, seqTrack = 1),
    )

    @Test fun roundTripKeepsEverything() {
        val p = sample()
        val back = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p, back)
    }

    @Test fun unknownAndMissingFieldsAreTolerated() {
        val p = ProjectCodec.decode("""{"name":"Old","fromTheFuture":42,"transport":{"bpm":100}}""")
        assertNotNull(p)
        assertEquals("Old", p.name)
        assertEquals(100f, p.transport.bpm)
        assertEquals(0.5f, p.transport.swing)
        assertEquals(emptyList(), p.tracks)
    }

    @Test fun garbageIsNullNotACrash() {
        assertNull(ProjectCodec.decode("not json"))
        assertNull(ProjectCodec.decode("""{"name": """))
    }

    @Test fun metaRoundTrip() {
        val m = ProjectMeta("abc123", "Night Drive", 1L, 3, 87.5f)
        assertEquals(m, ProjectCodec.decodeMeta(ProjectCodec.encodeMeta(m)))
    }
}
