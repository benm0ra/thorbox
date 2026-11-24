## What & why
<!-- What changes for the player, and why. Link the issue: "Fixes #12". -->

## On screen
<!-- For UI changes: before/after from the ci-shots branch (see CONTRIBUTING) or a Thor. Otherwise "none". -->

## Checklist
- [ ] Tests pass: `tools/run-host-tests.sh`, `./gradlew :core-model:test :app:testDebugUnitTest`, lint
- [ ] New DSP or project-file fields have tests (new fields have defaults, so old projects open)
- [ ] No allocation / locks / logging / JNI on the audio thread; per-frame UI values read in draw lambdas
- [ ] New files carry `SPDX-License-Identifier: GPL-3.0-or-later`; commits are signed off (`git commit -s`)
- [ ] A line in `CHANGELOG.md` (player-facing changes), and the manual updated if a page changed
- [ ] Tested on a Thor (or say what you couldn't test)
