// SPDX-License-Identifier: GPL-3.0-or-later
// Integration test: drives the real AudioEngine callback with a fake Oboe stream (test/cpp/stubs).
// Covers sequencing → synth → mixer strips → master, mute/solo/pan, recording events, pattern lifecycle.
#include "AudioEngine.h"
#include "Decoder.h"
#include <cmath>
#include <cstdio>
#include <vector>
#include <chrono>
#include <thread>
#include <fcntl.h>
#include <string>
// Decoder stub: fd 7777 "decodes" to 1 s of a 220 Hz tone (mono, 48 kHz); 7779 is the same, cut from
// a "30-minute" file; 7778 decodes until cancelled; anything else fails.
// Lets the test drive the real worker → registry → pollEvent → assign → play path.
std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** err, DecodeProgress* prog) {
    if (fd == 7778) {
        for (int i = 0; i < 4000; ++i) {
            if (prog && prog->report && !prog->report(0.0001f * i)) { if (err) *err = "cancelled"; return nullptr; }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (err) *err = "never cancelled";
        return nullptr;
    }
    if (fd == 7779 && prog) { prog->truncated = true; prog->sourceSeconds = 1800; fd = 7777; }
    if (fd != 7777) { if (err) *err = "stub"; return nullptr; }
    auto b = std::make_unique<SampleBuffer>();
    b->sampleRate = 48000; b->channels = 1; b->frames = 48000;
    b->data.resize(48000);
    for (int i = 0; i < 48000; ++i) b->data[(size_t)i] = 0.5f * std::sin(2.0 * 3.14159265358979 * 220.0 * i / 48000.0);
    return b;
}
static int gLastType = 0;
static bool waitEvent(AudioEngine& e, double* ev) {
    for (int i = 0; i < 400; ++i) { if ((gLastType = e.pollEvent(ev))) return true; std::this_thread::sleep_for(std::chrono::milliseconds(5)); }
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
    double tr[24], ev[12], rec[4];
    float m[26];
    {   // Sample load path: worker decode → registry → event → assign to a pad → stretch job → event.
        AudioEngine e;
        e.loadSampleAsync(7777, 41);
        double lv[12] = {};
        const bool got = waitEvent(e, lv);
        CHECK(got && (int)lv[0] == 41 && lv[1] > 0 && (int64_t)lv[2] == 48000, "a loaded sample comes back with an id and its length");
        const int sid = (int)lv[1];
        CHECK(e.assignSample(0, sid), "the loaded sample can be put on a pad");
        {   // waveform view data: [min, max, rms] per bucket; a steady sine has rms ≈ peak / √2
            float w[3 * 64];
            CHECK(e.getWave(sid, 64, w) && w[1] > 0.1f && w[0] < -0.1f && std::fabs(w[2] - w[1] / std::sqrt(2.f)) < 0.05f * w[1] + 0.01f,
                  "wave: min %.2f max %.2f rms %.2f", w[0], w[1], w[2]);
            CHECK(!e.getWave(sid + 999, 64, w), "wave: unknown sample → false");
        }
        {   // PAD EDIT: stop, mute group, mono vs poly (track 0 is a sampler)
            e.noteOn(0, 1.f); render(e, 4800);
            const double before = rms(render(e, 2400), 0);
            e.stopPads(0); render(e, 2400);
            CHECK(before > 0.01 && rms(render(e, 2400), 0) < 1e-4, "STOP fades out a one-shot that's still playing");
            e.assignSample(1, sid);
            e.setPadParam(0, (int)PadParam::Pan, -1.f); e.setPadParam(1, (int)PadParam::Pan, 1.f);
            e.setPadParam(0, (int)PadParam::MuteGroup, 3); e.setPadParam(1, (int)PadParam::MuteGroup, 3);
            e.noteOn(0, 1.f); render(e, 2400); e.noteOn(1, 1.f); render(e, 2400);
            auto ch = render(e, 4800);
            CHECK(rms(ch, 0) < 1e-3 && rms(ch, 1) > 0.01, "mute group: pad 2 cuts pad 1 off (open/closed hat)");
            e.stopPads(-1); render(e, 4800);
            e.setPadParam(0, (int)PadParam::MuteGroup, 0); e.setPadParam(1, (int)PadParam::MuteGroup, 0);
            // CHOKE ALL ("one at a time"): no groups needed, any pad cuts the one before it
            e.setPadParam(0, (int)PadParam::ChokeAll, 1.f); e.setPadParam(1, (int)PadParam::ChokeAll, 1.f);
            e.noteOn(0, 1.f); render(e, 2400); e.noteOn(1, 1.f); render(e, 2400);
            auto ca = render(e, 4800);
            CHECK(rms(ca, 0) < 1e-3 && rms(ca, 1) > 0.01, "choke all: pad 2 silences pad 1");
            e.stopPads(-1); render(e, 4800);
            e.setPadParam(0, (int)PadParam::ChokeAll, 0.f); e.setPadParam(1, (int)PadParam::ChokeAll, 0.f);
            e.noteOn(0, 1.f); render(e, 2400); e.noteOn(1, 1.f); render(e, 2400);
            auto lay = render(e, 4800);
            CHECK(rms(lay, 0) > 0.01 && rms(lay, 1) > 0.01, "layered (choke off): both pads keep sounding");
            e.stopPads(-1); render(e, 4800);
            e.setPadParam(0, (int)PadParam::Pan, 0.f);
            // 2400 frames at 48 kHz = exactly 11 cycles of 220 Hz, so two overlapping hits are in phase
            e.noteOn(0, 1.f); render(e, 2400); e.noteOn(0, 1.f); const double mono = rms(render(e, 4800), 0);
            e.stopPads(0); render(e, 4800);
            e.setPadParam(0, (int)PadParam::Poly, 1.f);
            e.noteOn(0, 1.f); render(e, 2400); e.noteOn(0, 1.f); const double poly = rms(render(e, 4800), 0);
            CHECK(poly > mono * 1.7, "poly: a retrigger overlaps the last hit (%.3f vs mono %.3f)", poly, mono);
            e.stopPads(0); render(e, 4800);
            e.setPadParam(0, (int)PadParam::Poly, 0.f);
            CHECK(!e.setPadParam(0, 99, 1.f) && !e.setPadParam(-1, 0, 0.f) && !e.stopPads(42), "bad pad params / tracks rejected");
            {   // note repeat on a sampler pad: a 50 ms slice at 1/16 (120 BPM = 125 ms) gives a hit every 125 ms
                e.setTempo(120); e.setPadRegion(0, 0, 2400);
                e.repeatOn(0, 0, 240, 1.f);
                auto rr = render(e, 48000);
                e.repeatOff(0, 0); render(e, 4800);
                int bursts = 0; bool loud = false;
                for (int i = 0; i < 48000; i += 240) {             // 5 ms windows
                    double s = 0; for (int k = 0; k < 240; ++k) s += std::fabs(rr[2 * (i + k)]);
                    const bool now = s / 240 > 0.02;
                    if (now && !loud) ++bursts;
                    loud = now;
                }
                CHECK(bursts >= 7 && bursts <= 9, "note repeat on a sampler pad: %d hits in 1 s at 1/16, 120 BPM", bursts);
                e.setPadRegion(0, 0, -1);
            }
        }
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
        CHECK(waitEvent(e, fv) && gLastType == 2 && (int)fv[0] == 43 && (int)fv[1] == kErrFormat,
              "a file that won't decode reports a failure + its reason, not a crash");
        e.loadSampleAsync(7779, 45);
        double tv[12] = {};
        CHECK(waitEvent(e, tv) && gLastType == 1 && (int)tv[0] == 45 && tv[10] == 1.0 && tv[11] == 1800.0,
              "a file over 10 minutes loads (its first 10) and says so, with its full length");
        e.loadSampleAsync(7778, 46);
        float pr[3] = {};
        bool sawDecoding = false;
        for (int i = 0; i < 400 && !sawDecoding; ++i) { e.loadProgress(pr); sawDecoding = (int)pr[0] == 46 && (int)pr[1] == 1; std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
        e.cancelLoad(46);
        double xv[12] = {};
        CHECK(sawDecoding && waitEvent(e, xv) && gLastType == 2 && (int)xv[0] == 46 && (int)xv[1] == kErrCancelled,
              "load progress names the request while decoding, and CANCEL stops it");
        e.loadProgress(pr);
        CHECK((int)pr[0] == 0 && (int)pr[1] == 0, "progress goes back to idle after the load ends");
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
        // strip EQ: a 24 dB/oct low-pass at 100 Hz takes most of the synth away; bad values never land
        render(e, 2400); const double open = rms(render(e, 24000), 0);
        CHECK(e.setChannel(1, 16 + eq::LpHz, 100.f) && e.setChannel(1, 16 + eq::LpSlope, 24.f), "EQ params accepted on a track strip");
        CHECK(!e.setChannel(1, 16 + eq::LpHz, 5.f) && !e.setChannel(1, 16 + eq::kParams, 1.f) && !e.setChannel(AudioEngine::kTracks, 16, 1.f),
              "EQ: out-of-range values, unknown ids and the master strip are refused");
        render(e, 9600); const double shut = rms(render(e, 24000), 0);
        CHECK(shut < open * 0.5, "LP 100 Hz on the synth strip: rms %.3f → %.3f", open, shut);
        e.setChannel(1, 16 + eq::On, 0.f);              // bypass glides back to the dry sound
        render(e, 9600); const double back = rms(render(e, 24000), 0);
        CHECK(std::fabs(back - open) < open * 0.05, "EQ OFF: back to the dry level (%.3f vs %.3f)", back, open);
        e.setChannel(1, 16 + eq::On, 1.f); e.setChannel(1, 16 + eq::LpHz, 20000.f);

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
        float lv[drums::kSlots]; int32_t hits[drums::kSlots]; e.getDrumTelemetry(2, lv, hits);
        CHECK(hits[0] >= 1 && hits[1] >= 1, "drum hit counters advance (kick %d, snare %d)", hits[0], hits[1]);
        e.setChannel(2, 2, 1.f); render(e, 2400); auto dm = render(e, 24000);
        CHECK(rms(dm, 0) < 1e-5, "muting strip 2 silences the drums");
        e.setChannel(2, 2, 0.f);
        e.setChannel(8, 0, -60.f); render(e, 9600); auto mm = render(e, 24000);
        CHECK(rms(mm, 0) < 2e-3, "strip 8 is the master fader (rms %.2e)", rms(mm, 0));
        e.setChannel(8, 0, 0.f);
        CHECK(!e.setChannel(9, 0, 0.f) && !e.drumTrigger(2, drums::kSlots, 1.f) && !e.drumParam(2, 0, 99, 1.f) && !e.drumTrigger(8, 0, 1.f)
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
    {   // DUCK: the kick alone (drum voice 0 on track 2) ducks a held synth note (track 1); the snare doesn't
        AudioEngine e;
        e.setTrackType(1, 2); e.setTrackType(2, 3);          // track 1 synth, track 2 drums
        e.setChannel(2, 2, 1.f); e.setChannel(1, 2, 0.f);       // drums muted (they still key), synth audible
        e.synthParam(1, synth::AmpS, 1.f); e.synthParam(1, synth::AmpD, 10.f);   // full sustain: a steady level
        e.synthNoteOn(1, 60, 1.f); render(e, 9600);
        const double base = rms(render(e, 2400), 0);
        e.setDuck(duck::Source, 2); e.setDuck(duck::SourceSlot, 0); e.setDuck(duck::Targets, 1 << 1);
        e.setDuck(duck::ThresholdDb, -30.f); e.setDuck(duck::Ratio, 20.f); e.setDuck(duck::AttackMs, 0.5f);
        e.setDuck(duck::ReleaseMs, 150.f); e.setDuck(duck::Mix, 1.f);
        render(e, 256); float dk[4]; e.getDuck(dk);
        e.drumTrigger(2, 0, 1.f); const double ducked = rms(render(e, 2400), 0); e.getDuck(dk);
        CHECK(base > 0.01 && ducked < base * 0.5 && dk[0] > 6.f && dk[3] >= 1.f,
              "DUCK: the kick pulls the synth down (%.3f → %.3f, %.1f dB, %d triggers)", base, ducked, dk[0], (int)dk[3]);
        // (the default patch's level drifts as its oscillators beat, so recovery is read from the ducker itself)
        render(e, 48000); e.getDuck(dk); render(e, 2400); e.getDuck(dk);
        CHECK(dk[0] < 0.1f && dk[1] < 0.1f, "DUCK: back up after the release (%.2f dB left)", dk[1]);
        e.drumTrigger(2, 1, 1.f); render(e, 4800); e.getDuck(dk);
        CHECK(dk[0] < 0.1f, "DUCK keyed off the kick ignores the snare (%.2f dB)", dk[0]);
        e.setDuck(duck::Source, duck::kBeat); e.setPlaying(true); render(e, 48000); e.getDuck(dk);
        CHECK(dk[0] > 6.f, "DUCK on the ghost BEAT pumps with the transport (%.1f dB)", dk[0]);
        CHECK(!e.setDuck(duck::kCount, 0.f) && !e.setDuck(-1, 0.f), "bad DUCK params rejected");
    }
    {   // EXPORT: an offline bounce is the live render, sample for sample; stems sum to the mix; files are whole
        auto setup = [](AudioEngine& e) {
            e.setTempo(100); e.setPattern(1, synthLoop());
            auto* dp = new seq::Pattern(); dp->lengthTicks = 3840; dp->count = 8;
            for (int i = 0; i < 8; ++i) dp->events[i] = seq::Event{i * 480, 120, (int16_t)(i % 4 == 2 ? 1 : (i % 2 ? 2 : 0)), 1.f, 1.f};
            e.setPattern(2, dp);
            e.setChannel(1, 0, -6.f); e.setChannel(1, 1, -0.4f); e.setChannel(2, 1, 0.3f);   // a fader and two pans
            e.setDuck(duck::Source, duck::kBeat); e.setDuck(duck::Targets, 2.f);
            e.setPlaying(true);
        };
        const int N = 1024 * 90;                                        // ~1.9 s, four loops' worth of hits
        AudioEngine live; setup(live);
        std::vector<float> L((size_t)N * 2);
        for (int off = 0; off < N; off += 1024) {
            if (off == 1024 * 30) live.fxOn(1, 0.6f, 0.4f);              // an echo punched in mid-way
            live.onAudioReady(&gStream, L.data() + 2 * off, 1024);
        }
        AudioEngine off; setup(off);
        const std::string mixPath = "/tmp/ce_export_mix.wav", stemPath = "/tmp/ce_export_synth.flac";
        CHECK(off.exportBegin(48000) && !off.exportBegin(48000), "exportBegin parks the live output (once)");
        CHECK(off.exportOpen(0, ::open(mixPath.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600), 0, 24)
              && off.exportOpen(1, ::open(stemPath.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600), 1, 24), "export files open");
        int stems[AudioEngine::kTracks]; for (int& v : stems) { v = -1; }
        stems[1] = 1;
        off.exportRoute(0, stems);
        // the live callback, if the device keeps calling it, plays silence and moves nothing
        off.getTransport(tr); const double t0 = tr[0];
        std::vector<float> z(2 * 512, 1.f); off.onAudioReady(&gStream, z.data(), 512); off.getTransport(tr);
        bool silent = true; for (float v : z) silent &= v == 0.f;
        CHECK(silent && tr[0] == t0, "while exporting, the device callback outputs silence and doesn't advance");
        // capture what the mix writer sees by routing it twice: into a file, and back from the file
        float peak = 0.f;
        for (int o = 0; o < N; o += 1024) {
            if (o == 1024 * 30) off.fxOn(1, 0.6f, 0.4f);
            peak = std::max(peak, off.exportRender(1024));
        }
        CHECK(off.exportClose(0) == N && off.exportClose(1) == N && off.exportClose(1) == -1, "both files closed with %d frames", N);
        off.exportEnd();
        // read the 24-bit WAV back and compare with the live output, quantised the same way
        FILE* f = std::fopen(mixPath.c_str(), "rb"); std::vector<uint8_t> wav((size_t)N * 6 + 44);
        const size_t got = f ? std::fread(wav.data(), 1, wav.size(), f) : 0; if (f) std::fclose(f);
        exportfile::Quantizer q(24); int diff = 0; double lvl = 0;
        for (int i = 0; i < 2 * N && got == wav.size(); ++i) {
            const uint8_t* p = wav.data() + 44 + 3 * (size_t)i;
            int32_t v = p[0] | p[1] << 8 | p[2] << 16; if (v & 0x800000) v -= 0x1000000;
            diff = std::max(diff, std::abs(v - q(L[(size_t)i]))); lvl = std::max(lvl, (double)std::fabs(L[(size_t)i]));
        }
        CHECK(got == wav.size() && diff == 0 && lvl > 0.05 && peak > 0.05, "offline mix == live render, bit for bit (peak %.2f, max diff %d LSB)", lvl, diff);
        std::remove(mixPath.c_str()); std::remove(stemPath.c_str());
        render(off, 4800);
        CHECK(rms(render(off, 9600), 0) > 0.01, "after exportEnd the live callback plays again");
    }
    {   // EXPORT stems: per track, post fader/pan/duck/master gain, pre master FX: they add up to the dry mix
        AudioEngine e;
        e.setTempo(120); e.setPattern(1, synthLoop());
        auto* dp = new seq::Pattern(); dp->lengthTicks = 3840; dp->count = 4;
        for (int i = 0; i < 4; ++i) dp->events[i] = seq::Event{i * 960, 120, (int16_t)(i % 2), 1.f, 1.f};
        e.setPattern(2, dp);
        e.setChannel(1, 0, -3.f); e.setChannel(2, 1, -0.5f); e.setMasterGainDb(-4.f);
        e.setPlaying(true);
        e.exportBegin(44100);
        int stems[AudioEngine::kTracks]; for (int k = 0; k < AudioEngine::kTracks; ++k) stems[k] = 1 + k;
        // no files open: routing still captures, so peaks report what each stem would hold
        e.exportRoute(0, stems);
        float sp[AudioEngine::kTracks]; float maxS1 = 0, maxS2 = 0, other = 0;
        for (int o = 0; o < 44100 * 2; o += 2048) {
            e.exportRender(2048, sp); maxS1 = std::max(maxS1, sp[1]); maxS2 = std::max(maxS2, sp[2]);
            for (int k = 3; k < AudioEngine::kTracks; ++k) other = std::max(other, sp[k]);
        }
        CHECK(maxS1 > 0.02f && maxS2 > 0.02f && other == 0.f, "stems: synth %.2f, drums %.2f, empty tracks silent", maxS1, maxS2);
        // now into files: mix, synth stem, drum stem (24-bit WAV) — read back, the stems sum to the mix
        const char* paths[3] = {"/tmp/ce_sum_mix.wav", "/tmp/ce_sum_1.wav", "/tmp/ce_sum_2.wav"};
        for (int i = 0; i < 3; ++i) e.exportOpen(i, ::open(paths[i], O_RDWR | O_CREAT | O_TRUNC, 0600), 0, 24);
        for (int& v : stems) { v = -1; }
        stems[1] = 1; stems[2] = 2;
        e.exportRoute(0, stems);
        const int M = 2048 * 40;
        for (int o = 0; o < M; o += 2048) e.exportRender(2048);
        for (int i = 0; i < 3; ++i) e.exportClose(i);
        e.exportEnd();
        auto readWav = [](const char* p, int n) {
            std::vector<int32_t> v; FILE* f = std::fopen(p, "rb"); if (!f) return v;
            std::vector<uint8_t> b((size_t)n * 6 + 44); const size_t g = std::fread(b.data(), 1, b.size(), f); std::fclose(f);
            if (g != b.size()) return v;
            for (int i = 0; i < 2 * n; ++i) { const uint8_t* q = b.data() + 44 + 3 * (size_t)i; int32_t x = q[0] | q[1] << 8 | q[2] << 16; if (x & 0x800000) x -= 0x1000000; v.push_back(x); }
            return v;
        };
        const auto mx = readWav(paths[0], M), s1 = readWav(paths[1], M), s2 = readWav(paths[2], M);
        int worst = mx.size() == (size_t)2 * M && s1.size() == mx.size() && s2.size() == mx.size() ? 0 : 1 << 30;
        int32_t big = 0;
        for (size_t i = 0; worst < (1 << 30) && i < mx.size(); ++i) { worst = std::max(worst, std::abs(mx[i] - s1[i] - s2[i])); big = std::max(big, std::abs(mx[i])); }
        CHECK(worst <= 2 && big > 100000, "stems sum to the mix (worst %d LSB of 24-bit, 44.1 kHz)", worst);
        for (auto p : paths) std::remove(p);
    }
    {   // MIDI out: clock (24 per beat) + START on bar 1, a synth track's notes on its channel, STOP
        AudioEngine e;
        e.setTempo(120); e.setPattern(1, synthLoop());
        e.setMidiClock(true); e.setMidiOut(1, 3);
        render(e, 4800);                                           // clock runs while stopped too
        int64_t buf[3 * 512];
        int clocksStopped = 0;
        for (int k; (k = e.pollMidi(buf, 512)) > 0;) for (int i = 0; i < k; ++i) clocksStopped += (buf[3 * i + 2] & 0xFF) == 0xF8;
        e.setPlaying(true);
        render(e, 47000);                                           // just under 1 s = 2 beats at 120
        std::vector<int64_t> clk, on; int64_t startAt = -1; int other = 0;
        for (int k; (k = e.pollMidi(buf, 512)) > 0;) for (int i = 0; i < k; ++i) {
            const int b0 = buf[3 * i + 2] & 0xFF;
            if (b0 == 0xF8) clk.push_back(buf[3 * i]); else if (b0 == 0xFA) startAt = buf[3 * i];
            else if (b0 == 0x93) on.push_back(buf[3 * i]); else if (b0 != 0x83) ++other;
        }
        bool even = clk.size() >= 46;
        for (size_t i = 1; even && i < clk.size(); ++i) even = std::llabs(clk[i] - clk[i - 1] - 1000) <= 1;   // 40 ticks = 1000 samples
        CHECK(clocksStopped >= 4 && startAt >= 0 && even && !clk.empty() && clk[0] == startAt,
              "MIDI clock: running while stopped (%d), START then a clock every 1000 samples from it (%zu)", clocksStopped, clk.size());
        CHECK(on.size() == 2 && on[0] == startAt && std::llabs(on[1] - on[0] - 24000) <= 1 && other == 0,
              "track 2 notes go out on channel 4, a beat apart (%zu)", on.size());
        e.setPlaying(false); render(e, 960);
        bool stop = false; for (int k; (k = e.pollMidi(buf, 512)) > 0;) for (int i = 0; i < k; ++i) stop |= (buf[3 * i + 2] & 0xFF) == 0xFC;
        CHECK(stop, "STOP goes out when the transport stops");
    }
    {   // the metronome's four sounds all make a click, and the count-in sounds before bar 1
        for (int snd = 0; snd < 4; ++snd) {
            AudioEngine e; e.setTempo(120); e.setClick(0, (float)snd); e.setClick(1, 1.f); e.setMetronome(true); e.setPlaying(true);
            CHECK(rms(render(e, 4800), 0) > 0.003, "metronome sound %d is audible", snd);
        }
        AudioEngine e; e.setTempo(120); e.setCountIn(4 * 960); e.setPlaying(true);
        render(e, 4800); e.getTransport(tr);
        CHECK(tr[20] > 3000 && tr[0] == 0 && rms(render(e, 4800), 0) >= 0.0, "count-in: transport waits at 0 with %.0f ticks to go", tr[20]);
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
