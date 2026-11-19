// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Punch-in master effects (OP-XY / SP-404 style). Hold a pad to engage, release to snap back dry.
// Each effect takes an XY position (0..1, 0..1) from where your finger sits on the pad, and it
// can be moved while held. Several can stack. Header-only and Android-free (tested on desktop).
//
// Chain: [time FX: tape stop > reverse > stutter] → filter → bitcrush → vinyl → gate
// Time FX read from a ring buffer of the DRY master input, so they always replay what you just
// heard. Every effect crossfades its wet signal in and out over ~6 ms: engaging and releasing never click.
//
// RT-safe: the ring buffer is allocated once in the constructor (main thread); process() never allocates.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace fx {

constexpr float kPi = 3.14159265358979f;

enum Id : int { Stutter = 0, Filter, TapeStop, Crush, Vinyl, Reverse, Gate, Haze, kCount };

// Tempo-synced divisions, in quarter notes: x picks one (left = long, right = short).
inline float divisionBeats(float x) {
    static constexpr float d[] = {1.f, 0.5f, 1.f / 3.f, 0.25f, 1.f / 6.f, 0.125f, 1.f / 12.f};   // 1/4 … 1/32T
    return d[std::clamp((int)(x * 7.f), 0, 6)];
}

class MasterFx {
public:
    static constexpr int kRingBits = 19;                 // 524288 frames ≈ 10.9 s at 48 kHz
    static constexpr int kRing = 1 << kRingBits;

    MasterFx() : mRingL(kRing, 0.f), mRingR(kRing, 0.f) {
        // HAZE reverb lines (Freeverb-style tunings, scaled for up to 96 kHz). Allocated once, here.
        for (int c = 0; c < 2; ++c) {
            for (int k = 0; k < kCombs; ++k) mComb[c][k].assign((size_t)((kCombLen[k] + c * 23) * 2), 0.f);
            for (int k = 0; k < kAps; ++k) mAp[c][k].assign((size_t)((kApLen[k] + c * 23) * 2), 0.f);
        }
    }

    void engage(int id, float x, float y) {
        if (id < 0 || id >= kCount) return;
        Slot& s = mSlot[id];
        const bool wasOn = s.on;
        s.on = true; s.x = std::clamp(x, 0.f, 1.f); s.y = std::clamp(y, 0.f, 1.f);
        if (!wasOn) {                                    // capture the moment of engagement
            s.start = mWrite; s.phase = 0.0; s.speed = 1.0; s.readPos = (double)mWrite;
            if (id == Filter) { mFl[0] = mFl[1] = Svf{}; mFh[0] = mFh[1] = Svf{}; }
        }
    }
    void move(int id, float x, float y) {
        if (id < 0 || id >= kCount || !mSlot[id].on) return;
        mSlot[id].x = std::clamp(x, 0.f, 1.f); mSlot[id].y = std::clamp(y, 0.f, 1.f);
    }
    void release(int id) { if (id >= 0 && id < kCount) mSlot[id].on = false; }
    void releaseAll() { for (auto& s : mSlot) s.on = false; }
    uint32_t activeMask() const { uint32_t m = 0; for (int i = 0; i < kCount; ++i) if (mSlot[i].wet > 1e-3f || mSlot[i].on) m |= 1u << i; return m; }
    float hazeTail() const { return mHazeTail; }

