// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Direct reader for uncompressed WAV / AIFF / AIFF-C. Android's MediaExtractor has no AIFF
// support at all and drops several WAV flavours sample packs are full of (8-bit, 64-bit float,
// odd chunk layouts, size fields of 0 or 0xFFFFFFFF from streaming writers), so these are parsed
// here and only compressed formats go to the platform decoder. Pure, bounds-checked, host-tested.
#include "SampleBuffer.h"
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>

namespace pcm {

enum class Fmt { Int, Float };

struct Layout {
    int32_t sampleRate = 0;
    int channels = 0;
    int bits = 0;            // container bits per sample: 8, 16, 24, 32, 64
    Fmt fmt = Fmt::Int;
    bool bigEndian = false;
    bool unsigned8 = false;  // WAV 8-bit is unsigned, AIFF 8-bit is signed
    const uint8_t* data = nullptr;
    size_t bytes = 0;
};

namespace detail {
inline uint32_t le32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
inline uint16_t le16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
inline uint32_t be32(const uint8_t* p) { return ((uint32_t)p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }
inline uint16_t be16(const uint8_t* p) { return (uint16_t)((p[0] << 8) | p[1]); }
inline bool tag(const uint8_t* p, const char* t) { return std::memcmp(p, t, 4) == 0; }

// 80-bit IEEE extended (AIFF sample rate).
inline double ext80(const uint8_t* p) {
    const int exp = ((p[0] & 0x7F) << 8) | p[1];
    uint64_t mant = 0;
    for (int i = 0; i < 8; ++i) mant = (mant << 8) | p[2 + i];
    if (exp == 0 && mant == 0) return 0.0;
    if (exp == 0x7FFF) return 0.0;                         // inf / NaN: treat as invalid
    const double v = std::ldexp((double)mant, exp - 16383 - 63);
    return (p[0] & 0x80) ? -v : v;
}

inline bool validBits(int bits, Fmt f) {
    return f == Fmt::Int ? (bits == 8 || bits == 16 || bits == 24 || bits == 32) : (bits == 32 || bits == 64);
}

inline bool wav(const uint8_t* b, size_t n, Layout& L) {
    if (n < 12 || !tag(b + 8, "WAVE") || !(tag(b, "RIFF") || tag(b, "RIFX"))) return false;
    const bool be = tag(b, "RIFX");
    auto u32 = [&](const uint8_t* p) { return be ? be32(p) : le32(p); };
    auto u16 = [&](const uint8_t* p) { return be ? be16(p) : le16(p); };
    bool haveFmt = false;
    size_t pos = 12;
    while (pos + 8 <= n) {
        const uint8_t* c = b + pos;
        uint64_t sz = u32(c + 4);
        const size_t body = pos + 8;
        if (tag(c, "fmt ")) {
            if (sz < 16 || body + 16 > n) return false;
            int format = u16(b + body);
            L.channels = u16(b + body + 2);
            L.sampleRate = (int32_t)u32(b + body + 4);
            L.bits = u16(b + body + 14);
            if (format == 0xFFFE && sz >= 40 && body + 26 <= n) format = u16(b + body + 24);   // EXTENSIBLE: sub-format GUID
            if (format == 1) L.fmt = Fmt::Int;
            else if (format == 3) L.fmt = Fmt::Float;
            else return false;                               // ADPCM, µ-law, MP3-in-WAV…: let the platform try
            // Some writers store 24-in-32 or 20-in-24 with the *valid* bits here; the container size wins.
            const int align = u16(b + body + 12);
            if (L.channels > 0 && align > 0 && align % L.channels == 0) L.bits = align / L.channels * 8;
            L.bigEndian = be;
            L.unsigned8 = true;
            haveFmt = true;
        } else if (tag(c, "data")) {
            if (!haveFmt) return false;
            if (sz == 0 || sz == 0xFFFFFFFFu || body + sz > n) sz = n - body;   // streamed / truncated: take what's there
            L.data = b + body; L.bytes = (size_t)sz;
            return true;
        }
        if (body + sz > n) break;
        pos = body + (size_t)sz + (sz & 1);                  // chunks are word-aligned
    }
    return false;
}

inline bool aiff(const uint8_t* b, size_t n, Layout& L) {
    if (n < 12 || !tag(b, "FORM")) return false;
    const bool aifc = tag(b + 8, "AIFC");
    if (!aifc && !tag(b + 8, "AIFF")) return false;
    bool haveComm = false;
    uint32_t frames = 0;
    size_t pos = 12;
    while (pos + 8 <= n) {
        const uint8_t* c = b + pos;
        uint64_t sz = be32(c + 4);
        const size_t body = pos + 8;
        if (tag(c, "COMM")) {
            if (sz < 18 || body + 18 > n) return false;
            L.channels = be16(b + body);
            frames = be32(b + body + 2);
            L.bits = be16(b + body + 6);
            const double sr = ext80(b + body + 8);
            L.sampleRate = (sr > 0 && sr < 1e7) ? (int32_t)std::lround(sr) : 0;
            L.fmt = Fmt::Int; L.bigEndian = true; L.unsigned8 = false;
            if (aifc) {
                if (sz < 22 || body + 22 > n) return false;
                const uint8_t* ct = b + body + 18;
                if (tag(ct, "NONE") || tag(ct, "twos")) {}
                else if (tag(ct, "sowt")) L.bigEndian = false;
                else if (tag(ct, "fl32") || tag(ct, "FL32")) { L.fmt = Fmt::Float; L.bits = 32; }
                else if (tag(ct, "fl64") || tag(ct, "FL64")) { L.fmt = Fmt::Float; L.bits = 64; }
                else return false;                           // ima4, ulaw, …
            }
            L.bits = (L.bits + 7) / 8 * 8;                   // 12-bit / 20-bit samples sit in whole bytes
            haveComm = true;
        } else if (tag(c, "SSND")) {
            if (!haveComm || body + 8 > n) return false;
            const uint32_t offset = be32(b + body);
            const size_t start = body + 8 + offset;
            if (start > n) return false;
            size_t avail = n - start;
            if (sz >= 8 + offset && body + sz <= n) avail = (size_t)(sz - 8 - offset);
            if (L.channels > 0 && L.bits > 0) {
                const size_t want = (size_t)frames * L.channels * (L.bits / 8);
                if (want > 0 && want < avail) avail = want;
            }
            L.data = b + start; L.bytes = avail;
            return true;
        }
        if (body + sz > n) break;
        pos = body + (size_t)sz + (sz & 1);
    }
    return false;
}

inline float sample(const uint8_t* p, const Layout& L) {
    const int bytes = L.bits / 8;
    uint8_t q[8];
    if (L.bigEndian) { for (int i = 0; i < bytes; ++i) q[i] = p[bytes - 1 - i]; } else std::memcpy(q, p, bytes);
    float v;
    if (L.fmt == Fmt::Float) {
        if (bytes == 4) { float f; std::memcpy(&f, q, 4); v = f; }
        else { double d; std::memcpy(&d, q, 8); v = (float)d; }
        if (!std::isfinite(v)) v = 0.f;
    } else {
        switch (bytes) {
            case 1: v = L.unsigned8 ? (q[0] - 128) / 128.f : (int8_t)q[0] / 128.f; break;
            case 2: { int16_t s; std::memcpy(&s, q, 2); v = s / 32768.f; break; }
            case 3: { int32_t s = (int32_t)(((uint32_t)q[0] << 8) | ((uint32_t)q[1] << 16) | ((uint32_t)q[2] << 24)); v = (s >> 8) / 8388608.f; break; }
            default: { int32_t s; std::memcpy(&s, q, 4); v = (float)(s / 2147483648.0); break; }
        }
    }
    return v > 1.f ? 1.f : (v < -1.f ? -1.f : v);
}
} // namespace detail

constexpr int32_t kMinRate = 8000, kMaxRate = 384000, kMaxChannels = 32;

// Is this a WAV/AIFF we read ourselves? Fills [L] when it is.
inline bool probe(const uint8_t* b, size_t n, Layout& L) {
    L = Layout{};
    return detail::wav(b, n, L) || detail::aiff(b, n, L);
}

// Parses [b, b+n). Returns null with [err] set when the file is a WAV/AIFF we can't use
// (and [unsupported] = true when it's a different format the platform decoder should try).
inline std::unique_ptr<SampleBuffer> decode(const uint8_t* b, size_t n, int64_t maxSeconds,
                                            const char** err, bool* unsupported) {
    auto fail = [&](const char* why, bool tryPlatform) -> std::unique_ptr<SampleBuffer> {
        if (err) *err = why;
        if (unsupported) *unsupported = tryPlatform;
        return nullptr;
    };
    Layout L;
    if (!probe(b, n, L)) return fail("not PCM", true);
    if (L.sampleRate < kMinRate || L.sampleRate > kMaxRate) return fail("unsupported sample rate", false);
    if (L.channels < 1 || L.channels > kMaxChannels) return fail("unsupported channel count", false);
    if (!detail::validBits(L.bits, L.fmt)) return fail("unsupported bit depth", false);
    const size_t frameBytes = (size_t)L.channels * (L.bits / 8);
    int64_t frames = (int64_t)(L.bytes / frameBytes);
    if (frames <= 0) return fail("decoded no audio", false);
    if (frames > maxSeconds * L.sampleRate) return fail("file longer than 10 minutes", false);
    const int outCh = L.channels > 2 ? 2 : L.channels;
    auto out = std::make_unique<SampleBuffer>();
    out->sampleRate = L.sampleRate;
    out->channels = outCh;
    out->frames = frames;
    out->data.resize((size_t)frames * outCh);
    float* o = out->data.data();
    const int bps = L.bits / 8;
    for (int64_t f = 0; f < frames; ++f) {
        const uint8_t* p = L.data + (size_t)f * frameBytes;
        for (int c = 0; c < outCh; ++c) *o++ = detail::sample(p + c * bps, L);
    }
    return out;
}

} // namespace pcm
