// SPDX-License-Identifier: GPL-3.0-or-later
#include "AudioEngine.h"
#include "Decoder.h"
#include "Stretch.h"
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>
#include <climits>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ThorboxEngine", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ThorboxEngine", __VA_ARGS__)

AudioEngine::AudioEngine() {
    for (auto& g : mScGain) g.store(1.f, std::memory_order_relaxed);
    mWorker = std::thread([this] { workerLoop(); });
}

AudioEngine::~AudioEngine() {
    stop();
    for (int t = 0; t < seq::kTracks; ++t) delete mSeq.setPattern(t, nullptr);   // stream is closed: safe
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
    if (strip < 0 || strip > kStrips || param < 0 || param > 6) return false;
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
    out[10] = mTelSwing.load(std::memory_order_relaxed);
    out[12] = mTelFx.load(std::memory_order_relaxed);
    out[13] = (double)mTelPads.load(std::memory_order_relaxed);
    out[14] = mTelPlayPad.load(std::memory_order_relaxed);
    out[15] = mTelEchoTail.load(std::memory_order_relaxed);   // FX tails (echo / reverb) for the pads

    out[16] = 0; out[17] = 0;
    out[18] = (double)mTelDrums.load(std::memory_order_relaxed);
    out[19] = mTelRevTail.load(std::memory_order_relaxed);
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
bool AudioEngine::getWave(int sampleId, int buckets, float* out) {
    std::shared_ptr<SampleBuffer> buf;
    {
        std::lock_guard<std::mutex> l(mRegMtx);
        auto it = mRegistry.find(sampleId);
        if (it == mRegistry.end()) return false;
        buf = it->second.buf;
    }
    if (buckets <= 0 || buf->frames <= 0) return false;
    const int ch = buf->channels;
    const float inv = 1.f / (float)ch;
    const float* d = buf->data.data();
    for (int b = 0; b < buckets; ++b) {
        const int64_t a = buf->frames * b / buckets;
        const int64_t e = std::min(buf->frames, std::max(a + 1, buf->frames * (b + 1) / buckets));
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

// ================= Audio thread =================

SamplerVoice* AudioEngine::allocVoice() {
    SamplerVoice* oldest = &mVoices[0];
    for (auto& v : mVoices) {
        if (!v.active()) return &v;
        if (v.startedAt() < oldest->startedAt()) oldest = &v;
    }
    return oldest;   // voice steal (hard cut; M2 adds a stolen-voice fade)
}

void AudioEngine::handleCommand(const Command& c) {
    const float sr = (float)mDeviceRate.load(std::memory_order_relaxed);
    switch (c.type) {
        case CmdType::NoteOn:
            startPad(c.pad, c.f0, sr);
            if (c.pad % kPadsPerTrack != kPreviewPad)
                recordEvent(AudioEventType::NoteRecorded, c.pad / kPadsPerTrack, c.pad % kPadsPerTrack, c.f0, mSeq.tick());
            break;
        case CmdType::NoteOff:
            releasePad(c.pad, sr);
            if (c.pad % kPadsPerTrack != kPreviewPad)
                recordEvent(AudioEventType::NoteReleased, c.pad / kPadsPerTrack, c.pad % kPadsPerTrack, 0.f, mSeq.tick());
            break;
        case CmdType::SetPitch: {
            const float ratio = std::pow(2.f, ((float)c.i0 + c.f0 / 100.f) / 12.f);
            mPads[c.pad].pitchRatio = ratio;
            for (auto& v : mVoices) if (v.active() && v.pad() == c.pad) v.setPitchRatio(ratio);   // live repitch
            break;
        }
        case CmdType::SetPlayMode:
            mPads[c.pad].mode = (PlayMode)c.i0;
            break;
        case CmdType::SynthNoteOn:
            mSynths[c.pad].noteOn(c.i0, c.f0);
            recordEvent(AudioEventType::NoteRecorded, c.pad, c.i0, c.f0, mSeq.tick());
            break;
        case CmdType::SynthNoteOff:
            mSynths[c.pad].noteOff(c.i0);
            recordEvent(AudioEventType::NoteReleased, c.pad, c.i0, 0.f, mSeq.tick());
            break;
        case CmdType::SetTempo: mSeq.setTempo(c.f0); break;
        case CmdType::SetSwing: mSeq.setSwing(c.f0, c.i0); break;
        case CmdType::SetPlaying:
            mSeq.setPlaying(c.i0 != 0);
            break;
        case CmdType::SetRecording: mRecording = c.i0 != 0; break;
        case CmdType::SetMetronome: mMetroOn = c.i0 != 0; mSeq.setMetronome(mMetroOn); break;
        case CmdType::SetPattern: {
            const seq::Pattern* old = mSeq.setPattern(c.pad, static_cast<const seq::Pattern*>(c.ptr));
            if (old) { AudioEvent e; e.type = AudioEventType::PatternReleased; e.ptr = old; mAudioEvents.push(e); }  // full queue → leak, never UAF
            break;
        }
        case CmdType::RepeatOn: mSeq.repeatOn(c.pad, c.i0, c.i1, c.f0); break;
        case CmdType::RepeatOff: mSeq.repeatOff(c.pad, c.i0); break;
        case CmdType::RepeatVelocity: mSeq.setRepeatVelocity(c.f0); break;
        case CmdType::SetPadRegion: mPads[c.pad].start = c.i0; mPads[c.pad].end = c.i1; break;
        case CmdType::SetPadGain: mPads[c.pad].gain = c.f0; break;
        case CmdType::SetPadParam: {
            PadState& p = mPads[c.pad];
            const float v = c.f0;
            switch ((PadParam)c.i0) {
                case PadParam::Pan:         p.opts.pan = std::clamp(v, -1.f, 1.f); break;
                case PadParam::AttackMs:    p.opts.attackMs = std::clamp(v, 1.f, 5000.f); break;
                case PadParam::DecayMs:     p.opts.decayMs = std::clamp(v, 0.f, 20000.f); break;
                case PadParam::ReleaseMs:   p.opts.releaseMs = std::clamp(v, 1.f, 5000.f); break;
                case PadParam::Cutoff:      p.opts.cutoff = std::clamp(v, 0.f, 1.f); break;
                case PadParam::Reso:        p.opts.reso = std::clamp(v, 0.f, 1.f); break;
                case PadParam::Reverse:     p.opts.reverse = v >= 0.5f; break;
                case PadParam::MuteGroup:   p.muteGroup = std::clamp((int)std::lround(v), 0, 16); break;
                case PadParam::MuteTargetA: p.muteA = std::clamp((int)std::lround(v), -1, kPreviewPad - 1); break;
                case PadParam::MuteTargetB: p.muteB = std::clamp((int)std::lround(v), -1, kPreviewPad - 1); break;
                case PadParam::Poly:        p.poly = v >= 0.5f; break;
                case PadParam::VelSens:     p.velSens = std::clamp(v, 0.f, 1.f); break;
                case PadParam::ChokeAll:    p.chokeAll = v >= 0.5f; break;
            }
            break;
        }
        case CmdType::StopPads:
            for (auto& v : mVoices)
                if (v.active() && !v.releasing() && (c.pad < 0 || v.pad() / kPadsPerTrack == c.pad)) v.release(25.f, sr);
            break;
        case CmdType::FxOn:   mFx.engage(c.i0, c.f0, c.f1); break;
        case CmdType::FxMove: mFx.move(c.i0, c.f0, c.f1); break;
        case CmdType::FxOff:  mFx.release(c.i0); break;
        case CmdType::FxAllOff: mFx.releaseAll(); break;
        case CmdType::DrumTrigger: mDrums[c.i1].trigger(c.pad, c.f0); recordEvent(AudioEventType::NoteRecorded, c.i1, c.pad, c.f0, mSeq.tick()); break;
        case CmdType::DrumParam: mDrums[c.i1].setParam(c.pad, c.i0, c.f0); break;
        case CmdType::SetTrackType: {
            const TrackType t = (TrackType)c.i0;
            if (mTrackType[c.pad] == t) break;
            // Silence whatever the track was playing before it changes instrument.
            for (auto& v : mVoices) if (v.active() && v.pad() / kPadsPerTrack == c.pad) v.release(5.f, sr);
            mSynths[c.pad].allNotesOff();
            mTrackType[c.pad] = t;
            break;
        }
        case CmdType::SetFocus:
            if (mTrackType[c.pad] == TrackType::Synth) mFocusSynth = c.pad;
            else if (mTrackType[c.pad] == TrackType::Sampler) mFocusSampler = c.pad;
            break;
        case CmdType::SetChannel: {
            if (c.pad == kStrips) {                       // master: volume only
                if (c.i0 == (int)ChannelParam::VolumeDb) mMasterGainTarget = std::pow(10.f, std::clamp(c.f0, -60.f, 6.f) / 20.f);
                break;
            }
            Strip& s = mStrips[c.pad];
            switch ((ChannelParam)c.i0) {
                case ChannelParam::VolumeDb: s.volDb = std::clamp(c.f0, -60.f, 6.f); break;
                case ChannelParam::Pan: s.pan = std::clamp(c.f0, -1.f, 1.f); break;
                case ChannelParam::Mute: s.mute = c.f0 != 0.f; break;
                case ChannelParam::Solo: s.solo = c.f0 != 0.f; break;
                case ChannelParam::ScSource: { const int src = (int)c.f0; s.scSrc = (src >= 0 && src < kStrips && src != c.pad) ? src : -1; break; }
                case ChannelParam::ScAmount: s.scAmount = std::clamp(c.f0, 0.f, 1.f); break;
                case ChannelParam::ScRelease: s.scReleaseMs = std::clamp(c.f0, 20.f, 1000.f); break;
            }
            break;
        }
        case CmdType::SynthParam:   mSynths[c.pad].setParam(c.i0, c.f0); break;
        case CmdType::SynthAllOff:  mSynths[c.pad].allNotesOff(); break;
        case CmdType::SetMasterGain:
            mMasterGainTarget = c.f0;
            break;
        case CmdType::AssignSample: {
            PadState& p = mPads[c.pad];
            const SampleBuffer* old = p.buf;
            // Voices on this pad may be reading the old buffer: stop them before giving it back.
            for (auto& v : mVoices) if (v.active() && v.pad() == c.pad) v.kill();
            p.buf = c.buf;
            if (old) {
                // If this push ever failed, the buffer would just leak (never be freed while in use).
                // That's safe: leaking is better than a use-after-free. 256 slots is far more than needed.
                mAudioEvents.push({AudioEventType::BufferReleased, old->id});
            }
            break;
        }
    }
}

void AudioEngine::startPad(int pad, float vel, float sr) {
    if (pad < 0 || pad >= kMaxPads) return;
    PadState& p = mPads[pad];
    if (!p.buf) return;
    // MONO pads (the default, like classic samplers): a retrigger fades the previous hit out over 4 ms.
    // POLY pads let hits overlap.
    if (!p.poly)
        for (auto& v : mVoices)
            if (v.active() && v.pad() == pad && !v.releasing()) v.release(4.f, sr);
    // Mute group: every other pad on this track in the same group is cut (open / closed hat).
    // Mute targets: up to two specific pads this one cuts.
    const int track = pad / kPadsPerTrack, local = pad % kPadsPerTrack;
    // CHOKE ALL (the track's "one at a time" setting): every other pad on this track is cut.
    if (p.muteGroup > 0 || p.muteA >= 0 || p.muteB >= 0 || p.chokeAll) {
        for (auto& v : mVoices) {
            if (!v.active() || v.releasing() || v.pad() == pad || v.pad() / kPadsPerTrack != track) continue;
            const int other = v.pad() % kPadsPerTrack;
            if (other == kPreviewPad) continue;
            const bool grouped = p.muteGroup > 0 && mPads[v.pad()].muteGroup == p.muteGroup;
            if (p.chokeAll || grouped || other == p.muteA || other == p.muteB) v.release(10.f, sr);
        }
    }
    (void)local;
    const float curve = 0.1f + 0.9f * vel * vel;   // a gentle curve: soft taps stay audible
    const float gain = 1.f + (curve - 1.f) * p.velSens;
    allocVoice()->start(p.buf, pad, p.pitchRatio, gain * p.gain, p.mode, sr, ++mVoiceStamp, p.start, p.end, p.opts);
}

void AudioEngine::releasePad(int pad, float sr) {
    for (auto& v : mVoices)
        if (v.active() && v.pad() == pad && v.mode() != PlayMode::OneShot && !v.releasing())
            v.release(sr);                                // the pad's own release time
}

// Live hits are recorded only while recording AND playing. They're also registered with the
// sequencer so the quantized copy doesn't double-trigger later in the same pass.
void AudioEngine::recordEvent(AudioEventType t, int track, int note, float vel, double tick) {
    if (!mRecording || !mSeq.playing()) return;
    if (t == AudioEventType::NoteRecorded) mSeq.noteLiveHit(track, note);
    AudioEvent e; e.type = t; e.i0 = track; e.i1 = note; e.f0 = vel; e.tick = tick;
    mAudioEvents.push(e);   // full queue → the hit just isn't recorded; playback is unaffected
}

void AudioEngine::applyFire(const seq::Fire& f, double tickAtChunk, double tps, float sr) {
    if (f.track < 0) {                                // metronome click
        mClickEnv = 1.f; mClickPhase = 0.f; mClickFreq = f.note ? 2400.f : 1600.f;
        return;
    }
    const TrackType type = (f.track >= 0 && f.track < kTracks) ? mTrackType[f.track] : TrackType::None;
    if (type == TrackType::Sampler) {
        if (f.note >= 0 && f.note < kPreviewPad) {
            const int pad = f.track * kPadsPerTrack + f.note;
            if (f.on) startPad(pad, f.velocity, sr); else releasePad(pad, sr);
        }
    }
    else if (type == TrackType::Drums) { if (f.on) mDrums[f.track].trigger(f.note, f.velocity); }   // one-shots: note-offs ignored
    else if (type == TrackType::Synth) {
        synth::Synth& mSynth = mSynths[f.track];
        if (f.on && f.src) {
            // Pattern hit: its p-locks apply now and hold until the next step without them.
            bool any = false;
            for (int i = 0; i < seq::kLocks; ++i) any |= f.src->lockId[i] >= 0;
            if (any) { mSynth.unlockAll(); for (int i = 0; i < seq::kLocks; ++i) if (f.src->lockId[i] >= 0) mSynth.lockParam(f.src->lockId[i], f.src->lockVal[i]); }
            else mSynth.unlockAll();
        }
        if (f.on) mSynth.noteOn(f.note, f.velocity); else mSynth.noteOff(f.note);
    }
    else return;
    if (f.fromRepeat) recordEvent(AudioEventType::NoteRecorded, f.track, f.note, f.velocity, tickAtChunk + f.offset * tps);
}

void AudioEngine::renderSegment(int a, int b, float sr, uint32_t& w) {
    const int n = b - a;
    if (n <= 0) return;
    for (auto& v : mVoices) if (v.active()) v.render(mBus[v.pad() / kPadsPerTrack].data() + 2 * a, n, sr);
    bool scoped = false;
    for (int t = 0; t < kTracks; ++t) {
        if (mTrackType[t] == TrackType::Synth) {
            const bool focus = t == mFocusSynth;
            mSynths[t].render(mBus[t].data() + 2 * a, focus ? mSynthScratch.data() + a : nullptr, n);
            scoped |= focus;
        } else if (mTrackType[t] == TrackType::Drums) {
            mDrums[t].render(mBus[t].data() + 2 * a, n);
        }
    }
    if (!scoped) std::fill(mSynthScratch.begin() + a, mSynthScratch.begin() + b, 0.f);
    for (int i = 0; i < n; ++i) mScope[(w++) & (kScope - 1)].store(mSynthScratch[a + i], std::memory_order_relaxed);
    if (mClickEnv > 1e-4f) {
        const float dec = std::exp(-1.f / (0.012f * sr)), inc = mClickFreq / sr;
        for (int i = 0; i < n; ++i) {
            const float s = synth::fastSin(synth::kTwoPi * mClickPhase - synth::kPi) * -0.25f * mClickEnv;
            mClickPhase += inc; if (mClickPhase >= 1.f) mClickPhase -= 1.f;
            mClickEnv *= dec;
            mClickBus[2 * (a + i)] += s; mClickBus[2 * (a + i) + 1] += s;
        }
    }
}

// Channel strips → master. Gains are smoothed per sample (~5 ms) so fader moves and mutes never click.
void AudioEngine::mixChunk(float* out, int n, float sr, double tick, double tps) {
    bool anySolo = false;
    for (auto& s : mStrips) anySolo |= s.solo;
    const float coeff = 1.f - std::exp(-1.f / (0.005f * sr));
    const float mcoeff = 1.f - std::exp(-1.f / (0.01f * sr));
    // Sidechain ("pump"): each ducked strip follows its source strip's PRE-fader level (instant
    // attack, release ms) and turns the gain down by up to `amount`. Keys are read from the raw
    // buses before any ducking, so ducking order never matters.
    for (int k = 0; k < kStrips; ++k) {
        Strip& s = mStrips[k];
        if (s.scSrc < 0 || s.scAmount <= 0.f) { s.scEnv = 0.f; continue; }
        const float* key = mBus[s.scSrc].data();
        const float rel = std::exp(-1.f / (s.scReleaseMs * 0.001f * sr));
        float env = s.scEnv, gmin = s.scGainMin;
        float* bus = mBus[k].data();
        for (int i = 0; i < n; ++i) {
            const float x = std::max(std::fabs(key[2 * i]), std::fabs(key[2 * i + 1]));
            env = x > env ? x : env * rel;
            const float g = 1.f - s.scAmount * std::min(1.f, env * 2.5f);
            gmin = std::min(gmin, g);
            bus[2 * i] *= g; bus[2 * i + 1] *= g;
        }
        s.scEnv = env; s.scGainMin = gmin;
    }
    for (int k = 0; k < kStrips; ++k) {
        Strip& s = mStrips[k];
        const bool audible = !s.mute && (!anySolo || s.solo);
        const float lin = audible ? std::pow(10.f, s.volDb / 20.f) : 0.f;
        const float ang = (s.pan + 1.f) * synth::kPi * 0.25f;       // equal-power, unity at centre
        const float tL = lin * std::cos(ang) * 1.41421356f, tR = lin * std::sin(ang) * 1.41421356f;
        const float* bus = mBus[k].data();
        float pL = s.peakL, pR = s.peakR;
        for (int i = 0; i < n; ++i) {
            s.gL += (tL - s.gL) * coeff; s.gR += (tR - s.gR) * coeff;
            const float l = bus[2 * i] * s.gL, r = bus[2 * i + 1] * s.gR;
            out[2 * i] += l; out[2 * i + 1] += r;
            pL = std::max(pL, std::fabs(l)); pR = std::max(pR, std::fabs(r));
        }
        s.peakL = pL; s.peakR = pR;
    }
    mFx.process(out, n, sr, mSeq.bpm(), tick, tps);   // punch-in FX: post-mix, pre master fader
    float mL = mMasterPeakL, mR = mMasterPeakR;
    uint32_t tw = mMasterTapWrite.load(std::memory_order_relaxed);
    for (int i = 0; i < n; ++i) {
        mMasterGain += (mMasterGainTarget - mMasterGain) * mcoeff;
        const float l = std::clamp(out[2 * i] * mMasterGain, -1.f, 1.f);
        const float r = std::clamp(out[2 * i + 1] * mMasterGain, -1.f, 1.f);
        mL = std::max(mL, std::fabs(l)); mR = std::max(mR, std::fabs(r));
        mMasterSide[tw & (kScope - 1)].store(0.5f * (l - r), std::memory_order_relaxed);
        mMasterTap[(tw++) & (kScope - 1)].store(0.5f * (l + r), std::memory_order_relaxed);
        mLoud.process(l, r);
        out[2 * i] = std::clamp(l + mClickBus[2 * i], -1.f, 1.f);         // click: monitoring only, after master
        out[2 * i + 1] = std::clamp(r + mClickBus[2 * i + 1], -1.f, 1.f);
    }
    mMasterPeakL = mL; mMasterPeakR = mR;
    mMasterTapWrite.store(tw, std::memory_order_release);
}

void AudioEngine::getVisual(float* bands, int nBands, float* wave, int nWave, float* energy, int nEnergy) {
    const uint32_t w = mMasterTapWrite.load(std::memory_order_acquire);
    for (int i = 0; i < viz::kFft; ++i)
        mVizIn[i] = mMasterTap[(w - (uint32_t)viz::kFft + (uint32_t)i) & (kScope - 1)].load(std::memory_order_relaxed);
    const float sr = (float)mDeviceRate.load(std::memory_order_relaxed);
    mSpectrum.analyze(mVizIn.data(), sr, bands, nBands, &energy[0], &energy[1], &energy[2],
                      nEnergy >= 5 ? &energy[3] : nullptr, nEnergy >= 5 ? &energy[4] : nullptr);
    nWave = std::clamp(nWave, 0, viz::kFft);
    for (int i = 0; i < nWave; ++i) wave[i] = mVizIn[viz::kFft - nWave + i];
}

void AudioEngine::getLoudness(float* out) const {
    out[0] = mLoud.momentary(); out[1] = mLoud.shortTerm(); out[2] = mLoud.integrated();
    out[3] = mLoud.peakDbL(); out[4] = mLoud.peakDbR(); out[5] = mLoud.seconds();
}

int AudioEngine::getStereo(float* mid, float* side, int n) const {
    n = std::clamp(n, 0, (int)kScope);
    const uint32_t w = mMasterTapWrite.load(std::memory_order_acquire);
    for (int i = 0; i < n; ++i) {
        const uint32_t k = (w - (uint32_t)n + (uint32_t)i) & (kScope - 1);
        mid[i] = mMasterTap[k].load(std::memory_order_relaxed);
        side[i] = mMasterSide[k].load(std::memory_order_relaxed);
    }
    return n;
}

static inline void atomicMax(std::atomic<float>& a, float v) {
    float cur = a.load(std::memory_order_relaxed);
    while (v > cur && !a.compare_exchange_weak(cur, v, std::memory_order_relaxed)) {}
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    if (mTuner) mTuner->tune();
    if (!mOutI16) { renderFloat(stream, static_cast<float*>(audioData), numFrames); return oboe::DataCallbackResult::Continue; }
    // 16-bit device: render float into scratch, then convert (with clipping) in slices.
    auto* dst = static_cast<int16_t*>(audioData);
    for (int32_t off = 0; off < numFrames; off += kI16ScratchFrames) {
        const int32_t n = std::min<int32_t>(kI16ScratchFrames, numFrames - off);
        renderFloat(stream, mI16Scratch.data(), n);
        for (int32_t i = 0; i < 2 * n; ++i) {
            const float v = std::clamp(mI16Scratch[(size_t)i], -1.f, 1.f);
            dst[2 * off + i] = (int16_t)std::lrint(v * 32767.f);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::renderFloat(oboe::AudioStream* stream, float* audioData, int32_t numFrames) {
#if defined(__aarch64__)
    // Flush denormals to zero: filter and envelope tails decaying toward 0 would otherwise hit
    // ~100× slower subnormal arithmetic and cause CPU spikes exactly when notes release.
    { uint64_t fpcr; __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr)); __asm__ volatile("msr fpcr, %0" :: "r"(fpcr | (1ull << 24))); }
#endif
    Command c;
    while (mCmds.pop(c)) handleCommand(c);

    float* out = static_cast<float*>(audioData);
    std::memset(out, 0, sizeof(float) * (size_t)numFrames * 2);
    const float sr = (float)stream->getSampleRate();
    for (auto& sy : mSynths) sy.setSampleRate(sr);
    for (auto& d : mDrums) d.setSampleRate(sr);
    mLoud.setSampleRate(sr);
    for (auto& s : mStrips) { s.peakL = s.peakR = 0.f; s.scGainMin = 1.f; }
    mMasterPeakL = mMasterPeakR = 0.f;
    uint32_t w = mScopeWrite.load(std::memory_order_relaxed);

    for (int off = 0; off < numFrames; off += kChunk) {
        const int len = std::min(kChunk, numFrames - off);
        const double tickAtChunk = mSeq.tick();
        const double tps = mSeq.ticksPerSample(sr);
        const int nf = mSeq.process(len, sr, mFires.data(), (int)mFires.size());
        for (auto& b : mBus) std::fill(b.begin(), b.begin() + 2 * len, 0.f);
        std::fill(mClickBus.begin(), mClickBus.begin() + 2 * len, 0.f);

        // Sample-accurate: render up to each event, apply it, continue.
        int pos = 0;
        for (int i = 0; i < nf; ++i) {
            const seq::Fire& f = mFires[i];
            if (f.offset > pos) { renderSegment(pos, f.offset, sr, w); pos = f.offset; }
            applyFire(f, tickAtChunk, tps, sr);
        }
        renderSegment(pos, len, sr, w);
        mixChunk(out + 2 * off, len, sr, tickAtChunk, tps);
    }
    mScopeWrite.store(w, std::memory_order_release);

    // ---- telemetry ----
    {
        const synth::Telemetry& t = mSynths[mFocusSynth].telemetry();
        const float vals[kTel] = {t.lfoValue, t.lfoPhase, t.ampLevel, t.ampStage, t.ampStageTime,
                                  t.fenvLevel, t.fenvStage, t.fenvStageTime, t.cutoffHz, t.activeVoices,
                                  t.newestNote, mMasterGain};
        for (int i = 0; i < kTel; ++i) mTel[i].store(vals[i], std::memory_order_relaxed);
        mTelNotes[0].store(t.notes[0], std::memory_order_relaxed);
        mTelNotes[1].store(t.notes[1], std::memory_order_relaxed);
    }
    mTelTick.store(mSeq.tick(), std::memory_order_relaxed);
    mTelFlags.store((mSeq.playing() ? 1 : 0) | (mRecording ? 2 : 0) | (mMetroOn ? 4 : 0), std::memory_order_relaxed);
    mTelBpm.store(mSeq.bpm(), std::memory_order_relaxed);
    mTelSwing.store(mSeq.swing(), std::memory_order_relaxed);
    mTelFx.store(mFx.activeMask(), std::memory_order_relaxed);
    mTelEchoTail.store(mFx.echoTail(), std::memory_order_relaxed);
    mTelRevTail.store(mFx.reverbTail(), std::memory_order_relaxed);
    for (int k = 0; k < kStrips; ++k) {
        atomicMax(mMeters[2 * k], mStrips[k].peakL); atomicMax(mMeters[2 * k + 1], mStrips[k].peakR);
        // keep the deepest ducking until read (min): stored as 1 - g so atomicMax works
        const float gr = mStrips[k].scGainMin;
        float cur = mScGain[k].load(std::memory_order_relaxed);
        while (gr < cur && !mScGain[k].compare_exchange_weak(cur, gr, std::memory_order_relaxed)) {}
    }
    atomicMax(mMeters[2 * kStrips], mMasterPeakL); atomicMax(mMeters[2 * kStrips + 1], mMasterPeakR);
    {
        uint32_t dm = 0;
        for (int t = 0; t < kTracks; ++t) {
            if (mTrackType[t] != TrackType::Drums) continue;
            for (int s = 0; s < drums::kSlots; ++s) {
                if (mDrums[t].active(s)) dm |= 1u << s;
                mDrumLevel[t * drums::kSlots + s].store(mDrums[t].slotLevel(s), std::memory_order_relaxed);
                mDrumHits[t * drums::kSlots + s].store((int32_t)mDrums[t].hitCount(s), std::memory_order_relaxed);
            }
        }
        mTelDrums.store(dm, std::memory_order_relaxed);
    }
    atomicMax(mPeak, std::max(mMasterPeakL, mMasterPeakR));

    // Playhead (position in the whole sample) of the newest voice, which pad it's on, and the pad activity mask.
    float ph = -1.f;
    int phPad = -1;
    uint64_t newest = 0;
    uint32_t mask = 0;
    for (auto& v : mVoices) {
        if (!v.active() || !v.buffer()) continue;
        if (v.pad() / kPadsPerTrack != mFocusSampler) continue;      // pad telemetry = the focused sampler track
        const int local = v.pad() % kPadsPerTrack;
        if (v.sounding()) mask |= 1u << local;
        if (v.startedAt() >= newest) {
            newest = v.startedAt(); phPad = local;
            ph = (float)(v.position() / (double)v.buffer()->frames);
        }
    }
    mPlayhead.store(ph, std::memory_order_relaxed);
    mTelPads.store(mask, std::memory_order_relaxed);
    mTelPlayPad.store(phPad, std::memory_order_relaxed);
    return;
}
