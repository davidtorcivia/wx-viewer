#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
status=0
bash scripts/run-radar-layers-preview.sh || status=1
WX_REQUIRE_SCREENSHOTS=1 sh scripts/run-android-e2e.sh || status=1
debug=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
preview=app/build/outputs/apk/preview/app-preview.apk
external=macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
bash scripts/run-offline-preview-smoke.sh "$debug" "$tests" "$preview" "$external" \
  app/build/outputs/offline-preview || status=1
bash scripts/run-radar-offline-phases.sh "$debug" "$tests" \
  app/build/outputs/radar-offline-phases || status=1
exit "$status"
