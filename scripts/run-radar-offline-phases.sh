#!/usr/bin/env bash
# Execute the two intentionally skipped suite phases in separate app processes, with real radios off.
set -euo pipefail
debug_apk=$(realpath "$1")
tests_apk=$(realpath "$2")
output=$(realpath -m "$3")
mkdir -p "$output"
[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "Disposable emulator required" >&2; exit 2; }
airplane=$(adb shell settings get global airplane_mode_on | tr -d '\r')
wifi=$(adb shell settings get global wifi_on | tr -d '\r')
data=$(adb shell settings get global mobile_data | tr -d '\r')
[[ "$airplane" =~ ^[01]$ && "$wifi" =~ ^[0123]$ && "$data" =~ ^[01]$ ]] || exit 2
restore_radios() {
  timeout 20 adb shell cmd connectivity airplane-mode "$([[ "$airplane" == 1 ]] && echo enable || echo disable)" || true
  timeout 20 adb shell svc wifi "$([[ "$wifi" == 1 || "$wifi" == 2 ]] && echo enable || echo disable)" || true
  timeout 20 adb shell svc data "$([[ "$data" == 1 ]] && echo enable || echo disable)" || true
}
trap restore_radios EXIT INT TERM
adb install -r "$debug_apk"
adb install -r -t "$tests_apk"
timeout 180 bash "$(dirname "$0")/run-radar-offline-e2e.sh" seed > "$output/seed.log" 2>&1
grep -q 'OK (1 test)' "$output/seed.log" || { cat "$output/seed.log"; exit 1; }
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
set +e
timeout 180 bash "$(dirname "$0")/run-radar-offline-e2e.sh" verify > "$output/verify.log" 2>&1
status=$?
set -e
adb logcat -d -t 1000 > "$output/logcat.txt" || true
if [[ $status -ne 0 ]] || ! grep -q 'OK (1 test)' "$output/verify.log"; then
  cat "$output/verify.log"
  exit 1
fi
