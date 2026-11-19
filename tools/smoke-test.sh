#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Launch smoke test: install the debug APK on a running emulator/device, launch it, and fail if it
# doesn't stay alive. Runs twice: single screen, then with a simulated 1080x1240 second display
# (the Thor's bottom screen). Logs and screenshots land in ./smoke/.
set -u
PKG=com.thorbox.app
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
OUT=smoke; mkdir -p "$OUT"
fail=0

run_case() {
  local name=$1
  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity | tee "$OUT/$name-start.txt"
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

adb install -r "$APK"
run_case single
# Simulate the Thor's bottom screen as a secondary display.
adb shell settings put global overlay_display_devices 1080x1240/320
sleep 5
run_case dual
adb shell settings delete global overlay_display_devices
exit $fail
