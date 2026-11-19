// SPDX-License-Identifier: GPL-3.0-or-later
#include "Stretch.h"
#include <chrono>
#include <cmath>
#include <cstdio>
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[180]; std::snprintf(b_, 180, __VA_ARGS__); std::printf("%-70s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
static double freqOf(const std::vector<float>& x, int ch, int sr) {   // zero crossings over the middle 80%
    size_t n = x.size() / ch, a = n / 10, b = n - n / 10; int z = 0;
    for (size_t i = a + 1; i < b; ++i) if (x[(i - 1) * ch] < 0 && x[i * ch] >= 0) ++z;
    return z / ((b - a) / (double)sr);
}
int main() {
    const int sr = 48000;
    std::vector<float> sine(sr * 2 * 2);    // 2 s stereo 440 Hz
    for (int i = 0; i < sr * 2; ++i) sine[2 * i] = sine[2 * i + 1] = 0.5f * (float)std::sin(2 * M_PI * 440 * i / sr);
    struct Case { double tempo, semis, expLen, expHz; };
    Case cs[] = {{0.5, 0, 4.0, 440}, {1.5, 0, 2.0 / 1.5, 440}, {1.0, 12, 2.0, 880}, {1.0, -5, 2.0, 440 * std::pow(2, -5 / 12.0)},
                 {140.0 / 93.0, 4, 2.0 / (140.0 / 93.0), 440 * std::pow(2, 4 / 12.0)}};
    for (auto& c : cs) {
        auto out = stretch::process(sine, 2, sr, c.tempo, c.semis);
        double len = out.size() / 2.0 / sr, hz = freqOf(out, 2, sr);
        CHECK(std::fabs(len - c.expLen) < 0.01 && std::fabs(hz - c.expHz) < c.expHz * 0.01,
              "tempo ×%.3f, %+.0f st → %.3f s (exp %.3f), %.1f Hz (exp %.1f)", c.tempo, c.semis, len, c.expLen, hz, c.expHz);
    }
    // Rhythm: clicks every 0.25 s stay evenly spaced (at 0.375 s) after a 1.5× slow-down
    std::vector<float> clicks(sr * 2, 0.f);
    for (int k = 0; k < 8; ++k) for (int i = 0; i < 200; ++i) clicks[k * sr / 4 + i] = (float)std::exp(-i / 30.0) * (i % 2 ? 1 : -1) * 0.8f;
    auto sl = stretch::process(clicks, 1, sr, 1.0 / 1.5, 0);
    int found = 0; double maxErr = 0;
    for (int k = 1; k < 8; ++k) {
        const int expect = (int)(k * 0.375 * sr); int best = -1; float bv = 0;
        for (int i = expect - 1500; i < expect + 1500 && i < (int)sl.size(); ++i) if (std::fabs(sl[i]) > bv) { bv = std::fabs(sl[i]); best = i; }
        if (bv > 0.2f) { ++found; maxErr = std::max(maxErr, std::fabs(best - expect) / (double)sr * 1000); }
    }
    CHECK(found == 7 && maxErr < 12.0, "transients keep their timing after stretch (%d/7, worst %.1f ms)", found, maxErr);
    auto same = stretch::process(sine, 2, sr, 1.0, 0.0);
    CHECK(same == sine, "tempo 1 / 0 st is a bit-exact passthrough");
    bool fin = true; for (float v : stretch::process(sine, 2, sr, 0.25, 24)) fin &= std::isfinite(v);
    CHECK(fin, "extreme settings stay finite");
    auto t0 = std::chrono::steady_clock::now();
    std::vector<float> loop(sr * 8 * 2, 0.f); for (size_t i = 0; i < loop.size(); ++i) loop[i] = (float)std::sin(i * 0.01) * 0.3f;
    stretch::process(loop, 2, sr, 1.5, 3);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    CHECK(ms < 2000, "8 s stereo loop stretched in %.0f ms (desktop, -O1 + sanitizers)", ms);
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails); return fails != 0;
}
