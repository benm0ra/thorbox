// SPDX-License-Identifier: GPL-3.0-or-later
#include "Analysis.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979f;
using cf = std::complex<float>;

// In-place iterative radix-2 FFT with a precomputed twiddle table (built once per STFT size;
// the old per-butterfly complex multiply-accumulate drifted and cost ~2× more).
struct Fft {
    explicit Fft(size_t n) : n(n), tw(n / 2) {
        for (size_t k = 0; k < n / 2; ++k) {
            const double a = -2.0 * 3.14159265358979323846 * (double)k / (double)n;
            tw[k] = cf((float)std::cos(a), (float)std::sin(a));
        }
    }
    void operator()(std::vector<cf>& a) const {
        for (size_t i = 1, j = 0; i < n; ++i) {
            size_t bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(a[i], a[j]);
        }
        for (size_t len = 2; len <= n; len <<= 1) {
            const size_t step = n / len, half = len / 2;
            for (size_t i = 0; i < n; i += len)
                for (size_t k = 0; k < half; ++k) {
                    const cf u = a[i + k], v = a[i + k + half] * tw[k * step];
                    a[i + k] = u + v;
                    a[i + k + half] = u - v;
                }
        }
    }
    size_t n;
    std::vector<cf> tw;
};

std::vector<float> hann(size_t n) {
    std::vector<float> w(n);
    for (size_t i = 0; i < n; ++i) w[i] = 0.5f - 0.5f * std::cos(2.f * kPi * (float)i / (float)n);
    return w;
}

// Magnitude spectrogram, frame-by-frame, calling fn(frameIndex, mags).
template <typename Fn>
void stft(const std::vector<float>& x, size_t n, size_t hop, Fn&& fn) {
    if (x.size() < n) return;
    const auto win = hann(n);
    const Fft fft(n);
    std::vector<cf> buf(n);
    std::vector<float> mag(n / 2 + 1);
    size_t frame = 0;
    for (size_t start = 0; start + n <= x.size(); start += hop, ++frame) {
        for (size_t i = 0; i < n; ++i) buf[i] = cf(x[start + i] * win[i], 0.f);
        fft(buf);
        for (size_t k = 0; k <= n / 2; ++k) mag[k] = std::abs(buf[k]);
        fn(frame, mag);
    }
}

