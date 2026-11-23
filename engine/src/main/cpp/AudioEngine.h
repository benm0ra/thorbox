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
#include <vector>
#include "Analysis.h"
#include "Commands.h"
#include "SampleBuffer.h"
#include "SamplerVoice.h"
#include "SpscQueue.h"
#include "Ducker.h"
#include "Synth.h"
#include "DrumSynth.h"
#include "Sequencer.h"
#include "MasterFx.h"
#include "Spectrum.h"
#include "Loudness.h"

// Results the worker hands back to the UI (via pollEvent). Non-RT, so a mutex is fine here.
struct WorkerResult {
    int32_t requestId = 0;
    int32_t sampleId = 0;     // 0 = failed
    int64_t frames = 0;
    int32_t sampleRate = 0;
    int32_t channels = 0;
    int32_t error = 0;        // DecodeError when sampleId == 0
    bool truncated = false;   // only the first 10 minutes were kept
    double sourceSeconds = 0; // the file's full length, when known
    AnalysisResult analysis;
};

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    /**
     * Set by the JNI layer (JNI_OnLoad): attach / detach the decode worker thread to the Java VM.
     * Android's media extractor (MP3, FLAC, OGG, M4A) needs a JVM-attached thread; on a bare
     * std::thread it fails to create the extractor at all. Null on desktop builds (tests).
     */
    static inline void (*sThreadAttach)() = nullptr;
    static inline void (*sThreadDetach)() = nullptr;
