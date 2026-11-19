<img src="docs/logo.png" width="120" alt="Clamshell Beats logo">

# Clamshell Beats

**A touch-first sampler and synth groovebox for the AYN Thor dual-screen handheld.**
It combines Koala Sampler's zero-friction flow with Ableton Live's session/detail split across the Thor's hinge.
Synth pages are animated in the spirit of Teenage Engineering's instruments.

> Status: **pre-alpha** (Milestone 1 + synth engine). Unofficial: not affiliated with AYN, Teenage Engineering, Koala or Ableton (see NOTICE).
> License: **GPL-3.0-or-later**.

## What works now

- **Dual-screen:** the wide 6" panel shows the *contextual view* (sampler or synth). The near-square 3.92" panel is the *Touch Deck*. Roles come from screen shape, so it works whichever panel the firmware calls primary.
- **Serato-style chopping:**
  - Loading a sample auto-chops it at its transients onto **16 pads** (MPC layout: pad 1 bottom-left).
  - Drag slice markers on the waveform, or re-chop with **AUTO** (sensitivity), **÷4 / ÷8 / ÷16** equal slices, or **LAZY** (tap pads while the sample plays).
  - **KEY MATCH** moves the sample into the project key by the smallest interval (relative major/minor aware).
  - **BPM SYNC** time-stretches it to the project tempo with pitch kept (WSOLA + resampler). It always renders from the original, so quality never degrades.
  - **PROJECT ← SAMPLE** adopts the sample's key and BPM instead.
  - Per-pad pitch and play mode. The drum sequencer edits the selected pad.
- **Synth sequencing:**
  - **NOTE GRID** (piano roll): rows fold to in-key notes when locked. Tap to add or remove, drag sideways for length, up/down for pitch. Stack notes for chords.
  - **STEP deck** (OP-XY-style): tap a step to toggle it, or **hold a step and play keys** to write notes or chords into it.
  - Holding a step opens its components on the top screen: **ratchet** ×1–4, **condition** (1:2, 2:4…), **nudge**, **velocity**, and **parameter locks** (cutoff, reso, env amount, decay).
- **Sampler:**
  - Load WAV/MP3/FLAC/OGG/M4A from a samples folder you grant once.
  - Key and BPM are detected automatically (one-tap ×2 / ÷2 tempo fix).
  - Varispeed pitch (semitones + cents), with One-Shot, Gate and Loop modes.
  - The big pad fires on touch-down, and velocity comes from where you hit it.
- **Synth:**
  - 8-voice, 2-oscillator morphing synth (sine → triangle → saw → square, band-limited), detune and mix.
  - Resonant TPT state-variable low-pass filter, with filter-envelope amount and key tracking.
  - Amp and filter ADSRs, and a global LFO (5 shapes → pitch, cutoff, amp or shape).
- **Groove:**
  - Sample-accurate sequencer for the drum and synth tracks (8/16/32 steps) with velocity, probability and gate lanes.
  - MPC-style swing (50–75%, on a 1/8 or 1/16 grid), tap tempo, a metronome, and live recording with quantize strength.
  - Note repeat (1/8 → 1/32T, swing-aware, recorded too).
- **Generators:** DICE (in-key melodies and Euclidean drum rhythms), MUTATE, CHANCE (per-step probability for patterns that evolve), SNAP to scale, and 16 levels of undo.
- **Scales:** auto-follow the sample's detected key, or pick a root plus major/minor/dorian/mixolydian/pentatonic/chromatic.
- **Mixer:** sampler, synth and master strips with fader, pan, mute, solo and post-fader stereo meters.
- **Punch-in FX:** stutter, filter sweep (LP ◂ ▸ HP), tape stop, crush, vinyl, reverse and trance gate. Hold to engage with XY control, latch to keep on, and L2/R2 act as analog punch-ins.
- **KEYS deck:** 4×4 scale keyboard locked to the sample's detected key (or chromatic), with chord mode, octave shift and glissando. Pads glow from the engine's real sounding notes.

## Look: liquid glass + visualizer

- **Glass where it helps:** the rail, deck tabs, transport bars, panels and overlays are glass. They show an audio-reactive aurora refracted at their edges (AGSL shader, Android 13+).
- **Solid where it matters:** pads, encoders, faders and step cells stay solid and high-contrast, for speed and legibility.
- **Settings:** FULL / SUBTLE / OFF on the VIZ screen. OFF is plain AMOLED black, which saves the most battery.
- **VIZ:** music-reactive visuals from the master output: SPECTRA (glossy bars and peak caps), ORBIT (circular scope with echo trails) and PLASMA (shader).
  - It appears automatically after 30 s of playback with no touches on the top screen (AUTO). Tap to change mode.

## The synth UI

Each page has **one hero animation** driven by the live engine (telemetry plus an output scope), and **four colour-capped encoders**: blue, green, white and orange. Every encoder's colour is the colour of the thing it moves above it.

