// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the punch-in master FX.
#include "MasterFx.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace fx;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-70s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f, BPM = 120.f;   // 1/16 = 6000 samples, 0.04 ticks/sample
static long gT = 0;   // running sample clock
// A "unique" signal so replays are identifiable: ~99 Hz body + 4.7 kHz edge.
static float src(long t) { return 0.4f * std::sin(t * 0.013f) * std::sin(t * 0.00071f + 1.f) + 0.15f * std::sin(t * 0.61f); }
static std::vector<float> run(MasterFx& f, int n, std::vector<float>* dry = nullptr, bool silent = false) {
    std::vector<float> io(n * 2);
    for (int i = 0; i < n; ++i) { io[2 * i] = io[2 * i + 1] = silent ? 0.f : src(gT + i); }
    if (dry) *dry = io;
    for (int off = 0; off < n; off += 192) {
        const int m = std::min(192, n - off);
        f.process(io.data() + 2 * off, m, SR, BPM, (gT + off) * 0.04, 0.04);
    }
    gT += n;
    return io;
}
static double rms(const std::vector<float>& x, size_t a = 0, size_t b = 0) {
    if (!b) b = x.size();
    double s = 0;
    for (size_t i = a; i < b; ++i) s += x[i] * x[i];
    return std::sqrt(s / (b - a));
}
static double diff(const std::vector<float>& a, const std::vector<float>& b, size_t from = 0) { double s = 0; for (size_t i = from; i < a.size(); ++i) s += std::fabs(a[i] - b[i]); return s; }
static bool finite(const std::vector<float>& x) { for (float v : x) if (!std::isfinite(v)) return false; return true; }
static double hf(const std::vector<float>& x) { double s = 0; for (size_t i = 2; i < x.size(); i += 2) s += std::pow(x[i] - x[i - 2], 2); return s; }
// Energy of the left channel around one frequency (Goertzel).
static double tone(const std::vector<float>& x, double hz) {
    const double w = 2 * M_PI * hz / SR, c = 2 * std::cos(w); double s1 = 0, s2 = 0;
    for (size_t i = 0; i < x.size(); i += 2) { const double s0 = x[i] + c * s1 - s2; s2 = s1; s1 = s0; }
    return std::sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / (x.size() / 2);
}

