// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop test: varispeed pitch accuracy, sample-rate compensation, modes, SPSC queue.
// g++ -std=c++20 -O2 -I../../main/cpp voice_test.cpp -o vt && ./vt
#include "SamplerVoice.h"
#include "SpscQueue.h"
#include <cmath>
#include <cstdio>
#include <thread>
#include <vector>
static int fails = 0;
#define CHECK(c, ...) do { bool ok_ = (c); std::printf("%-52s %s\n", __VA_ARGS__, ok_ ? "PASS" : "FAIL"); if (!ok_) ++fails; } while (0)

// Frequency by zero-crossing count over the left channel.
static double freqOf(const std::vector<float>& st, int sr) {
    int z = 0; for (size_t i = 2; i < st.size(); i += 2) if (st[i - 2] < 0 && st[i] >= 0) ++z;
    return z / ((st.size() / 2) / (double)sr);
}
int main() {
    SampleBuffer b; b.id = 1; b.sampleRate = 44100; b.channels = 1; b.frames = 44100 * 2;
    b.data.resize(b.frames);
    for (int i = 0; i < b.frames; ++i) b.data[i] = (float)std::sin(2 * M_PI * 440.0 * i / 44100.0);

    const int dev = 48000;
    for (int semis : {0, 12, -12, 7}) {
        SamplerVoice v; std::vector<float> out(dev / 2 * 2, 0.f);
        v.start(&b, 0, std::pow(2.f, semis / 12.f), 1.f, PlayMode::OneShot, dev, 1);
        v.render(out.data(), dev / 2, dev);
        double expect = 440.0 * std::pow(2.0, semis / 12.0), got = freqOf(out, dev);
        char name[80]; std::snprintf(name, 80, "44.1k sample on 48k device, %+d st: %.1f Hz", semis, got);
        CHECK(std::fabs(got - expect) < 3.0, name);
    }
    { // cents
        SamplerVoice v; std::vector<float> out(dev * 2, 0.f);
        v.start(&b, 0, std::pow(2.f, (50 / 100.f) / 12.f), 1.f, PlayMode::OneShot, dev, 1);
        v.render(out.data(), dev, dev);
        CHECK(std::fabs(freqOf(out, dev) - 452.9) < 2.0, "+50 cents ≈ 452.9 Hz");
    }
    { // one-shot ends at the sample end; loop keeps going; gate release ends
        SamplerVoice v; std::vector<float> out(dev * 3 * 2, 0.f);
        v.start(&b, 0, 1.f, 1.f, PlayMode::OneShot, dev, 1);
        v.render(out.data(), dev * 3, dev);
        CHECK(!v.active(), "one-shot stops after the sample (2 s < 3 s)");
        SamplerVoice l; std::fill(out.begin(), out.end(), 0.f);
        l.start(&b, 0, 1.f, 1.f, PlayMode::Loop, dev, 1);
        l.render(out.data(), dev * 3, dev);
        CHECK(l.active() && std::fabs(out[2 * (dev * 5 / 2)]) + std::fabs(out[2 * (dev * 5 / 2) + 2]) > 0.01f, "loop still sounding at 2.5 s");
        l.release(15.f, dev);
        std::vector<float> o2(dev / 10 * 2, 0.f); l.render(o2.data(), dev / 10, dev);
        CHECK(!l.active(), "loop stops within 100 ms of release");
    }
    { // de-click: first sample of a hit must be ~0
        SampleBuffer dc; dc.sampleRate = dev; dc.channels = 1; dc.frames = dev; dc.data.assign(dev, 1.f);
        SamplerVoice v; std::vector<float> out(64 * 2, 0.f);
        v.start(&dc, 0, 1.f, 1.f, PlayMode::OneShot, dev, 1);
        v.render(out.data(), 64, dev);
        CHECK(out[0] < 0.05f && out[126] > out[0], "attack ramps from ~0 (no click)");
    }
    { // Slices: play [1 s, 1.25 s) of a 2 s buffer, one-shot stops at the slice end with a fade
        SampleBuffer rb; rb.sampleRate = dev; rb.channels = 1; rb.frames = dev * 2; rb.data.assign(rb.frames, 0.5f);
        SamplerVoice v; std::vector<float> out(dev / 2 * 2, 0.f);
        v.start(&rb, 3, 1.f, 1.f, PlayMode::OneShot, dev, 1, dev, dev + dev / 4);
        v.render(out.data(), dev / 2, dev);
        const int endAt = dev / 4;
        CHECK(!v.active() && out[2 * (endAt - 2)] < 0.05f && out[2 * (endAt - 2000)] > 0.45f && out[2 * (endAt + 10)] == 0.f,
              "slice plays its region, fades at the end, then stops");
        SamplerVoice l; std::fill(out.begin(), out.end(), 0.f);
        l.start(&rb, 3, 1.f, 1.f, PlayMode::Loop, dev, 1, dev, dev + dev / 8);
        l.render(out.data(), dev / 2, dev);
        CHECK(l.active() && l.position() >= dev && l.position() <= dev + dev / 8, "loop mode wraps inside the slice");
    }
    { // SPSC under contention
        SpscQueue<int, 64> q; long long sum = 0; const int N = 2000000;
        std::thread prod([&] { for (int i = 1; i <= N; ) if (q.push(i)) ++i; });
        int got = 0, x; while (got < N) if (q.pop(x)) { sum += x; ++got; }
        prod.join();
        CHECK(sum == (long long)N * (N + 1) / 2, "SPSC queue: 2M items, no loss or dupes");
    }
    std::printf(fails ? "\n%d FAILED\n" : "\nall passed\n", fails);
    return fails != 0;
}
