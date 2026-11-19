// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for punch-in master FX.
#include "MasterFx.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace fx;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-64s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f, BPM = 120.f;   // 1/16 = 6000 samples
static long gT = 0;   // running sample clock
// Feeds an ascending ramp-ish "unique" signal so replays are identifiable.
static float src(long t) { return 0.4f * std::sin(t * 0.013f) * std::sin(t * 0.00071f + 1.f) + 0.15f * std::sin(t * 0.61f); }   // 99 Hz-ish body + 4.7 kHz edge
static std::vector<float> run(MasterFx& f, int n, std::vector<float>* dry = nullptr) {
    std::vector<float> io(n * 2);
    for (int i = 0; i < n; ++i) { io[2 * i] = io[2 * i + 1] = src(gT + i); }
    if (dry) *dry = io;
    for (int off = 0; off < n; off += 192) {
        const int m = std::min(192, n - off);
        f.process(io.data() + 2 * off, m, SR, BPM, (gT + off) * 0.04, 0.04);   // 0.04 ticks/sample at 120 BPM
    }
    gT += n;
    return io;
}
static double rms(const std::vector<float>& x) { double s = 0; for (float v : x) s += v * v; return std::sqrt(s / x.size()); }
static double diff(const std::vector<float>& a, const std::vector<float>& b, size_t from = 0) { double s = 0; for (size_t i = from; i < a.size(); ++i) s += std::fabs(a[i] - b[i]); return s; }
static bool finite(const std::vector<float>& x) { for (float v : x) if (!std::isfinite(v)) return false; return true; }

int main() {
    { MasterFx f; std::vector<float> dry; auto out = run(f, 48000, &dry);
      CHECK(diff(out, dry) == 0.0, "all FX off: bit-exact passthrough"); }

    { MasterFx f; run(f, 24000);
      f.engage(Stutter, 0.5f, 0.5f);                      // x=0.5 → 1/16 = 6000 samples
      auto out = run(f, 48000);
      double err = 0; for (int i = 12000; i < 36000; ++i) err += std::fabs(out[2 * i] - out[2 * (i + 6000)]);
      CHECK(err / 24000 < 1e-4, "stutter 1/16 repeats the captured slice every 6000 samples");
      f.release(Stutter); run(f, 1200); std::vector<float> dry; auto back = run(f, 4800, &dry);
      CHECK(diff(back, dry) / back.size() < 1e-3, "release snaps back to dry (within 25 ms)"); }

    { MasterFx f; run(f, 48000); std::vector<float> d; auto pre = run(f, 4800, &d);
      f.engage(Reverse, 0.f, 0.5f);                        // 1 beat = 24000 samples
      auto out = run(f, 12000);
      // out[k] should equal the input from k+1 samples before engagement
      double err = 0; int cnt = 0;
      for (int k = 2000; k < 4600; ++k) /* after the 6 ms crossfade settles */ { err += std::fabs(out[2 * k] - d[2 * (4800 - 1 - k)]); ++cnt; }
      CHECK(err / cnt < 1e-3, "reverse plays the last beat backwards"); }

    { MasterFx f; run(f, 48000); f.engage(TapeStop, 0.5f, 0.f);   // fastest: 80 ms
      run(f, 9600); auto out = run(f, 4800);
      CHECK(rms(out) < 1e-4, "tape stop winds down to silence (rms %.1e)", rms(out)); }

    { MasterFx f; run(f, 4800); f.engage(Filter, 0.05f, 0.2f);   // hard low-pass
      std::vector<float> dry; run(f, 2400); auto out = run(f, 24000, &dry);
      // compare first-difference energy (high-frequency content)
      double hfo = 0, hfd = 0; for (size_t i = 2; i < out.size(); i += 2) { hfo += std::pow(out[i] - out[i - 2], 2); hfd += std::pow(dry[i] - dry[i - 2], 2); }
      CHECK(hfo < hfd * 0.1, "filter sweep left = strong low-pass (HF %.2f%% of dry)", 100 * hfo / hfd);
      f.move(Filter, 0.5f, 0.2f); run(f, 2400); std::vector<float> d2; auto mid = run(f, 4800, &d2);
      CHECK(diff(mid, d2) / mid.size() < 1e-3, "filter at centre is transparent"); }

    { MasterFx f; f.engage(Crush, 1.f, 0.f); run(f, 2400); auto out = run(f, 4800);
      bool quant = true; for (float v : out) { float q = v * 8.f; quant &= std::fabs(q - std::round(q)) < 1e-3f; }
      CHECK(quant, "bitcrush at 3 bits quantizes to 1/8 steps"); }

    { MasterFx f; run(f, 4800); f.engage(Gate, 0.35f, 0.f); run(f, 2400); auto out = run(f, 48000);
      // Windowed RMS (5 ms): count transitions into near-silence. 1/16 at 120 BPM → 8 gaps per second.
      int silentRuns = 0; bool inSilence = false;
      for (size_t w = 0; w + 480 <= out.size(); w += 480) {
          double e = 0; for (size_t i = w; i < w + 480; i += 2) e += out[i] * out[i];
          const bool s = std::sqrt(e / 240) < 0.01;
          if (s && !inSilence) ++silentRuns;
          inSilence = s;
      }
      CHECK(silentRuns >= 7 && silentRuns <= 9, "trance gate chops on the 1/16 grid (%d gaps/s)", silentRuns); }

    { MasterFx f; run(f, 24000); for (int id = 0; id < kCount; ++id) f.engage(id, 0.9f, 0.9f);
      auto out = run(f, 96000); f.releaseAll(); auto after = run(f, 4800);
      CHECK(finite(out) && finite(after), "all seven stacked at extreme settings: finite");
      CHECK(f.activeMask() == 0, "mask clears once every effect has faded out"); }

    { MasterFx f; run(f, 9600); f.engage(Vinyl, 1.f, 1.f); run(f, 2400); std::vector<float> d; auto out = run(f, 48000, &d);
      CHECK(diff(out, d) / out.size() > 1e-3 && finite(out), "vinyl audibly colours the signal"); }

    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
