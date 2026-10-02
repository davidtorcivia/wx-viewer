#!/usr/bin/env python3
"""Always write a fail-closed comparison, including missing/setup-failed legs."""
import json
from pathlib import Path
import sys

VISUAL_REGRESSIONS = {'black_native_view', 'flat_native_view', 'partial_black_native_view',
                      'map_detail_loss', 'opaque_field_coverage_loss', 'frozen_native_playback'}


def summarize(root, count, shard):
    expected = 2 * sum(i % count == shard for i in range(10))
    result = {'baselineCommit': '0b69bee21196178bb92f91fc8b00495fb263713e',
              'baselineFailuresAreExpectedRegressionEvidence': True, 'commonHarness': True,
              'sameEmulator': True, 'legs': {}, 'failures': [], 'passed': False, 'status': 'inconclusive'}

    def error(code, message):
        result['failures'].append({'code': code, 'message': message})

    def read(path, label):
        try:
            value = json.loads(path.read_text())
            if not isinstance(value, dict):
                raise ValueError('Expected a JSON object')
            return value
        except (OSError, ValueError, TypeError) as exc:
            error('missing_or_invalid_evidence', f'{label} unavailable at {path}: {exc}. See setup/instrumentation logs; no continuity result is established.')
            return None

    if shard == 0:
        negative = read(root / 'baseline-negative-control/negative-control-proof.json', 'Controlled baseline negative proof')
        result['controlledBaselineNegativeProof'] = negative
        if negative is not None and negative.get('validNegativeControl') is not True:
            error('invalid_negative_control', negative.get('reason', 'Controlled baseline gap was not established'))
    for leg in ('baseline', 'candidate'):
        report = read(root / leg / 'continuity/continuity-analysis.json', f'{leg} native analysis')
        result['legs'][leg] = report
        if report is None:
            continue
        if report.get('schemaVersion') != 1:
            error('invalid_analysis_schema', f'{leg}: unsupported or missing analysis schema')
            continue
        captures = report.get('captures', [])
        if not isinstance(captures, list) or len(captures) != expected or report.get('failures'):
            error('incomplete_analysis', f'{leg}: expected {expected} captures and no inventory/setup failures; report status={report.get("status")}; failures={report.get("failures", [])}')
            continue
        if leg == 'baseline':
            for capture in captures:
                if (not isinstance(capture, dict) or capture.get('status') not in ('passed', 'failed')
                        or not isinstance(capture.get('failures'), list)
                        or any(not isinstance(f, dict) or f.get('status') != 'failed' or f.get('code') not in VISUAL_REGRESSIONS
                               for f in capture.get('failures', []))):
                    error('inconclusive_baseline', 'Baseline acquisition/action/cadence evidence is incomplete or inconclusive; expected visual regressions do not excuse missing evidence')
                    break
        elif report.get('passed') is not True or report.get('status') != 'passed':
            error('candidate_continuity_failed', f'Candidate native continuity did not pass: {report.get("status")}')
    result['passed'] = not result['failures']
    result['status'] = 'passed' if result['passed'] else 'inconclusive'
    root.mkdir(parents=True, exist_ok=True)
    (root / 'comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    root, count, shard = Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
    assert 1 <= count <= 5 and 0 <= shard < count
    report = summarize(root, count, shard)
    print(f"Native comparison: {report['status']}")
    for failure in report['failures']:
        print(f"{failure['code']}: {failure['message']}")
    sys.exit(0 if report['passed'] else 1)
