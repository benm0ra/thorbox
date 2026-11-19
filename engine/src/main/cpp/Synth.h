// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Polyphonic 2-oscillator morphing synth. Header-only and Android-free, so it can be tested
// on a desktop (engine/src/test/cpp/synth_test.cpp).
//
// Signal path per voice:
//   osc1 (shape morph) ─┐
//                       ├─ equal-power mix ─ soft drive ─ TPT state-variable LPF ─ amp env ─ out
//   osc2 (shape morph, detune, octave) ─┘
// Modulation (control rate, every kCtrl samples): filter env, key tracking, global LFO
// → {pitch | cutoff | amp | shape}.
//
// Everything here is RT-safe: no allocation, no locks, no syscalls. Parameters arrive already
// validated (finite, clamped) from the JNI layer; setParam clamps again defensively.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace synth {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 6.28318530717959f;
constexpr int kVoices = 8;
constexpr int kCtrl = 16;   // control-rate sub-block (3 kHz at 48 kHz): filter coefs, LFO, pitch

// Must match com.thorbox.engine.SynthParam ids. Units are physical (Kotlin owns the knob curves).
enum Param : int {
    Osc1Shape = 0,   // 0..1  sine → tri → saw → square
    Osc2Shape,       // 0..1
    Osc2Detune,      // cents -100..100
    OscMix,          // 0..1  (0 = osc1 only, 1 = osc2 only, equal-power)
    Osc1Octave,      // -3..3
    Osc2Octave,      // -3..3
    CutoffHz,        // 20..20000
    Resonance,       // 0..1
    FilterEnvAmt,    // -1..1  (±5 octaves)
    KeyTrack,        // 0..1
    AmpA, AmpD, AmpS, AmpR,       // ms, ms, 0..1, ms
    FEnvA, FEnvD, FEnvS, FEnvR,   // ms, ms, 0..1, ms
    LfoRateHz,       // 0.05..20
    LfoDepth,        // 0..1
    LfoShape,        // 0 sine, 1 tri, 2 saw, 3 square, 4 sample&hold
    LfoTarget,       // 0 pitch, 1 cutoff, 2 amp, 3 shape
    GainDb,          // -40..+6
    kParamCount
};

struct ParamRange { float lo, hi, def; };
inline constexpr std::array<ParamRange, kParamCount> kRanges = {{
    {0, 1, 0.66f}, {0, 1, 1.f}, {-100, 100, 7.f}, {0, 1, 0.35f}, {-3, 3, 0}, {-3, 3, 0},
    {20, 20000, 1800.f}, {0, 1, 0.25f}, {-1, 1, 0.45f}, {0, 1, 0.5f},
    {0.5f, 10000, 5}, {1, 10000, 250}, {0, 1, 0.8f}, {1, 10000, 300},
    {0.5f, 10000, 2}, {1, 10000, 350}, {0, 1, 0.15f}, {1, 10000, 300},
    {0.05f, 20, 1.5f}, {0, 1, 0.2f}, {0, 4, 0}, {0, 3, 1}, {-40, 6, -9},
}};

// ---------- small fast math ----------
inline float fastSin(float x) {   // x in [-π, π], max err ≈ 0.001
    const float B = 4.f / kPi, C = -4.f / (kPi * kPi);
    float y = B * x + C * x * std::fabs(x);
    return 0.225f * (y * std::fabs(y) - y) + y;
}
inline float softClip(float x) {   // Padé tanh approximation, clamped
    x = std::clamp(x, -3.f, 3.f);
    return x * (27.f + x * x) / (27.f + 9.f * x * x);
}
inline float polyBlep(float t, float dt) {
    if (t < dt) { t /= dt; return t + t - t * t - 1.f; }
    if (t > 1.f - dt) { t = (t - 1.f) / dt; return t * t + t + t + 1.f; }
    return 0.f;
}
inline float noteHz(float note) { return 440.f * std::exp2((note - 69.f) / 12.f); }

// Band-limited shape morph. Each segment crossfades two neighbouring waveforms.
inline float oscSample(float t, float dt, float shape) {
    const float s = std::clamp(shape, 0.f, 1.f) * 3.f;
    const int seg = std::min(2, (int)s);
    const float f = s - (float)seg;
    auto sine = [&] { return fastSin(kTwoPi * t - kPi) * -1.f; };     // phase-aligned sine
    auto tri  = [&] { return 1.f - 4.f * std::fabs(t - 0.5f); };
    auto saw  = [&] { return (2.f * t - 1.f) - polyBlep(t, dt); };
    auto sqr  = [&] {
        float v = t < 0.5f ? 1.f : -1.f;
        float t2 = t + 0.5f; if (t2 >= 1.f) t2 -= 1.f;
        return v + polyBlep(t, dt) - polyBlep(t2, dt);
    };
    switch (seg) {
        case 0:  return sine() * (1.f - f) + tri() * f;
        case 1:  return tri() * (1.f - f) + saw() * f;
        default: return saw() * (1.f - f) + sqr() * f;
    }
}

