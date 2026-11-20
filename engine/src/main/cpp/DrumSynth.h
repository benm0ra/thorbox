// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Analog-style drum machine: 8 voices ("slots"), each one of several synthesis models.
// Header-only and Android-free (tested on the desktop, engine/src/test/cpp/drum_test.cpp).
//
// Every slot has the same seven controls, interpreted by its model:
//   TUNE  semitones around the model's base pitch      DECAY  ms (amp tail)
//   TONE  0..1 brightness / body-vs-noise balance      SNAP   0..1 transient: click, pitch sweep, noise
//   DRIVE 0..1 saturation                              LEVEL  dB     PAN  -1..1
// plus a choke group (an open hat is cut by the closed hat in the same group).
//
// Kit-wide character bus ("DUST" page), addressed as slot kBusSlot:
//   CRUNCH  sample-rate + bit-depth reduction (≈26 kHz / 12-bit at 50%, the SP-style grit)
//   MUFFLE  low-pass from 18 kHz down to 1.8 kHz (the filtered-break sound)
//   TAPE    asymmetric saturation that glues and thickens
//   DUST    vinyl crackle + hiss, only while the kit is playing (fades out ~3 s after the last hit)
// Every bus control at 0 bypasses the bus entirely.
//
// RT-safe: no allocation, no locks. Two voices per slot alternate, so a retrigger fades the old
// hit out over 3 ms instead of clicking.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace drums {

constexpr float kPi = 3.14159265358979f;
constexpr float kTwoPi = 6.28318530717959f;
constexpr int kSlots = 16;                       // 16 pads, MPC-style

// Must match com.thorbox.engine.NativeEngine.DrumModel ordinals.
enum Model : int { Kick = 0, Snare, Clap, Hat, Tom, Rim, Cowbell, Cymbal, Zap, Shaker, kModelCount };
// Must match com.thorbox.engine.NativeEngine.DrumParam.
enum Param : int { ModelId = 0, Tune, Decay, Tone, Snap, Drive, LevelDb, Pan, Choke, kParamCount };

// Kit-wide bus (slot index kBusSlot). Must match com.thorbox.engine.NativeEngine.DrumBus.
constexpr int kBusSlot = kSlots;
enum BusParam : int { Crunch = 0, Muffle, Tape, Dust, kBusParamCount };

struct ParamRange { float lo, hi; };
inline constexpr std::array<ParamRange, kParamCount> kRanges = {{
    {0, kModelCount - 1}, {-24, 24}, {5, 4000}, {0, 1}, {0, 1}, {0, 1}, {-40, 6}, {-1, 1}, {0, 4},
}};

inline float fsin(float x) {   // x in [-π, π]
    const float B = 4.f / kPi, C = -4.f / (kPi * kPi);
    float y = B * x + C * x * std::fabs(x);
    return 0.225f * (y * std::fabs(y) - y) + y;
}
inline float sinCycle(float ph) { return -fsin(kTwoPi * ph - kPi); }   // ph in [0,1)
inline float sat(float x) { x = std::clamp(x, -3.f, 3.f); return x * (27.f + x * x) / (27.f + 9.f * x * x); }

// State-variable filter with LP/BP/HP outputs (TPT).
struct Svf {
    float ic1 = 0, ic2 = 0, a1 = 0, a2 = 0, a3 = 0, k = 1;
    float lp = 0, bp = 0, hp = 0;
    void set(float fc, float q, float sr) {
        fc = std::clamp(fc, 20.f, 0.45f * sr);
        const float g = std::tan(kPi * fc / sr);
        k = 1.f / std::max(0.3f, q);
        a1 = 1.f / (1.f + g * (g + k)); a2 = g * a1; a3 = g * a2;
    }
    inline void process(float v0) {
        const float v3 = v0 - ic2;
        const float v1 = a1 * ic1 + a2 * v3;
        const float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2.f * v1 - ic1; ic2 = 2.f * v2 - ic2;
        lp = v2; bp = v1; hp = v0 - k * v1 - v2;
    }
    void reset() { ic1 = ic2 = 0; }
};

