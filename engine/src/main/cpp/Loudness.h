// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Loudness meter for the master bus, after ITU-R BS.1770 / EBU R128: K-weighting (high shelf +
// RLB high-pass), 100 ms sub-blocks, momentary (400 ms), short-term (3 s) and gated integrated
// loudness (absolute gate −70 LUFS, relative gate −10 LU) kept as a histogram, so memory stays
// fixed however long the session runs. Also sample peaks since the last reset.
//
// process() runs on the audio thread and allocates nothing; the results are published through
// atomics once per 100 ms block. reset() may be called from any thread (it's a flag).
// Header-only, Android-free (tested on desktop: engine/src/test/cpp/loudness_test.cpp).

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>

namespace meter {

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
    inline double process(double x) { const double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
    void clear() { z1 = z2 = 0; }
};

class Loudness {
public:
    static constexpr float kFloor = -100.f;          // what "nothing yet" reads as

    void setSampleRate(double sr) {
        if (sr == mSr) return;
        mSr = sr;
        // BS.1770 stage 1: high shelf (+4 dB above ~1.7 kHz), designed for any sample rate.
        {
            const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
            const double K = std::tan(M_PI * f0 / sr), Vh = std::pow(10.0, G / 20.0), Vb = std::pow(Vh, 0.4996667741545416);
            const double a0 = 1.0 + K / Q + K * K;
            mShelf = {(Vh + Vb * K / Q + K * K) / a0, 2.0 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0,
                      2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0};
        }
        // Stage 2: RLB high-pass (~38 Hz).
        {
            const double f0 = 38.13547087602444, Q = 0.5003270373238773;
            const double K = std::tan(M_PI * f0 / sr);
            const double a0 = 1.0 + K / Q + K * K;
            mHp = {1.0, -2.0, 1.0, 2.0 * (K * K - 1.0) / a0, (1.0 - K / Q + K * K) / a0};
        }
        mBlockLen = std::max(1, (int)std::lround(sr * 0.1));
        resetNow();
    }

    void reset() { mResetReq.store(true, std::memory_order_relaxed); }

    // One stereo frame (post master fader).
    inline void process(float l, float r) {
        if (mResetReq.load(std::memory_order_relaxed)) { mResetReq.store(false, std::memory_order_relaxed); resetNow(); }
        const double kl = mHpL.process(mShelfL.process(l)), kr = mHpR.process(mShelfR.process(r));
        mAcc += kl * kl + kr * kr;
        mPkL = std::max(mPkL, std::fabs(l)); mPkR = std::max(mPkR, std::fabs(r));
        if (++mN >= mBlockLen) endBlock();
    }

    float momentary() const { return mM.load(std::memory_order_relaxed); }
    float shortTerm() const { return mS.load(std::memory_order_relaxed); }
    float integrated() const { return mI.load(std::memory_order_relaxed); }
    float peakDbL() const { return mPeakDbL.load(std::memory_order_relaxed); }
    float peakDbR() const { return mPeakDbR.load(std::memory_order_relaxed); }
    float seconds() const { return mSecs.load(std::memory_order_relaxed); }

    static float lufs(double meanSquare) { return meanSquare > 1e-12 ? (float)(-0.691 + 10.0 * std::log10(meanSquare)) : kFloor; }

private:
    static constexpr int kSub = 30;                   // 3 s of 100 ms sub-blocks
    static constexpr float kHistLo = -70.f, kHistStep = 0.1f;
    static constexpr int kHist = 760;                 // −70 … +6 LUFS in 0.1 LU bins

    void resetNow() {
        mShelfL = mShelf; mShelfR = mShelf; mHpL = mHp; mHpR = mHp;
        mShelfL.clear(); mShelfR.clear(); mHpL.clear(); mHpR.clear();
        mAcc = 0; mN = 0; mSubs.fill(0.0); mSubCount = 0; mSubHead = 0;
        mHistN.fill(0); mHistE.fill(0.0);
        mPkL = mPkR = 0.f; mBlocks = 0;
        mM.store(kFloor); mS.store(kFloor); mI.store(kFloor); mPeakDbL.store(kFloor); mPeakDbR.store(kFloor); mSecs.store(0.f);
    }

    void endBlock() {
        mSubs[mSubHead] = mAcc / mN;                  // mean square (L + R summed, per BS.1770)
        mSubHead = (mSubHead + 1) % kSub;
        mSubCount = std::min(mSubCount + 1, kSub);
        mAcc = 0; mN = 0; ++mBlocks;
        auto meanOf = [&](int count) {
            double s = 0; const int c = std::min(count, mSubCount);
            for (int k = 1; k <= c; ++k) s += mSubs[(mSubHead - k + kSub) % kSub];
            return c > 0 ? s / c : 0.0;
        };
        const double m400 = meanOf(4);
        mM.store(mSubCount >= 4 ? lufs(m400) : kFloor, std::memory_order_relaxed);
        mS.store(mSubCount >= 30 ? lufs(meanOf(30)) : (mSubCount >= 4 ? lufs(meanOf(mSubCount)) : kFloor), std::memory_order_relaxed);
        // Integrated: every 400 ms gating block (75% overlap = one per sub-block) above −70 LUFS.
        if (mSubCount >= 4) {
            const float L = lufs(m400);
            if (L > kHistLo) {
                const int bin = std::clamp((int)((L - kHistLo) / kHistStep), 0, kHist - 1);
                mHistN[bin]++; mHistE[bin] += m400;
            }
            double e = 0; long long n = 0;
            for (int b = 0; b < kHist; ++b) { e += mHistE[b]; n += mHistN[b]; }
            if (n > 0) {
                const float rel = lufs(e / n) - 10.f;
                double e2 = 0; long long n2 = 0;
                for (int b = 0; b < kHist; ++b) if (kHistLo + (b + 0.5f) * kHistStep >= rel) { e2 += mHistE[b]; n2 += mHistN[b]; }
                mI.store(n2 > 0 ? lufs(e2 / n2) : kFloor, std::memory_order_relaxed);
            }
        }
        mPeakDbL.store(mPkL > 1e-6f ? 20.f * std::log10(mPkL) : kFloor, std::memory_order_relaxed);
        mPeakDbR.store(mPkR > 1e-6f ? 20.f * std::log10(mPkR) : kFloor, std::memory_order_relaxed);
        mSecs.store((float)(mBlocks * 0.1), std::memory_order_relaxed);
    }

    double mSr = 0;
    Biquad mShelf, mHp, mShelfL, mShelfR, mHpL, mHpR;
    int mBlockLen = 4800, mN = 0;
    double mAcc = 0;
    std::array<double, kSub> mSubs{};
    int mSubCount = 0, mSubHead = 0;
    std::array<long long, kHist> mHistN{};
    std::array<double, kHist> mHistE{};
    float mPkL = 0.f, mPkR = 0.f;
    long long mBlocks = 0;
    std::atomic<bool> mResetReq{false};
    std::atomic<float> mM{kFloor}, mS{kFloor}, mI{kFloor}, mPeakDbL{kFloor}, mPeakDbR{kFloor}, mSecs{0.f};
};

} // namespace meter
