#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Launch smoke test: install the debug APK on a running emulator/device, launch it, and fail if it
# doesn't stay alive. Runs twice: single screen, then with a simulated 1080x1240 second display
# (the Thor's bottom screen). Logs and screenshots land in ./smoke/.
set -u
PKG=com.clamshellbeats.app
ACT=com.thorbox.app.MainActivity   # code package differs from the application id
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
OUT=smoke; mkdir -p "$OUT"
fail=0

run_case() {
  local name=$1
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/$ACT | tee "$OUT/$name-start.txt"
  sleep 20
  adb logcat -d -v threadtime > "$OUT/$name-logcat.txt"
  adb exec-out screencap -p > "$OUT/$name.png" || true
  if adb shell pidof $PKG >/dev/null; then
    echo "::notice::[$name] app is running after 20 s"
  else
    echo "::error::[$name] app is NOT running: it crashed or closed"
    fail=1
  fi
  # Surface Java and native crashes right in the job log.
  if grep -qE "FATAL EXCEPTION|Fatal signal|AndroidRuntime: Process|Abort message" "$OUT/$name-logcat.txt"; then
    echo "::group::[$name] crash"
    grep -E -A60 "FATAL EXCEPTION|Fatal signal|\*\*\* \*\*\* \*\*\*" "$OUT/$name-logcat.txt" | head -200
    echo "::endgroup::"
    fail=1
  fi
  echo "::group::[$name] app log"
  grep -E "thorbox|Thorbox|oboe|AAudio|$PKG" "$OUT/$name-logcat.txt" | tail -80
  echo "::endgroup::"
}