struct SlotParams {
    float model = Kick, tune = 0, decay = 400, tone = 0.5f, snap = 0.5f, drive = 0, levelDb = 0, pan = 0, choke = 0;
    float get(int id) const {
        switch (id) { case ModelId: return model; case Tune: return tune; case Decay: return decay; case Tone: return tone;
            case Snap: return snap; case Drive: return drive; case LevelDb: return levelDb; case Pan: return pan; default: return choke; }
    }
    void set(int id, float v) {
        switch (id) { case ModelId: model = std::round(v); break; case Tune: tune = v; break; case Decay: decay = v; break;
            case Tone: tone = v; break; case Snap: snap = v; break; case Drive: drive = v; break; case LevelDb: levelDb = v; break;
            case Pan: pan = v; break; default: choke = std::round(v); break; }
    }
};

// Metallic oscillator bank: six square waves at the classic inharmonic ratios (808 hats/cymbal).
inline constexpr float kMetal[6] = {205.3f, 304.4f, 369.6f, 522.7f, 540.0f, 800.0f};

struct Voice {
    bool on = false;
    int model = Kick;
    float t = 0.f;                    // seconds since trigger
    float amp = 0.f, ampK = 0.f;      // main tail: amp *= ampK per sample
    float nz = 0.f, nzK = 0.f;        // noise / secondary tail
    float pe = 0.f, peK = 0.f;        // pitch envelope
    float fade = 1.f, fadeK = 1.f;    // retrigger fade-out
    float vel = 1.f;
    float ph[6] = {};
    float baseHz = 50.f, sweep = 0.f;
    float tone = 0.5f, snap = 0.5f, drive = 0.f;
    Svf f1, f2;
    uint32_t rng = 0x12345u;
    float noise() { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return (float)(rng & 0xFFFFFF) / 8388607.5f - 1.f; }
};

// The character bus: dust → tape → crunch → muffle, stereo, processed in place.
struct Bus {
    float crunch = 0.f, muffle = 0.f, tape = 0.f, dust = 0.f;
    bool active() const { return crunch > 1e-3f || muffle > 1e-3f || tape > 1e-3f || dust > 1e-3f; }
    float get(int id) const { switch (id) { case Crunch: return crunch; case Muffle: return muffle; case Tape: return tape; default: return dust; } }
    void set(int id, float v) {
        v = std::clamp(v, 0.f, 1.f);
        switch (id) { case Crunch: crunch = v; break; case Muffle: muffle = v; break; case Tape: tape = v; break; default: dust = v; break; }
    }

