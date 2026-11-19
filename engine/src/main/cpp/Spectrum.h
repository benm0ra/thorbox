// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Spectrum analyzer for the visualizer. It runs on the CALLER's thread (the UI frame tick), never on
// the audio thread: the audio thread only writes samples into a lock-free ring.
// Allocation-free after construction. Header-only, tested on desktop.
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>

namespace viz {

constexpr int kFft = 2048;   // 23 Hz bins at 48 kHz: enough to separate kick from sub
constexpr int kMaxBands = 64;

class Spectrum {
public:
    Spectrum() {
        for (int i = 0; i < kFft; ++i) mWin[i] = 0.5f - 0.5f * std::cos(2.f * 3.14159265f * i / kFft);
        for (int k = 0; k < kFft / 2; ++k) {
            const double a = -2.0 * 3.14159265358979323846 * k / kFft;
            mTw[k] = {(float)std::cos(a), (float)std::sin(a)};
        }
    }

    // in: kFft mono samples, oldest first. Writes nBands values in 0..1 (log-spaced 30 Hz–16 kHz,
    // -72..0 dBFS mapped to 0..1). Also returns bass / mid / treble energy (0..1) for reactive visuals.
    void analyze(const float* in, float sr, float* bands, int nBands, float* bass, float* mid, float* high) {
        nBands = std::clamp(nBands, 1, kMaxBands);
        for (int i = 0; i < kFft; ++i) mBuf[i] = {in[i] * mWin[i], 0.f};
        fft();
        const float binHz = sr / kFft;
        const float lo = 30.f, hi = std::min(16000.f, sr * 0.45f);
        float eb = 0, em = 0, eh = 0;
        for (int b = 0; b < nBands; ++b) {
            const float f0 = lo * std::pow(hi / lo, (float)b / nBands);
            const float f1 = lo * std::pow(hi / lo, (float)(b + 1) / nBands);
            int k0 = std::max(1, (int)std::floor(f0 / binHz)), k1 = std::max(k0 + 1, (int)std::ceil(f1 / binHz));
            k1 = std::min(k1, kFft / 2);
            float m = 0.f;
            if (k1 - k0 <= 1) {
                // Band narrower than a bin (low end): interpolate the magnitude at the band centre.
                const float pos = std::sqrt(f0 * f1) / binHz;
                const int a = std::clamp((int)pos, 1, kFft / 2 - 2);
                const float t = std::clamp(pos - (float)a, 0.f, 1.f);
                m = std::abs(mBuf[a]) * (1.f - t) + std::abs(mBuf[a + 1]) * t;
            } else {
                for (int k = k0; k < k1; ++k) m = std::max(m, std::abs(mBuf[k]));
            }
            const float db = 20.f * std::log10(m * (4.f / kFft) + 1e-9f);   // Hann gain ≈ 0.5, sine peak → 0 dB
            const float v = std::clamp((db + 72.f) / 72.f, 0.f, 1.f);
            bands[b] = v;
            const float fc = std::sqrt(f0 * f1);
            if (fc < 250.f) eb = std::max(eb, v); else if (fc < 4000.f) em = std::max(em, v); else eh = std::max(eh, v);
        }
        *bass = eb; *mid = em; *high = eh;
    }

private:
    void fft() {
        for (int i = 1, j = 0; i < kFft; ++i) {
            int bit = kFft >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(mBuf[i], mBuf[j]);
        }
        for (int len = 2; len <= kFft; len <<= 1) {
            const int step = kFft / len, half = len / 2;
            for (int i = 0; i < kFft; i += len)
                for (int k = 0; k < half; ++k) {
                    const auto u = mBuf[i + k], v = mBuf[i + k + half] * mTw[k * step];
                    mBuf[i + k] = u + v; mBuf[i + k + half] = u - v;
                }
        }
    }
    std::array<float, kFft> mWin{};
    std::array<std::complex<float>, kFft / 2> mTw{};
    std::array<std::complex<float>, kFft> mBuf{};
};

} // namespace viz
