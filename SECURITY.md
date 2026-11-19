# Security policy

## Supported versions
Only the latest `main` and the most recent release get security fixes.

## Reporting a vulnerability
**Please don't open a public issue for security problems.** Report privately through GitHub's
*Security → Report a vulnerability* (private advisory) on this repository. Include:
- what you found and where (file / function), and the impact you expect;
- a reproducer (for parser bugs, the smallest audio file that triggers it);
- the device, Android version and app version.

We aim to acknowledge within 7 days and to ship a fix or mitigation within 30 days for
high-severity issues. You'll be credited in the release notes unless you'd rather not be.

## Threat model (what we defend)
Thorbox is an **offline** instrument. It requests **no Android permissions**, has **no network
access** (no `INTERNET` permission) and no accounts, analytics or ads.

The main attack surface is **untrusted audio files**:

| Surface | Defence |
|---|---|
| File parsing / decoding | Platform `MediaExtractor`/`MediaCodec` (hardened, updated through Play system updates). Inputs over 1 GB, non-regular files, sample rates outside 8 kHz–384 kHz, more than 32 channels and audio over 10 minutes are refused. Codec-reported offsets are bounds-checked against the buffer they describe. NaN/Inf samples are scrubbed, and all samples are clamped. |
| JNI boundary (`jni_bridge.cpp`) | Every value is validated: finite floats, integer ranges, array sizes. This file is compiled without `-ffast-math`, so the checks can't be optimized away. |
| Real-time audio thread | No allocation, locks or syscalls. Its only inputs are fixed-size POD commands through a bounded lock-free queue. Sample buffers are refcounted and freed only after the audio thread releases them, so there is no use-after-free. |
| Storage | Read-only, single-folder access through the Storage Access Framework. The previous folder's grant is released when you pick a new one. Backups and device transfer are disabled (`dataExtractionRules`). |
| Native binary | `-z relro -z now -z noexecstack`, stack protector, `_FORTIFY_SOURCE` (NDK defaults), hidden symbol visibility (only JNI entry points are exported), 16 KB page alignment. |
| Release builds | R8 minification. Signed only with a maintainer key from `keystore.properties` or CI secrets, never the public debug key. |
| Supply chain | Gradle wrapper validation in CI, Dependabot for Gradle and Actions, a minimal dependency set (Oboe plus AndroidX/Kotlin). |

Host tests run under AddressSanitizer and UndefinedBehaviorSanitizer in CI (`tools/run-host-tests.sh`).
A fuzzing harness for the decoder and analysis code is on the roadmap. Contributions are welcome.
