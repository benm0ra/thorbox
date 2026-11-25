// SPDX-License-Identifier: GPL-3.0-or-later
#include "AudioEngine.h"
// AudioEngine, part 1 of 3: lifecycle, the main-thread API (each call pushes a Command), sample loading
// on the worker thread, polling. The audio thread is in AudioRender.cpp, the offline bounce in AudioExport.cpp.
#include "Decoder.h"
#include "Stretch.h"
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include <climits>
#include <thread>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ThorboxEngine", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ThorboxEngine", __VA_ARGS__)

AudioEngine::AudioEngine() {
    for (auto& g : mScGain) g.store(1.f, std::memory_order_relaxed);
    mMidiCh.fill(-1);
    for (auto& m : mMidiMap) for (int i = 0; i < 16; ++i) m[(size_t)i] = (uint8_t)(36 + i);   // pads / slots from C1, MPC-style
    mWorker = std::thread([this] { workerLoop(); });
}

AudioEngine::~AudioEngine() {
    stop();
    for (int t = 0; t < seq::kTracks; ++t) delete mSeq.setPattern(t, nullptr);   // stream is closed: safe
    mSeq.commit(0, -1);                                                            // staged scene patterns too
    while (const seq::Pattern* p = mSeq.retired()) delete p;
    { std::lock_guard<std::mutex> l(mJobMtx); mQuit = true; }
    mJobCv.notify_all();
    if (mWorker.joinable()) mWorker.join();
}

// ================= Lifecycle =================

bool AudioEngine::openStream() {
    // Ask for the lowest-latency output the device will give, most demanding first, and keep the
    // first that lands on the exclusive (MMAP) fast path, else the one with the smallest burst.
    // Many Qualcomm devices only grant MMAP / FAST tracks for 16-bit at the native 48 kHz with
    // Usage::Game; Float + Media is the safe fallback (what earlier builds always used).
    struct Try { oboe::SharingMode share; oboe::AudioFormat fmt; oboe::Usage usage; const char* name; };
    const Try tries[] = {
        {oboe::SharingMode::Exclusive, oboe::AudioFormat::I16,   oboe::Usage::Game,  "exclusive i16 game"},
        {oboe::SharingMode::Exclusive, oboe::AudioFormat::Float, oboe::Usage::Game,  "exclusive float game"},
        {oboe::SharingMode::Shared,    oboe::AudioFormat::I16,   oboe::Usage::Game,  "shared i16 game"},
        {oboe::SharingMode::Exclusive, oboe::AudioFormat::Float, oboe::Usage::Media, "exclusive float media"},
    };
    std::shared_ptr<oboe::AudioStream> best;
    const char* bestName = "";
    for (const Try& t : tries) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(t.share)
            ->setFormat(t.fmt)
            ->setFormatConversionAllowed(false)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(48000)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setUsage(t.usage)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (mOutputDeviceId > 0) b.setDeviceId(mOutputDeviceId);
        std::shared_ptr<oboe::AudioStream> s;
        if (b.openStream(s) != oboe::Result::OK || !s) continue;
        LOGI("try %s: %d Hz, burst %d, sharing %s, format %s", t.name, s->getSampleRate(), s->getFramesPerBurst(),
             oboe::convertToText(s->getSharingMode()), oboe::convertToText(s->getFormat()));
        const bool fmtOk = s->getFormat() == oboe::AudioFormat::Float || s->getFormat() == oboe::AudioFormat::I16;
        if (!fmtOk) { s->close(); continue; }
        const bool better = !best || (s->getSharingMode() == oboe::SharingMode::Exclusive && best->getSharingMode() != oboe::SharingMode::Exclusive)
            || (s->getSharingMode() == best->getSharingMode() && s->getFramesPerBurst() < best->getFramesPerBurst());
        if (better) { if (best) best->close(); best = s; bestName = t.name; }
        else s->close();
        if (best && best->getSharingMode() == oboe::SharingMode::Exclusive) break;   // can't do better than MMAP
    }
    if (!best) { LOGW("openStream failed for every configuration"); return false; }
    mStream = best;
    mOutI16 = mStream->getFormat() == oboe::AudioFormat::I16;
    if (mOutI16 && mI16Scratch.size() < (size_t)kI16ScratchFrames * 2) mI16Scratch.assign((size_t)kI16ScratchFrames * 2, 0.f);

    // Start at the smallest buffer the device allows; the tuner adds a burst whenever it underruns.
    mStream->setBufferSizeInFrames(mStream->getFramesPerBurst());
    mTuner = std::make_unique<oboe::LatencyTuner>(*mStream);
    mDeviceRate.store(mStream->getSampleRate());
    LOGI("stream open (%s): %d Hz, burst %d, buffer %d, sharing %s, api %s", bestName,
         mStream->getSampleRate(), mStream->getFramesPerBurst(), mStream->getBufferSizeInFrames(),
         oboe::convertToText(mStream->getSharingMode()), oboe::convertToText(mStream->getAudioApi()));

    oboe::Result r = mStream->requestStart();
    if (r != oboe::Result::OK) { LOGW("requestStart failed: %s", oboe::convertToText(r)); mStream->close(); mStream.reset(); mTuner.reset(); return false; }
    return true;
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> l(mStreamMtx);
    mWantRunning = true;
    if (mStream) return true;
    return openStream();
}

