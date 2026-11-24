// SPDX-License-Identifier: GPL-3.0-or-later
#include "AudioEngine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
#include <unistd.h>

// AudioEngine, part 3 of 3: EXPORT, the offline bounce (main thread; the live callback is parked).

bool AudioEngine::exportBegin(int sampleRate) {
    if (mOffline.load(std::memory_order_relaxed)) return false;
    mExpRate = (float)std::clamp(sampleRate, 22050, 96000);
    mCapMix.assign(2 * (size_t)kExportMaxFrames, 0.f);
    mExpOut.assign(2 * (size_t)kExportMaxFrames, 0.f);
    for (auto& c : mCapStem) c.assign(2 * (size_t)kExportMaxFrames, 0.f);
    mExpStemSlot.fill(-1); mExpMixSlot = -1;
    mOffline.store(true, std::memory_order_seq_cst);
    // wait out a live block already in flight (a few ms at most); afterwards the callback stays silent
    while (mInRender.load(std::memory_order_seq_cst)) std::this_thread::yield();
    return true;
}

bool AudioEngine::exportOpen(int slot, int fd, int format, int bits) {
    if (!mOffline.load(std::memory_order_relaxed) || slot < 0 || slot >= kExportSlots || fd < 0) { if (fd >= 0) ::close(fd); return false; }
    const int sr = (int)mExpRate;
    if (format == (int)exportfile::Format::Flac) mExpW[(size_t)slot] = std::make_unique<exportfile::FlacWriter>(fd, sr, bits);
    else mExpW[(size_t)slot] = std::make_unique<exportfile::WavWriter>(fd, sr, bits);
    return true;
}

void AudioEngine::exportRoute(int mixSlot, const int* stemSlots) {
    auto ok = [&](int s) { return s >= 0 && s < kExportSlots ? s : -1; };
    mExpMixSlot = ok(mixSlot);
    for (int k = 0; k < kStrips; ++k) mExpStemSlot[(size_t)k] = stemSlots ? ok(stemSlots[k]) : -1;
}

float AudioEngine::exportRender(int frames, float* stemPeaks) {
    if (!mOffline.load(std::memory_order_relaxed)) return -1.f;
    frames = std::clamp(frames, 0, kExportMaxFrames);
    mCapturing = true;
    renderFloat(mExpRate, mExpOut.data(), frames);
    mCapturing = false;
    float peak = 0.f;
    for (int i = 0; i < 2 * frames; ++i) peak = std::max(peak, std::fabs(mCapMix[(size_t)i]));
    if (mExpMixSlot >= 0 && mExpW[(size_t)mExpMixSlot]) mExpW[(size_t)mExpMixSlot]->write(mCapMix.data(), frames);
    for (int k = 0; k < kStrips; ++k) {
        const int sl = mExpStemSlot[(size_t)k];
        float p = 0.f;
        if (sl >= 0) {
            for (int i = 0; i < 2 * frames; ++i) p = std::max(p, std::fabs(mCapStem[(size_t)k][(size_t)i]));
            if (mExpW[(size_t)sl]) mExpW[(size_t)sl]->write(mCapStem[(size_t)k].data(), frames);
        }
        if (stemPeaks) stemPeaks[k] = p;
        peak = std::max(peak, p);
    }
    return peak;
}

int64_t AudioEngine::exportClose(int slot) {
    if (slot < 0 || slot >= kExportSlots || !mExpW[(size_t)slot]) return -1;
    auto& w = mExpW[(size_t)slot];
    const bool ok = w->close();
    const int64_t n = w->frames();
    w.reset();
    return ok ? n : -1;
}

void AudioEngine::exportEnd() {
    if (!mOffline.load(std::memory_order_relaxed)) return;
    for (auto& w : mExpW) w.reset();                                   // an unfinished file is just closed
    mExpStemSlot.fill(-1); mExpMixSlot = -1;
    mLoud.reset();
    mCapMix = {}; mExpOut = {};
    for (auto& c : mCapStem) c = {};
    mOffline.store(false, std::memory_order_seq_cst);                  // the live callback takes over again
}
