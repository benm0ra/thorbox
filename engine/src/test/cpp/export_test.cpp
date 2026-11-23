// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for EXPORT's file writers: WAV (16 / 24) and our FLAC encoder, decoded back here (a
// small decoder for exactly the subset we write, checking every CRC) and compared sample for sample.
#include "Export.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <vector>
using namespace exportfile;
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[180]; std::snprintf(b_, 180, __VA_ARGS__); \
    std::printf("%-70s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)

static std::vector<uint8_t> slurp(const std::string& p) {
    std::vector<uint8_t> v; FILE* f = std::fopen(p.c_str(), "rb"); if (!f) return v;
    uint8_t buf[65536]; size_t n; while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) v.insert(v.end(), buf, buf + n);
    std::fclose(f); return v;
}
static std::string tmp(const char* ext) { char p[] = "/tmp/exptestXXXXXX"; int fd = mkstemp(p); ::close(fd); std::string s = p; s += ext; std::rename(p, s.c_str()); return s; }

// ---- a FLAC decoder for our subset: CONSTANT / VERBATIM / FIXED + Rice, independent stereo ----
struct BitReader {
    const uint8_t* d; size_t n, pos = 0;
    uint64_t get(int bits) { uint64_t v = 0; for (int i = 0; i < bits; ++i) { v = (v << 1) | ((d[pos >> 3] >> (7 - (pos & 7))) & 1); ++pos; } return v; }
    int64_t sget(int bits) { const uint64_t v = get(bits); return (v & (1ull << (bits - 1))) ? (int64_t)v - (1ll << bits) : (int64_t)v; }
    uint32_t unary() { uint32_t q = 0; while (get(1) == 0) ++q; return q; }
    void align() { pos = (pos + 7) & ~(size_t)7; }
};
static bool decodeFlac(const std::vector<uint8_t>& f, std::vector<int32_t>& L, std::vector<int32_t>& R, int& rate, int& bps, uint64_t& total, int& badCrc) {
    if (f.size() < 42 || std::memcmp(f.data(), "fLaC", 4) != 0) return false;
    BitReader si{f.data() + 8, 34};
    si.get(16); si.get(16); si.get(24); si.get(24);
    rate = (int)si.get(20); const int ch = (int)si.get(3) + 1; bps = (int)si.get(5) + 1; total = si.get(36);
    if (ch != 2) return false;
    size_t at = 42; badCrc = 0;
    while (at + 2 < f.size()) {
        BitReader b{f.data() + at, f.size() - at};
        if (b.get(14) != 0x3FFE) return false;
        b.get(2); const int bsCode = (int)b.get(4); b.get(4); const int chCode = (int)b.get(4); b.get(4);
        if (bsCode != 7 || chCode != 1) return false;
        uint32_t lead = (uint32_t)b.get(8); int extra = 0; while (lead & (0x80 >> extra)) ++extra; if (extra) --extra;
        for (int i = 0; i < extra; ++i) b.get(8);
        const int n = (int)b.get(16) + 1;
        const size_t hdrBytes = b.pos / 8;
        if ((uint8_t)b.get(8) != crc8(f.data() + at, hdrBytes)) ++badCrc;
        for (int c = 0; c < 2; ++c) {
            std::vector<int32_t>& out = c == 0 ? L : R;
            const size_t base = out.size(); out.resize(base + (size_t)n);
            int32_t* x = out.data() + base;
            b.get(1); const int type = (int)b.get(6); b.get(1);
            if (type == 0) { const int32_t v = (int32_t)b.sget(bps); for (int i = 0; i < n; ++i) x[i] = v; }
            else if (type == 1) { for (int i = 0; i < n; ++i) x[i] = (int32_t)b.sget(bps); }
            else if ((type & 0x38) == 0x08) {
                const int order = type & 7;
                for (int i = 0; i < order; ++i) x[i] = (int32_t)b.sget(bps);
                const int kb = b.get(2) == 1 ? 5 : 4; const int po = (int)b.get(4); const int part = n >> po;
                std::vector<int32_t> r((size_t)n);
                for (int p = 0; p < (1 << po); ++p) {
                    const int k = (int)b.get(kb); const int a = p == 0 ? order : p * part, e = (p + 1) * part;
                    for (int i = a; i < e; ++i) { const uint32_t u = (b.unary() << k) | (uint32_t)(k ? b.get(k) : 0); r[(size_t)i] = (int32_t)((u >> 1) ^ (0u - (u & 1))); }
                }
                for (int i = order; i < n; ++i) {
                    int64_t p = 0;
                    switch (order) { case 1: p = x[i-1]; break; case 2: p = 2LL*x[i-1]-x[i-2]; break; case 3: p = 3LL*x[i-1]-3LL*x[i-2]+x[i-3]; break; case 4: p = 4LL*x[i-1]-6LL*x[i-2]+4LL*x[i-3]-x[i-4]; break; default: break; }
                    x[i] = (int32_t)(p + r[(size_t)i]);
                }
            } else return false;
        }
        b.align();
        const size_t body = b.pos / 8;
        if ((uint16_t)b.get(16) != crc16(f.data() + at, body)) ++badCrc;
        at += body + 2;
    }
    return true;
}

