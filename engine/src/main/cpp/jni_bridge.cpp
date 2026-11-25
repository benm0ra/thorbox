// SPDX-License-Identifier: GPL-3.0-or-later
// Thin JNI layer. Every function here is called from the Android main thread only
// (the single producer for the command queue).
//
// SECURITY BOUNDARY: this file is the trust boundary between managed code and the RT engine.
// Every value is validated here (finite floats, integer ranges, array sizes) before it can reach
// DSP code. This file is deliberately compiled WITHOUT -ffast-math (see CMakeLists.txt) so the
// std::isfinite checks can't be optimized away.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <new>
#include <unistd.h>
#include "AudioEngine.h"

static std::unique_ptr<AudioEngine> gEngine;
static JavaVM* gVm = nullptr;

// The decode worker runs Android's media extractor, which needs its thread attached to the VM.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    AudioEngine::sThreadAttach = [] { JNIEnv* env = nullptr; if (gVm) gVm->AttachCurrentThread(&env, nullptr); };
    AudioEngine::sThreadDetach = [] { if (gVm) gVm->DetachCurrentThread(); };
    return JNI_VERSION_1_6;
}

static AudioEngine& engine() {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    return *gEngine;
}
static inline bool finite(float v) { return std::isfinite(v); }

#define JNI_FN(name) Java_com_thorbox_engine_NativeEngine_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(nativeStart)(JNIEnv*, jobject) { return engine().start(); }
JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv*, jobject) { if (gEngine) gEngine->stop(); }

JNIEXPORT jboolean JNICALL JNI_FN(nativeNoteOn)(JNIEnv*, jobject, jint pad, jfloat vel) {
    return gEngine && finite(vel) && gEngine->noteOn(pad, vel);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeNoteOff)(JNIEnv*, jobject, jint pad) {
    return gEngine && gEngine->noteOff(pad);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPitch)(JNIEnv*, jobject, jint pad, jint semis, jfloat cents) {
    if (!gEngine || !finite(cents) || semis < -48 || semis > 48 || cents < -100.f || cents > 100.f) return JNI_FALSE;
    return gEngine->setPitch(pad, semis, cents);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPlayMode)(JNIEnv*, jobject, jint pad, jint mode) {
    return gEngine && gEngine->setPlayMode(pad, mode);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetMasterGainDb)(JNIEnv*, jobject, jfloat db) {
    if (!gEngine || !finite(db) || db < -60.f || db > 12.f) return JNI_FALSE;
    return gEngine->setMasterGainDb(db);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSynthNoteOn)(JNIEnv*, jobject, jint track, jint note, jfloat vel) {
    return gEngine && finite(vel) && gEngine->synthNoteOn(track, note, vel);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSynthNoteOff)(JNIEnv*, jobject, jint track, jint note) {
    return gEngine && gEngine->synthNoteOff(track, note);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSynthParam)(JNIEnv*, jobject, jint track, jint id, jfloat value) {
    if (!gEngine || !finite(value) || id < 0 || id >= synth::kParamCount) return JNI_FALSE;
    const auto r = synth::kRanges[id];
    if (value < r.lo || value > r.hi) return JNI_FALSE;      // reject rather than silently clamp: surfaces UI bugs
    return gEngine->synthParam(track, id, value);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSynthAllOff)(JNIEnv*, jobject, jint track) {
    return gEngine && gEngine->synthAllOff(track);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetTrackType)(JNIEnv*, jobject, jint track, jint type) {
    return gEngine && gEngine->setTrackType(track, type);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetFocus)(JNIEnv*, jobject, jint track) {
    return gEngine && gEngine->setFocus(track);
}
JNIEXPORT void JNICALL JNI_FN(nativeGetMeters)(JNIEnv* env, jobject, jfloatArray out) {
    constexpr int n = 2 * AudioEngine::kTracks + 2 + AudioEngine::kTracks;
    if (!gEngine || !out || env->GetArrayLength(out) < n) return;
    float tmp[n];
    gEngine->getMeters(tmp);
    env->SetFloatArrayRegion(out, 0, n, tmp);
}

