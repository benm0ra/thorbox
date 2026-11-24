// SPDX-License-Identifier: GPL-3.0-or-later
#include "AudioEngine.h"
#include <algorithm>
#include <cmath>
#include <cstring>

// AudioEngine, part 2 of 3: the audio thread. Everything here runs inside onAudioReady (or an export
// render, which takes its place): no allocation, no locks, no syscalls, no logging. See CONTRIBUTING.md.
// Part 1 (AudioEngine.cpp): lifecycle, the main-thread API, sample loading, polling.
// Part 3 (AudioExport.cpp): the offline bounce.

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
        case CmdType::QueuePattern: mSeq.stage(c.pad, static_cast<const seq::Pattern*>(c.ptr)); break;
        case CmdType::CommitScene: mSeq.commit(c.i0, c.i1); break;
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
        case CmdType::SetDuck: {
            const bool was = mDuck.s.active();
            mDuck.s.set(c.i0, c.f0);
            if (was && !mDuck.s.active()) mDuck.reset();
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
            // DUCK keyed off one drum voice: that voice is also written (mono) into the key
            const bool tap = mDuck.s.active() && mDuck.s.source == t && mDuck.s.sourceSlot >= 0;
            mDrums[t].render(mBus[t].data() + 2 * a, n, tap ? mDuckKey.data() + a : nullptr, tap ? mDuck.s.sourceSlot : -1);
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
    // DUCK: one key (a track, one drum voice, or the beat) ducks every target track. The key is read
    // from the raw buses before anything is turned down, so it never ducks itself into silence.
    if (mDuck.s.active()) {
        const duck::Settings& d = mDuck.s;
        const bool beat = d.source == duck::kBeat;
        const bool slotKey = !beat && d.source < kTracks && d.sourceSlot >= 0 && mTrackType[d.source] == TrackType::Drums;
        if (beat) {
            const bool run = mSeq.playing();
            for (int i = 0; i < n; ++i) mDuckKey[i] = run ? duck::beatPulse(tick + i * tps, seq::kPpqn, sr, tps) : 0.f;
        } else if (!slotKey && d.source < kTracks) {
            const float* key = mBus[d.source].data();
            for (int i = 0; i < n; ++i) mDuckKey[i] = std::max(std::fabs(key[2 * i]), std::fabs(key[2 * i + 1]));
        }
        mDuck.process(mDuckKey.data(), n, sr, mDuckGain.data());
        const float makeup = std::pow(10.f, d.makeupDb / 20.f);
        for (int k = 0; k < kStrips; ++k) {
            if (!(d.targets & (1u << k))) continue;
            if (!beat && !slotKey && k == d.source) continue;                 // a whole track never ducks itself
            float* bus = mBus[k].data();
            float gmin = mStrips[k].scGainMin;
            for (int i = 0; i < n; ++i) {
                const float g = mDuckGain[i];
                bus[2 * i] *= g; bus[2 * i + 1] *= g;
                gmin = std::min(gmin, g / makeup);                             // the mixer's PUMP bar: reduction only
            }
            mStrips[k].scGainMin = gmin;
        }
    }
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
        // EXPORT stem: this strip post fader/pan/duck (master gain applied below), pre master FX
        float* st = (mCapturing && mExpStemSlot[k] >= 0) ? mCapStem[k].data() + 2 * mCapOff : nullptr;
        for (int i = 0; i < n; ++i) {
            s.gL += (tL - s.gL) * coeff; s.gR += (tR - s.gR) * coeff;
            const float l = bus[2 * i] * s.gL, r = bus[2 * i + 1] * s.gR;
            out[2 * i] += l; out[2 * i + 1] += r;
            pL = std::max(pL, std::fabs(l)); pR = std::max(pR, std::fabs(r));
            if (st) { st[2 * i] = l; st[2 * i + 1] = r; }
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
        mGainScratch[i] = mMasterGain;
        out[2 * i] = std::clamp(l + mClickBus[2 * i], -1.f, 1.f);         // click: monitoring only, after master
        out[2 * i + 1] = std::clamp(r + mClickBus[2 * i + 1], -1.f, 1.f);
        if (mCapturing) { mCapMix[2 * (mCapOff + i)] = l; mCapMix[2 * (mCapOff + i) + 1] = r; }   // EXPORT: no click
    }
    if (mCapturing)
        for (int k = 0; k < kStrips; ++k) {
            if (mExpStemSlot[k] < 0) continue;
            float* st = mCapStem[k].data() + 2 * mCapOff;
            for (int i = 0; i < n; ++i) { st[2 * i] *= mGainScratch[i]; st[2 * i + 1] *= mGainScratch[i]; }
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
    // EXPORT handshake (Dekker): announce we're in, then look. exportBegin sets mOffline and waits for
    // mInRender to clear, so either we see mOffline here or it sees us and waits for this block.
    mInRender.store(true, std::memory_order_seq_cst);
    if (mOffline.load(std::memory_order_seq_cst)) {
        mInRender.store(false, std::memory_order_release);
        std::memset(audioData, 0, (size_t)numFrames * 2 * (mOutI16 ? sizeof(int16_t) : sizeof(float)));
        return oboe::DataCallbackResult::Continue;
    }
    const float sr = (float)stream->getSampleRate();
    struct Out { std::atomic<bool>& f; ~Out() { f.store(false, std::memory_order_release); } } out{mInRender};
    if (!mOutI16) { renderFloat(sr, static_cast<float*>(audioData), numFrames); return oboe::DataCallbackResult::Continue; }
    // 16-bit device: render float into scratch, then convert (with clipping) in slices.
    auto* dst = static_cast<int16_t*>(audioData);
    for (int32_t off = 0; off < numFrames; off += kI16ScratchFrames) {
        const int32_t n = std::min<int32_t>(kI16ScratchFrames, numFrames - off);
        renderFloat(sr, mI16Scratch.data(), n);
        for (int32_t i = 0; i < 2 * n; ++i) {
            const float v = std::clamp(mI16Scratch[(size_t)i], -1.f, 1.f);
            dst[2 * off + i] = (int16_t)std::lrint(v * 32767.f);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::renderFloat(float sr, float* audioData, int32_t numFrames) {
#if defined(__aarch64__)
    // Flush denormals to zero: filter and envelope tails decaying toward 0 would otherwise hit
    // ~100× slower subnormal arithmetic and cause CPU spikes exactly when notes release.
    { uint64_t fpcr; __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr)); __asm__ volatile("msr fpcr, %0" :: "r"(fpcr | (1ull << 24))); }
#endif
    Command c;
    while (mCmds.pop(c)) handleCommand(c);
    freeRetired();

    float* out = static_cast<float*>(audioData);
    std::memset(out, 0, sizeof(float) * (size_t)numFrames * 2);
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
        std::fill(mDuckKey.begin(), mDuckKey.begin() + len, 0.f);
        std::fill(mClickBus.begin(), mClickBus.begin() + 2 * len, 0.f);

        // Sample-accurate: render up to each event, apply it, continue.
        int pos = 0;
        for (int i = 0; i < nf; ++i) {
            const seq::Fire& f = mFires[i];
            if (f.offset > pos) { renderSegment(pos, f.offset, sr, w); pos = f.offset; }
            applyFire(f, tickAtChunk, tps, sr);
        }
        renderSegment(pos, len, sr, w);
        mCapOff = off;
        mixChunk(out + 2 * off, len, sr, tickAtChunk, tps);
    }
    mScopeWrite.store(w, std::memory_order_release);
    freeRetired();
    mTelOrigin.store(mSeq.origin(), std::memory_order_relaxed);
    mTelScene.store(mSeq.sceneTag(), std::memory_order_relaxed);
    mTelQueueTag.store(mSeq.queuedTag(), std::memory_order_relaxed);
    mTelQueueAt.store(mSeq.queuedAt(), std::memory_order_relaxed);

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
    atomicMax(mDuckGrMax, mDuck.grMax); atomicMax(mDuckKeyPk, mDuck.keyPeak);
    mDuck.grMax = 0.f; mDuck.keyPeak = 0.f;
    mDuckGrNow.store(mDuck.s.active() ? mDuck.grDb() : 0.f, std::memory_order_relaxed);
    mDuckTrig.store(mDuck.triggers, std::memory_order_relaxed);
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
