// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the BS.1770 / R128 loudness meter.
#include "Loudness.h"
#include <cmath>
#include <cstdio>
using namespace meter;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-64s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)

static void sine(Loudness& m, double sr, double dbfs, double secs, double hz = 1000.0) {
    const double a = std::pow(10.0, dbfs / 20.0);
    static double ph = 0;
    for (long i = 0; i < (long)(secs * sr); ++i) { const float s = (float)(a * std::sin(ph)); ph += 2 * M_PI * hz / sr; m.process(s, s); }
}
static void silence(Loudness& m, double sr, double secs) { for (long i = 0; i < (long)(secs * sr); ++i) m.process(0.f, 0.f); }

int main() {
    for (double sr : {44100.0, 48000.0, 96000.0}) {
        Loudness m; m.setSampleRate(sr);
        sine(m, sr, -23.0, 20.0);
        CHECK(std::fabs(m.momentary() + 23.f) < 0.1f && std::fabs(m.shortTerm() + 23.f) < 0.1f && std::fabs(m.integrated() + 23.f) < 0.1f,
              "%.0f Hz: 1 kHz stereo sine at -23 dBFS reads -23 LUFS (M %.2f S %.2f I %.2f)", sr, m.momentary(), m.shortTerm(), m.integrated());
        CHECK(std::fabs(m.peakDbL() + 23.f) < 0.05f, "%.0f Hz: sample peak -23 dBFS (%.2f)", sr, m.peakDbL());
    }
    {   // absolute gate: silence doesn't drag the integrated value down
        Loudness m; m.setSampleRate(48000); sine(m, 48000, -23, 10); silence(m, 48000, 10);
        CHECK(std::fabs(m.integrated() + 23.f) < 0.1f && m.momentary() <= Loudness::kFloor, "silence is gated out of integrated (%.2f)", m.integrated());
    }
    {   // relative gate (EBU 3341 style): -20 then -40 → the quiet part is gated → -20
        Loudness m; m.setSampleRate(48000); sine(m, 48000, -20, 10); sine(m, 48000, -40, 10);
        CHECK(std::fabs(m.integrated() + 20.f) < 0.15f, "relative gate drops the part 10 LU below (I %.2f)", m.integrated());
    }
    {   // K-weighting: lows count less than mids (100 Hz sine reads quieter than 1 kHz at the same level)
        Loudness a, b; a.setSampleRate(48000); b.setSampleRate(48000);
        sine(a, 48000, -20, 5, 1000); sine(b, 48000, -20, 5, 40);
        CHECK(b.shortTerm() < a.shortTerm() - 1.f, "K-weighting: 40 Hz reads lower than 1 kHz (%.2f vs %.2f)", b.shortTerm(), a.shortTerm());
    }
    {   // reset
        Loudness m; m.setSampleRate(48000); sine(m, 48000, -10, 5); m.reset(); silence(m, 48000, 0.2);
        CHECK(m.integrated() <= Loudness::kFloor && m.peakDbL() <= Loudness::kFloor, "reset clears integrated + peaks");
        sine(m, 48000, -30, 5);
        CHECK(std::fabs(m.integrated() + 30.f) < 0.15f, "and measures afresh (%.2f)", m.integrated());
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
