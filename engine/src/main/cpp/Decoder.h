// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstring>
#include <functional>
#include <memory>
#include "SampleBuffer.h"

// Decodes WAV / AIFF directly (PcmFile.h) and everything else the platform codecs handle (MP3, FLAC,
// AAC, OGG) from a file descriptor using the NDK's AMediaExtractor + AMediaCodec, with no third-party decoders.
// Takes ownership of fd and always closes it. Runs on the worker thread only.
// Returns nullptr on failure; errOut gets a short reason.
//
// Files longer than 10 minutes load their first 10 minutes ([DecodeProgress::truncated]).
struct DecodeProgress {
    /** Called now and then with 0..1 (or -1 when the length isn't known). Return false to cancel. */
    std::function<bool(float)> report;
    bool truncated = false;       // out: only the first 10 minutes were kept
    double sourceSeconds = 0;     // out: the file's full length, when known
};
std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** errOut, DecodeProgress* progress = nullptr);

// Why a load failed, for the UI (NativeEngine.LoadError mirrors these).
enum DecodeError { kErrUnknown = 0, kErrFormat = 1, kErrTooLong = 2, kErrRate = 3, kErrDepth = 4, kErrEmpty = 5, kErrRead = 6,
                   kErrCancelled = 7, kErrMemory = 8 };
inline int decodeErrorCode(const char* why) {
    if (!why) return kErrUnknown;
    auto has = [&](const char* s) { return std::strstr(why, s) != nullptr; };
    if (has("cancelled")) return kErrCancelled;
    if (has("memory")) return kErrMemory;
    if (has("10 minutes") || has("too large")) return kErrTooLong;
    if (has("sample rate")) return kErrRate;
    if (has("bit depth") || has("channel count")) return kErrDepth;
    if (has("no audio")) return kErrEmpty;
    if (has("stat") || has("regular")) return kErrRead;
    return kErrFormat;
}