JNIEXPORT void JNICALL JNI_FN(nativeLoadSampleFd)(JNIEnv*, jobject, jint fd, jint requestId) {
    if (fd < 0) return;
    engine().loadSampleAsync(fd, requestId);   // takes ownership of fd
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPadRegion)(JNIEnv*, jobject, jint pad, jint start, jint end) {
    return gEngine && start >= 0 && end >= -1 && gEngine->setPadRegion(pad, start, end);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPadGain)(JNIEnv*, jobject, jint pad, jfloat g) {
    return gEngine && finite(g) && g >= 0.f && g <= 16.f && gEngine->setPadGain(pad, g);   // up to +24 dB (NORMALIZE on quiet chops)
}
// A stretch of the sample, for the zoomed waveform: [from, to) frames into out.length / 3 buckets.
JNIEXPORT jboolean JNICALL JNI_FN(nativeGetWaveRange)(JNIEnv* env, jobject, jint sampleId, jlong from, jlong to, jfloatArray out) {
    if (!gEngine || !out || from < 0 || to <= from) return JNI_FALSE;
    const jsize n = env->GetArrayLength(out);
    if (n < 3 || n % 3 != 0 || n > 3 * 16384) return JNI_FALSE;
    std::unique_ptr<float[]> tmp(new (std::nothrow) float[(size_t)n]);
    if (!tmp || !gEngine->getWave(sampleId, n / 3, tmp.get(), from, to)) return JNI_FALSE;
    env->SetFloatArrayRegion(out, 0, n, tmp.get());
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeGetWave)(JNIEnv* env, jobject, jint sampleId, jfloatArray out) {
    if (!gEngine || !out) return JNI_FALSE;
    const jsize len = env->GetArrayLength(out);
    if (len < 3 || len > 3 * 16384 || len % 3 != 0) return JNI_FALSE;
    jfloat* p = env->GetFloatArrayElements(out, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = gEngine->getWave(sampleId, len / 3, p);
    env->ReleaseFloatArrayElements(out, p, ok ? 0 : JNI_ABORT);
    return ok;
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPadParam)(JNIEnv*, jobject, jint pad, jint param, jfloat v) {
    return gEngine && finite(v) && gEngine->setPadParam(pad, param, v);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeStopPads)(JNIEnv*, jobject, jint track) {
    return gEngine && gEngine->stopPads(track);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetDuck)(JNIEnv*, jobject, jint param, jfloat v) {
    return gEngine && finite(v) && gEngine->setDuck(param, v);
}
JNIEXPORT void JNICALL JNI_FN(nativeGetDuck)(JNIEnv* env, jobject, jfloatArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 4) return;
    float tmp[4];
    gEngine->getDuck(tmp);
    env->SetFloatArrayRegion(out, 0, 4, tmp);
}
// EXPORT (main thread only; see AudioEngine.h)
JNIEXPORT jboolean JNICALL JNI_FN(nativeExportBegin)(JNIEnv*, jobject, jint sr) { return engine().exportBegin(sr); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeExportOpen)(JNIEnv*, jobject, jint slot, jint fd, jint format, jint bits) {
    if (!gEngine) { if (fd >= 0) ::close(fd); return JNI_FALSE; }
    return gEngine->exportOpen(slot, fd, format, bits);
}
JNIEXPORT void JNICALL JNI_FN(nativeExportRoute)(JNIEnv* env, jobject, jint mix, jintArray stems) {
    if (!gEngine) return;
    int s[AudioEngine::kTracks]; for (int& v : s) v = -1;
    if (stems) { const jsize n = std::min<jsize>(env->GetArrayLength(stems), AudioEngine::kTracks); env->GetIntArrayRegion(stems, 0, n, reinterpret_cast<jint*>(s)); }
    gEngine->exportRoute(mix, s);
}
JNIEXPORT jfloat JNICALL JNI_FN(nativeExportRender)(JNIEnv* env, jobject, jint frames, jfloatArray stemPeaks) {
    if (!gEngine) return -1.f;
    float p[AudioEngine::kTracks] = {};
    const float peak = gEngine->exportRender(frames, p);
    if (stemPeaks && env->GetArrayLength(stemPeaks) >= AudioEngine::kTracks) env->SetFloatArrayRegion(stemPeaks, 0, AudioEngine::kTracks, p);
    return peak;
}
JNIEXPORT jlong JNICALL JNI_FN(nativeExportClose)(JNIEnv*, jobject, jint slot) { return gEngine ? gEngine->exportClose(slot) : -1; }
JNIEXPORT void JNICALL JNI_FN(nativeExportEnd)(JNIEnv*, jobject) { if (gEngine) gEngine->exportEnd(); }
// Safe from any thread (reads an immutable buffer).
JNIEXPORT jint JNICALL JNI_FN(nativeGetTransients)(JNIEnv* env, jobject, jint sampleId, jfloat sens, jint maxSlices, jlongArray out) {
    if (!gEngine || !out || !finite(sens) || maxSlices < 1 || maxSlices > 64) return 0;
    const jsize len = env->GetArrayLength(out);
    if (len < maxSlices) return 0;
    int64_t tmp[64];
    const int n = gEngine->transients(sampleId, std::clamp(sens, 0.f, 1.f), maxSlices, tmp);
    jlong j[64]; for (int i = 0; i < n; ++i) j[i] = (jlong)tmp[i];
    env->SetLongArrayRegion(out, 0, n, j);
    return n;
}
JNIEXPORT void JNICALL JNI_FN(nativeStretchSample)(JNIEnv*, jobject, jint sampleId, jdouble tempo, jdouble semis, jint requestId, jlong cropStart, jlong cropEnd) {
    if (!gEngine || sampleId <= 0 || !std::isfinite(tempo) || !std::isfinite(semis) || tempo < 0.25 || tempo > 4.0 || semis < -24 || semis > 24) return;
    if (cropStart < 0 || cropEnd < 0 || (cropEnd != 0 && cropEnd <= cropStart)) return;   // the worker also checks against the buffer
    gEngine->stretchSampleAsync(sampleId, tempo, semis, requestId, (int64_t)cropStart, (int64_t)cropEnd);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeAssignSample)(JNIEnv*, jobject, jint pad, jint sampleId) {
    return gEngine && sampleId >= 0 && gEngine->assignSample(pad, sampleId);
}
JNIEXPORT void JNICALL JNI_FN(nativeUnloadSample)(JNIEnv*, jobject, jint sampleId) {
    if (gEngine && sampleId > 0) gEngine->unloadSample(sampleId);
}
JNIEXPORT jint JNICALL JNI_FN(nativePollEvent)(JNIEnv* env, jobject, jdoubleArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 12) return 0;
    double tmp[12] = {0};
    const int type = gEngine->pollEvent(tmp);
    if (type) env->SetDoubleArrayRegion(out, 0, 12, tmp);
    return type;
}
JNIEXPORT void JNICALL JNI_FN(nativeLoadProgress)(JNIEnv* env, jobject, jfloatArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 3) return;
    float tmp[3];
    gEngine->loadProgress(tmp);
    env->SetFloatArrayRegion(out, 0, 3, tmp);
}
JNIEXPORT void JNICALL JNI_FN(nativeCancelLoad)(JNIEnv*, jobject, jint requestId) {
    if (gEngine && requestId > 0) gEngine->cancelLoad(requestId);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeGetPeaks)(JNIEnv* env, jobject, jint sampleId, jfloatArray out) {
    if (!gEngine || !out) return JNI_FALSE;
    const jsize len = env->GetArrayLength(out);
    if (len < 2 || len > 2 * 16384) return JNI_FALSE;      // bounded work on the caller's thread
    jfloat* p = env->GetFloatArrayElements(out, nullptr);
    if (!p) return JNI_FALSE;
    const bool ok = gEngine->getPeaks(sampleId, len / 2, p);
    env->ReleaseFloatArrayElements(out, p, ok ? 0 : JNI_ABORT);
    return ok;
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeRegionLevels)(JNIEnv* env, jobject, jint sampleId, jlongArray regions, jfloatArray out) {
    if (!gEngine || !regions || !out) return JNI_FALSE;
    const jsize rl = env->GetArrayLength(regions), ol = env->GetArrayLength(out);
    const int n = rl / 2;
    if (n < 1 || n > 64 || ol < 2 * n) return JNI_FALSE;
    jlong r[128]; env->GetLongArrayRegion(regions, 0, 2 * n, r);
    int64_t a[64], e[64]; float pk[64], ld[64];
    for (int i = 0; i < n; ++i) { a[i] = (int64_t)r[2 * i]; e[i] = (int64_t)r[2 * i + 1]; }
    if (!gEngine->regionLevels(sampleId, a, e, n, pk, ld)) return JNI_FALSE;
    jfloat o[128]; for (int i = 0; i < n; ++i) { o[2 * i] = pk[i]; o[2 * i + 1] = ld[i]; }
    env->SetFloatArrayRegion(out, 0, 2 * n, o);
    return JNI_TRUE;
}
JNIEXPORT jfloat JNICALL JNI_FN(nativePlayhead)(JNIEnv*, jobject) { return gEngine ? gEngine->playhead() : -1.f; }
JNIEXPORT jfloat JNICALL JNI_FN(nativePeakLevel)(JNIEnv*, jobject) { return gEngine ? gEngine->peakLevel() : 0.f; }
JNIEXPORT void JNICALL JNI_FN(nativeGetStats)(JNIEnv* env, jobject, jdoubleArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 8) return;
    double tmp[8];
    gEngine->getStats(tmp);
    env->SetDoubleArrayRegion(out, 0, 8, tmp);
}

