// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test for key/BPM detection on synthetic material.
// Build & run:  g++ -std=c++20 -O2 -I../../main/cpp ../../main/cpp/Analysis.cpp analysis_test.cpp -o at && ./at
#include "Analysis.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
#include <cstdlib>

static const char* kNames[] = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
static constexpr double PI = 3.141592653589793;
static int fails = 0;

static double hz(int midi) { return 440.0 * std::pow(2.0, (midi - 69) / 12.0); }

// Sawtooth-ish tone (5 harmonics) with a soft envelope.
static void addNote(std::vector<float>& x, int sr, double t0, double dur, int midi, float amp) {
    const int a = (int)(t0 * sr), n = (int)(dur * sr);
    for (int i = 0; i < n && a + i < (int)x.size(); ++i) {
        double t = (double)i / sr, env = std::min(1.0, t * 50) * std::exp(-1.5 * t);
        double s = 0;
        for (int h = 1; h <= 5; ++h) s += std::sin(2 * PI * hz(midi) * h * t) / h;
        x[a + i] += (float)(amp * env * s);
    }
}

static void addClick(std::vector<float>& x, int sr, double t0, float amp, std::mt19937& rng) {
    std::uniform_real_distribution<float> u(-1, 1);
    const int a = (int)(t0 * sr);
    for (int i = 0; i < sr / 50 && a + i < (int)x.size(); ++i)
        x[a + i] += amp * u(rng) * std::exp(-(float)i / (sr * 0.004f));
}

static void check(const char* name, const std::vector<float>& x, int sr, int expRoot, int expMode, float expBpm) {
    auto r = analyzeAudio(x.data(), (int64_t)x.size(), 1, sr);
    bool keyOk = (r.keyRoot == expRoot) && (expRoot < 0 || r.keyMode == expMode);
    // Octave errors (87 vs 174) are inherently ambiguous; the UI offers x2 / ÷2. Accept them, but flag them.
    bool exact = expBpm == 0 ? r.bpm == 0 : std::fabs(r.bpm - expBpm) < 0.6f;
    bool octave = expBpm != 0 && (std::fabs(r.bpm * 2 - expBpm) < 1.2f || std::fabs(r.bpm / 2 - expBpm) < 0.6f);
    bool bpmOk = exact || octave;
    std::printf("%-28s key=%s%s (r=%.2f)  bpm=%.1f (c=%.2f)   %s\n", name,
                r.keyRoot >= 0 ? kNames[r.keyRoot] : "—",
                r.keyRoot >= 0 ? (r.keyMode ? " Min" : " Maj") : "", r.keyConfidence, r.bpm, r.bpmConfidence,
                keyOk && bpmOk ? (exact ? "PASS" : "PASS (tempo octave)") : "FAIL");
    if (!(keyOk && bpmOk)) ++fails;
}