int main() {
    { MasterFx f; std::vector<float> dry; auto out = run(f, 48000, &dry);
      CHECK(diff(out, dry) == 0.0, "all FX off: bit-exact passthrough"); }

    // ---- LOOPER ----
    { MasterFx f; run(f, 24000);
      f.engage(Looper, 0.5f, 0.5f);                       // x=0.5 → 1/16 = 6000 samples, 1× forward
      auto out = run(f, 48000);
      double err = 0; for (int i = 12000; i < 36000; ++i) err += std::fabs(out[2 * i] - out[2 * (i + 6000)]);
      CHECK(err / 24000 < 1e-4, "looper 1/16 repeats the captured slice every 6000 samples");
      f.release(Looper); run(f, 1200); std::vector<float> dry; auto back = run(f, 4800, &dry);
      CHECK(diff(back, dry) / back.size() < 1e-3, "release snaps back to dry (within 25 ms)"); }
    { MasterFx f; run(f, 48000); std::vector<float> d; run(f, 4800, &d);
      f.engage(Looper, 0.f, 0.4f);                        // 1 beat, Y=0.4 → reverse at 1×
      auto out = run(f, 12000);
      double err = 0; int cnt = 0;
      for (int k = 2000; k < 4600; ++k) { err += std::fabs(out[2 * k] - d[2 * (4800 - 1 - k)]); ++cnt; }
      CHECK(err / cnt < 1e-3, "looper low = the last beat backwards"); }
    { MasterFx f; run(f, 24000); f.engage(Looper, 0.f, 1.f);   // 1 beat, 2× forward (first pass clamps to live)
      auto out = run(f, 72000);
      CHECK(finite(out) && rms(out) > 0.05, "looper 2× plays and stays bounded"); }

    // ---- TAPE STOP / BACKSPIN ----
    { MasterFx f; run(f, 48000); f.engage(TapeStop, 0.2f, 0.f);   // stop, fastest: 80 ms
      run(f, 9600); auto out = run(f, 4800);
      CHECK(rms(out) < 1e-4, "tape stop winds down to silence (rms %.1e)", rms(out)); }
    { MasterFx f; run(f, 48000); f.engage(TapeStop, 0.8f, 0.f);   // backspin, 80 ms
      auto early = run(f, 1200); run(f, 9600); auto out = run(f, 4800);
      CHECK(finite(early) && rms(early) > 0.01 && rms(out) < 1e-4, "backspin whips back then fades out"); }

    // ---- FILTER + DRIVE ----
    { MasterFx f; run(f, 4800); f.engage(Filter, 0.05f, 0.2f);   // hard low-pass
      std::vector<float> dry; run(f, 2400); auto out = run(f, 24000, &dry);
      CHECK(hf(out) < hf(dry) * 0.1, "filter left = strong low-pass (HF %.2f%% of dry)", 100 * hf(out) / hf(dry));
      f.move(Filter, 0.5f, 0.2f); run(f, 2400); std::vector<float> d2; auto mid = run(f, 4800, &d2);
      CHECK(diff(mid, d2) / mid.size() < 1e-3, "filter at centre (no drive) is transparent");
      f.move(Filter, 0.5f, 1.f); run(f, 2400); std::vector<float> d3; auto dr = run(f, 4800, &d3);
      CHECK(diff(dr, d3) / dr.size() > 1e-3 && finite(dr), "filter top = drive colours even when open"); }

    // ---- LO-FI ----
    { MasterFx f; f.engage(LoFi, 0.f, 1.f); run(f, 2400); auto out = run(f, 4800);
      bool quant = true; for (float v : out) { float q = v * 16.f; quant &= std::fabs(q - std::round(q)) < 1e-3f; }
      CHECK(quant, "lo-fi Y top quantizes to 4 bits (1/16 steps)");
      f.move(LoFi, 1.f, 0.f); run(f, 2400); std::vector<float> d; auto lo = run(f, 24000, &d);
      CHECK(hf(lo) < hf(d) * 0.2, "lo-fi X right = low sample rate, filtered (HF %.1f%%)", 100 * hf(lo) / hf(d)); }

    // ---- SLICER ----
    { MasterFx f; run(f, 4800); f.engage(Slicer, 0.f, 0.3f); run(f, 2400); auto out = run(f, 48000);
      int silentRuns = 0; bool inSilence = false;
      for (size_t w = 0; w + 480 <= out.size(); w += 480) {
          double e = 0; for (size_t i = w; i < w + 480; i += 2) e += out[i] * out[i];
          const bool s = std::sqrt(e / 240) < 0.01;
          if (s && !inSilence) ++silentRuns;
          inSilence = s;
      }
      CHECK(silentRuns >= 7 && silentRuns <= 9, "slicer pattern 1 chops every 1/16 (%d gaps/s)", silentRuns);
      f.move(Slicer, 0.8f, 0.3f); run(f, 2400); auto q = run(f, 48000);   // quarters: 2 opens per second
      int opens = 0; bool open = false;
      for (size_t w = 0; w + 480 <= q.size(); w += 480) {
          double e = 0; for (size_t i = w; i < w + 480; i += 2) e += q[i] * q[i];
          const bool o = std::sqrt(e / 240) > 0.02;
          if (o && !open) ++opens;
          open = o;
      }
      CHECK(opens >= 1 && opens <= 3, "slicer quarters pattern opens twice a second (%d)", opens); }

    // ---- SCATTER ----
    { MasterFx f; run(f, 48000); f.engage(Scatter, 0.2f, 1.f); std::vector<float> d; auto out = run(f, 96000, &d);
      CHECK(finite(out) && diff(out, d) / out.size() > 1e-3, "scatter re-orders the audio");
      f.release(Scatter); run(f, 1200); std::vector<float> d2; auto back = run(f, 4800, &d2);
      CHECK(diff(back, d2) / back.size() < 1e-3, "scatter release snaps back to dry"); }
    {   // two punches on the same bar position: which slices get altered should differ
        MasterFx f; gT = 0; run(f, 96000);
        auto altered = [&](void) {
            f.engage(Scatter, 0.2f, 0.6f); std::vector<float> d; auto o = run(f, 96000, &d); f.release(Scatter);
            std::vector<int> m;
            for (int k = 0; k < 16; ++k) { double e = 0; for (int i = k * 6000 + 300; i < k * 6000 + 5700; ++i) e += std::fabs(o[2 * i] - d[2 * i]); m.push_back(e > 1.0); }
            run(f, 96000);                         // a bar dry, so the next punch starts at the same bar position
            return m;
        };
        auto m1 = altered(), m2 = altered();
        int changes = 0, any = 0; for (int k = 0; k < 16; ++k) { changes += m1[k] != m2[k]; any += m1[k] + m2[k]; }
        CHECK(any > 0 && changes > 0, "scatter: a new pattern every punch (%d slices differ)", changes);
    }

    // ---- ISOLATOR ----
    { MasterFx f; run(f, 4800); f.engage(Isolator, 0.5f, 0.f);   // depth 0 → transparent
      run(f, 2400); std::vector<float> d; auto t = run(f, 4800, &d);
      CHECK(diff(t, d) / t.size() < 1e-4, "isolator at zero depth is transparent (bands sum back)");
      f.move(Isolator, 0.f, 1.f); run(f, 4800); std::vector<float> d2; auto lo = run(f, 24000, &d2);
      CHECK(tone(lo, 93.9) < 0.25 * tone(d2, 93.9) && tone(lo, 4660) > 0.7 * tone(d2, 4660), "isolator left/top kills the lows, keeps the highs");
      f.move(Isolator, 1.f, 1.f); run(f, 4800); std::vector<float> d3; auto hi = run(f, 24000, &d3);
      CHECK(tone(hi, 4660) < 0.25 * tone(d3, 4660) && tone(hi, 93.9) > 0.7 * tone(d3, 93.9), "isolator right/top kills the highs, keeps the lows"); }

    // ---- CASSETTE / VINYL ----
    { MasterFx f; run(f, 9600); f.engage(Cassette, 0.8f, 0.8f); run(f, 2400); std::vector<float> d; auto out = run(f, 48000, &d);
      CHECK(diff(out, d) / out.size() > 1e-3 && finite(out) && hf(out) < hf(d), "cassette wobbles, darkens and saturates"); }
    { MasterFx f; run(f, 9600); f.engage(Vinyl, 1.f, 1.f); run(f, 2400); std::vector<float> d; auto out = run(f, 48000, &d);
      CHECK(diff(out, d) / out.size() > 1e-3 && finite(out), "vinyl audibly colours the signal"); }

    // ---- RESONATOR ----
    {   // white noise in: the comb pulls out its note (C5 ≈ 523 Hz) against a neighbour (440 Hz)
        MasterFx f; f.engage(Resonator, 0.75f, 0.9f);   // MIDI 72 = C5
        uint32_t s = 1; std::vector<float> io(2 * 48000);
        for (auto& v : io) { s ^= s << 13; s ^= s >> 17; s ^= s << 5; v = ((s & 0xFFFF) / 32768.f - 1.f) * 0.2f; }
        for (int off = 0; off < 48000; off += 192) f.process(io.data() + 2 * off, 192, SR, BPM);
        std::vector<float> tail(io.begin() + 2 * 12000, io.end());
        const double ratio = tone(tail, 523.25) / std::max(1e-9, tone(tail, 440.0));
        CHECK(finite(io) && ratio > 3.0, "resonator rings at its note (C5 vs A4 ×%.1f)", ratio);
    }

    // ---- ECHO ----
    { MasterFx f; run(f, 4800, nullptr, true);
      // one click in, echo at 1/8 (x in [2/7, 3/7)) = 12000 samples
      f.engage(Echo, 0.35f, 0.5f);
      std::vector<float> io(2 * 48000, 0.f); io[2 * 2000] = io[2 * 2000 + 1] = 1.f;    // click after the 6 ms fade-in
      for (int off = 0; off < 48000; off += 192) f.process(io.data() + 2 * off, 192, SR, BPM, 0.0, 0.0);
      int peakAt = 0; float pk = 0; for (int i = 8000; i < 20000; ++i) if (std::fabs(io[2 * i]) > pk) { pk = std::fabs(io[2 * i]); peakAt = i; }
      CHECK(pk > 0.05f && std::abs(peakAt - 14000) < 200, "echo repeats on the 1/8 (at %d, %.2f)", peakAt - 2000, pk);
      int peak2 = 0; float pk2 = 0; for (int i = 20000; i < 32000; ++i) if (std::fabs(io[2 * i]) > pk2) { pk2 = std::fabs(io[2 * i]); peak2 = i; }
      CHECK(pk2 > 0.01f && pk2 < pk && std::abs(peak2 - 26000) < 300, "…and again, quieter (feedback %.2f)", pk2);
      f.release(Echo);
      std::vector<float> z(2 * 24000, 0.f);
      for (int k = 0; k < 40; ++k) { std::fill(z.begin(), z.end(), 0.f); f.process(z.data(), 24000, SR, BPM); }
      double late = 0; for (float v : z) late += std::fabs(v);
      CHECK(late < 1e-2 && f.echoTail() == 0.f && f.activeMask() == 0, "echo tail dies after release (%.2e)", late); }
    { MasterFx f; run(f, 4800); f.engage(Echo, 0.1f, 1.f); auto out = run(f, 240000);   // max feedback: runaway but bounded
      double pk = 0; for (float v : out) pk = std::max(pk, (double)std::fabs(v));
      CHECK(finite(out) && pk < 4.0, "echo at max feedback runs away but stays bounded (peak %.2f)", pk); }

    // ---- REVERB ----
    { MasterFx f; run(f, 9600); f.engage(Reverb, 0.9f, 0.5f); std::vector<float> d; auto wet = run(f, 48000, &d);
      CHECK(diff(wet, d) / wet.size() > 1e-3 && finite(wet), "reverb adds a wash");
      f.release(Reverb);
      std::vector<float> io(2 * 24000, 0.f); f.process(io.data(), 24000, 48000.f, 120.f);
      double tail = 0; for (int i = 0; i < 4800; ++i) tail += std::fabs(io[2 * i]);
      CHECK(tail > 1.0 && f.reverbTail() > 0.f, "reverb tail rings after release (%.2f)", tail);
      for (int k = 0; k < 60; ++k) f.process(io.data(), 24000, 48000.f, 120.f), std::fill(io.begin(), io.end(), 0.f);
      f.process(io.data(), 24000, 48000.f, 120.f);
      double late = 0; for (float v : io) late += std::fabs(v);
      CHECK(late < 1e-2 && finite(io), "reverb tail eventually decays to silence (%.2e)", late); }

    // ---- everything at once ----
    { MasterFx f; run(f, 24000); for (int id = 0; id < kCount; ++id) f.engage(id, 0.9f, 0.9f);
      auto out = run(f, 96000); f.releaseAll();
      for (int k = 0; k < 40; ++k) run(f, 24000, nullptr, true);
      auto after = run(f, 4800);
      CHECK(finite(out) && finite(after), "all %d stacked at extreme settings: finite", (int)kCount);
      CHECK(f.activeMask() == 0, "mask clears once every effect has faded out"); }
    { MasterFx f; f.engage(-1, 0, 0); f.engage(kCount, 0, 0); f.move(99, 0, 0); f.release(-5);
      std::vector<float> d; auto out = run(f, 4800, &d);
      CHECK(diff(out, d) == 0.0, "bad ids ignored"); }

    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
