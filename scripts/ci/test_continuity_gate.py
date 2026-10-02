import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_contracts import continuity_report, acquisition_limited, coverage

gate = coverage.continuity_gate


class ContinuityAcquisitionGateTest(unittest.TestCase):
    def test_exact_whitelist_only_is_nonblocking_without_claiming_continuity(self):
        self.assertEqual(gate.ACQUISITION_ONLY, {'low_capture_cadence', 'irregular_capture_cadence', 'capture_gap'})
        for code in gate.ACQUISITION_ONLY:
            with self.subTest(code=code):
                report = acquisition_limited(continuity_report(1), code)
                original = copy.deepcopy(report)
                result = gate.validate(report, 0, 1, 1, 3, 1)
                self.assertTrue(result['gatePassed'])
                self.assertFalse(result['continuityProven'])
                self.assertEqual(result['status'], 'acquisition-limited')
                self.assertEqual(result['analyzerExitCode'], 1)
                self.assertEqual(report, original)

    def test_pixel_correctness_and_every_unknown_inconclusive_remain_blocking(self):
        for code in ('black_frame', 'flat_frame', 'frozen_native_playback', 'map_detail_loss',
                     'opaque_field_coverage_loss', 'play_stamps', 'truncated_capture',
                     'scaled_video', 'capture_timing_mismatch', 'setup_failure',
                     'unreadable_evidence', 'future_unknown_code'):
            for status in ('failed', 'inconclusive'):
                with self.subTest(code=code, status=status):
                    report = acquisition_limited(continuity_report(1))
                    report['captures'][-1].update(status=status, passed=False,
                                                  failures=[{'code': code, 'status': status}])
                    with self.assertRaises(AssertionError): gate.validate(report, 0, 1, 1, 3, 1)

    def test_whitelisted_code_with_failed_status_is_not_waived(self):
        report = acquisition_limited(continuity_report(1))
        report['captures'][0]['failures'][0]['status'] = 'failed'
        with self.assertRaises(AssertionError): gate.validate(report, 0, 1, 1, 3, 1)

    def test_raw_capture_analysis_and_diagnostic_errors_remain_blocking(self):
        report = acquisition_limited(continuity_report(1))
        for statuses in ((1, 1, 1), (2, 1, 1), (0, 2, 1), (0, 0, 1), (0, 1, 0)):
            with self.subTest(statuses=statuses), self.assertRaises(AssertionError):
                gate.validate(report, *statuses, 3, 1)

    def test_malformed_failures_and_nonfinite_or_boolean_counts_are_blocking(self):
        for value in (None, {}, '', False):
            report = acquisition_limited(continuity_report(1)); report['captures'][-1]['failures'] = value
            with self.subTest(value=value), self.assertRaises(AssertionError):
                gate.validate(report, 0, 1, 1, 3, 1)
        for value in (float('inf'), float('nan'), True, 3.0, '3'):
            report = acquisition_limited(continuity_report(1)); report['captures'][0]['summary']['completedPlays'] = value
            with self.subTest(value=value), self.assertRaises(AssertionError):
                gate.validate(report, 0, 1, 1, 3, 1)
        report = acquisition_limited(continuity_report(1)); report['captures'][0]['fontScale'] = True
        with self.assertRaises(AssertionError): gate.validate(report, 0, 1, 1, 3, 1)

    def test_inconsistent_missing_or_weakened_evidence_remains_blocking(self):
        mutations = [lambda r: r['captures'].pop(),
                     lambda r: r['captures'].append(r['captures'][0]),
                     lambda r: r.update(status='passed', passed=True),
                     lambda r: r.update(failures=[{'code': 'capture_gap', 'status': 'inconclusive'}]),
                     lambda r: r['captures'][0].update(failures=[]),
                     lambda r: r['thresholds'].update(minimumEffectiveFps=10),
                     lambda r: r['captures'][0]['summary'].update(completedScrubs=3),
                     lambda r: r['captures'][0]['summary'].update(decodedFrames=0),
                     lambda r: r['captures'][0]['coverage'].update(everyEncodedFrame=False),
                     lambda r: r['captures'][0].update(videoSha256=''),
                     lambda r: r.update(decodedFrames=1),
                     lambda r: r.update(shardIndex=0)]
        for mutation in mutations:
            report = acquisition_limited(continuity_report(1)); mutation(report)
            with self.subTest(report=report), self.assertRaises(AssertionError):
                gate.validate(report, 0, 1, 1, 3, 1)

    def test_cli_missing_files_and_stale_runner_exit_are_blocking(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); report = root / 'report.json'; output = root / 'gate.json'
            report.write_text(json.dumps(acquisition_limited(continuity_report(1))))
            acquisition, analysis, diagnostic = [root / name for name in ('acquisition', 'analysis', 'diagnostic')]
            command = [sys.executable, str(Path(__file__).with_name('check-radar-continuity-gate.py')),
                       str(report), '--acquisition-status', str(acquisition), '--analysis-status', str(analysis),
                       '--diagnostic-status', str(diagnostic), '--runner-exit', '2',
                       '--shard-count', '3', '--shard-index', '1', '--output', str(output)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)
            self.assertFalse(json.loads(output.read_text())['gatePassed'])
            acquisition.write_text('0'); analysis.write_text('1'); diagnostic.write_text('1')
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)
            self.assertIn('runner exit', json.loads(output.read_text())['reason'])

    def test_real_runner_reset_prevents_stale_report_after_analyzer_crash(self):
        script = (Path(__file__).parents[1] / 'run-radar-continuity-preview.sh').read_text()
        reset = script[script.index('mkdir -p "$output"'):script.index('fail_setup()')]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / 'continuity-analysis.json'
            report.write_text(json.dumps(acquisition_limited(continuity_report(1))))
            (root / 'continuity-gate.json').write_text('{"gatePassed":true}')
            subprocess.run(['bash', '-c', 'output=$1\n' + reset, 'reset', directory], check=True)
            self.assertFalse(report.exists())
            self.assertFalse((root / 'continuity-gate.json').exists())
            # A current analyzer that exits 1 without writing output cannot reuse the
            # previous acquisition-only report, even after capture itself succeeded.
            for name, value in (('acquisition', 0), ('analysis', 1), ('', 1)):
                suffix = '-' + name if name else ''
                (root / f'continuity{suffix}-status.txt').write_text(str(value))
            command = [sys.executable, str(Path(__file__).with_name('check-radar-continuity-gate.py')),
                       str(report), '--acquisition-status', str(root / 'continuity-acquisition-status.txt'),
                       '--analysis-status', str(root / 'continuity-analysis-status.txt'),
                       '--diagnostic-status', str(root / 'continuity-status.txt'), '--runner-exit', '1',
                       '--shard-count', '3', '--shard-index', '1', '--output', str(root / 'continuity-gate.json')]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 1)
            self.assertFalse(json.loads((root / 'continuity-gate.json').read_text())['gatePassed'])


if __name__ == '__main__':
    unittest.main()
