// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Offline time-stretch + pitch-shift for KEY MATCH and BPM SYNC (Serato-style "keep tempo when
// you change key, keep key when you change tempo").
//
// Method: WSOLA (waveform-similarity overlap-add) changes duration without changing pitch, then a
// cubic resampler changes pitch (and duration back). For pitch p (ratio) and tempo t (ratio):
//   1. WSOLA stretch by  alpha = p / t   (length × p/t, pitch unchanged)
//   2. resample by p                     (length ÷ p, pitch × p)
//   → final length = original / t, pitch × p.
// It runs on the worker thread and always renders from the ORIGINAL sample, so repeated key or BPM
// changes never stack up quality loss. Header-only and Android-free (tested on desktop).

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace stretch {

// Interleaved in → interleaved out. tempo > 1 = faster/shorter. semitones = pitch change.
inline std::vector<float> process(const std::vector<float>& in, int channels, int sampleRate, double tempo, double semitones) {
    if (channels < 1 || in.size() < (size_t)channels * 8) return in;
    tempo = std::clamp(tempo, 0.25, 4.0);
    semitones = std::clamp(semitones, -24.0, 24.0);
    const double p = std::pow(2.0, semitones / 12.0);
    const double alpha = p / tempo;
    const int64_t nIn = (int64_t)(in.size() / channels);
    if (std::fabs(alpha - 1.0) < 1e-6 && std::fabs(p - 1.0) < 1e-6) return in;

    // ---- 1. WSOLA ----
    // ~23 ms grains at 50% overlap, ±~6 ms similarity search: a good, artifact-light default for
    // drums and music loops alike.
    const int N = std::max(256, (int)(0.023 * sampleRate)) & ~1;
    const int Hs = N / 2;
    const int tol = std::max(32, (int)(0.006 * sampleRate));
    std::vector<float> win(N);
    for (int i = 0; i < N; ++i) win[i] = 0.5f - 0.5f * std::cos(2.f * 3.14159265f * i / N);

    const int64_t nOut1 = (int64_t)std::ceil(nIn * alpha) + N;
    std::vector<float> mid((size_t)nOut1 * channels, 0.f), norm((size_t)nOut1, 0.f);
    auto mono = [&](int64_t i) -> float {
        if (i < 0 || i >= nIn) return 0.f;
        float s = 0.f; for (int c = 0; c < channels; ++c) s += in[(size_t)(i * channels + c)];
        return s;
    };

    int64_t prevIn = 0;                      // where the previous grain was read from
    for (int64_t outPos = 0, k = 0; outPos < nOut1 - N; outPos += Hs, ++k) {
        const int64_t nominal = (int64_t)std::llround(k * Hs / alpha);
        int64_t best = nominal;
        if (k > 0) {
            // The "natural continuation" of the previous grain is prevIn + Hs: find the candidate near
            // `nominal` whose first half best matches it (normalized cross-correlation, coarse step).
            const int64_t nat = prevIn + Hs;
            double bestScore = -1e30;
            for (int64_t d = -tol; d <= tol; d += 2) {
                const int64_t cand = nominal + d;
                if (cand < 0 || cand + N >= nIn) continue;
                double xy = 0, xx = 1e-9, yy = 1e-9;
                for (int i = 0; i < Hs; i += 2) {
                    const float a = mono(nat + i), b = mono(cand + i);
                    xy += a * b; xx += a * a; yy += b * b;
                }
                const double score = xy / std::sqrt(xx * yy);
                if (score > bestScore) { bestScore = score; best = cand; }
            }
        }
        best = std::clamp<int64_t>(best, 0, std::max<int64_t>(0, nIn - 1));
        for (int i = 0; i < N; ++i) {
            const int64_t src = best + i;
            if (src >= nIn) break;
            const float w = win[i];
            for (int c = 0; c < channels; ++c) mid[(size_t)((outPos + i) * channels + c)] += in[(size_t)(src * channels + c)] * w;
            norm[(size_t)(outPos + i)] += w;
        }
        prevIn = best;
    }
    const int64_t nMid = std::min<int64_t>(nOut1, (int64_t)std::llround(nIn * alpha));
    for (int64_t i = 0; i < nMid; ++i) {
        const float w = norm[(size_t)i] > 1e-3f ? 1.f / norm[(size_t)i] : 0.f;
        for (int c = 0; c < channels; ++c) mid[(size_t)(i * channels + c)] *= w;
    }

    // ---- 2. resample by p (4-point Hermite) ----
    const int64_t nOut = std::max<int64_t>(1, (int64_t)std::llround(nMid / p));
    std::vector<float> out((size_t)nOut * channels);
    auto at = [&](int64_t i, int c) { i = std::clamp<int64_t>(i, 0, nMid - 1); return mid[(size_t)(i * channels + c)]; };
    for (int64_t j = 0; j < nOut; ++j) {
        const double pos = j * p;
        const int64_t i = (int64_t)pos;
        const float t = (float)(pos - i);
        for (int c = 0; c < channels; ++c) {
            const float xm1 = at(i - 1, c), x0 = at(i, c), x1 = at(i + 1, c), x2 = at(i + 2, c);
            const float c1 = (x1 - xm1) * 0.5f, v = x0 - x1, w = c1 + v, a = w + v + (x2 - x0) * 0.5f, bn = w + a;
            out[(size_t)(j * channels + c)] = std::clamp((((a * t) - bn) * t + c1) * t + x0, -1.f, 1.f);
        }
    }
    return out;
}

} // namespace stretch