// ---------- envelope ----------
// One-pole segments: attack aims past 1.0 (so it lands in finite time with a natural curve),
// decay and release approach their targets exponentially. Times are "≈ time to 98%".
struct Adsr {
    enum Stage : int { Off = 0, Attack, Decay, Sustain, Release };
    Stage stage = Off;
    float level = 0.f, coef = 0.f, target = 0.f, sustain = 1.f;
    float stageTime = 0.f;   // seconds in current stage (telemetry / animation)

    static float coefFor(float ms, float sr) {
        const float tau = std::max(0.0005f, ms * 0.001f) / 4.f;
        return 1.f - std::exp(-1.f / (tau * sr));
    }
    void gateOn(float a, float d, float s, float sr) {
        sustain = s; stage = Attack; stageTime = 0.f;
        // Aim at 1.25 and stop at 1.0: that's reached after ln(5)·τ, so scale τ to land on `a` ms.
        target = 1.25f; coef = coefFor(a * 2.486f, sr);
        decayCoef = coefFor(d, sr);
    }
    void gateOff(float r, float sr) {
        if (stage == Off) return;
        stage = Release; stageTime = 0.f; target = 0.f; coef = coefFor(r, sr);
    }
    void setSustain(float s) { sustain = s; if (stage == Sustain || stage == Decay) target = s; }
    inline float tick(float dtSec) {
        stageTime += dtSec;
        level += (target - level) * coef;
        if (stage == Attack && level >= 1.f) {
            level = 1.f; stage = Decay; stageTime = 0.f; target = sustain; coef = decayCoef;
        } else if (stage == Decay && std::fabs(level - sustain) < 1e-3f) {
            stage = Sustain; stageTime = 0.f;
        } else if (stage == Release && level < 1e-4f) {
            level = 0.f; stage = Off;
        }
        return level;
    }
private:
    float decayCoef = 0.f;
};

// ---------- TPT state-variable filter (Simper / Zavalishin), lowpass out ----------
struct Svf {
    float ic1 = 0.f, ic2 = 0.f, a1 = 0.f, a2 = 0.f, a3 = 0.f;
    void set(float fc, float res, float sr) {
        fc = std::clamp(fc, 20.f, 0.45f * sr);
        const float g = std::tan(kPi * fc / sr);
        const float k = 2.f - 1.96f * std::clamp(res, 0.f, 1.f);   // never fully self-oscillates
        a1 = 1.f / (1.f + g * (g + k)); a2 = g * a1; a3 = g * a2;
    }
    inline float process(float v0) {
        const float v3 = v0 - ic2;
        const float v1 = a1 * ic1 + a2 * v3;
        const float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2.f * v1 - ic1; ic2 = 2.f * v2 - ic2;
        return v2;
    }
    void reset() { ic1 = ic2 = 0.f; }
};

struct Voice {
    int note = -1;
    float velocity = 0.f;
    float ph1 = 0.f, ph2 = 0.f;
    float lastCutoff = 1000.f;
    uint64_t stamp = 0;
    Adsr amp, fenv;
    Svf filter;
    bool active() const { return amp.stage != Adsr::Off; }
};

struct Telemetry {   // written by the audio thread once per block, read by UI (see AudioEngine)
    float lfoValue = 0, lfoPhase = 0;
    float ampLevel = 0, ampStage = 0, ampStageTime = 0;
    float fenvLevel = 0, fenvStage = 0, fenvStageTime = 0;
    float cutoffHz = 0, activeVoices = 0, newestNote = -1;
    uint64_t notes[2] = {0, 0};   // bitmask of sounding MIDI notes
};

class Synth {
public:
    Synth() { for (int i = 0; i < kParamCount; ++i) base[i] = p[i] = kRanges[i].def; }

    void setSampleRate(float sr) { mSr = std::max(8000.f, sr); }