// Per-frame UI telemetry. Region copies only: no pinning, no allocation.
JNIEXPORT void JNICALL JNI_FN(nativeGetSynthTelemetry)(JNIEnv* env, jobject, jfloatArray out, jlongArray notes) {
    if (!gEngine || !out || !notes || env->GetArrayLength(out) < 12 || env->GetArrayLength(notes) < 2) return;
    float t[12]; int64_t n[2];
    gEngine->getSynthTelemetry(t, n);
    env->SetFloatArrayRegion(out, 0, 12, t);
    jlong jn[2] = {(jlong)n[0], (jlong)n[1]};
    env->SetLongArrayRegion(notes, 0, 2, jn);
}
JNIEXPORT jint JNICALL JNI_FN(nativeGetScope)(JNIEnv* env, jobject, jfloatArray out) {
    if (!gEngine || !out) return 0;
    const jsize len = std::min<jsize>(env->GetArrayLength(out), 2048);
    float tmp[2048];
    const int n = gEngine->getScope(tmp, len);
    env->SetFloatArrayRegion(out, 0, n, tmp);
    return n;
}

// ---------------- transport / groove / mixer ----------------

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetTempo)(JNIEnv*, jobject, jfloat bpm) {
    return gEngine && finite(bpm) && bpm >= 30.f && bpm <= 300.f && gEngine->setTempo(bpm);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetSwing)(JNIEnv*, jobject, jfloat amount, jint grid) {
    return gEngine && finite(amount) && amount >= 0.5f && amount <= 0.75f && (grid == 240 || grid == 480) && gEngine->setSwing(amount, grid);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPlaying)(JNIEnv*, jobject, jboolean on) { return gEngine && gEngine->setPlaying(on); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetRecording)(JNIEnv*, jobject, jboolean on) { return gEngine && gEngine->setRecording(on); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetMetronome)(JNIEnv*, jobject, jboolean on) { return gEngine && gEngine->setMetronome(on); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetClick)(JNIEnv*, jobject, jint param, jfloat v) { return gEngine && finite(v) && gEngine->setClick(param, v); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetCountIn)(JNIEnv*, jobject, jfloat ticks) { return gEngine && finite(ticks) && gEngine->setCountIn(ticks); }