    // In-place stereo processing of n frames (interleaved).
    void process(float* io, int n, float sr, float bpm, double tick0 = 0.0, double tps = 0.0) {
        const float xf = 1.f - std::exp(-1.f / (0.006f * sr));     // ~6 ms wet crossfade
        const double beat = 60.0 / std::max(30.f, bpm) * sr;          // samples per quarter note

        // Fast path: everything dry and fully faded out → only feed the ring.
        bool any = mHazeTail > 1e-5f;                              // a reverb tail keeps ringing after release
        for (auto& s : mSlot) any |= s.on || s.wet > 1e-4f;
        if (!any) {
            for (int i = 0; i < n; ++i) { push(io[2 * i], io[2 * i + 1]); }
            return;
        }

        // Control-rate setup
        Slot& st = mSlot[Stutter]; Slot& rv = mSlot[Reverse]; Slot& tp = mSlot[TapeStop];
        const double stLen = std::max(64.0, divisionBeats(st.x) * beat);
        const double rvLen = std::max(256.0, std::pow(2.0, std::floor(rv.x * 3.f)) * beat);          // 1, 2, 4, 8 beats
        const double tpRate = 1.0 / ((0.08 + 1.9 * tp.y * tp.y) * sr);                                 // stop time 80 ms … 2 s
        Slot& fl = mSlot[Filter];
        setupFilter(fl.x, fl.y, sr);
        Slot& cr = mSlot[Crush];
        const float levels = std::exp2(16.f - 13.f * cr.x);          // 16 → 3 bits
        const int hold = 1 + (int)(cr.y * cr.y * 40.f);              // downsample ×1 … ×41
        Slot& vy = mSlot[Vinyl];
        Slot& gt = mSlot[Gate];
        Slot& hz = mSlot[Haze];
        const double gtTicks = divisionBeats(0.3f + 0.7f * gt.x) * 960.0;   // locked to the song position
        const float duty = 0.15f + 0.7f * (1.f - gt.y);

        for (int i = 0; i < n; ++i) {
            const float inL = io[2 * i], inR = io[2 * i + 1];
            push(inL, inR);
            float l = inL, r = inR;

            // ---- time effects (mutually exclusive by priority, each crossfaded) ----
            fade(st, xf); fade(rv, xf); fade(tp, xf);
            if (st.wet > 0.f) {
                const double ph = std::fmod(st.phase, stLen);
                const double p = (double)st.start + ph;
                const float g = edge((float)ph, (float)stLen, 96.f);        // de-click each repeat
                // The ring already holds this sample (push() above), so the first pass is live audio
                // and every later pass replays the captured slice.
                const float sl = at(mRingL, p) * g, sr2 = at(mRingR, p) * g;
                l += (sl - l) * st.wet; r += (sr2 - r) * st.wet;
                st.phase += 1.0;
            }
            if (rv.wet > 0.f) {
                const double ph = std::fmod(rv.phase, rvLen);
                const double p = (double)rv.start - 1.0 - ph;
                const float g = edge((float)ph, (float)rvLen, 256.f);
                l += (at(mRingL, p) * g - l) * rv.wet; r += (at(mRingR, p) * g - r) * rv.wet;
                rv.phase += 1.0;
            }
            if (tp.wet > 0.f) {
                if (tp.on) tp.speed = std::max(0.0, tp.speed - tpRate);
                tp.readPos += tp.speed;
                const float g = (float)std::min(1.0, tp.speed * 8.0);      // fade the last moments
                l += (lerpAt(mRingL, tp.readPos) * g - l) * tp.wet;
                r += (lerpAt(mRingR, tp.readPos) * g - r) * tp.wet;
            }

            // ---- filter sweep: left half low-pass, right half high-pass, centre ≈ open ----
            fade(fl, xf);
            if (fl.wet > 0.f) {
                const float fL = mUseHp ? mFh[0].hp(l) : mFl[0].lp(l);
                const float fR = mUseHp ? mFh[1].hp(r) : mFl[1].lp(r);
                l += (fL - l) * fl.wet * mFiltMix; r += (fR - r) * fl.wet * mFiltMix;
            }

            // ---- bitcrush / downsample ----
            fade(cr, xf);
            if (cr.wet > 0.f) {
                if (mHoldCount <= 0) {
                    mHoldL = std::round(l * levels) / levels; mHoldR = std::round(r * levels) / levels;
                    mHoldCount = hold;
                }
                --mHoldCount;
                l += (mHoldL - l) * cr.wet; r += (mHoldR - r) * cr.wet;
            }

            // ---- vinyl: wow + flutter (modulated delay), band-limit, crackle ----
            fade(vy, xf);
            if (vy.wet > 0.f) {
                mWowPh += 0.55f / sr; if (mWowPh >= 1.f) mWowPh -= 1.f;
                mFlutPh += 7.3f / sr; if (mFlutPh >= 1.f) mFlutPh -= 1.f;
                const float depth = (0.4f + 2.6f * vy.x) * 0.001f * sr;     // up to ~3 ms of pitch wobble
                const double d = 0.006 * sr + depth * std::sin(2.f * kPi * mWowPh) + depth * 0.15f * std::sin(2.f * kPi * mFlutPh);
                const double p = (double)mWrite - 1.0 - d;
                float wl = lerpAt(mRingL, p), wr = lerpAt(mRingR, p);
                mVinLp[0] += (wl - mVinLp[0]) * 0.35f; mVinLp[1] += (wr - mVinLp[1]) * 0.35f;   // ~4–5 kHz roll-off
                wl = mVinLp[0]; wr = mVinLp[1];
                if (rand01() < (0.0002f + 0.003f * vy.y) * (48000.f / sr)) mCrackle = (rand01() * 2.f - 1.f) * (0.15f + 0.35f * vy.y);
                wl += mCrackle; wr += mCrackle * 0.8f; mCrackle *= 0.55f;
                l += (wl - l) * vy.wet; r += (wr - r) * vy.wet;
            }

            // ---- HAZE: a smoky reverb wash. X = size (decay), Y = tone (dark ↔ bright); wet ducks the dry a little.
            fade(hz, xf);
            if (hz.wet > 0.f || mHazeTail > 1e-5f) {
                const float fb = 0.70f + 0.27f * hz.x;                  // comb feedback: room → hall → forever
                const float damp = 0.55f - 0.45f * hz.y;               // high-frequency loss inside the tank
                const float in = (l + r) * 0.5f * (hz.on ? 1.f : 0.f) * 0.18f;
                float out[2];
                for (int c = 0; c < 2; ++c) {
                    float acc = 0.f;
                    for (int k = 0; k < kCombs; ++k) {
                        std::vector<float>& buf = mComb[c][k];
                        const size_t len = std::min(buf.size(), (size_t)((kCombLen[k] + c * 23) * sr / 44100.f));
                        size_t& p = mCombPos[c][k]; if (p >= len) p = 0;
                        const float y = buf[p];
                        mCombLp[c][k] = y * (1.f - damp) + mCombLp[c][k] * damp;
                        buf[p] = in + mCombLp[c][k] * fb;
                        if (++p >= len) p = 0;
                        acc += y;
                    }
                    for (int k = 0; k < kAps; ++k) {
                        std::vector<float>& buf = mAp[c][k];
                        const size_t len = std::min(buf.size(), (size_t)((kApLen[k] + c * 23) * sr / 44100.f));
                        size_t& p = mApPos[c][k]; if (p >= len) p = 0;
                        const float b = buf[p];
                        buf[p] = acc + b * 0.5f;
                        acc = b - acc;
                        if (++p >= len) p = 0;
                    }
                    out[c] = acc;
                }
                mHazeTail = std::max(std::fabs(out[0]), std::fabs(out[1])) + mHazeTail * 0.9995f;
                if (mHazeTail > 1.f) mHazeTail = 1.f;
                const float wet = 0.9f;
                const float duck = 1.f - 0.35f * hz.wet;
                l = l * duck + out[0] * wet; r = r * duck + out[1] * wet;
                if (!hz.on && mHazeTail < 1e-5f) { mHazeTail = 0.f; }
            }

            // ---- trance gate ----
            fade(gt, xf);
            if (gt.wet > 0.f) {
                const double ph = std::fmod(tick0 + i * tps, gtTicks) / gtTicks;
                const float target = ph < duty ? 1.f : 0.f;
                mGateEnv += (target - mGateEnv) * 0.02f;
                const float g = 1.f - gt.wet * (1.f - mGateEnv);
                l *= g; r *= g;
            }

            io[2 * i] = l; io[2 * i + 1] = r;
        }
    }

private:
    struct Slot { bool on = false; float x = 0.5f, y = 0.5f, wet = 0.f; uint32_t start = 0; double phase = 0, speed = 1, readPos = 0; };
    struct Svf {   // TPT SVF with LP and HP outputs
        float ic1 = 0, ic2 = 0, a1 = 0, a2 = 0, a3 = 0, k = 1.4f;
        void set(float fc, float res, float sr) {
            fc = std::clamp(fc, 20.f, 0.45f * sr);
            const float g = std::tan(kPi * fc / sr);
            k = 2.f - 1.9f * std::clamp(res, 0.f, 1.f);
            a1 = 1.f / (1.f + g * (g + k)); a2 = g * a1; a3 = g * a2;
        }
        inline void tick(float v0, float& lp, float& bp) {
            const float v3 = v0 - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; lp = v2; bp = v1;
        }
        inline float lp(float x) { float l, b; tick(x, l, b); return l; }
        inline float hp(float x) { float l, b; tick(x, l, b); return x - k * b - l; }
    };

