// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Audio file writers for EXPORT (bouncing songs and stems): WAV (16 / 24-bit PCM) and FLAC.
//
// Both write to a file descriptor the app opened (a MediaStore file), stream as they go, and patch
// their headers on close (the lengths aren't known until the song has rendered). Not real-time:
// used from the export loop, never from the audio callback.
//
// FLAC is our own small encoder (so every device writes the same bytes, and it's host-testable):
// fixed blocks of 4096, per channel the best of CONSTANT (silence), FIXED predictors order 0…4 with
// partitioned Rice / Rice2 coding, or VERBATIM; frame / header CRCs; STREAMINFO with the total sample count
// patched at close (MD5 left 0 = "not computed", which the format allows).
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <unistd.h>
#include <vector>

namespace exportfile {

enum class Format : int32_t { Wav = 0, Flac = 1 };

class Writer {
public:
    virtual ~Writer() = default;
    /** Interleaved stereo float, ±1 full scale (clipped on the way to integers). */
    virtual bool write(const float* lr, int frames) = 0;
    /** Patch the header, close the fd. */
    virtual bool close() = 0;
    int64_t frames() const { return mFrames; }
protected:
    int64_t mFrames = 0;
};

inline bool writeAll(int fd, const void* data, size_t n) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    while (n > 0) { const ssize_t w = ::write(fd, p, n); if (w <= 0) return false; p += w; n -= (size_t)w; }
    return true;
}
inline bool pwriteAll(int fd, const void* data, size_t n, off_t at) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    while (n > 0) { const ssize_t w = ::pwrite(fd, p, n, at); if (w <= 0) return false; p += w; n -= (size_t)w; at += w; }
    return true;
}

/** Float → integer PCM: 24-bit straight, 16-bit with TPDF dither (deterministic). */
class Quantizer {
public:
    explicit Quantizer(int bits) : mBits(bits) {}
    int32_t operator()(float x) {
        if (mBits >= 24) return (int32_t)std::lrint(std::clamp(x, -1.f, 1.f) * 8388607.f);
        const float d = (rnd() - rnd()) / 32768.f;                     // ±1 LSB triangular
        return (int32_t)std::clamp<long>(std::lrint((double)std::clamp(x + d, -1.f, 1.f) * 32767.0), -32768L, 32767L);
    }
private:
    int mBits;
    uint32_t mSeed = 0x2545F491u;
    float rnd() { mSeed ^= mSeed << 13; mSeed ^= mSeed >> 17; mSeed ^= mSeed << 5; return (float)(mSeed & 0xFFFFFF) / 16777216.f; }
};

// ------------------------------------------------------------------------------------------- WAV