bool AudioEngine::setOutputDevice(int deviceId) {
    std::lock_guard<std::mutex> l(mStreamMtx);
    if (deviceId < 0) deviceId = 0;
    if (deviceId == mOutputDeviceId && mStream) return true;
    mOutputDeviceId = deviceId;
    if (!mWantRunning) return true;
    if (mStream) { mStream->stop(); mStream->close(); mStream.reset(); mTuner.reset(); }
    return openStream();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> l(mStreamMtx);
    mWantRunning = false;
    if (mStream) { mStream->stop(); mStream->close(); mStream.reset(); mTuner.reset(); }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    // Called on an Oboe-owned thread (not the audio callback), so reopening here is allowed.
    // Typical cause: headphones plugged/unplugged → ErrorDisconnected.
    LOGW("stream closed with error %s", oboe::convertToText(error));
    // try_lock: if start()/stop() is mid-flight on the main thread it owns the stream's fate;
    // blocking here could deadlock against Oboe's close().
    std::unique_lock<std::mutex> l(mStreamMtx, std::try_to_lock);
    if (!l.owns_lock() || mStream.get() != stream) return;
    mStream.reset();
    if (mWantRunning) { LOGI("reopening stream"); openStream(); }
}

// ================= Real-time control (main thread) =================
// All of these only push a POD command. They never block and never allocate.

bool AudioEngine::noteOn(int pad, float velocity) {
    if (pad < 0 || pad >= kMaxPads) return false;
    return mCmds.push({CmdType::NoteOn, pad, 0, std::clamp(velocity, 0.f, 1.f), nullptr});
}
bool AudioEngine::noteOff(int pad) {
    if (pad < 0 || pad >= kMaxPads) return false;
    return mCmds.push({CmdType::NoteOff, pad, 0, 0.f, nullptr});
}
bool AudioEngine::setPitch(int pad, int semis, float cents) {
    if (pad < 0 || pad >= kMaxPads) return false;
    return mCmds.push({CmdType::SetPitch, pad, semis, cents, nullptr});
}
bool AudioEngine::setPlayMode(int pad, int mode) {
    if (pad < 0 || pad >= kMaxPads || mode < 0 || mode > 2) return false;
    return mCmds.push({CmdType::SetPlayMode, pad, mode, 0.f, nullptr});
}
static inline bool validTrack(int t) { return t >= 0 && t < seq::kTracks; }
bool AudioEngine::synthNoteOn(int track, int note, float vel) {
    if (!validTrack(track) || note < 0 || note > 127) return false;
    return mCmds.push({CmdType::SynthNoteOn, track, note, std::clamp(vel, 0.f, 1.f), nullptr});
}
bool AudioEngine::synthNoteOff(int track, int note) {
    if (!validTrack(track) || note < 0 || note > 127) return false;
    return mCmds.push({CmdType::SynthNoteOff, track, note, 0.f, nullptr});
}
bool AudioEngine::synthParam(int track, int id, float value) {
    if (!validTrack(track) || id < 0 || id >= synth::kParamCount) return false;
    return mCmds.push({CmdType::SynthParam, track, id, value, nullptr});
}
bool AudioEngine::synthAllOff(int track) { return validTrack(track) && mCmds.push({CmdType::SynthAllOff, track, 0, 0.f, nullptr}); }
bool AudioEngine::setTrackType(int track, int type) {
    if (!validTrack(track) || type < 0 || type > 3) return false;
    return mCmds.push({CmdType::SetTrackType, track, type});
}
bool AudioEngine::setFocus(int track) { return validTrack(track) && mCmds.push({CmdType::SetFocus, track}); }
void AudioEngine::getMeters(float* out) {
    for (int i = 0; i < 2 * kStrips + 2; ++i) out[i] = mMeters[i].exchange(0.f, std::memory_order_relaxed);
    for (int k = 0; k < kStrips; ++k) out[2 * kStrips + 2 + k] = mScGain[k].exchange(1.f, std::memory_order_relaxed);
}

