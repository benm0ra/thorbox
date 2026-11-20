// SPDX-License-Identifier: GPL-3.0-or-later
// Integration test: drives the real AudioEngine callback with a fake Oboe stream (test/cpp/stubs).
// Covers sequencing → synth → mixer strips → master, mute/solo/pan, recording events, pattern lifecycle.
#include "AudioEngine.h"
#include <cmath>
#include <cstdio>
#include <vector>
#include <chrono>
#include <thread>
// Decoder stub: fd 7777 "decodes" to 1 s of a 220 Hz tone (mono, 48 kHz); anything else fails.
// Lets the test drive the real worker → registry → pollEvent → assign → play path.
std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** err) {
    if (fd != 7777) { if (err) *err = "stub"; return nullptr; }
    auto b = std::make_unique<SampleBuffer>();
    b->sampleRate = 48000; b->channels = 1; b->frames = 48000;
    b->data.resize(48000);
    for (int i = 0; i < 48000; ++i) b->data[(size_t)i] = 0.5f * std::sin(2.0 * 3.14159265358979 * 220.0 * i / 48000.0);
    return b;
}
static bool waitEvent(AudioEngine& e, double* ev) {
    for (int i = 0; i < 400; ++i) { if (e.pollEvent(ev)) return true; std::this_thread::sleep_for(std::chrono::milliseconds(5)); }
    return false;
}

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
    float m[26];
    {   // Sample load path: worker decode → registry → event → assign to a pad → stretch job → event.
        AudioEngine e;
        e.loadSampleAsync(7777, 41);
        double lv[12] = {};
        const bool got = waitEvent(e, lv);
        CHECK(got && (int)lv[0] == 41 && lv[1] > 0 && (int64_t)lv[2] == 48000, "a loaded sample comes back with an id and its length");
        const int sid = (int)lv[1];
        CHECK(e.assignSample(0, sid), "the loaded sample can be put on a pad");
        {   // NORMALIZE input: per-region peak + loudness (a steady sine: loudness = peak − 3 dB)
            int64_t st[2] = {0, 24000}, en[2] = {24000, 48000}; float pk[2], ld[2];
            CHECK(e.regionLevels(sid, st, en, 2, pk, ld) && pk[0] < 0.1f && pk[0] > -30.f && std::fabs((pk[0] - ld[0]) - 3.01f) < 0.3f,
                  "region levels: peak %.1f dB, loudness %.1f dB", pk[0], ld[0]);
            CHECK(!e.regionLevels(sid + 999, st, en, 2, pk, ld), "region levels: unknown sample → false");
        }
        e.stretchSampleAsync(sid, 1.25, 2.0, 42);
        double sv[12] = {};
        const bool got2 = waitEvent(e, sv);
        CHECK(got2 && (int)sv[0] == 42 && sv[1] > 0 && (int)sv[1] != sid, "a KEY MATCH / BPM SYNC render comes back as a new sample");
        e.stretchSampleAsync(sid, 1.0, 3.0, 44, 12000, 24000);          // per-pad WARP: one slice, +3 st, same length
        double cv[12] = {};
        CHECK(waitEvent(e, cv) && (int)cv[0] == 44 && cv[1] > 0 && std::llabs((long long)cv[2] - 12000) < 600, "a cropped WARP render is just that slice, same length");
        e.loadSampleAsync(-1, 43);
        double fv[12] = {};
        CHECK(waitEvent(e, fv) && (int)fv[0] == 43 && fv[1] == 0, "a file that won't decode reports a failure, not a crash");
    }
    {
        AudioEngine e;
        e.setTempo(120); e.setPattern(1, synthLoop()); e.setPlaying(true);
        auto out = render(e, 48000);
        CHECK(rms(out, 0) > 0.01 && rms(out, 1) > 0.01, "sequenced synth reaches the output (rms %.3f)", rms(out, 0));
        e.getTransport(tr);
        CHECK(std::fabs(tr[0] - 1920.0) < 2.0 && tr[1] == 1 && tr[3] == 120, "transport tick = 1920 after 1 s at 120 BPM (%.1f)", tr[0]);
        e.getMeters(m);
        CHECK(m[2] > 0.01 && m[0] == 0 && m[16] > 0.01, "meters: synth strip + master move, sampler strip silent");

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
        e.synthNoteOn(1, 72, 0.7f); render(e, 4800); e.synthNoteOff(1, 72); render(e, 256);
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
        render(e, 2400); e.getMeters(m);
        auto dr = render(e, 48000); e.getMeters(m);
        CHECK(rms(dr, 0) > 0.01 && m[4] > 0.01 && m[2] < 1e-3, "drum track sequenced on its own strip (rms %.3f, meter %.3f)", rms(dr, 0), m[4]);
        float lv[8]; int32_t hits[8]; e.getDrumTelemetry(2, lv, hits);
        CHECK(hits[0] >= 1 && hits[1] >= 1, "drum hit counters advance (kick %d, snare %d)", hits[0], hits[1]);
        e.setChannel(2, 2, 1.f); render(e, 2400); auto dm = render(e, 24000);
        CHECK(rms(dm, 0) < 1e-5, "muting strip 2 silences the drums");
        e.setChannel(2, 2, 0.f);
        e.setChannel(8, 0, -60.f); render(e, 9600); auto mm = render(e, 24000);
        CHECK(rms(mm, 0) < 2e-3, "strip 8 is the master fader (rms %.2e)", rms(mm, 0));
        e.setChannel(8, 0, 0.f);
        CHECK(!e.setChannel(9, 0, 0.f) && !e.drumTrigger(2, 8, 1.f) && !e.drumParam(2, 0, 99, 1.f) && !e.drumTrigger(8, 0, 1.f)
              && !e.synthNoteOn(8, 60, 1.f) && !e.setTrackType(3, 7), "out-of-range track / strip / slot / param rejected");
        CHECK(e.drumParam(2, drums::kBusSlot, drums::Muffle, 0.4f) && !e.drumParam(2, drums::kBusSlot, 9, 0.4f) && !e.drumParam(2, drums::kBusSlot + 1, 0, 0.4f),
              "drum character bus params accepted, bad ones rejected");
        e.drumParam(2, drums::kBusSlot, drums::Muffle, 0.f);
        e.setRecording(true); render(e, 256); e.pollEvent(ev); while (e.pollRecord(rec)) {}
        e.setPattern(2, nullptr);
        e.drumTrigger(2, 5, 0.9f); render(e, 256); e.pollEvent(ev);
        bool got = false; while (e.pollRecord(rec)) got |= ((int)rec[0] == 2 && (int)rec[1] == 5);
        CHECK(got, "live drum hits are recorded on track 2");
        e.setRecording(false); e.setChannel(1, 2, 0.f);

        // ---- 8 tracks: a second synth on track 5, a second drum machine on track 6 ----
        e.setTrackType(5, 2); e.setTrackType(6, 3);
        e.setPattern(5, synthLoop());
        auto* dp2 = new seq::Pattern(); dp2->lengthTicks = 3840; dp2->count = 1;
        dp2->events[0] = seq::Event{0, 120, 0, 1.f, 1.f};
        e.setPattern(6, dp2);
        e.setChannel(2, 2, 1.f);                       // mute track 2's drums: only the new tracks + nothing else
        render(e, 4800); e.getMeters(m);
        auto multi = render(e, 48000); e.getMeters(m);
        CHECK(m[10] > 0.01 && m[12] > 0.01, "extra synth (track 5) and drums (track 6) play on their own strips (%.3f, %.3f)", m[10], m[12]);
        e.synthParam(5, 0, 1.f);                       // a param on track 5 must not touch track 1
        CHECK(e.synthParam(1, 0, 0.f), "per-track synth params accepted");

        // ---- sidechain: duck track 5 (synth) from track 6 (kick) ----
        e.setChannel(5, 4, 6.f); e.setChannel(5, 5, 1.f); e.setChannel(5, 6, 200.f);
        render(e, 9600); e.getMeters(m);
        render(e, 96000); e.getMeters(m);                // a full 2 s pattern, so the kick lands in the window
        CHECK(m[18 + 5] < 0.3f && m[18 + 1] >= 0.999f, "sidechain: track 5 ducks under the kick (min gain %.2f), track 1 untouched", m[18 + 5]);
        e.setChannel(5, 5, 0.f); render(e, 9600); e.getMeters(m); render(e, 24000); e.getMeters(m);
        CHECK(m[18 + 5] >= 0.999f, "sidechain amount 0 = no ducking");
        CHECK(e.setChannel(5, 4, -1.f), "sidechain source -1 = off");
        // Switch a track's instrument while it plays: no crash, track goes quiet.
        e.setTrackType(5, 0); render(e, 4800); e.getMeters(m); render(e, 24000); e.getMeters(m);
        CHECK(m[10] < 1e-6, "track type NONE renders nothing");
        e.setChannel(2, 2, 0.f);

        e.setPlaying(false); render(e, 48000);
        e.getTransport(tr);
        CHECK(tr[1] == 0, "stop");
        bool finite = true; for (float v : click) finite &= std::isfinite(v);
        CHECK(finite, "all output finite");
    }   // destructor deletes live patterns (ASan: no leaks)
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
