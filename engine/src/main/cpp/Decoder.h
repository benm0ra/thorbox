// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstring>
#include <memory>
#include "SampleBuffer.h"

// Decodes WAV / AIFF directly (PcmFile.h) and everything else the platform codecs handle (MP3, FLAC,
// AAC, OGG) from a file descriptor using the NDK's AMediaExtractor + AMediaCodec, with no third-party decoders.
// Takes ownership of fd and always closes it. Runs on the worker thread only.
// Returns nullptr on failure; errOut gets a short reason.
std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** errOut);

// Why a load failed, for the UI (NativeEngine.LoadError mirrors these).
enum DecodeError { kErrUnknown = 0, kErrFormat = 1, kErrTooLong = 2, kErrRate = 3, kErrDepth = 4, kErrEmpty = 5, kErrRead = 6 };
inline int decodeErrorCode(const char* why) {
    if (!why) return kErrUnknown;
    auto has = [&](const char* s) { return std::strstr(why, s) != nullptr; };
    if (has("10 minutes") || has("too large")) return kErrTooLong;
    if (has("sample rate")) return kErrRate;
    if (has("bit depth") || has("channel count")) return kErrDepth;
    if (has("no audio")) return kErrEmpty;
    if (has("stat") || has("regular")) return kErrRead;
    return kErrFormat;
}