int main() {
    const int sr = 48000;
    std::mt19937 rng(42);

    { // C major: I–IV–V–I, 1 chord per bar at 120 BPM, 4 bars, with a kick on every beat
        std::vector<float> x(sr * 8, 0.f);
        int chords[4][3] = {{60,64,67},{65,69,72},{67,71,74},{60,64,67}};
        for (int b = 0; b < 4; ++b) for (int n : chords[b]) addNote(x, sr, b * 2.0, 2.0, n, 0.15f);
        for (int b = 0; b < 16; ++b) addClick(x, sr, b * 0.5, 0.6f, rng);
        check("C major prog @120", x, sr, 0, 0, 120.f);
    }
    { // A minor: i–iv–V–i (E major has G#), 93 BPM, exactly 4 bars long
        const double beat = 60.0 / 93.0;
        std::vector<float> x((size_t)(sr * beat * 16), 0.f);
        int chords[4][3] = {{57,60,64},{62,65,69},{64,68,71},{57,60,64}};
        for (int b = 0; b < 4; ++b) for (int n : chords[b]) addNote(x, sr, b * 4 * beat, 4 * beat, n, 0.15f);
        for (int b = 0; b < 16; ++b) addClick(x, sr, b * beat, 0.6f, rng);
        for (int b = 0; b < 32; ++b) addClick(x, sr, b * beat / 2, 0.15f, rng);
        check("A minor prog @93 (loop)", x, sr, 9, 1, 93.f);
    }
    { // F# minor melody-ish phrase, 174 BPM drum&bass-style hits, 8 bars
        const double beat = 60.0 / 174.0;
        std::vector<float> x((size_t)(sr * beat * 32), 0.f);
        int notes[] = {54,57,61,64,61,57,59,62,54,57,61,66,64,61,57,54};
        for (int i = 0; i < 16; ++i) addNote(x, sr, i * 2 * beat, 2 * beat, notes[i], 0.2f);
        for (int i = 0; i < 16; ++i) addNote(x, sr, i * 2 * beat, 2 * beat, 42, 0.15f); // F# bass
        for (int b = 0; b < 32; ++b) addClick(x, sr, b * beat, 0.6f, rng);
        check("F# minor @174 (loop)", x, sr, 6, 1, 174.f);
    }
    { // Drum one-shot: 0.3 s noise burst -> no key, no tempo
        std::vector<float> x((size_t)(sr * 0.3), 0.f);
        addClick(x, sr, 0.0, 0.9f, rng);
        check("noise one-shot", x, sr, -1, 0, 0.f);
    }
    { // Drum-only loop at 140 -> no key, tempo 140
        const double beat = 60.0 / 140.0;
        std::vector<float> x((size_t)(sr * beat * 16), 0.f);
        for (int b = 0; b < 16; ++b) addClick(x, sr, b * beat, 0.8f, rng);
        for (int b = 0; b < 32; ++b) addClick(x, sr, b * beat / 2 + 0.01, 0.2f, rng);
        check("drum loop @140", x, sr, -1, 0, 140.f);
    }
    { // 44.1 kHz file: D major arpeggio at 100 BPM, 4 bars
        const int s2 = 44100; const double beat = 0.6;
        std::vector<float> x((size_t)(s2 * beat * 16), 0.f);
        int arp[] = {62,66,69,74,67,71,74,79,69,73,76,81,62,66,69,74};
        for (int i = 0; i < 16; ++i) addNote(x, s2, i * beat, beat * 1.5, arp[i], 0.25f);
        for (int b = 0; b < 16; ++b) addClick(x, s2, b * beat, 0.5f, rng);
        check("D major arp @100 44.1k", x, s2, 2, 0, 100.f);
    }
    { // Transients: a "drum loop" of 8 hits at uneven positions (kick-ish thumps + noisy snares)
        std::vector<float> x(sr * 4, 0.f);
        const double hits[] = {0.0, 0.5, 0.75, 1.25, 2.0, 2.5, 3.0, 3.375};
        for (int k = 0; k < 8; ++k) {
            const int a = (int)(hits[k] * sr);
            for (int i = 0; i < sr / 5 && a + i < (int)x.size(); ++i) {
                double t = (double)i / sr, env = std::exp(-t * 18);
                x[a + i] += (float)(0.6 * env * (k % 2 ? 0.5 * (std::rand() / (double)RAND_MAX * 2 - 1) : std::sin(2 * PI * (60 + 90 * std::exp(-t * 40)) * t)));
            }
        }
        int64_t out[32];
        int c = detectTransients(x.data(), (int64_t)x.size(), 1, sr, 0.6f, 16, out);
        int matched = 0;
        for (int k = 1; k < 8; ++k) for (int j = 0; j < c; ++j) if (std::llabs(out[j] - (int64_t)(hits[k] * sr)) < sr * 0.015) { ++matched; break; }
        bool ok = c == 8 && out[0] == 0 && matched == 7;
        std::printf("%-28s %d slices, %d/7 hits within 15 ms   %s\n", "transients (8-hit loop)", c, matched, ok ? "PASS" : "FAIL");
        if (!ok) ++fails;
        int c2 = detectTransients(x.data(), (int64_t)x.size(), 1, sr, 0.6f, 4, out);
        bool ok2 = c2 == 4;
        std::printf("%-28s capped at %d slices                %s\n", "transients maxSlices=4", c2, ok2 ? "PASS" : "FAIL");
        if (!ok2) ++fails;
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails ? 1 : 0;
}
