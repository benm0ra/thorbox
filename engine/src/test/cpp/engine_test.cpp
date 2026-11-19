// SPDX-License-Identifier: GPL-3.0-or-later
// Integration test: drives the real AudioEngine callback with a fake Oboe stream (test/cpp/stubs).
// Covers sequencing → synth → mixer strips → master, mute/solo/pan, recording events, pattern lifecycle.
#include "AudioEngine.h"
#include <cmath>
#include <cstdio>
#include <vector>
std::unique_ptr<SampleBuffer> decodeAudioFd(int, const char** err) { if (err) *err = "stub"; return nullptr; }

static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-64s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)

static oboe::AudioStream gStream;
static std::vector<float> render(AudioEngine& e, int frames, int block = 192) {
    std::vector<float> out(frames * 2);
    for (int off = 0; off < frames; off += block) e.onAudioReady(&gStream, out.data() + 2 * off, std::min(block, frames - off));
    return out;
}
static double rms(const std::vector<float>& x, int ch) { double s = 0; for (size_t i = ch; i < x.size(); i += 2) s += x[i] * x[i]; return std::sqrt(s / (x.size() / 2)); }
static seq::Pattern* synthLoop() {
    auto* p = new seq::Pattern(); p->lengthTicks = 3840; p->count = 4;
    for (int i = 0; i < 4; ++i) p->events[i] = seq::Event{i * 960, 800, (int16_t)(60 + i * 3), 0.9f, 1.f};
    return p;
}

