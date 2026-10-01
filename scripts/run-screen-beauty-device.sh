#!/usr/bin/env bash
# Real Android configuration also constrains dialog windows and native picker text.
set -euo pipefail
debug_apk=$(realpath "$1")
tests_apk=$(realpath "$2")
output=$(realpath -m "$3")
mkdir -p "$output"
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "Disposable emulator required" >&2; exit 2; }
size=$(adb shell wm size | tr -d '\r' | sed -n 's/^Override size: //p')
density=$(adb shell wm density | tr -d '\r' | sed -n 's/^Override density: //p')
font=$(adb shell settings get system font_scale | tr -d '\r')
[[ -z "$size" || "$size" =~ ^[0-9]+x[0-9]+$ ]] || exit 2
[[ -z "$density" || "$density" =~ ^[0-9]+$ ]] || exit 2
[[ "$font" == null || "$font" =~ ^[0-9]+([.][0-9]+)?$ ]] || exit 2
restore_display() {
  timeout 20 adb shell wm size "${size:-reset}" || true
  timeout 20 adb shell wm density "${density:-reset}" || true
  if [[ "$font" == null ]]; then
    timeout 20 adb shell settings delete system font_scale || true
  else
    timeout 20 adb shell settings put system font_scale "$font" || true
  fi
}
trap restore_display EXIT INT TERM
adb install -r "$debug_apk"
adb install -r -t "$tests_apk"
adb shell wm density 420
adb shell wm size 840x1680
adb shell settings put system font_scale 2.0
adb shell am force-stop zone.disinfo.wx
set +e
timeout 300 adb shell am instrument -w -r -e wxBeautyDevice true \
  -e class 'zone.disinfo.wx.ScreenBeautyE2eTest#actualDeviceLargeTextLightDialogs,zone.disinfo.wx.ScreenBeautyE2eTest#actualDeviceLargeTextDarkDialogs' \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner > "$output/results.txt" 2>&1
result=$?
set -e
adb exec-out run-as zone.disinfo.wx tar -C files/e2e -cf - . > "$output/screenshots.tar" || true
if [[ -s "$output/screenshots.tar" ]]; then tar -xf "$output/screenshots.tar" -C "$output"; fi
adb logcat -d -t 400 > "$output/logcat.txt" || true
cat "$output/results.txt"
[[ $result -eq 0 ]] && grep -q 'OK (2 tests)' "$output/results.txt"
