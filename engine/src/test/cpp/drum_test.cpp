// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the drum synth DSP.
#include "DrumSynth.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace drums;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[128]; std::snprintf(b_, 128, __VA_ARGS__); \
    std::printf("%-64s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr int SR = 48000;

static std::vector<float> run(DrumSynth& d, int frames) {
    std::vector<float> out(frames * 2, 0.f);
    for (int off = 0; off < frames; off += 192) d.render(out.data() + 2 * off, std::min(192, frames - off));
    return out;
}
static double rms(const std::vector<float>& x, size_t a, size_t b) { double s = 0; for (size_t i = a; i < b; i += 2) s += x[i] * x[i]; return std::sqrt(s / ((b - a) / 2)); }
static double peak(const std::vector<float>& x) { double m = 0; for (float v : x) m = std::max(m, (double)std::fabs(v)); return m; }
static bool finite(const std::vector<float>& x) { for (float v : x) if (!std::isfinite(v)) return false; return true; }
// Zero-crossing rate (Hz) of the left channel over [a, b) frames.
static double zc(const std::vector<float>& x, int a, int b) { int z = 0; for (int i = a + 1; i < b; ++i) if (x[2 * (i - 1)] < 0 && x[2 * i] >= 0) ++z; return z / ((b - a) / (double)SR); }

static DrumSynth one(int model, float decay = 400.f) {
    DrumSynth d; d.setSampleRate(SR);
    for (int s = 0; s < kSlots; ++s) d.setParam(s, ModelId, (float)model);
    d.setParam(0, Decay, decay); d.setParam(0, Choke, 0);
    return d;
}

int main() {
    for (int m = 0; m < kModelCount; ++m) {
        DrumSynth d = one(m);
        d.trigger(0, 1.f);
        auto o = run(d, SR);
        const double early = rms(o, 0, 2 * 4800), late = rms(o, 2 * 36000, 2 * 48000);
        CHECK(finite(o) && peak(o) > 0.03 && peak(o) < 4.0, "model %d: audible and bounded (peak %.2f)", m, peak(o));
        CHECK(late < early * 0.1, "model %d: decays (early %.3f → late %.4f)", m, early, late);
        CHECK(!d.active(0), "model %d: voice frees itself after the tail", m);
    }
    {   // Kick pitch sweep: high at the start, near base pitch later.
        DrumSynth d = one(Kick, 800); d.setParam(0, Snap, 1.f); d.setParam(0, Tone, 0.f);
        d.trigger(0, 1.f); auto o = run(d, SR / 2);
        double f0 = zc(o, 200, 1400), f1 = zc(o, 9600, 19200);
        CHECK(f0 > 2.0 * f1 && f1 > 35 && f1 < 70, "kick: pitch sweeps down (%.0f Hz → %.0f Hz)", f0, f1);
    }
    {   // TUNE moves the pitch by semitones.
        DrumSynth a = one(Tom, 1500), b = one(Tom, 1500); b.setParam(0, Tune, 12.f);
        a.trigger(0, 1.f); b.trigger(0, 1.f);
        auto oa = run(a, SR / 2), ob = run(b, SR / 2);
        double r = zc(ob, 12000, 24000) / zc(oa, 12000, 24000);
        CHECK(std::fabs(r - 2.0) < 0.15, "tom: +12 semitones doubles the pitch (×%.2f)", r);
    }
    {   // DECAY: longer decay → more energy late.
        DrumSynth a = one(Snare, 100), b = one(Snare, 800);
        a.trigger(0, 1.f); b.trigger(0, 1.f);
        auto oa = run(a, SR / 2), ob = run(b, SR / 2);
        CHECK(rms(ob, 2 * 9600, 2 * 14400) > 5 * rms(oa, 2 * 9600, 2 * 14400), "snare: decay knob lengthens the tail");
    }
    {   // Choke: the closed hat cuts the open hat.
        DrumSynth d; d.setSampleRate(SR);
        d.setParam(3, ModelId, Hat); d.setParam(4, ModelId, Hat); d.setParam(4, Decay, 2000);
        d.setParam(3, Choke, 1); d.setParam(4, Choke, 1); d.setParam(3, Decay, 40);
        d.trigger(4, 1.f); run(d, 4800);
        d.trigger(3, 1.f); run(d, 4800);
        CHECK(!d.active(4), "choke group: closed hat silences the open hat");
    }
    {   // Retrigger fades instead of clicking: no sample-to-sample jump bigger than the hit itself.
        DrumSynth d = one(Kick, 1000);
        d.trigger(0, 1.f); auto a = run(d, 2000);
        d.trigger(0, 1.f); auto b = run(d, 2000);
        CHECK(finite(b) && peak(b) < 2.5, "retrigger stays bounded (peak %.2f)", peak(b));
        CHECK(d.hitCount(0) == 2, "hit counter counts triggers");
    }
    {   // Pan + level.
        DrumSynth d = one(Kick); d.setParam(0, Pan, -1.f);
        d.trigger(0, 1.f); auto o = run(d, 4800);
        double l = 0, r = 0; for (int i = 0; i < 4800; ++i) { l += o[2 * i] * o[2 * i]; r += o[2 * i + 1] * o[2 * i + 1]; }
        CHECK(l > 0.01 && r < 1e-6, "pan hard left");
        DrumSynth q = one(Kick); q.setParam(0, LevelDb, -40.f); q.trigger(0, 1.f);
        CHECK(peak(run(q, 4800)) < 0.03, "level -40 dB is quiet");
    }
    {   // Garbage in: NaN, out-of-range slot/param are ignored, values are clamped.
        DrumSynth d = one(Kick);
        d.setParam(0, Tune, NAN); d.setParam(9, Tune, 1); d.setParam(0, 42, 1); d.setParam(0, Decay, 1e9f);
        d.trigger(-1, 1); d.trigger(8, 1); d.trigger(0, 1);
        CHECK(d.param(0, Decay) == 4000.f && std::isfinite(d.param(0, Tune)) && finite(run(d, 4800)), "invalid input ignored / clamped");
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