| Page | Hero animation | Blue | Green | White | Orange |
|---|---|---|---|---|---|
| **OSC** | Two morphing waveforms (stroke weight shows the mix), osc 2 drifting with detune, live scope below | Shape 1 | Shape 2 | Detune | Mix |
| **FILTER** | Live frequency response with resonance peak; the harmonics of the note you're holding visibly get eaten | Cutoff | Reso (peak dot) | Env amount (sweep bracket) | Key track (harmonics) |
| **AMP / F·ENV** | ADSR drawn as four coloured segments; a dot rides the curve at the engine's real envelope level | Attack | Decay | Sustain | Release |
| **LFO** | A "turntable": the wave wrapped around a circle with a hand sweeping at the real phase, plus a scrolling trail | Rate (hand) | Depth (tip + trail) | Wave (ring) | Target |

While you turn an encoder, its name and value fill the corner of the hero. Double-tap an encoder to reset it.
Animations read state only in the draw phase and reuse all buffers. A 120 Hz frame never recomposes or allocates.

## Sounds

- **Synth presets.** 33 factory sounds in 7 families: 808, BASS, LEAD, KEYS, PLUCK, PAD and WEIRD. Tap the name to browse, or use ◀ ▶. SAVE stores your own presets on the device. Long-press one of YOUR presets to delete it.
- **DICE / MUTATE (OP-XY style).** DICE rolls a brand-new playable sound within the selected family. MUTATE nudges the current one. ↶ undoes either.
- **808 voice.** The PITCH page has a pitch envelope (the boom) and per-oscillator octaves. The VOICE page has glide, mono legato (slides), drive and noise.
- **Drum synth (DRM).** Its own track and mixer strip, with 8 sounds built from 10 models (kick, snare, clap, hat, tom, rim, cowbell, cymbal, zap, shaker) and 12 factory kits. You get a TR-style grid, DICE BEAT, kit save/load and per-sound dice. BIT the drummer plays along, in each sound's colour.
- **Punch-in FX.** Every FX pad has a live glyph that shows what the effect is doing. **TILT** (FX deck) lets you lean the Thor to steer held effects, and shake it to dice.

## Controls

Everything can be driven from the Thor's buttons. **Hold SELECT** to see this map on screen.

**Navigation: SELECT is Shift.** It works in every view.

| SELECT + | |
|---|---|
| **A** | **NAV cursor** on/off. The D-pad moves a ring over *every* on-screen control, A presses, L1/R1 switch between the top and bottom screens, and B leaves NAV. |
| **D-pad ←/→** | top-screen view: SAMPLE · SYNTH · DRUMS · STEPS · MIXER · VISUAL |
| **D-pad ↑/↓** | bottom deck: PADS · KEYS · DRUMS · STEP · FX |
| **L1 / R1** | tempo −1 / +1 BPM (hold to keep going) |
| **X** | tap tempo |
| **Y** | metronome |
| **B** | diagnostics (live sensors, SAVE LOG to Downloads) |
| **L3 / R3** | cycle theme / glass look |
| *(tap SELECT alone)* | record |

**B** is Back: it leaves the visualizer and closes the step editor. It never quits the app.

| | Sampler view | Synth view |
|---|---|---|
| **D-pad** | move the pad cursor on the 4×4 grid | ←/→ page · ↑/↓ keyboard octave |
| **A / R1** | hold = play the selected pad | hold = play root note / chord |
| **L-stick flick** | ←/→ pitch ±1 st · ↑/↓ ±12 st | (sticks are the encoders) |
| **Sticks** | n/a | the four encoders: L-stick X = blue, Y = green; R-stick X = white, Y = orange |
| **X** | cycle play mode | chord mode |
| **Y** | reset pitch | reset page |
| **START** | play/stop | same |
| **L1 (hold)** | note repeat | note repeat |
| **L2 / R2** | analog filter / stutter punch-in | same |

**DRM view (drum synth):** D-pad ←/→ step · ↑/↓ sound · A toggle step · R1 hold = play the sound · sticks = the 4 encoders · X dice beat · Y undo · L3 next kit · R3 dice kit.
**Synth presets:** L3 = next preset · R3 = DICE a new sound.

**SEQ view:** D-pad moves the step cursor and edits the current lane · A toggles a step · X dice · Y undo.
**MIXER:** D-pad selects a strip and nudges it ±1 dB · A mute · Y solo · X opens that track · L-stick Y volume · R-stick X pan. **+ TRACK** adds a sampler, synth or drum track (up to 8). **⋯** on a strip opens PUMP (sidechain), clear and remove.

Switching the bottom deck tab (PADS ↔ KEYS) switches the top view (SMP ↔ SYN) too. The rail on the top screen does the reverse.

## Download

Grab the latest APK from **[Releases](../../releases)**, open it on the Thor and allow *Install unknown apps*. New versions install over old ones.

## Build

