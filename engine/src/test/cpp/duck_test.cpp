// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the DUCK sidechain compressor: the gain computer's maths, attack / release timing,
// MIX and MAKEUP, and the ghost beat pulse.
#include "Ducker.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace duck;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-70s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f;
static float db(float g) { return 20.f * std::log10(std::max(1e-9f, g)); }

// run [n] frames of a constant key level through the ducker; returns the gains
static std::vector<float> run(Ducker& d, float keyLevel, int n) {
    std::vector<float> key(n, keyLevel), g(n);
    for (int off = 0; off < n; off += 256) d.process(key.data() + off, std::min(256, n - off), SR, g.data() + off);
    return g;
}

int main() {
    // ---- static curve: above the knee, reduction = over · (1 − 1/ratio) ----
    CHECK(std::fabs(reductionDb(-6.f, -24.f, 4.f) - 13.5f) < 1e-3f, "4:1, 18 dB over → 13.5 dB reduction");
    CHECK(reductionDb(-40.f, -24.f, 4.f) == 0.f, "below the threshold (and the knee): no reduction");
    CHECK(reductionDb(-24.f, -24.f, 4.f) > 0.f && reductionDb(-24.f, -24.f, 4.f) < 1.f, "at the threshold: inside the soft knee (%.2f dB)", reductionDb(-24.f, -24.f, 4.f));
    CHECK(std::fabs(reductionDb(0.f, -30.f, 20.f) - 28.5f) < 1e-3f, "20:1 behaves like a limiter (28.5 dB off 30 over)");
    CHECK(reductionDb(0.f, -30.f, 1.f) == 0.f, "1:1 never ducks");

    // ---- steady state + attack ----
    Ducker d;
    d.s.source = 0; d.s.targets = 2; d.s.thresholdDb = -24.f; d.s.ratio = 4.f; d.s.attackMs = 5.f; d.s.releaseMs = 200.f; d.s.mix = 1.f;
    auto g = run(d, 0.5f, 48000);                                   // key at −6 dBFS for 1 s
    const float want = -reductionDb(db(0.5f), -24.f, 4.f);
    CHECK(std::fabs(db(g.back()) - want) < 0.05f, "steady state: %.2f dB (want %.2f)", db(g.back()), want);
    // after one attack time (5 ms = 240 frames) the reduction is ~63 % of the way there
    Ducker a; a.s = d.s;
    auto ga = run(a, 0.5f, 240);
    const float frac = db(ga.back()) / want;
    CHECK(frac > 0.55f && frac < 0.70f, "attack: 63 %% of the way after ATTACK ms (%.0f %%)", frac * 100);

    // ---- release: back to ~37 % of the reduction after RELEASE ms ----
    const float before = -db(g.back());
    auto gr = run(d, 0.f, (int)(0.2f * SR));
    const float left = -db(gr.back()) / before;
    CHECK(left > 0.30f && left < 0.44f, "release: %.0f %% of the reduction left after RELEASE ms (want ~37 %%)", left * 100);
    auto gr2 = run(d, 0.f, 48000);
    CHECK(db(gr2.back()) > -0.05f, "fully back up after the key stops (%.2f dB)", db(gr2.back()));

    // ---- MIX and MAKEUP ----
    Ducker m; m.s = d.s; m.s.mix = 0.5f;
    auto gm = run(m, 0.5f, 48000);
    const float full = std::pow(10.f, want / 20.f);
    CHECK(std::fabs(gm.back() - (1.f - 0.5f * (1.f - full))) < 1e-3f, "MIX 50 %%: half the gain change (%.3f)", gm.back());
    Ducker mk; mk.s = d.s; mk.s.makeupDb = 6.f;
    auto gq = run(mk, 0.f, 4800);
    CHECK(std::fabs(db(gq.back()) - 6.f) < 0.05f, "MAKEUP +6 dB with no key: the targets come up 6 dB");

    // ---- triggers: one per crossing (with hysteresis), telemetry max ----
    Ducker t; t.s = d.s;
    run(t, 0.f, 4800); run(t, 0.5f, 2400); run(t, 0.f, 24000); run(t, 0.5f, 2400); run(t, 0.f, 24000);
    CHECK(t.triggers == 2, "two kicks → two triggers (%u)", t.triggers);
    CHECK(t.grMax > 5.f, "telemetry keeps the deepest reduction (%.1f dB)", t.grMax);

    // ---- the ghost beat ----
    const double tps = 120.0 / 60.0 * 960.0 / SR;                  // 120 BPM
    CHECK(beatPulse(0.0, 960.0, SR, tps) > 0.99f, "beat pulse: full on the beat");
    CHECK(beatPulse(480.0, 960.0, SR, tps) < 0.01f, "beat pulse: gone by the off-beat");
    CHECK(beatPulse(0.0, 960.0, SR, 0.0) == 0.f, "beat pulse: silent when stopped");

    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails ? 1 : 0;
}
