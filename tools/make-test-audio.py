#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes short test samples in every uncompressed layout the sampler reads itself (PcmFile.h):
WAV 8/16/24-bit + 32-bit float, stereo and mono, and AIFF / AIFF-C (sowt). Used by the CI smoke test
to load each one through the app's real load path. Usage: make-test-audio.py OUT_DIR"""
import math
import os
import struct
import sys

RATE = 44100
SECONDS = 1.5


def tone(n, ch):
    # a decaying 110 Hz hit every 0.25 s, so the auto-chopper finds slices
    out = []
    for i in range(n):
        t = i / RATE
        ph = t % 0.25
        v = 0.8 * math.exp(-ph * 18) * math.sin(2 * math.pi * 110 * ph)
        out.append([v if c == 0 else -v for c in range(ch)])
    return out


def pcm_bytes(frames, bits, flt, big):
    b = bytearray()
    for fr in frames:
        for v in fr:
            if flt:
                b += struct.pack('>f' if big else '<f', v)
            elif bits == 8:
                b.append(int(round(v * 127)) + 128 if not big else (int(round(v * 127)) & 0xFF))
            else:
                q = int(round(v * (2 ** (bits - 1) - 1)))
                raw = q.to_bytes(bits // 8, 'little', signed=True)
                b += raw[::-1] if big else raw
    return bytes(b)


def wav(path, bits, ch, flt=False):
    frames = tone(int(RATE * SECONDS), ch)
    data = pcm_bytes(frames, bits, flt, False)
    fmt = struct.pack('<HHIIHH', 3 if flt else 1, ch, RATE, RATE * ch * bits // 8, ch * bits // 8, bits)
    body = b'WAVE' + b'fmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', len(data)) + data
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)


def ext80(x):
    e = 0
    m = int(x)
    while m < (1 << 63):
        m <<= 1
        e += 1
    return struct.pack('>HQ', 16383 + 63 - e, m)


def aiff(path, bits, ch, sowt=False):
    frames = tone(int(RATE * SECONDS), ch)
    data = pcm_bytes(frames, bits, False, not sowt)
    comm = struct.pack('>hIh', ch, len(frames), bits) + ext80(RATE)
    if sowt:
        comm += b'sowt' + b'\x00\x00'
    ssnd = struct.pack('>II', 0, 0) + data
    body = (b'AIFC' if sowt else b'AIFF') + b'COMM' + struct.pack('>I', len(comm)) + comm \
        + b'SSND' + struct.pack('>I', len(ssnd)) + ssnd
    with open(path, 'wb') as f:
        f.write(b'FORM' + struct.pack('>I', len(body)) + body)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    wav(os.path.join(out, 'kick-16.wav'), 16, 2)
    wav(os.path.join(out, 'kick-8.wav'), 8, 1)
    wav(os.path.join(out, 'kick-24.wav'), 24, 2)
    wav(os.path.join(out, 'kick-float.wav'), 32, 2, flt=True)
    aiff(os.path.join(out, 'kick.aif'), 16, 2)
    aiff(os.path.join(out, 'kick-sowt.aifc'), 16, 2, sowt=True)


if __name__ == '__main__':
    main()
