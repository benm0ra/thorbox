#!/usr/bin/env bash
# Experiment: sit on one view, playing, with art debug flags; log host + guest memory until the emulator dies.
set -u
F=${1:-0}; V=${2:-SYNTH}
PKG=com.clamshellbeats.app; ACT=com.thorbox.app.MainActivity
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
mkdir -p soak
adb install -r "$APK" >/dev/null
adb shell am start -W -n $PKG/$ACT >/dev/null; sleep 8
adb shell am start -n $PKG/$ACT --ei artdebug "$F" >/dev/null; sleep 1
adb shell am start -n $PKG/$ACT --ez nav false >/dev/null; sleep 1
adb shell am start -n $PKG/$ACT --es view "$V" --ez play true >/dev/null; sleep 2
for k in BUTTON_A DPAD_RIGHT DPAD_DOWN BUTTON_A BUTTON_X BUTTON_THUMBR BUTTON_Y BUTTON_B; do adb shell input keyevent KEYCODE_$k; done
t0=$(date +%s)
for i in $(seq 1 36); do
  sleep 10; t=$(( $(date +%s) - t0 ))
  if ! timeout 30 adb shell true 2>/dev/null; then echo "::error::flags=$F view=$V DIED after ${t}s"; free -m; dmesg 2>/dev/null | tail -5; exit 1; fi
  q=$(ps -C qemu-system-x86_64 -o rss= 2>/dev/null | head -1)
  hostfree=$(free -m | awk '/Mem:/{print $7}')
  app=$(adb shell dumpsys meminfo $PKG 2>/dev/null | grep -m1 "TOTAL PSS" | tr -s ' ' | cut -c1-60)
  gfx=$(adb shell dumpsys gfxinfo $PKG 2>/dev/null | grep -E "Total frames rendered|90th percentile" | tr -s ' ' | tr '\n' ' ')
  echo "t=${t}s qemuRSS=${q}KB hostAvail=${hostfree}MB | $app | $gfx"
  [ $((i % 6)) -eq 0 ] && adb exec-out screencap -p > "soak/f$F-$V-$t.png"
done
echo "::notice::flags=$F view=$V survived ${t}s"
