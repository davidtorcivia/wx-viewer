import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).parent

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

instrumentation = load('instrumentation', 'check-instrumentation.py')
coverage = load('coverage_contract', 'verify-coverage.py')

def continuity_report(shard):
    captures = [{'layer': layer, 'fontScale': font, 'theme': 'dark', 'status': 'passed',
                 'passed': True, 'failures': [], 'videoSha256': 'a' * 64,
                 'coverage': {'everyEncodedFrame': True},
                 'summary': {'decodedFrames': 1000, 'completedPlays': 3,
                             'completedScrubs': 4, 'duplicateOrBackwardPts': 0}}
                for index, layer in enumerate(coverage.LAYERS) if index % 3 == shard
                for font in (1.0, 2.0)]
    return {'schemaVersion': 1, 'analysis': 'native-encoded-frame-continuity',
            'shardCount': 3, 'shardIndex': shard, 'fontScales': [1.0, 2.0],
            'thresholds': dict(coverage.continuity_gate.THRESHOLDS),
            'expectedCaptures': len(captures), 'analyzedCaptures': len(captures),
            'decodedFrames': 1000 * len(captures),
            'passed': True, 'status': 'passed', 'failures': [], 'captures': captures}


def acquisition_limited(report, code='low_capture_cadence'):
    report.update(status='inconclusive', passed=False)
    report['captures'][0].update(status='inconclusive', passed=False,
                                failures=[{'code': code, 'status': 'inconclusive'}])
    return report