bool AudioEngine::setTempo(float bpm) { return mCmds.push({CmdType::SetTempo, 0, 0, std::clamp(bpm, 30.f, 300.f)}); }
bool AudioEngine::setSwing(float a, int grid) {
    return mCmds.push({CmdType::SetSwing, 0, grid == 480 ? 480 : 240, std::clamp(a, 0.5f, 0.75f)});
}
bool AudioEngine::setPlaying(bool on) { return mCmds.push({CmdType::SetPlaying, 0, on ? 1 : 0}); }
bool AudioEngine::setRecording(bool on) { return mCmds.push({CmdType::SetRecording, 0, on ? 1 : 0}); }
bool AudioEngine::setMetronome(bool on) { return mCmds.push({CmdType::SetMetronome, 0, on ? 1 : 0}); }
bool AudioEngine::setClick(int param, float v) { return param >= 0 && param <= 2 && std::isfinite(v) && mCmds.push({CmdType::SetClick, 0, param, v}); }
bool AudioEngine::setCountIn(float ticks) { return std::isfinite(ticks) && mCmds.push({CmdType::SetCountIn, 0, 0, std::clamp(ticks, 0.f, 4.f * 4 * seq::kPpqn)}); }
bool AudioEngine::setMidiOut(int track, int ch) { return validTrack(track) && mCmds.push({CmdType::SetMidiOut, track, std::clamp(ch, -1, 15)}); }
bool AudioEngine::setMidiMap(int track, int note, int midiNote) {
    return validTrack(track) && note >= 0 && note < 16 && midiNote >= 0 && midiNote <= 127 && mCmds.push({CmdType::SetMidiMap, track, note, 0.f, nullptr, nullptr, midiNote});
}
bool AudioEngine::setMidiClock(bool on) { return mCmds.push({CmdType::SetMidiClock, 0, on ? 1 : 0}); }
int AudioEngine::pollMidi(int64_t* out, int max) {
    int n = 0; MidiMsg m;
    while (n < max && mMidiOut.pop(m)) {
        out[3 * n] = m.frame; out[3 * n + 1] = m.len; out[3 * n + 2] = m.b0 | (m.b1 << 8) | (m.b2 << 16);
        ++n;
    }
    return n;
}
void AudioEngine::midiAnchor(int64_t* out) {
    // the two halves are written separately; re-read until they belong together
    int64_t f, t;
    do { t = mAnchorNanos.load(std::memory_order_acquire); f = mAnchorFrame.load(std::memory_order_acquire); }
    while (t != mAnchorNanos.load(std::memory_order_acquire));
    out[0] = f; out[1] = t; out[2] = mDeviceRate.load(std::memory_order_relaxed);
}
bool AudioEngine::queuePattern(int track, seq::Pattern* owned) {
    if (track < 0 || track >= seq::kTracks) { delete owned; return false; }
    Command c{CmdType::QueuePattern, track};
    c.ptr = owned;
    if (!mCmds.push(c)) { delete owned; return false; }
    return true;
}
bool AudioEngine::commitScene(int loopTicks, int tag) {
    if (loopTicks < 0 || loopTicks > 64 * 4 * seq::kPpqn) return false;
    Command c{CmdType::CommitScene, 0, loopTicks}; c.i1 = tag;
    return mCmds.push(c);
}
void AudioEngine::freeRetired() {
    while (const seq::Pattern* p = mSeq.retired()) { AudioEvent e; e.type = AudioEventType::PatternReleased; e.ptr = p; mAudioEvents.push(e); }
}
bool AudioEngine::setPattern(int track, seq::Pattern* owned) {
    if (!owned) return false;
    if (track < 0 || track >= seq::kTracks) { delete owned; return false; }
    Command c{CmdType::SetPattern, track};
    c.ptr = owned;
    if (!mCmds.push(c)) { delete owned; return false; }
    return true;
}
bool AudioEngine::repeatOn(int track, int note, int rate, float vel) {
    if (track < 0 || track >= seq::kTracks || note < 0 || note > 127) return false;
    Command c{CmdType::RepeatOn, track, note, std::clamp(vel, 0.f, 1.f)};
    c.i1 = std::clamp(rate, 40, 1920);
    return mCmds.push(c);
}
bool AudioEngine::repeatOff(int track, int note) { return mCmds.push({CmdType::RepeatOff, track, note}); }
bool AudioEngine::repeatVelocity(float v) { return mCmds.push({CmdType::RepeatVelocity, 0, 0, std::clamp(v, 0.f, 1.f)}); }
bool AudioEngine::setChannel(int strip, int param, float value) {
    if (strip < 0 || strip > kStrips || param < 0) return false;
    // range checks here, so bad values never reach the audio thread
    if (param >= (int)ChannelParam::LimBase) {
        master::LimSettings probe;
        if (strip != kStrips || !master::set(probe, param - (int)ChannelParam::LimBase, value)) return false;
    } else if (param >= (int)ChannelParam::GlueBase) {
        master::GlueSettings probe;
        if (strip != kStrips || !master::set(probe, param - (int)ChannelParam::GlueBase, value)) return false;
    } else if (param >= (int)ChannelParam::EqBase) {
        eq::Settings probe;
        if (!eq::set(probe, param - (int)ChannelParam::EqBase, value)) return false;
    } else if (param > 6 || (strip == kStrips && param != (int)ChannelParam::VolumeDb)) return false;
    return mCmds.push({CmdType::SetChannel, strip, param, value});
}

