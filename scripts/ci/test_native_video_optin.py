from pathlib import Path
import os
import subprocess
import unittest

from test_contracts import coverage

ROOT = Path(__file__).resolve().parents[2]


class NativeVideoOptInContractTest(unittest.TestCase):
    def test_workflow_default_and_budget_keep_original_five_lanes_and_cache(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('native_video_evidence:\n        description:', text)
        self.assertIn('type: boolean\n        required: false\n        default: false', text)
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.native_video_evidence && 'true' || 'false'", text)
        self.assertIn("timeout-minutes: ${{ github.event_name == 'workflow_dispatch' && inputs.native_video_evidence && 45 || 30 }}", text)
        self.assertIn('lane: [debug, playback, layers-0, layers-1, layers-2]', text)
        self.assertIn('max-parallel: 5', text)
        self.assertIn('Restore latest compatible Gradle build state', text)
        self.assertIn('Build all packages once and lint', text)
        self.assertIn("if: env.WX_NATIVE_VIDEO_EVIDENCE == 'true' && startsWith(matrix.lane, 'layers-')", text)
        lines = text.splitlines()
        uploads = [i for i, line in enumerate(lines) if line.startswith('      - name: Save ') and line.endswith(' native video')]
        self.assertEqual(len(uploads), 20)
        for index in uploads:
            self.assertIn("if: always() && env.WX_NATIVE_VIDEO_EVIDENCE == 'true' && matrix.lane", lines[index + 1])

    def test_only_two_new_instrumentation_logs_are_conditional(self):
        phases = coverage.phases_contract
        for lane in coverage.LANES:
            normal, video = phases.required_logs(lane), phases.required_logs(lane, native_video=True)
            if lane.startswith('layers-'):
                self.assertEqual(len(normal), 3)
                self.assertEqual(set(video) - set(normal), {
                    'radar-layers-preview/continuity/font-1.0/instrumentation.log',
                    'radar-layers-preview/continuity/font-2.0/instrumentation.log'})
                self.assertEqual(normal.keys(), {key for key in video if '/continuity/' not in key})
            else:
                self.assertEqual(normal, video)
        self.assertFalse(phases.native_video_enabled('false'))
        self.assertTrue(phases.native_video_enabled('true'))
        with self.assertRaises(AssertionError): phases.native_video_enabled('1')

    def test_actual_shell_block_runs_only_optin_and_keeps_gate_failure_blocking(self):
        text = (ROOT / 'scripts/run-radar-layers-preview.sh').read_text()
        block = text[text.index('native_video=${WX_NATIVE_VIDEO_EVIDENCE:-false}'):text.index('# Exercise every layer/range')]
        harness = '''
suite_status=0; output=/tmp/disposable-unused; shard_count=3; shard_index=0
bash() { echo CAPTURE; return 1; }
python3() { echo GATE; return "${GATE_EXIT:-0}"; }
''' + block + '\nexit "$suite_status"\n'
        for enabled, gate_exit, expected, markers in [('false', '0', 0, ''), ('true', '0', 0, 'CAPTURE\nGATE\n'),
                                                     ('true', '1', 1, 'CAPTURE\nGATE\n'), ('bad', '0', 2, '')]:
            with self.subTest(enabled=enabled, gate_exit=gate_exit):
                env = dict(os.environ, WX_NATIVE_VIDEO_EVIDENCE=enabled, GATE_EXIT=gate_exit)
                result = subprocess.run(['bash', '-c', harness], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, expected)
                self.assertEqual(result.stdout, markers)
        # The explicitly requested standalone comparison path bypasses the optional
        # main-layer branch and still returns the raw strict detector result.
        standalone = text[text.index('if [[ "$mode" == continuity ]]'):text.index('remote=/sdcard/Android/media/zone.disinfo.wx.macrobenchmark/radar-layers-preview')]
        self.assertIn('bash scripts/run-radar-continuity-preview.sh', standalone)
        self.assertIn('exit $?', standalone)


if __name__ == '__main__':
    unittest.main()
