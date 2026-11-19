# Contributing to Clamshell Beats

Thanks for helping! Clamshell Beats is a touch-first sampler/groovebox for the AYN Thor dual-screen
handheld, built on Oboe (C++) and Jetpack Compose.

## Ground rules
- **License:** contributions are accepted under **GPL-3.0-or-later**. Every source file starts with
  `SPDX-License-Identifier: GPL-3.0-or-later`.
- **Sign-off (DCO):** commit with `git commit -s` to certify the
  [Developer Certificate of Origin](https://developercertificate.org/). We don't use a CLA.
- Be kind. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Build and test
```bash
./gradlew :app:assembleDebug        # needs Android SDK + NDK; see README
tools/run-host-tests.sh             # C++ engine tests on desktop, ASan + UBSan (no Android needed)
```
A PR must keep `tools/run-host-tests.sh` green and add tests for new DSP.

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
- Kotlin: official style (`kotlin.code.style=official`). C++: C++20, 4-space indent, `mCamelCase` members.
- Keep JNI (`jni_bridge.cpp`) boring: validate, then marshal. Logic goes in the engine.
- Commit messages: imperative mood (“Add LFO S&H shape”), explaining the *why* in the body.