bool AudioEngine::fxOn(int id, float x, float y) {
    if (id < 0 || id >= fx::kCount) return false;
    Command c{CmdType::FxOn, 0, id, std::clamp(x, 0.f, 1.f)}; c.f1 = std::clamp(y, 0.f, 1.f);
    return mCmds.push(c);
}
bool AudioEngine::fxMove(int id, float x, float y) {
    if (id < 0 || id >= fx::kCount) return false;
    Command c{CmdType::FxMove, 0, id, std::clamp(x, 0.f, 1.f)}; c.f1 = std::clamp(y, 0.f, 1.f);
    return mCmds.push(c);
}
bool AudioEngine::fxOff(int id) { return id >= 0 && id < fx::kCount && mCmds.push({CmdType::FxOff, 0, id}); }
bool AudioEngine::fxAllOff() { return mCmds.push({CmdType::FxAllOff}); }

int AudioEngine::pollRecord(double* out) {
    if (mRecQueue.empty()) return 0;
    const Rec r = mRecQueue.front();
    mRecQueue.pop_front();
    out[0] = r.track; out[1] = r.note; out[2] = r.vel; out[3] = r.tick;
    return r.type;
}

void AudioEngine::getTransport(double* out) {
    out[0] = mTelTick.load(std::memory_order_relaxed);
    const int f = mTelFlags.load(std::memory_order_relaxed);
    out[1] = f & 1; out[2] = (f >> 1) & 1; out[11] = (f >> 2) & 1;
    out[3] = mTelBpm.load(std::memory_order_relaxed);
    for (int i = 0; i < 6; ++i) out[4 + i] = 0;   // meters moved to getMeters()
    out[4] = mTelQueueTag.load(std::memory_order_relaxed);   // scene queued to change (-1 none)
    out[5] = mTelQueueAt.load(std::memory_order_relaxed);    // … at this tick
    out[10] = mTelSwing.load(std::memory_order_relaxed);
    out[12] = mTelFx.load(std::memory_order_relaxed);
    out[13] = (double)mTelPads.load(std::memory_order_relaxed);
    out[14] = mTelPlayPad.load(std::memory_order_relaxed);
    out[15] = mTelEchoTail.load(std::memory_order_relaxed);   // FX tails (echo / reverb) for the pads

    out[16] = mTelOrigin.load(std::memory_order_relaxed);    // the current scene started at this tick
    out[17] = mTelScene.load(std::memory_order_relaxed);     // … and this is its tag
    out[18] = (double)mTelDrums.load(std::memory_order_relaxed);
    out[19] = mTelRevTail.load(std::memory_order_relaxed);
    out[20] = mTelPreroll.load(std::memory_order_relaxed);   // count-in ticks still to go (0 = none / done)
}

