#!/usr/bin/env sh
# AGP collects app-owned shared output before uninstalling the instrumented app.
set -u
cd "$(dirname "$0")/.."
mkdir -p app/build/outputs/e2e
bash ./gradlew --no-daemon "$@" connectedDebugAndroidTest
status=$?
additional=app/build/outputs/connected_android_test_additional_output
# This diagnostic may show the launcher after AGP cleanup; it is not an app screenshot.
adb exec-out screencap -p > app/build/outputs/e2e/post-suite-screen.png 2>/dev/null || true
if [ "${WX_REQUIRE_SCREENSHOTS:-0}" = 1 ] && ! find "$additional" -type f -name '*.png' 2>/dev/null | grep -q .; then
  echo "No device test screenshots were collected before app cleanup" >&2
  if [ "$status" -eq 0 ]; then status=1; fi
fi
exit "$status"