// ---------------- MIDI out + audio device ----------------
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetMidiOut)(JNIEnv*, jobject, jint track, jint ch) { return gEngine && gEngine->setMidiOut(track, ch); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetMidiMap)(JNIEnv*, jobject, jint track, jint note, jint midi) { return gEngine && gEngine->setMidiMap(track, note, midi); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetMidiClock)(JNIEnv*, jobject, jboolean on) { return gEngine && gEngine->setMidiClock(on); }
// MIDI thread: the queue's only consumer. out holds 3 longs per message.
JNIEXPORT jint JNICALL JNI_FN(nativePollMidi)(JNIEnv* env, jobject, jlongArray out) {
    if (!gEngine || !out) return 0;
    const int max = std::min<int>(env->GetArrayLength(out) / 3, 256);
    int64_t tmp[3 * 256];
    const int n = gEngine->pollMidi(tmp, max);
    if (n > 0) env->SetLongArrayRegion(out, 0, 3 * n, reinterpret_cast<const jlong*>(tmp));
    return n;
}
JNIEXPORT void JNICALL JNI_FN(nativeMidiAnchor)(JNIEnv* env, jobject, jlongArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 3) return;
    int64_t tmp[3]; gEngine->midiAnchor(tmp);
    env->SetLongArrayRegion(out, 0, 3, reinterpret_cast<const jlong*>(tmp));
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetOutputDevice)(JNIEnv*, jobject, jint id) { return gEngine && gEngine->setOutputDevice(id); }

