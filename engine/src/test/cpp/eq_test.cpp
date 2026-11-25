// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the channel EQ: each band's response at known frequencies, slopes, OFF gliding
// to a true bypass, a flat strip touching nothing, and the range checks on incoming values.
#include "ChannelEq.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace eq;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-70s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f;

// gain in dB of [e] at [hz]: settle 0.5 s, then compare RMS over 0.25 s (both channels must agree)
static float gainDb(ChannelEq& e, float hz) {
    const int settle = 24000, meas = 12000, n = settle + meas;
    std::vector<float> lr(2 * n);
    for (int i = 0; i < n; ++i) lr[2 * i] = lr[2 * i + 1] = 0.5f * std::sin(2.f * (float)M_PI * hz * i / SR);
    for (int off = 0; off < n; off += 256) e.process(lr.data() + 2 * off, std::min(256, n - off), SR);
    double in = 0, outL = 0, outR = 0;
    for (int i = settle; i < n; ++i) {
        const double x = 0.5 * std::sin(2.0 * M_PI * hz * i / SR);
        in += x * x; outL += lr[2 * i] * lr[2 * i]; outR += lr[2 * i + 1] * lr[2 * i + 1];
    }
    if (std::fabs(outL - outR) > 1e-6 * (outL + 1e-12)) return 999.f;   // L and R must match
    return (float)(10.0 * std::log10(outL / in));
}
static ChannelEq with(void (*f)(Settings&)) { ChannelEq e; f(e.s); return e; }
static bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

