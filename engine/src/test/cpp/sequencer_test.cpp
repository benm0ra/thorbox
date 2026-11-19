// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the sequencer: timing, swing, loop wrap, probability, repeat, metronome.
#include "Sequencer.h"
#include <cmath>
#include <cstdio>
#include <vector>
using namespace seq;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-66s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)
constexpr float SR = 48000.f;

struct Hit { long sample; int track; bool on; int note; float vel; };
// Runs the sequencer for `seconds` in callback-sized blocks; returns absolute sample positions.
static std::vector<Hit> run(Sequencer& s, double seconds, int block = 192) {
    std::vector<Hit> hits; Fire f[512]; long pos = 0; const long total = (long)(seconds * SR);
    while (pos < total) {
        int n = (int)std::min<long>(block, total - pos);
        int c = s.process(n, SR, f, 512);
        for (int i = 0; i < c; ++i) hits.push_back({pos + f[i].offset, f[i].track, f[i].on, f[i].note, f[i].velocity});
        pos += n;
    }
    return hits;
}
static std::vector<long> onsets(const std::vector<Hit>& h, int track) { std::vector<long> v; for (auto& x : h) if (x.on && x.track == track) v.push_back(x.sample); return v; }
static double samplesPerTick(float bpm) { return SR * 60.0 / (bpm * kPpqn); }

