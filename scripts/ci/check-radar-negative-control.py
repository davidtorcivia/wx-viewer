#!/usr/bin/env python3
"""Accept only the known native weather-gap failure on the unchanged baseline."""
import importlib.util
import json
from pathlib import Path
import re
import sys

SPEC = importlib.util.spec_from_file_location('instrumentation', Path(__file__).with_name('check-instrumentation.py'))
instrumentation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(instrumentation)
TEST = 'zone.disinfo.wx.ui.RadarRasterContinuityTest#cloudsRetainWeatherThroughDelayedFailedAndStaleTiles'
EXPECTED = re.compile(r'^java\.lang\.AssertionError: clouds/delayed-replacement (?:painted an unknown/blank/double-weather composite:|lost weather pixels at sample )')
FORBIDDEN = re.compile(r'FATAL EXCEPTION|Fatal signal|INSTRUMENTATION_FAILED|Process crashed|NoSuchMethodError|NoSuchFieldError|IllegalAccessError|NoClassDefFoundError|SecurityException')


LOGCAT_LINE = re.compile(r'^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+(\d+)\s+([VDIWEAF])\s+([^:]+):\s*(.*)$')
TARGET_START = re.compile(r'Start proc (\d+):zone\.disinfo\.wx(?:\.test)?(?::[^/\s]+)?/u(\d+)a(\d+)\b')
STATS_PERMISSION = re.compile(r'^java\.lang\.SecurityException: Need REGISTER_STATS_PULL_ATOM permission\.: Neither user (\d+) nor current process has android\.permission\.REGISTER_STATS_PULL_ATOM\.$')


def classify_runtime_diagnostics(logcat):
    """Keep fail-closed matching; exempt only the observed unrelated OS stats warning.

    Run36962125678's actual log attributes this warning to Binder in system_server,
    calling UID10120; ActivityManager separately proves the WX/test PIDs and UIDs.
    Never suppress it in an app/test PID, for the app/test UID, without attribution,
    or as an instrumentation failure. Preserve the exact ignored line in the proof.
    """
    target_pids, target_uids, system_pids = set(), set(), set()
    attribution_lines = []
    for line in logcat.splitlines():
        parsed = LOGCAT_LINE.match(line)
        if parsed and parsed[4].strip() == 'ActivityManager':
            started = TARGET_START.search(parsed[5])
            if started:
                target_pids.add(int(started[1]))
                target_uids.add(int(started[2]) * 100000 + 10000 + int(started[3]))
                system_pids.add(int(parsed[1]))
                attribution_lines.append(line)
    rejected, ignored = [], []
    for line in logcat.splitlines():
        if not FORBIDDEN.search(line):
            continue
        parsed = LOGCAT_LINE.match(line)
        stats = STATS_PERMISSION.match(parsed[5]) if parsed else None
        if (parsed and stats and target_pids and target_uids and int(parsed[1]) in system_pids
                and int(parsed[1]) not in target_pids and parsed[3] == 'W'
                and parsed[4].strip() == 'Binder' and int(stats[1]) not in target_uids):
            ignored.append(line)
        else:
            rejected.append(line)
    attribution = {'targetProcessIds': sorted(target_pids), 'targetUids': sorted(target_uids),
                   'systemServerProcessIds': sorted(system_pids),
                   'activityManagerStartLines': attribution_lines}
    return rejected, ignored, attribution


def validate(log, logcat, metrics, image_exists):
    tests = instrumentation.parse(log)
    assert set(tests) == {TEST}, 'Negative control did not run exactly the requested test'
    result = tests[TEST]
    assert result['code'] == -2, 'Expected a JUnit assertion failure, not pass/skip/crash/error'
    assert EXPECTED.match(result['stack']), 'Failure is not the known delayed-replacement weather gap'
    assert not FORBIDDEN.search(log), 'Crash, access/linkage or instrumentation error in test output invalidates negative control'
    rejected, ignored, attribution = classify_runtime_diagnostics(logcat)
    assert not rejected, 'Crash, access/linkage or unattributed runtime error invalidates negative control: ' + ' | '.join(rejected)[:2000]
    assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$', log, re.M), 'Runner did not complete normally'
    assert metrics.get('overlay') == 'clouds' and metrics.get('field') == 'cloud'
    assert metrics.get('outcome') == 'failed' and EXPECTED.match(metrics.get('failure', '')), 'Metrics failure differs from known rendering failure'
    samples = metrics.get('samples', [])
    ready = [s for s in samples if s.get('phase') == 'initial-ready' and s.get('red', 0) >= .65 and s.get('redComposite', 0) >= .98]
    gaps = [s for s in samples if s.get('phase') == 'delayed-replacement' and
            (s.get('redComposite', 1) < .98 or s.get('red', 1) < .65)]
    assert ready and gaps, 'Need real initially painted weather followed by measured delayed-target gap'
    requests = metrics.get('requests', {})
    assert requests.get('unrelatedPendingBasemap', 0) > 0, 'Controlled unrelated basemap request was not exercised'
    assert any('continuity-cloud-1-' in path and count > 0 for path, count in requests.items()), 'Delayed target never reached native transport'
    for sample in (ready[0], gaps[0]):
        assert sample.get('pixels', 0) > 1000 and image_exists(sample.get('weatherScreenshot', '')), 'Native pixel metrics need their actual saved weather image'
    return {'validNegativeControl': True, 'result': 'expected-weather-gap-detected', 'test': TEST,
            'failure': result['stack'], 'initialReadySequence': ready[0]['sequence'], 'gapSequence': gaps[0]['sequence'],
            'initialRedFraction': ready[0]['red'], 'gapRedFraction': gaps[0]['red'],
            'gapRedCompositeFraction': gaps[0]['redComposite'],
            'ignoredUnrelatedSystemStatsWarnings': ignored,
            'ignoredWarningAttribution': attribution if ignored else None}


def main(root):
    report = {'validNegativeControl': False, 'result': 'unavailable-negative-proof'}
    try:
        assert (root / 'command-status.txt').read_text().strip() == '0', 'Negative control command timed out or failed before normal runner completion'
        directory = root / 'raster-continuity-clouds'
        metrics = json.loads((directory / 'metrics.json').read_text())
        def exists(name):
            path = (directory / name).resolve()
            return bool(name) and path.is_relative_to(directory.resolve()) and path.is_file() and path.stat().st_size > 80
        report = validate((root / 'instrumentation.log').read_text(), (root / 'logcat.txt').read_text(), metrics, exists)
    except (OSError, ValueError, TypeError, KeyError, AssertionError) as error:
        report['reason'] = str(error)
    (root / 'negative-control-proof.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return 0 if report['validNegativeControl'] else 1

if __name__ == '__main__':
    sys.exit(main(Path(sys.argv[1])))