class WavWriter final : public Writer {
public:
    WavWriter(int fd, int sampleRate, int bits) : mFd(fd), mRate(sampleRate), mBits(bits == 16 ? 16 : 24), mQ(mBits) {
        uint8_t h[44] = {};
        header(h, 0);
        mOk = writeAll(mFd, h, sizeof h);
    }
    ~WavWriter() override { if (mFd >= 0) close(); }
    bool write(const float* lr, int n) override {
        if (!mOk) return false;
        const int bps = mBits / 8;
        mBuf.resize((size_t)n * 2 * bps);
        uint8_t* o = mBuf.data();
        for (int i = 0; i < 2 * n; ++i) {
            const int32_t v = mQ(lr[i]);
            *o++ = (uint8_t)(v & 0xFF); *o++ = (uint8_t)((v >> 8) & 0xFF);
            if (bps == 3) *o++ = (uint8_t)((v >> 16) & 0xFF);
        }
        mFrames += n;
        return mOk = writeAll(mFd, mBuf.data(), mBuf.size());
    }
    bool close() override {
        if (mFd < 0) return mOk;
        uint8_t h[44] = {};
        header(h, (uint32_t)std::min<int64_t>(mFrames * 2 * (mBits / 8), 0xFFFFFFF0LL));
        const bool ok = mOk && pwriteAll(mFd, h, sizeof h, 0);
        ::close(mFd); mFd = -1;
        return ok;
    }
private:
    void header(uint8_t* h, uint32_t dataBytes) const {
        auto u32 = [&](int at, uint32_t v) { for (int i = 0; i < 4; ++i) h[at + i] = (uint8_t)(v >> (8 * i)); };
        auto u16 = [&](int at, uint16_t v) { h[at] = (uint8_t)v; h[at + 1] = (uint8_t)(v >> 8); };
        std::memcpy(h, "RIFF", 4); u32(4, 36 + dataBytes); std::memcpy(h + 8, "WAVEfmt ", 8);
        u32(16, 16); u16(20, 1); u16(22, 2); u32(24, (uint32_t)mRate);
        u32(28, (uint32_t)mRate * 2 * (mBits / 8)); u16(32, (uint16_t)(2 * (mBits / 8))); u16(34, (uint16_t)mBits);
        std::memcpy(h + 36, "data", 4); u32(40, dataBytes);
    }
    int mFd; int mRate; int mBits; Quantizer mQ; bool mOk = false;
    std::vector<uint8_t> mBuf;
};

// ------------------------------------------------------------------------------------------ FLAC

class BitWriter {
public:
    void put(uint64_t v, int bits) {            // bits <= 57
        mAcc = (mAcc << bits) | (v & ((bits == 64) ? ~0ull : ((1ull << bits) - 1)));
        mN += bits;
        while (mN >= 8) { mN -= 8; mOut.push_back((uint8_t)(mAcc >> mN)); }
    }
    void putSigned(int64_t v, int bits) { put((uint64_t)v & ((1ull << bits) - 1), bits); }
    void unary(uint32_t q) { while (q >= 32) { put(0, 32); q -= 32; } put(1, (int)q + 1); }
    void align() { if (mN > 0) put(0, 8 - mN); }
    std::vector<uint8_t>& bytes() { return mOut; }
    void clear() { mOut.clear(); mAcc = 0; mN = 0; }
private:
    uint64_t mAcc = 0; int mN = 0;
    std::vector<uint8_t> mOut;
};

inline uint8_t crc8(const uint8_t* d, size_t n) {
    uint8_t c = 0;
    for (size_t i = 0; i < n; ++i) { c ^= d[i]; for (int b = 0; b < 8; ++b) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1); }
    return c;
}
inline uint16_t crc16(const uint8_t* d, size_t n) {
    uint16_t c = 0;
    for (size_t i = 0; i < n; ++i) { c ^= (uint16_t)(d[i] << 8); for (int b = 0; b < 8; ++b) c = (c & 0x8000) ? (uint16_t)((c << 1) ^ 0x8005) : (uint16_t)(c << 1); }
    return c;
}

