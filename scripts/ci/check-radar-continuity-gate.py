#!/usr/bin/env python3
"""Separate strictly acquisition-limited diagnostics from blocking radar evidence.

The analyzer and its original nonzero result are never rewritten. Only these three
measured acquisition limits may be nonblocking; unknown/incomplete/pixel/correctness
failures stay blocking, including palette ambiguity and insufficient painted stamps.
"""
import argparse
import json
import math
from pathlib import Path
import re

LAYERS = ['Temperature', 'Dew point', 'Wind', 'Wind gusts', 'Clouds',
          'Precip total', 'Snow total', 'Radar', 'Satellite', 'Radar + satellite']
ACQUISITION_ONLY = frozenset({'low_capture_cadence', 'irregular_capture_cadence', 'capture_gap'})
THRESHOLDS = {'minimumEffectiveFps': 24.0, 'maximumFrameGapMs': 150.0,
              'maximumP95FrameGapMs': 100.0, 'minimumPlays': 2,
              'minimumPlaySeconds': 4.0, 'minimumScrubs': 4}


def validate(report, acquisition_status, analysis_status, diagnostic_status, shard_count, shard_index):
    assert isinstance(report, dict)
    assert all(type(value) is int for value in (acquisition_status, analysis_status, diagnostic_status, shard_count, shard_index))
    assert acquisition_status == 0, 'Native capture/setup/transport failed'
    assert analysis_status in (0, 1), 'Analyzer did not finish normally'
    assert diagnostic_status == analysis_status, 'Raw diagnostic result differs from analyzer/capture result'
    assert 1 <= shard_count <= 5 and 0 <= shard_index < shard_count
    assert type(report['schemaVersion']) is int and report['schemaVersion'] == 1 and report['analysis'] == 'native-encoded-frame-continuity'
    assert type(report['shardCount']) is int and type(report['shardIndex']) is int
    assert report['shardCount'] == shard_count and report['shardIndex'] == shard_index
    assert report['thresholds'] == THRESHOLDS, 'Continuity thresholds changed'
    assert all(type(value) in (int, float) and math.isfinite(value) for value in report['thresholds'].values())
    assert report['fontScales'] == [1.0, 2.0]
    assert type(report['fontScales']) is list and all(type(value) in (int, float) for value in report['fontScales'])
    assert report['failures'] == [], 'Inventory/setup failure remains blocking'
    expected = {(layer, font) for i, layer in enumerate(LAYERS) if i % shard_count == shard_index
                for font in (1.0, 2.0)}
    assert all(type(report[key]) is int for key in ('expectedCaptures', 'analyzedCaptures', 'decodedFrames'))
    assert report['expectedCaptures'] == report['analyzedCaptures'] == len(expected)
    assert type(report['captures']) is list
    seen, limited = set(), []
    frames = 0
    for capture in report['captures']:
        assert isinstance(capture, dict)
        assert type(capture['layer']) is str and type(capture['fontScale']) in (int, float)
        assert math.isfinite(capture['fontScale'])
        key = (capture['layer'], float(capture['fontScale']))
        assert key in expected and key not in seen, f'Wrong/duplicate capture: {key}'
        seen.add(key)
        assert capture['theme'] == 'dark'
        assert re.fullmatch(r'[0-9a-f]{64}', capture['videoSha256']), 'Missing native-video identity'
        assert capture['coverage']['everyEncodedFrame'] is True
        summary = capture['summary']
        assert isinstance(summary, dict)
        assert all(type(summary[key]) is int for key in ('decodedFrames', 'completedPlays', 'completedScrubs', 'duplicateOrBackwardPts'))
        assert summary['decodedFrames'] > 0 and summary['completedPlays'] >= 2 and summary['completedScrubs'] >= 4
        assert summary['duplicateOrBackwardPts'] == 0
        frames += summary['decodedFrames']
        failures = capture['failures']
        assert type(failures) is list and all(isinstance(f, dict) for f in failures), 'Malformed failure evidence'
        if failures:
            assert capture['status'] == 'inconclusive' and capture['passed'] is False, 'Product/evidence failure remains blocking'
            assert all(f['code'] in ACQUISITION_ONLY and f['status'] == 'inconclusive' for f in failures), failures
            limited.append({'layer': key[0], 'fontScale': key[1],
                            'codes': sorted({f['code'] for f in failures})})
        else:
            assert capture['status'] == 'passed' and capture['passed'] is True
    assert seen == expected, 'Missing native-video case'
    assert report['decodedFrames'] == frames
    expected_status = 'inconclusive' if limited else 'passed'
    assert report['status'] == expected_status and report['passed'] is (not limited), 'Inconsistent analyzer result'
    assert analysis_status == (1 if limited else 0), 'Raw analyzer exit differs from report'
    return {'schemaVersion': 1, 'gatePassed': True,
            'status': 'acquisition-limited' if limited else 'passed',
            'continuityProven': not limited, 'analyzerStatus': report['status'],
            'analyzerExitCode': analysis_status, 'acquisitionExitCode': acquisition_status,
            'nonblockingAcquisitionLimits': limited,
            'scope': 'Original thresholds and diagnostic failures retained. This is not a no-flash pass.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('--acquisition-status', required=True, type=Path)
    parser.add_argument('--analysis-status', required=True, type=Path)
    parser.add_argument('--diagnostic-status', required=True, type=Path)
    parser.add_argument('--runner-exit', required=True, type=int)
    parser.add_argument('--shard-count', required=True, type=int)
    parser.add_argument('--shard-index', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        diagnostic_status = int(args.diagnostic_status.read_text().strip())
        assert args.runner_exit == diagnostic_status, 'Current runner exit differs from saved diagnostic result'
        result = validate(json.loads(args.report.read_text()),
                          int(args.acquisition_status.read_text().strip()),
                          int(args.analysis_status.read_text().strip()), diagnostic_status,
                          args.shard_count, args.shard_index)
    except (AssertionError, KeyError, TypeError, ValueError, OSError) as exc:
        result = {'schemaVersion': 1, 'gatePassed': False, 'status': 'blocked',
                  'continuityProven': False, 'reason': str(exc) or type(exc).__name__}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['gatePassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
