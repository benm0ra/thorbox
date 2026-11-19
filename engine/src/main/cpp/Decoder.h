// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <memory>
#include "SampleBuffer.h"

// Decodes WAV / MP3 (and anything else the platform codecs handle: FLAC, AAC, OGG) from a file
// descriptor using the NDK's AMediaExtractor + AMediaCodec, with no third-party decoders.
// Takes ownership of fd and always closes it. Runs on the worker thread only.
// Returns nullptr on failure; errOut gets a short reason.
std::unique_ptr<SampleBuffer> decodeAudioFd(int fd, const char** errOut);
