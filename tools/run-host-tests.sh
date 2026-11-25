#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds and runs the engine's desktop tests under AddressSanitizer + UndefinedBehaviorSanitizer.
# Usage: tools/run-host-tests.sh   (needs g++ ≥ 11 or clang++ ≥ 14). Exits non-zero on any failure.
set -euo pipefail
cd "$(dirname "$0")/.."
CXX=${CXX:-g++}
SRC=engine/src/main/cpp
T=engine/src/test/cpp
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
FLAGS=(-std=c++20 -fno-exceptions -fno-rtti -O1 -g -Wall -Wextra -Werror -I"$SRC" -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
# -fno-exceptions/-fno-rtti match the NDK build, so code that only compiles with exceptions fails here too.

run() {   # name, then compiler args
    local name=$1; shift
    echo "== $name"
    "$CXX" "${FLAGS[@]}" "$@" -o "$OUT/$name"
    "$OUT/$name"
}
run analysis  "$SRC/Analysis.cpp" "$T/analysis_test.cpp"
run voice     -pthread "$T/voice_test.cpp"
run synth     "$T/synth_test.cpp"
run drums     "$T/drum_test.cpp"
run loudness  "$T/loudness_test.cpp"
run pcm       "$T/pcm_test.cpp"
run sequencer "$T/sequencer_test.cpp"
# The engine test compiles AudioEngine.cpp against tiny Oboe/Android stubs (test/cpp/stubs).
run fx        "$T/fx_test.cpp"
run spectrum  "$T/spectrum_test.cpp"
run stretch   "$T/stretch_test.cpp"
run duck      "$T/duck_test.cpp"
run eq        "$T/eq_test.cpp"
run master    "$T/master_test.cpp"
run export    "$T/export_test.cpp"
run engine    -pthread -Wno-unused-parameter -I"$T/stubs" "$SRC/AudioEngine.cpp" "$SRC/AudioRender.cpp" "$SRC/AudioExport.cpp" "$SRC/Analysis.cpp" "$T/engine_test.cpp"
echo "All host tests passed (ASan + UBSan)."
