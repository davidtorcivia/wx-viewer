#!/usr/bin/env bash
# Runs against the preview already installed and seeded by the normal or A/B harness.
# The exact same external test APK and this script are used for both A/B legs.
set -uo pipefail
cd "$(dirname "$0")/.."
output=${1:-app/build/outputs/radar-layers-preview/continuity}
shard_count=${WX_LAYER_SHARD_COUNT:-1}
shard_index=${WX_LAYER_SHARD_INDEX:-0}
[[ "$shard_count" =~ ^[1-3]$ && "$shard_index" =~ ^[0-2]$ && "$shard_index" -lt "$shard_count" ]] || exit 2
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Disposable emulator required'; exit 2; }
for tool in ffmpeg ffprobe; do command -v "$tool" >/dev/null || { echo "Missing $tool"; exit 2; }; done
analysis_python=python3
if ! python3 -c 'import numpy, PIL' >/dev/null 2>&1; then
 # Dependencies belong to an isolated host-only environment, never the app under test.
 analysis_venv="${RUNNER_TEMP:-/tmp}/wx-radar-video-analysis"
 python3 -m venv "$analysis_venv" || exit 2
 "$analysis_venv/bin/python" -m pip install --disable-pip-version-check numpy==2.2.6 Pillow==11.3.0 || exit 2
 analysis_python="$analysis_venv/bin/python"
fi
# The ordinary build discovers this suite without requiring video dependencies. Here
# they are available, so missing or skipped detector tests must fail before capture.
"$analysis_python" - <<'PYTEST' || exit 2
import sys, unittest
suite = unittest.defaultTestLoader.discover('scripts/ci', pattern='test_radar_continuity.py')
assert suite.countTestCases() > 0, 'Missing native video analyzer regression tests'
result = unittest.TextTestRunner(verbosity=2).run(suite)
sys.exit(0 if result.wasSuccessful() and not result.skipped else 1)
PYTEST
original_night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
[[ "$original_night" == yes || "$original_night" == no || "$original_night" == auto ]] || exit 2
original_font=$(adb shell settings get system font_scale | tr -d '\r')
[[ "$original_font" == null || "$original_font" =~ ^[0-9]+([.][0-9]+)?$ ]] || exit 2
restore_font() {
 adb shell cmd uimode night "$original_night" || true
 if [[ "$original_font" == null ]]; then adb shell settings delete system font_scale || true
 else adb shell settings put system font_scale "$original_font" || true; fi
}
trap restore_font EXIT INT TERM
mkdir -p "$output"
status=0
adb shell cmd uimode night yes || exit 2
for font in 1.0 2.0; do
 local_output="$output/font-$font"
 remote="/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/continuity-shard-$shard_index-font-$font"
 mkdir -p "$local_output"
 adb shell settings put system font_scale "$font" || status=1
 adb shell am force-stop zone.disinfo.wx || status=1
 # Isolate evidence across reruns; these are disposable test-only output directories.
 adb shell rm -rf "$remote" || status=1
 timeout 480 adb shell am instrument -w -r \
  -e class zone.disinfo.wx.macrobenchmark.RadarLayersPreviewTest#allLayersContinuousNativeFrames \
  -e wxRadarContinuityPreview true -e wxFontScale "$font" \
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
"$analysis_python" scripts/analyze-radar-continuity.py "$output" --output "$output/continuity-analysis.json" \
 --shard-count "$shard_count" --shard-index "$shard_index" || status=1
printf '%s\n' "$status" > "$output/continuity-status.txt"
exit "$status"
