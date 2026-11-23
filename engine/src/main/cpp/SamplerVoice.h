// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>
#include "Commands.h"
#include "SampleBuffer.h"

// Per-hit sound settings from the pad (PAD EDIT page). Defaults = the plain old behaviour.
struct VoiceOpts {
    float pan = 0.f;          // -1 … 1, equal power (unity in the centre)
    float attackMs = 1.f;     // ≥ 1 ms (de-click)
    float decayMs = 0.f;      // 0 = hold; else fades to silence over this long after the attack ("decay from start")
    float releaseMs = 15.f;   // the fade on note-off / choke / stop
    float cutoff = 1.f;       // 0 … 1 (20 Hz … 20 kHz, log); 1 = filter off
    float reso = 0.f;         // 0 … 1
    bool reverse = false;     // plays the slice backwards
};

// One playing instance of a sample. Varispeed playback with 4-point Hermite interpolation, an
// attack / decay / release envelope, equal-power pan, an optional 2-pole low-pass (TPT state
// variable filter) and reverse. Everything here is RT-safe.
class SamplerVoice {
public:
    bool active() const { return mStage != Stage::Off; }
    int pad() const { return mPad; }
    const SampleBuffer* buffer() const { return mBuf; }
    double position() const { return mPos; }
    bool sounding() const { return mStage == Stage::Attack || mStage == Stage::Sustain; }
    uint64_t startedAt() const { return mStartStamp; }
    bool releasing() const { return mStage == Stage::Release; }
    float releaseMs() const { return mReleaseMs; }

    // [startFrame, endFrame) is the slice this hit plays (the whole buffer if endFrame <= startFrame).
    void start(const SampleBuffer* buf, int pad, float pitchRatio, float gain, PlayMode mode,
               float deviceRate, uint64_t stamp, int64_t startFrame = 0, int64_t endFrame = -1,
               const VoiceOpts& o = VoiceOpts()) {
        mBuf = buf; mPad = pad; mPitchRatio = pitchRatio; mGain = gain; mMode = mode;
        const int64_t n = buf ? buf->frames : 0;
        mStart = std::clamp<int64_t>(startFrame, 0, std::max<int64_t>(0, n - 2));
        mEnd = (endFrame <= mStart || endFrame > n) ? n : endFrame;
        mReverse = o.reverse;
        mPos = mReverse ? (double)(mEnd - 1) : (double)mStart; mStartStamp = stamp;
        mEnv = 0.f;
        mStage = Stage::Attack;
        mEnvStep = 1.f / std::max(1.f, std::max(1.f, o.attackMs) * 0.001f * deviceRate);
        mDecayStep = o.decayMs > 0.f ? 1.f / std::max(1.f, o.decayMs * 0.001f * deviceRate) : 0.f;
        mReleaseMs = std::max(1.f, o.releaseMs);
        const float a = (std::clamp(o.pan, -1.f, 1.f) + 1.f) * 0.78539816f;   // equal power, unity at centre
        mPanL = std::cos(a) * 1.41421356f; mPanR = std::sin(a) * 1.41421356f;
        mFilter = o.cutoff < 0.995f;
        if (mFilter) {
            const float fc = std::min(20.f * std::pow(1000.f, std::clamp(o.cutoff, 0.f, 1.f)), 0.45f * deviceRate);
            mG = std::tan(3.14159265f * fc / deviceRate);
            mK = 2.f - 1.9f * std::clamp(o.reso, 0.f, 1.f);
            mA1 = 1.f / (1.f + mG * (mG + mK)); mA2 = mG * mA1; mA3 = mG * mA2;
            mIc1[0] = mIc1[1] = mIc2[0] = mIc2[1] = 0.f;
        }
    }

    /** Fade out over the pad's own release time. */
    void release(float deviceRate) { release(mReleaseMs, deviceRate); }
    void release(float ms, float deviceRate) {
        if (mStage == Stage::Off) return;
        mStage = Stage::Release;
        mEnvStep = mEnv / std::max(1.f, ms * 0.001f * deviceRate);
        if (mEnvStep <= 0.f) mStage = Stage::Off;
    }

    void kill() { mStage = Stage::Off; mBuf = nullptr; }
    void setPitchRatio(float r) { mPitchRatio = r; }
    PlayMode mode() const { return mMode; }

    // Adds into an interleaved stereo buffer.
    void render(float* out, int numFrames, float deviceRate) {
        if (!active() || !mBuf || mBuf->frames < 2) { mStage = Stage::Off; return; }
        if (mBuf->channels == 1) renderT<1>(out, numFrames, deviceRate);
        else renderT<2>(out, numFrames, deviceRate);
    }

private:
    enum class Stage { Off, Attack, Sustain, Decay, Release };

