// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
#include <vector>

// Decoded audio, immutable once handed to the audio thread.
// Kept at the file's native sample rate. The voice resamples on the fly (varispeed),
// so importing never costs an extra resampling pass and "frames" match the Kotlin model.
struct SampleBuffer {
    int32_t id = 0;
    int32_t sampleRate = 48000;
    int32_t channels = 1;          // 1 or 2 (more are folded to 2 at decode)
    int64_t frames = 0;
    std::vector<float> data;       // interleaved

    inline float at(int ch, int64_t frame) const { return data[(size_t)(frame * channels + ch)]; }
};
