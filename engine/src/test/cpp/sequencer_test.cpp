// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the sequencer: timing, swing, loop wrap, probability, repeat, metronome.
#include "Sequencer.h"
#include <algorithm>
#include <cstdlib>
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

    {   // scenes: a set of patterns staged, then swapped in exactly on a loop boundary (120 BPM: a bar = 2 s = 96000 samples)
        Pattern A; A.lengthTicks = 3840; A.count = 1; A.events[0] = Event{0, 120, 1, 1.f, 1.f};
        Pattern B; B.lengthTicks = 3840; B.count = 1; B.events[0] = Event{0, 120, 2, 1.f, 1.f};
        Pattern C; C.lengthTicks = 1920; C.count = 1; C.events[0] = Event{0, 120, 3, 1.f, 1.f};   // a 2-beat pattern
        Sequencer s; s.setTempo(120); s.setPattern(0, &A); s.setPlaying(true);
        run(s, 1.0);                                                   // half way through bar 1
        s.stage(0, &B); s.commit(3840, 5);
        CHECK(s.queuedTag() == 5 && std::fabs(s.queuedAt() - 3840) < 1e-6, "commit arms the change for the next bar (tick %.0f)", s.queuedAt());
        auto h = run(s, 3.0);                                          // 1.0 s … 4.0 s
        std::vector<long> a1, b2;
        for (auto& x : h) if (x.on && x.track == 0) (x.note == 1 ? a1 : b2).push_back(x.sample + 48000);
        CHECK(a1.empty() && b2.size() == 2 && std::labs(b2[0] - 96000) <= 1 && std::labs(b2[1] - 192000) <= 1,
              "pattern B takes over exactly on the bar (%ld), A is gone", b2.empty() ? -1L : b2[0]);
        CHECK(std::fabs(s.origin() - 3840) < 1e-6 && s.sceneTag() == 5 && s.queuedTag() == -1, "origin = the change point, tag reported");
        const Pattern* r = s.retired();
        CHECK(r == &A && s.retired() == nullptr, "the replaced pattern is handed back to be freed");
        // a longer loop (B × 2): C lands two bars after B started, and loops from there
        s.stage(0, &C); s.commit(7680, 6);
        CHECK(std::fabs(s.queuedAt() - 3840 - 7680) < 1e-6, "B ×2: the next change waits for two bars of B (tick %.0f)", s.queuedAt());
        auto h2 = run(s, 4.0);                                         // 4.0 s … 8.0 s: C starts at 6.0 s
        std::vector<long> c3;
        for (auto& x : h2) if (x.on && x.track == 0 && x.note == 3 && x.sample + 192000 < 380000) c3.push_back(x.sample + 192000);
        CHECK(c3.size() == 2 && std::labs(c3[0] - 288000) <= 1 && std::labs(c3[1] - 336000) <= 1,
              "C starts on its own beat 1 and loops every 2 beats (%zu hits)", c3.size());
        s.retired();
        // stopped: the change is immediate; a cancel hands the staged pattern back
        Sequencer t; t.setPattern(0, &A);
        t.stage(0, &B); t.commit(3840, 9);
        CHECK(t.pattern(0) == &B && t.sceneTag() == 9 && t.origin() == 0.0 && t.retired() == &A, "stopped: the scene changes at once");
        t.stage(0, &C); t.commit(0, -1);
        CHECK(t.pattern(0) == &B && t.retired() == &C && t.queuedTag() == -1, "cancel: the staged pattern comes back, nothing changes");
    }
    {   // 64 bars: a sorted pattern (each block looks only near the playhead) fires exactly what a full scan does
        auto* big = new Pattern(); big->lengthTicks = kMaxBars * 4 * kPpqn;
        uint32_t r = 12345; auto rnd = [&](int m) { r ^= r << 13; r ^= r >> 17; r ^= r << 5; return (int)(r % (uint32_t)m); };
        int n = 0;
        for (int step = 0; step < kMaxBars * 16 && n < kMaxEvents; ++step)
            for (int v = 0; v < 3 && n < kMaxEvents; ++v) if (rnd(3) == 0) {
                Event e{step * 240, 60 + rnd(400), (int16_t)rnd(16), 0.8f, 1.f};
                if (rnd(12) == 0) { e.ratchet = (int8_t)(2 + rnd(3)); e.length = 240 + rnd(720); }
                big->events[n++] = e;
            }
        big->count = n;
        auto* sorted = new Pattern(*big);
        std::stable_sort(sorted->events, sorted->events + n, [](const Event& x, const Event& y) { return x.tick < y.tick; });
        int reach = 0; for (int i = 0; i < n; ++i) if (sorted->events[i].ratchet > 1) reach = std::max(reach, (int)sorted->events[i].length);
        sorted->reach = reach + 241; sorted->sorted = true;
        Sequencer a, b;
        for (Sequencer* q : {&a, &b}) { q->setTempo(174); q->setSwing(0.66f, 240); q->setPlaying(true); }
        a.setPattern(0, big); b.setPattern(0, sorted);
        auto key = [](std::vector<Hit> v) { std::sort(v.begin(), v.end(), [](const Hit& x, const Hit& y) {
            return x.sample != y.sample ? x.sample < y.sample : x.on != y.on ? x.on < y.on : x.note < y.note; }); return v; };
        const double secs = big->lengthTicks / (174.0 / 60 * kPpqn) + 3;      // the whole 64 bars and into the next loop
        const auto ha = key(run(a, secs, 256)), hb = key(run(b, secs, 256));
        bool same = ha.size() == hb.size();
        for (size_t i = 0; same && i < ha.size(); ++i) same = ha[i].sample == hb[i].sample && ha[i].on == hb[i].on && ha[i].note == hb[i].note;
        CHECK(same && ha.size() > 2000, "64-bar pattern, %d events: windowed playback == full scan (%zu hits)", n, ha.size());
        delete big; delete sorted;
    }
    {   // count-in: 2 bars of clicks, then bar 1 starts on the exact sample
        Sequencer s; s.setTempo(120); s.setCountIn(2 * 4 * kPpqn);
        Pattern one; one.lengthTicks = 3840; one.count = 1; one.events[0] = Event{0, 120, 7, 1.f, 1.f};
        s.setPattern(2, &one); s.setPlaying(true);
        const auto h = run(s, 4.5, 192);
        std::vector<long> clicks, notes; for (auto& x : h) { if (x.on && x.track == -1) clicks.push_back(x.sample); if (x.on && x.track == 2) notes.push_back(x.sample); }
        const long bar = (long)(4 * kPpqn * samplesPerTick(120));                // 96000 samples
        bool ok = clicks.size() == 8;
        for (size_t i = 0; ok && i < 8; ++i) ok = std::labs(clicks[i] - (long)(i * bar / 4)) <= 1;
        CHECK(ok, "count-in: 8 clicks a beat apart (%zu)", clicks.size());
        CHECK(!notes.empty() && std::labs(notes[0] - 2 * bar) <= 1 && notes.size() == 1, "bar 1 plays right after the count-in, at sample %ld (want %ld)", notes.empty() ? -1L : notes[0], 2 * bar);
        s.setPattern(2, nullptr);
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