// Krumhansl–Kessler key profiles. Swappable later (Temperley / EDM-tuned profiles) if
// electronic material mis-detects. Keep them behind this function.
constexpr float kMajor[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
constexpr float kMinor[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};

float pearson(const float* a, const float* b) {
    float ma = 0, mb = 0;
    for (int i = 0; i < 12; ++i) { ma += a[i]; mb += b[i]; }
    ma /= 12; mb /= 12;
    float num = 0, da = 0, db = 0;
    for (int i = 0; i < 12; ++i) {
        num += (a[i] - ma) * (b[i] - mb);
        da += (a[i] - ma) * (a[i] - ma);
        db += (b[i] - mb) * (b[i] - mb);
    }
    return (da <= 0 || db <= 0) ? 0.f : num / std::sqrt(da * db);
}

void detectKey(const std::vector<float>& mono, int sr, AnalysisResult& r) {
    const size_t n = 8192, hop = 4096;
    float chroma[12] = {0};
    const float binHz = (float)sr / (float)n;

    // Precompute bin -> (pitch class, weight). Weight favours bins near a semitone centre.
    const size_t nb = n / 2 + 1;
    std::vector<int> pcOf(nb, -1);
    std::vector<float> wOf(nb, 0.f);
    for (size_t k = 1; k < nb; ++k) {
        const float f = (float)k * binHz;
        if (f < 60.f || f > 5000.f) continue;
        const float midi = 69.f + 12.f * std::log2(f / 440.f);
        const float nearest = std::round(midi);
        const float d = midi - nearest;                     // -0.5..0.5
        const float w = std::cos(kPi * d);
        pcOf[k] = (((int)nearest % 12) + 12) % 12;
        wOf[k] = w * w;
    }

    float globalPeak = 0.f;
    std::vector<std::array<float, 12>> frames;
    stft(mono, n, hop, [&](size_t, const std::vector<float>& mag) {
        std::array<float, 12> c{};
        float e = 0.f;
        for (size_t k = 1; k < nb; ++k) {
            if (pcOf[k] < 0) continue;
            const float m = mag[k] * wOf[k];
            c[pcOf[k]] += m;
            e += m;
        }
        globalPeak = std::max(globalPeak, e);
        frames.push_back(c);
    });
    if (frames.empty() || globalPeak <= 0.f) return;

    // Per-frame normalisation so a loud intro doesn't dominate; skip near-silent frames.
    for (auto& c : frames) {
        float e = 0.f, mx = 0.f;
        for (float v : c) { e += v; mx = std::max(mx, v); }
        if (e < globalPeak * 0.05f || mx <= 0.f) continue;
        for (int i = 0; i < 12; ++i) chroma[i] += c[i] / mx;
    }

    float best = -2.f, second = -2.f;
    int bestRoot = -1, bestMode = 0;
    float rotated[12];
    for (int mode = 0; mode < 2; ++mode) {
        const float* prof = mode == 0 ? kMajor : kMinor;
        for (int root = 0; root < 12; ++root) {
            for (int pc = 0; pc < 12; ++pc) rotated[pc] = prof[(pc - root + 12) % 12];
            const float c = pearson(chroma, rotated);
            if (c > best) { second = best; best = c; bestRoot = root; bestMode = mode; }
            else if (c > second) second = c;
        }
    }
    r.keyConfidence = std::max(0.f, best);
    // Below this the material is not convincingly tonal (drums, noise, single hits).
    // Showing "—" is better than showing a confidently wrong key.
    if (best >= 0.55f) { r.keyRoot = bestRoot; r.keyMode = bestMode; }
}

void detectTempo(const std::vector<float>& mono, int sr, AnalysisResult& r) {
    const double duration = (double)mono.size() / sr;
    if (duration < 2.5) return;                              // one-shots and short hits: no tempo

    const size_t n = 1024, hop = 512;
    const double fps = (double)sr / hop;

    // Onset envelope: log-compressed spectral flux, half-wave rectified.
    std::vector<float> prev(n / 2 + 1, 0.f), onset;
    stft(mono, n, hop, [&](size_t, const std::vector<float>& mag) {
        float flux = 0.f;
        for (size_t k = 1; k < mag.size(); ++k) {
            const float lm = std::log1p(1000.f * mag[k]);
            flux += std::max(0.f, lm - prev[k]);
            prev[k] = lm;
        }
        onset.push_back(flux);
    });
    if (onset.size() < 16) return;

    // Remove the slow-moving trend (≈0.5 s moving average), keep the positive part.
    const int half = std::max(1, (int)(0.25 * fps));
    std::vector<float> o(onset.size());
    for (int i = 0; i < (int)onset.size(); ++i) {
        int a = std::max(0, i - half), b = std::min((int)onset.size() - 1, i + half);
        float m = 0.f;
        for (int j = a; j <= b; ++j) m += onset[j];
        m /= (float)(b - a + 1);
        o[i] = std::max(0.f, onset[i] - m);
    }
    // Light Gaussian smoothing (σ ≈ 1 frame). A beat rarely lands on an exact frame boundary,
    // so without this the ACF peak for the true beat splits across two lags and loses to a
    // multiple that happens to be near-integer.
    {
        const float k[5] = {0.054f, 0.244f, 0.403f, 0.244f, 0.054f};
        std::vector<float> sm(o.size(), 0.f);
        for (int i = 0; i < (int)o.size(); ++i)
            for (int j = -2; j <= 2; ++j) {
                const int t = std::clamp(i + j, 0, (int)o.size() - 1);
                sm[i] += k[j + 2] * o[t];
            }
        o.swap(sm);
    }

    // Autocorrelation over musically useful lags.
    const int minLag = std::max(1, (int)std::floor(60.0 * fps / 220.0));
    const int maxLag = std::min((int)o.size() / 2, (int)std::ceil(60.0 * fps / 50.0));
    if (maxLag <= minLag + 2) return;
    std::vector<double> acf(4 * maxLag + 1, 0.0);
    for (int lag = 0; lag < (int)acf.size() && lag < (int)o.size(); ++lag) {
        double s = 0.0;
        for (size_t i = lag; i < o.size(); ++i) s += (double)o[i] * o[i - lag];
        acf[lag] = s / (double)(o.size() - lag);             // unbiased
    }
    const double a0 = acf[0];
    if (a0 <= 0.0) return;
    for (double& v : acf) v /= a0;

    auto acfAt = [&](int l) { return (l >= 0 && l < (int)acf.size()) ? acf[l] : 0.0; };
    int bestLag = -1;
    double bestScore = -1e9;
    for (int lag = minLag; lag <= maxLag; ++lag) {
        const double bpm = 60.0 * fps / lag;
        const double oct = std::log2(bpm / 120.0);
        const double prior = std::exp(-0.5 * oct * oct);     // gentle preference around 120
        const double score = (acfAt(lag) + 0.5 * acfAt(2 * lag) + 0.25 * acfAt(4 * lag)) * prior;
        if (score > bestScore) { bestScore = score; bestLag = lag; }
    }
    if (bestLag < 0) return;

    // Parabolic refinement of the peak.
    double lag = bestLag;
    const double y0 = acfAt(bestLag - 1), y1 = acfAt(bestLag), y2 = acfAt(bestLag + 1);
    const double den = y0 - 2 * y1 + y2;
    if (std::fabs(den) > 1e-12) lag += 0.5 * (y0 - y2) / den;
    double bpm = 60.0 * fps / lag;
    float conf = (float)std::clamp(y1, 0.0, 1.0);

    // Loop-length snap: most loops are an exact number of bars long, which is far more
    // precise than the ACF. If a whole number of beats fits the file length at a tempo
    // within 4% of our estimate (or its double or half), trust the file length.
    double snapped = 0.0, bestErr = 0.04;
    for (int beats : {1, 2, 4, 8, 12, 16, 24, 32, 48, 64}) {
        const double cand = 60.0 * beats / duration;
        for (double mult : {1.0, 2.0, 0.5}) {
            const double target = bpm * mult;
            const double err = std::fabs(cand - target) / target;
            if (err < bestErr) { bestErr = err; snapped = cand / mult; } // keep the estimate's octave
        }
    }
    if (snapped > 0.0) { bpm = snapped; conf = std::max(conf, 0.8f); }

    while (bpm < 70.0) bpm *= 2.0;
    while (bpm >= 180.0) bpm *= 0.5;

    if (conf < 0.1f) return;                                 // no meaningful periodicity
    r.bpm = (float)(std::round(bpm * 10.0) / 10.0);
    r.bpmConfidence = conf;
}

} // namespace