    void process(float* buf, int n, float sr, bool playing) {
        // muffle filter (set once per block)
        if (muffle != lpFor) { lpFor = muffle; const float fc = 18000.f * std::pow(0.1f, muffle); lpL.set(fc, 0.62f, sr); lpR.set(fc, 0.62f, sr); }
        if (crackFor != sr) { crackFor = sr; crackF.set(2400.f, 0.9f, sr); hissF.set(5000.f, 0.6f, sr); }
        if (playing) holdN = (int)(3.f * sr);
        const float envTarget = holdN > 0 ? 1.f : 0.f;
        const float envK = 1.f - std::exp(-1.f / (0.25f * sr));
        const float g = 1.f + 5.f * tape, bias = 0.18f * tape, norm = 1.f / (1.f + 0.55f * (g - 1.f)), satB = sat(bias);
        const float rate = 1.f - 0.82f * crunch;                     // hold-clock ratio
        const float steps = std::exp2(15.f - 9.f * crunch);          // 16-bit → 7-bit
        const float popP = dust * 22.f / sr;
        for (int i = 0; i < n; ++i) {
            float l = buf[2 * i], r = buf[2 * i + 1];
            if (holdN > 0) --holdN;
            if (dust > 1e-3f) {
                dustEnv += (envTarget - dustEnv) * envK;
                if (envTarget == 0.f && dustEnv < 2e-3f) dustEnv = 0.f;     // fully silent once faded
                if (dustEnv > 1e-4f) {
                    float pop = 0.f;
                    if (rnd01() < popP) { const float a = rnd01(); pop = (0.03f + 0.35f * a * a * a) * (rnd01() < 0.5f ? 1.f : -1.f); popPan = rnd01() * 2.f - 1.f; }
                    crackF.process(pop * 8.f);
                    hissF.process(rnd01() * 2.f - 1.f);
                    const float c = crackF.bp * dust * dustEnv, h = hissF.lp * 0.006f * dust * dustEnv;
                    l += c * (1.f - 0.5f * popPan) + h; r += c * (1.f + 0.5f * popPan) + h;
                }
            }
            if (tape > 1e-3f) { l = (sat(g * l + bias) - satB) * norm; r = (sat(g * r + bias) - satB) * norm; }
            if (crunch > 1e-3f) {
                holdPh += rate;
                if (holdPh >= 1.f) { holdPh -= 1.f; holdL = std::round(l * steps) / steps; holdR = std::round(r * steps) / steps; }
                l = holdL; r = holdR;
            }
            if (muffle > 1e-3f) { lpL.process(l); lpR.process(r); l = lpL.lp; r = lpR.lp; }
            buf[2 * i] = l; buf[2 * i + 1] = r;
        }
    }

private:
    float holdPh = 1.f, holdL = 0.f, holdR = 0.f;
    Svf lpL, lpR, crackF, hissF;
    float lpFor = -1.f, crackFor = -1.f;
    float dustEnv = 0.f, popPan = 0.f;
    int holdN = 0;
    uint32_t rng = 0xC0FFEE11u;
    float rnd01() { rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5; return (float)(rng & 0xFFFFFF) / 16777216.f; }
};

inline float tailK(float ms, float sr) { return std::exp(-6.9f / (std::max(1.f, ms) * 0.001f * sr)); }   // -60 dB at ms

class DrumSynth {
public:
    DrumSynth() {
        // Default kit (the app sends its own on start; this keeps tests and a bare engine musical).
        const float m[kSlots] = {Kick, Snare, Clap, Hat, Hat, Tom, Rim, Cymbal, Kick, Snare, Shaker, Hat, Tom, Cowbell, Cymbal, Zap};
        const float d[kSlots] = {450, 220, 260, 60, 420, 380, 70, 1400, 250, 160, 120, 35, 300, 300, 900, 200};
        for (int s = 0; s < kSlots; ++s) { slots[s].model = m[s]; slots[s].decay = d[s]; }
        slots[3].choke = slots[4].choke = slots[11].choke = 1;
        slots[4].tone = 0.6f;
        for (int s = 0; s < kSlots; ++s) for (int k = 0; k < 2; ++k) voices[s][k].rng = 0x9E3779B9u * (uint32_t)(2 * s + k + 1);
    }
    void setSampleRate(float sr) { mSr = std::max(8000.f, sr); }

    void setParam(int slot, int id, float v) {
        if (slot == kBusSlot) { if (id >= 0 && id < kBusParamCount && std::isfinite(v)) bus.set(id, v); return; }
        if (slot < 0 || slot >= kSlots || id < 0 || id >= kParamCount || !std::isfinite(v)) return;
        slots[slot].set(id, std::clamp(v, kRanges[id].lo, kRanges[id].hi));
    }
    float param(int slot, int id) const {
        if (slot == kBusSlot) return (id >= 0 && id < kBusParamCount) ? bus.get(id) : 0.f;
        return (slot >= 0 && slot < kSlots) ? slots[slot].get(id) : 0.f;
    }

