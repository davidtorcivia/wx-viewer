#!/usr/bin/env bash
# Never points at a user device. Seed public NYC configuration in disposable debug storage,
# then replace it with the exact R8-minified preview (same CI test signing key).
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Disposable emulator required'; exit 2; }
output=app/build/outputs/radar-layers-preview
mkdir -p "$output"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop zone.disinfo.wx
adb shell pm clear zone.disinfo.wx
python3 - "$output/settings.xml" <<'PY'
import json, sys, xml.etree.ElementTree as ET
root = ET.Element('map')
ET.SubElement(root, 'string', name='settings').text = json.dumps({
    'version': 1, 'serverUrl': 'https://sref.disinfo.zone',
    'places': [{'id':'layer-test-nyc','name':'NYC','lat':40.7128,'lon':-74.006}],
    'locationEnabled':False, 'backgroundLocationEnabled':False,
    'alerts': {'enabled':False}})
ET.ElementTree(root).write(sys.argv[1], encoding='unicode', xml_declaration=True)
PY
adb shell run-as zone.disinfo.wx mkdir -p shared_prefs
adb shell 'run-as zone.disinfo.wx sh -c "cat > shared_prefs/wx_settings_v1.xml"' < "$output/settings.xml"
adb install -r app/build/outputs/apk/preview/app-preview.apk
bash scripts/verify-preview-apk.sh app/build/outputs/apk/preview/app-preview.apk > "$output/apk-verification.txt"
adb install -r -t macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
adb logcat -c
remote=/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/radar-layers-preview
set +e
timeout 900 adb shell am instrument -w -r \
 -e class zone.disinfo.wx.macrobenchmark.RadarLayersPreviewTest \
 -e wxRadarLayersPreview true -e additionalTestOutputDir "$remote" \
 zone.disinfo.wx.macrobenchmark/androidx.test.runner.AndroidJUnitRunner > "$output/instrumentation.log" 2>&1
status=$?
set -e
adb pull "$remote/." "$output/" || true
adb logcat -d > "$output/logcat.txt" || true
if [[ $status -ne 0 ]] || ! grep -q 'OK (1 test)' "$output/instrumentation.log"; then
 cat "$output/instrumentation.log"
 grep -A 35 -B 2 -E 'FATAL EXCEPTION|Fatal signal' "$output/logcat.txt" || true
 exit 1
fi