class InstrumentationContractTest(unittest.TestCase):
    def test_parser_tracks_terminal_result_not_started_or_summary(self):
        raw = '\n'.join(['INSTRUMENTATION_STATUS: class=Example', 'INSTRUMENTATION_STATUS: test=works',
                         'INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS: class=Example',
                         'INSTRUMENTATION_STATUS: test=works', 'INSTRUMENTATION_STATUS_CODE: 0',
                         'INSTRUMENTATION_CODE: -1'])
        self.assertEqual(instrumentation.parse(raw), {'Example#works': {'code': 0, 'stack': ''}})

    def test_missing_and_unexpected_tests_fail(self):
        tests = {'Example#a': {'code': 0}, 'Example#b': {'code': 0}}
        with self.assertRaises(AssertionError):
            instrumentation.validate({'Example#a': tests['Example#a']}, tests)
        with self.assertRaises(AssertionError):
            instrumentation.validate(tests, {'Example#a': tests['Example#a']})

    def test_failure_assumption_and_ignore_fail(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(AssertionError):
                instrumentation.validate({'Example#a': {'code': code}}, {'Example#a': {}})

    def test_only_explicit_separate_phases_may_skip(self):
        tests = {name: {'code': -4} for name in instrumentation.PHASE_ONLY}
        instrumentation.validate(tests, tests, allow_phases=True)
        tests['Example#unplannedSkip'] = {'code': -4}
        with self.assertRaises(AssertionError):
            instrumentation.validate(tests, tests, allow_phases=True)

    def test_duplicate_terminal_result_fails(self):
        raw = 'INSTRUMENTATION_STATUS: class=A\nINSTRUMENTATION_STATUS: test=b\nINSTRUMENTATION_STATUS_CODE: 0\n'
        with self.assertRaises(AssertionError):
            instrumentation.parse(raw + raw)

class CoverageContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for lane in coverage.LANES:
            (self.root / (lane + '-status.txt')).write_text('0\n')
            count = 5 if lane == 'debug' else 1
            (self.root / (lane + '-phases.tsv')).write_text('phase\t0\t10\n' * count)
        for state in ('live', 'saved', 'empty'):
            for shard in range(3):
                results = []
                for layer, index in sorted(coverage.EXPECTED):
                    if coverage.LAYERS.index(layer) % 3 != shard:
                        continue
                    case = {'layer': layer, 'rangeIndex': index, 'result': 'passed'}
                    if state == 'live':
                        case['playback'] = {'animated': True}
                    else:
                        case['offline'] = True
                    results.append(case)
                if state == 'live' and shard == 0:
                    results.append({'rapidSwitches': 20, 'coldRestart': True, 'result': 'passed'})
                self.write(f'layers-{shard}-{state}.json', {'shardIndex': shard, 'shardCount': 3,
                           'minifiedPreview': True, 'failures': [], 'results': results})
        for shard in range(3):
            self.write(f'layers-{shard}-continuity.json', continuity_report(shard))
            for suffix in ('-acquisition', '-analysis', ''):
                (self.root / f'layers-{shard}-continuity{suffix}-status.txt').write_text('0\n')
        for lane in coverage.LANES:
            self.write(lane + '-phase-tests.json', {name: {method: {'code': 0} for method in methods}
                       for name, methods in coverage.phases_contract.required_logs(lane).items()})
        source = instrumentation.source_inventory(HERE.resolve().parents[1] / 'app/src/androidTest')
        self.write('debug-tests.json', {'discovered': len(source), 'executed': len(source),
                   'tests': {key: {'code': 0} for key in source}})

    def write(self, name, data):
        (self.root / name).write_text(json.dumps(data))

    def mutate(self, name, function):
        data = json.loads((self.root / name).read_text())
        function(data)
        self.write(name, data)

    def test_complete_union(self):
        result = coverage.validate(self.root)
        self.assertEqual(result['layers'], {'live': 28, 'saved': 28, 'empty': 28})

    def test_missing_continuity_video_cannot_pass(self):
        self.mutate('layers-0-continuity.json', lambda d: d['captures'].pop())
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_unexplained_inconclusive_cannot_pass(self):
        self.mutate('layers-1-continuity.json', lambda d: d.update(status='inconclusive', passed=False))
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_only_whitelisted_acquisition_limits_are_nonblocking_and_explicit(self):
        self.mutate('layers-1-continuity.json', acquisition_limited)
        for suffix in ('-analysis', ''):
            (self.root / f'layers-1-continuity{suffix}-status.txt').write_text('1\n')
        result = coverage.validate(self.root)
        self.assertFalse(result['continuity_proven'])
        self.assertEqual(len(result['nonblocking_acquisition_limits']), 1)
        # No generated gate JSON is trusted; aggregate recomputes from raw evidence.
        self.write('layers-1-continuity-gate.json', {'gatePassed': True})
        self.mutate('layers-1-continuity.json', lambda d: d['captures'][0]['failures'].append(
            {'code': 'black_frame', 'status': 'failed'}))
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_duplicate_continuity_video_cannot_pass(self):
        self.mutate('layers-0-continuity.json', lambda d: d['captures'].append(d['captures'][0]))
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_special_phase_skip_cannot_pass(self):
        self.mutate('playback-phase-tests.json', lambda d: next(iter(next(iter(d.values())).values())).update(code=-4))
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_filtered_source_test_cannot_disappear(self):
        def omit(d):
            d['tests'].pop(next(iter(d['tests'])))
            d['executed'] -= 1
            d['discovered'] -= 1
        self.mutate('debug-tests.json', omit)
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_missing_shard_fails(self):
        (self.root / 'layers-1-live.json').unlink()
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_duplicate_or_missing_case_fails(self):
        for operation in ('duplicate', 'missing'):
            with self.subTest(operation=operation):
                data = json.loads((self.root / 'layers-0-saved.json').read_text())
                original = copy.deepcopy(data)
                if operation == 'duplicate': data['results'].append(data['results'][0])
                else: data['results'].pop()
                self.write('layers-0-saved.json', data)
                with self.assertRaises(AssertionError): coverage.validate(self.root)
                self.write('layers-0-saved.json', original)

    def test_cross_layer_stress_cannot_disappear(self):
        self.mutate('layers-0-live.json', lambda d: d['results'].pop())
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_wrong_shard_cannot_pass(self):
        self.mutate('layers-1-empty.json', lambda d: d.update(shardIndex=2))
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_lane_failure_cannot_pass(self):
        (self.root / 'playback-status.txt').write_text('1\n')
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_missing_separate_phase_cannot_pass(self):
        (self.root / 'debug-phases.tsv').write_text('phase\t0\t10\n' * 4)
        with self.assertRaises(AssertionError): coverage.validate(self.root)

    def test_partial_android_suite_cannot_pass(self):
        self.mutate('debug-tests.json', lambda d: d.update(executed=159))
        with self.assertRaises(AssertionError): coverage.validate(self.root)


class RadarNegativeControlContractTest(unittest.TestCase):
    def setUp(self):
        self.validator = load('radar_negative_control', 'check-radar-negative-control.py')
        self.failure = 'java.lang.AssertionError: clouds/delayed-replacement painted an unknown/blank/double-weather composite: {"red":0}'
        self.log = ('INSTRUMENTATION_STATUS: class=zone.disinfo.wx.ui.RadarRasterContinuityTest\n'
                    'INSTRUMENTATION_STATUS: test=cloudsRetainWeatherThroughDelayedFailedAndStaleTiles\n'
                    f'INSTRUMENTATION_STATUS: stack={self.failure}\n'
                    'INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n')
        self.metrics = {'overlay': 'clouds', 'field': 'cloud', 'outcome': 'failed', 'failure': self.failure,
                        'requests': {'unrelatedPendingBasemap': 1, '/continuity-cloud-1-fixture': 1},
                        'samples': [{'phase': 'initial-ready', 'red': 1., 'redComposite': 1., 'sequence': 0,
                                     'pixels': 10000, 'weatherScreenshot': 'ready.png'},
                                    {'phase': 'delayed-replacement', 'red': 0., 'redComposite': 0., 'sequence': 1,
                                     'pixels': 10000, 'weatherScreenshot': 'gap.png'}]}

    def test_known_gap_with_real_pixel_evidence_is_required(self):
        proof = self.validator.validate(self.log, '', self.metrics, lambda _: True)
        self.assertTrue(proof['validNegativeControl'])

    def test_arbitrary_assertion_is_not_negative_evidence(self):
        with self.assertRaises(AssertionError):
            self.validator.validate(self.log.replace(self.failure, 'java.lang.AssertionError: Timeout'), '', self.metrics, lambda _: True)

    def test_crash_linkage_access_errors_cannot_pass(self):
        for error in ('FATAL EXCEPTION', 'NoSuchMethodError', 'IllegalAccessError', 'SecurityException'):
            with self.subTest(error=error), self.assertRaises(AssertionError):
                self.validator.validate(self.log, error, self.metrics, lambda _: True)

    def test_missing_native_pixels_or_transport_cannot_pass(self):
        for key in ('samples', 'requests'):
            metrics = copy.deepcopy(self.metrics); metrics[key] = [] if key == 'samples' else {}
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.validator.validate(self.log, '', metrics, lambda _: True)
        with self.assertRaises(AssertionError):
            self.validator.validate(self.log, '', self.metrics, lambda _: False)

    def test_only_proven_unrelated_system_stats_warning_is_ignored(self):
        start = '10-02 03:59:06.749 542 576 I ActivityManager: Start proc 1848:zone.disinfo.wx/u0a148 for added application zone.disinfo.wx'
        warning = '10-02 03:59:07.681 542 1637 W Binder : java.lang.SecurityException: Need REGISTER_STATS_PULL_ATOM permission.: Neither user 10120 nor current process has android.permission.REGISTER_STATS_PULL_ATOM.'
        proof = self.validator.validate(self.log, start + '\n' + warning, self.metrics, lambda _: True)
        self.assertEqual(proof['ignoredUnrelatedSystemStatsWarnings'], [warning])
        for invalid in (warning, start + '\n' + warning.replace('542 1637', '1848 1637'),
                        start + '\n' + warning.replace('user 10120', 'user 10148'),
                        start + '\n' + warning.replace('542 1637', '999 1637'),
                        start + '\n' + warning.replace(' W Binder', ' E Binder'),
                        start + '\n' + warning.replace('REGISTER_STATS_PULL_ATOM', 'CAMERA')):
            with self.subTest(invalid=invalid), self.assertRaises(AssertionError):
                self.validator.validate(self.log, invalid, self.metrics, lambda _: True)

    def test_pass_skip_and_runner_error_are_not_expected_failure(self):
        for code in ('0', '-1', '-3', '-4'):
            with self.subTest(code=code), self.assertRaises(AssertionError):
                self.validator.validate(self.log.replace('STATUS_CODE: -2', 'STATUS_CODE: ' + code), '', self.metrics, lambda _: True)


class RadarComparisonSummaryContractTest(unittest.TestCase):
    def setUp(self):
        self.module = load('radar_comparison_summary', 'summarize-radar-comparison.py')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.good = {'schemaVersion': 1, 'passed': True, 'status': 'passed', 'failures': [],
                     'captures': [{'status': 'passed', 'failures': []} for _ in range(6)]}
        for leg in ('baseline', 'candidate'):
            self.write(leg, self.good)

    def write(self, leg, data):
        path = self.root / leg / 'continuity/continuity-analysis.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))

    def test_complete_pair_can_pass(self):
        self.assertTrue(self.module.summarize(self.root, 3, 1)['passed'])

    def test_missing_analysis_is_explicit_inconclusive_without_traceback(self):
        (self.root / 'baseline/continuity/continuity-analysis.json').unlink()
        result = self.module.summarize(self.root, 3, 1)
        self.assertFalse(result['passed'])
        self.assertEqual(result['status'], 'inconclusive')
        self.assertEqual(result['failures'][0]['code'], 'missing_or_invalid_evidence')
        self.assertTrue((self.root / 'comparison.json').is_file())

    def test_missing_ffmpeg_setup_report_cannot_pass(self):
        self.write('candidate', {'schemaVersion': 1, 'passed': False, 'status': 'inconclusive', 'captures': [],
                                'failures': [{'code': 'setup_failure', 'message': 'Missing ffmpeg'}]})
        result = self.module.summarize(self.root, 3, 1)
        self.assertFalse(result['passed'])
        self.assertIn('Missing ffmpeg', result['failures'][0]['message'])

    def test_known_visual_baseline_failure_is_retained(self):
        baseline = copy.deepcopy(self.good)
        baseline.update(passed=False, status='failed')
        baseline['captures'][0].update(status='failed', failures=[{'code': 'black_native_view', 'status': 'failed'}])
        self.write('baseline', baseline)
        self.assertTrue(self.module.summarize(self.root, 3, 1)['passed'])

    def test_low_cadence_is_not_expected_negative_control(self):
        baseline = copy.deepcopy(self.good)
        baseline['captures'][0].update(status='inconclusive', failures=[{'code': 'low_capture_cadence', 'status': 'inconclusive'}])
        self.write('baseline', baseline)
        self.assertFalse(self.module.summarize(self.root, 3, 1)['passed'])

    def test_shard_zero_requires_negative_proof(self):
        self.assertFalse(self.module.summarize(self.root, 3, 0)['passed'])

    def test_corrupt_json_and_schema_are_graceful_failures(self):
        path = self.root / 'candidate/continuity/continuity-analysis.json'
        path.write_text('{')
        self.assertFalse(self.module.summarize(self.root, 3, 1)['passed'])
        self.write('candidate', {'passed': True, 'captures': self.good['captures'], 'failures': []})
        self.assertFalse(self.module.summarize(self.root, 3, 1)['passed'])

if __name__ == '__main__':
    unittest.main()
