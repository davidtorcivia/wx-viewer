#!/usr/bin/env bash
# Baseline rendering is intentionally unmodified. Reuse one test APK, emulator and action
# sequence. Baseline regressions remain visible while only candidate regressions gate success.
set -uo pipefail
baseline=$(realpath "${1:?baseline checkout required}")
candidate=$(realpath "${2:?candidate checkout required}")
output=$(realpath -m "${3:?output required}")
mkdir -p "$output"
[[ $(git -C "$baseline" rev-parse HEAD) == 0b69bee21196178bb92f91fc8b00495fb263713e ]] || exit 2
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || exit 2
cd "$candidate"
status=0
# One controlled native negative-control run is enough; candidate's full main suite
# already runs this test. No baseline production source or APK is patched.
if [[ "${WX_LAYER_SHARD_INDEX:-0}" == 0 ]]; then
 negative="$output/baseline-negative-control"
 remote=/sdcard/Android/media/zone.disinfo.wx/baseline-negative-control
 mkdir -p "$negative"
 adb uninstall zone.disinfo.wx >/dev/null 2>&1 || true
 if adb install -r "$baseline/app/build/outputs/apk/debug/app-debug.apk" &&
    adb install -r -t "$candidate/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"; then
  adb shell am force-stop zone.disinfo.wx
  adb shell pm clear zone.disinfo.wx
  adb shell rm -rf "$remote"
  adb logcat -c
  timeout 180 adb shell am instrument -w -r \
   -e class zone.disinfo.wx.ui.RadarRasterContinuityTest#cloudsRetainWeatherThroughDelayedFailedAndStaleTiles \
   -e additionalTestOutputDir "$remote" \
   zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$negative/instrumentation.log"
  printf '%s\n' "${PIPESTATUS[0]}" > "$negative/command-status.txt"
  adb pull "$remote/." "$negative/" || true
 else
  printf 'APK installation/access error; negative proof unavailable\n' > "$negative/instrumentation.log"
 fi
 adb logcat -d > "$negative/logcat.txt" || true
 sha256sum "$baseline/app/build/outputs/apk/debug/app-debug.apk" \
  "$candidate/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" > "$negative/apk-sha256.txt"
 python3 scripts/ci/check-radar-negative-control.py "$negative" || status=1
 # Continue both video legs even when negative proof is unavailable, retaining all diagnostics.
fi
for leg in baseline candidate; do
 checkout=$baseline
 [[ "$leg" == baseline ]] || checkout=$candidate
 # A clean public NYC fixture per leg prevents old frames/cache/preferences leaking A→B.
 adb uninstall zone.disinfo.wx >/dev/null 2>&1 || true
 WX_DEBUG_APK="$checkout/app/build/outputs/apk/debug/app-debug.apk" \
 WX_PREVIEW_APK="$checkout/app/build/outputs/apk/preview/app-preview.apk" \
 WX_RADAR_OUTPUT="$output/$leg" bash scripts/run-radar-layers-preview.sh continuity
 result=$?
 printf '%s\n' "$result" > "$output/$leg-status.txt"
 if [[ "$leg" == candidate && $result -ne 0 ]]; then status=1; fi
 adb logcat -d > "$output/$leg/logcat.txt" || true
 sha256sum "$checkout/app/build/outputs/apk/preview/app-preview.apk" \
  "$candidate/macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk" > "$output/$leg/apk-sha256.txt"
done
python3 - "$output" "${WX_LAYER_SHARD_COUNT:-1}" "${WX_LAYER_SHARD_INDEX:-0}" <<'PY' || status=1
import json, pathlib, sys
root = pathlib.Path(sys.argv[1]); count, shard = map(int, sys.argv[2:])
expected = 2 * sum(i % count == shard for i in range(10))
result = {'baselineCommit': '0b69bee21196178bb92f91fc8b00495fb263713e',
          'baselineFailuresAreExpectedRegressionEvidence': True,
          'commonHarness': True, 'sameEmulator': True, 'legs': {}}
if shard == 0:
    result['controlledBaselineNegativeProof'] = json.loads((root / 'baseline-negative-control/negative-control-proof.json').read_text())
for leg in ('baseline', 'candidate'):
    report = json.loads((root / leg / 'continuity/continuity-analysis.json').read_text())
    result['legs'][leg] = report
(root / 'comparison.json').write_text(json.dumps(result, indent=2))
# Known baseline rendering failures do not excuse absent/truncated/low-cadence evidence.
baseline = result['legs']['baseline']
assert len(baseline['captures']) == expected and not baseline['failures'], 'Incomplete/duplicate baseline video set'
visual_regressions = {'black_native_view', 'flat_native_view', 'partial_black_native_view',
                      'map_detail_loss', 'opaque_field_coverage_loss', 'frozen_native_playback'}
for capture in baseline['captures']:
    assert all(f.get('status') != 'inconclusive' and f.get('code') in visual_regressions
               for f in capture.get('failures', [])), 'Baseline acquisition/action evidence incomplete or inconclusive'
assert result['legs']['candidate']['passed'] is True, 'Candidate native continuity failed'
PY
exit "$status"
