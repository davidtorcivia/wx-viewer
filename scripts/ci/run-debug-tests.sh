#!/usr/bin/env bash
# Consume the exact prebuilt APKs. No Gradle startup/recompile on emulator runners.
set -euo pipefail
cd "$(dirname "$0")/../.."
mode=${1:-full}
[[ "$mode" == full || "$mode" == smoke ]] || exit 2
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
output="app/build/outputs/e2e/$mode"
remote="/sdcard/Android/media/zone.disinfo.wx/ci-$mode"
mkdir -p "$output"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am force-stop zone.disinfo.wx
adb shell pm clear zone.disinfo.wx
critical=zone.disinfo.wx.ui.RadarSliderDispatchTest,zone.disinfo.wx.ui.RadarIntentOwnershipTest,zone.disinfo.wx.ui.RadarConnectivityStatusTest,zone.disinfo.wx.data.NetworkConnectivityCallbackTest
args=()
discovery_args=()
check_args=()
if [[ "$mode" == smoke ]]; then
  args=(-e class "$critical")
  discovery_args=("${args[@]}")
  check_args=(--classes "$critical")
else
  # Critical classes already ran first. Execute the remainder once, then verify the
  # union against an unfiltered discovery from this exact APK.
  args=(-e notClass "$critical")
  check_args=(--allow-separate-phases)
fi
# Discover from this APK, not a hand-maintained count. The full run must cover every
# discovered test and cannot hide an unexpected assumption/skip behind a green job.
timeout 120 adb shell am instrument -w -r -e log true "${discovery_args[@]}" \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner > "$output/inventory.log" 2>&1
set +e
timeout 900 adb shell am instrument -w -r "${args[@]}" \
  -e additionalTestOutputDir "$remote" \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$output/instrumentation.log"
result=${PIPESTATUS[0]}
set -e
mkdir -p "app/build/outputs/connected_android_test_additional_output/$mode"
adb pull "$remote/." "app/build/outputs/connected_android_test_additional_output/$mode/" || true
adb logcat -d -t 1500 > "$output/logcat.txt" || true
# ADB can exit zero even when instrumentation crashes. Require this individual
# invocation's completion, before combining critical/remainder logs for coverage.
if ! grep -qE '^OK \([0-9]+ tests?\)' "$output/instrumentation.log"; then result=1; fi
validation_log="$output/instrumentation.log"
if [[ "$mode" == full ]]; then
  cat app/build/outputs/e2e/smoke/instrumentation.log "$output/instrumentation.log" > "$output/complete-suite.log" || result=1
  validation_log="$output/complete-suite.log"
fi
python3 scripts/ci/check-instrumentation.py "$validation_log" "$output/inventory.log" \
  "$output/tests.json" "${check_args[@]}" || result=1
if [[ "$mode" == full ]] && ! find "app/build/outputs/connected_android_test_additional_output/full" -name '*.png' -type f | grep -q .; then
  echo 'No device screenshots collected' >&2
  result=1
fi
exit "$result"