AnalysisResult analyzeAudio(const float* x, int64_t frames, int channels, int sampleRate) {
    AnalysisResult r;
    if (!x || frames <= 0 || channels <= 0 || sampleRate <= 0) return r;
    std::vector<float> mono((size_t)frames);
    for (int64_t i = 0; i < frames; ++i) {
        float s = 0.f;
        for (int c = 0; c < channels; ++c) s += x[i * channels + c];
        mono[(size_t)i] = s / (float)channels;
    }
    if ((double)frames / sampleRate >= 0.4) detectKey(mono, sampleRate, r);
    detectTempo(mono, sampleRate, r);
    return r;
}

int detectTransients(const float* x, int64_t frames, int channels, int sr, float sensitivity, int maxSlices, int64_t* out) {
    if (!x || frames <= 0 || channels <= 0 || sr <= 0 || maxSlices <= 0 || !out) return 0;
    out[0] = 0;
    if (maxSlices == 1 || frames < 2048) return 1;
    std::vector<float> mono((size_t)frames);
    for (int64_t i = 0; i < frames; ++i) {
        float s = 0.f;
        for (int c = 0; c < channels; ++c) s += x[i * channels + c];
        mono[(size_t)i] = s / (float)channels;
    }
    const size_t n = 1024, hop = 256;
    std::vector<float> prev(n / 2 + 1, 0.f), flux;
    stft(mono, n, hop, [&](size_t, const std::vector<float>& mag) {
        float f = 0.f;
        for (size_t k = 1; k < mag.size(); ++k) { const float lm = std::log1p(1000.f * mag[k]); f += std::max(0.f, lm - prev[k]); prev[k] = lm; }
        flux.push_back(f);
    });
    if (flux.size() < 8) return 1;
    float mx = 0.f; for (float f : flux) mx = std::max(mx, f);
    if (mx <= 0.f) return 1;
    const float sens = std::clamp(sensitivity, 0.f, 1.f);
    const float ratio = 1.15f + 1.6f * (1.f - sens);       // how far above the local mean a peak must rise
    const float floorAbs = mx * (0.02f + 0.18f * (1.f - sens));
    const int minGap = std::max(1, (int)(0.07 * sr / hop));  // 70 ms between slices
    struct Cand { int64_t frame; float strength; };
    std::vector<Cand> cands;
    int last = -minGap;
    for (int i = 1; i + 1 < (int)flux.size(); ++i) {
        int a = std::max(0, i - 10), b = std::min((int)flux.size() - 1, i + 10);
        float mean = 0.f; for (int j = a; j <= b; ++j) mean += flux[j]; mean /= (float)(b - a + 1);
        bool peak = flux[i] >= flux[i - 1] && flux[i] > flux[i + 1];
        for (int j = std::max(0, i - 3); j <= std::min((int)flux.size() - 1, i + 3) && peak; ++j) if (flux[j] > flux[i]) peak = false;
        if (!peak || flux[i] < floorAbs || flux[i] < mean * ratio || i - last < minGap) continue;
        // The newest hop of the window holds the attack: place it there, then pull back ~3 ms.
        int64_t f = (int64_t)(i * hop + n - hop) - (int64_t)(0.003 * sr);
        if (f < (int64_t)(0.03 * sr)) continue;               // a hit at the very start is slice 0 anyway
        cands.push_back({std::min(f, frames - 1), flux[i]});
        last = i;
    }
    std::sort(cands.begin(), cands.end(), [](const Cand& p, const Cand& q) { return p.strength > q.strength; });
    if ((int)cands.size() > maxSlices - 1) cands.resize((size_t)(maxSlices - 1));
    std::sort(cands.begin(), cands.end(), [](const Cand& p, const Cand& q) { return p.frame < q.frame; });
    int count = 1;
    const int64_t zwin = (int64_t)(0.002 * sr);
    for (auto& c : cands) {
        int64_t best = c.frame;                               // snap back onto a zero crossing (±2 ms)
        for (int64_t k = c.frame; k > std::max<int64_t>(1, c.frame - zwin); --k)
            if ((mono[(size_t)k - 1] <= 0.f) != (mono[(size_t)k] <= 0.f)) { best = k; break; }
        out[count++] = best;
    }
    return count;
}