    // A parameter lock: overrides one param until the next unlocked step (OP-XY / Elektron "p-lock").
    void lockParam(int id, float v) {
        if (id < 0 || id >= kParamCount) return;
        p[id] = std::clamp(v, kRanges[id].lo, kRanges[id].hi);
        mLocked |= 1u << id;
        if (id == AmpS) for (auto& vc : voices) vc.amp.setSustain(p[id]);
        if (id == FEnvS) for (auto& vc : voices) vc.fenv.setSustain(p[id]);
    }
    void unlockAll() {
        if (!mLocked) return;
        for (int i = 0; i < kParamCount; ++i) if (mLocked & (1u << i)) p[i] = base[i];
        mLocked = 0;
    }

    void setParam(int id, float v) {
        if (id < 0 || id >= kParamCount) return;
        v = std::clamp(v, kRanges[id].lo, kRanges[id].hi);
        base[id] = v;
        if (mLocked & (1u << id)) return;   // a running lock wins until the next unlocked step
        p[id] = v;
        if (id == AmpS) for (auto& vc : voices) vc.amp.setSustain(v);
        if (id == FEnvS) for (auto& vc : voices) vc.fenv.setSustain(v);
    }
    float param(int id) const { return (id >= 0 && id < kParamCount) ? p[id] : 0.f; }

    void noteOn(int note, float vel) {
        if (note < 0 || note > 127) return;
        Voice* v = nullptr;
        for (auto& vc : voices) if (vc.active() && vc.note == note) { v = &vc; break; }   // retrigger same note
        if (!v) for (auto& vc : voices) if (!vc.active()) { v = &vc; break; }
        if (!v) {   // steal: oldest releasing voice first, else oldest overall
            for (auto& vc : voices) if (vc.amp.stage == Adsr::Release && (!v || vc.stamp < v->stamp)) v = &vc;
            if (!v) for (auto& vc : voices) if (!v || vc.stamp < v->stamp) v = &vc;
        }
        const bool wasSilent = !anyActive();
        // Stolen/retriggered voices keep their envelope level and phase, so there's no click.
        if (!v->active()) { v->ph1 = 0.f; v->ph2 = 0.25f; v->filter.reset(); v->amp.level = 0.f; v->fenv.level = 0.f; }
        v->note = note; v->velocity = std::clamp(vel, 0.f, 1.f); v->stamp = ++mStamp;
        v->amp.gateOn(p[AmpA], p[AmpD], p[AmpS], mSr);
        v->fenv.gateOn(p[FEnvA], p[FEnvD], p[FEnvS], mSr);
        if (wasSilent) { mLfoPhase = 0.f; }   // LFO retrigger on the first note
    }

    void noteOff(int note) {
        for (auto& vc : voices)
            if (vc.active() && vc.note == note && vc.amp.stage != Adsr::Release) {
                vc.amp.gateOff(p[AmpR], mSr); vc.fenv.gateOff(p[FEnvR], mSr);
            }
    }
    void allNotesOff() { for (auto& vc : voices) { vc.amp.gateOff(p[AmpR], mSr); vc.fenv.gateOff(p[FEnvR], mSr); } }
    bool anyActive() const { for (auto& v : voices) if (v.active()) return true; return false; }

