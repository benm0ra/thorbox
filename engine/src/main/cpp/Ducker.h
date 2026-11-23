// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// DUCK: one sidechain compressor for the whole mix, MPC-style. A key signal (a track, one drum voice,
// or a ghost "beat" pulse on every quarter note) drives a gain computer; the gain it computes turns
// down every TARGET track at once, so a sample chop, a bassline and synth chords all pump off the
// same kick while the drums stay dry.
//
//   detector   fast peak follower on |key| (5 ms fall), in dB
//   computer   soft knee (6 dB) above THRESHOLD, reduction = over · (1 − 1/RATIO); RATIO 20 ≈ a limiter
//   smoothing  gain reduction rises with ATTACK, falls back with RELEASE (both one-pole, in dB)
//   output     g = 1 − MIX · (1 − 10^(−GR/20)), then × MAKEUP
//
// Audio-thread only (no allocation, no locks). Telemetry is read through AudioEngine's atomics.
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace duck {

// Must match com.thorbox.engine.NativeEngine.DuckParam.
enum Param : int32_t {
    Source = 0,     // key track 0…7, -1 off, kBeat = ghost pulse on every quarter note
    SourceSlot,     // drum voice 0…15 when the source is a drum track (-1 = the whole track)
    Targets,        // bitmask of ducked tracks
    ThresholdDb,    // −60 … 0
    Ratio,          // 1 … 20
    AttackMs,       // 0.1 … 50
    ReleaseMs,      // 20 … 1000
    Mix,            // 0 … 1 (how much of the ducking you hear)
    MakeupDb,       // 0 … +12 on the ducked tracks
    kCount
};
constexpr int kBeat = 99;

struct Settings {
    int source = -1, sourceSlot = -1;
    uint32_t targets = 0;
    float thresholdDb = -24.f, ratio = 4.f, attackMs = 2.f, releaseMs = 180.f, mix = 1.f, makeupDb = 0.f;

    void set(int id, float v) {
        switch (id) {
            case Source: { const int s = (int)std::lround(v); source = (s == kBeat || (s >= 0 && s < 64)) ? s : -1; break; }
            case SourceSlot: sourceSlot = std::clamp((int)std::lround(v), -1, 15); break;
            case Targets: targets = (uint32_t)std::clamp((long)std::lround(v), 0L, 0xFFFFL); break;
            case ThresholdDb: thresholdDb = std::clamp(v, -60.f, 0.f); break;
            case Ratio: ratio = std::clamp(v, 1.f, 20.f); break;
            case AttackMs: attackMs = std::clamp(v, 0.1f, 50.f); break;
            case ReleaseMs: releaseMs = std::clamp(v, 20.f, 1000.f); break;
            case Mix: mix = std::clamp(v, 0.f, 1.f); break;
            case MakeupDb: makeupDb = std::clamp(v, 0.f, 12.f); break;
            default: break;
        }
    }
    bool active() const { return source >= 0 && targets != 0 && mix > 0.f; }
};

// Static gain computer (dB in → dB of reduction), soft knee of [kneeDb] around the threshold.
inline float reductionDb(float levelDb, float thresholdDb, float ratio, float kneeDb = 6.f) {
    const float over = levelDb - thresholdDb;
    const float slope = 1.f - 1.f / std::max(1.f, ratio);
    if (2.f * over <= -kneeDb) return 0.f;
    if (2.f * over < kneeDb) { const float t = over + kneeDb * 0.5f; return slope * t * t / (2.f * kneeDb); }
    return slope * over;
}

class Ducker {
public:
    Settings s;

    // key: mono key signal for n frames (already |max(L, R)| or a signed signal, either works).
    // gainOut: the linear gain for the ducked tracks per frame (MIX and MAKEUP applied).
    void process(const float* key, int n, float sr, float* gainOut) {
        const float aC = 1.f - std::exp(-1.f / (std::max(0.05f, s.attackMs) * 0.001f * sr));
        const float rC = 1.f - std::exp(-1.f / (std::max(5.f, s.releaseMs) * 0.001f * sr));
        const float fall = std::exp(-1.f / (0.005f * sr));
        const float makeup = std::pow(10.f, s.makeupDb / 20.f);
        float lvl = mLevel, gr = mGrDb;
        for (int i = 0; i < n; ++i) {
            const float ax = std::fabs(key[i]);
            lvl = ax > lvl ? ax : lvl * fall;
            const float db = 20.f * std::log10(lvl + 1e-9f);
            const float target = std::min(60.f, reductionDb(db, s.thresholdDb, s.ratio));
            gr += (target - gr) * (target > gr ? aC : rC);
            const float g = std::pow(10.f, -gr / 20.f);
            gainOut[i] = (1.f - s.mix * (1.f - g)) * makeup;
            // telemetry: deepest reduction / loudest key since the UI last looked, and trigger count
            grMax = std::max(grMax, gr * s.mix);
            keyPeak = std::max(keyPeak, lvl);
            const bool over = db > s.thresholdDb;
            if (over && !mOver) ++triggers;
            if (over) mOver = true; else if (db < s.thresholdDb - 3.f) mOver = false;   // 3 dB hysteresis
        }
        mLevel = lvl; mGrDb = gr;
    }
    void reset() { mLevel = 0.f; mGrDb = 0.f; mOver = false; }
    float grDb() const { return mGrDb * s.mix; }

    // read-and-clear by the engine's telemetry pass
    float grMax = 0.f, keyPeak = 0.f;
    uint32_t triggers = 0;

private:
    float mLevel = 0.f, mGrDb = 0.f;
    bool mOver = false;
};

// The ghost trigger: a kick-shaped pulse (full level, 35 ms decay) on every quarter note.
inline float beatPulse(double tick, double ppqn, float sr, double tps) {
    if (tps <= 0.0) return 0.f;
    const double ph = std::fmod(tick, ppqn);
    const double secs = ph / tps / sr;
    return (float)std::exp(-secs / 0.035);
}

}  // namespace duck