// Builds an immutable pattern from parallel arrays, fully validated, then hands it to the engine.
// jFlags[i] = ratchet | condEvery << 4 | condPhase << 8. jLockIds / jLockVals: 2 per event (-1 = none).
static seq::Pattern* buildPattern(JNIEnv* env, jint track, jint lengthTicks,
        jintArray jTicks, jintArray jLens, jintArray jNotes, jfloatArray jVels, jfloatArray jProbs,
        jintArray jFlags, jintArray jLockIds, jfloatArray jLockVals) {
    if (track < 0 || track >= seq::kTracks) return nullptr;
    if (lengthTicks < 240 || lengthTicks > seq::kMaxBars * 4 * seq::kPpqn) return nullptr;   // 1 step … 64 bars
    if (!jTicks || !jLens || !jNotes || !jVels || !jProbs || !jFlags || !jLockIds || !jLockVals) return nullptr;
    const jsize n = env->GetArrayLength(jTicks);
    if (n < 0 || n > seq::kMaxEvents || env->GetArrayLength(jLens) != n || env->GetArrayLength(jNotes) != n ||
        env->GetArrayLength(jVels) != n || env->GetArrayLength(jProbs) != n || env->GetArrayLength(jFlags) != n ||
        env->GetArrayLength(jLockIds) != 2 * n || env->GetArrayLength(jLockVals) != 2 * n) return nullptr;
    auto* p = new (std::nothrow) seq::Pattern();
    if (!p) return nullptr;
    std::unique_ptr<jint[]> t(new (std::nothrow) jint[n + 1]), l(new (std::nothrow) jint[n + 1]), no(new (std::nothrow) jint[n + 1]);
    std::unique_ptr<jfloat[]> v(new (std::nothrow) jfloat[n + 1]), pr(new (std::nothrow) jfloat[n + 1]);
    std::unique_ptr<jint[]> fl(new (std::nothrow) jint[n + 1]), li(new (std::nothrow) jint[2 * n + 1]);
    std::unique_ptr<jfloat[]> lv(new (std::nothrow) jfloat[2 * n + 1]);
    if (!t || !l || !no || !v || !pr || !fl || !li || !lv) { delete p; return nullptr; }
    env->GetIntArrayRegion(jFlags, 0, n, fl.get()); env->GetIntArrayRegion(jLockIds, 0, 2 * n, li.get());
    env->GetFloatArrayRegion(jLockVals, 0, 2 * n, lv.get());
    env->GetIntArrayRegion(jTicks, 0, n, t.get()); env->GetIntArrayRegion(jLens, 0, n, l.get());
    env->GetIntArrayRegion(jNotes, 0, n, no.get());
    env->GetFloatArrayRegion(jVels, 0, n, v.get()); env->GetFloatArrayRegion(jProbs, 0, n, pr.get());
    p->lengthTicks = lengthTicks;
    int c = 0;
    for (jsize i = 0; i < n; ++i) {
        const int maxNote = 127;                          // (sampler pads are 0…15; the engine ignores the rest)
        if (t[i] < 0 || t[i] >= lengthTicks || l[i] < 1 || l[i] > lengthTicks) continue;   // drop invalid events
        if (no[i] < 0 || no[i] > maxNote || !finite(v[i]) || !finite(pr[i])) continue;
        seq::Event e{t[i], l[i], (int16_t)no[i], std::clamp(v[i], 0.f, 1.f), std::clamp(pr[i], 0.f, 1.f)};
        e.ratchet = (int8_t)std::clamp(fl[i] & 0xF, 1, 4);
        e.condEvery = (int8_t)std::clamp((fl[i] >> 4) & 0xF, 1, 8);
        e.condPhase = (int8_t)std::clamp((fl[i] >> 8) & 0xF, 0, e.condEvery - 1);
        for (int k = 0; k < seq::kLocks; ++k) {
            const int id = li[2 * i + k]; const float val = lv[2 * i + k];
            // Locks: a known synth param id and an in-range finite value (applied on synth tracks only).
            if (id >= 0 && id < synth::kParamCount && finite(val) && val >= synth::kRanges[id].lo && val <= synth::kRanges[id].hi) {
                e.lockId[k] = (int8_t)id; e.lockVal[k] = val;
            }
        }
        p->events[c++] = e;
    }
    p->count = c;
    // sorted by tick, so the audio thread only looks at the events near the playhead (see seq::Pattern)
    std::stable_sort(p->events, p->events + c, [](const seq::Event& x, const seq::Event& y) { return x.tick < y.tick; });
    int32_t reach = 0;
    for (int i = 0; i < c; ++i) if (p->events[i].ratchet > 1) reach = std::max(reach, p->events[i].length);
    p->reach = reach + 2 * 480 / 4 + 1;      // + the most swing can delay a note (0.25 · 2 · an 1/8 = 240 ticks)
    p->sorted = true;
    return p;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSetPattern)(JNIEnv* env, jobject, jint track, jint lengthTicks,
        jintArray jTicks, jintArray jLens, jintArray jNotes, jfloatArray jVels, jfloatArray jProbs,
        jintArray jFlags, jintArray jLockIds, jfloatArray jLockVals) {
    if (!gEngine) return JNI_FALSE;
    seq::Pattern* p = buildPattern(env, track, lengthTicks, jTicks, jLens, jNotes, jVels, jProbs, jFlags, jLockIds, jLockVals);
    return p && gEngine->setPattern(track, p);      // takes ownership
}
// The same pattern, staged for the next scene change (an empty pattern is sent as no events).
JNIEXPORT jboolean JNICALL JNI_FN(nativeQueuePattern)(JNIEnv* env, jobject, jint track, jint lengthTicks,
        jintArray jTicks, jintArray jLens, jintArray jNotes, jfloatArray jVels, jfloatArray jProbs,
        jintArray jFlags, jintArray jLockIds, jfloatArray jLockVals) {
    if (!gEngine) return JNI_FALSE;
    seq::Pattern* p = buildPattern(env, track, lengthTicks, jTicks, jLens, jNotes, jVels, jProbs, jFlags, jLockIds, jLockVals);
    return p && gEngine->queuePattern(track, p);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeCommitScene)(JNIEnv*, jobject, jint loopTicks, jint tag) {
    return gEngine && gEngine->commitScene(loopTicks, tag);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeRepeatOn)(JNIEnv*, jobject, jint track, jint note, jint rate, jfloat vel) {
    return gEngine && finite(vel) && rate >= 40 && rate <= 1920 && gEngine->repeatOn(track, note, rate, vel);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeRepeatOff)(JNIEnv*, jobject, jint track, jint note) {
    return gEngine && track >= 0 && track < seq::kTracks && gEngine->repeatOff(track, note);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeRepeatVelocity)(JNIEnv*, jobject, jfloat v) {
    return gEngine && finite(v) && gEngine->repeatVelocity(v);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeSetChannel)(JNIEnv*, jobject, jint strip, jint param, jfloat value) {
    if (!gEngine || !finite(value)) return JNI_FALSE;
    if (param == 0 && (value < -60.f || value > 6.f)) return JNI_FALSE;
    if (param == 1 && (value < -1.f || value > 1.f)) return JNI_FALSE;
    if (param == 4 && (value < -1.f || value >= (float)AudioEngine::kTracks)) return JNI_FALSE;
    if (param == 5 && (value < 0.f || value > 1.f)) return JNI_FALSE;
    if (param == 6 && (value < 20.f || value > 1000.f)) return JNI_FALSE;
    // EQ params (16+) are range-checked by AudioEngine::setChannel via eq::set
    return gEngine->setChannel(strip, param, value);
}
static inline bool unit(float v) { return finite(v) && v >= 0.f && v <= 1.f; }
JNIEXPORT jboolean JNICALL JNI_FN(nativeFxOn)(JNIEnv*, jobject, jint id, jfloat x, jfloat y) {
    return gEngine && unit(x) && unit(y) && gEngine->fxOn(id, x, y);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeFxMove)(JNIEnv*, jobject, jint id, jfloat x, jfloat y) {
    return gEngine && unit(x) && unit(y) && gEngine->fxMove(id, x, y);
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeFxOff)(JNIEnv*, jobject, jint id) { return gEngine && gEngine->fxOff(id); }
JNIEXPORT jboolean JNICALL JNI_FN(nativeFxAllOff)(JNIEnv*, jobject) { return gEngine && gEngine->fxAllOff(); }

