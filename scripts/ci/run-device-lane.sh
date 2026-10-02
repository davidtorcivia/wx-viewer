#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/../.."
lane=${1:?lane required}
export WX_NATIVE_VIDEO_EVIDENCE=${WX_NATIVE_VIDEO_EVIDENCE:-false}
[[ "$WX_NATIVE_VIDEO_EVIDENCE" == true || "$WX_NATIVE_VIDEO_EVIDENCE" == false ]] || exit 2
status=0
mkdir -p ci-proof
run_phase() {
  local name=$1 start result
  shift
  start=$(date +%s)
  echo "::group::$name"
  "$@"
  result=$?
  echo '::endgroup::'
  printf '%s\t%s\t%s\n' "$name" "$result" "$(( $(date +%s) - start ))" >> "ci-proof/$lane-phases.tsv"
  if [[ $result -ne 0 ]]; then status=1; fi
}
debug=app/build/outputs/apk/debug/app-debug.apk
tests=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
preview=app/build/outputs/apk/preview/app-preview.apk
external=macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
case "$lane" in
  debug)
    run_phase 'Fast targeted slider, timer ownership and connectivity regressions' bash scripts/ci/run-debug-tests.sh smoke
    run_phase 'Every discovered Android test and screen capture' bash scripts/ci/run-debug-tests.sh full
    run_phase 'Minified offline cold-start, process-death and pull-refresh' bash scripts/run-offline-preview-smoke.sh "$debug" "$tests" "$preview" "$external" app/build/outputs/offline-preview
    run_phase 'Saved radar in a fresh process with radios off' bash scripts/run-radar-offline-phases.sh "$debug" "$tests" app/build/outputs/radar-offline-phases
    run_phase 'Actual-device 200-percent text and dialogs' bash scripts/run-screen-beauty-device.sh "$debug" "$tests" app/build/outputs/e2e/device-beauty
    cp app/build/outputs/e2e/full/tests.json ci-proof/debug-tests.json 2>/dev/null || status=1
    ;;
  playback)
    run_phase 'Cold forecast and sustained minified playback, interruptions and recovery' bash scripts/run-radar-layers-preview.sh playback
    ;;
  layers-[012])
    export WX_LAYER_SHARD_COUNT=3 WX_LAYER_SHARD_INDEX=${lane#layers-}
    printf '%s\n' "$WX_NATIVE_VIDEO_EVIDENCE" > "ci-proof/$lane-native-video.txt"
    run_phase 'Live, saved-offline and empty-offline layer shard' bash scripts/run-radar-layers-preview.sh layers
    output=app/build/outputs/radar-layers-preview
    if [[ "$WX_NATIVE_VIDEO_EVIDENCE" == true ]]; then
      cp "$output/continuity/continuity-analysis.json" "ci-proof/$lane-continuity.json" || status=1
      for kind in acquisition analysis ''; do
        suffix=${kind:+-$kind}
        cp "$output/continuity/continuity$suffix-status.txt" "ci-proof/$lane-continuity$suffix-status.txt" || status=1
      done
      cp "$output/continuity/continuity-gate.json" "ci-proof/$lane-continuity-gate.json" || status=1
    fi
    for state in live saved empty; do
      source="$output/radar-layers-proof.json"
      [[ "$state" == live ]] || source="$output/offline-$state/radar-layers-proof.json"
      cp "$source" "ci-proof/$lane-$state.json" || status=1
    done
    ;;
  *) echo "Unknown lane: $lane" >&2; exit 2 ;;
esac
python3 scripts/ci/check-phase-tests.py "$lane" || status=1
printf '%s\n' "$status" > "ci-proof/$lane-status.txt"
exit "$status"
