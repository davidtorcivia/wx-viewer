#!/usr/bin/env bash
# Seed disposable fixture storage through debug instrumentation, then test the actual preview.
set -euo pipefail
debug_apk=$(realpath "$1")
debug_tests=$(realpath "$2")
preview_apk=$(realpath "$3")
external_tests=$(realpath "$4")
output=$(realpath -m "$5")
mkdir -p "$output"
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "Disposable emulator required" >&2; exit 2; }
airplane=$(adb shell settings get global airplane_mode_on | tr -d '\r')
wifi=$(adb shell settings get global wifi_on | tr -d '\r')
data=$(adb shell settings get global mobile_data | tr -d '\r')
[[ "$airplane" =~ ^[01]$ && "$wifi" =~ ^[0123]$ && "$data" =~ ^[01]$ ]] || { echo "Unknown original emulator radio state" >&2; exit 2; }
restore_radios() {
  timeout 20 adb shell cmd connectivity airplane-mode "$([[ "$airplane" == 1 ]] && echo enable || echo disable)" || true
  timeout 20 adb shell svc wifi "$([[ "$wifi" == 1 || "$wifi" == 2 ]] && echo enable || echo disable)" || true
  timeout 20 adb shell svc data "$([[ "$data" == 1 ]] && echo enable || echo disable)" || true
}
trap restore_radios EXIT INT TERM
adb install -r "$debug_apk"
adb install -r -t "$debug_tests"
# Exercise pull-refresh's real offline branches while the production debug Activity is installed.
# Restore the original radio state before seeding the independent minified cold-start proof.
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
set +e
timeout 150 adb shell am instrument -w -r \
  -e class 'zone.disinfo.wx.ManualRefreshLifecycleE2eTest#cachedManualFailureCompletesAllRequestsAndKeepsCurrentFixAndContent,zone.disinfo.wx.ManualRefreshLifecycleE2eTest#emptyCacheFailureStopsManualRefreshAndAllowsAnotherAttempt' \
  -e wxExpectOffline true \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner > "$output/pull-refresh-offline.log" 2>&1
refresh_status=$?
set -e
restore_radios
if [[ $refresh_status -ne 0 ]] || ! grep -q 'OK (2 tests)' "$output/pull-refresh-offline.log"; then
  cat "$output/pull-refresh-offline.log"
  exit 1
fi
seed_remote=/sdcard/Android/media/zone.disinfo.wx/offline-preview-seed
timeout 300 adb shell am instrument -w -r \
  -e class 'zone.disinfo.wx.OfflinePreviewSeedTest#seedForMinifiedPreview' \
  -e wxSeedOfflinePreview true -e additionalTestOutputDir "$seed_remote" \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner > "$output/seed.log" 2>&1
grep -q 'OK (1 test)' "$output/seed.log" || { cat "$output/seed.log"; exit 1; }
seed_pid=$(sed -n 's/^INSTRUMENTATION_STATUS: wxSeedPid=//p' "$output/seed.log" | tr -d '\r' | tail -n 1)
[[ "$seed_pid" =~ ^[0-9]+$ ]] || { echo "Seed process ID missing" >&2; exit 1; }
adb pull "$seed_remote/." "$output/seed/" || true
# Inspect the actual persisted document after the seed process has exited. This prevents a
# successful in-memory seed from being mistaken for an offline startup regression. The app is
# still the debuggable fixture build here; no root access or preview data-access bypass is used.
adb shell am force-stop zone.disinfo.wx
adb exec-out run-as zone.disinfo.wx cat shared_prefs/wx_settings_v1.xml > "$output/seed/settings-after-process.xml"
adb exec-out run-as zone.disinfo.wx ls -l files/display-cache-v1 > "$output/seed/cache-files-after-process.txt"
python3 - "$output/seed/settings-after-process.xml" <<'PY'
import json, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
raw = root.find("string[@name='settings']")
assert raw is not None and raw.text, "No persisted settings after seed process exit"
settings = json.loads(raw.text)
assert settings['serverUrl'] == 'https://wx-offline-preview-fixture.invalid', settings
assert [(p['id'], p['name']) for p in settings['places']] == [('offline-nyc', 'NYC'), ('offline-boston', 'Boston')], settings
print('Verified persisted fixture origin and both places after seed process exit')
PY
adb install -r "$preview_apk"
bash "$(dirname "$0")/verify-preview-apk.sh" "$preview_apk" > "$output/preview-package-verification.txt"
adb install -r -t "$external_tests"
remote=/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/offline-preview
set +e
timeout 300 adb shell am instrument -w -r \
  -e class zone.disinfo.wx.macrobenchmark.OfflinePreviewSmokeTest \
  -e wxOfflinePreview true -e wxSeedPid "$seed_pid" \
  -e wxFirstPlace NYC -e wxSecondPlace Boston \
  -e wxFirstTemperature '68°' -e wxSecondTemperature '55°' \
  -e additionalTestOutputDir "$remote" \
  zone.disinfo.wx.macrobenchmark/androidx.test.runner.AndroidJUnitRunner \
  > "$output/offline-preview.log" 2>&1
status=$?
set -e
adb pull "$remote/." "$output/" || true
adb logcat -d -t 1500 > "$output/logcat.txt" || true
if [[ $status -ne 0 ]] || ! grep -q 'OK (1 test)' "$output/offline-preview.log"; then
  cat "$output/offline-preview.log"
  exit 1
fi
