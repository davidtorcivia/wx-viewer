#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
status=0
WX_REQUIRE_SCREENSHOTS=1 sh scripts/run-android-e2e.sh :macrobenchmark:testPlaybackShapeOracle || status=1
# Run deterministic UI/network regressions before the longer public-feed matrix.
# The preview harness clears app storage and reseeds its own settings, so its
# cold-start/cache guarantees do not depend on the earlier debug suite.
bash scripts/run-radar-layers-preview.sh || status=1
debug=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
preview=app/build/outputs/apk/preview/app-preview.apk
external=macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
bash scripts/run-offline-preview-smoke.sh "$debug" "$tests" "$preview" "$external" \
  app/build/outputs/offline-preview || status=1
bash scripts/run-radar-offline-phases.sh "$debug" "$tests" \
  app/build/outputs/radar-offline-phases || status=1
bash scripts/run-screen-beauty-device.sh "$debug" "$tests" \
  app/build/outputs/e2e/device-beauty || status=1
exit "$status"