    void trigger(int slot, float vel) {
        if (slot < 0 || slot >= kSlots) return;
        const SlotParams& sp = slots[slot];
        // Choke: hits in the same group cut each other (closed hat silences the open hat).
        if (sp.choke > 0.f)
            for (int s = 0; s < kSlots; ++s)
                if (s != slot && slots[s].choke == sp.choke)
                    for (auto& v : voices[s]) if (v.on) v.fadeK = std::exp(-1.f / (0.004f * mSr));
        auto& pair = voices[slot];
        Voice& old = pair[cur[slot]];
        if (old.on) old.fadeK = std::exp(-1.f / (0.003f * mSr));
        cur[slot] ^= 1;
        Voice& v = pair[cur[slot]];
        start(v, sp, std::clamp(vel, 0.f, 1.f));
        hits[slot]++;
    }

    // Adds into interleaved stereo `out`.
    void render(float* out, int n) {
        for (float& p : pkBlock) p = 0.f;
        if (!bus.active()) { renderVoices(out, n); }
        else for (int off = 0; off < n; off += kChunk) {
            const int m = std::min(kChunk, n - off);
            bool playing = false;
            for (int s = 0; s < kSlots && !playing; ++s) playing = voices[s][0].on || voices[s][1].on;
            std::fill(scratch.begin(), scratch.begin() + 2 * m, 0.f);
            renderVoices(scratch.data(), m);
            bus.process(scratch.data(), m, mSr, playing);
            for (int i = 0; i < 2 * m; ++i) out[2 * off + i] += scratch[i];
        }
        for (int s = 0; s < kSlots; ++s) level[s] = std::max(pkBlock[s], level[s] * 0.8f);
    }

private:
    static constexpr int kChunk = 256;
    void renderVoices(float* out, int n) {
        for (int s = 0; s < kSlots; ++s) {
            const SlotParams& sp = slots[s];
            const float lin = std::exp2(sp.levelDb / 6.0206f) * 0.7f;
            const float ang = (sp.pan + 1.f) * kPi * 0.25f;
            const float gl = lin * std::cos(ang) * 1.41421356f, gr = lin * std::sin(ang) * 1.41421356f;
            float pk = 0.f;
            for (auto& v : voices[s]) {
                if (!v.on) continue;
                for (int i = 0; i < n; ++i) {
                    const float x = sample(v) * v.fade;
                    v.fade *= v.fadeK;
                    out[2 * i] += x * gl; out[2 * i + 1] += x * gr;
                    pk = std::max(pk, std::fabs(x));
                    if (v.fade < 1e-4f || (v.amp < 1e-4f && v.nz < 1e-4f && v.t > 0.01f)) { v.on = false; break; }
                }
            }
            pkBlock[s] = std::max(pkBlock[s], pk);
        }
    }

public:

    bool active(int slot) const { return slot >= 0 && slot < kSlots && (voices[slot][0].on || voices[slot][1].on); }
    // Per-slot envelope level (0..1) for animation, decaying peak follower.
    float slotLevel(int slot) const { return (slot >= 0 && slot < kSlots) ? level[slot] : 0.f; }
    uint32_t hitCount(int slot) const { return (slot >= 0 && slot < kSlots) ? hits[slot] : 0u; }

private:
    void start(Voice& v, const SlotParams& sp, float vel) {
        const float sr = mSr;
        v.on = true; v.model = (int)sp.model; v.t = 0.f; v.vel = vel;
        v.fade = 1.f; v.fadeK = 1.f;
        v.tone = sp.tone; v.snap = sp.snap; v.drive = sp.drive;
        v.f1.reset(); v.f2.reset();
        for (float& p : v.ph) p = 0.f;
        const float tr = std::exp2(sp.tune / 12.f);
        const float dec = sp.decay;
        v.amp = vel; v.ampK = tailK(dec, sr);
        v.nz = 0.f; v.nzK = 0.f; v.pe = 1.f; v.peK = 0.f; v.sweep = 0.f;
        switch (v.model) {
            case Kick:   // sine + pitch drop + click
                v.baseHz = 48.f * tr; v.sweep = 1.f + 6.f * sp.snap; v.peK = tailK(18.f + 60.f * (1.f - sp.tone), sr);
                v.nz = vel * (0.15f + 0.6f * sp.snap); v.nzK = tailK(4.f, sr);
                v.f1.set(1500.f + 6000.f * sp.tone, 0.7f, sr);
                break;
            case Snare:  // two detuned bodies + bright noise
                v.baseHz = 185.f * tr; v.sweep = 0.6f; v.peK = tailK(25.f, sr);
                v.amp = vel * (1.f - 0.55f * sp.tone); v.ampK = tailK(dec * 0.6f, sr);
                v.nz = vel * (0.35f + 0.65f * sp.snap); v.nzK = tailK(dec, sr);
                v.f1.set(1800.f + 5000.f * sp.tone, 0.8f, sr);
                break;
            case Clap:   // band-passed noise in 3 quick bursts + a tail
                v.amp = 0.f; v.nz = vel; v.nzK = tailK(dec, sr);
                v.f1.set(900.f * tr + 1400.f * sp.tone, 1.6f, sr);
                break;
            case Hat: case Cymbal: {
                const bool cym = v.model == Cymbal;
                v.amp = vel; v.ampK = tailK(dec, sr);
                v.nz = vel * (0.2f + 0.6f * sp.snap); v.nzK = tailK(dec * 0.5f, sr);
                v.baseHz = tr * (cym ? 0.85f : 1.f);
                v.f1.set((cym ? 5200.f : 7200.f) + 4000.f * sp.tone, cym ? 0.9f : 1.4f, sr);
                v.f2.set(cym ? 3000.f : 6000.f, 0.7f, sr);
                break;
            }
            case Tom:
                v.baseHz = 110.f * tr; v.sweep = 0.4f + 1.2f * sp.snap; v.peK = tailK(60.f + 120.f * sp.tone, sr);
                v.nz = vel * 0.25f * sp.snap; v.nzK = tailK(20.f, sr);
                v.f1.set(3000.f, 0.7f, sr);
                break;
            case Rim:
                v.baseHz = 1700.f * tr; v.ampK = tailK(std::min(dec, 120.f), sr);
                v.nz = vel * (0.3f + 0.7f * sp.snap); v.nzK = tailK(6.f, sr);
                v.f1.set(2500.f * tr + 3000.f * sp.tone, 2.f, sr);
                break;
            case Cowbell:
                v.baseHz = 540.f * tr; v.ampK = tailK(dec, sr);
                v.f1.set(800.f * tr * (1.f + sp.tone), 2.2f, sr);
                break;
            case Zap:    // laser: huge fast exponential sweep down
                v.baseHz = 80.f * tr; v.sweep = 8.f + 40.f * sp.snap; v.peK = tailK(15.f + 120.f * sp.tone, sr);
                break;
            case Shaker:
                v.amp = 0.f; v.nz = vel; v.nzK = tailK(dec, sr);
                v.f1.set(4000.f + 6000.f * sp.tone, 1.2f, sr);
                break;
        }
    }