class FlacWriter final : public Writer {
public:
    static constexpr int kBlock = 4096;
    FlacWriter(int fd, int sampleRate, int bits) : mFd(fd), mRate(sampleRate), mBits(bits == 16 ? 16 : 24), mQ(mBits) {
        mCh[0].reserve(kBlock); mCh[1].reserve(kBlock);
        uint8_t h[42];
        streaminfo(h, 0);
        mOk = writeAll(mFd, h, sizeof h);
    }
    ~FlacWriter() override { if (mFd >= 0) close(); }
    bool write(const float* lr, int n) override {
        if (!mOk) return false;
        for (int i = 0; i < n; ++i) {
            mCh[0].push_back(mQ(lr[2 * i])); mCh[1].push_back(mQ(lr[2 * i + 1]));
            if ((int)mCh[0].size() == kBlock && !(mOk = frame())) return false;
        }
        mFrames += n;
        return true;
    }
    bool close() override {
        if (mFd < 0) return mOk;
        if (mOk && !mCh[0].empty()) mOk = frame();
        uint8_t h[42];
        streaminfo(h, (uint64_t)mFrames);
        const bool ok = mOk && pwriteAll(mFd, h, sizeof h, 0);
        ::close(mFd); mFd = -1;
        return ok;
    }

private:
    // "fLaC" + the STREAMINFO block (last metadata block)
    void streaminfo(uint8_t* h, uint64_t total) const {
        std::memcpy(h, "fLaC", 4);
        h[4] = 0x80; h[5] = 0; h[6] = 0; h[7] = 34;                    // last block, STREAMINFO, 34 bytes
        BitWriter b;
        b.put(kBlock, 16); b.put(kBlock, 16);                          // min / max block size (the last may be short: allowed)
        b.put(0, 24); b.put(0, 24);                                    // min / max frame size: unknown
        b.put((uint64_t)mRate, 20); b.put(2 - 1, 3); b.put((uint64_t)(mBits - 1), 5);
        b.put(total & 0xFFFFFFFFFULL, 36);
        for (int i = 0; i < 16; ++i) b.put(0, 8);                      // MD5: not computed
        std::memcpy(h + 8, b.bytes().data(), 34);
    }

    bool frame() {
        const int n = (int)mCh[0].size();
        BitWriter& b = mBw; b.clear();
        b.put(0x3FFE, 14); b.put(0, 1); b.put(0, 1);                   // sync, reserved, fixed blocksize
        b.put(0x7, 4);                                                 // block size: 16-bit (n-1) at the end of the header
        b.put(0x0, 4);                                                 // sample rate: from STREAMINFO
        b.put(0x1, 4);                                                 // 2 channels, independent
        b.put(0x0, 3); b.put(0, 1);                                    // sample size: from STREAMINFO, reserved
        utf8(b, (uint32_t)mFrameNo);
        b.put((uint64_t)(n - 1), 16);
        b.put(crc8(b.bytes().data(), b.bytes().size()), 8);
        for (int c = 0; c < 2; ++c) subframe(b, mCh[c].data(), n);
        b.align();
        const uint16_t crc = crc16(b.bytes().data(), b.bytes().size());
        b.put(crc, 16);
        ++mFrameNo;
        mCh[0].clear(); mCh[1].clear();
        return writeAll(mFd, b.bytes().data(), b.bytes().size());
    }

    static void utf8(BitWriter& b, uint32_t v) {
        if (v < 0x80) { b.put(v, 8); return; }
        int extra = v < 0x800 ? 1 : v < 0x10000 ? 2 : v < 0x200000 ? 3 : v < 0x4000000 ? 4 : 5;
        static const uint8_t lead[] = {0, 0xC0, 0xE0, 0xF0, 0xF8, 0xFC};
        b.put(lead[extra] | (v >> (6 * extra)), 8);
        for (int i = extra - 1; i >= 0; --i) b.put(0x80 | ((v >> (6 * i)) & 0x3F), 8);
    }

