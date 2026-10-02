#!/usr/bin/env python3
"""Require actual passes, not JUnit's OK summary (which also permits assumptions)."""
import importlib.util
import json
import os
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location('instrumentation', Path(__file__).with_name('check-instrumentation.py'))
instrumentation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(instrumentation)
P = 'zone.disinfo.wx.'
M = P + 'macrobenchmark.'

def native_video_enabled(value=None):
    value = os.environ.get('WX_NATIVE_VIDEO_EVIDENCE', 'false') if value is None else value
    assert value in ('true', 'false'), 'WX_NATIVE_VIDEO_EVIDENCE must be true or false'
    return value == 'true'


def required_logs(lane, native_video=False):
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
            **({f'radar-layers-preview/continuity/font-{font}/instrumentation.log': [M + 'RadarLayersPreviewTest#allLayersContinuousNativeFrames'] for font in ('1.0', '2.0')} if native_video else {}),
        }
    raise ValueError(lane)


def validate_results(lane, results, native_video=False):
    expected = required_logs(lane, native_video)
    assert set(results) == set(expected), f'{lane}: missing phase logs'
    for name, methods in expected.items():
        tests = results[name]
        assert set(tests) == set(methods), f'{name}: missing/unexpected phase methods'
        assert all(value['code'] == 0 for value in tests.values()), f'{name}: failed or skipped phase method'


def collect(lane, output, native_video=False):
    results = {name: instrumentation.parse((output / name).read_text()) for name in required_logs(lane, native_video)}
    validate_results(lane, results, native_video)
    return results

if __name__ == '__main__':
    lane = sys.argv[1]
    results = collect(lane, Path('app/build/outputs'), native_video_enabled())
    Path(f'ci-proof/{lane}-phase-tests.json').write_text(json.dumps(results, indent=2))
    print(f'Verified {sum(map(len, results.values()))} real passes in {lane} special phases')
