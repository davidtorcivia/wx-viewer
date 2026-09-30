#!/usr/bin/env sh
# Run against an already installed debug app + instrumentation APK on a test device/emulator.
# Separate invocations intentionally prove a process-death restore, not an in-memory hit.
set -eu
phase=${1:-}
case "$phase" in
  seed) method=seedColdProcessRadar ;;
  verify)
    # Turn off device Wi-Fi and mobile data before this phase; the test verifies actual offline state.
    adb shell am force-stop zone.disinfo.wx
    method=coldProcessRadarRestoresActualImageWithoutFrameMetadata
    ;;
  *) echo 'Usage: run-radar-offline-e2e.sh seed | verify (disable test-device networking before verify)' >&2; exit 2 ;;
esac
adb shell am instrument -w -r -e radarCachePhase "$phase" \
  -e class "zone.disinfo.wx.ui.RadarOfflineIntegrationTest#$method" \
  zone.disinfo.wx.test/androidx.test.runner.AndroidJUnitRunner
