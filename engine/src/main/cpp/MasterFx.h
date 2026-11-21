// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Punch-in master effects, modelled on the classic sampler-workstation performance set (SP-style),
// played from XY pads: hold a pad to engage, release to snap back dry. Where the finger sits on the
// pad is the effect's XY, and it can move while held. Several can stack. Header-only, Android-free.
//
//   LOOPER     DJ-style loop of the slice you hit:     X = length 1/4 … 1/32T   Y = speed (↓ reverse · mid 1× · ↑ up to 2×)
//   FILTER     filter + drive:                         X = LP ◂ ▸ HP            Y = resonance, drive above half
//   TAPE STOP  X left = tape stop, right = backspin    Y = how long it takes
//   LO-FI      sample-rate + bit crush, anti-alias LP  X = rate                 Y = bits
//   VINYL      wow, crackle, band-limit, squash        X = wow                  Y = dust
//   SCATTER    re-orders the bar in slices             X = type (1/16 → 1/8, wilder) Y = depth
//   SLICER     rhythmic gate patterns, song-locked     X = pattern              Y = duty
//   REVERB     big wash with a tail                    X = size                 Y = tone
//   ECHO       tempo-synced tape echo with a tail      X = time 1/16 … 1/2      Y = feedback (top runs away)
//   ISOLATOR   DJ 3-band kill                          X = band (low/mid/high)  Y = cut depth
//   CASSETTE   tape age + saturation                   X = age                  Y = drive
//   RESONATOR  tuned comb resonance                    X = note (snapped to key by the app)  Y = feedback
//
// Order: [loop/time FX: tape stop > looper > scatter] → isolator → filter → lo-fi → cassette → vinyl
//        → resonator → slicer → echo → reverb
// Time FX read a ring buffer of the DRY master input, so they replay what you just heard. Every
// effect crossfades over ~6 ms, so engaging and releasing never click. ECHO and REVERB keep their
// tails after release.
//
// RT-safe: every buffer is allocated once in the constructor (main thread); process() never allocates.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace fx {

constexpr float kPi = 3.14159265358979f;

// Must match com.thorbox.engine.NativeEngine.Fx.
enum Id : int { Looper = 0, Filter, TapeStop, LoFi, Vinyl, Scatter, Slicer, Reverb, Echo, Isolator, Cassette, Resonator, kCount };

// Tempo-synced divisions, in quarter notes: x picks one (left = long, right = short).
inline float divisionBeats(float x) {
    static constexpr float d[] = {1.f, 0.5f, 1.f / 3.f, 0.25f, 1.f / 6.f, 0.125f, 1.f / 12.f};   // 1/4 … 1/32T
    return d[std::clamp((int)(x * 7.f), 0, 6)];
}
// ECHO times in quarter notes: 1/16, 1/8T, 1/8, 1/8D, 1/4, 1/4D, 1/2.
inline float echoBeats(float x) {
    static constexpr float d[] = {0.25f, 1.f / 3.f, 0.5f, 0.75f, 1.f, 1.5f, 2.f};
    return d[std::clamp((int)(x * 7.f), 0, 6)];
}
// LOOPER speed from Y: below 0.4 reverse (1× → 0.5×), 0.4–0.6 normal, above up to 2× (pitched up).
inline float looperSpeed(float y) {
    if (y >= 0.6f) return 1.f + (y - 0.6f) / 0.4f;
    if (y > 0.4f) return 1.f;
    return -(0.5f + 0.5f * y / 0.4f);
}
// SLICER patterns (16 steps, bit i = step i open).
inline constexpr uint16_t kSlicePatterns[8] = {
    0xFFFF,   // every 16th
    0x5555,   // every other
    0x6DB6,   // rolling 0110 1101 1011 0110
    0x4925,   // tresillo-ish 3-3-2
    0x7777,   // three on, one off
    0x3333,   // pairs
    0x1111,   // quarters
    0xEDB7,   // stutter-y
};
// RESONATOR note (MIDI) from X: four octaves, C2..C6 (the app snaps X to the song's key).
inline float resonatorNote(float x) { return 36.f + 48.f * std::clamp(x, 0.f, 1.f); }
inline float soft(float x) { x = std::clamp(x, -3.f, 3.f); return x * (27.f + x * x) / (27.f + 9.f * x * x); }