bool AudioEngine::drumTrigger(int track, int slot, float vel) {
    if (!validTrack(track) || slot < 0 || slot >= drums::kSlots) return false;
    Command c{CmdType::DrumTrigger, slot, 0, std::clamp(vel, 0.f, 1.f)}; c.i1 = track;
    return mCmds.push(c);
}
bool AudioEngine::drumParam(int track, int slot, int id, float v) {
    if (!validTrack(track) || !std::isfinite(v)) return false;
    const bool bus = slot == drums::kBusSlot && id >= 0 && id < drums::kBusParamCount;
    if (!bus && (slot < 0 || slot >= drums::kSlots || id < 0 || id >= drums::kParamCount)) return false;
    Command c{CmdType::DrumParam, slot, id, v}; c.i1 = track;
    return mCmds.push(c);
}
void AudioEngine::getDrumTelemetry(int track, float* levels, int32_t* hits) {
    if (!validTrack(track)) return;
    for (int s = 0; s < drums::kSlots; ++s) {
        levels[s] = mDrumLevel[track * drums::kSlots + s].load(std::memory_order_relaxed);
        hits[s] = mDrumHits[track * drums::kSlots + s].load(std::memory_order_relaxed);
    }
}

bool AudioEngine::setPadRegion(int pad, int64_t s, int64_t e) {
    if (pad < 0 || pad >= kMaxPads || s < 0 || s > INT32_MAX || e > INT32_MAX) return false;
    Command c{CmdType::SetPadRegion, pad, (int32_t)s}; c.i1 = (int32_t)e;
    return mCmds.push(c);
}
bool AudioEngine::setPadParam(int pad, int param, float v) {
    if (pad < 0 || pad >= kMaxPads || param < 0 || param > (int)PadParam::ChokeAll || !std::isfinite(v)) return false;
    return mCmds.push({CmdType::SetPadParam, pad, param, v});
}
bool AudioEngine::setDuck(int param, float v) {
    if (param < 0 || param >= duck::kCount || !std::isfinite(v)) return false;
    return mCmds.push({CmdType::SetDuck, 0, param, v});
}
void AudioEngine::getDuck(float* out) {
    out[0] = mDuckGrMax.exchange(0.f, std::memory_order_relaxed);
    out[1] = mDuckGrNow.load(std::memory_order_relaxed);
    out[2] = mDuckKeyPk.exchange(0.f, std::memory_order_relaxed);
    out[3] = (float)mDuckTrig.load(std::memory_order_relaxed);
}
bool AudioEngine::stopPads(int track) {
    if (track < -1 || track >= kTracks) return false;
    return mCmds.push({CmdType::StopPads, track, 0, 0.f});
}
bool AudioEngine::setPadGain(int pad, float g) {
    if (pad < 0 || pad >= kMaxPads) return false;
    return mCmds.push({CmdType::SetPadGain, pad, 0, std::clamp(g, 0.f, 16.f)});
}

int AudioEngine::transients(int sampleId, float sensitivity, int maxSlices, int64_t* out) {
    std::shared_ptr<SampleBuffer> buf;
    { std::lock_guard<std::mutex> l(mRegMtx); auto it = mRegistry.find(sampleId); if (it == mRegistry.end()) return 0; buf = it->second.buf; }
    return detectTransients(buf->data.data(), buf->frames, buf->channels, buf->sampleRate, sensitivity, maxSlices, out);
}

bool AudioEngine::setMasterGainDb(float db) {
    return mCmds.push({CmdType::SetMasterGain, 0, 0, std::pow(10.f, db / 20.f), nullptr});
}

// ================= Sample management =================

void AudioEngine::loadSampleAsync(int fd, int requestId) {
    std::vector<int> dropped;
    {
        std::lock_guard<std::mutex> l(mJobMtx);
        // Keep at most 2 pending decodes; older taps are superseded anyway.
        while (mJobs.size() >= 2) { if (mJobs.front().fd >= 0) close(mJobs.front().fd); dropped.push_back(mJobs.front().requestId); mJobs.pop_front(); }
        mJobs.push_back({fd, requestId});
    }
    mJobCv.notify_one();
    if (!dropped.empty()) {
        std::lock_guard<std::mutex> l(mResultMtx);
        for (int id : dropped) { WorkerResult r; r.requestId = id; mResults.push_back(r); }   // reported as failed
    }
}

