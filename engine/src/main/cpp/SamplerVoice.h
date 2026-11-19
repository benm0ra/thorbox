// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>
#include "Commands.h"
#include "SampleBuffer.h"

// One playing instance of a sample. Varispeed playback with 4-point Hermite interpolation
// and a linear AR envelope (the full ADSR arrives in M2). Everything here is RT-safe.
class SamplerVoice {
public:
    bool active() const { return mStage != Stage::Off; }
    int pad() const { return mPad; }
    const SampleBuffer* buffer() const { return mBuf; }
    double position() const { return mPos; }
    bool sounding() const { return mStage == Stage::Attack || mStage == Stage::Sustain; }
    uint64_t startedAt() const { return mStartStamp; }
    bool releasing() const { return mStage == Stage::Release; }

    // [startFrame, endFrame) is the slice this hit plays (the whole buffer if endFrame <= startFrame).
    void start(const SampleBuffer* buf, int pad, float pitchRatio, float gain, PlayMode mode,
               float deviceRate, uint64_t stamp, int64_t startFrame = 0, int64_t endFrame = -1) {
        mBuf = buf; mPad = pad; mPitchRatio = pitchRatio; mGain = gain; mMode = mode;
        const int64_t n = buf ? buf->frames : 0;
        mStart = std::clamp<int64_t>(startFrame, 0, std::max<int64_t>(0, n - 2));
        mEnd = (endFrame <= mStart || endFrame > n) ? n : endFrame;
        mPos = (double)mStart; mStartStamp = stamp;
        mEnv = 0.f;
        mStage = Stage::Attack;
        mEnvStep = 1.f / std::max(1.f, 0.001f * deviceRate);      // 1 ms de-click attack
    }

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
    enum class Stage { Off, Attack, Sustain, Release };

    // Channel count is a template parameter, so the inner loop has no per-sample channel branch.
    // The end-of-slice fade is computed only inside its last ~4 ms (no per-sample division before it).
    template <int CH>
    void renderT(float* out, int numFrames, float deviceRate) {
        const SampleBuffer& b = *mBuf;
        const double inc = (double)mPitchRatio * (double)b.sampleRate / (double)deviceRate;
        const bool loop = (mMode == PlayMode::Loop);
        const double end = (double)mEnd, len = (double)(mEnd - mStart);
        const double fadeLen = std::max(1.0, 0.004 * (double)b.sampleRate);
        const double fadeStart = loop ? 1e300 : end - fadeLen;
        const float invFade = (float)(1.0 / fadeLen);
        for (int i = 0; i < numFrames; ++i) {
            if (mStage == Stage::Attack) {
                mEnv += mEnvStep;
                if (mEnv >= 1.f) { mEnv = 1.f; mStage = Stage::Sustain; }
            } else if (mStage == Stage::Release) {
                mEnv -= mEnvStep;
                if (mEnv <= 0.f) { mStage = Stage::Off; return; }
            }
            if (mPos >= end) {
                if (loop && len >= 2.0) mPos -= len;
                else { mStage = Stage::Off; return; }
            }
            const int64_t i0 = (int64_t)mPos;
            const float t = (float)(mPos - (double)i0);
            float g = mGain * mEnv;
            if (mPos > fadeStart) g *= (float)(end - mPos) * invFade;
            if constexpr (CH == 1) {
                const float s = interp(b, 0, i0, t, loop) * g;
                out[2 * i] += s; out[2 * i + 1] += s;
            } else {
                out[2 * i]     += interp(b, 0, i0, t, loop) * g;
                out[2 * i + 1] += interp(b, 1, i0, t, loop) * g;
            }
            mPos += inc;
        }
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
    PlayMode mMode = PlayMode::OneShot;
    Stage mStage = Stage::Off;
    uint64_t mStartStamp = 0;
};
