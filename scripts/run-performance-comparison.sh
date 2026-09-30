#!/usr/bin/env bash
# Run only on the disposable, accelerated CI emulator or an explicitly selected test device.
set -euo pipefail
baseline_apk=$(realpath "$1")
candidate_apk=$(realpath "$2")
test_apk=$(realpath "$3")
output=$(realpath -m "$4")
mkdir -p "$output"
for apk in "$baseline_apk" "$candidate_apk" "$test_apk"; do test -f "$apk"; done
sha256sum "$baseline_apk" "$candidate_apk" "$test_apk" > "$output/apk-sha256.txt"
adb shell getprop ro.build.fingerprint > "$output/device-fingerprint.txt"
adb shell getprop ro.build.version.sdk > "$output/device-api.txt"
adb shell wm size 720x1600
adb shell wm density 280
adb shell wm size > "$output/device-size.txt"
adb shell wm density > "$output/device-density.txt"
adb install -r -t "$test_apk"

run_leg() {
  local leg=$1 apk=$2
  local remote="/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/performance-$leg"
  mkdir -p "$output/$leg"
  adb install -r "$apk"
  # Clear only disposable fixture data. Both APKs use the same common benchmark versionCode.
  adb shell pm clear zone.disinfo.wx
  adb shell dumpsys package zone.disinfo.wx > "$output/$leg/package.txt"
  set +e
  timeout 1200 adb shell am instrument -w -r \
    -e class zone.disinfo.wx.macrobenchmark.WeatherPerformanceBenchmark \
    -e androidx.benchmark.suppressErrors EMULATOR \
    -e additionalTestOutputDir "$remote" \
    zone.disinfo.wx.macrobenchmark/androidx.test.runner.AndroidJUnitRunner \
    > "$output/$leg/instrumentation.log" 2>&1
  local status=$?
  set -e
  adb pull "$remote/." "$output/$leg/" || true
  adb logcat -d -t 1200 > "$output/$leg/logcat.txt" || true
  if [[ $status -ne 0 ]] || ! grep -q 'OK (4 tests)' "$output/$leg/instrumentation.log"; then
    cat "$output/$leg/instrumentation.log"
    echo "Benchmark leg failed: $leg" >&2
    return 1
  fi
}

run_leg baseline "$baseline_apk"
run_leg optimized "$candidate_apk"
run_leg baseline-repeat "$baseline_apk"
python3 "$(dirname "$0")/summarize-performance.py" "$output"