int main() {
    // ---- a flat strip is a wire ----
    {
        ChannelEq e;
        std::vector<float> lr(512), ref(512);
        for (int i = 0; i < 512; ++i) lr[i] = ref[i] = std::sin(i * 0.37f);
        e.process(lr.data(), 256, SR);
        bool same = true; for (int i = 0; i < 512; ++i) same &= lr[i] == ref[i];
        CHECK(same && !e.active(), "flat (default) settings: bit-identical, no band running");
    }

    // ---- high-pass ----
    {
        auto e = with([](Settings& s) { s.hpHz = 1000.f; });
        const float at = gainDb(e, 1000.f), lo = gainDb(e, 125.f), hi = gainDb(e, 8000.f);
        CHECK(near(at, -3.01f, 0.3f), "HP 12 @1k: −3 dB at the corner (%.2f)", at);
        CHECK(near(lo, -36.f, 1.5f), "HP 12 @1k: ~−36 dB three octaves down (%.1f)", lo);
        CHECK(near(hi, 0.f, 0.2f), "HP 12 @1k: flat three octaves up (%.2f)", hi);
    }
    {
        auto e = with([](Settings& s) { s.hpHz = 1000.f; s.hp24 = true; });
        const float at = gainDb(e, 1000.f), lo = gainDb(e, 250.f);
        CHECK(near(at, -3.01f, 0.3f), "HP 24 @1k: −3 dB at the corner (%.2f)", at);
        CHECK(near(lo, -48.f, 1.5f), "HP 24 @1k: ~−48 dB two octaves down (%.1f)", lo);
    }

    // ---- low-pass ----
    {
        auto e = with([](Settings& s) { s.lpHz = 1000.f; });
        const float at = gainDb(e, 1000.f), hi = gainDb(e, 8000.f), lo = gainDb(e, 125.f);
        CHECK(near(at, -3.01f, 0.3f), "LP 12 @1k: −3 dB at the corner (%.2f)", at);
        CHECK(hi < -34.f && hi > -40.f, "LP 12 @1k: ~−36 dB three octaves up (%.1f)", hi);
        CHECK(near(lo, 0.f, 0.2f), "LP 12 @1k: flat three octaves down (%.2f)", lo);
    }
    {
        auto e = with([](Settings& s) { s.lpHz = 1000.f; s.lp24 = true; });
        const float hi = gainDb(e, 4000.f);
        CHECK(hi < -45.f && hi > -51.f, "LP 24 @1k: ~−48 dB two octaves up (%.1f)", hi);
    }

    // ---- the three EQ bands ----
    {
        auto e = with([](Settings& s) { s.midDb = 6.f; s.midHz = 1000.f; s.midQ = 1.f; });
        const float at = gainDb(e, 1000.f), far = gainDb(e, 60.f);
        CHECK(near(at, 6.f, 0.2f), "MID +6 @1k: +6 dB at the centre (%.2f)", at);
        CHECK(near(far, 0.f, 0.3f), "MID +6 @1k: ~0 dB four octaves away (%.2f)", far);
    }
    {
        auto e = with([](Settings& s) { s.midDb = -12.f; s.midHz = 300.f; s.midQ = 4.f; });
        const float at = gainDb(e, 300.f), oct = gainDb(e, 600.f);
        CHECK(near(at, -12.f, 0.3f), "MID −12 @300 Q4: −12 dB cut (%.2f)", at);
        CHECK(oct > -2.f, "MID Q4 is narrow: an octave away is almost untouched (%.2f)", oct);
    }
    {
        auto e = with([](Settings& s) { s.lowDb = 6.f; s.lowHz = 100.f; });
        const float sub = gainDb(e, 25.f), top = gainDb(e, 5000.f);
        CHECK(near(sub, 6.f, 0.4f), "LOW shelf +6 @100: +6 dB in the sub (%.2f)", sub);
        CHECK(near(top, 0.f, 0.2f), "LOW shelf +6 @100: flat up top (%.2f)", top);
    }
    {
        auto e = with([](Settings& s) { s.highDb = -6.f; s.highHz = 6000.f; });
        const float air = gainDb(e, 18000.f), bass = gainDb(e, 100.f);
        CHECK(near(air, -6.f, 0.5f), "HIGH shelf −6 @6k: −6 dB in the air (%.2f)", air);
        CHECK(near(bass, 0.f, 0.2f), "HIGH shelf −6 @6k: bass untouched (%.2f)", bass);
    }

    // ---- OFF: glides to neutral, then a true bypass ----
    {
        auto e = with([](Settings& s) { s.hpHz = 500.f; s.midDb = 9.f; s.lpHz = 2000.f; });
        gainDb(e, 1000.f);
        CHECK(e.active(), "bands running while ON");
        e.s.on = false;
        const float g = gainDb(e, 1000.f);
        CHECK(!e.active() && near(g, 0.f, 0.01f), "OFF: every band glided out and stopped (%.3f dB)", g);
        e.s.on = true;
        CHECK(near(gainDb(e, 1000.f), 9.f - 0.f, 1.5f), "back ON: the settings return (mid boost audible)");
    }

    // ---- smoothing: a jump is a short glide, not a step ----
    {
        // 200 Hz through an HP that jumps from off to 2 kHz: settled, that's ~−40 dB; in the first
        // chunk after the jump the working corner has only glided part of the way, so 200 Hz still passes
        ChannelEq e;
        auto chunkDb = [&](int c0) {
            std::vector<float> lr(2 * 256);
            double in = 0, out = 0;
            for (int i = 0; i < 256; ++i) { const float x = std::sin(2.f * (float)M_PI * 200.f * (c0 * 256 + i) / SR); lr[2 * i] = lr[2 * i + 1] = x; in += x * x; }
            e.process(lr.data(), 256, SR);
            for (int i = 0; i < 256; ++i) out += lr[2 * i] * lr[2 * i];
            return (float)(10.0 * std::log10(out / in));
        };
        chunkDb(0);
        e.s.hpHz = 2000.f;
        const float first = chunkDb(1);
        float last = 0.f; for (int c = 2; c < 200; ++c) last = chunkDb(c);
        CHECK(first > -6.f, "first chunk after a jump to HP 2k: 200 Hz still mostly there (%.1f dB)", first);
        CHECK(last < -30.f, "after the glide: 200 Hz is cut (%.1f dB)", last);
    }

    // ---- range checks ----
    {
        Settings s;
        CHECK(!set(s, HpHz, 10.f) && !set(s, HpHz, 5000.f) && set(s, HpHz, 80.f) && s.hpHz == 80.f, "HP Hz: 20..4000 only");
        CHECK(!set(s, MidDb, 30.f) && !set(s, MidDb, NAN) && set(s, MidDb, -18.f), "gains: ±18 dB, no NaN");
        CHECK(!set(s, MidQ, 0.f) && set(s, MidQ, 2.f), "Q: 0.2..10");
        CHECK(!set(s, kParams, 1.f) && !set(s, -1, 1.f), "unknown ids rejected");
        CHECK(set(s, HpSlope, 24.f) && s.hp24 && set(s, HpSlope, 12.f) && !s.hp24, "slope: 12 / 24");
    }

    // ---- stability: extreme settings on a full-scale square stay finite ----
    {
        auto e = with([](Settings& s) { s.hpHz = 4000.f; s.hp24 = true; s.lpHz = 100.f; s.lp24 = true; s.lowDb = 18.f; s.lowHz = 30.f;
            s.midDb = 18.f; s.midHz = 12000.f; s.midQ = 10.f; s.highDb = 18.f; s.highHz = 18000.f; });
        std::vector<float> lr(2 * 48000);
        for (int i = 0; i < 48000; ++i) lr[2 * i] = lr[2 * i + 1] = (i / 40) % 2 ? 1.f : -1.f;
        for (int off = 0; off < 48000; off += 256) e.process(lr.data() + 2 * off, std::min(256, 48000 - off), SR);
        bool finite = true; for (float v : lr) finite &= std::isfinite(v) && std::fabs(v) < 100.f;
        CHECK(finite, "every band at its extreme: output stays finite and bounded");
    }

    if (fails) { std::printf("%d FAILED\n", fails); return 1; }
    std::printf("eq: all passed\n");
    return 0;
}
