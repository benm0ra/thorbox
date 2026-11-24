# Changelog

What changed, newest first. Releases are the `v*` tags; everything since the last tag is under
**Unreleased**. Add a line here in the same PR as the change, written for players (what it does for
them), not for developers.

## Unreleased (alpha)

### Developer
- The engine is split by thread (`AudioEngine.cpp` for the main thread, `AudioRender.cpp` for the audio thread, `AudioExport.cpp` for the offline bounce). There's a code map in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), dead code is gone, and lint has zero findings.

### Player-facing
- **A new fit for the DUCK rat:** a proper hoodie with sleeves, baggy denim stacked on Timbs, a backwards fitted, a gold chain and headphones round his neck (the theme colour is on the sleeve stripes and cap button now, not the whole hoodie). Each kick now hits with a BOOM burst, dust off his boots, a puff through the grate and a little platform shake, all as big as the dip. The tunnel is a real arch.
- **Patterns up to 64 bars**, any length you like (÷2 · − · + · ×2), in every editor. A bar strip shows where the notes are and turns the page, and FOLLOW keeps the page on the playhead.
- **RANDOM chop:** 16 slices cut at random places, on the hits where it can. Every tap is a new flip.
- **Zoom the waveform:** pinch to zoom in and out, slide to move, and drag markers to the sample. Double-tap or FIT shows the whole sample again.
- **Metronome:** four sounds (CLICK, WOOD, COWBELL, HAT), level, beat-1 accent, a 1- or 2-bar count-in before recording with a big countdown, and click only while recording (⚙ → AUDIO).
- **⚙ → AUDIO:** choose the output (speaker, headphones, USB interface, HDMI, Bluetooth) and the field recorder's input.
- **⚙ → MIDI:**
  - Play the app from a USB or Bluetooth MIDI keyboard or pad controller, with CCs for filter, LFO, volume and pan.
  - Follow outside MIDI clock and START / STOP.
  - Send clock, and any track's notes on its own channel, to drive outboard gear, timed to what you hear.
- **A clearer SONG page.** Patterns (your loops) on the left, the song (the order they play in) on the right and as a timeline across the top, with the play button's job and what's playing spelled out in words.
- **EXPORT to WAV / FLAC, with stems.** Bounce the song (or a stretch of it, or a pattern × N) faster than real time: WAV or FLAC, 24 / 16-bit, 48 / 44.1 kHz, and optionally a stem per track (dry, so they sum to the mix, or with the master FX printed on each) and a MIDI file of the same bars. It renders the same song steps, automation and ducking as live playback, sample for sample (host-tested), and our own FLAC encoder is checked bit-exact against a decoder and ffmpeg. Files go to *Music/Clamshell Beats/Exports* and **SHARE** sends them on. Automation is now kept per pattern.
- **PATTERNS + SONG.** There are 16 patterns per project, each a pattern for every track (MPC-style sequences). Launch one and it changes exactly on the bar line: the engine swaps it in at the right sample, so it's never late. Chain patterns into a **song** (× repeats, reorder, loop or stop at the end), copy a pattern to start a variation, and save it all with the project.
- **KEY LOCK.** Pick a key and lock it. Every sampler track key-matches to it (new samples too), synth patterns snap into the scale as they play (your notes are kept), and the keys stay in scale. Change the key and everything follows.
- **Characters with real motion:** spring physics (follow-through, anticipation, squash and stretch) for the rat, the cat and BIG DAWG, tuned with an offline motion sim (`tools/anim-sim`).

- **DUCK, a sidechain page for the whole mix.** One key (the kick alone, any track, or a ghost BEAT) ducks any set of tracks: WEIGHT, SQUASH, SNAP, BOUNCE, PUMP and LOUD are real threshold, ratio, attack, release, mix and makeup. There are four presets (BOOM BAP POCKET, HEAVY DILLA PUMP, SUBTLE GLUE, LO-FI GHOST), a gain-reduction meter with the last 3 s of the pump, and an NYC subway rat on the DUCK ST platform. He holds a slice overhead and squashes by the real gain reduction, then rises on the release. The mixer's PUMP moved here (older projects carry over).

