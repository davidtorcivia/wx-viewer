#!/usr/bin/env sh
# Runs production-app instrumentation, then retains screenshots even on failure.
set -u
cd "$(dirname "$0")/.."
mkdir -p app/build/outputs/e2e
bash ./gradlew --no-daemon "$@" connectedDebugAndroidTest
status=$?
adb exec-out run-as zone.disinfo.wx tar -cf - -C files/e2e . > app/build/outputs/e2e/screenshots.tar 2>/dev/null && \
  tar xf app/build/outputs/e2e/screenshots.tar -C app/build/outputs/e2e/ || true
rm -f app/build/outputs/e2e/screenshots.tar
adb exec-out screencap -p > app/build/outputs/e2e/final-screen.png 2>/dev/null || true
exit "$status"