class MasterFx {
public:
    static constexpr int kRingBits = 19;                 // 524288 frames ≈ 10.9 s at 48 kHz
    static constexpr int kRing = 1 << kRingBits;
    static constexpr int kEcho = 1 << 18;                // 262144 frames: a 1/2-note echo down to ~45 BPM at 48 kHz
    static constexpr int kRes = 4096;                    // resonator line (C2 at 96 kHz ≈ 1470 frames)

    MasterFx() : mRingL(kRing, 0.f), mRingR(kRing, 0.f), mEchoBuf(kEcho, 0.f), mResL(kRes, 0.f), mResR(kRes, 0.f) {
        // REVERB lines (Freeverb-style tunings, scaled for up to 96 kHz). Allocated once, here.
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
            s.start = mWrite; s.phase = 0.0; s.speed = 1.0; s.readPos = (double)mWrite; s.age = 0.0;
            s.mode = s.x >= 0.5f ? 1 : 0;                // TAPE STOP: 0 stop, 1 backspin (picked where you land)
            if (id == Filter) { mFl[0] = mFl[1] = Svf{}; mFh[0] = mFh[1] = Svf{}; }
            if (id == Scatter) { mScSeed = mScSeed * 1664525u + 1013904223u + mWrite; mScStep = -1; }
        }
    }
    void move(int id, float x, float y) {
        if (id < 0 || id >= kCount || !mSlot[id].on) return;
        mSlot[id].x = std::clamp(x, 0.f, 1.f); mSlot[id].y = std::clamp(y, 0.f, 1.f);
    }
    void release(int id) { if (id >= 0 && id < kCount) mSlot[id].on = false; }
    void releaseAll() { for (auto& s : mSlot) s.on = false; }
    uint32_t activeMask() const {
        uint32_t m = 0;
        for (int i = 0; i < kCount; ++i) if (mSlot[i].wet > 1e-3f || mSlot[i].on) m |= 1u << i;
        return m;
    }
    float reverbTail() const { return mRevTail; }
    float echoTail() const { return mEchoTail; }

    // In-place stereo processing of n frames (interleaved). tick0/tps: song position (960 PPQN) for
    // the song-locked effects; when the transport isn't running (tps == 0) they free-run at the tempo.
    void process(float* io, int n, float sr, float bpm, double tick0 = 0.0, double tps = 0.0) {
        const float xf = 1.f - std::exp(-1.f / (0.006f * sr));     // ~6 ms wet crossfade
        const double beat = 60.0 / std::max(30.f, bpm) * sr;          // samples per quarter note
        if (tps <= 0.0) { tps = 960.0 / beat; tick0 = mFreeTick; }
        mFreeTick = tick0 + n * tps;

        // Fast path: everything dry, faded out and no tail ringing → only feed the ring.
        bool any = mRevTail > 1e-5f || mEchoTail > 1e-5f;
        for (auto& s : mSlot) any |= s.on || s.wet > 1e-4f;
        if (!any) {
            for (int i = 0; i < n; ++i) push(io[2 * i], io[2 * i + 1]);
            return;
        }

        // ---------- control-rate setup ----------
        Slot& lp = mSlot[Looper]; Slot& tp = mSlot[TapeStop]; Slot& sc = mSlot[Scatter];
        const double lpLen = std::max(64.0, divisionBeats(lp.x) * beat);
        const double lpSpeed = looperSpeed(lp.y);
        const double tpTime = (0.08 + 1.9 * tp.y * tp.y) * sr;          // 80 ms … 2 s
        Slot& fl = mSlot[Filter];
        setupFilter(fl.x, fl.y, sr);
        const float drive = std::clamp((fl.y - 0.5f) * 2.f, 0.f, 1.f);
        const float dGain = 1.f + 5.f * drive, dNorm = 1.f / (1.f + 0.45f * (dGain - 1.f));
        Slot& lf = mSlot[LoFi];
        const int hold = 1 + (int)(lf.x * lf.x * 30.f);                 // 48 kHz → ~1.5 kHz
        const float levels = std::exp2(16.f - 12.f * lf.y);              // 16 → 4 bits
        const float lfK = hold > 1 ? 1.f - std::exp(-2.f * kPi * std::min(0.45f * sr / hold, 16000.f) / sr) : 1.f;
        Slot& vy = mSlot[Vinyl]; Slot& cs = mSlot[Cassette];
        const float csK = 1.f - std::exp(-2.f * kPi * (12000.f * std::pow(0.2f, cs.x)) / sr);  // 12 k → 2.4 k
        const float csG = 1.f + 5.f * cs.y, csN = 1.f / (1.f + 0.5f * (csG - 1.f));
        Slot& is = mSlot[Isolator];
        // 4th-order (two cascaded Butterworth) crossovers at 280 Hz and 2.6 kHz: steep enough to really kill a band.
        if (mIsoSr != sr) { mIsoSr = sr; for (auto& f : mIso) { for (int k = 0; k < 4; ++k) f[k].set(280.f, kButter, sr); for (int k = 4; k < 8; ++k) f[k].set(2600.f, kButter, sr); } }
        const float pos = is.x * 2.f, depth = std::min(1.f, is.y * 1.15f);
        const float kLo = depth * std::clamp(1.f - pos, 0.f, 1.f);
        const float kMid = depth * std::clamp(1.f - std::fabs(pos - 1.f), 0.f, 1.f);
        const float kHi = depth * std::clamp(pos - 1.f, 0.f, 1.f);
        Slot& sl = mSlot[Slicer];
        const uint16_t pat = kSlicePatterns[std::clamp((int)(sl.x * 8.f), 0, 7)];
        const float duty = 0.15f + 0.8f * sl.y;
        Slot& rs = mSlot[Resonator];
        const float resHz = 440.f * std::exp2((resonatorNote(rs.x) - 69.f) / 12.f);
        // the damping one-pole adds ~(1-a)/a samples to the loop; take it off so the comb lands on the note
        const float resP = std::clamp(sr / resHz - (1.f - kResDamp) / kResDamp, 2.f, (float)kRes - 2.f);
        const float resFb = 0.72f + 0.27f * rs.y;
        Slot& ec = mSlot[Echo];
        const float ecTarget = (float)std::min((double)kEcho - 4.0, echoBeats(ec.x) * beat);
        if (mEchoTime <= 0.f) mEchoTime = ecTarget;
        float ecFb = 0.25f + 0.8f * ec.y;                                 // tops out at 1.05: dub runaway (soft-clipped)
        if (!ec.on) ecFb = std::min(ecFb, 0.7f);                          // released: always lets the tail die
        if (mEchoSr != sr) { mEchoSr = sr; mEchoHp = 1.f - std::exp(-2.f * kPi * 150.f / sr); mEchoLp = 1.f - std::exp(-2.f * kPi * 3800.f / sr); }
        Slot& rv = mSlot[Reverb];
        if (mRevSr != sr) {                                                // delay lengths only change with the rate
            mRevSr = sr;
            for (int c = 0; c < 2; ++c) {
                for (int k = 0; k < kCombs; ++k) mCombLen[c][k] = std::min(mComb[c][k].size(), (size_t)((kCombLen[k] + c * 23) * sr / 44100.f));
                for (int k = 0; k < kAps; ++k) mApLenS[c][k] = std::min(mAp[c][k].size(), (size_t)((kApLen[k] + c * 23) * sr / 44100.f));
            }
        }
        const double scTicks = (sc.x < 0.5f ? 240.0 : 480.0);           // SCATTER slice: 1/16, or 1/8 for the wild half
        const int scSteps = sc.x < 0.5f ? 16 : 8;
        const double scLen = scTicks / tps;                                // samples per slice

        for (int i = 0; i < n; ++i) {
            const float inL = io[2 * i], inR = io[2 * i + 1];
            push(inL, inR);
            const uint32_t now = mWrite - 1;                              // index of the sample just pushed
            const double tick = tick0 + i * tps;
            float l = inL, r = inR;

            // ---------- LOOPER ----------
            fade(lp, xf);
            if (lp.wet > 0.f) {
                float a, b;
                if (lpSpeed >= 0.0) {
                    // Forward: loops from the moment you hit it; the first pass is live, so even a
                    // faster speed can't read ahead of the audio that exists yet.
                    const double elapsed = (double)(now - lp.start) + 1.0;
                    const double q = std::fmod(lp.phase, lpLen);
                    const double p = (double)lp.start + q;
                    const float g = edge((float)q, (float)lpLen, 96.f);
                    a = lerpAt(mRingL, p) * g; b = lerpAt(mRingR, p) * g;
                    lp.phase += lpSpeed;
                    if (elapsed < lpLen) lp.phase = std::min(lp.phase, elapsed);
                } else {
                    // Reverse: the slice you just heard, backwards (it already exists in full).
                    const double q = std::fmod(lp.phase, lpLen);
                    const double p = (double)lp.start - 1.0 - q;
                    const float g = edge((float)q, (float)lpLen, 96.f);
                    a = lerpAt(mRingL, p) * g; b = lerpAt(mRingR, p) * g;
                    lp.phase += -lpSpeed;
                }
                l += (a - l) * lp.wet; r += (b - r) * lp.wet;
            }

            // ---------- SCATTER: the bar re-ordered, slice by slice, a new pattern every punch ----------
            fade(sc, xf);
            if (sc.wet > 0.f) {
                const int64_t step = (int64_t)std::floor(tick / scTicks);
                if (step != mScStep) {
                    mScStep = step; mScStart = now;
                    const uint32_t k = (uint32_t)(step % scSteps);
                    uint32_t h = (mScSeed ^ (k * 2654435761u)) * 2246822519u; h ^= h >> 15;
                    const float roll = (h & 0xFFFF) / 65536.f;
                    const float d = 0.15f + 0.8f * sc.y;
                    // The first slice of each beat stays put more often: the groove survives the chaos.
                    mScAction = (roll < d * (k % 4 == 0 ? 0.5f : 1.f)) ? 1 + (int)((h >> 16) % (sc.x < 0.5f ? 4u : 5u)) : 0;
                }
                const double o = (double)(now - mScStart);
                float a = l, b = r;
                const double L = scLen;
                switch (mScAction) {
                    case 1: { const double p = (double)mScStart - L + std::fmod(o, L); a = lerpAt(mRingL, p); b = lerpAt(mRingR, p); break; }       // repeat last slice
                    case 2: { const double p = (double)mScStart - 1.0 - std::fmod(o, L); a = lerpAt(mRingL, p); b = lerpAt(mRingR, p); break; }     // last slice reversed
                    case 3: { const double p = (double)mScStart - L + std::fmod(o, L * 0.25); a = lerpAt(mRingL, p); b = lerpAt(mRingR, p); break; } // stutter
                    case 4: { a = 0.f; b = 0.f; break; }                                                                                          // drop out
                    case 5: { const double p = (double)mScStart - L + std::fmod(o * 0.5, L); a = lerpAt(mRingL, p); b = lerpAt(mRingR, p); break; }  // half speed
                    default: break;
                }
                if (mScAction != 0) {
                    const float g = edge((float)std::min(o, L - 1.0), (float)L, 64.f);
                    a = a * g + l * (1.f - g); b = b * g + r * (1.f - g);
                }
                l += (a - l) * sc.wet; r += (b - r) * sc.wet;
            }

            // ---------- TAPE STOP / BACKSPIN ----------
            fade(tp, xf);
            if (tp.wet > 0.f) {
                float g;
                if (tp.mode == 0) {                                         // stop: slows to a halt
                    if (tp.on) tp.speed = std::max(0.0, tp.speed - 1.0 / tpTime);
                    g = (float)std::min(1.0, tp.speed * 8.0);
                } else {                                                    // backspin: yanked backwards, fading out
                    if (tp.on) { tp.speed = std::max(-3.0, tp.speed - 4.0 / (0.25 * tpTime)); tp.age += 1.0; }
                    g = (float)std::max(0.0, 1.0 - tp.age / tpTime);
                }
                tp.readPos += tp.speed;
                l += (lerpAt(mRingL, tp.readPos) * g - l) * tp.wet;
                r += (lerpAt(mRingR, tp.readPos) * g - r) * tp.wet;
            }

            // ---------- ISOLATOR: DJ 3-band kill; zero depth is exactly the input ----------
            fade(is, xf);
            if (is.wet > 0.f) {
                float* ch[2] = {&l, &r};
                for (int c = 0; c < 2; ++c) {
                    const float x = *ch[c];
                    Svf* f = mIso[c];
                    const float lpLo = f[1].lp(f[0].lp(x)), hpLo = f[3].hp(f[2].hp(x));
                    const float lpHi = f[5].lp(f[4].lp(x)), hpHi = f[7].hp(f[6].hp(x));
                    const float y = x + kLo * (hpLo - x) + kHi * (lpHi - x) + kMid * ((lpLo + hpHi) - x);
                    *ch[c] = x + (y - x) * is.wet;
                }
            }

            // ---------- FILTER + DRIVE: left half low-pass, right half high-pass, centre ≈ open ----------
            fade(fl, xf);
            if (fl.wet > 0.f) {
                float fL = mUseHp ? mFh[0].hp(l) : mFl[0].lp(l);
                float fR = mUseHp ? mFh[1].hp(r) : mFl[1].lp(r);
                if (drive > 0.f) { fL = soft(fL * dGain) * dNorm; fR = soft(fR * dGain) * dNorm; }
                const float m = std::max(mFiltMix, drive);
                l += (fL - l) * fl.wet * m; r += (fR - r) * fl.wet * m;
            }

            // ---------- LO-FI: hold + quantize, then the converter's soft low-pass ----------
            fade(lf, xf);
            if (lf.wet > 0.f) {
                if (mHoldCount <= 0) {
                    mHoldL = std::round(l * levels) / levels; mHoldR = std::round(r * levels) / levels;
                    mHoldCount = hold;
                }
                --mHoldCount;
                float a = mHoldL, b = mHoldR;
                if (hold > 1) { mLfL += (a - mLfL) * lfK; mLfR += (b - mLfR) * lfK; a = mLfL; b = mLfR; }
                l += (a - l) * lf.wet; r += (b - r) * lf.wet;
            }

            // ---------- CASSETTE: wow/flutter, head loss, saturation, hiss, dropouts ----------
            fade(cs, xf);
            if (cs.wet > 0.f) {
                mCsWow += 0.9f / sr; if (mCsWow >= 1.f) mCsWow -= 1.f;
                mCsFl += 9.f / sr; if (mCsFl >= 1.f) mCsFl -= 1.f;
                const double d = 0.008 * sr + (0.2 + 1.0 * cs.x) * 0.001 * sr * std::sin(2.f * kPi * mCsWow)
                               + 0.15 * cs.x * 0.001 * sr * std::sin(2.f * kPi * mCsFl);
                const double p = (double)now - d;
                float a = lerpAt(mRingL, p), b = lerpAt(mRingR, p);
                // The wobble reads the dry ring; the effects before it are blended back on top.
                a += l - inL; b += r - inR;
                mCsL += (a - mCsL) * csK; mCsR += (b - mCsR) * csK; a = mCsL; b = mCsR;
                a = soft(a * csG) * csN; b = soft(b * csG) * csN;
                const float hiss = (rand01() * 2.f - 1.f) * 0.004f * cs.x;
                if (cs.x > 0.55f && rand01() < (cs.x - 0.55f) * 0.00012f * (48000.f / sr)) mCsDrop = 1.f;
                mCsDrop *= 0.9993f;
                const float dg = 1.f - 0.8f * mCsDrop;
                a = (a + hiss) * dg; b = (b + hiss) * dg;
                l += (a - l) * cs.wet; r += (b - r) * cs.wet;
            }

            // ---------- VINYL: wow (modulated delay), band-limit, squash, crackle ----------
            fade(vy, xf);
            if (vy.wet > 0.f) {
                mWowPh += 0.55f / sr; if (mWowPh >= 1.f) mWowPh -= 1.f;
                mFlutPh += 7.3f / sr; if (mFlutPh >= 1.f) mFlutPh -= 1.f;
                const float dep = (0.4f + 2.6f * vy.x) * 0.001f * sr;     // up to ~3 ms of pitch wobble
                const double d = 0.006 * sr + dep * std::sin(2.f * kPi * mWowPh) + dep * 0.15f * std::sin(2.f * kPi * mFlutPh);
                const double p = (double)now - d;
                float wl = lerpAt(mRingL, p) + (l - inL), wr = lerpAt(mRingR, p) + (r - inR);
                mVinLp[0] += (wl - mVinLp[0]) * 0.35f; mVinLp[1] += (wr - mVinLp[1]) * 0.35f;   // ~4–5 kHz roll-off
                mVinHp[0] += (mVinLp[0] - mVinHp[0]) * 0.012f; mVinHp[1] += (mVinLp[1] - mVinHp[1]) * 0.012f;  // ~90 Hz rumble cut
                wl = soft((mVinLp[0] - mVinHp[0]) * 1.6f) * 0.7f; wr = soft((mVinLp[1] - mVinHp[1]) * 1.6f) * 0.7f;
                if (rand01() < (0.0002f + 0.003f * vy.y) * (48000.f / sr)) mCrackle = (rand01() * 2.f - 1.f) * (0.15f + 0.35f * vy.y);
                wl += mCrackle; wr += mCrackle * 0.8f; mCrackle *= 0.55f;
                l += (wl - l) * vy.wet; r += (wr - r) * vy.wet;
            }

            // ---------- RESONATOR: a tuned comb that rings at the note under your finger ----------
            fade(rs, xf);
            if (rs.wet > 0.f) {
                const float in = (l + r) * 0.5f * 0.35f * rs.wet;
                const float dl = readRes(mResL, resP), dr = readRes(mResR, resP * 1.003f);
                mResDampL += (dl - mResDampL) * kResDamp; mResDampR += (dr - mResDampR) * kResDamp;
                const float yl = soft(in + mResDampL * resFb), yr = soft(in + mResDampR * resFb);
                mResL[mResW] = yl; mResR[mResW] = yr; mResW = (mResW + 1) & (kRes - 1);
                l = l * (1.f - 0.45f * rs.wet) + yl * 0.9f * rs.wet;
                r = r * (1.f - 0.45f * rs.wet) + yr * 0.9f * rs.wet;
            }

            // ---------- SLICER: song-locked gate patterns ----------
            fade(sl, xf);
            if (sl.wet > 0.f) {
                const double stepF = tick / 240.0;
                const int st = (int)((int64_t)std::floor(stepF) % 16);
                const float ph = (float)(stepF - std::floor(stepF));
                const float target = ((pat >> st) & 1u) && ph < duty ? 1.f : 0.f;
                mGateEnv += (target - mGateEnv) * 0.02f;
                const float g = 1.f - sl.wet * (1.f - mGateEnv);
                l *= g; r *= g;
            }

            // ---------- ECHO: tape echo, filtered + saturated feedback, tail after release ----------
            fade(ec, xf);
            if (ec.wet > 0.f || mEchoTail > 1e-5f) {
                mEchoTime += (ecTarget - mEchoTime) * 0.0004f;               // moving X glides the time (tape pitch-bend)
                mEchoWow += 0.7f / sr; if (mEchoWow >= 1.f) mEchoWow -= 1.f;
                const float dt = mEchoTime + 0.0003f * sr * std::sin(2.f * kPi * mEchoWow);
                const float d = readEcho(dt);
                mEchoHpS += (d - mEchoHpS) * mEchoHp;                        // keep the lows out of the loop
                mEchoLpS += ((d - mEchoHpS) - mEchoLpS) * mEchoLp;          // each repeat a little darker
                const float in = (l + r) * 0.5f * ec.wet * (ec.on ? 1.f : 0.f);
                mEchoBuf[mEchoW] = soft(in + mEchoLpS * ecFb);
                mEchoW = (mEchoW + 1) & (kEcho - 1);
                mEchoTail = std::max(std::fabs(d), mEchoTail * 0.99995f);
                if (!ec.on && ec.wet <= 0.f && mEchoTail < 1e-5f) { mEchoTail = 0.f; }
                l += d * 0.6f; r += d * 0.6f;
            }

            // ---------- REVERB: big wash (X = size, Y = tone); the tail rings on after release ----------
            fade(rv, xf);
            if (rv.wet > 0.f || mRevTail > 1e-5f) {
                const float fb = 0.70f + 0.27f * rv.x;
                const float damp = 0.55f - 0.45f * rv.y;
                const float in = (l + r) * 0.5f * (rv.on ? 1.f : 0.f) * 0.18f;
                float out[2];
                for (int c = 0; c < 2; ++c) {
                    float acc = 0.f;
                    for (int k = 0; k < kCombs; ++k) {
                        std::vector<float>& buf = mComb[c][k];
                        const size_t len = mCombLen[c][k];
                        size_t& p = mCombPos[c][k]; if (p >= len) p = 0;
                        const float y = buf[p];
                        mCombLp[c][k] = y * (1.f - damp) + mCombLp[c][k] * damp;
                        buf[p] = in + mCombLp[c][k] * fb;
                        if (++p >= len) p = 0;
                        acc += y;
                    }
                    for (int k = 0; k < kAps; ++k) {
                        std::vector<float>& buf = mAp[c][k];
                        const size_t len = mApLenS[c][k];
                        size_t& p = mApPos[c][k]; if (p >= len) p = 0;
                        const float b = buf[p];
                        buf[p] = acc + b * 0.5f;
                        acc = b - acc;
                        if (++p >= len) p = 0;
                    }
                    out[c] = acc;
                }
                mRevTail = std::max(std::fabs(out[0]), std::fabs(out[1])) + mRevTail * 0.9995f;
                if (mRevTail > 1.f) mRevTail = 1.f;
                const float duck = 1.f - 0.35f * rv.wet;
                l = l * duck + out[0] * 0.9f; r = r * duck + out[1] * 0.9f;
                if (!rv.on && mRevTail < 1e-5f) mRevTail = 0.f;
            }

            io[2 * i] = l; io[2 * i + 1] = r;
        }
    }

