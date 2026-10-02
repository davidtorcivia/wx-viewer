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

if __name__ == '__main__':
    unittest.main()