int main() {
    const int SR = 48000, N = 48000 * 3 + 1234;                       // 3 s and a short last block
    std::vector<float> lr((size_t)N * 2);
    for (int i = 0; i < N; ++i) {
        const float t = (float)i / SR;
        // music-ish: a chord, a kick-like thump, noise; then 0.5 s of digital silence
        float s = 0.25f * std::sin(2 * 3.14159f * 110 * t) + 0.15f * std::sin(2 * 3.14159f * 277 * t) + 0.05f * std::sin(2 * 3.14159f * 3300 * t);
        s += 0.4f * std::exp(-std::fmod(t, 0.5f) * 18) * std::sin(2 * 3.14159f * 55 * std::fmod(t, 0.5f));
        s += 0.02f * ((float)((i * 1103515245u + 12345u) >> 16 & 0x7FFF) / 32768.f - 0.5f);
        if (t > 2.5f) s = 0.f;
        lr[2 * (size_t)i] = s; lr[2 * (size_t)i + 1] = 0.8f * s + (i % 2 ? 0.001f : -0.001f) * (t <= 2.5f);
    }
    for (int bits : {24, 16}) {
        // FLAC
        const std::string fp = tmp(".flac");
        {   FlacWriter w(::open(fp.c_str(), O_RDWR), SR, bits);
            for (int off = 0; off < N; off += 1000) w.write(lr.data() + 2 * off, std::min(1000, N - off));
            CHECK(w.close() && w.frames() == N, "FLAC %d-bit: wrote %d frames", bits, N); }
        const auto f = slurp(fp);
        std::vector<int32_t> L, R; int rate = 0, bps = 0, bad = 0; uint64_t total = 0;
        const bool ok = decodeFlac(f, L, R, rate, bps, total, bad);
        CHECK(ok && rate == SR && bps == bits && total == (uint64_t)N && L.size() == (size_t)N && bad == 0,
              "FLAC %d-bit decodes: %d Hz, %d-bit, %llu frames, CRC errors %d", bits, rate, bps, (unsigned long long)total, bad);
        // the same integers our writer saw (24-bit has no dither: recompute and compare exactly)
        if (bits == 24) {
            Quantizer q(24); bool same = true;
            for (int i = 0; i < N && same; ++i) same = L[(size_t)i] == q(lr[2 * (size_t)i]) && R[(size_t)i] == q(lr[2 * (size_t)i + 1]);
            CHECK(same, "FLAC 24-bit: every sample comes back exactly");
        } else {
            double err = 0; for (int i = 0; i < N; ++i) err = std::max(err, std::fabs(L[(size_t)i] / 32767.0 - lr[2 * (size_t)i]));
            CHECK(err < 3.0 / 32767, "FLAC 16-bit: within dither of the source (max %.2f LSB)", err * 32767);
        }
        const size_t raw = (size_t)N * 2 * (bits / 8);
        CHECK(f.size() < raw * (bits == 24 ? 0.72 : 0.65), "FLAC %d-bit compresses (%.0f %% of raw)", bits, 100.0 * f.size() / raw);
        // WAV
        const std::string wp = tmp(".wav");
        {   WavWriter w(::open(wp.c_str(), O_RDWR), SR, bits);
            w.write(lr.data(), N); CHECK(w.close(), "WAV %d-bit closes", bits); }
        const auto wv = slurp(wp);
        const uint32_t dataBytes = wv.size() >= 44 ? (uint32_t)(wv[40] | wv[41] << 8 | wv[42] << 16 | (uint32_t)wv[43] << 24) : 0;
        CHECK(wv.size() == 44 + raw && dataBytes == raw && std::memcmp(wv.data(), "RIFF", 4) == 0 && wv[34] == bits,
              "WAV %d-bit: header patched (%u data bytes)", bits, dataBytes);
        if (bits == 24) {
            Quantizer q(24); bool same = true;
            for (int i = 0; i < 2 * N && same; ++i) {
                const uint8_t* p = wv.data() + 44 + 3 * (size_t)i;
                int32_t v = p[0] | p[1] << 8 | p[2] << 16; if (v & 0x800000) v -= 0x1000000;
                same = v == q(lr[(size_t)i]);
            }
            CHECK(same, "WAV 24-bit: every sample exact");
        }
        if (std::getenv("KEEP")) std::printf("kept %s %s\n", fp.c_str(), wp.c_str()); else { std::remove(fp.c_str()); std::remove(wp.c_str()); }
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails ? 1 : 0;
}
