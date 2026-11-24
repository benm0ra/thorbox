# Architecture

A map of the code for people working on it. For what the app does, read the [manual](MANUAL.md). For
how to contribute (rules, tests, PRs), read [CONTRIBUTING.md](../CONTRIBUTING.md).

## The big picture

```
 touch / gamepad / tilt
        │
        ▼
  Compose UI (app/ui) ──reads──► AppState + controllers (app/…)      main thread
        │                              │
        └──── calls ───────────────────┤
                                       ▼
                          NativeEngine.kt (engine/, Kotlin facade)
                                       │ JNI (jni_bridge.cpp: validate, then marshal)
                                       ▼
                     AudioEngine (C++) ── Command ──► SpscQueue ──►  audio thread
                                       ◄── AudioEvent / atomics ──    (Oboe callback)
```

- There are three Gradle modules:
  - **`core-model`** is plain Kotlin and JVM-tested. It holds the project model, the file format, presets and the MIDI writer.
  - **`engine`** is the C++ real-time engine plus its Kotlin facade, `NativeEngine`.
  - **`app`** is everything Android: state, controllers, UI and input.
- **Two threads matter.**
  - The **main thread** owns all app state and is the only producer of engine commands.
  - The **audio thread** (the Oboe callback) owns all DSP state. It never allocates, locks, logs or calls Java.
  - Two other threads exist: a worker thread inside the engine decodes and time-stretches samples, and coroutines handle file I/O.
- **One frame clock.** `AppState`'s Choreographer callback runs once per vsync. It polls engine events, reads telemetry, runs `GrooveController.onFrame` (pattern changes, song steps, automation, meters), integrates the sticks, and bumps `telemetry.frame`. Canvas code that animates reads `telemetry.frame` inside its draw lambda, so a frame redraws without recomposing.

## engine/ (C++20, `engine/src/main/cpp`)

| File | What it is |
|---|---|
| `AudioEngine.h` | The engine class: every command, buffer and telemetry atomic. Start here. |
| `AudioEngine.cpp` | Part 1: stream lifecycle, the main-thread API (each call pushes a `Command`), sample loading on the worker, polling. |
| `AudioRender.cpp` | Part 2: **the audio thread**. `onAudioReady` → `renderFloat` (drain commands, sequencer, voices) → `mixChunk` (strips, DUCK, master FX, master, meters). |
| `AudioExport.cpp` | Part 3: the offline bounce (EXPORT). It parks the live callback and renders on the main thread. |
| `Commands.h`, `SpscQueue.h` | The `Command` / `AudioEvent` types and the lock-free ring that carries them between threads. |
| `Sequencer.h` | Clock, patterns, swing, probability, ratchets, note repeat, metronome. It also does **scenes**: pattern changes swapped in on an exact sample. |
| `SamplerVoice.h`, `Synth.h`, `DrumSynth.h` | The three instruments. |
| `MasterFx.h`, `Ducker.h`, `Loudness.h` | The 12 punch-in FX, the sidechain ducker (DUCK) and the R128 meter. |
| `Decoder.*`, `PcmFile.h`, `Stretch.h` | Decoding through MediaCodec, our own WAV/AIFF reader, and time-stretch. |
| `Analysis.*`, `Spectrum.h` | Key and BPM detection, transients; the visualizer and analyzer spectra. |
| `Export.h` | Streaming WAV and FLAC writers (our own FLAC encoder). |
| `jni_bridge.cpp` | The trust boundary. It validates every value from Kotlin before the engine sees it. |

**Adding an engine feature** usually takes five steps:
1. Add a `CmdType` and its handling in `AudioEngine::handleCommand` (AudioRender.cpp).
2. Add a main-thread method that pushes the command (AudioEngine.cpp).
3. Add a JNI function (`jni_bridge.cpp`).
4. Add a `NativeEngine` method.
5. Add a test in `engine/src/test/cpp`. The host tests compile the engine against tiny Oboe/Android stubs and run it under ASan and UBSan, so no device is needed.

## core-model/ (`com.thorbox.model`)

- `Models.kt` has the domain types: samples, pads, synth patches, clips and notes, tracks.
- `ProjectFile.kt` is the saved project, as versioned JSON. New fields need defaults so old projects still open. `ProjectCodec` encodes and decodes it, and `ProjectCodecTest` / `SongTest` cover round trips.
- `Duck.kt`, `FactoryPresets.kt` and `MidiWriter.kt` hold the DUCK settings and presets, the factory sounds, and the .mid export.