    // residuals of fixed predictor [order] for samples [order, n)
    static void residual(const int32_t* x, int n, int order, int32_t* r) {
        for (int i = order; i < n; ++i) {
            int64_t p = 0;
            switch (order) {
                case 0: p = 0; break;
                case 1: p = x[i - 1]; break;
                case 2: p = 2LL * x[i - 1] - x[i - 2]; break;
                case 3: p = 3LL * x[i - 1] - 3LL * x[i - 2] + x[i - 3]; break;
                default: p = 4LL * x[i - 1] - 6LL * x[i - 2] + 4LL * x[i - 3] - x[i - 4]; break;
            }
            r[i] = (int32_t)(x[i] - p);
        }
    }
    static uint32_t zz(int32_t v) { return ((uint32_t)v << 1) ^ (uint32_t)(v >> 31); }
    // bits to Rice-code [a, b) with parameter k
    static uint64_t riceBits(const int32_t* r, int a, int b, int k) {
        uint64_t s = 0;
        for (int i = a; i < b; ++i) s += (zz(r[i]) >> k) + 1 + (uint64_t)k;
        return s;
    }
    // RICE (4-bit k <= 14) is plenty for 16-bit; 24-bit residuals want bigger k, so those use RICE2
    int kMax() const { return mBits > 16 ? 30 : 14; }
    int kBits() const { return mBits > 16 ? 5 : 4; }
    int bestK(const int32_t* r, int a, int b, uint64_t* bits) const {
        // start from the mean, check the neighbours
        uint64_t sum = 0; for (int i = a; i < b; ++i) sum += zz(r[i]);
        const int cnt = std::max(1, b - a);
        int k0 = 0; while (k0 < kMax() && ((uint64_t)cnt << (k0 + 1)) < sum) ++k0;
        int best = k0; uint64_t bb = riceBits(r, a, b, k0);
        for (int k : {k0 - 1, k0 + 1}) if (k >= 0 && k <= kMax()) { const uint64_t v = riceBits(r, a, b, k); if (v < bb) { bb = v; best = k; } }
        *bits = bb;
        return best;
    }

    void subframe(BitWriter& b, const int32_t* x, int n) {
        bool constant = true;
        for (int i = 1; i < n && constant; ++i) constant = x[i] == x[0];
        if (constant) { b.put(0, 1); b.put(0x00, 6); b.put(0, 1); b.putSigned(x[0], mBits); return; }
        mRes.resize((size_t)n);
        // pick the order + partitioning with the fewest bits
        int bestOrder = -1, bestPo = 0; uint64_t bestBits = (uint64_t)n * mBits;          // = VERBATIM
        for (int order = 0; order <= std::min(4, n - 1); ++order) {
            residual(x, n, order, mRes.data());
            for (int po = 0; po <= 6; ++po) {
                if ((n & ((1 << po) - 1)) != 0 || (n >> po) <= order) break;
                uint64_t bits = (uint64_t)order * mBits + 2 + 4;
                const int part = n >> po;
                for (int p = 0; p < (1 << po); ++p) {
                    uint64_t pb; bestK(mRes.data(), p == 0 ? order : p * part, (p + 1) * part, &pb);
                    bits += (uint64_t)kBits() + pb;
                }
                if (bits < bestBits) { bestBits = bits; bestOrder = order; bestPo = po; }
            }
        }
        if (bestOrder < 0) {                                           // VERBATIM
            b.put(0, 1); b.put(0x01, 6); b.put(0, 1);
            for (int i = 0; i < n; ++i) b.putSigned(x[i], mBits);
            return;
        }
        b.put(0, 1); b.put((uint64_t)(0x08 | bestOrder), 6); b.put(0, 1); // FIXED, order
        for (int i = 0; i < bestOrder; ++i) b.putSigned(x[i], mBits);
        residual(x, n, bestOrder, mRes.data());
        b.put(mBits > 16 ? 1 : 0, 2); b.put((uint64_t)bestPo, 4);      // RICE / RICE2, partition order
        const int part = n >> bestPo;
        for (int p = 0; p < (1 << bestPo); ++p) {
            const int a = p == 0 ? bestOrder : p * part, e = (p + 1) * part;
            uint64_t pb; const int k = bestK(mRes.data(), a, e, &pb);
            b.put((uint64_t)k, kBits());
            for (int i = a; i < e; ++i) { const uint32_t u = zz(mRes[(size_t)i]); b.unary(u >> k); if (k) b.put(u & ((1u << k) - 1), k); }
        }
    }

    int mFd; int mRate; int mBits; Quantizer mQ; bool mOk = false;
    std::vector<int32_t> mCh[2];
    std::vector<int32_t> mRes;
    BitWriter mBw;
    uint32_t mFrameNo = 0;
};

}  // namespace exportfile
