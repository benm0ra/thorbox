#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Worst-case audio-callback benchmark on the desktop (best of N runs; desktop numbers are noisy).
# Usage: tools/bench.sh [runs]   — prints µs per 192-frame callback and the share of the real-time budget.
set -euo pipefail
cd "$(dirname "$0")/.."
T=engine/src/test/cpp; SRC=engine/src/main/cpp; OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
${CXX:-g++} -std=c++20 -O3 -DNDEBUG -ffast-math -pthread -Wno-unused-parameter -I"$SRC" -I"$T/stubs" \
    "$SRC/AudioEngine.cpp" "$SRC/Analysis.cpp" "$T/bench.cpp" -o "$OUT/bench"
RUNS=${1:-5}
for i in $(seq "$RUNS"); do "$OUT/bench"; done | awk '/µs/ { k=$0; sub(/ +[0-9.]+ µs.*/, "", k); v=$(NF-3); if (!(k in m) || v < m[k]) { m[k]=v; l[k]=$0 } } END { for (k in l) print l[k] }' | sort
