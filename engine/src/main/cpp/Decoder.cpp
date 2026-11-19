// SPDX-License-Identifier: GPL-3.0-or-later
#include "Decoder.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cmath>
#include <cstring>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ThorboxDecoder", __VA_ARGS__)

namespace {
// android.media.AudioFormat encodings
constexpr int32_t kPcm16 = 2, kPcmFloat = 4, kPcm24Packed = 21, kPcm32 = 22;
constexpr int64_t kMaxSeconds = 10 * 60;            // Hard cap: 10 min stereo at 48k ≈ 230 MB of float
constexpr off_t   kMaxFileBytes = 1024LL * 1024 * 1024; // Refuse >1 GB inputs before touching the parser
constexpr int32_t kMinRate = 8000, kMaxRate = 384000, kMaxChannels = 32;

struct FdGuard { int fd; ~FdGuard() { if (fd >= 0) close(fd); } };
struct ExGuard { AMediaExtractor* p; ~ExGuard() { if (p) AMediaExtractor_delete(p); } };
struct CodecGuard { AMediaCodec* p; bool started = false;
    ~CodecGuard() { if (p) { if (started) AMediaCodec_stop(p); AMediaCodec_delete(p); } } };
struct FmtGuard { AMediaFormat* p; ~FmtGuard() { if (p) AMediaFormat_delete(p); } };

// Appends decoded PCM, folding >2 channels down to the first two.
void appendPcm(const uint8_t* src, size_t bytes, int32_t enc, int srcCh, int dstCh, std::vector<float>& dst) {
    if (enc != kPcm16 && enc != kPcmFloat && enc != kPcm24Packed && enc != kPcm32) return;  // unknown: skip
    const int bps = enc == kPcm16 ? 2 : enc == kPcm24Packed ? 3 : 4;
    const size_t frames = bytes / (size_t)(bps * srcCh);
    dst.reserve(dst.size() + frames * dstCh);
    for (size_t f = 0; f < frames; ++f) {
        for (int c = 0; c < dstCh; ++c) {
            const uint8_t* p = src + (f * srcCh + c) * bps;
            float v;
            switch (enc) {
                case kPcm16: { int16_t s; std::memcpy(&s, p, 2); v = s / 32768.f; break; }
                case kPcm24Packed: { int32_t s = (p[0] << 8) | (p[1] << 16) | (p[2] << 24); v = (s >> 8) / 8388608.f; break; }
                case kPcm32: { int32_t s; std::memcpy(&s, p, 4); v = (float)(s / 2147483648.0); break; }
                default: std::memcpy(&v, p, 4); if (!std::isfinite(v)) v = 0.f; break; // float: scrub NaN/Inf
            }
            v = v > 1.f ? 1.f : (v < -1.f ? -1.f : v);
            dst.push_back(v);
        }
    }
}
} // namespace

