#!/usr/bin/env bash
# Runs against the preview already installed and seeded by the normal or A/B harness.
# The exact same external test APK and this script are used for both A/B legs.
set -uo pipefail
cd "$(dirname "$0")/.."
output=${1:-app/build/outputs/radar-layers-preview/continuity}
shard_count=${WX_LAYER_SHARD_COUNT:-1}
shard_index=${WX_LAYER_SHARD_INDEX:-0}
mkdir -p "$output"
# Reset every raw result before setup. A stale report from an interrupted local rerun
# can never pair with successful status files from the previous invocation.
rm -f "$output/continuity-analysis.json" "$output/continuity-gate.json" || exit 2
printf '2\n' > "$output/continuity-acquisition-status.txt"
printf '2\n' > "$output/continuity-analysis-status.txt"
printf '2\n' > "$output/continuity-status.txt"
fail_setup() {
 local reason=$1
 printf 'Native continuity setup failed: %s\n' "$reason" >&2
 python3 - "$output" "$shard_count" "$shard_index" "$reason" <<'PYSETUP'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1]); count, shard = int(sys.argv[2]), int(sys.argv[3])
report = {'schemaVersion': 1, 'passed': False, 'status': 'inconclusive',
          'shardCount': count, 'shardIndex': shard,
          'expectedCaptures': 2 * sum(i % count == shard for i in range(10)),
          'analyzedCaptures': 0, 'decodedFrames': 0, 'captures': [],
          'failures': [{'code': 'setup_failure', 'status': 'inconclusive', 'message': sys.argv[4]}]}
(root / 'continuity-analysis.json').write_text(json.dumps(report, indent=2) + '\n')
(root / 'continuity-status.txt').write_text('2\n')
PYSETUP
 exit 2
}
[[ "$shard_count" =~ ^[1-5]$ && "$shard_index" =~ ^[0-4]$ && "$shard_index" -lt "$shard_count" ]] || exit 2
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || fail_setup 'Disposable emulator required'
for tool in ffmpeg ffprobe; do command -v "$tool" >/dev/null || fail_setup "Missing $tool; install the official Ubuntu ffmpeg package before capture"; done
analysis_python=python3
if ! python3 -c 'import numpy, PIL' >/dev/null 2>&1; then
 # Dependencies belong to an isolated host-only environment, never the app under test.
 analysis_venv="${RUNNER_TEMP:-/tmp}/wx-radar-video-analysis"
 python3 -m venv "$analysis_venv" || fail_setup "Could not create isolated Python analysis environment"
 "$analysis_venv/bin/python" -m pip install --disable-pip-version-check numpy==2.2.6 Pillow==11.3.0 || fail_setup "Could not install pinned native-video analysis dependencies"
 analysis_python="$analysis_venv/bin/python"
