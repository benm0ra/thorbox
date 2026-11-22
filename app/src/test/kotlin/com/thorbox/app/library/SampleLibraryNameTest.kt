// SPDX-License-Identifier: GPL-3.0-or-later
package com.thorbox.app.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleLibraryNameTest {
    @Test fun listsAudioFilesOfEverySupportedType() {
        for (n in listOf("kick.wav", "Break 90.WAV", "loop.mp3", "pad.flac", "vox.ogg", "bass.m4a", "dope ass drums.aif", "chop.AIFF"))
            assertTrue(SampleLibrary.isAudioName(n), n)
    }

    @Test fun skipsMacMetadataHiddenFilesAndOtherTypes() {
        for (n in listOf("._11-Game Over.wav", "._kick.aif", ".hidden.wav", "notes.txt", "cover.jpg", "wav", "kick.wav.asd"))
            assertFalse(SampleLibrary.isAudioName(n), n)
    }
}