    // Channel count is a template parameter, so the inner loop has no per-sample channel branch.
    // The end-of-slice fade is computed only inside its last ~4 ms (no per-sample division before it).
    template <int CH>
    void renderT(float* out, int numFrames, float deviceRate) {
        const SampleBuffer& b = *mBuf;
        const double step = (double)mPitchRatio * (double)b.sampleRate / (double)deviceRate;
        const double inc = mReverse ? -step : step;
        const bool loop = (mMode == PlayMode::Loop);
        const double lo = (double)mStart, hi = (double)mEnd, len = hi - lo;
        const double fadeLen = std::max(1.0, 0.004 * (double)b.sampleRate);
        const float invFade = (float)(1.0 / fadeLen);
        for (int i = 0; i < numFrames; ++i) {
            switch (mStage) {
                case Stage::Attack:
                    mEnv += mEnvStep;
                    if (mEnv >= 1.f) { mEnv = 1.f; mStage = mDecayStep > 0.f ? Stage::Decay : Stage::Sustain; }
                    break;
                case Stage::Decay:
                    mEnv -= mDecayStep;
                    if (mEnv <= 0.f) { mStage = Stage::Off; return; }
                    break;
                case Stage::Release:
                    mEnv -= mEnvStep;
                    if (mEnv <= 0.f) { mStage = Stage::Off; return; }
                    break;
                default: break;
            }
            if (!mReverse && mPos >= hi) {
                if (loop && len >= 2.0) mPos -= len;
                else { mStage = Stage::Off; return; }
            } else if (mReverse && mPos < lo) {
                if (loop && len >= 2.0) mPos += len;
                else { mStage = Stage::Off; return; }
            }
            const int64_t i0 = (int64_t)mPos;
            const float t = (float)(mPos - (double)i0);
            float g = mGain * mEnv;
            if (!loop) {                                      // fade the last ~4 ms before the slice edge
                const double left = mReverse ? mPos - lo : hi - mPos;
                if (left < fadeLen) g *= (float)left * invFade;
            }
            float l, r;
            if constexpr (CH == 1) { l = r = interp(b, 0, i0, t, loop); }
            else { l = interp(b, 0, i0, t, loop); r = interp(b, 1, i0, t, loop); }
            if (mFilter) { l = svf(l, 0); r = (CH == 1) ? l : svf(r, 1); }
            out[2 * i]     += l * g * mPanL;
            out[2 * i + 1] += r * g * mPanR;
            mPos += inc;
        }
    }

    // TPT state-variable low-pass (Zavalishin), one state pair per channel.
    inline float svf(float x, int ch) {
        const float v3 = x - mIc2[ch];
        const float v1 = mA1 * mIc1[ch] + mA2 * v3;
        const float v2 = mIc2[ch] + mA2 * mIc1[ch] + mA3 * v3;
        mIc1[ch] = 2.f * v1 - mIc1[ch];
        mIc2[ch] = 2.f * v2 - mIc2[ch];
        return v2;
    }

    static inline int64_t idx(int64_t i, int64_t n, bool loop) {
        if (loop) { i %= n; if (i < 0) i += n; return i; }
        return i < 0 ? 0 : (i >= n ? n - 1 : i);
    }
    static inline float interp(const SampleBuffer& b, int ch, int64_t i, float t, bool loop) {
        const int64_t n = b.frames;
        if (i >= 1 && i + 2 < n) {   // fast path (≈ every sample): direct reads, no wrap/clamp math
            const float* d = b.data.data() + (size_t)((i - 1) * b.channels + ch);
            const int s = b.channels;
            return hermite(d[0], d[s], d[2 * s], d[3 * s], t);
        }
        const float xm1 = b.at(ch, idx(i - 1, n, loop));
        const float x0  = b.at(ch, idx(i,     n, loop));
        const float x1  = b.at(ch, idx(i + 1, n, loop));
        const float x2  = b.at(ch, idx(i + 2, n, loop));
        return hermite(xm1, x0, x1, x2, t);
    }
    static inline float hermite(float xm1, float x0, float x1, float x2, float t) {   // 4-point, 3rd-order
        const float c = (x1 - xm1) * 0.5f;
        const float v = x0 - x1;
        const float w = c + v;
        const float a = w + v + (x2 - x0) * 0.5f;
        const float bNeg = w + a;
        return (((a * t) - bNeg) * t + c) * t + x0;
    }

    const SampleBuffer* mBuf = nullptr;
    int mPad = -1;
    double mPos = 0.0;
    int64_t mStart = 0, mEnd = 0;
    float mPitchRatio = 1.f;
    float mGain = 1.f;
    float mEnv = 0.f;
    float mEnvStep = 0.f;
    float mDecayStep = 0.f;
    float mReleaseMs = 15.f;
    float mPanL = 1.f, mPanR = 1.f;
    bool mReverse = false, mFilter = false;
    float mG = 0.f, mK = 2.f, mA1 = 1.f, mA2 = 0.f, mA3 = 0.f;
    float mIc1[2] = {0.f, 0.f}, mIc2[2] = {0.f, 0.f};
    PlayMode mMode = PlayMode::OneShot;
    Stage mStage = Stage::Off;
    uint64_t mStartStamp = 0;
};
