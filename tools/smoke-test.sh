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
  # HAZE + its pixel film, then release (the smoke tail keeps drawing for a few seconds)
  adb shell am start -n $PKG/$ACT --es deck FX --ei fx 7 >/dev/null; sleep 3
  adb exec-out screencap -p > "$OUT/$name-haze.png" || true
  adb shell am start -n $PKG/$ACT --ei fx -1 >/dev/null; sleep 3
  for th in cyberpunk pastel terminal grayscale studio genesis; do        # every theme on the busiest screens
    for v in DRUM SYNTH; do adb shell am start -n $PKG/$ACT --es theme $th --es view $v --es deck FX >/dev/null; sleep 2; done
    adb exec-out screencap -p > "$OUT/$name-theme-$th.png" || true
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

adb install -r "$APK"
run_case single
tour single
# Simulate the Thor's bottom screen as a secondary display.
adb shell settings put global overlay_display_devices 1080x1240/320
sleep 5
run_case dual
tour dual
adb shell settings delete global overlay_display_devices
exit $fail