std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** errOut) {
    auto fail = [&](const char* why) -> std::unique_ptr<SampleBuffer> { if (errOut) *errOut = why; LOGE("%s", why); return nullptr; };
    FdGuard fdg{fd};
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return fail("cannot stat file");
    if (!S_ISREG(st.st_mode)) return fail("not a regular file");
    if (st.st_size > kMaxFileBytes) return fail("file too large");

    ExGuard ex{AMediaExtractor_new()};
    if (AMediaExtractor_setDataSourceFd(ex.p, fd, 0, st.st_size) != AMEDIA_OK) return fail("unsupported or corrupt file");

    int track = -1;
    const char* mime = nullptr;
    FmtGuard fmt{nullptr};
    for (size_t i = 0; i < AMediaExtractor_getTrackCount(ex.p); ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex.p, i);
        const char* m = nullptr;
        if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m && std::strncmp(m, "audio/", 6) == 0) {
            track = (int)i; fmt.p = f; mime = m; break;
        }
        AMediaFormat_delete(f);
    }
    if (track < 0) return fail("no audio track");
    AMediaExtractor_selectTrack(ex.p, track);

    int32_t sr = 0, ch = 0;
    AMediaFormat_getInt32(fmt.p, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
    AMediaFormat_getInt32(fmt.p, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
    // Ask for float output. Decoders that can't do it ignore this and report the real encoding.
    AMediaFormat_setInt32(fmt.p, AMEDIAFORMAT_KEY_PCM_ENCODING, kPcmFloat);

    CodecGuard codec{AMediaCodec_createDecoderByType(mime)};
    if (!codec.p) return fail("no decoder for this format");
    if (AMediaCodec_configure(codec.p, fmt.p, nullptr, nullptr, 0) != AMEDIA_OK) return fail("decoder configure failed");
    if (AMediaCodec_start(codec.p) != AMEDIA_OK) return fail("decoder start failed");
    codec.started = true;

    int32_t enc = kPcm16;
    {   // The configured input format may already carry the encoding.
        FmtGuard of{AMediaCodec_getOutputFormat(codec.p)};
        int32_t e; if (of.p && AMediaFormat_getInt32(of.p, AMEDIAFORMAT_KEY_PCM_ENCODING, &e)) enc = e;
    }

    auto out = std::make_unique<SampleBuffer>();
    bool inEos = false, outEos = false;
    int idleSpins = 0;
    while (!outEos) {
        if (!inEos) {
            ssize_t ib = AMediaCodec_dequeueInputBuffer(codec.p, 2000);
            if (ib >= 0) {
                size_t cap = 0;
                uint8_t* buf = AMediaCodec_getInputBuffer(codec.p, (size_t)ib, &cap);
                ssize_t n = AMediaExtractor_readSampleData(ex.p, buf, cap);
                if (n < 0) {
                    AMediaCodec_queueInputBuffer(codec.p, (size_t)ib, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    inEos = true;
                } else {
                    AMediaCodec_queueInputBuffer(codec.p, (size_t)ib, 0, (size_t)n, AMediaExtractor_getSampleTime(ex.p), 0);
                    AMediaExtractor_advance(ex.p);
                }
            }
        }
        AMediaCodecBufferInfo info{};
        ssize_t ob = AMediaCodec_dequeueOutputBuffer(codec.p, &info, 2000);
        if (ob >= 0) {
            idleSpins = 0;
            if (info.size > 0) {
                size_t sz = 0;
                const uint8_t* p = AMediaCodec_getOutputBuffer(codec.p, (size_t)ob, &sz);
                // Never trust codec-reported offsets: they must lie inside the buffer we were given.
                const bool inBounds = p && info.offset >= 0 &&
                    (size_t)info.offset + (size_t)info.size <= sz;
                const bool fmtOk = ch >= 1 && ch <= kMaxChannels;
                if (inBounds && fmtOk) appendPcm(p + info.offset, (size_t)info.size, enc, ch, ch > 2 ? 2 : ch, out->data);
            }
            if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outEos = true;
            AMediaCodec_releaseOutputBuffer(codec.p, (size_t)ob, false);
            if (sr > 0 && ch > 0 && (int64_t)out->data.size() / (ch > 2 ? 2 : ch) > kMaxSeconds * sr)
                return fail("file longer than 10 minutes");
        } else if (ob == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            FmtGuard of{AMediaCodec_getOutputFormat(codec.p)};
            AMediaFormat_getInt32(of.p, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sr);
            AMediaFormat_getInt32(of.p, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
            int32_t e; if (AMediaFormat_getInt32(of.p, AMEDIAFORMAT_KEY_PCM_ENCODING, &e)) enc = e;
        } else if (ob == AMEDIACODEC_INFO_TRY_AGAIN_LATER && inEos) {
            if (++idleSpins > 500) break;                     // ~1 s with no output after EOS: give up gracefully
        }
    }

    if (sr < kMinRate || sr > kMaxRate) return fail("unsupported sample rate");
    if (ch < 1 || ch > kMaxChannels || out->data.empty()) return fail("decoded no audio");
    if (enc != kPcm16 && enc != kPcmFloat && enc != kPcm24Packed && enc != kPcm32) return fail("unsupported PCM encoding");
    out->sampleRate = sr;
    out->channels = ch > 2 ? 2 : ch;
    out->frames = (int64_t)out->data.size() / out->channels;
    return out;
}
