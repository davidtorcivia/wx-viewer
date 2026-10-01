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

# Keep every leg's evidence even when an earlier test case fails.
: > "$output/leg-status.jsonl"
record_leg() {
  python3 - "$output/leg-status.jsonl" "$1" "$2" "$3" "$4" <<'PY'
import json,sys
with open(sys.argv[1], "a") as f:
    f.write(json.dumps({"leg":sys.argv[2], "status":sys.argv[3],
                        "phase":sys.argv[4], "command_exit_code":int(sys.argv[5])}) + "\n")
PY
}

run_leg() {
  local leg=$1 apk=$2
  local remote="/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/performance-$leg"
  local status=0
  mkdir -p "$output/$leg" || return 1
  # These explicit guards matter: run_leg is called in an if condition, where Bash
  # disables implicit errexit inside the function. Never measure the previous APK.
  adb install -r "$apk" > "$output/$leg/setup.log" 2>&1 || {
    status=$?; record_leg "$leg" failed install "$status"; return 1;
  }
  # Clear only disposable fixture data. Both APKs use the same common benchmark versionCode.
  adb shell pm clear zone.disinfo.wx >> "$output/$leg/setup.log" 2>&1 || {
    status=$?; record_leg "$leg" failed clear-fixture "$status"; return 1;
  }
  adb shell dumpsys package zone.disinfo.wx > "$output/$leg/package.txt" || {
    status=$?; record_leg "$leg" failed package-metadata "$status"; return 1;
  }
  timeout 1200 adb shell am instrument -w -r \
    -e class zone.disinfo.wx.macrobenchmark.WeatherPerformanceBenchmark \
    -e androidx.benchmark.suppressErrors EMULATOR \
    -e additionalTestOutputDir "$remote" \
    zone.disinfo.wx.macrobenchmark/androidx.test.runner.AndroidJUnitRunner \
    > "$output/$leg/instrumentation.log" 2>&1 || status=$?
  adb pull "$remote/." "$output/$leg/" || true
  adb logcat -d -t 1200 > "$output/$leg/logcat.txt" || true
  if [[ $status -ne 0 ]] || ! grep -q 'OK (4 tests)' "$output/$leg/instrumentation.log"; then
    record_leg "$leg" failed instrumentation "$status"
    cat "$output/$leg/instrumentation.log"
    echo "Benchmark leg failed: $leg; preserving evidence and continuing" >&2
    return 1
  fi
  record_leg "$leg" passed instrumentation "$status"
}

failed=0
if run_leg baseline "$baseline_apk"; then :; else failed=1; fi
if run_leg optimized "$candidate_apk"; then :; else failed=1; fi
if run_leg baseline-repeat "$baseline_apk"; then :; else failed=1; fi
python3 - "$output" "$failed" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
legs=[json.loads(line) for line in (root/"leg-status.jsonl").read_text().splitlines()]
complete=sys.argv[2]=="0" and len(legs)==3 and all(x["status"]=="passed" for x in legs)
(root/"leg-status.json").write_text(json.dumps({x["leg"]:0 if x["status"]=="passed" else 1 for x in legs},indent=2)+"\n")
(root/"leg-status-details.json").write_text(json.dumps({"complete":complete,"legs":legs},indent=2)+"\n")
if not complete:
    (root/"partial-evidence.md").write_text(
        "# Incomplete Android performance run\n\n"
        "At least one leg or scenario failed. This is not a complete A/B/A comparison. "
        "Raw successful scenario measurements, loaded-hero observations and traces remain "
        "under each attempted leg. Inspect leg-status.json and instrumentation logs before "
        "using a scenario; no overall performance conclusion is generated.\n")
PY
if [[ $failed -ne 0 ]]; then
  echo "A/B/A is incomplete; all attempted leg evidence is retained" >&2
  exit 1
fi
python3 "$(dirname "$0")/summarize-performance.py" "$output"