int main() {
    double tr[20], ev[10], rec[4];
    {
        AudioEngine e;
        e.setTempo(120); e.setPattern(1, synthLoop()); e.setPlaying(true);
        auto out = render(e, 48000);
        CHECK(rms(out, 0) > 0.01 && rms(out, 1) > 0.01, "sequenced synth reaches the output (rms %.3f)", rms(out, 0));
        e.getTransport(tr);
        CHECK(std::fabs(tr[0] - 1920.0) < 2.0 && tr[1] == 1 && tr[3] == 120, "transport tick = 1920 after 1 s at 120 BPM (%.1f)", tr[0]);
        CHECK(tr[6] > 0.01 && tr[4] == 0 && tr[8] > 0.01, "meters: synth strip + master move, sampler strip silent");

        e.setChannel(1, 2, 1.f);                      // mute synth
        render(e, 2400);                              // let the 5 ms smoothing settle
        auto muted = render(e, 24000);
        CHECK(rms(muted, 0) < 1e-5, "mute silences the synth strip (rms %.2e)", rms(muted, 0));
        e.setChannel(1, 2, 0.f); e.setChannel(0, 3, 1.f);   // unmute synth, solo (empty) sampler
        render(e, 2400); auto soloed = render(e, 24000);
        CHECK(rms(soloed, 0) < 1e-5, "solo on another strip silences the synth");
        e.setChannel(0, 3, 0.f); e.setChannel(1, 1, -1.f);  // hard-left pan
        render(e, 4800); auto left = render(e, 24000);
        CHECK(rms(left, 0) > 0.01 && rms(left, 1) < 1e-4, "hard-left pan: L %.3f, R %.1e", rms(left, 0), rms(left, 1));
        e.setChannel(1, 1, 0.f);

        e.setPattern(1, synthLoop());                 // swap → old pattern comes back for deletion
        render(e, 512);
        e.pollEvent(ev);                              // frees the released pattern (ASan checks no leak/UAF)

        e.setRecording(true); render(e, 256);
        e.synthNoteOn(72, 0.7f); render(e, 4800); e.synthNoteOff(72); render(e, 256);
        e.pollEvent(ev);
        int t1 = e.pollRecord(rec); double onTick = rec[3]; int note = (int)rec[1]; float vel = (float)rec[2];
        int t2 = e.pollRecord(rec);
        CHECK(t1 == 1 && note == 72 && std::fabs(vel - 0.7f) < 1e-6 && t2 == 2 && rec[3] > onTick,
              "recording: live note-on/off reported with ticks (%d,%d)", t1, t2);
        e.setRecording(false);

        e.setMetronome(true); e.setChannel(1, 2, 1.f); render(e, 2400);
        auto click = render(e, 48000);
        CHECK(rms(click, 0) > 1e-3, "metronome audible even with every strip muted (monitoring path)");
        e.setMetronome(false);

        e.repeatOn(1, 64, 240, 0.8f);  // repeat while playing (synth still muted → check via record path)
        e.setRecording(true);
        render(e, 24000);
        e.pollEvent(ev);
        int reps = 0; while (e.pollRecord(rec)) if ((int)rec[1] == 64) ++reps;
        CHECK(reps >= 3, "note-repeat hits are recorded (%d in 0.5 s at 1/16)", reps);
        e.repeatOff(1, 64); e.setRecording(false);

        e.setChannel(1, 2, 0.f); e.repeatOff(1, 64);
        e.fxOn(0, 0.5f, 0.5f); render(e, 4800); e.getTransport(tr);
        CHECK(((int)tr[12] & 1) == 1, "punch-in stutter reported active");
        e.fxOff(0); render(e, 9600); e.getTransport(tr);
        CHECK((int)tr[12] == 0, "…and inactive after release");

        // Drum synth: its own track (2), strip (2) and meters [16..17]; master is strip 3.
        e.setChannel(1, 2, 1.f);                       // mute the synth so only drums sound
        auto* dp = new seq::Pattern(); dp->lengthTicks = 3840; dp->count = 4;
        for (int i = 0; i < 4; ++i) dp->events[i] = seq::Event{i * 960, 120, (int16_t)(i % 2 ? 1 : 0), 1.f, 1.f};
        e.setPattern(2, dp);
        render(e, 2400); e.getTransport(tr);
        auto dr = render(e, 48000); e.getTransport(tr);
        CHECK(rms(dr, 0) > 0.01 && tr[16] > 0.01 && tr[6] < 1e-3, "drum track sequenced on its own strip (rms %.3f, meter %.3f)", rms(dr, 0), tr[16]);
        float lv[8]; int32_t hits[8]; e.getDrumTelemetry(lv, hits);
        CHECK(hits[0] >= 1 && hits[1] >= 1, "drum hit counters advance (kick %d, snare %d)", hits[0], hits[1]);
        e.setChannel(2, 2, 1.f); render(e, 2400); auto dm = render(e, 24000);
        CHECK(rms(dm, 0) < 1e-5, "muting strip 2 silences the drums");
        e.setChannel(2, 2, 0.f);
        e.setChannel(3, 0, -60.f); render(e, 9600); auto mm = render(e, 24000);
        CHECK(rms(mm, 0) < 2e-3, "strip 3 is the master fader (rms %.2e)", rms(mm, 0));
        e.setChannel(3, 0, 0.f);
        CHECK(!e.setChannel(4, 0, 0.f) && !e.drumTrigger(8, 1.f) && !e.drumParam(0, 99, 1.f), "out-of-range strip / slot / param rejected");
        e.setRecording(true); render(e, 256); e.pollEvent(ev); while (e.pollRecord(rec)) {}
        e.setPattern(2, nullptr);
        e.drumTrigger(5, 0.9f); render(e, 256); e.pollEvent(ev);
        bool got = false; while (e.pollRecord(rec)) got |= ((int)rec[0] == 2 && (int)rec[1] == 5);
        CHECK(got, "live drum hits are recorded on track 2");
        e.setRecording(false); e.setChannel(1, 2, 0.f);

        e.setPlaying(false); render(e, 48000);
        e.getTransport(tr);
        CHECK(tr[1] == 0, "stop");
        bool finite = true; for (float v : click) finite &= std::isfinite(v);
        CHECK(finite, "all output finite");
    }   // destructor deletes live patterns (ASan: no leaks)
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
