// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdint>
struct SampleBuffer;

// Must match com.thorbox.engine.PlayMode ordinal and model.PlayMode.
enum class PlayMode : int32_t { OneShot = 0, Gate = 1, Loop = 2 };

enum class CmdType : uint8_t {
    NoteOn,        // pad, f0 = velocity 0..1
    NoteOff,       // pad
    SetPitch,      // pad, i0 = semitones, f0 = cents
    SetPlayMode,   // pad, i0 = PlayMode
    SetMasterGain, // f0 = linear gain
    AssignSample,  // pad, buf (nullptr clears the pad)
    SynthNoteOn,   // i0 = MIDI note, f0 = velocity
    SynthNoteOff,  // i0 = MIDI note
    SynthParam,    // i0 = synth::Param id, f0 = value (physical units)
    SynthAllOff,
    SetTempo,      // f0 = BPM
    SetSwing,      // f0 = 0.50..0.75, i0 = grid ticks (240 = 1/16, 480 = 1/8)
    SetPlaying,    // i0 = 0/1
    SetRecording,  // i0 = 0/1
    SetMetronome,  // i0 = 0/1
    SetPattern,    // pad = track, ptr = seq::Pattern* (ownership moves to the audio thread)
    RepeatOn,      // pad = track, i0 = note, i1 = rate ticks, f0 = velocity
    RepeatOff,     // pad = track, i0 = note
    RepeatVelocity,// f0
    SetChannel,    // pad = strip (kTracks = master), i0 = ChannelParam, f0 = value
    SetPadRegion,  // pad, i0 = start frame, i1 = end frame (-1 = to the end)
    SetPadGain,    // pad, f0 = linear gain
    FxOn,          // i0 = fx::Id, f0 = x, f1 = y
    FxMove,        // i0 = fx::Id, f0 = x, f1 = y
    FxOff,         // i0 = fx::Id
    FxAllOff,
    DrumTrigger,   // pad = slot, i1 = track, f0 = velocity
    DrumParam,     // pad = slot, i1 = track, i0 = drums::Param, f0 = value
    SetTrackType,  // pad = track, i0 = TrackType
    SetFocus,      // pad = track: its synth feeds the synth telemetry/scope, its pads the pad telemetry
    SetPadParam,   // pad, i0 = PadParam, f0 = value
    StopPads,      // pad = track (-1 = every track): fade out every sampler voice, one-shots too
    SetDuck,       // i0 = duck::Param, f0 = value
    QueuePattern,  // pad = track, ptr = seq::Pattern* (or nullptr = empty): staged for the next scene change
    CommitScene,   // i0 = loop ticks (the change lands on the next multiple, from the scene's start; 0 = now), i1 = tag (-1 = cancel)
};

// Per-pad sound settings (the PAD EDIT page). Must match com.thorbox.engine.NativeEngine.PadParam.
enum class PadParam : int32_t {
    Pan = 0, AttackMs = 1, DecayMs = 2, ReleaseMs = 3, Cutoff = 4, Reso = 5, Reverse = 6,
    MuteGroup = 7,   // 0 off, 1…16: pads in the same group on a track cut each other off (open/closed hat)
    MuteTargetA = 8, // local pad 0…15 this pad cuts off when it plays (-1 none)
    MuteTargetB = 9,
    Poly = 10,       // 0 mono (a retrigger cuts the last hit), 1 poly (hits overlap)
    VelSens = 11,    // 0 … 1: how much velocity sets the level (0 = every hit full)
    ChokeAll = 12,   // 0/1: this pad cuts every other pad on its track (one sound at a time, the default)
};

// What instrument a track plays. Must match com.thorbox.engine.NativeEngine.TrackType.
enum class TrackType : int32_t { None = 0, Sampler = 1, Synth = 2, Drums = 3 };

// Plain-old-data, trivially copyable, fixed size. Never contains owning pointers:
// buf lifetime is guaranteed by the engine's registry refcount (see AudioEngine::assignSample).
// Sidechain: SC_SOURCE = the strip whose signal ducks this one (-1 off), SC_AMOUNT 0..1, SC_RELEASE ms.
enum class ChannelParam : int32_t { VolumeDb = 0, Pan = 1, Mute = 2, Solo = 3, ScSource = 4, ScAmount = 5, ScRelease = 6 };

struct Command {
    CmdType type = CmdType::NoteOn;
    int32_t pad = 0;
    int32_t i0 = 0;
    float f0 = 0.f;
    const SampleBuffer* buf = nullptr;
    const void* ptr = nullptr;
    int32_t i1 = 0;
    float f1 = 0.f;
};

enum class AudioEventType : uint8_t {
    BufferReleased,  // i0 = sample id. The audio thread no longer references that buffer.
    PatternReleased, // ptr = seq::Pattern* to delete on the main thread
    NoteRecorded,    // i0 = track, i1 = note, f0 = velocity, tick = absolute tick
    NoteReleased,    // i0 = track, i1 = note, tick
};

struct AudioEvent {
    AudioEventType type = AudioEventType::BufferReleased;
    int32_t i0 = 0;
    int32_t i1 = 0;
    float f0 = 0.f;
    double tick = 0.0;
    const void* ptr = nullptr;
};
