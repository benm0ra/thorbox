// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for the direct WAV / AIFF reader (PcmFile.h): every bit depth and layout
// sample packs ship, plus malformed headers that must fail cleanly (run under ASan).
#include "PcmFile.h"
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); char b_[160]; std::snprintf(b_, 160, __VA_ARGS__); \
    std::printf("%-64s %s\n", b_, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)

using Bytes = std::vector<uint8_t>;
static void put(Bytes& b, const char* t) { b.insert(b.end(), t, t + 4); }
static void le(Bytes& b, uint64_t v, int n) { for (int i = 0; i < n; ++i) b.push_back((uint8_t)(v >> (8 * i))); }
static void be(Bytes& b, uint64_t v, int n) { for (int i = n - 1; i >= 0; --i) b.push_back((uint8_t)(v >> (8 * i))); }

// Test signal: ramp per channel, ch c frame f = (f - 4) / 8 + c * 0.0 (exactly representable at 8 bits).
static double val(int f, int c) { return ((f % 9) - 4) / 8.0 * (c ? -1 : 1); }

static void encode(Bytes& d, double v, int bits, bool flt, bool big, bool unsigned8) {
    Bytes s;
    if (flt && bits == 32) { float x = (float)v; uint32_t u; std::memcpy(&u, &x, 4); le(s, u, 4); }
    else if (flt) { double x = v; uint64_t u; std::memcpy(&u, &x, 8); le(s, u, 8); }
    else if (bits == 8) s.push_back(unsigned8 ? (uint8_t)(int)std::lround(v * 128 + 128 > 255 ? 255 : v * 128 + 128) : (uint8_t)(int8_t)std::lround(v * 127.999));
    else { const int64_t m = (int64_t)1 << (bits - 1); int64_t q = std::llround(v * m); if (q >= m) q = m - 1; le(s, (uint64_t)q, bits / 8); }
    if (big) for (int i = (int)s.size() - 1; i >= 0; --i) d.push_back(s[i]); else d.insert(d.end(), s.begin(), s.end());
}

static Bytes wav(int ch, int bits, bool flt, int frames, int sr = 44100, bool extensible = false, bool junk = false, uint32_t dataSize = UINT32_MAX - 1) {
    Bytes pcm;
    for (int f = 0; f < frames; ++f) for (int c = 0; c < ch; ++c) encode(pcm, val(f, c), bits, flt, false, true);
    Bytes b; put(b, "RIFF"); le(b, 0, 4); put(b, "WAVE");
    if (junk) { put(b, "JUNK"); le(b, 3, 4); b.insert(b.end(), {1, 2, 3, 0}); }   // odd size: padded
    put(b, "fmt "); le(b, extensible ? 40 : 16, 4);
    le(b, extensible ? 0xFFFE : (flt ? 3 : 1), 2); le(b, ch, 2); le(b, sr, 4);
    le(b, (uint64_t)sr * ch * bits / 8, 4); le(b, ch * bits / 8, 2); le(b, bits, 2);
    if (extensible) { le(b, 22, 2); le(b, bits, 2); le(b, 3, 4); le(b, flt ? 3 : 1, 2); for (int i = 0; i < 14; ++i) b.push_back(0); }
    put(b, "data"); le(b, dataSize == UINT32_MAX - 1 ? pcm.size() : dataSize, 4);
    b.insert(b.end(), pcm.begin(), pcm.end());
    return b;
}

static void ext80(Bytes& b, double sr) {   // positive integers only
    int e = 0; uint64_t m = (uint64_t)sr;
    while (!(m & (1ull << 63))) { m <<= 1; ++e; }
    be(b, 16383 + 63 - e, 2); be(b, m, 8);
}

static Bytes aiff(int ch, int bits, int frames, const char* comp = nullptr, int sr = 48000) {
    const bool flt = comp && (std::string(comp) == "fl32" || std::string(comp) == "fl64");
    const bool little = comp && std::string(comp) == "sowt";
    Bytes pcm;
    for (int f = 0; f < frames; ++f) for (int c = 0; c < ch; ++c) encode(pcm, val(f, c), bits, flt, !little, false);
    Bytes b; put(b, "FORM"); be(b, 0, 4); put(b, comp ? "AIFC" : "AIFF");
    put(b, "COMM"); be(b, comp ? 24 : 18, 4);
    be(b, ch, 2); be(b, frames, 4); be(b, bits, 2); ext80(b, sr);
    if (comp) { put(b, comp); be(b, 0, 2); }
    put(b, "SSND"); be(b, pcm.size() + 8, 4); be(b, 0, 4); be(b, 0, 4);
    b.insert(b.end(), pcm.begin(), pcm.end());
    return b;
}

static bool matches(const SampleBuffer& s, int ch, int frames, double tol) {
    if (s.channels != (ch > 2 ? 2 : ch) || s.frames != frames) return false;
    for (int f = 0; f < frames; ++f) for (int c = 0; c < s.channels; ++c)
        if (std::fabs(s.at(c, f) - val(f, c)) > tol) return false;
    return true;
}

