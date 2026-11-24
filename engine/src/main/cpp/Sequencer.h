// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Sample-accurate clock + pattern sequencer + note repeat + metronome.
// Header-only and Android-free, so it's tested on a desktop (engine/src/test/cpp/sequencer_test.cpp).
//
// Time is in TICKS (960 per quarter note). The clock always runs, so note repeat works with the
// transport stopped, like an MPC. PLAY resets it to bar 1 and enables pattern playback.
//
// Groove: MPC-style swing delays every odd grid step (1/8 or 1/16) by
//     (swing - 0.5) * 2 * gridTicks,  swing ∈ [0.50, 0.75]   (50% straight, ~66% triplet shuffle)
// applied at playback, so changing swing never rewrites the pattern. Only notes within ±1/8 of
// a grid step of an odd grid position are swung, so triplets and free timing stay put.
//
// RT-safety: process() never allocates. Patterns are immutable POD blocks allocated on the main
// thread and swapped in by pointer (ownership returns through the engine's event queue).

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace seq {

constexpr int kPpqn = 960;
constexpr int kTracks = 8;            // any mix of sampler (note = pad), synth (note = MIDI) and drum (note = slot) tracks
constexpr int kMaxEvents = 4096;      // per pattern (64 bars of 16ths, with chords and drum layers)
constexpr int kMaxBars = 64;
constexpr int kMaxPendingOffs = 256;
constexpr int kMaxRepeats = 16;

constexpr int kLocks = 2;     // parameter locks per step

struct Event {
    int32_t tick = 0;      // 0 .. lengthTicks-1, unswung
    int32_t length = 120;  // ticks
    int16_t note = 0;
    float velocity = 0.8f; // 0..1
    float probability = 1.f;
    // Step components
    int8_t ratchet = 1;    // 1..4 retriggers spread across the note's length
    int8_t condEvery = 1;  // play on loop `condPhase` of every `condEvery` loops (1 = always)
    int8_t condPhase = 0;
    int8_t lockId[kLocks] = {-1, -1};   // synth::Param ids (-1 = none)
    float lockVal[kLocks] = {0.f, 0.f};
};

struct Pattern {
    int32_t lengthTicks = 16 * 240;
    int32_t count = 0;
    // When the events are sorted by tick (buildPattern does it), each block only looks at the ones
    // that can land in it: ticks in [from - reach, to), reach = how far a note can sound after its
    // tick (swing delay + ratchet spread). Unsorted patterns (hand-built in tests) are scanned whole.
    bool sorted = false;
    int32_t reach = 0;
    Event events[kMaxEvents];
};

struct Fire {
    int32_t offset;   // sample offset in block
    int8_t track;     // -1 = metronome
    bool on;
    int16_t note;     // metronome: 1 = downbeat
    float velocity;
    bool fromRepeat;  // note-repeat hits are recorded like finger hits
    const Event* src; // pattern event (for parameter locks), or nullptr
};

class Sequencer {
public:
    // ---- control (audio thread, from drained commands) ----
    void setTempo(float bpm) { mBpm = std::clamp(bpm, 30.f, 300.f); }
    void setSwing(float amount, int gridTicks) {
        mSwing = std::clamp(amount, 0.5f, 0.75f);
        mSwingGrid = (gridTicks == 480) ? 480 : 240;
    }
    void setPlaying(bool p) {
        if (p && !mPlaying) {
            mTick = 0.0; mOrigin = 0.0; for (auto& l : mLastLive) for (auto& v : l) v = -1e18;
            mPreroll = mCountInTotal = mCountIn;                 // count-in: clicks first, then bar 1
            if (mPreroll <= 0.0) mStartPending = true;
        }
        if (!p && mPlaying) { mFlushOffs = true; mPreroll = 0.0; mStopPending = true; }   // stop → release everything the pattern started
        mPlaying = p;
    }
    void setMetronome(bool on) { mMetro = on; }
    /** Ticks of count-in clicks before the next PLAY reaches bar 1 (0 = none). */
    void setCountIn(double ticks) { mCountIn = std::clamp(ticks, 0.0, 4.0 * 4 * kPpqn); }
    /** Count-in ticks still to go (0 once bar 1 has started). */
    double preroll() const { return mPreroll; }
    /** Where in the last process() block bar 1 started (PLAY, or the end of a count-in), else -1. Clears. */
    int takeStart() { const int s = mStartAt; mStartAt = -1; return s; }
    /** True once after the transport stopped. Clears. */
    bool takeStop() { const bool s = mStopPending; mStopPending = false; return s; }
    // Returns the previous pattern (caller hands it back to the main thread for deletion).
    const Pattern* setPattern(int track, const Pattern* p) {
        if (track < 0 || track >= kTracks) return p;
        const Pattern* old = mPattern[track];
        mPattern[track] = p;
        return old;
    }
    const Pattern* pattern(int track) const { return (track >= 0 && track < kTracks) ? mPattern[track] : nullptr; }

    // ---- scenes: a whole set of patterns that changes together, exactly on a loop boundary ----
    // stage() parks a track's next pattern (nullptr = empty); commit() arms the change for the next
    // multiple of [loopTicks] counted from the current pattern's start (the origin), and tags it so
    // the app can tell when it happened. Stopped, or loopTicks <= 0: the change is immediate. Every
    // pattern this lets go of (the replaced ones, an overwritten stage) is handed out by retired().
    void stage(int track, const Pattern* p) {
        if (track < 0 || track >= kTracks) { retire(p); return; }
        if (mStaged[track]) retire(mStage[track]);
        mStage[track] = p; mStaged[track] = true;
    }
    void commit(int loopTicks, int tag) {
        if (tag < 0) {                                           // cancel: drop what's staged
            for (int t = 0; t < kTracks; ++t) if (mStaged[t]) { retire(mStage[t]); mStage[t] = nullptr; mStaged[t] = false; }
            mArmed = false; return;
        }
        mQueueTag = tag;
        if (!mPlaying || loopTicks <= 0) { swapScene(mPlaying ? mTick : 0.0); return; }
        const double k = std::ceil((mTick - mOrigin) / loopTicks - 1e-9);
        mQueueAt = mOrigin + std::max(0.0, k) * loopTicks;
        mArmed = true;
    }
    double origin() const { return mOrigin; }
    int sceneTag() const { return mSceneTag; }
    int queuedTag() const { return mArmed ? mQueueTag : -1; }
    double queuedAt() const { return mArmed ? mQueueAt : -1.0; }
    /** Patterns let go of since the last call (the caller frees them off the audio thread). */
    const Pattern* retired() { return mRetiredCount > 0 ? mRetired[--mRetiredCount] : nullptr; }

    void repeatOn(int track, int note, int rateTicks, float vel) {
        if (track < 0 || track >= kTracks) return;
        rateTicks = std::clamp(rateTicks, 40, 1920);
        Repeat* free = nullptr;
        for (auto& r : mRepeats) {
            if (r.active && r.track == track && r.note == note) { free = &r; break; }
            if (!r.active && !free) free = &r;
        }
        if (!free) return;
        free->active = true; free->track = track; free->note = note; free->rate = rateTicks; free->vel = vel;
        free->fireNow = true;                                  // first hit is immediate (feels tight)
    }
    void repeatOff(int track, int note) {
        for (auto& r : mRepeats) if (r.active && r.track == track && r.note == note) r.active = false;
    }
    void setRepeatVelocity(float v) { for (auto& r : mRepeats) if (r.active) r.vel = std::clamp(v, 0.f, 1.f); }

    // A live (finger) hit was just recorded at the current tick: suppress the pattern's copy of it
    // for the next 1/16 so a forward-quantized recording doesn't double-trigger on this pass.
    void noteLiveHit(int track, int note) {
        if (track < 0 || track >= kTracks || note < 0 || note > 127) return;
        mLastLive[track][note] = mTick;
    }

    double tick() const { return mTick; }
    double ticksPerSample(float sr) const { return (double)mBpm / 60.0 * kPpqn / (double)sr; }
    float swing() const { return mSwing; }
    bool playing() const { return mPlaying; }
    float bpm() const { return mBpm; }

    // Produces Fires for the next n frames, sorted by offset. Returns the count.
    int process(int n, float sr, Fire* out, int max) {
        mStartAt = -1;
        if (mStartPending) { mStartPending = false; mStartAt = 0; }
        if (!mPlaying || mPreroll <= 0.0) return processFrom(0, n, sr, out, max);
        // Count-in: the pattern clock waits at 0 while the clicks count the bars in.
        const double tps = (double)mBpm / 60.0 * kPpqn / (double)sr;
        const int m = std::min(n, (int)std::ceil(mPreroll / tps - 1e-9));
        const double p0 = mCountInTotal - mPreroll, p1 = p0 + m * tps;
        int count = 0;
        for (int64_t b = (int64_t)std::ceil(p0 / kPpqn); (double)b * kPpqn < p1 && (double)b * kPpqn < mCountInTotal - 1e-6 && count < max; ++b)
            out[count++] = Fire{std::clamp((int)std::floor(((double)b * kPpqn - p0) / tps), 0, m - 1), -1, true, (int16_t)((b % 4 == 0) ? 1 : 0), 1.f, false, nullptr};
        mPreroll -= m * tps;
        if (mPreroll <= 1e-6) { mPreroll = 0.0; mStartAt = m < n ? m : n - 1; }
        if (m < n) count += processFrom(m, n - m, sr, out + count, max - count);
        return count;
    }

private:
    // process() for frames [base, base + n) of the caller's block.
    int processFrom(int base, int n, float sr, Fire* out, int max) {
        int count = 0;
        const double tps = (double)mBpm / 60.0 * kPpqn / (double)sr;  // ticks per sample
        const double t0 = mTick, t1 = mTick + tps * n;
        // Blocks partition the tick line exactly ([t0, t1) then [t1, …)), so every event lands in
        // exactly one block: never missed, never doubled. Rounding can put it ±1 sample off.
        bool rep = false;   // set while emitting note-repeat hits
        const Event* cur = nullptr;   // pattern event being emitted (carries p-locks on its first hit)
        auto emit = [&](double atTick, int track, bool on, int note, float vel) {
            if (count >= max) return;
            const int off = (int)std::floor((atTick - t0) / tps);
            out[count++] = Fire{base + std::clamp(off, 0, n - 1), (int8_t)track, on, (int16_t)note, vel, rep && on, cur};
        };

        if (mFlushOffs) {
            for (int i = 0; i < mOffCount; ++i) emit(t0, mOffs[i].track, false, mOffs[i].note, 0.f);
            mOffCount = 0; mFlushOffs = false;
        }

        // Pending note-offs
        for (int i = 0; i < mOffCount;) {
            if (mOffs[i].tick < t1) { emit(std::max(t0, mOffs[i].tick), mOffs[i].track, false, mOffs[i].note, 0.f); mOffs[i] = mOffs[--mOffCount]; }
            else ++i;
        }

        // Pattern events in [a, b), each track's loop counted from the scene's origin.
        auto patterns = [&](double a, double b) {
            for (int tr = 0; tr < kTracks; ++tr) {
                const Pattern* p = mPattern[tr];
                if (!p || p->lengthTicks <= 0 || b <= a) continue;
                const double len = p->lengthTicks;
                const int64_t k0 = (int64_t)std::floor((a - mOrigin) / len), k1 = (int64_t)std::floor((b - mOrigin) / len);
                // k0-1: a swung note near the loop end can spill into the next loop's first block.
                for (int64_t k = k0 - 1; k <= k1; ++k) {
                    if (k < 0) continue;
                    int e0 = 0, e1 = p->count;
                    if (p->sorted) {                                  // only the events that can land in [a, b)
                        const double base = mOrigin + (double)k * len;
                        const double lo = a - base - p->reach, hi = b - base;
                        int L = 0, R = p->count;
                        while (L < R) { const int M = (L + R) / 2; if ((double)p->events[M].tick < lo) L = M + 1; else R = M; }
                        e0 = L; R = p->count;
                        while (L < R) { const int M = (L + R) / 2; if ((double)p->events[M].tick < hi) L = M + 1; else R = M; }
                        e1 = L;
                    }
                    for (int e = e0; e < e1; ++e) {
                        const Event& ev = p->events[e];
                        const double st = swung(ev.tick);
                        const int rat = std::clamp<int>(ev.ratchet, 1, 4);
                        const double sub = std::max(10.0, (double)ev.length / rat);   // ratchet spacing
                        // Trig condition: loop k plays if k mod every == phase (loops count from the scene's start = 0).
                        if (ev.condEvery > 1 && (k % ev.condEvery) != std::clamp<int>(ev.condPhase, 0, ev.condEvery - 1)) continue;
                        for (int r = 0; r < rat; ++r) {
                            const double at = mOrigin + (double)k * len + st + r * sub;
                            if (at < a || at >= b) continue;
                            if (r == 0 && ev.note >= 0 && ev.note < 128 && at - mLastLive[tr][ev.note] < 240.0 && at >= mLastLive[tr][ev.note]) continue;
                            // Probability is decided once per step (on its first hit); ratchet hits follow it.
                            if (r == 0) mStepPass[tr] = !(ev.probability < 1.f && rand01() >= ev.probability);
                            if (!mStepPass[tr]) continue;
                            cur = (r == 0) ? &ev : nullptr;
                            // Ratchet hits after the first get a gentle velocity fall-off, like a drum roll.
                            emit(at, tr, true, ev.note, ev.velocity * (1.f - 0.12f * r));
                            cur = nullptr;
                            addOff(at + (rat > 1 ? sub * 0.6 : std::max(10, ev.length)), tr, ev.note);
                        }
                    }
                }
            }
        };
        if (mPlaying) {
            if (mArmed && mQueueAt < t1) {
                // the scene changes inside this block: the old patterns up to the boundary, the new ones after
                const double cut = std::max(mQueueAt, t0);
                patterns(t0, cut);
                swapScene(mQueueAt);
                patterns(cut, t1);
            } else patterns(t0, t1);
            if (mMetro) {
                for (int64_t b = (int64_t)std::ceil(t0 / kPpqn); (double)b * kPpqn < t1; ++b)
                    emit((double)b * kPpqn, -1, true, (b % 4 == 0) ? 1 : 0, 1.f);
            }
        }

        // Note repeat: fires on the (swung) repeat grid of the always-running clock.
        rep = true;
        for (auto& r : mRepeats) {
            if (!r.active) continue;
            if (r.fireNow) {
                r.fireNow = false;
                emit(t0, r.track, true, r.note, r.vel);
                addOff(t0 + r.rate * 0.5, r.track, r.note);
                r.lastGrid = std::floor(t0 / r.rate);           // don't double-fire this grid step
            }
            // Start one step back: a swung (delayed) step from before t0 may land inside this block.
            for (double g = std::max(std::floor(t0 / r.rate) - 1.0, r.lastGrid + 1); g * r.rate < t1 + r.rate; g += 1.0) {
                const double at = swung(g * r.rate);
                if (at >= t1) break;
                if (at < t0) continue;
                emit(at, r.track, true, r.note, r.vel);
                r.lastGrid = g;
                addOff(at + r.rate * 0.5, r.track, r.note);
            }
        }

        rep = false;
        mTick = t1;
        (void)base;
        std::sort(out, out + count, [](const Fire& a, const Fire& b) {
            return a.offset != b.offset ? a.offset < b.offset : (!a.on && b.on);   // offs before ons at the same sample
        });
        return count;
    }

public:
    // Exposed for tests and for the UI's playhead maths.
    double swung(double tick) const {
        if (mSwing <= 0.5f) return tick;
        const double g = mSwingGrid;
        const double idx = std::round(tick / g);
        if (std::fabs(tick - idx * g) > g / 8.0) return tick;            // not on the grid (triplets, free timing)
        if (((int64_t)idx & 1) == 0) return tick;                        // even steps stay put
        return tick + (mSwing - 0.5) * 2.0 * g;
    }

private:
    struct Off { double tick; int8_t track; int16_t note; };
    struct Repeat { bool active = false, fireNow = false; int track = 0, note = 0; double rate = 240; float vel = 0.8f; double lastGrid = -1; };

    void addOff(double at, int track, int note) {
        if (mOffCount < kMaxPendingOffs) mOffs[mOffCount++] = Off{at, (int8_t)track, (int16_t)note};
    }
    float rand01() {   // xorshift32
        mRng ^= mRng << 13; mRng ^= mRng >> 17; mRng ^= mRng << 5;
        return (float)(mRng & 0xFFFFFF) / 16777216.f;
    }

    double mTick = 0.0;
    float mBpm = 90.f;
    float mSwing = 0.5f;
    int mSwingGrid = 240;
    bool mPlaying = false, mMetro = false, mFlushOffs = false;
    double mCountIn = 0.0, mCountInTotal = 0.0, mPreroll = 0.0;
    bool mStartPending = false, mStopPending = false;
    int mStartAt = -1;
    const Pattern* mPattern[kTracks] = {};
    Off mOffs[kMaxPendingOffs];
    int mOffCount = 0;
    Repeat mRepeats[kMaxRepeats];
    double mLastLive[kTracks][128];
    uint32_t mRng = 0xC0FFEEu;
    bool mStepPass[kTracks] = {true, true, true, true, true, true, true, true};
    // scenes
    double mOrigin = 0.0, mQueueAt = 0.0;
    bool mArmed = false;
    int mQueueTag = 0, mSceneTag = 0;
    const Pattern* mStage[kTracks] = {};
    bool mStaged[kTracks] = {};
    const Pattern* mRetired[4 * kTracks] = {};
    int mRetiredCount = 0;
    void retire(const Pattern* p) { if (p && mRetiredCount < 4 * kTracks) mRetired[mRetiredCount++] = p; }   // (full: leak, never a use-after-free)
    void swapScene(double at) {
        for (int t = 0; t < kTracks; ++t) if (mStaged[t]) { retire(mPattern[t]); mPattern[t] = mStage[t]; mStage[t] = nullptr; mStaged[t] = false; }
        mOrigin = at; mSceneTag = mQueueTag; mArmed = false;
    }

public:
    Sequencer() { for (auto& l : mLastLive) for (auto& v : l) v = -1e18; }
};

} // namespace seq