- **Sampler: 1 AT A TIME** (the default). Playing a pad silences the pad before it, so chops never pile up; turn it off in PAD EDIT → MUTE to layer them. Mute groups and targets still work either way.
- **Sampler: transpose ALL.** The PITCH − / + buttons switch between the selected pad and **ALL** pads (a track transpose on top of each pad's pitch, saved with the project).
- **Bottom screen:** a status line where the empty NAV band was (track, sample · pad · mode · 1 AT A TIME, key, kit, bar.beat with beat lights), and pads that show their slice's waveform, play mode, mute group, pitch and length.
- **Light themes:** the synth cat's "closed filter" shading no longer spills over the waveform in Pastel and Vintage, and the NAV cursor no longer hides small labels.

- **Sampler**
  - **Faster loading:** MP3/FLAC/OGG/M4A decode as fast as the phone's decoder allows (the old loop capped it at ~10–15× realtime), and key/tempo detection on long songs listens to excerpts (a 4-minute song ~2.5× faster, 10 minutes ~7×). Long files load their first 10 minutes; WAV/AIFF are read directly; a **loading card** shows each step with an estimate and **CANCEL**.
  - **A waveform that tells you things:** each slice in its pad's colour, RMS body inside a peak outline, clip marks, a time ruler, a beat/bar grid from the detected BPM, length · rate · channels · peak, the selected slice's start → end, and the playhead's time.
  - **■ STOP** lights while anything on the sampler sounds and cuts it all (R3 on the controller).
  - **PAD EDIT**, styled like a hardware sampler's backlit LCD with F1–F6 soft keys: tune, fine, level, pan, reverse, warp · attack, decay, release, velocity → level · low-pass cutoff + resonance · **mute groups** (choke: open/closed hat) and two **mute targets** · ONE SHOT / NOTE ON / LOOP and MONO / POLY. Each page has a picture of what it does. Saved with the project.
  - **Pattern lane under the waveform:** tap a step to put the selected pad there, long-press to clear; **SEQ CHOPS** writes the chops in their original order and timing as a starting point; **●** records live, **✕** clears, **SWING** sits right there.
  - **Note repeat fixed:** in NAV mode L1 only switched screens; now L1 hold = note repeat, L1 tap = top screen. RPT on the decks latches with a tap. 16 LEVELS repeats too.
  - **Field recorder:** MIC input (it used the raw source at −52 dBFS), ▶ PLAY the take, KEEP → PADS lays it on a sampler track.
- **Synth:** half the screen is now **a cat in shades making biscuits** on something that suits the sound family (pillow, waterbed, speaker, turntable, keyboard, guitar, slime), and she reads the patch: her tail is the waveform, detune brings a twin, the cutoff is the room's light, resonance buzzes her whiskers, attack/release set her kneading, the LFO flies a moth, octave sets her size, a pitch drop makes her hop, poly notes bring kittens, drive grows a mohawk, noise makes her fur crackle. Turning a knob captions what it does to her.
- **BIG DAWG, redrawn:** a real right-handed kit seen from the crowd (kick front and centre, rack tom on the kick, snare between his knees, hi-hat with his foot on the pedal, crash, floor tom and ride), one shell finish per kit, a rug; proper elbows (two-bone IK) and short bulldog arms; a hoodie, jeans and sneakers. The bodega corner reacts to the kit: shutter rattle and a glowing piece on the kick, brick flash and flapping posters on the snare, dust and the OPEN sign on the hats, a crash lighting the corner, BODEGA neon following the level, a pumping boombox and a rippling puddle.
- **Mixer, rebuilt:** pan moved above the fader and only follows sideways drags (raising a fader from the bottom used to grab the pan); the fader moves relative to your finger with a dB scale and a double-tap to 0 dB; 28-segment LED meters with peak hold and a clip light; a colour band per track.
- **Themes:** two new ones (**Bodega**, navy + neon; **Vintage**, warm light grey) for eight in all; deeper pads and readable muted text in Pastel; Grayscale pads alternate so neighbours separate. Text on any coloured button or tab now picks dark or light by contrast (light themes' selected tabs used to go blank). **⚙ → LOOK** shows each theme as a live preview tile.
- **UI:** small text is centred everywhere (it sat low and clipped in small chips), drum rows show full names, drum pads share one text size, the characters sit on the left of both instrument screens.
- **Engine:** per-pad sound (pan, envelope, SVF filter, reverse, choke, poly, velocity), StopPads, and [min, max, rms] waveform data; host tests cover all of it.