    // Adds into interleaved stereo `out`. Writes mono synth output to `mono` (for the scope) if non-null.
    void render(float* out, float* mono, int n) {
        const float gain = std::exp2(p[GainDb] / 6.0206f) * 0.5f;   // dB → linear, headroom for chords
        const float mixA = std::cos(p[OscMix] * kPi * 0.5f), mixB = std::sin(p[OscMix] * kPi * 0.5f);
        const float dtSec = 1.f / mSr;
        if (mono) std::fill(mono, mono + n, 0.f);

        for (int off = 0; off < n; off += kCtrl) {
            const int len = std::min(kCtrl, n - off);
            const float lfo = advanceLfo(len) * p[LfoDepth];
            const int target = (int)p[LfoTarget];
            const float lfoPitch = target == 0 ? lfo * 2.f : 0.f;          // ±2 semitones
            const float lfoCut   = target == 1 ? lfo * 4.f : 0.f;          // ±4 octaves
            const float lfoAmp   = target == 2 ? 1.f - 0.5f * (p[LfoDepth] - lfo) : 1.f;   // tremolo in [1-depth, 1]
            const float lfoShape = target == 3 ? lfo * 0.5f : 0.f;
            const float sh1 = std::clamp(p[Osc1Shape] + lfoShape, 0.f, 1.f);
            const float sh2 = std::clamp(p[Osc2Shape] + lfoShape, 0.f, 1.f);

            for (auto& v : voices) {
                if (!v.active()) continue;
                const float note = (float)v.note + lfoPitch;
                const float f1 = noteHz(note + 12.f * p[Osc1Octave]);
                const float f2 = noteHz(note + 12.f * p[Osc2Octave] + p[Osc2Detune] / 100.f);
                const float dt1 = std::min(0.49f, f1 / mSr), dt2 = std::min(0.49f, f2 / mSr);

                const float octs = p[FilterEnvAmt] * 5.f * v.fenv.level * (0.6f + 0.4f * v.velocity)
                                 + p[KeyTrack] * ((float)v.note - 60.f) / 12.f + lfoCut;
                v.lastCutoff = std::clamp(p[CutoffHz] * std::exp2(octs), 20.f, 0.45f * mSr);
                v.filter.set(v.lastCutoff, p[Resonance], mSr);
                const float vgain = gain * (0.3f + 0.7f * v.velocity) * lfoAmp;

                for (int i = 0; i < len; ++i) {
                    float s = mixA * oscSample(v.ph1, dt1, sh1) + mixB * oscSample(v.ph2, dt2, sh2);
                    v.ph1 += dt1; if (v.ph1 >= 1.f) v.ph1 -= 1.f;
                    v.ph2 += dt2; if (v.ph2 >= 1.f) v.ph2 -= 1.f;
                    s = v.filter.process(softClip(s * 1.2f));
                    v.fenv.tick(dtSec);
                    const float a = v.amp.tick(dtSec);
                    s *= a * vgain;
                    out[2 * (off + i)] += s;
                    out[2 * (off + i) + 1] += s;
                    if (mono) mono[off + i] += s;
                    if (!v.active()) break;
                }
            }
        }
        updateTelemetry();
    }

    const Telemetry& telemetry() const { return tel; }

private:
    float advanceLfo(int len) {
        const float shape = p[LfoShape];
        mLfoPhase += p[LfoRateHz] * (float)len / mSr;
        if (mLfoPhase >= 1.f) { mLfoPhase -= std::floor(mLfoPhase); mShValue = nextRandom(); }
        const float t = mLfoPhase;
        switch ((int)shape) {
            case 0:  mLfoValue = fastSin(kTwoPi * t - kPi) * -1.f; break;
            case 1:  mLfoValue = 1.f - 4.f * std::fabs(t - 0.5f); break;
            case 2:  mLfoValue = 2.f * t - 1.f; break;
            case 3:  mLfoValue = t < 0.5f ? 1.f : -1.f; break;
            default: mLfoValue = mShValue; break;
        }
        return mLfoValue;
    }
    float nextRandom() {   // xorshift32 → [-1, 1]
        mRng ^= mRng << 13; mRng ^= mRng >> 17; mRng ^= mRng << 5;
        return (float)(mRng & 0xFFFFFF) / 8388607.5f - 1.f;
    }
    void updateTelemetry() {
        const Voice* newest = nullptr;
        int count = 0;
        tel.notes[0] = tel.notes[1] = 0;
        for (auto& v : voices) {
            if (!v.active()) continue;
            ++count;
            tel.notes[v.note >> 6] |= (1ull << (v.note & 63));
            if (!newest || v.stamp > newest->stamp) newest = &v;
        }
        tel.lfoValue = mLfoValue * p[LfoDepth];
        tel.lfoPhase = mLfoPhase;
        tel.activeVoices = (float)count;
        if (newest) {
            tel.ampLevel = newest->amp.level; tel.ampStage = (float)newest->amp.stage; tel.ampStageTime = newest->amp.stageTime;
            tel.fenvLevel = newest->fenv.level; tel.fenvStage = (float)newest->fenv.stage; tel.fenvStageTime = newest->fenv.stageTime;
            tel.cutoffHz = newest->lastCutoff; tel.newestNote = (float)newest->note;
        } else {
            tel.ampLevel = tel.fenvLevel = 0.f; tel.ampStage = tel.fenvStage = 0.f;
            tel.cutoffHz = p[CutoffHz]; tel.newestNote = -1.f;
        }
    }

    std::array<float, kParamCount> p{};
    std::array<float, kParamCount> base{};   // the patch as set by the UI; p = base + any active locks
    uint32_t mLocked = 0;
    std::array<Voice, kVoices> voices{};
    Telemetry tel;
    float mSr = 48000.f;
    float mLfoPhase = 0.f, mLfoValue = 0.f, mShValue = 0.f;
    uint32_t mRng = 0x9E3779B9u;
    uint64_t mStamp = 0;
};

} // namespace synth