void AudioEngine::stretchSampleAsync(int sampleId, double tempo, double semis, int requestId, int64_t cropStart, int64_t cropEnd) {
    { std::lock_guard<std::mutex> l(mJobMtx); Job j{-1, requestId, sampleId, tempo, semis, cropStart, cropEnd}; mJobs.push_back(j); }
    mJobCv.notify_one();
}

void AudioEngine::workerLoop() {
    struct JvmThread {
        JvmThread() { if (sThreadAttach) sThreadAttach(); }
        ~JvmThread() { if (sThreadDetach) sThreadDetach(); }
    } jvm;
    for (;;) {
        Job job{};
        {
            std::unique_lock<std::mutex> l(mJobMtx);
            mJobCv.wait(l, [this] { return mQuit || !mJobs.empty(); });
            if (mQuit) {                                    // close any fds we still own
                for (auto& j : mJobs) if (j.fd >= 0) close(j.fd);
                mJobs.clear();
                return;
            }
            job = mJobs.front();
            mJobs.pop_front();
        }

        WorkerResult res;
        res.requestId = job.requestId;
        const char* err = nullptr;
        std::unique_ptr<SampleBuffer> decoded;
        bool analyse = true;
        if (job.srcSample > 0) {                            // stretch job: render from the source buffer
            std::shared_ptr<SampleBuffer> src;
            { std::lock_guard<std::mutex> l(mRegMtx); auto it = mRegistry.find(job.srcSample); if (it != mRegistry.end()) src = it->second.buf; }
            if (src) {
                decoded = std::make_unique<SampleBuffer>();
                decoded->sampleRate = src->sampleRate; decoded->channels = src->channels;
                if (job.cropEnd > job.cropStart && job.cropStart >= 0 && job.cropEnd <= src->frames) {
                    // one slice only (per-pad WARP): stretch just [cropStart, cropEnd)
                    const auto ch = (int64_t)src->channels;
                    std::vector<float> part(src->data.begin() + (ptrdiff_t)(job.cropStart * ch), src->data.begin() + (ptrdiff_t)(job.cropEnd * ch));
                    decoded->data = stretch::process(part, src->channels, src->sampleRate, job.tempo, job.semis);
                } else {
                    decoded->data = stretch::process(src->data, src->channels, src->sampleRate, job.tempo, job.semis);
                }
                decoded->frames = (int64_t)(decoded->data.size() / src->channels);
                analyse = false;                            // the UI derives key/BPM from the original
            }
        } else {
            mProgFrac.store(0.f); mProgStage.store(1); mProgReq.store(job.requestId);
            DecodeProgress prog;
            const int req = job.requestId;
            prog.report = [this, req](float f) { mProgFrac.store(f, std::memory_order_relaxed); return mCancelReq.load(std::memory_order_relaxed) != req; };
            decoded = decodeAudioFd(job.fd, &err, &prog);   // closes fd
            res.truncated = prog.truncated;
            res.sourceSeconds = prog.sourceSeconds;
            if (decoded && analyse) { mProgStage.store(2); mProgFrac.store(-1.f); }
        }
        if (decoded) {
            if (analyse) res.analysis = analyzeAudio(decoded->data.data(), decoded->frames, decoded->channels, decoded->sampleRate);
            res.frames = decoded->frames;
            res.sampleRate = decoded->sampleRate;
            res.channels = decoded->channels;
            std::lock_guard<std::mutex> l(mRegMtx);
            // Take the id BEFORE moving the buffer. In `mRegistry[decoded->id] = Entry{move(decoded)}` C++17
            // evaluates the right side first, so decoded is already null when the key is read: a
            // guaranteed null dereference, which the optimiser turned into a crash on every sample load.
            const int id = mNextSampleId++;
            decoded->id = id;
            res.sampleId = id;
            mRegistry[id] = RegistryEntry{std::shared_ptr<SampleBuffer>(std::move(decoded)), 0, false};
        }
        else res.error = decodeErrorCode(err);
        if (job.srcSample <= 0 && mProgReq.load() == job.requestId) { mProgStage.store(0); mProgReq.store(0); mProgFrac.store(-1.f); }
        std::lock_guard<std::mutex> l(mResultMtx);
        mResults.push_back(res);
    }
}

