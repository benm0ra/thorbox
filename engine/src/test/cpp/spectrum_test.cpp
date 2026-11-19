// SPDX-License-Identifier: GPL-3.0-or-later
#include "Spectrum.h"
#include <cmath>
#include <cstdio>
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); std::printf("%-60s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
int main() {
    viz::Spectrum s; float in[viz::kFft], bands[48], b, m, h;
    auto bandOf = [](float f) { return (int)(48 * std::log(f / 30.f) / std::log(16000.f / 30.f)); };
    for (float f : {60.f, 1000.f, 8000.f}) {
        for (int i = 0; i < viz::kFft; ++i) in[i] = std::sin(2 * 3.14159265f * f * i / 48000.f);
        s.analyze(in, 48000.f, bands, 48, &b, &m, &h);
        int peak = 0; for (int i = 1; i < 48; ++i) if (bands[i] > bands[peak]) peak = i;
        CHECK(std::abs(peak - bandOf(f)) <= 1 && bands[peak] > 0.9f, "%5.0f Hz sine peaks in band %d (expected %d), level %.2f", f, peak, bandOf(f), bands[peak]);
        if (f == 60.f) CHECK(b > 0.9f && h < 0.6f, "60 Hz → bass energy high (%.2f), treble low (%.2f)", b, h);
    }
    for (float& v : in) v = 0.f;
    s.analyze(in, 48000.f, bands, 48, &b, &m, &h);
    bool zero = true; for (float v : bands) zero &= v == 0.f;
    CHECK(zero && b == 0.f, "silence → all bands 0");
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails); return fails != 0;
}