// Visualizer: bands (≤64), waveform (≤1024), energy (3). Region copies only.
JNIEXPORT void JNICALL JNI_FN(nativeGetVisual)(JNIEnv* env, jobject, jfloatArray jBands, jfloatArray jWave, jfloatArray jEnergy) {
    if (!gEngine || !jBands || !jWave || !jEnergy || env->GetArrayLength(jEnergy) < 3) return;
    const int nb = std::min<jsize>(env->GetArrayLength(jBands), viz::kMaxBands);
    const int nw = std::min<jsize>(env->GetArrayLength(jWave), viz::kFft);
    const int ne = env->GetArrayLength(jEnergy) >= 5 ? 5 : 3;
    float b[viz::kMaxBands], w[viz::kFft], e[5] = {};
    if (nb < 1) return;
    gEngine->getVisual(b, nb, w, nw, e, ne);
    env->SetFloatArrayRegion(jBands, 0, nb, b);
    env->SetFloatArrayRegion(jWave, 0, nw, w);
    env->SetFloatArrayRegion(jEnergy, 0, ne, e);
}
JNIEXPORT void JNICALL JNI_FN(nativeGetLoudness)(JNIEnv* env, jobject, jfloatArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 6) return;
    float o[6]; gEngine->getLoudness(o);
    env->SetFloatArrayRegion(out, 0, 6, o);
}
JNIEXPORT void JNICALL JNI_FN(nativeGetMasterGr)(JNIEnv* env, jobject, jfloatArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 2) return;
    float o[2]; gEngine->getMasterGr(o);
    env->SetFloatArrayRegion(out, 0, 2, o);
}
JNIEXPORT void JNICALL JNI_FN(nativeResetLoudness)(JNIEnv*, jobject) { if (gEngine) gEngine->resetLoudness(); }
JNIEXPORT jint JNICALL JNI_FN(nativeGetStereo)(JNIEnv* env, jobject, jfloatArray jMid, jfloatArray jSide) {
    if (!gEngine || !jMid || !jSide) return 0;
    const int n = std::min<jsize>(std::min(env->GetArrayLength(jMid), env->GetArrayLength(jSide)), (jsize)2048);   // = AudioEngine::kScope
    float m[2048], sd[2048];
    const int got = gEngine->getStereo(m, sd, n);
    env->SetFloatArrayRegion(jMid, 0, got, m);
    env->SetFloatArrayRegion(jSide, 0, got, sd);
    return got;
}