bool AudioEngine::assignSample(int pad, int sampleId) {
    if (pad < 0 || pad >= kMaxPads) return false;
    const SampleBuffer* raw = nullptr;
    std::lock_guard<std::mutex> l(mRegMtx);
    if (sampleId != 0) {
        auto it = mRegistry.find(sampleId);
        if (it == mRegistry.end() || it->second.pendingDelete) return false;
        raw = it->second.buf.get();
        it->second.audioRefs++;   // held until the audio thread reports BufferReleased
    }
    if (!mCmds.push({CmdType::AssignSample, pad, 0, 0.f, raw})) {
        if (sampleId != 0) mRegistry[sampleId].audioRefs--;
        return false;
    }
    return true;
}

void AudioEngine::unloadSample(int sampleId) {
    std::lock_guard<std::mutex> l(mRegMtx);
    auto it = mRegistry.find(sampleId);
    if (it == mRegistry.end()) return;
    if (it->second.audioRefs == 0) mRegistry.erase(it);   // freed here, on the main thread
    else it->second.pendingDelete = true;                 // freed when the audio thread lets go
}

void AudioEngine::releaseRef(int sampleId) {
    std::lock_guard<std::mutex> l(mRegMtx);
    auto it = mRegistry.find(sampleId);
    if (it == mRegistry.end()) return;
    if (--it->second.audioRefs <= 0 && it->second.pendingDelete) mRegistry.erase(it);
}

// ================= Polling =================

int AudioEngine::pollEvent(double* out) {
    AudioEvent ev;
    while (mAudioEvents.pop(ev)) {
        switch (ev.type) {
            case AudioEventType::BufferReleased: releaseRef(ev.i0); break;
            case AudioEventType::PatternReleased: delete static_cast<const seq::Pattern*>(ev.ptr); break;
            case AudioEventType::NoteRecorded:
                if (mRecQueue.size() < 4096) mRecQueue.push_back({1, ev.i0, ev.i1, ev.f0, ev.tick});
                break;
            case AudioEventType::NoteReleased:
                if (mRecQueue.size() < 4096) mRecQueue.push_back({2, ev.i0, ev.i1, 0.f, ev.tick});
                break;
        }
    }
    std::lock_guard<std::mutex> l(mResultMtx);
    if (mResults.empty()) return 0;
    WorkerResult r = mResults.front();
    mResults.pop_front();
    out[0] = r.requestId;
    out[1] = r.sampleId != 0 ? r.sampleId : r.error;   // a failure carries its DecodeError here
    out[2] = (double)r.frames;
    out[3] = r.sampleRate;
    out[4] = r.channels;
    out[5] = r.analysis.keyRoot;
    out[6] = r.analysis.keyMode;
    out[7] = r.analysis.keyConfidence;
    out[8] = r.analysis.bpm;
    out[9] = r.analysis.bpmConfidence;
    out[10] = r.truncated ? 1.0 : 0.0;
    out[11] = r.sourceSeconds;
    return r.sampleId != 0 ? 1 : 2;
}

bool AudioEngine::getPeaks(int sampleId, int buckets, float* out) {
    std::shared_ptr<SampleBuffer> buf;
    {
        std::lock_guard<std::mutex> l(mRegMtx);
        auto it = mRegistry.find(sampleId);
        if (it == mRegistry.end()) return false;
        buf = it->second.buf;                  // keep it alive while we read, without holding the lock
    }
    if (buckets <= 0 || buf->frames <= 0) return false;
    for (int b = 0; b < buckets; ++b) {
        const int64_t a = buf->frames * b / buckets;
        const int64_t e = std::max(a + 1, buf->frames * (b + 1) / buckets);
        float mn = 0.f, mx = 0.f;
        for (int64_t f = a; f < e && f < buf->frames; ++f) {
            float s = 0.f;
            for (int c = 0; c < buf->channels; ++c) s += buf->at(c, f);
            s /= (float)buf->channels;
            mn = std::min(mn, s); mx = std::max(mx, s);
        }
        out[2 * b] = mn; out[2 * b + 1] = mx;
    }
    return true;
}