fi
# The ordinary build discovers this suite without requiring video dependencies. Here
# they are available, so missing or skipped detector tests must fail before capture.
"$analysis_python" - <<'PYTEST' || fail_setup "Native-video analyzer regression suite failed or skipped tests"
import sys, unittest
suite = unittest.defaultTestLoader.discover('scripts/ci', pattern='test_radar_continuity.py')
assert suite.countTestCases() > 0, 'Missing native video analyzer regression tests'
result = unittest.TextTestRunner(verbosity=2).run(suite)
sys.exit(0 if result.wasSuccessful() and not result.skipped else 1)
PYTEST
original_night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
[[ "$original_night" == yes || "$original_night" == no || "$original_night" == auto ]] || fail_setup "Could not establish current emulator night mode"
original_font=$(adb shell settings get system font_scale | tr -d '\r')
[[ "$original_font" == null || "$original_font" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail_setup "Could not establish current emulator font scale"
# One measured acquisition profile only: halve physical dimensions and density together,
# retaining the identical dp viewport. Functional/UI phases run at their original size.
original_size_state=$(adb shell wm size | tr -d '\r')
original_density_state=$(adb shell wm density | tr -d '\r')
original_size_override=$(sed -n 's/^Override size: //p' <<< "$original_size_state")
original_density_override=$(sed -n 's/^Override density: //p' <<< "$original_density_state")
active_size=${original_size_override:-$(sed -n 's/^Physical size: //p' <<< "$original_size_state")}
active_density=${original_density_override:-$(sed -n 's/^Physical density: //p' <<< "$original_density_state")}
[[ "$active_size" =~ ^[0-9]+x[0-9]+$ && "$active_density" =~ ^[0-9]+$ ]] || fail_setup "Could not establish current physical display profile"
profile=$(python3 - "$active_size" "$active_density" <<'PYPROFILE'
import sys
width, height = map(int, sys.argv[1].split('x')); density = int(sys.argv[2])
assert width % 4 == height % 4 == density % 2 == 0, 'Half-resolution profile must preserve exact dp dimensions and even codec dimensions'
assert width >= 1080 and height >= 1920 and density >= 320, 'Unexpected original display; do not silently change the dp viewport'
print(width // 2, height // 2, density // 2)
PYPROFILE
) || fail_setup "Original emulator profile does not support the single half-resolution acquisition attempt"
read -r capture_width capture_height capture_density <<< "$profile"
restore_recording_profile() {
 adb shell am force-stop zone.disinfo.wx || true
 adb shell wm size "${original_size_override:-reset}" || true
 adb shell wm density "${original_density_override:-reset}" || true
 adb shell cmd uimode night "$original_night" || true
 if [[ "$original_font" == null ]]; then adb shell settings delete system font_scale || true
 else adb shell settings put system font_scale "$original_font" || true; fi
}
trap restore_recording_profile EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
status=0
adb shell am force-stop zone.disinfo.wx || fail_setup "Could not stop preview before applying acquisition profile"
adb shell wm size "${capture_width}x${capture_height}" || fail_setup "Could not apply acquisition display size"
adb shell wm density "$capture_density" || fail_setup "Could not apply acquisition display density"
adb shell cmd uimode night yes || fail_setup "Could not enable controlled dark-theme capture"
for font in 1.0 2.0; do
 local_output="$output/font-$font"
 remote="/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/continuity-shard-$shard_index-font-$font"
 mkdir -p "$local_output"
 adb shell settings put system font_scale "$font" || status=1
 adb shell am force-stop zone.disinfo.wx || status=1
 # Isolate evidence across reruns; these are disposable test-only output directories.
 adb shell rm -rf "$remote" || status=1
 # Four warmed cases measured about400s; preserve margin for a slower combined layer.
 timeout 600 adb shell am instrument -w -r \
  -e class zone.disinfo.wx.macrobenchmark.RadarLayersPreviewTest#allLayersContinuousNativeFrames \
  -e wxRadarContinuityPreview true -e wxFontScale "$font" \
  -e wxCaptureProfile half-linear-resolution-same-dp \
  -e wxCaptureWidth "$capture_width" -e wxCaptureHeight "$capture_height" -e wxCaptureDensity "$capture_density" \
  -e wxCaptureOriginalSize "$active_size" -e wxCaptureOriginalDensity "$active_density" \
  -e wxLayerShardCount "$shard_count" -e wxLayerShardIndex "$shard_index" \
  -e additionalTestOutputDir "$remote" \
  zone.disinfo.wx.macrobenchmark/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$local_output/instrumentation.log"
 [[ ${PIPESTATUS[0]} -eq 0 ]] || status=1
 adb pull "$remote/." "$local_output/" || status=1
 # A printed OK summary can include assumptions. Require the exact method's terminal pass.
 python3 - "$local_output/instrumentation.log" <<'PY' || status=1
import importlib.util, pathlib, sys
spec = importlib.util.spec_from_file_location('instrumentation', 'scripts/ci/check-instrumentation.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
results = m.parse(pathlib.Path(sys.argv[1]).read_text())
expected = 'zone.disinfo.wx.macrobenchmark.RadarLayersPreviewTest#allLayersContinuousNativeFrames'
assert set(results) == {expected} and results[expected]['code'] == 0, results
PY
done
printf '%s\n' "$status" > "$output/continuity-acquisition-status.txt"
"$analysis_python" scripts/analyze-radar-continuity.py "$output" --output "$output/continuity-analysis.json" \
 --shard-count "$shard_count" --shard-index "$shard_index"
analysis_status=$?
printf '%s\n' "$analysis_status" > "$output/continuity-analysis-status.txt"
[[ "$analysis_status" -eq 0 ]] || status=1
[[ -s "$output/continuity-analysis.json" ]] || fail_setup "Analyzer did not produce a report; inspect analysis and capture logs"
printf '%s\n' "$status" > "$output/continuity-status.txt"
exit "$status"
