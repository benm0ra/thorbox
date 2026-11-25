// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// The master bus tools, after the mix and the punch-in FX: GLUE (a bus compressor) before the master
// fader, LIMIT (a look-ahead brickwall) after it. (The master EQ is a ChannelEq, see ChannelEq.h.)
//
//   GLUE   stereo-linked, feed-forward, log-domain: soft-knee gain computer (Ducker.h's curve), the
//          reduction smoothed with ATTACK / RELEASE (or AUTO: slower the longer it's been working, so
//          sustained compression doesn't pump). Optional side-chain high-pass so the kick's sub doesn't
//          drive it. MAKEUP, and MIX for parallel ("New York") compression.
//   LIMIT  INPUT drive, a CEILING it never goes over: a gain envelope that is the running minimum of
//          the needed gain over the look-ahead window (plus RELEASE), then a box average over the same
//          window, with the audio delayed to match. Each output sample's gain is therefore ≤ the gain
//          its own peak needs: no overs, no clicks. Look-ahead is 1.5 ms; while OFF the delay is
//          crossfaded out, so it adds no latency.
//
// process() runs on the audio thread and allocates nothing. Header-only, Android-free
// (tested on desktop: engine/src/test/cpp/master_test.cpp).

#include <algorithm>
#include <array>
#include <cmath>
#include "Ducker.h"

namespace master {

// ---- GLUE ----
enum GlueParam : int { GOn = 0, GThreshDb, GRatio, GAttackMs, GReleaseMs, GMakeupDb, GMix, GScHpHz, kGlueParams };
struct GlueSettings {
    bool on = false;
    float thresholdDb = -18.f, ratio = 2.f, attackMs = 10.f, releaseMs = 0.f /* 0 = AUTO */, makeupDb = 0.f, mix = 1.f, scHpHz = 0.f;
};
inline bool set(GlueSettings& s, int id, float v) {
    if (!std::isfinite(v)) return false;
    auto in = [&](float lo, float hi) { return v >= lo && v <= hi; };
    switch (id) {
        case GOn: s.on = v != 0.f; return true;
        case GThreshDb: if (!in(-48.f, 0.f)) return false; s.thresholdDb = v; return true;
        case GRatio: if (!in(1.f, 20.f)) return false; s.ratio = v; return true;
        case GAttackMs: if (!in(0.1f, 100.f)) return false; s.attackMs = v; return true;
        case GReleaseMs: if (!(v == 0.f || in(20.f, 2000.f))) return false; s.releaseMs = v; return true;
        case GMakeupDb: if (!in(0.f, 24.f)) return false; s.makeupDb = v; return true;
        case GMix: if (!in(0.f, 1.f)) return false; s.mix = v; return true;
        case GScHpHz: if (!(v == 0.f || in(20.f, 500.f))) return false; s.scHpHz = v; return true;
        default: return false;
    }
}

class Glue {
public:
    GlueSettings s;
    float grMax = 0.f;          // deepest reduction (dB, positive) since the reader last cleared it

    void process(float* lr, int n, float sr) {
        const float tOn = s.on ? 1.f : 0.f;
        if (!s.on && mWet <= 0.f) { mRed = 0.f; mAvg = 0.f; return; }
        const float att = std::exp(-1.f / (std::max(0.1f, s.attackMs) * 0.001f * sr));
        const float avgK = std::exp(-1.f / (1.0f * sr));                   // ~1 s average of the reduction (AUTO)
        const float hpA = s.scHpHz > 0.f ? std::exp(-2.f * (float)M_PI * s.scHpHz / sr) : 0.f;
        const float wetStep = 1.f / (0.01f * sr);                           // ON / OFF: a 10 ms crossfade
        const float makeupT = std::pow(10.f, s.makeupDb / 20.f);
        const float mixT = s.mix;
        const float k = 1.f - std::exp(-1.f / (0.02f * sr));                // makeup / mix glide (20 ms)
        float red = mRed, avg = mAvg, gmx = grMax, wet = mWet, mk = mMakeup, mx = mMix;
        float hpXl = mHpXl, hpXr = mHpXr, hpYl = mHpYl, hpYr = mHpYr;
        for (int i = 0; i < n; ++i) {
            const float l = lr[2 * i], r = lr[2 * i + 1];
            float dl = l, dr = r;
            if (hpA > 0.f) {                                                // one-pole high-pass on the detector only
                hpYl = hpA * (hpYl + l - hpXl); hpXl = l; dl = hpYl;
                hpYr = hpA * (hpYr + r - hpXr); hpXr = r; dr = hpYr;
            }
            const float pk = std::max(std::fabs(dl), std::fabs(dr));
            const float want = pk > 1e-6f ? duck::reductionDb(20.f * std::log10(pk), s.thresholdDb, s.ratio) : 0.f;
            if (want > red) red = att * red + (1.f - att) * want;
            else {
                // AUTO: 100 ms when it's only catching peaks, up to 1.2 s when it's been working all along
                const float relMs = s.releaseMs > 0.f ? s.releaseMs : 100.f + 1100.f * std::min(1.f, avg / 6.f);
                const float rel = std::exp(-1.f / (relMs * 0.001f * sr));
                red = rel * red + (1.f - rel) * want;
            }
            avg = avgK * avg + (1.f - avgK) * want;                           // how hard the signal has been asking (not the release itself)
            gmx = std::max(gmx, red);
            wet += wet < tOn ? std::min(wetStep, tOn - wet) : -std::min(wetStep, wet - tOn);
            mk += (makeupT - mk) * k; mx += (mixT - mx) * k;
            const float g = std::pow(10.f, -red / 20.f) * mk;
            const float m = mx * wet;                                       // wet share
            lr[2 * i] = l * (1.f - m) + l * g * m;
            lr[2 * i + 1] = r * (1.f - m) + r * g * m;
        }
        mRed = red; mAvg = avg; grMax = gmx; mWet = wet; mMakeup = mk; mMix = mx;
        mHpXl = hpXl; mHpXr = hpXr; mHpYl = hpYl; mHpYr = hpYr;
    }
    float reductionDb() const { return mRed * mWet; }
    void reset() { mRed = mAvg = 0.f; mHpXl = mHpXr = mHpYl = mHpYr = 0.f; }

private:
    float mRed = 0.f, mAvg = 0.f, mWet = 0.f, mMakeup = 1.f, mMix = 1.f;
    float mHpXl = 0.f, mHpXr = 0.f, mHpYl = 0.f, mHpYr = 0.f;
};

// ---- LIMIT ----
enum LimParam : int { LOn = 0, LInputDb, LCeilingDb, LReleaseMs, kLimParams };
struct LimSettings { bool on = false; float inputDb = 0.f, ceilingDb = -0.3f, releaseMs = 80.f; };
inline bool set(LimSettings& s, int id, float v) {
    if (!std::isfinite(v)) return false;
    auto in = [&](float lo, float hi) { return v >= lo && v <= hi; };
    switch (id) {
        case LOn: s.on = v != 0.f; return true;
        case LInputDb: if (!in(0.f, 24.f)) return false; s.inputDb = v; return true;
        case LCeilingDb: if (!in(-12.f, 0.f)) return false; s.ceilingDb = v; return true;
        case LReleaseMs: if (!in(5.f, 1000.f)) return false; s.releaseMs = v; return true;
        default: return false;
    }
}

class Limiter {
public:
    static constexpr int kMaxLook = 256;
    LimSettings s;
    float grMax = 0.f;          // deepest reduction (dB, positive) since the reader last cleared it