// [min, max, rms] per bucket of the mono mix: the waveform view draws the peaks as the outline
// and the RMS as the solid body (what you hear as loudness).
bool AudioEngine::getWave(int sampleId, int buckets, float* out, int64_t from, int64_t to) {
    std::shared_ptr<SampleBuffer> buf;
    {
        std::lock_guard<std::mutex> l(mRegMtx);
        auto it = mRegistry.find(sampleId);
        if (it == mRegistry.end()) return false;
        buf = it->second.buf;
    }
    if (buckets <= 0 || buf->frames <= 0) return false;
    if (to < 0 || to > buf->frames) to = buf->frames;
    from = std::clamp<int64_t>(from, 0, std::max<int64_t>(0, to - 1));
    const int64_t span = std::max<int64_t>(1, to - from);
    const int ch = buf->channels;
    const float inv = 1.f / (float)ch;
    const float* d = buf->data.data();
    for (int b = 0; b < buckets; ++b) {
        const int64_t a = from + span * b / buckets;
        const int64_t e = std::min(to, std::max(a + 1, from + span * (b + 1) / buckets));
        float mn = 0.f, mx = 0.f; double sq = 0.0;
        for (int64_t f = a; f < e; ++f) {
            float s = d[f * ch];
            if (ch == 2) s = (s + d[f * ch + 1]) * 0.5f; else for (int c = 1; c < ch; ++c) s += d[f * ch + c] * inv;
            mn = std::min(mn, s); mx = std::max(mx, s); sq += (double)s * s;
        }
        out[3 * b] = mn; out[3 * b + 1] = mx; out[3 * b + 2] = (float)std::sqrt(sq / (double)std::max<int64_t>(1, e - a));
    }
    return true;
}

bool AudioEngine::regionLevels(int sampleId, const int64_t* starts, const int64_t* ends, int n, float* outPeakDb, float* outLoudDb) {
    std::shared_ptr<SampleBuffer> buf;
    {
        std::lock_guard<std::mutex> l(mRegMtx);
        auto it = mRegistry.find(sampleId);
        if (it == mRegistry.end()) return false;
        buf = it->second.buf;
    }
    if (!buf || buf->frames <= 0) return false;
    const int64_t win = std::max<int64_t>(64, (int64_t)(0.05 * buf->sampleRate)), hop = win / 2;
    auto toDb = [](double v) { return v > 1e-9 ? (float)(20.0 * std::log10(v)) : -120.f; };
    for (int i = 0; i < n; ++i) {
        const int64_t a = std::clamp<int64_t>(starts[i], 0, buf->frames), e = std::clamp<int64_t>(ends[i], a, buf->frames);
        double pk = 0.0, best = 0.0;
        for (int64_t w0 = a; w0 < e; w0 += hop) {
            const int64_t w1 = std::min(e, w0 + win);
            double ss = 0.0;
            for (int64_t f = w0; f < w1; ++f) {
                float s = 0.f;
                for (int c = 0; c < buf->channels; ++c) s += buf->at(c, f);
                s /= (float)buf->channels;
                ss += (double)s * s;
                pk = std::max(pk, (double)std::fabs(s));
            }
            if (w1 > w0) best = std::max(best, ss / (double)(w1 - w0));
            if (w1 >= e) break;
        }
        outPeakDb[i] = toDb(pk);
        outLoudDb[i] = toDb(std::sqrt(best));
    }
    return true;
}

void AudioEngine::getSynthTelemetry(float* out, int64_t* notes) {
    for (int i = 0; i < kTel; ++i) out[i] = mTel[i].load(std::memory_order_relaxed);
    notes[0] = (int64_t)mTelNotes[0].load(std::memory_order_relaxed);
    notes[1] = (int64_t)mTelNotes[1].load(std::memory_order_relaxed);
}

int AudioEngine::getScope(float* out, int n) {
    n = std::clamp(n, 0, (int)kScope);
    const uint32_t w = mScopeWrite.load(std::memory_order_acquire);
    for (int i = 0; i < n; ++i) out[i] = mScope[(w - (uint32_t)n + (uint32_t)i) & (kScope - 1)].load(std::memory_order_relaxed);
    return n;
}

void AudioEngine::getStats(double* out) {
    std::memset(out, 0, sizeof(double) * 8);
    std::lock_guard<std::mutex> l(mStreamMtx);
    if (!mStream) return;
    out[0] = 1;
    out[1] = mStream->getSampleRate();
    out[2] = mStream->getFramesPerBurst();
    out[3] = mStream->getBufferSizeInFrames();
    auto lat = mStream->calculateLatencyMillis();
    out[4] = lat ? lat.value() : -1.0;
    auto xr = mStream->getXRunCount();
    out[5] = xr ? xr.value() : -1;
    out[6] = mStream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
    out[7] = mStream->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 0;
}