# Visit every view and deck while playing, poke the buttons, and make sure nothing crashes.
tour() {
  local name=$1
  adb logcat -c
  adb shell am start -n $PKG/$ACT --ez nav false >/dev/null; sleep 1   # the tour presses the PLAY-mode buttons
  for v in SYNTH DRUM SEQ MIX VIZ SAMPLER; do
    adb shell am start -n $PKG/$ACT --es view $v --ez play true >/dev/null; sleep 2
    for k in BUTTON_A DPAD_RIGHT DPAD_DOWN BUTTON_A BUTTON_X BUTTON_THUMBR BUTTON_Y BUTTON_B; do adb shell input keyevent KEYCODE_$k; done
    sleep 1; adb exec-out screencap -p > "$OUT/$name-view-$v.png" || true
  done
  for d in DRUMS KEYS STEP FX PADS; do
    adb shell am start -n $PKG/$ACT --es deck $d >/dev/null; sleep 2
    adb exec-out screencap -p > "$OUT/$name-deck-$d.png" || true
  done
  # Fill the song: a second synth, a second drum machine, another sampler; then play everything.
  for k in SYNTH DRUMS SAMPLER SYNTH DRUMS; do adb shell am start -n $PKG/$ACT --es add $k >/dev/null; sleep 1; done
  for v in MIX SYNTH DRUM SEQ SAMPLER; do adb shell am start -n $PKG/$ACT --es view $v --ez play true >/dev/null; sleep 2
    adb exec-out screencap -p > "$OUT/$name-8tracks-$v.png" || true; done
  # NAV cursor: walk both screens with the buttons only (move, press, switch screen, flip views/decks)
  adb shell am start -n $PKG/$ACT --ez nav true >/dev/null; sleep 1
  for k in DPAD_DOWN DPAD_RIGHT DPAD_RIGHT BUTTON_R1 DPAD_DOWN DPAD_RIGHT BUTTON_L1 BUTTON_R2 DPAD_DOWN BUTTON_Y DPAD_LEFT BUTTON_L2 DPAD_UP; do
    adb shell input keyevent KEYCODE_$k; done
  adb exec-out screencap -p > "$OUT/$name-nav.png" || true
  adb shell am start -n $PKG/$ACT --ez nav false >/dev/null; sleep 1
  # Sampler end to end: the demo break goes through load → analysis → auto-chop → pads, then plays.
  adb shell am start -n $PKG/$ACT --es view SAMPLER --es deck PADS --ez demo true >/dev/null; sleep 5
  adb exec-out screencap -p > "$OUT/$name-sampler-demo.png" || true
  if ! adb shell pidof $PKG >/dev/null; then echo "::error::[$name] app died loading the demo break"; adb logcat -d -b crash | tail -60; fail=1
  elif ! adb logcat -d | grep -q "loaded Demo break"; then echo "::error::[$name] demo break never finished loading"; adb logcat -d | grep -i thorbox | tail -30; fail=1
  else echo "::notice::[$name] sampler: demo break loaded and chopped"; fi
  # Every file type the sampler takes, through the real load path: WAV (8/16/24-bit, float), AIFF,
  # AIFF-C, and the platform-decoded ones (MP3 / FLAC / OGG / M4A) when ffmpeg is on the runner.
  gen=$(mktemp -d); python3 "$(dirname "$0")/make-test-audio.py" "$gen"
  if command -v ffmpeg >/dev/null; then
    for x in mp3 flac ogg m4a; do ffmpeg -loglevel error -f lavfi -i "sine=frequency=220:duration=2" -ac 2 "$gen/tone.$x" || true; done
  else echo "::warning::[$name] no ffmpeg on the runner: MP3/FLAC/OGG/M4A loads not tested"; fi
  # into the app's own private folder (run-as works on the debug build), so no storage permission is involved
  dev=/data/data/$PKG/files/cifiles; adb shell run-as $PKG mkdir -p files/cifiles
  for f in "$gen"/*; do
    b=$(basename "$f"); adb push "$f" "/data/local/tmp/$b" >/dev/null; adb shell chmod 644 "/data/local/tmp/$b"
    adb shell run-as $PKG cp "/data/local/tmp/$b" "files/cifiles/$b"
    adb logcat -c
    adb shell am start -n $PKG/$ACT --es view SAMPLER --es deck PADS --es loadfile "$dev/$b" >/dev/null; sleep 4
    if adb logcat -d | grep -q "loaded $b"; then echo "::notice::[$name] sampler loaded $b"
    else echo "::error::[$name] sampler could not load $b"; adb logcat -d | grep -E "Thorbox|NuMediaExtractor|NdkJavaVM|can't open" | tail -15; fail=1; fi
  done
  adb exec-out screencap -p > "$OUT/$name-sampler-loaded-file.png" || true
  for t in 0 1 2 3 4; do                                 # SETTINGS pages, then the meters strip + recorder sheet
    adb shell am start -n $PKG/$ACT --ei settings $t >/dev/null; sleep 2
    adb exec-out screencap -p > "$OUT/$name-settings-$t.png" || true
  done
  adb shell am start -n $PKG/$ACT --ei settings -1 --ez meters true --es view SYNTH --ez play true >/dev/null; sleep 3
  adb exec-out screencap -p > "$OUT/$name-meters.png" || true
  adb shell am start -n $PKG/$ACT --ez meters false --ez mic true >/dev/null; sleep 2
  adb exec-out screencap -p > "$OUT/$name-mic.png" || true
  adb shell am start -n $PKG/$ACT --ez mic false >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es deck FX --ei fx 7 >/dev/null; sleep 3      # REVERB engaged, then its tail
  adb exec-out screencap -p > "$OUT/$name-reverb.png" || true
  adb shell am start -n $PKG/$ACT --ei fx -1 >/dev/null; sleep 3
  for th in bodega vintage cyberpunk pastel terminal grayscale studio genesis; do        # every theme on the busiest screens
    for v in DRUM SYNTH; do adb shell am start -n $PKG/$ACT --es theme $th --es view $v --es deck FX >/dev/null; sleep 2; done
    adb exec-out screencap -p > "$OUT/$name-theme-$th.png" || true
    adb shell am start -n $PKG/$ACT --es view MIX --es deck PADS >/dev/null; sleep 2
    adb exec-out screencap -p > "$OUT/$name-theme-$th-mix.png" || true
    mkdir -p "$OUT/shots"; cp "$OUT/$name-theme-$th.png" "$OUT/shots/theme-$th.png" 2>/dev/null; cp "$OUT/$name-theme-$th-mix.png" "$OUT/shots/theme-$th-mix.png" 2>/dev/null
  done
  adb logcat -d -v threadtime > "$OUT/$name-tour-logcat.txt"
  if ! adb shell pidof $PKG >/dev/null || grep -qE "FATAL EXCEPTION|Fatal signal" "$OUT/$name-tour-logcat.txt"; then
    echo "::error::[$name] crashed during the UI tour"
    grep -E -A60 "FATAL EXCEPTION|Fatal signal" "$OUT/$name-tour-logcat.txt" | head -150
    fail=1
  else
    echo "::notice::[$name] UI tour OK (all views + decks)"
  fi
}

# Clean screenshots for reviewing the UI (published to the ci-shots branch by CI): Thor-top-sized
# landscape, scratch session, demo break + a pattern on every track, playing.
shots() {
  local d="$OUT/shots"; mkdir -p "$d"
  adb shell wm size 1920x1080; adb shell wm density 360; sleep 2
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/$ACT --ez scratch true >/dev/null; sleep 6
  adb shell am start -n $PKG/$ACT --ez demo true --es view SAMPLER >/dev/null; sleep 6
  adb shell am start -n $PKG/$ACT --ez dice true --ez play true >/dev/null; sleep 3
  adb exec-out screencap -p > "$d/sampler.png"
  # sampler: the waveform with a slice playing, then PAD EDIT's five pages
  adb shell am start -n $PKG/$ACT --es view SAMPLER >/dev/null; sleep 2; adb exec-out screencap -p > "$d/wave.png"
  for pg in 0 1 2 3 4; do adb shell am start -n $PKG/$ACT --es view SAMPLER --ei padedit $pg >/dev/null; sleep 1.5; adb exec-out screencap -p > "$d/padedit-$pg.png"; done
  adb shell am start -n $PKG/$ACT --ei padedit -1 >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view DRUM >/dev/null; sleep 3
  for i in 1 2 3 4 5 6; do adb exec-out screencap -p > "$d/drums-$i.png"; sleep 0.35; done
  adb shell am start -n $PKG/$ACT --es kit CRATE_DUST --ei drumpage 2 >/dev/null; sleep 3
  adb exec-out screencap -p > "$d/drums-dust.png"
  adb shell am start -n $PKG/$ACT --ei drumpage 0 >/dev/null; sleep 1
  for v in SYNTH SEQ MIX VIZ; do adb shell am start -n $PKG/$ACT --es view $v >/dev/null; sleep 3; adb exec-out screencap -p > "$d/$v.png"; done
  # the cat on the SYNTH screen: one shot per sound family (each has its own thing to knead)
  for p in WARM_PAD 808_CLASSIC ACID_LINE SQUARE_LEAD E-PIANO TRAP_BELL ROBOT; do
    adb shell am start -n $PKG/$ACT --es view SYNTH --es synth $p --ez play true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/cat-$p.png"
  done
  # the Thor's top screen on its own (the deck lives on the other panel there), in every theme:
  # the synth hero (cat | waveform), the drum screen and the mixer
  for th in genesis studio bodega cyberpunk vintage pastel terminal grayscale; do
    adb shell am start -n $PKG/$ACT --ez toponly true --es theme $th --es view SYNTH --es synth WARM_PAD --ez play true >/dev/null; sleep 2.5
    adb exec-out screencap -p > "$d/top-synth-$th.png"
  done
  adb shell am start -n $PKG/$ACT --es theme pastel --es view SYNTH --es synth E-PIANO >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-synth-pastel-keys.png"
  adb shell am start -n $PKG/$ACT --es view DRUM >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-drums-pastel.png"
  # SONG: patterns + a song, playing in song mode; then the key sheet with KEY LOCK on
  adb shell am start -n $PKG/$ACT --es view SONG --ez songdemo true >/dev/null; sleep 2
  adb shell am start -n $PKG/$ACT --ez play true >/dev/null; sleep 1; adb exec-out screencap -p > "$d/top-song.png"
  adb shell am start -n $PKG/$ACT --ez play false --ez keylock true --ez keysheet true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-keysheet.png"
  adb shell am start -n $PKG/$ACT --ez keylock false --ez keysheet false >/dev/null; sleep 1
  # EXPORT: the sheet; then a real bounce of the pattern ×2 (FLAC, stems, MIDI): files on disk, the result sheet
  adb shell am start -n $PKG/$ACT --es view SONG --ez exportsheet true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-export.png"
  adb logcat -c || true
  adb shell am start -n $PKG/$ACT --ez exportsheet false --es exportnow pattern-flac-stems-midi >/dev/null; sleep 2.5
  adb exec-out screencap -p > "$d/top-exporting.png"
  for _ in $(seq 1 90); do adb logcat -d -s Thorbox | grep -q "export:" && break; sleep 1; done
  sleep 1; adb exec-out screencap -p > "$d/top-export-done.png"
  if adb logcat -d -s Thorbox | grep -q "export: EXPORTED"; then
    echo "::notice::[export] $(adb logcat -d -s Thorbox | grep 'export: EXPORTED' | tail -1 | sed 's/.*export: //')"
    adb shell 'ls -l "/sdcard/Music/Clamshell Beats/Exports/"*' || true
    ex=$(mktemp -d); adb pull "/sdcard/Music/Clamshell Beats/Exports" "$ex" >/dev/null 2>&1 || true
    if command -v ffprobe >/dev/null; then
      find "$ex" -name '*.flac' -print0 | while IFS= read -r -d '' f; do
        ffmpeg -v error -i "$f" -f null - && echo "decodes: $(basename "$f") $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f") s" || { echo "::error::[export] export doesn't decode: $f"; touch "$ex/bad"; }
      done
      [ -e "$ex/bad" ] && fail=1
    fi
    rm -rf "$ex"
  else
    echo "::error::[export] export never finished"; adb logcat -d -s Thorbox | tail -20; fail=1
  fi
  adb shell am start -n $PKG/$ACT --ez exportsheet false >/dev/null; sleep 1
  # DUCK: off (nothing picked yet), then keyed off the kick and playing, in pastel and genesis, several frames (the rat pumps)
  adb shell am start -n $PKG/$ACT --es view DUCK >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-duck-off.png"
  adb shell am start -n $PKG/$ACT --ez duckdemo true --ez play true >/dev/null; sleep 2
  for i in 1 2 3 4; do adb exec-out screencap -p > "$d/top-duck-pastel-$i.png"; sleep 0.17; done
  adb shell am start -n $PKG/$ACT --es theme genesis --es duckpreset HEAVY_DILLA_PUMP >/dev/null; sleep 2
  for i in 1 2 3 4; do adb exec-out screencap -p > "$d/top-duck-$i.png"; sleep 0.17; done
  adb shell am start -n $PKG/$ACT --es theme pastel >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view MIX >/dev/null; sleep 2; adb exec-out screencap -p > "$d/top-mix-pastel.png"
  adb shell am start -n $PKG/$ACT --ez toponly false --es theme genesis >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --ez play false --es synth INIT >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view DRUM --es deck FX >/dev/null; sleep 2; adb exec-out screencap -p > "$d/fx.png"
  adb shell am start -n $PKG/$ACT --ei fx 0 >/dev/null; sleep 0.3
  adb shell am start -n $PKG/$ACT --ei fx 8 >/dev/null; sleep 1.5; adb exec-out screencap -p > "$d/fx-on.png"
  adb shell am start -n $PKG/$ACT --ei fx -1 >/dev/null; sleep 0.5
  adb shell am start -n $PKG/$ACT --ei latch 8 >/dev/null; sleep 0.3
  adb shell am start -n $PKG/$ACT --ei latch 1 >/dev/null; sleep 0.3
  adb shell am start -n $PKG/$ACT --ei latch 4 --ez fxxy true >/dev/null; sleep 1.5; adb exec-out screencap -p > "$d/fx-xy.png"
  adb shell am start -n $PKG/$ACT --ez fxxy false --ez dry true --es deck PADS >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view DRUM --es deck DRUMS >/dev/null; sleep 2; adb exec-out screencap -p > "$d/drums16.png"
  adb shell am start -n $PKG/$ACT --es levels TUNE >/dev/null; sleep 1.5; adb exec-out screencap -p > "$d/levels.png"
  adb shell am start -n $PKG/$ACT --es levels OFF --es deck PADS >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view SAMPLER --ez mic true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/mic.png"
  # record a real take: grant the permission, record 3 s, stop → the review screen
  adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
  adb logcat -c || true
  adb shell am start -n $PKG/$ACT --ez recstart true >/dev/null; sleep 3; adb exec-out screencap -p > "$d/mic-rec.png"
  adb shell am start -n $PKG/$ACT --ez recstop true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/mic-review.png"
  sleep 4; adb exec-out screencap -p > "$d/mic-review-later.png"
  adb logcat -d -s ClamshellMic:* | tee "$d/mic-log.txt" | tail -20
  adb logcat -d -b crash,main "*:E" | grep -i -E "clamshell|thorbox|AndroidRuntime|FATAL" | tail -30 | tee "$d/mic-errors.txt" || true
  adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | head -3 | tee -a "$d/mic-errors.txt" || true
  if ! grep -q "capture: " "$d/mic-log.txt"; then echo "RECORDER FAILED: no audio was captured"; exit 1; fi
  if ! grep -q "stop: ok=true" "$d/mic-log.txt"; then echo "RECORDER FAILED: take didn't finish cleanly"; exit 1; fi
  adb shell am start -n $PKG/$ACT --ez mic false >/dev/null; sleep 1
  adb shell am start -n $PKG/$ACT --es view SYNTH --ez meters true >/dev/null; sleep 3; adb exec-out screencap -p > "$d/meters.png"
  adb shell am start -n $PKG/$ACT --ez meters false --ei settings 0 >/dev/null; sleep 2; adb exec-out screencap -p > "$d/settings.png"
  adb shell am start -n $PKG/$ACT --ei settings 1 >/dev/null; sleep 2; adb exec-out screencap -p > "$d/settings-look.png"
  adb shell am start -n $PKG/$ACT --ei settings -1 >/dev/null; sleep 1
  # NAV + buttons, driven by real key events: cursor, a grabbed knob, the bottom screen, a pad grid, the cheat sheet
  key() { adb shell input keyevent "KEYCODE_$1"; sleep 0.4; }
  adb shell am start -n $PKG/$ACT --es view SYNTH --es deck PADS --ez nav true >/dev/null; sleep 2; adb exec-out screencap -p > "$d/nav-1.png"
  for k in DPAD_DOWN DPAD_DOWN DPAD_DOWN DPAD_DOWN; do key $k; done; adb exec-out screencap -p > "$d/nav-2-moved.png"
  key BUTTON_A; key DPAD_RIGHT; key DPAD_UP; adb exec-out screencap -p > "$d/nav-3-grab.png"
  key BUTTON_R1; adb exec-out screencap -p > "$d/nav-4-bottom.png"
  for k in DPAD_DOWN DPAD_DOWN DPAD_DOWN; do key $k; done; key BUTTON_A; key DPAD_RIGHT; adb exec-out screencap -p > "$d/nav-5-grid.png"
  key BUTTON_B; key BUTTON_B; key BUTTON_B
  adb shell am start -n $PKG/$ACT --ez nav false --ez controlmap true >/dev/null; sleep 3; adb exec-out screencap -p > "$d/controlmap.png"
  adb shell am start -n $PKG/$ACT --ez controlmap false --es view DRUM --es deck FX >/dev/null; sleep 1.5
  key DPAD_RIGHT; key DPAD_DOWN; adb exec-out screencap -p > "$d/fx-buttons.png"
  adb shell am start -n $PKG/$ACT --es view SAMPLER --es deck PADS >/dev/null; sleep 2; adb exec-out screencap -p > "$d/sampler-after.png"
  adb shell am force-stop $PKG
  adb shell wm size reset; adb shell wm density reset; sleep 2
  echo "::notice::screenshots: $(ls "$d" | wc -l) files"
}

adb install -r "$APK"
shots
run_case single
tour single
# Simulate the Thor's bottom screen as a secondary display.
adb shell settings put global overlay_display_devices 1080x1240/320
sleep 5
run_case dual
tour dual
adb shell settings delete global overlay_display_devices
exit $fail
