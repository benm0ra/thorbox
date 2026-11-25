# Contributing to Clamshell Beats

Thanks for helping! Clamshell Beats is a touch-first sampler/groovebox for the AYN Thor dual-screen
handheld, built on Oboe (C++) and Jetpack Compose.

## Ground rules
- **License:** contributions are accepted under **GPL-3.0-or-later**. Every source file starts with
  `SPDX-License-Identifier: GPL-3.0-or-later`.
- **Sign-off (DCO):** commit with `git commit -s` to certify the
  [Developer Certificate of Origin](https://developercertificate.org/). We don't use a CLA.
- Be kind. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Start here
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** maps the code: modules, threads, which file owns what, and how to add an engine feature or a page.
- **[docs/ART_STYLE.md](docs/ART_STYLE.md)** is the house style (NIGHT LINE '79). All art and UI chrome go through `GraffKit.kt`, so new drawings match the rest.
- **[docs/MANUAL.md](docs/MANUAL.md)** is what the app does, from the player's side.
- Issues labelled **good first issue** are small and self-contained.

## Workflow
1. Branch from `main` (`feat/…`, `fix/…`, `docs/…`). Keep a PR to one idea; small PRs get reviewed fast.
2. Run the tests you can (below), then push and open a PR against `main`. The template has the checklist.
3. CI builds, lints and runs every test. It also launches the app on an emulator and screenshots every page and theme. For a UI change, look at the screenshots:
   ```bash
   git fetch origin ci-shots && mkdir -p /tmp/shots && git archive origin/ci-shots | tar -x -C /tmp/shots
   ```
   Say in the PR what changed on screen, ideally with a before/after.
4. Green CI and a maintainer's review merge it. `main` is always installable: every push updates the rolling **dev** build.

## Build and test
```bash
./gradlew :app:assembleDebug                          # needs Android SDK + NDK; see README
tools/run-host-tests.sh                               # C++ engine tests on desktop, ASan + UBSan (g++ or clang; no Android)
./gradlew :core-model:test :app:testDebugUnitTest     # project format, presets, pure logic (JVM)
./gradlew :app:lintDebug                              # zero findings expected; app/lint.xml says what's ignored and why
tools/smoke-test.sh                                   # with an emulator or device attached: launch + screenshots
```
- A PR must keep all of these green.
- New DSP and new project-file fields need tests. A new field needs a default, so old projects still open.
- On a Thor, `tools/thor-dev.bat` installs the newest green build and `tools/thor-shots.bat` screenshots both screens. See the README.

## The real-time rules (non-negotiable)
Code reachable from `AudioEngine::onAudioReady` must never:
1. allocate or free memory (`new`, `malloc`, `std::vector` growth, `std::string`, `std::function` captures);
2. lock (`std::mutex`, `synchronized`), wait, sleep or make syscalls (including `__android_log_print`);
3. call into Java/JNI, or do file I/O;
4. have an unbounded loop that depends on input data.

Everything reaches the audio thread through `SpscQueue` as a POD `Command`. Everything leaves it
through `SpscQueue<AudioEvent>` or relaxed atomics (telemetry). If you're unsure, ask in the PR.

## Threading rule for Kotlin
All `NativeEngine` calls happen on the **main thread**, which is the single producer of the command
queue. `NativeEngine.peaks()` is the only exception.

## UI rules
- **Zero friction:** core actions are one tap and never sit in nested menus or modal dialogs.
- **Per-frame values are read in draw lambdas only** (`Canvas`, `drawBehind`, `drawWithCache`),
  never in composition. Steady-state frames must not allocate: reuse `Path`s and remember `TextStyle`s.
- **Touch targets** are at least 44 dp. Pads fire on touch-down through `pointerInteropFilter`.
- **Encoder colours** (blue / green / white / orange) map 1:1 to what they draw on the page above.
- Original artwork only. Draw inspiration from other instruments, but never copy their graphics,
  icons, characters or fonts.

## Style
- Kotlin: official style (`kotlin.code.style=official`, `.editorconfig`). C++: C++20, 4-space indent, `mCamelCase` members.
- Big files are fine when they're sectioned (`// ===== SECTION =====`). Put new code in the section it belongs to, or give it its own file when it stands alone (as `export/Exporter.kt` does).
- Comments say *why*, and the player-facing words on screen use the same names as the manual.
- No dead code: delete it (git remembers). Lint and the compiler should stay warning-free.
- Keep JNI (`jni_bridge.cpp`) boring: validate, then marshal. Logic goes in the engine.
- Commit messages: imperative mood (“Add LFO S&H shape”), explaining the *why* in the body.