## app/ (`com.thorbox.app`)

| Package / file | What it owns |
|---|---|
| `AppState.kt` | The app's state root: tracks, the current view, settings, loading, the mic, the frame ticker. One instance per process. |
| `groove/GrooveController.kt` | Everything time-based: transport, the 8 tracks' clips, **patterns and SONG**, DUCK, the mixer, punch-in FX, automation, live recording. |
| `groove/GrooveLogic.kt` | Pure functions (scales, quantize, steps, generators), JVM-tested. |
| `groove/Automation.kt` | Automation lanes: recording and playback. |
| `sampler/SamplerController.kt` | One per sampler track: chops, pads, pitch, stretch, KEY LOCK matching. |
| `drums/DrumController.kt` | One per drum track: the kit and its voices. |
| `export/Exporter.kt` | EXPORT. It drives the offline bounce from the frame callback and writes the files through MediaStore. |
| `project/ProjectManager.kt` | Save, open and autosave projects, with a crash guard for opening. |
| `input/` | `InputRouter` (gamepad → actions by context), `Bindings` (what each button does where, which also feeds the cheat sheet), `Nav` (the NAV cursor), `TiltController`. |
| `dual/` | The two screens: which composable goes on which display (a Presentation, or a second Activity as fallback). |
| `ui/` | Compose screens, one file per page (`SongScreen`, `DuckScreen`, `MixScreen`, …), plus `Theme` / `Tokens` / `Components` for the look and `AnimKit` for spring animation. The characters live in `BiscuitCat`, `Drummer` and the rat in `DuckScreen`. |

**Adding a page** takes four steps:
1. Add a value to `TopView` (AppState).
2. Add a rail button and a branch in `SessionScreen`.
3. Add a `PlayCtx` in `Bindings` (buttons and the cheat sheet) and its handling in `InputRouter`.
4. Add a debug launch extra in `MainActivity` and a screenshot in `tools/smoke-test.sh` (and `thor-shots.ps1`).

## Rules that keep it fast

These rules are enforced in review:
- **Audio thread:** no allocation, locks, syscalls, logging or JNI. Anything crossing threads goes through `SpscQueue` or atomics.
- **Main thread only** talks to `NativeEngine`. The command queue has a single producer.
- **Compose:** a value that changes every frame is read inside a draw lambda (`Canvas`, `drawBehind`), or through `derivedStateOf` when text depends on it. It is never read directly in composition. Steady-state frames don't allocate: remember `Path`s, `TextStyle`s and brushes.
- **Work only when it shows.** The visualizer FFT, scope reads and glass redraws all check whether anything is on screen first.

## Tests and CI

| Layer | How to run it | What it covers |
|---|---|---|
| Engine | `tools/run-host-tests.sh` | DSP, sequencer scenes, FX, DUCK, the FLAC/WAV writers, offline == live, under ASan/UBSan |
| Model + logic | `./gradlew :core-model:test :app:testDebugUnitTest` | the project format, presets, GrooveLogic |
| Lint | `./gradlew :app:lintDebug` | `app/lint.xml` lists what's deliberately ignored |
| On an emulator | `tools/smoke-test.sh` (CI runs it) | It launches the app on one and two screens, screenshots every page and theme, loads files, runs an EXPORT and decodes it. |
| On a Thor | `tools/thor-dev.bat`, `tools/thor-shots.bat` | install the newest green build; screenshot both real screens |

CI (`.github/workflows/ci.yml`) runs all of the above on every PR. It also:
- publishes the emulator **screenshots to the `ci-shots` branch** (`git fetch origin ci-shots`), so UI changes can be reviewed without downloading artifacts;
- updates the rolling **`dev` pre-release**, which `thor-dev` installs. Tagging `v*` makes a signed release (`release.yml`).

### Debug launch extras

Debug builds accept `adb shell am start` extras that put the app into a given state, for screenshots and tests. The full list is in `MainActivity.applyLaunchExtras`:
- `view`, `theme`, `deck`, `toponly`, `settings`, `synth`, `play`;
- `songdemo`, `duckdemo`, `duckpreset`, `keylock`, `keysheet`;
- `padedit`, `exportsheet`, `exportnow`, `fx`, `latch` and more.

For example:

```
adb shell am start -n com.clamshellbeats.app/com.thorbox.app.MainActivity --es view SONG --ez songdemo true
```