static std::unique_ptr<SampleBuffer> dec(const Bytes& b, const char** err = nullptr, bool* plat = nullptr) {
    return pcm::decode(b.data(), b.size(), 600, err, plat);
}

int main() {
    struct { int bits; bool flt; double tol; } wf[] = {{8, false, 1.0 / 64}, {16, false, 1e-4}, {24, false, 1e-6}, {32, false, 1e-8}, {32, true, 1e-7}, {64, true, 1e-7}};
    for (auto w : wf) for (int ch : {1, 2, 6}) {
        auto s = dec(wav(ch, w.bits, w.flt, 300));
        CHECK(s && matches(*s, ch, 300, w.tol), "WAV %d-bit %s, %d ch", w.bits, w.flt ? "float" : "int", ch);
    }
    { auto s = dec(wav(2, 24, false, 200, 96000, true));
      CHECK(s && s->sampleRate == 96000 && matches(*s, 2, 200, 1e-6), "WAV EXTENSIBLE 24-bit 96 kHz"); }
    { auto s = dec(wav(2, 16, false, 100, 44100, false, true));
      CHECK(s && matches(*s, 2, 100, 1e-4), "WAV with an odd-sized JUNK chunk before fmt"); }
    { auto s = dec(wav(1, 16, false, 100, 44100, false, false, 0));
      CHECK(s && s->frames == 100, "WAV data size 0 (streamed writer) reads to end of file"); }
    { auto s = dec(wav(1, 16, false, 100, 44100, false, false, 0xFFFFFFFFu));
      CHECK(s && s->frames == 100, "WAV data size 0xFFFFFFFF reads to end of file"); }
    { Bytes b = wav(2, 16, false, 100); b.resize(b.size() - 41);
      auto s = dec(b);
      CHECK(s && s->frames == 89, "truncated WAV keeps the whole frames it has (%lld)", s ? (long long)s->frames : -1LL); }

    for (int bits : {8, 16, 24, 32}) for (int ch : {1, 2}) {
        auto s = dec(aiff(ch, bits, 300));
        CHECK(s && s->sampleRate == 48000 && matches(*s, ch, 300, bits == 8 ? 1.0 / 64 : 1e-4), "AIFF %d-bit, %d ch", bits, ch);
    }
    { auto s = dec(aiff(2, 16, 300, "sowt")); CHECK(s && matches(*s, 2, 300, 1e-4), "AIFF-C sowt (little-endian 16-bit)"); }
    { auto s = dec(aiff(2, 32, 300, "fl32")); CHECK(s && matches(*s, 2, 300, 1e-7), "AIFF-C fl32"); }
    { auto s = dec(aiff(1, 64, 300, "fl64")); CHECK(s && matches(*s, 1, 300, 1e-7), "AIFF-C fl64"); }
    { auto s = dec(aiff(1, 16, 50, nullptr, 44100)); CHECK(s && s->sampleRate == 44100, "AIFF 80-bit rate 44100 (%d)", s ? s->sampleRate : -1); }

    // Not ours: compressed formats go to the platform decoder.
    {   const char* e = nullptr; bool plat = false;
        Bytes mp3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0, 0xFF, 0xFB, 0x90, 0x64};
        CHECK(!dec(mp3, &e, &plat) && plat, "MP3 is left to the platform decoder"); }
    {   Bytes b = wav(2, 16, false, 10); b[20] = 2;    // format 2 = MS ADPCM
        const char* e = nullptr; bool plat = false;
        CHECK(!dec(b, &e, &plat) && plat, "ADPCM WAV is left to the platform decoder"); }
    // Ours but broken: fail cleanly, never crash.
    {   Bytes b = wav(2, 12, false, 10); const char* e = nullptr; bool plat = true;
        CHECK(!dec(b, &e, &plat) && !plat, "WAV with a bogus bit depth is refused (%s)", e ? e : "-"); }
    {   Bytes b = wav(0, 16, false, 10); const char* e = nullptr; bool plat = true;
        CHECK(!dec(b, &e, &plat) && !plat, "WAV with 0 channels is refused"); }
    {   Bytes b = wav(2, 16, false, 10, 1000); const char* e = nullptr; bool plat = true;
        CHECK(!dec(b, &e, &plat) && !plat, "WAV at 1 kHz is refused (%s)", e ? e : "-"); }
    {   Bytes b = wav(2, 16, false, 64);                 // every possible truncation point
        bool ok = true;
        for (size_t n = 0; n < b.size(); ++n) { Bytes t(b.begin(), b.begin() + n); (void)dec(t); }
        Bytes a = aiff(2, 24, 64, "sowt");
        for (size_t n = 0; n < a.size(); ++n) { Bytes t(a.begin(), a.begin() + n); (void)dec(t); }
        CHECK(ok, "every truncated header parses without overrunning (ASan)"); }
    {   Bytes b = wav(2, 16, false, 10); b[40] = 0xF0; b[41] = 0xFF; b[42] = 0xFF; b[43] = 0x7F;   // huge data size
        auto s = dec(b);
        CHECK(s && s->frames == 10, "a data size past end of file is clamped"); }

    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails ? 1 : 0;
}