    void setupFilter(float x, float y, float sr) {
        // x < 0.5: low-pass sweeping down from 20 kHz to 80 Hz; x > 0.5: high-pass sweeping up to 8 kHz.
        const float d = (x - 0.5f) * 2.f;
        mUseHp = d > 0.f;
        const float a = std::fabs(d);
        mFiltMix = std::min(1.f, a * 6.f);              // a tiny dead-zone in the centre = transparent
        const float fc = mUseHp ? 20.f * std::pow(400.f, a) : 20000.f * std::pow(80.f / 20000.f, a);
        for (auto* f : {&mFl[0], &mFl[1], &mFh[0], &mFh[1]}) f->set(fc, 0.15f + 0.75f * y, sr);
    }

    static inline void fade(Slot& s, float xf) { s.wet += ((s.on ? 1.f : 0.f) - s.wet) * xf; if (!s.on && s.wet < 1e-4f) s.wet = 0.f; }
    static inline float edge(float ph, float len, float ramp) {
        const float r = std::min(ramp, len * 0.25f);
        return std::min(1.f, std::min(ph / r, (len - ph) / r));
    }
    inline void push(float l, float r) { mRingL[mWrite & (kRing - 1)] = l; mRingR[mWrite & (kRing - 1)] = r; ++mWrite; }
    inline float at(const std::vector<float>& ring, double pos) const {
        return ring[(uint32_t)(int64_t)std::floor(pos) & (kRing - 1)];
    }
    inline float lerpAt(const std::vector<float>& ring, double pos) const {
        const double f = std::floor(pos);
        const float t = (float)(pos - f);
        const uint32_t i = (uint32_t)(int64_t)f;
        return ring[i & (kRing - 1)] * (1.f - t) + ring[(i + 1) & (kRing - 1)] * t;
    }
    float rand01() { mRng ^= mRng << 13; mRng ^= mRng >> 17; mRng ^= mRng << 5; return (float)(mRng & 0xFFFFFF) / 16777216.f; }

    std::vector<float> mRingL, mRingR;
    uint32_t mWrite = 0;
    Slot mSlot[kCount];
    Svf mFl[2], mFh[2];
    bool mUseHp = false;
    float mFiltMix = 0.f;
    float mHoldL = 0, mHoldR = 0; int mHoldCount = 0;
    float mWowPh = 0, mFlutPh = 0, mVinLp[2] = {0, 0}, mCrackle = 0;
    float mGateEnv = 1.f;
    uint32_t mRng = 0xBADC0DEu;

    static constexpr int kCombs = 6, kAps = 3;
    static constexpr int kCombLen[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491};
    static constexpr int kApLen[kAps] = {556, 441, 341};
    std::vector<float> mComb[2][kCombs], mAp[2][kAps];
    size_t mCombPos[2][kCombs] = {}, mApPos[2][kAps] = {};
    float mCombLp[2][kCombs] = {};
    float mHazeTail = 0.f;
};

} // namespace fx