int main() {
    const float bpm = 120.f;   // 1/16 = 240 ticks = 0.125 s = 6000 samples
    Pattern p16; p16.lengthTicks = 16 * 240; p16.count = 16;
    for (int i = 0; i < 16; ++i) p16.events[i] = Event{i * 240, 120, 0, 0.8f, 1.f};

    { Sequencer s; s.setTempo(bpm); s.setPattern(0, &p16); s.setPlaying(true);
      auto on = onsets(run(s, 2.0), 0);
      bool exact = on.size() == 16; for (size_t i = 0; i < on.size() && exact; ++i) exact = std::labs(on[i] - (long)(i * 6000)) <= 1;
      CHECK(exact, "16 straight 1/16s at 120 BPM land within 1 sample (%zu hits)", on.size()); }

    { Sequencer s; s.setTempo(bpm); s.setPattern(0, &p16); s.setSwing(0.66f, 240); s.setPlaying(true);
      auto on = onsets(run(s, 2.0), 0);
      const long sw = (long)std::lround((0.66 - 0.5) * 2 * 240 * samplesPerTick(bpm));   // 1920 samples
      bool ok = on.size() == 16;
      for (size_t i = 0; i < on.size() && ok; ++i) ok = std::labs(on[i] - (long)(i * 6000 + (i % 2 ? sw : 0))) <= 1;
      CHECK(ok, "swing 66%% on 1/16 delays odd steps by %ld samples, evens untouched", sw); }

    { Sequencer s; s.setTempo(bpm); s.setPattern(0, &p16); s.setSwing(0.66f, 480); s.setPlaying(true);
      auto on = onsets(run(s, 2.0), 0);
      // 1/8 swing: 1/16 positions 2, 6, 10, 14 (odd 1/8s) move; 1/16 offbeats (odd 1/16s) aren't on the 1/8 grid → unmoved
      const long sw = (long)std::lround(0.16 * 2 * 480 * samplesPerTick(bpm));
      bool ok = on.size() == 16;
      for (size_t i = 0; i < on.size() && ok; ++i) ok = std::labs(on[i] - (long)(i * 6000 + (i % 4 == 2 ? sw : 0))) <= 1;
      CHECK(ok, "swing on 1/8 grid moves only odd 1/8 positions"); }

    { Pattern tri; tri.lengthTicks = 960; tri.count = 3; for (int i = 0; i < 3; ++i) tri.events[i] = Event{i * 320, 100, 1, 1.f, 1.f};
      Sequencer s; s.setTempo(bpm); s.setSwing(0.75f, 240); s.setPattern(1, &tri); s.setPlaying(true);
      auto on = onsets(run(s, 0.5), 1);
      bool ok = on.size() == 3 && std::labs(on[1] - 8000) <= 1 && std::labs(on[2] - 16000) <= 1;
      CHECK(ok, "triplets ignore swing (stay at 0 / 8000 / 16000)"); }

    { Sequencer s; s.setTempo(bpm); s.setPattern(0, &p16); s.setPlaying(true);
      auto on = onsets(run(s, 6.0, 37), 0);    // odd block size, 3 loops
      CHECK(on.size() == 48, "loops seamlessly across odd block sizes (48 hits / 3 bars)"); }

    { Pattern last; last.lengthTicks = 16 * 240; last.count = 1; last.events[0] = Event{15 * 240, 60, 0, 1, 1};
      Sequencer s; s.setTempo(bpm); s.setSwing(0.75f, 240); s.setPattern(0, &last); s.setPlaying(true);
      auto on = onsets(run(s, 4.0), 0);
      CHECK(on.size() == 2 && std::labs(on[0] - (15 * 6000 + 3000)) <= 1, "max-swung last step still fires each loop (%zu)", on.size()); }

    { Pattern pr = p16; for (int i = 0; i < 16; ++i) pr.events[i].probability = 0.5f;
      Sequencer s; s.setTempo(bpm); s.setPattern(0, &pr); s.setPlaying(true);
      auto on = onsets(run(s, 40.0), 0);   // 320 chances
      CHECK(on.size() > 120 && on.size() < 200, "50%% probability fires about half (%zu / 320)", on.size()); }

    { Sequencer s; s.setTempo(bpm); s.setPattern(0, &p16); s.setPlaying(true);
      auto h = run(s, 1.0);
      int ons = 0, offs = 0; for (auto& x : h) (x.on ? ons : offs)++;
      s.setPlaying(false); auto h2 = run(s, 0.1);
      int offs2 = 0; for (auto& x : h2) if (!x.on) offs2++;
      CHECK(ons == offs + offs2, "every note-on gets a note-off, including on stop (%d/%d)", ons, offs + offs2); }

    { Sequencer s; s.setTempo(bpm); s.repeatOn(0, 3, 240, 0.7f);   // transport stopped: repeat still runs
      auto on = onsets(run(s, 0.99), 0);
      bool spaced = on.size() >= 8; for (size_t i = 1; i < on.size() && spaced; ++i) spaced = std::labs((on[i] - on[i - 1]) - 6000) <= 1;
      CHECK(on.size() == 8 && on[0] == 0 && spaced, "note repeat 1/16 with transport stopped (%zu hits, first at 0)", on.size());
      s.repeatOff(0, 3); auto after = onsets(run(s, 0.5), 0);
      CHECK(after.empty(), "repeat stops on release"); }

    { Sequencer s; s.setTempo(bpm); s.setSwing(0.66f, 240); run(s, 0.01); s.repeatOn(1, 60, 240, 1.f);
      auto on = onsets(run(s, 1.0), 1);
      bool sw = on.size() >= 4;
      for (size_t i = 1; i + 1 < on.size() && sw; ++i) { long d = on[i + 1] - on[i]; sw = std::labs(d - 4080) <= 2 || std::labs(d - 7920) <= 2; }
      CHECK(sw, "note repeat follows swing (alternating 7920 / 4080 sample gaps)"); }

    { Sequencer s; s.setTempo(bpm); s.setMetronome(true); s.setPlaying(true);
      auto h = run(s, 2.0); int beats = 0, down = 0; for (auto& x : h) if (x.track == -1) { beats++; down += x.note; }
      CHECK(beats == 4 && down == 1, "metronome: 4 clicks per bar at 120, 1 downbeat (%d,%d)", beats, down); }

    { Pattern one; one.lengthTicks = 3840; one.count = 1; one.events[0] = Event{240, 60, 5, 1, 1};
      Sequencer s; s.setTempo(bpm); s.setPattern(0, &one); s.setPlaying(true);
      run(s, 0.1);                       // tick ≈ 96: user hits live and it's recorded quantized forward to 240
      s.noteLiveHit(0, 5);
      auto first = onsets(run(s, 1.9), 0); auto second = onsets(run(s, 2.0), 0);
      CHECK(first.empty() && second.size() == 1, "forward-quantized live hit isn't doubled this pass, plays next loop"); }

    { Sequencer s; s.setTempo(bpm); Pattern a = p16;
      s.setPattern(0, &a); s.setPlaying(true); run(s, 0.3);
      const Pattern* old = s.setPattern(0, &p16);
      CHECK(old == &a, "pattern swap returns the old pointer for deletion"); }

    { Pattern r; r.lengthTicks = 3840; r.count = 1; r.events[0] = Event{0, 240, 7, 1.f, 1.f}; r.events[0].ratchet = 4;
      Sequencer s; s.setTempo(bpm); s.setPattern(0, &r); s.setPlaying(true);
      auto on = onsets(run(s, 0.5), 0);
      bool ok = on.size() == 4; for (size_t i = 0; i < on.size() && ok; ++i) ok = std::labs(on[i] - (long)(i * 1500)) <= 1;
      CHECK(ok, "ratchet ×4 on a 1/16 step: 4 hits 1500 samples apart (%zu)", on.size()); }

    { Pattern c; c.lengthTicks = 3840; c.count = 1; c.events[0] = Event{0, 120, 1, 1.f, 1.f}; c.events[0].condEvery = 2; c.events[0].condPhase = 1;
      Sequencer s; s.setTempo(bpm); s.setPattern(0, &c); s.setPlaying(true);
      auto on = onsets(run(s, 8.0), 0);      // 4 loops of 2 s
      bool ok = on.size() == 2 && std::labs(on[0] - 96000) <= 1 && std::labs(on[1] - 288000) <= 1;
      CHECK(ok, "condition 2:2 plays on loops 2 and 4 only (%zu hits)", on.size()); }

    { Pattern pr; pr.lengthTicks = 3840; pr.count = 1; pr.events[0] = Event{0, 240, 2, 1.f, 0.5f}; pr.events[0].ratchet = 3;
      Sequencer s; s.setTempo(bpm); s.setPattern(0, &pr); s.setPlaying(true);
      auto on = onsets(run(s, 80.0), 0);   // 40 loops
      CHECK(on.size() % 3 == 0 && on.size() > 30 && on.size() < 90, "probability decides the whole ratchet (hits come in 3s: %zu)", on.size()); }

    { Pattern pl; pl.lengthTicks = 3840; pl.count = 1; pl.events[0] = Event{0, 120, 60, 1.f, 1.f}; pl.events[0].lockId[0] = 6; pl.events[0].lockVal[0] = 300.f;
      Sequencer s; s.setTempo(bpm); s.setPattern(1, &pl); s.setPlaying(true);
      Fire f[64]; int c = s.process(192, SR, f, 64); bool ok = false;
      for (int i = 0; i < c; ++i) if (f[i].on && f[i].src && f[i].src->lockId[0] == 6 && f[i].src->lockVal[0] == 300.f) ok = true;
      CHECK(ok, "pattern hits carry their parameter locks"); }

    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
