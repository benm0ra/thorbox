// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include "Analysis.h"
#include "Commands.h"
#include "SampleBuffer.h"
#include "SamplerVoice.h"
#include "SpscQueue.h"
#include "Synth.h"
#include "DrumSynth.h"
#include "Sequencer.h"
#include "MasterFx.h"
#include "Spectrum.h"

// Results the worker hands back to the UI (via pollEvent). Non-RT, so a mutex is fine here.
struct WorkerResult {
    int32_t requestId = 0;
    int32_t sampleId = 0;     // 0 = failed
    int64_t frames = 0;
    int32_t sampleRate = 0;
    int32_t channels = 0;
    AnalysisResult analysis;
};

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kMaxPads = 17;        // 16 pads + the chop-preview voice
    static constexpr int kPreviewPad = 16;
    static constexpr int kMaxVoices = 32;

    AudioEngine();
    ~AudioEngine() override;

    // ---- Lifecycle (main thread) ----
    bool start();
    void stop();

    // ---- Real-time control (main thread → audio thread, lock-free) ----
    bool noteOn(int pad, float velocity);
    bool noteOff(int pad);
    bool setPitch(int pad, int semitones, float cents);
    bool setPlayMode(int pad, int mode);
    bool setPadRegion(int pad, int64_t startFrame, int64_t endFrame);
    bool setPadGain(int pad, float linear);
    // Transient onsets of a loaded sample (frames, ascending, first is 0). Safe from any thread.
    int transients(int sampleId, float sensitivity, int maxSlices, int64_t* out);
    bool setMasterGainDb(float db);
    bool synthNoteOn(int note, float velocity);
    bool synthNoteOff(int note);
    bool synthParam(int id, float value);
    bool synthAllOff();
    bool drumTrigger(int slot, float velocity);
    bool drumParam(int slot, int id, float value);
    // Drum animation telemetry: per-slot level (0..1) and hit counters (wrap; compare to last read).
    void getDrumTelemetry(float* levels8, int32_t* hits8);

    // ---- Transport / groove / mixer (main thread → audio thread) ----
    bool setTempo(float bpm);
    bool setSwing(float amount, int gridTicks);
    bool setPlaying(bool on);
    bool setRecording(bool on);
    bool setMetronome(bool on);
    bool setPattern(int track, seq::Pattern* owned);   // takes ownership (deleted on failure)
    bool repeatOn(int track, int note, int rateTicks, float velocity);
    bool repeatOff(int track, int note);
    bool repeatVelocity(float v);
    bool setChannel(int strip, int param, float value);  // strip 0 sampler, 1 synth, 2 drums, 3 master
    bool fxOn(int id, float x, float y);
    bool fxMove(int id, float x, float y);
    bool fxOff(int id);
    bool fxAllOff();
    // Recorded live hits (main thread). Returns 0 none, 1 note-on, 2 note-off; out = {track, note, vel, tick}.
    int pollRecord(double* out4);
    void getTransport(double* out20);   // [12] = active punch-in FX bitmask, [16..17] drum meters, [18] drum activity mask
    // Visualizer (main thread): FFT of the latest master output. energy3 = bass, mid, treble (0..1).
    void getVisual(float* bands, int nBands, float* wave, int nWave, float* energy3);

    // ---- Sample management (main thread) ----
    void loadSampleAsync(int fd, int requestId);      // takes ownership of fd
    // Renders a time-stretched / pitch-shifted copy of a loaded sample on the worker. The result
    // arrives through pollEvent like a load (new sample id); the source stays loaded.
    void stretchSampleAsync(int sampleId, double tempo, double semitones, int requestId);
    bool assignSample(int pad, int sampleId);         // sampleId 0 clears the pad
    void unloadSample(int sampleId);

    // ---- Polling (main thread, once per UI frame) ----
    // Returns 0 (nothing), 1 (sample loaded) or 2 (load failed). Fills out[0..10].
    int pollEvent(double* out);
    bool getPeaks(int sampleId, int buckets, float* outMinMax);
    float playhead() const { return mPlayhead.load(std::memory_order_relaxed); }
    float peakLevel() { return mPeak.exchange(0.f, std::memory_order_relaxed); }
    void getStats(double* out);                       // see NativeEngine.Stats for layout
    void getSynthTelemetry(float* out12, int64_t* notes2);
    int getScope(float* out, int n);                  // latest n synth samples, oldest first

    // ---- Oboe callbacks (audio thread) ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct PadState {
        const SampleBuffer* buf = nullptr;
        float pitchRatio = 1.f;
        PlayMode mode = PlayMode::OneShot;
        int64_t start = 0, end = -1;          // slice region in frames (-1 = to the end)
        float gain = 1.f;
    };
    struct RegistryEntry {
        std::shared_ptr<SampleBuffer> buf;
        int audioRefs = 0;        // pads on the audio thread pointing at this buffer
        bool pendingDelete = false;
    };
    struct Job { int fd; int requestId; int srcSample = 0; double tempo = 1.0, semis = 0.0; };   // srcSample > 0 = stretch job

    bool openStream();
    void handleCommand(const Command& c);
    void startPad(int pad, float vel, float sr);
    void releasePad(int pad, float sr);
    void applyFire(const seq::Fire& f, double tickAtChunk, double tps, float sr);
    void renderSegment(int a, int b, float sr, uint32_t& scopeW);
    void mixChunk(float* out, int n, float sr, double tick, double tps);
    void recordEvent(AudioEventType t, int track, int note, float vel, double tick);
    SamplerVoice* allocVoice();
    void workerLoop();
    void releaseRef(int sampleId);

    // Audio-thread-only state
    std::array<PadState, kMaxPads> mPads{};
    std::array<SamplerVoice, kMaxVoices> mVoices{};
    uint64_t mVoiceStamp = 0;
    synth::Synth mSynth;
    drums::DrumSynth mDrums;
    seq::Sequencer mSeq;
    fx::MasterFx mFx;                  // punch-in effects on the master bus (ring allocated here, main thread)
    bool mRecording = false;

    // Rendering happens in chunks of kChunk frames, split at each sequencer event for sample accuracy.
    static constexpr int kChunk = 256;
    static constexpr int kStrips = 3;
    std::array<seq::Fire, 512> mFires{};
    std::array<std::array<float, 2 * kChunk>, kStrips> mBus{};   // per-track stereo buses
    std::array<float, 2 * kChunk> mClickBus{};                   // metronome (bypasses the mixer)
    std::array<float, kChunk> mSynthScratch{};                   // mono synth output for the scope
    struct Strip {
        float volDb = 0.f, pan = 0.f; bool mute = false, solo = false;
        float gL = 1.f, gR = 1.f;       // smoothed gains
        float peakL = 0.f, peakR = 0.f; // block peaks (post-fader)
    };
    std::array<Strip, kStrips> mStrips{};
    float mMasterPeakL = 0.f, mMasterPeakR = 0.f;
    float mClickPhase = 0.f, mClickEnv = 0.f, mClickFreq = 1500.f;
    float mMasterGain = 0.8f;
    float mMasterGainTarget = 0.8f;

    // Cross-thread
    SpscQueue<Command, 1024> mCmds;
    SpscQueue<AudioEvent, 256> mAudioEvents;
    std::atomic<float> mPlayhead{-1.f};
    std::atomic<float> mPeak{0.f};
    std::atomic<int32_t> mDeviceRate{48000};

    // Synth telemetry for the UI animations. Relaxed atomics: each value is independently
    // fresh; a frame mixing two blocks is invisible at 120 Hz.
    static constexpr int kTel = 12;
    std::array<std::atomic<float>, kTel> mTel{};
    std::array<std::atomic<uint64_t>, 2> mTelNotes{};
    static constexpr uint32_t kScope = 2048;          // power of two
    std::array<std::atomic<float>, kScope> mScope{};
    std::atomic<uint32_t> mScopeWrite{0};
    // Master output tap for the visualizer (mono, post-fader), and the main-thread analyzer.
    std::array<std::atomic<float>, kScope> mMasterTap{};
    std::atomic<uint32_t> mMasterTapWrite{0};
    viz::Spectrum mSpectrum;                   // main-thread only
    std::array<float, viz::kFft> mVizIn{};     // main-thread only

    // Transport + meters for the UI. Meters are peak-hold until read (exchange to 0).
    std::atomic<double> mTelTick{0.0};
    std::atomic<int32_t> mTelFlags{0};       // bit0 playing, bit1 recording, bit2 metronome
    std::atomic<uint32_t> mTelFx{0};
    std::atomic<uint32_t> mTelPads{0};       // bitmask of pads with a sounding voice
    std::atomic<int32_t> mTelPlayPad{-1};    // pad of the newest voice (for the chop playhead)
    std::atomic<float> mTelBpm{90.f}, mTelSwing{0.5f};
    std::array<std::atomic<float>, 8> mMeters{};  // sampler L/R, synth L/R, master L/R, drums L/R
    std::array<std::atomic<float>, drums::kSlots> mDrumLevel{};
    std::array<std::atomic<int32_t>, drums::kSlots> mDrumHits{};
    std::atomic<uint32_t> mTelDrums{0};
    bool mMetroOn = false;

    // Main-thread-only staging for recorded notes (filled while draining mAudioEvents).
    struct Rec { int type; int track; int note; float vel; double tick; };
    std::deque<Rec> mRecQueue;

    // Stream (guarded for open/close/stats; never touched by the callback's hot path)
    std::mutex mStreamMtx;
    std::shared_ptr<oboe::AudioStream> mStream;
    bool mWantRunning = false;

    // Registry (main + worker threads)
    std::mutex mRegMtx;
    std::unordered_map<int, RegistryEntry> mRegistry;
    int mNextSampleId = 1;

    // Worker
    std::thread mWorker;
    std::mutex mJobMtx;
    std::condition_variable mJobCv;
    std::deque<Job> mJobs;
    bool mQuit = false;
    std::mutex mResultMtx;
    std::deque<WorkerResult> mResults;
};