JNIEXPORT jint JNICALL JNI_FN(nativePollRecord)(JNIEnv* env, jobject, jdoubleArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 4) return 0;
    double tmp[4];
    const int type = gEngine->pollRecord(tmp);
    if (type) env->SetDoubleArrayRegion(out, 0, 4, tmp);
    return type;
}
JNIEXPORT void JNICALL JNI_FN(nativeGetTransport)(JNIEnv* env, jobject, jdoubleArray out) {
    if (!gEngine || !out || env->GetArrayLength(out) < 21) return;
    double tmp[24] = {};
    gEngine->getTransport(tmp);
    env->SetDoubleArrayRegion(out, 0, 21, tmp);
}

// ---------------- drum synth ----------------
JNIEXPORT jboolean JNICALL JNI_FN(nativeDrumTrigger)(JNIEnv*, jobject, jint track, jint slot, jfloat vel) {
    return gEngine && slot >= 0 && slot < drums::kSlots && finite(vel) && gEngine->drumTrigger(track, slot, std::clamp(vel, 0.f, 1.f));
}
JNIEXPORT jboolean JNICALL JNI_FN(nativeDrumParam)(JNIEnv*, jobject, jint track, jint slot, jint id, jfloat v) {
    if (!gEngine || !finite(v)) return JNI_FALSE;
    if (slot == drums::kBusSlot) {                       // kit-wide character bus, all 0..1
        if (id < 0 || id >= drums::kBusParamCount) return JNI_FALSE;
        return gEngine->drumParam(track, slot, id, std::clamp(v, 0.f, 1.f));
    }
    if (slot < 0 || slot >= drums::kSlots || id < 0 || id >= drums::kParamCount) return JNI_FALSE;
    return gEngine->drumParam(track, slot, id, std::clamp(v, drums::kRanges[id].lo, drums::kRanges[id].hi));
}
JNIEXPORT void JNICALL JNI_FN(nativeGetDrumTelemetry)(JNIEnv* env, jobject, jint track, jfloatArray jLevels, jintArray jHits) {
    if (!gEngine || !jLevels || !jHits || env->GetArrayLength(jLevels) < drums::kSlots || env->GetArrayLength(jHits) < drums::kSlots) return;
    float lv[drums::kSlots]; int32_t h[drums::kSlots];
    gEngine->getDrumTelemetry(track, lv, h);
    env->SetFloatArrayRegion(jLevels, 0, drums::kSlots, lv);
    env->SetIntArrayRegion(jHits, 0, drums::kSlots, reinterpret_cast<jint*>(h));
}

} // extern "C"
