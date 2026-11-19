// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>

// Offline analysis run on the worker thread after decode. Pure C++ with no Android dependencies,
// so it can be unit-tested on a desktop (see engine/src/test/cpp/analysis_test.cpp).
struct AnalysisResult {
    int   keyRoot = -1;        // 0 = C ... 11 = B. -1 = no confident key (one-shots, drums, noise)
    int   keyMode = 0;         // 0 = major, 1 = minor
    float keyConfidence = 0.f; // Pearson r of the best key profile match (0..1)
    float bpm = 0.f;           // 0 = no tempo (too short or no periodicity)
    float bpmConfidence = 0.f; // 0..1
};

AnalysisResult analyzeAudio(const float* interleaved, int64_t frames, int channels, int sampleRate);

// Onset positions for auto-chop: up to maxSlices frame positions, ascending, out[0] == 0.
// sensitivity 0..1 (higher = more, softer hits count). Starts are nudged back a few ms and onto
// a zero crossing so slices begin cleanly. Returns the count.
int detectTransients(const float* interleaved, int64_t frames, int channels, int sampleRate,
                     float sensitivity, int maxSlices, int64_t* out);