public:
    // 8 tracks; each can be a sampler, synth or drum machine. Sampler pads use GLOBAL ids:
    // track * kPadsPerTrack + pad (pad 16 of each track is its chop-preview voice).
    static constexpr int kTracks = seq::kTracks;
    static constexpr int kPadsPerTrack = 17;
    static constexpr int kMaxPads = kTracks * kPadsPerTrack;
    static constexpr int kPreviewPad = 16;      // local index within a track
    static constexpr int kMaxVoices = 48;

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
    bool setPadParam(int pad, int param, float value);
    bool getWave(int sampleId, int buckets, float* out);   // [min, max, rms] × buckets; any thread
    bool stopPads(int track);                         // -1 = all tracks
    // Transient onsets of a loaded sample (frames, ascending, first is 0). Safe from any thread.
    int transients(int sampleId, float sensitivity, int maxSlices, int64_t* out);
    bool setMasterGainDb(float db);
    bool synthNoteOn(int track, int note, float velocity);
    bool synthNoteOff(int track, int note);
    bool synthParam(int track, int id, float value);
    bool synthAllOff(int track);
    bool drumTrigger(int track, int slot, float velocity);
    bool drumParam(int track, int slot, int id, float value);
    // Drum animation telemetry for one track: per-slot level (0..1) and hit counters (wrap).
    void getDrumTelemetry(int track, float* levels8, int32_t* hits8);
    bool setTrackType(int track, int type);
    bool setFocus(int track);
    // Per-strip peaks (held until read): [2k, 2k+1] = strip k L/R, [16, 17] = master,
    // [18 + k] = strip k sidechain gain (1 = no ducking).
    void getMeters(float* out26);
    // DUCK (the mix's sidechain compressor): set a duck::Param; telemetry since the last read:
    // [0] deepest gain reduction dB, [1] reduction right now dB, [2] loudest key (linear), [3] trigger count.
    bool setDuck(int param, float value);
    void getDuck(float* out4);

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
    bool setChannel(int strip, int param, float value);  // strips 0..7 = tracks, 8 = master
    bool fxOn(int id, float x, float y);
    bool fxMove(int id, float x, float y);
    bool fxOff(int id);
    bool fxAllOff();
    // Recorded live hits (main thread). Returns 0 none, 1 note-on, 2 note-off; out = {track, note, vel, tick}.
    int pollRecord(double* out4);
    void getTransport(double* out20);   // [12] = active punch-in FX bitmask, [15]/[19] echo/reverb tails, [16..17] drum meters, [18] drum activity mask
    // Visualizer (main thread): FFT of the latest master output. energy3 = bass, mid, treble (0..1).
    // energy: [0..2] bass/mid/high; when nEnergy ≥ 5 also [3] dominant frequency (Hz, 0 = none), [4] its level (dBFS)
    void getVisual(float* bands, int nBands, float* wave, int nWave, float* energy, int nEnergy = 3);
    // Master loudness: [0] momentary, [1] short-term, [2] integrated (LUFS), [3]/[4] peak L/R since reset (dBFS), [5] seconds measured
    void getLoudness(float* out6) const;
    void resetLoudness() { mLoud.reset(); }
    // The last n master samples as mid (L+R)/2 and side (L−R)/2, oldest first (n ≤ kScope)
    int getStereo(float* mid, float* side, int n) const;

    // ---- Sample management (main thread) ----
    void loadSampleAsync(int fd, int requestId);      // takes ownership of fd
    /** The load in progress: [requestId, stage (0 idle · 1 decoding · 2 analysing), fraction 0..1 or -1]. */
    void loadProgress(float* out3) const {
        out3[0] = (float)mProgReq.load(std::memory_order_relaxed);
        out3[1] = (float)mProgStage.load(std::memory_order_relaxed);
        out3[2] = mProgFrac.load(std::memory_order_relaxed);
    }
    /** Stop decoding [requestId] (it then fails with kErrCancelled). */
    void cancelLoad(int requestId) { mCancelReq.store(requestId, std::memory_order_relaxed); }
    // Renders a time-stretched / pitch-shifted copy of a loaded sample on the worker. The result
    // arrives through pollEvent like a load (new sample id); the source stays loaded.
    void stretchSampleAsync(int sampleId, double tempo, double semitones, int requestId, int64_t cropStart = 0, int64_t cropEnd = 0);
    bool assignSample(int pad, int sampleId);         // sampleId 0 clears the pad
    void unloadSample(int sampleId);

    // ---- Polling (main thread, once per UI frame) ----
    // Returns 0 (nothing), 1 (sample loaded) or 2 (load failed). Fills out[0..10].
    int pollEvent(double* out);
    bool getPeaks(int sampleId, int buckets, float* outMinMax);
    // Per region [starts[i], ends[i]) of a loaded sample: peak dBFS and loudness (the loudest 50 ms RMS window, dBFS).
    // Main-thread call; reads the buffer outside the registry lock. Returns false for an unknown sample.
    bool regionLevels(int sampleId, const int64_t* starts, const int64_t* ends, int n, float* outPeakDb, float* outLoudDb);
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
        VoiceOpts opts;
        int muteGroup = 0, muteA = -1, muteB = -1;
        bool poly = false;
        float velSens = 1.f;
        bool chokeAll = false;                // cuts every other pad on the track
    };
    struct RegistryEntry {
        std::shared_ptr<SampleBuffer> buf;
        int audioRefs = 0;        // pads on the audio thread pointing at this buffer
        bool pendingDelete = false;
    };
    // srcSample > 0 = stretch job; [cropStart, cropEnd) frames of the source (cropEnd 0 = to the end).
    struct Job { int fd; int requestId; int srcSample = 0; double tempo = 1.0, semis = 0.0; int64_t cropStart = 0, cropEnd = 0; };

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
    std::array<synth::Synth, kTracks> mSynths{};
    std::array<drums::DrumSynth, kTracks> mDrums{};
    std::array<TrackType, kTracks> mTrackType{TrackType::Sampler, TrackType::Synth, TrackType::Drums};
    int mFocusSynth = 1, mFocusSampler = 0;
    seq::Sequencer mSeq;
    fx::MasterFx mFx;                  // punch-in effects on the master bus (ring allocated here, main thread)
    bool mRecording = false;

    // Rendering happens in chunks of kChunk frames, split at each sequencer event for sample accuracy.
    static constexpr int kChunk = 256;
    static constexpr int kI16ScratchFrames = 2048;
    static constexpr int kStrips = kTracks;
    std::array<seq::Fire, 512> mFires{};
    std::array<std::array<float, 2 * kChunk>, kStrips> mBus{};   // per-track stereo buses
    std::array<float, 2 * kChunk> mClickBus{};                   // metronome (bypasses the mixer)
    std::array<float, kChunk> mSynthScratch{};                   // mono synth output for the scope
    struct Strip {
        float volDb = 0.f, pan = 0.f; bool mute = false, solo = false;
        float gL = 1.f, gR = 1.f;       // smoothed gains
        float peakL = 0.f, peakR = 0.f; // block peaks (post-fader)
        int scSrc = -1; float scAmount = 0.f, scReleaseMs = 150.f;   // sidechain
        float scEnv = 0.f, scGainMin = 1.f;
    };
    std::array<float, kStrips> mScKey{};
    duck::Ducker mDuck;
    std::array<float, kChunk> mDuckKey{}, mDuckGain{};
    std::atomic<float> mDuckGrMax{0.f}, mDuckGrNow{0.f}, mDuckKeyPk{0.f};
    std::atomic<uint32_t> mDuckTrig{0};   // per-strip key envelope (pre-fader), shared by all ducked strips
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
    std::array<std::atomic<float>, kScope> mMasterSide{};   // (L−R)/2, written alongside mMasterTap (mid)
    meter::Loudness mLoud;                                  // R128 loudness of the master (audio thread writes, UI reads atomics)
    viz::Spectrum mSpectrum;                   // main-thread only
    std::array<float, viz::kFft> mVizIn{};     // main-thread only

    // Transport + meters for the UI. Meters are peak-hold until read (exchange to 0).
    std::atomic<double> mTelTick{0.0};
    std::atomic<int32_t> mTelFlags{0};       // bit0 playing, bit1 recording, bit2 metronome
    std::atomic<uint32_t> mTelFx{0};
    std::atomic<float> mTelEchoTail{0.f}, mTelRevTail{0.f};
    std::atomic<uint32_t> mTelPads{0};       // bitmask of pads with a sounding voice
    std::atomic<int32_t> mTelPlayPad{-1};    // pad of the newest voice (for the chop playhead)
    std::atomic<float> mTelBpm{90.f}, mTelSwing{0.5f};
    std::array<std::atomic<float>, 2 * kStrips + 2> mMeters{};   // strips L/R, then master L/R
    std::array<std::atomic<float>, kStrips> mScGain{};
    std::array<std::atomic<float>, kTracks * drums::kSlots> mDrumLevel{};
    std::array<std::atomic<int32_t>, kTracks * drums::kSlots> mDrumHits{};
    std::atomic<uint32_t> mTelDrums{0};
    bool mMetroOn = false;

    // Main-thread-only staging for recorded notes (filled while draining mAudioEvents).
    struct Rec { int type; int track; int note; float vel; double tick; };
    std::deque<Rec> mRecQueue;

    // Stream (guarded for open/close/stats; never touched by the callback's hot path)
    std::mutex mStreamMtx;
    std::shared_ptr<oboe::AudioStream> mStream;
    // Output format actually granted (Float, or I16 on devices whose fast/MMAP path wants 16-bit).
    bool mOutI16 = false;
    std::vector<float> mI16Scratch;                       // float render target when the device wants I16
    std::unique_ptr<oboe::LatencyTuner> mTuner;           // grows the buffer one burst at a time on underruns
    void renderFloat(oboe::AudioStream* stream, float* out, int32_t numFrames);
    bool mWantRunning = false;

    // Registry (main + worker threads)
    std::mutex mRegMtx;
    std::unordered_map<int, RegistryEntry> mRegistry;
    int mNextSampleId = 1;

    // Worker
    std::thread mWorker;
    std::atomic<int> mProgReq{0}, mProgStage{0}, mCancelReq{0};
    std::atomic<float> mProgFrac{-1.f};
    std::mutex mJobMtx;
    std::condition_variable mJobCv;
    std::deque<Job> mJobs;
    bool mQuit = false;
    std::mutex mResultMtx;
    std::deque<WorkerResult> mResults;
};
