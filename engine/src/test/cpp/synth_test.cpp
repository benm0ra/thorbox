// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the synth DSP.  g++ -std=c++20 -O2 -I../../main/cpp synth_test.cpp -o st && ./st
#include "Synth.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
using namespace synth;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[128]; std::snprintf(b_, 128, __VA_ARGS__); \
    std::printf("%-60s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr int SR = 48000;

static std::vector<float> run(Synth& s, int frames, std::vector<float>* mono = nullptr) {
    std::vector<float> out(frames * 2, 0.f), m(frames, 0.f);
    for (int off = 0; off < frames; off += 192) {
        int n = std::min(192, frames - off);
        s.render(out.data() + 2 * off, m.data() + off, n);
    }
    if (mono) *mono = m;
    return out;
}
static double rms(const std::vector<float>& x, size_t a = 0) { double s = 0; for (size_t i = a; i < x.size(); ++i) s += x[i] * x[i]; return std::sqrt(s / (x.size() - a)); }
// High-frequency content: RMS of the first difference (≈ a +6 dB/oct tilt).
static double hfRms(const std::vector<float>& x, size_t a) { double s = 0; for (size_t i = a + 2; i < x.size(); i += 2) { double d = x[i] - x[i - 2]; s += d * d; } return std::sqrt(s / ((x.size() - a) / 2)); }
static bool allFinite(const std::vector<float>& x) { for (float v : x) if (!std::isfinite(v)) return false; return true; }
static double zcFreq(const std::vector<float>& m, size_t a) { int z = 0; for (size_t i = a + 1; i < m.size(); ++i) if (m[i - 1] < 0 && m[i] >= 0) ++z; return z / ((m.size() - a) / (double)SR); }

static Synth openSaw() {
    Synth s; s.setSampleRate(SR);
    s.setParam(Osc1Shape, 0.666f); s.setParam(OscMix, 0.f); s.setParam(CutoffHz, 20000); s.setParam(FilterEnvAmt, 0);
    s.setParam(KeyTrack, 0); s.setParam(LfoDepth, 0); s.setParam(AmpA, 1); s.setParam(AmpS, 1);
    return s;
}

int main() {
    { Synth s = openSaw(); s.noteOn(69, 1.f); std::vector<float> m; auto o = run(s, SR, &m);
      double f = zcFreq(m, SR / 10);
      CHECK(std::fabs(f - 440.0) < 4.0, "A4 saw pitch: %.1f Hz", f);
      CHECK(allFinite(o) && rms(o) > 0.05, "output finite and audible (rms %.3f)", rms(o)); }

    for (float sh : {0.f, 0.333f, 0.666f, 1.f}) {
        Synth s = openSaw(); s.setParam(Osc1Shape, sh); s.noteOn(57, 1.f); std::vector<float> m; run(s, SR / 2, &m);
        double f = zcFreq(m, SR / 20);
        CHECK(std::fabs(f - 220.0) < 4.0, "shape %.2f keeps pitch (%.1f Hz)", sh, f);
    }

    { Synth a = openSaw(), b = openSaw(); b.setParam(CutoffHz, 300); a.noteOn(48, 1); b.noteOn(48, 1);
      double ra = hfRms(run(a, SR / 2), SR / 4), rb = hfRms(run(b, SR / 2), SR / 4);
      CHECK(rb < ra * 0.25, "LPF at 300 Hz removes a C3 saw's upper harmonics (%.4f → %.4f)", ra, rb); }

    { Synth s = openSaw(); s.setParam(Resonance, 1.f); s.setParam(CutoffHz, 20); s.noteOn(36, 1);
      auto o = run(s, SR);
      s.setParam(CutoffHz, 20000); auto o2 = run(s, SR);
      CHECK(allFinite(o) && allFinite(o2), "max resonance + extreme cutoff sweep stays finite"); }

    { Synth s = openSaw(); s.setParam(AmpR, 50); s.noteOn(60, 1); run(s, SR / 4); s.noteOff(60);
      run(s, SR / 2);
      CHECK(!s.anyActive(), "voice frees itself after a 50 ms release"); }

    { Synth s = openSaw(); s.setParam(AmpA, 200); s.noteOn(60, 1);
      run(s, SR * 150 / 1000);
      float l150 = s.telemetry().ampLevel; int st150 = (int)s.telemetry().ampStage;
      run(s, SR * 100 / 1000);
      CHECK(l150 > 0.5f && l150 < 1.f && st150 == Adsr::Attack, "200 ms attack still rising at 150 ms (%.2f)", l150);
      CHECK((int)s.telemetry().ampStage != Adsr::Attack, "…and finished by 250 ms (stage %d)", (int)s.telemetry().ampStage); }

    { Synth s = openSaw(); for (int n = 40; n < 52; ++n) s.noteOn(n, 0.8f); run(s, SR / 10);
      CHECK((int)s.telemetry().activeVoices == kVoices, "12 notes on 8 voices → 8 active (stealing)");
      auto o = run(s, SR / 10); CHECK(allFinite(o), "steal is finite"); }

    { Synth s = openSaw(); s.noteOn(64, 1); run(s, 512);
      const auto& t = s.telemetry();
      CHECK((t.notes[1] >> 0 & 1ull) == 1 && t.newestNote == 64, "telemetry note mask has E4 (64)"); }

    { // LFO: tremolo modulates amplitude
      Synth s = openSaw(); s.setParam(LfoTarget, 2); s.setParam(LfoDepth, 1); s.setParam(LfoRateHz, 4); s.noteOn(60, 1);
      std::vector<float> m; run(s, SR, &m);
      double lo = 1e9, hi = 0; for (int w = 0; w < 16; ++w) { double r = 0; for (int i = w * 1500; i < w * 1500 + 600; ++i) r += m[i] * m[i]; lo = std::min(lo, r); hi = std::max(hi, r); }
      CHECK(hi > lo * 4, "LFO→AMP produces tremolo (energy ratio %.1f)", hi / std::max(lo, 1e-12)); }

    { Synth s; s.setParam(-1, 1); s.setParam(kParamCount, 1); s.setParam(CutoffHz, 1e9f);
      CHECK(s.param(CutoffHz) == 20000.f, "out-of-range params are clamped / ignored"); }

    { Synth s; s.setParam(CutoffHz, 2000); s.lockParam(CutoffHz, 300);
      bool locked = s.param(CutoffHz) == 300.f;
      s.setParam(CutoffHz, 5000);                       // UI moves the knob during a locked step
      bool held = s.param(CutoffHz) == 300.f;
      s.unlockAll();
      CHECK(locked && held && s.param(CutoffHz) == 5000.f, "p-lock overrides, holds through knob moves, unlock restores the newest base"); }

    { // CPU: 8 voices, worst-case settings, 10 s of audio
      Synth s = openSaw(); s.setParam(Osc2Shape, 1.f); s.setParam(OscMix, 0.5f); s.setParam(LfoDepth, 1); s.setParam(LfoTarget, 1);
      for (int n = 48; n < 56; ++n) s.noteOn(n, 1);
      auto t0 = std::chrono::steady_clock::now(); run(s, SR * 10);
      double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      CHECK(ms < 1000.0, "8 voices × 10 s rendered in %.0f ms (%.1f%% of real time, desktop)", ms, ms / 100.0); }

    // ---- 0.7 additions: pitch envelope, glide, mono legato, drive, noise ----
    {   Synth s = openSaw(); s.setParam(Osc1Shape, 0.f); s.setParam(PitchEnvAmt, 24.f); s.setParam(PitchEnvDecay, 60.f);
        s.noteOn(45, 1.f); std::vector<float> m; run(s, SR / 2, &m);
        std::vector<float> a(m.begin(), m.begin() + 1200), b(m.begin() + 12000, m.begin() + 24000);
        double f0 = zcFreq(a, 0), f1 = zcFreq(b, 0);
        CHECK(f0 > 1.6 * f1 && std::fabs(f1 - 110.0) < 4.0, "pitch env: starts high, settles on A2 (%.0f → %.1f Hz)", f0, f1); }
    {   Synth s = openSaw(); s.setParam(Mono, 1.f); s.setParam(GlideMs, 200.f);
        s.noteOn(57, 1.f); run(s, SR / 4); s.noteOn(69, 1.f);
        std::vector<float> m; run(s, SR / 2, &m);
        std::vector<float> early(m.begin(), m.begin() + 1440), late(m.begin() + 14400, m.end());
        double fe = zcFreq(early, 0), fl = zcFreq(late, 0);
        CHECK(fe < 330.0 && std::fabs(fl - 440.0) < 5.0, "glide: slides from A3 up to A4 (%.0f → %.1f Hz)", fe, fl);
        CHECK(s.telemetry().activeVoices == 1, "mono: one voice for two held notes");
        s.noteOff(69); std::vector<float> m2; run(s, SR / 2, &m2);
        std::vector<float> back(m2.begin() + 14400, m2.end());
        CHECK(std::fabs(zcFreq(back, 0) - 220.0) < 4.0, "mono: releasing the top key returns to the held one (%.1f Hz)", zcFreq(back, 0));
        s.noteOff(57); run(s, SR);
        CHECK(!s.anyActive(), "mono: all keys up → silent"); }
    {   Synth a = openSaw(), b = openSaw(); a.setParam(Osc1Shape, 0.f); b.setParam(Osc1Shape, 0.f); b.setParam(Drive, 1.f);
        a.noteOn(57, 1.f); b.noteOn(57, 1.f);
        auto oa = run(a, SR / 2), ob = run(b, SR / 2);
        CHECK(hfRms(ob, SR / 4) > 1.5 * hfRms(oa, SR / 4) && allFinite(ob), "drive adds harmonics to a sine (%.3f vs %.3f)", hfRms(ob, SR / 4), hfRms(oa, SR / 4)); }
    {   Synth s = openSaw(); s.setParam(OscMix, 0.f); s.setParam(Osc1Shape, 0.f); s.setParam(Noise, 1.f);
        s.noteOn(30, 1.f); auto o = run(s, SR / 2);
        Synth q = openSaw(); q.setParam(Osc1Shape, 0.f); q.noteOn(30, 1.f); auto oq = run(q, SR / 2);
        CHECK(hfRms(o, SR / 4) > 5.0 * hfRms(oq, SR / 4), "noise knob adds broadband noise"); }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
