#!/usr/bin/env python3
"""Require actual passes, not JUnit's OK summary (which also permits assumptions)."""
import importlib.util
import json
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('instrumentation', Path(__file__).with_name('check-instrumentation.py'))
instrumentation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(instrumentation)
P = 'zone.disinfo.wx.'
M = P + 'macrobenchmark.'

def required_logs(lane):
    if lane == 'debug':
        return {
            'offline-preview/pull-refresh-offline.log': [P + 'ManualRefreshLifecycleE2eTest#cachedManualFailureCompletesAllRequestsAndKeepsCurrentFixAndContent', P + 'ManualRefreshLifecycleE2eTest#emptyCacheFailureStopsManualRefreshAndAllowsAnotherAttempt'],
            'offline-preview/seed.log': [P + 'OfflinePreviewSeedTest#seedForMinifiedPreview'],
            'offline-preview/offline-preview.log': [M + 'OfflinePreviewSmokeTest#actualColdProcessRestoresCachedAppAcrossNetworkFlap'],
            'radar-offline-phases/seed.log': [P + 'ui.RadarOfflineIntegrationTest#seedColdProcessRadar'],
            'radar-offline-phases/verify.log': [P + 'ui.RadarOfflineIntegrationTest#coldProcessRadarRestoresActualImageWithoutFrameMetadata'],
            'e2e/device-beauty/results.txt': [P + 'ScreenBeautyE2eTest#actualDeviceLargeTextLightDialogs', P + 'ScreenBeautyE2eTest#actualDeviceLargeTextDarkDialogs'],
        }
    if lane == 'playback':
        methods = ['coldRadarFreshnessAndAvailableForecastPlayback', 'sustainedPlaybackPixelsAndInterruptedFlowsOnMinifiedPreview']
        return {f'radar-layers-preview/playback-{method}/instrumentation.log': [M + 'RadarPlaybackPreviewTest#' + method] for method in methods}
    if lane in ('layers-0', 'layers-1', 'layers-2'):
        return {
            'radar-layers-preview/instrumentation.log': [M + 'RadarLayersPreviewTest#allLiveLayersRangesInteractionsAndLifecycle'],
            'radar-layers-preview/offline-saved.log': [M + 'RadarLayersPreviewTest#allLayersRemainResponsiveOffline'],
            'radar-layers-preview/offline-empty.log': [M + 'RadarLayersPreviewTest#allLayersRemainResponsiveOffline'],
            **{f'radar-layers-preview/continuity/font-{font}/instrumentation.log': [M + 'RadarLayersPreviewTest#allLayersContinuousNativeFrames'] for font in ('1.0', '2.0')},
        }
    raise ValueError(lane)


def validate_results(lane, results):
    expected = required_logs(lane)
    assert set(results) == set(expected), f'{lane}: missing phase logs'
    for name, methods in expected.items():
        tests = results[name]
        assert set(tests) == set(methods), f'{name}: missing/unexpected phase methods'
        assert all(value['code'] == 0 for value in tests.values()), f'{name}: failed or skipped phase method'


def collect(lane, output):
    results = {name: instrumentation.parse((output / name).read_text()) for name in required_logs(lane)}
    validate_results(lane, results)
    return results

if __name__ == '__main__':
    lane = sys.argv[1]
    results = collect(lane, Path('app/build/outputs'))
    Path(f'ci-proof/{lane}-phase-tests.json').write_text(json.dumps(results, indent=2))
    print(f'Verified {sum(map(len, results.values()))} real passes in {lane} special phases')