    void process(float* lr, int n, float sr) {
        const int L = std::clamp((int)std::lround(0.0015f * sr), 2, kMaxLook);
        if (L != mL) { mL = L; resetState(); }
        const float tOn = s.on ? 1.f : 0.f;
        if (!s.on && mWet <= 0.f) {                           // fully off: keep the delay line fed, touch nothing
            for (int i = 0; i < n; ++i) push(lr[2 * i], lr[2 * i + 1], 1.f);
            return;
        }
        const float ceil = std::pow(10.f, s.ceilingDb / 20.f);
        const float drive = std::pow(10.f, s.inputDb / 20.f);
        mRelCoef = 1.f - std::exp(-1.f / (std::max(5.f, s.releaseMs) * 0.001f * sr));
        const float wetStep = 1.f / (0.01f * sr);
        float gmx = grMax;
        for (int i = 0; i < n; ++i) {
            const float l = lr[2 * i], r = lr[2 * i + 1];
            // drive ramps with ON so switching never jumps
            const float dg = 1.f + (drive - 1.f) * mWet;
            const float pk = std::max(std::fabs(l), std::fabs(r)) * dg;
            const float need = pk > ceil ? ceil / pk : 1.f;
            push(l * dg, r * dg, need);
            // the delayed sample, with the envelope that is guaranteed to cover it
            const int o = (mPos + 1) % mL;                    // the oldest sample in the window: written L-1 pushes ago
            const float g = std::min(1.f, (float)(mSum / mL));
            const float yl = mDl[o] * g, yr = mDr[o] * g;
            mWet += mWet < tOn ? std::min(wetStep, tOn - mWet) : -std::min(wetStep, mWet - tOn);
            // OFF crossfades to the undelayed input, so a bypassed limiter adds no latency
            lr[2 * i] = yl * mWet + l * (1.f - mWet);
            lr[2 * i + 1] = yr * mWet + r * (1.f - mWet);
            gmx = std::max(gmx, g < 1.f ? -20.f * std::log10(g) * mWet : 0.f);
        }
        grMax = gmx;
    }
    float wet() const { return mWet; }
    void reset() { resetState(); }

private:
    // One step: write the sample and its needed gain; update the running min (with release) and the box sum.
    void push(float l, float r, float need) {
        mPos = (mPos + 1) % mL;
        mDl[mPos] = l; mDr[mPos] = r;
        // running minimum over the last L needs (monotonic deque of (index, value))
        ++mIdx;
        while (mQn > 0 && mQv[(mQh + mQn - 1) % kMaxLook] >= need) --mQn;
        mQi[(mQh + mQn) % kMaxLook] = mIdx; mQv[(mQh + mQn) % kMaxLook] = need; ++mQn;
        while (mQi[mQh] <= mIdx - mL) { mQh = (mQh + 1) % kMaxLook; --mQn; }
        const float wmin = mQv[mQh];
        // release: the envelope may rise only slowly, and never above what the window needs
        mEnv = std::min(wmin, mEnv + (1.f - mEnv) * mRelCoef);
        // box average of the envelope over L
        mSum += mEnv - mBox[mPos]; mBox[mPos] = mEnv;
    }
    void resetState() {
        mDl.fill(0.f); mDr.fill(0.f); mBox.fill(1.f); mSum = mL; mEnv = 1.f;
        mPos = 0; mQh = 0; mQn = 0; mIdx = 0;
    }
    std::array<float, kMaxLook> mDl{}, mDr{}, mBox{}, mQv{};
    std::array<long long, kMaxLook> mQi{};
    int mL = 0, mPos = 0, mQh = 0, mQn = 0;
    long long mIdx = 0;
    double mSum = 0.0;                                   // double: summed for hours without drifting
    float mEnv = 1.f, mWet = 0.f, mRelCoef = 0.001f;
};

}  // namespace master
