## What & why

## Checklist
- [ ] `tools/run-host-tests.sh` passes; new DSP has tests
- [ ] No allocation / locks / logging / JNI on the audio thread (see CONTRIBUTING.md)
- [ ] Per-frame UI values are read in draw lambdas only
- [ ] New files carry `SPDX-License-Identifier: GPL-3.0-or-later`
- [ ] Commits are signed off (`git commit -s`)
- [ ] Tested on a Thor (or say what you couldn't test)
