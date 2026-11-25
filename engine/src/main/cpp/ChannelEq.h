// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// A mixing EQ for one channel strip: a high-pass and a low-pass (12 or 24 dB/oct, Butterworth),
// a low shelf, a mid bell and a high shelf. Biquads after the RBJ "Audio EQ Cookbook".
//
// Parameter changes are smoothed: each chunk the working values glide ~30 ms toward the targets
// (frequencies in log space, gains in dB) and only the bands that moved are recomputed, so a drag
// sweeps instead of zippering. A band at its neutral value (HP at 20 Hz, LP at 20 kHz, 0 dB) is
// skipped entirely, so a flat strip costs nothing. OFF glides every band to neutral first, then
// bypasses: A/B never clicks.
//
// process() runs on the audio thread and allocates nothing. Header-only and Android-free
// (tested on desktop: engine/src/test/cpp/eq_test.cpp).

#include <algorithm>
#include <cmath>

namespace eq {

// Ids shared with the app (NativeEngine.Eq) and the SetChannel command (ChannelParam::Eq + id).
enum Param : int { On = 0, HpHz, HpSlope, LpHz, LpSlope, LowDb, LowHz, MidDb, MidHz, MidQ, HighDb, HighHz, kParams };

constexpr float kHpOff = 20.f;        // HP at (or below) this frequency is off
constexpr float kLpOff = 20000.f;     // LP at (or above) this frequency is off
constexpr float kMaxDb = 18.f;

struct Settings {
    bool on = true;
    float hpHz = kHpOff, lpHz = kLpOff;
    bool hp24 = false, lp24 = false;
    float lowDb = 0.f, lowHz = 100.f;
    float midDb = 0.f, midHz = 1000.f, midQ = 0.7f;
    float highDb = 0.f, highHz = 8000.f;
};

/** Range-checked setter: false when [id] or [v] is out of range (the caller drops the command). */
inline bool set(Settings& s, int id, float v) {
    if (!std::isfinite(v)) return false;
    auto in = [&](float lo, float hi) { return v >= lo && v <= hi; };
    switch (id) {
        case On: s.on = v != 0.f; return true;
        case HpHz: if (!in(kHpOff, 4000.f)) return false; s.hpHz = v; return true;
        case HpSlope: s.hp24 = v >= 24.f; return true;
        case LpHz: if (!in(100.f, kLpOff)) return false; s.lpHz = v; return true;
        case LpSlope: s.lp24 = v >= 24.f; return true;
        case LowDb: if (!in(-kMaxDb, kMaxDb)) return false; s.lowDb = v; return true;
        case LowHz: if (!in(30.f, 1000.f)) return false; s.lowHz = v; return true;
        case MidDb: if (!in(-kMaxDb, kMaxDb)) return false; s.midDb = v; return true;
        case MidHz: if (!in(100.f, 12000.f)) return false; s.midHz = v; return true;
        case MidQ: if (!in(0.2f, 10.f)) return false; s.midQ = v; return true;
        case HighDb: if (!in(-kMaxDb, kMaxDb)) return false; s.highDb = v; return true;
        case HighHz: if (!in(1000.f, 18000.f)) return false; s.highHz = v; return true;
        default: return false;
    }
}

struct Coefs { float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };

// ---- RBJ cookbook designs (normalised by a0) ----
inline Coefs lowpass(double f, double q, double sr) {
    const double w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q), a0 = 1 + al;
    return {float((1 - c) / 2 / a0), float((1 - c) / a0), float((1 - c) / 2 / a0), float(-2 * c / a0), float((1 - al) / a0)};
}
inline Coefs highpass(double f, double q, double sr) {
    const double w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q), a0 = 1 + al;
    return {float((1 + c) / 2 / a0), float(-(1 + c) / a0), float((1 + c) / 2 / a0), float(-2 * c / a0), float((1 - al) / a0)};
}
inline Coefs bell(double f, double q, double db, double sr) {
    const double A = std::pow(10.0, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / (2 * q), a0 = 1 + al / A;
    return {float((1 + al * A) / a0), float(-2 * c / a0), float((1 - al * A) / a0), float(-2 * c / a0), float((1 - al / A) / a0)};
}
// Shelves with slope S = 1 (the steepest without a bump).
inline Coefs lowShelf(double f, double db, double sr) {
    const double A = std::pow(10.0, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / 2 * std::sqrt(2.0), sa = 2 * std::sqrt(A) * al;
    const double a0 = (A + 1) + (A - 1) * c + sa;
    return {float(A * ((A + 1) - (A - 1) * c + sa) / a0), float(2 * A * ((A - 1) - (A + 1) * c) / a0), float(A * ((A + 1) - (A - 1) * c - sa) / a0),
            float(-2 * ((A - 1) + (A + 1) * c) / a0), float(((A + 1) + (A - 1) * c - sa) / a0)};
}
inline Coefs highShelf(double f, double db, double sr) {
    const double A = std::pow(10.0, db / 40), w = 2 * M_PI * f / sr, c = std::cos(w), al = std::sin(w) / 2 * std::sqrt(2.0), sa = 2 * std::sqrt(A) * al;
    const double a0 = (A + 1) - (A - 1) * c + sa;
    return {float(A * ((A + 1) + (A - 1) * c + sa) / a0), float(-2 * A * ((A - 1) + (A + 1) * c) / a0), float(A * ((A + 1) + (A - 1) * c - sa) / a0),
            float(2 * ((A - 1) - (A + 1) * c) / a0), float(((A + 1) - (A - 1) * c - sa) / a0)};
}

/** One stereo biquad (transposed direct form II). */
struct Stage {
    Coefs k;
    float l1 = 0, l2 = 0, r1 = 0, r2 = 0;
    bool live = false;
    void clear() { l1 = l2 = r1 = r2 = 0; }
    inline void run(float* lr, int n) {
        const float b0 = k.b0, b1 = k.b1, b2 = k.b2, a1 = k.a1, a2 = k.a2;
        float L1 = l1, L2 = l2, R1 = r1, R2 = r2;
        for (int i = 0; i < n; ++i) {
            const float x = lr[2 * i], y = b0 * x + L1;
            L1 = b1 * x - a1 * y + L2; L2 = b2 * x - a2 * y; lr[2 * i] = y;
            const float xr = lr[2 * i + 1], yr = b0 * xr + R1;
            R1 = b1 * xr - a1 * yr + R2; R2 = b2 * xr - a2 * yr; lr[2 * i + 1] = yr;
        }
        l1 = L1; l2 = L2; r1 = R1; r2 = R2;
    }
};

class ChannelEq {
public:
    Settings s;   // targets (written by the command handler on the audio thread)

    /** Filters [lr] (interleaved stereo, n frames) in place. */
    void process(float* lr, int n, float sr) {
        if (sr != mSr) { mSr = sr; mDirty = true; }
        const float ny = 0.45f * sr;
        // targets: OFF means every band glides to neutral
        const bool on = s.on;
        const float tHp = on ? s.hpHz : kHpOff, tLp = on ? std::min(s.lpHz, kLpOff) : kLpOff;
        const float tLow = on ? s.lowDb : 0.f, tMid = on ? s.midDb : 0.f, tHigh = on ? s.highDb : 0.f;
        const float k = 1.f - std::exp(-(float)n / (0.03f * sr));
        bool moved = mDirty;
        auto glideLog = [&](float& cur, float tgt) {
            if (cur == tgt) return;
            const float d = std::log(tgt / cur);
            if (std::fabs(d) < 1e-3f) cur = tgt; else cur *= std::exp(d * k);
            moved = true;
        };
        auto glideLin = [&](float& cur, float tgt, float eps) {
            if (cur == tgt) return;
            if (std::fabs(tgt - cur) < eps) cur = tgt; else cur += (tgt - cur) * k;
            moved = true;
        };
        glideLog(mHp, tHp); glideLog(mLp, tLp);
        glideLin(mLow, tLow, 0.01f); glideLin(mMid, tMid, 0.01f); glideLin(mHigh, tHigh, 0.01f);
        glideLog(mLowHz, s.lowHz); glideLog(mMidHz, s.midHz); glideLog(mHighHz, s.highHz); glideLog(mMidQ, s.midQ);
        if (s.hp24 != mHp24 || s.lp24 != mLp24) { mHp24 = s.hp24; mLp24 = s.lp24; moved = true; }
        if (moved) {
            mDirty = false;
            // high-pass: one Butterworth stage (12 dB/oct) or two (24 dB/oct)
            const bool hp = mHp > kHpOff * 1.01f;
            setLive(mSt[0], hp);
            setLive(mSt[1], hp && mHp24);
            if (hp) {
                const double f = std::min(mHp, ny);
                mSt[0].k = highpass(f, mHp24 ? 0.54119610 : M_SQRT1_2, sr);
                if (mHp24) mSt[1].k = highpass(f, 1.30656296, sr);
            }
            setLive(mSt[2], std::fabs(mLow) > 0.01f);
            if (mSt[2].live) mSt[2].k = lowShelf(std::min(mLowHz, ny), mLow, sr);
            setLive(mSt[3], std::fabs(mMid) > 0.01f);
            if (mSt[3].live) mSt[3].k = bell(std::min(mMidHz, ny), mMidQ, mMid, sr);
            setLive(mSt[4], std::fabs(mHigh) > 0.01f && mHighHz < ny);
            if (mSt[4].live) mSt[4].k = highShelf(mHighHz, mHigh, sr);
            // low-pass (off at 20 kHz, and above what the sample rate can carry)
            const bool lp = mLp < kLpOff * 0.99f && mLp < ny;
            setLive(mSt[5], lp);
            setLive(mSt[6], lp && mLp24);
            if (lp) {
                mSt[5].k = lowpass(mLp, mLp24 ? 0.54119610 : M_SQRT1_2, sr);
                if (mLp24) mSt[6].k = lowpass(mLp, 1.30656296, sr);
            }
        }
        for (auto& st : mSt) if (st.live) st.run(lr, n);
    }

    /** True while any band is doing something (for tests and the CPU budget). */
    bool active() const { for (auto& st : mSt) if (st.live) return true; return false; }

    void reset() { for (auto& st : mSt) { st.clear(); st.live = false; } mDirty = true; }

private:
    static void setLive(Stage& st, bool on) { if (on && !st.live) st.clear(); st.live = on; }
    Stage mSt[7];
    float mSr = 0.f;
    bool mDirty = true, mHp24 = false, mLp24 = false;
    // the working (smoothed) values
    float mHp = kHpOff, mLp = kLpOff, mLow = 0.f, mMid = 0.f, mHigh = 0.f;
    float mLowHz = 100.f, mMidHz = 1000.f, mHighHz = 8000.f, mMidQ = 0.7f;
};

}  // namespace eq
