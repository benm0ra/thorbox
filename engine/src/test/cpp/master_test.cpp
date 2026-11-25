// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the master tools: the limiter never goes over its ceiling (steady tones, sudden hits),
// is transparent below it and adds no latency when off; GLUE's static curve, makeup, MIX, AUTO release
// and bypass; the range checks.
#include "MasterChain.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace master;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[180]; std::snprintf(b_, 180, __VA_ARGS__); \
    std::printf("%-74s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f;
static float db(float g) { return 20.f * std::log10(std::max(1e-9f, g)); }

static std::vector<float> sine(float amp, float hz, int n) {
    std::vector<float> v(2 * n);
    for (int i = 0; i < n; ++i) v[2 * i] = v[2 * i + 1] = amp * std::sin(2.f * (float)M_PI * hz * i / SR);
    return v;
}
template <class P> static void run(P& p, std::vector<float>& v) {
    const int n = (int)v.size() / 2;
    for (int off = 0; off < n; off += 256) p.process(v.data() + 2 * off, std::min(256, n - off), SR);
}
static float peak(const std::vector<float>& v, int from = 0) { float m = 0; for (size_t i = 2 * from; i < v.size(); ++i) m = std::max(m, std::fabs(v[i])); return m; }

int main() {
    // ================= LIMIT =================
    {
        Limiter lim; auto in = sine(0.5f, 440.f, 4800), v = in;
        run(lim, v);
        bool same = true; for (size_t i = 0; i < v.size(); ++i) same &= v[i] == in[i];
        CHECK(same, "limiter OFF (default): bit-identical, no latency");
    }
    {
        Limiter lim; lim.s.on = true; lim.s.ceilingDb = -1.f;
        auto v = sine(2.f, 100.f, 48000);                   // +6 dBFS, 7 dB over the ceiling
        run(lim, v);
        const float c = std::pow(10.f, -1.f / 20.f);
        // after the 10 ms ON fade (which starts from the dry signal) nothing goes over
        CHECK(peak(v, 960) <= c + 1e-5f, "+6 dBFS sine, ceiling −1: peak %.3f dBFS after the fade-in", db(peak(v, 960)));
        CHECK(peak(v, 24000) > c * 0.97f, "…and it's limited, not squashed (%.2f dBFS)", db(peak(v, 24000)));
        CHECK(lim.grMax > 6.f && lim.grMax < 8.f, "gain reduction reported: %.1f dB", lim.grMax);
    }
    {
        // sudden hits out of silence: the look-ahead catches the very first sample of each
        Limiter lim; lim.s.on = true; lim.s.ceilingDb = -0.3f;
        std::vector<float> warm(2 * 4800, 0.f); run(lim, warm);      // let it fade in on silence
        std::vector<float> v(2 * 48000, 0.f);
        for (int hit = 0; hit < 10; ++hit) for (int i = 0; i < 400; ++i) {
            const int k = hit * 4700 + 100 + i;
            v[2 * k] = 4.f * std::exp(-i / 80.f) * (i % 2 ? 1.f : -1.f); v[2 * k + 1] = -v[2 * k];
        }
        run(lim, v);
        const float c = std::pow(10.f, -0.3f / 20.f);
        CHECK(peak(v) <= c + 1e-5f, "+12 dBFS hits out of silence: peak %.3f dBFS (ceiling −0.3)", db(peak(v)));
    }
    {
        // below the ceiling: just the 1.5 ms look-ahead delay, untouched
        Limiter lim; lim.s.on = true; lim.s.ceilingDb = -1.f;
        std::vector<float> warm(2 * 4800, 0.f); run(lim, warm);
        auto in = sine(0.25f, 1000.f, 9600), v = in;
        run(lim, v);
        const int d = (int)std::lround(0.0015f * SR) - 1;
        float err = 0.f; for (int i = d; i < 9600; ++i) err = std::max(err, std::fabs(v[2 * i] - in[2 * (i - d)]));
        CHECK(err < 1e-6f, "−12 dBFS under the ceiling: delayed %d samples, otherwise identical (err %.1e)", d, err);
    }
    {
        // INPUT drives it louder, still capped
        Limiter lim; lim.s.on = true; lim.s.ceilingDb = -1.f; lim.s.inputDb = 12.f;
        auto v = sine(0.3f, 200.f, 48000); run(lim, v);
        const float c = std::pow(10.f, -1.f / 20.f);
        CHECK(peak(v, 960) <= c + 1e-5f && peak(v, 24000) > c * 0.95f, "INPUT +12 on a −10 dBFS tone: pushed up to the ceiling (%.2f dBFS)", db(peak(v, 24000)));
    }
    {
        // OFF again: glides back to zero latency, then bit-identical
        Limiter lim; lim.s.on = true;
        auto v = sine(0.5f, 300.f, 9600); run(lim, v);
        lim.s.on = false;
        auto w = sine(0.5f, 300.f, 9600); run(lim, w);
        auto in = sine(0.5f, 300.f, 4800), x = in; run(lim, x);
        bool same = true; for (size_t i = 0; i < x.size(); ++i) same &= x[i] == in[i];
        CHECK(same, "limiter switched OFF: back to bit-identical (no latency)");
    }

    // ================= GLUE =================
    {
        Glue g; auto in = sine(0.5f, 440.f, 4800), v = in; run(g, v);
        bool same = true; for (size_t i = 0; i < v.size(); ++i) same &= v[i] == in[i];
        CHECK(same, "glue OFF (default): bit-identical");
    }
    {
        Glue g; g.s.on = true; g.s.thresholdDb = -20.f; g.s.ratio = 4.f; g.s.attackMs = 5.f; g.s.releaseMs = 200.f;
        auto v = sine(0.5f, 200.f, 96000); run(g, v);                // −6 dBFS peaks, 14 dB over
        const float want = -6.02f - duck::reductionDb(-6.02f, -20.f, 4.f);
        CHECK(std::fabs(db(peak(v, 72000)) - want) < 0.6f, "4:1 at −20 on −6 dBFS: settles at %.1f dBFS (want %.1f)", db(peak(v, 72000)), want);
        g.s.makeupDb = 6.f;
        auto w = sine(0.5f, 200.f, 48000); run(g, w);
        CHECK(std::fabs(db(peak(w, 24000)) - (want + 6.f)) < 0.6f, "MAKEUP +6: %.1f dBFS", db(peak(w, 24000)));
        g.s.mix = 0.f;
        auto x = sine(0.5f, 200.f, 48000); run(g, x);
        CHECK(std::fabs(db(peak(x, 24000)) + 6.02f) < 0.1f, "MIX 0: the dry signal (%.2f dBFS)", db(peak(x, 24000)));
    }
    {
        // AUTO release: after 3 s of working it lets go slower than after a 50 ms peak
        auto recover = [](int loudFrames) {
            Glue g; g.s.on = true; g.s.thresholdDb = -24.f; g.s.ratio = 4.f; g.s.attackMs = 1.f; g.s.releaseMs = 0.f;
            auto v = sine(0.7f, 150.f, loudFrames); run(g, v);
            std::vector<float> quiet(2 * 256, 0.f);
            const float start = g.reductionDb();
            for (int c = 0; c < 400; ++c) { run(g, quiet); if (g.reductionDb() < start * 0.37f) return (c + 1) * 256 / SR; }
            return 99.f;
        };
        const float fast = recover(2400), slow = recover(144000);
        CHECK(slow > fast * 3.f, "AUTO release: %.0f ms after a short peak, %.0f ms after 3 s of glue", fast * 1000, slow * 1000);
    }
    {
        // a sudden hit never makes it misbehave; extreme settings stay finite
        Glue g; g.s.on = true; g.s.thresholdDb = -48.f; g.s.ratio = 20.f; g.s.attackMs = 0.1f; g.s.makeupDb = 24.f; g.s.scHpHz = 120.f;
        auto v = sine(1.f, 60.f, 48000); run(g, v);
        bool fin = true; for (float s : v) fin &= std::isfinite(s);
        CHECK(fin, "extreme GLUE settings with the side-chain HP: finite output");
    }

    // ================= ranges =================
    {
        GlueSettings gs; LimSettings ls;
        CHECK(set(gs, GRatio, 4.f) && !set(gs, GRatio, 0.5f) && !set(gs, GThreshDb, 3.f) && set(gs, GReleaseMs, 0.f) && !set(gs, GReleaseMs, 5.f),
              "GLUE ranges (ratio ≥ 1, threshold ≤ 0, release 0 = AUTO or 20..2000)");
        CHECK(set(ls, LCeilingDb, -0.1f) && !set(ls, LCeilingDb, 1.f) && !set(ls, LInputDb, NAN) && !set(ls, kLimParams, 0.f),
              "LIMIT ranges (ceiling ≤ 0, no NaN, unknown ids refused)");
    }

    if (fails) { std::printf("%d FAILED\n", fails); return 1; }
    std::printf("master: all passed\n");
    return 0;
}