private:
    struct Slot { bool on = false; float x = 0.5f, y = 0.5f, wet = 0.f; uint32_t start = 0; double phase = 0, speed = 1, readPos = 0, age = 0; int mode = 0; };
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
        return std::clamp(std::min(ph / r, (len - ph) / r), 0.f, 1.f);
    }
    inline void push(float l, float r) { mRingL[mWrite & (kRing - 1)] = l; mRingR[mWrite & (kRing - 1)] = r; ++mWrite; }
    inline float lerpAt(const std::vector<float>& ring, double pos) const {
        const double f = std::floor(pos);
        const float t = (float)(pos - f);
        const uint32_t i = (uint32_t)(int64_t)f;
        return ring[i & (kRing - 1)] * (1.f - t) + ring[(i + 1) & (kRing - 1)] * t;
    }
    inline float readEcho(float d) const {
        const float pos = (float)mEchoW - d;
        const float f = std::floor(pos); const float t = pos - f;
        const int i = (int)f;
        return mEchoBuf[(size_t)(i & (kEcho - 1))] * (1.f - t) + mEchoBuf[(size_t)((i + 1) & (kEcho - 1))] * t;
    }
    inline float readRes(const std::vector<float>& b, float d) const {
        const float pos = (float)mResW - d;
        const float f = std::floor(pos); const float t = pos - f;
        const int i = (int)f;
        return b[(size_t)(i & (kRes - 1))] * (1.f - t) + b[(size_t)((i + 1) & (kRes - 1))] * t;
    }
    float rand01() { mRng ^= mRng << 13; mRng ^= mRng >> 17; mRng ^= mRng << 5; return (float)(mRng & 0xFFFFFF) / 16777216.f; }

    std::vector<float> mRingL, mRingR;
    uint32_t mWrite = 0;
    Slot mSlot[kCount];
    double mFreeTick = 0.0;
    // filter
    Svf mFl[2], mFh[2];
    bool mUseHp = false;
    float mFiltMix = 0.f;
    // lo-fi
    float mHoldL = 0, mHoldR = 0, mLfL = 0, mLfR = 0; int mHoldCount = 0;
    // vinyl
    float mWowPh = 0, mFlutPh = 0, mVinLp[2] = {0, 0}, mVinHp[2] = {0, 0}, mCrackle = 0;
    // cassette
    float mCsWow = 0, mCsFl = 0, mCsL = 0, mCsR = 0, mCsDrop = 0;
    // isolator: per channel 0-1 LP@280, 2-3 HP@280, 4-5 LP@2.6k, 6-7 HP@2.6k
    static constexpr float kButter = 0.308f;            // Svf res for k = √2 (Q 0.707)
    Svf mIso[2][8];
    float mIsoSr = -1.f;
    // slicer
    float mGateEnv = 1.f;
    // scatter
    uint32_t mScSeed = 0x5CA77E5u; int64_t mScStep = -1; uint32_t mScStart = 0; int mScAction = 0;
    // echo
    std::vector<float> mEchoBuf;
    int mEchoW = 0;
    float mEchoTime = 0.f, mEchoWow = 0.f, mEchoHpS = 0.f, mEchoLpS = 0.f, mEchoTail = 0.f, mEchoHp = 0.f, mEchoLp = 0.f, mEchoSr = -1.f;
    // resonator
    std::vector<float> mResL, mResR;
    int mResW = 0;
    float mResDampL = 0.f, mResDampR = 0.f;
    static constexpr float kResDamp = 0.6f;
    uint32_t mRng = 0xBADC0DEu;
    // reverb
    static constexpr int kCombs = 6, kAps = 3;
    static constexpr int kCombLen[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491};
    static constexpr int kApLen[kAps] = {556, 441, 341};
    std::vector<float> mComb[2][kCombs], mAp[2][kAps];
    size_t mCombPos[2][kCombs] = {}, mApPos[2][kAps] = {};
    float mCombLp[2][kCombs] = {};
    float mRevTail = 0.f;
    float mRevSr = -1.f;
    size_t mCombLen[2][kCombs] = {}, mApLenS[2][kAps] = {};
};

} // namespace fx