    inline float sample(Voice& v) {
        const float sr = mSr, dt = 1.f / sr;
        float y = 0.f;
        switch (v.model) {
            case Kick: {
                const float hz = v.baseHz * (1.f + v.sweep * v.pe);
                v.ph[0] += hz * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                v.f1.process(v.noise());
                y = sinCycle(v.ph[0]) * v.amp + v.f1.lp * v.nz;
                break;
            }
            case Snare: {
                const float hz = v.baseHz * (1.f + v.sweep * v.pe);
                v.ph[0] += hz * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                v.ph[1] += hz * 1.78f * dt; if (v.ph[1] >= 1.f) v.ph[1] -= 1.f;
                v.f1.process(v.noise());
                y = (0.6f * sinCycle(v.ph[0]) + 0.4f * sinCycle(v.ph[1])) * v.amp + (v.f1.hp * 0.7f + v.f1.bp * 0.5f) * v.nz;
                break;
            }
            case Clap: {
                // Bursts at 0, 10, 20 ms (each ~8 ms), then the tail.
                const float ms = v.t * 1000.f;
                float env = v.nz;
                if (ms < 30.f) { const float local = std::fmod(ms, 10.f); env = v.vel * std::exp(-local * 0.45f); }
                v.f1.process(v.noise());
                y = v.f1.bp * env * (1.4f + 0.8f * v.snap);
                break;
            }
            case Hat: case Cymbal: {
                float m = 0.f;
                for (int k = 0; k < 6; ++k) {
                    v.ph[k] += kMetal[k] * v.baseHz * (v.model == Cymbal ? 1.f : 1.65f) * dt;
                    if (v.ph[k] >= 1.f) v.ph[k] -= 1.f;
                    m += v.ph[k] < 0.5f ? 1.f : -1.f;
                }
                v.f1.process(m * (1.f / 6.f) * v.amp + v.noise() * v.nz);
                v.f2.process(v.f1.bp);
                y = v.f2.hp * 1.6f;
                break;
            }
            case Tom: {
                const float hz = v.baseHz * (1.f + v.sweep * v.pe);
                v.ph[0] += hz * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                v.f1.process(v.noise());
                y = sinCycle(v.ph[0]) * v.amp + v.f1.lp * v.nz;
                break;
            }
            case Rim: {
                v.ph[0] += v.baseHz * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                v.ph[1] += v.baseHz * 0.29f * dt; if (v.ph[1] >= 1.f) v.ph[1] -= 1.f;
                v.f1.process(v.noise() * v.nz + (sinCycle(v.ph[0]) + 0.6f * sinCycle(v.ph[1])) * v.amp);
                y = v.f1.bp * 1.5f;
                break;
            }
            case Cowbell: {
                v.ph[0] += v.baseHz * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                v.ph[1] += v.baseHz * 1.48f * dt; if (v.ph[1] >= 1.f) v.ph[1] -= 1.f;
                const float sq = (v.ph[0] < 0.5f ? 1.f : -1.f) + (v.ph[1] < 0.5f ? 1.f : -1.f);
                // Two-stage decay: a sharp attack then the ringing tail (the snap knob sets the attack).
                const float attack = v.t < 0.02f ? 1.f + 1.5f * v.snap : 1.f;
                v.f1.process(sq * 0.5f * v.amp * attack);
                y = v.f1.bp * 1.2f;
                break;
            }
            case Zap: {
                const float hz = v.baseHz * (1.f + v.sweep * v.pe);
                v.ph[0] += std::min(hz, 0.45f * sr) * dt; if (v.ph[0] >= 1.f) v.ph[0] -= 1.f;
                y = sinCycle(v.ph[0]) * v.amp;
                break;
            }
            case Shaker: {
                const float ms = v.t * 1000.f;
                const float rise = std::min(1.f, ms / (2.f + 25.f * (1.f - v.snap)));   // soft attack: it's shaken, not hit
                v.f1.process(v.noise());
                y = v.f1.bp * v.nz * rise * 1.3f;
                break;
            }
        }
        v.amp *= v.ampK; v.nz *= v.nzK; v.pe *= v.peK; v.t += dt;
        if (v.drive > 0.f) y = sat(y * (1.f + 8.f * v.drive)) / (1.f + 1.5f * v.drive);
        return y;
    }

    std::array<SlotParams, kSlots> slots{};
    std::array<std::array<Voice, 2>, kSlots> voices{};
    std::array<int, kSlots> cur{};
    std::array<float, kSlots> level{};
    std::array<uint32_t, kSlots> hits{};
    std::array<float, kSlots> pkBlock{};
    std::array<float, 2 * kChunk> scratch{};
    Bus bus;
    float mSr = 48000.f;
};

} // namespace drums