1. Install a recent Android Studio, with **NDK** and **CMake 3.22.1** from *SDK Manager → SDK Tools*. If they're missing, AGP downloads them.
2. Open the folder, let Gradle sync (AGP 8.7.3, Kotlin 2.0.21, Oboe 1.9.0), and run on the Thor over USB or wireless debugging.
3. Command line: `./gradlew :app:installDebug`. Engine tests without Android: `tools/run-host-tests.sh`.
4. Release builds are minified with R8. They're signed only when `keystore.properties` exists (git-ignored) or `THORBOX_KEYSTORE*` env vars are set; otherwise they come out unsigned.
5. **Publishing a release:** `git tag v0.7.0-alpha.1 && git push origin v0.7.0-alpha.1`. The Release workflow tests, builds and signs the APKs and attaches them to a GitHub Release. Tags containing `-` are marked pre-release.

## Device test checklist

1. Both screens light up. The footer `2nd:` line reads *Presentation → …* or *Activity → …*.
2. Send us the footer's `displays:` line from your Thor (it tells us how the firmware exposes the panels).
3. Load a melodic loop: KEY and BPM fill in. Drum one-shots show `—` on purpose.
4. The footer shows `AAudio EXCLUSIVE` and `xruns 0`. For a precise number, measure tap-to-tone latency with OboeTester.
5. KEYS deck: chords, glissando, octave shift, key lock follows the loaded sample. No stuck notes after a lid close.
6. The synth sticks: each stick axis moves its coloured encoder smoothly.
7. Leave a LOOP or a held pad playing for 10 minutes: `xruns` stays at 0.

## Architecture

```
core-model/  immutable, serializable project model (Sample, Slice, Pad, SynthPatch, Clip, Scene, Project)
engine/      C++20 real-time engine + Kotlin facade (NativeEngine.kt)
  AudioEngine   Oboe stream (LowLatency/Exclusive, native rate, 2× burst), command drain, mixing, telemetry
  SamplerVoice  varispeed sample playback (Hermite, fast path), de-click envelope, one-shot/gate/loop
  Synth.h       8-voice 2-osc morph synth, PolyBLEP, Simper SVF, ADSRs, LFO, control-rate modulation
  SpscQueue     lock-free single-producer/single-consumer ring (the only way into the audio thread)
  Decoder       platform MediaExtractor/MediaCodec → float, bounds-checked
  Analysis      key (chroma + Krumhansl profiles) and BPM (spectral-flux ACF + loop-length snap)
  Sequencer.h   clock, patterns, MPC swing, probability, note repeat, metronome (sample-accurate)
  MasterFx.h    punch-in master FX on a ring buffer of the dry mix; click-free crossfades
  jni_bridge    the trust boundary: validates every value
app/
  AppState      process-wide state; one Choreographer tick polls the engine, telemetry and sticks
  dual/         screen roles by geometry; Presentation, with a SecondScreenActivity fallback
  input/        context-aware gamepad router (sticks = encoders)
  synth/        knob spec: curves, formats, colour mapping, engine param ids
  groove/       GrooveLogic (scales, quantize, steps, generators: pure, JVM-tested) + GrooveController
  ui/           TopScreen (rail + SMP/SYN/SEQ/MIX), SynthScreen + SynthViz, SeqScreen, MixScreen, DeckScreen + KeysDeck + FxDeck
```

**Threading:**
- Kotlin calls the engine from the main thread only.
- The audio thread never allocates, locks or logs.
- The UI reads the engine through relaxed atomics and a scope ring.

## Performance

- **Audio:** worst case is 8 synth voices, a dense 2 × 400-event pattern with ratchets and p-locks, and all 7 punch-in FX. That uses about **1% of the real-time budget on a desktop CPU**, so expect roughly 3–5% on the Thor's cores. Measure it with `tools/bench.sh`.
  - The callback never allocates, locks or logs.
  - FFTs, time-stretching, decoding and pattern building all run off the audio thread.
- **UI:**
  - Live values are read only in draw lambdas, so a 120 Hz frame never recomposes.
  - Waveforms and brushes are cached per size, and only the playhead redraws each frame.
  - Glass redraws at ≤60 Hz (≤30 Hz in SUBTLE).
  - The visualizer FFT runs only when something shows it.
  - Pattern edits are sent to the engine at most once per frame.
- **Build:**
  - Native code is built `-O3` with ThinLTO for `armv8.2-a` (every Thor variant).
  - R8 for release.
  - ProfileInstaller applies Compose's baseline profiles to sideloaded installs.

## Roadmap

Next: Serato-style CHOP (16 pads, auto/equal/lazy slicing, KEY MATCH, BPM SYNC time-stretch) · scratch deck + SP-404-style DJ filter and isolator EQ · project save, stem/MIDI export, Wi-Fi Transfer + wired export · Ableton Link (opt-in) · Session clip grid · resampling · strum deck · Performance Mode.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) (it covers the real-time rules), [SECURITY.md](SECURITY.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Copyright © 2026 the Clamshell Beats contributors. [GPL-3.0-or-later](LICENSE). Third-party notices are in [NOTICE](NOTICE).
