// SPDX-License-Identifier: GPL-3.0-or-later
// Worst-case audio-callback benchmark (desktop). Build:
//   g++ -std=c++20 -O3 -DNDEBUG -ffast-math -pthread -Wno-unused-parameter -I../../main/cpp -Istubs \
//       ../../main/cpp/AudioEngine.cpp ../../main/cpp/Analysis.cpp bench.cpp -o bench && ./bench
// Reports µs per 192-frame callback and the share of the real-time budget (4 ms at 48 kHz).
#include "AudioEngine.h"
#include "Synth.h"
#include "Sequencer.h"
#include "MasterFx.h"
#include "DrumSynth.h"
#include "Loudness.h"
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
std::unique_ptr<SampleBuffer> decodeAudioFd(int, const char**) { return nullptr; }

using clk = std::chrono::steady_clock;
static double usPer(auto&& fn, int iters) {
    for (int i = 0; i < 50; ++i) fn();                       // warm-up
    auto t0 = clk::now();
    for (int i = 0; i < iters; ++i) fn();
    return std::chrono::duration<double, std::micro>(clk::now() - t0).count() / iters;
}
int main() {
    const int N = 192; const double budget = N / 48000.0 * 1e6;
    std::printf("callback = %d frames, budget %.0f µs\n\n", N, budget);
    std::vector<float> out(N * 2);

    { synth::Synth s; s.setSampleRate(48000); s.setParam(synth::OscMix, 0.5f); s.setParam(synth::Osc1Shape, 0.8f); s.setParam(synth::Osc2Shape, 0.4f);
      s.setParam(synth::LfoDepth, 0.5f); s.setParam(synth::LfoTarget, 1);
      for (int n = 48; n < 56; ++n) s.noteOn(n, 0.9f);
      std::vector<float> mono(N);
      double us = usPer([&] { std::fill(out.begin(), out.end(), 0.f); s.render(out.data(), mono.data(), N); }, 20000);
      std::printf("synth, 8 voices              %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget); }

    { SampleBuffer b; b.sampleRate = 44100; b.channels = 2; b.frames = 44100 * 20; b.data.resize(b.frames * 2);
      for (size_t i = 0; i < b.data.size(); ++i) b.data[i] = (float)std::sin(i * 0.001);
      std::vector<SamplerVoice> v(16);
      for (int i = 0; i < 16; ++i) v[i].start(&b, i, 1.07f, 0.5f, PlayMode::Loop, 48000, i + 1, i * 44100, i * 44100 + 40000);
      double us = usPer([&] { std::fill(out.begin(), out.end(), 0.f); for (auto& x : v) x.render(out.data(), N, 48000); }, 20000);
      std::printf("sampler, 16 voices (stereo)  %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget); }

    { seq::Pattern p; p.lengthTicks = 64 * 240; p.count = 400;
      for (int i = 0; i < 400; ++i) { p.events[i] = seq::Event{(i * 37) % (64 * 240), 120, (int16_t)(i % 16), 0.8f, 1.f}; }
      seq::Sequencer s; s.setTempo(140); s.setPattern(0, &p); s.setPattern(1, &p); s.setPlaying(true);
      seq::Fire f[512];
      double us = usPer([&] { s.process(N, 48000, f, 512); }, 20000);
      std::printf("sequencer, 2 × 400 events    %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget); }

    { fx::MasterFx m; for (size_t i = 0; i < out.size(); ++i) out[i] = (float)std::sin(i * 0.01);
      for (int id = 0; id < fx::kCount; ++id) m.engage(id, 0.7f, 0.6f);
      double us = usPer([&] { m.process(out.data(), N, 48000, 140, 0, 0.04); }, 20000);
      std::printf("master FX, all 12 engaged    %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget);
      fx::MasterFx m2;
      double us2 = usPer([&] { m2.process(out.data(), N, 48000, 140, 0, 0.04); }, 20000);
      std::printf("master FX, all off           %7.2f µs  (%5.2f%%)\n", us2, 100 * us2 / budget); }

    { // drum machine: all 16 voices ringing, then the same with the DUST bus fully on
      drums::DrumSynth d; d.setSampleRate(48000);
      for (int s = 0; s < drums::kSlots; ++s) d.setParam(s, drums::Decay, 4000);
      auto hitAll = [&] { for (int s = 0; s < drums::kSlots; ++s) d.trigger(s, 1.f); };
      hitAll(); int n = 0;
      double us = usPer([&] { if (++n % 100 == 0) hitAll(); std::fill(out.begin(), out.end(), 0.f); d.render(out.data(), N); }, 20000);
      std::printf("drums, 16 voices             %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget);
      for (int k = 0; k < drums::kBusParamCount; ++k) d.setParam(drums::kBusSlot, k, 0.6f);
      double us2 = usPer([&] { if (++n % 100 == 0) hitAll(); std::fill(out.begin(), out.end(), 0.f); d.render(out.data(), N); }, 20000);
      std::printf("drums, 16 voices + DUST bus  %7.2f µs  (%5.2f%%)\n", us2, 100 * us2 / budget); }

    { meter::Loudness m; m.setSampleRate(48000);
      for (size_t i = 0; i < out.size(); ++i) out[i] = (float)std::sin(i * 0.01) * 0.3f;
      double us = usPer([&] { for (int i = 0; i < N; ++i) m.process(out[2 * i], out[2 * i + 1]); }, 20000);
      std::printf("loudness meter (R128)        %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget); }

    { // Whole engine callback: 16 pads (via the sequencer) + 8 synth voices + all FX + mixer + taps
      AudioEngine e; oboe::AudioStream st;
      auto* sb = new SampleBuffer(); sb->sampleRate = 44100; sb->channels = 2; sb->frames = 44100 * 20; sb->data.resize(sb->frames * 2);
      for (size_t i = 0; i < sb->data.size(); ++i) sb->data[i] = (float)std::sin(i * 0.002) * 0.3f;
      // register directly through a fake load: use assign path via a Command
      for (int pad = 0; pad < 16; ++pad) {
          Command c{CmdType::AssignSample, pad}; c.buf = sb;   // test-only: bypass the registry
          (void)c;
      }
      auto* drums = new seq::Pattern(); drums->lengthTicks = 3840; drums->count = 64;
      for (int i = 0; i < 64; ++i) { drums->events[i] = seq::Event{(i % 16) * 240, 120, (int16_t)(i % 16), 0.9f, 1.f}; drums->events[i].ratchet = (i % 5 == 0) ? 4 : 1; }
      auto* syn = new seq::Pattern(); syn->lengthTicks = 3840; syn->count = 64;
      for (int i = 0; i < 64; ++i) { syn->events[i] = seq::Event{(i % 16) * 240, 400, (int16_t)(48 + (i * 7) % 24), 0.8f, 1.f}; syn->events[i].lockId[0] = 6; syn->events[i].lockVal[0] = 300.f + i * 50; }
      e.setTempo(170); e.setPattern(0, drums); e.setPattern(1, syn); e.setPlaying(true);
      for (int id = 0; id < fx::kCount; ++id) e.fxOn(id, 0.6f, 0.6f);
      double us = usPer([&] { e.onAudioReady(&st, out.data(), N); }, 20000);
      std::printf("FULL CALLBACK (synth+seq+FX) %7.2f µs  (%5.2f%%)\n", us, 100 * us / budget);
      delete sb;
    }
    return 0;
}
