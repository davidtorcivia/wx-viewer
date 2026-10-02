"""Test shell argv/lifecycle plumbing only; mock encoder is not native-video evidence."""
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'macrobenchmark/src/main/java/zone/disinfo/wx/macrobenchmark/NativeRadarRecording.kt'


class NativeRecordingShellContractTest(unittest.TestCase):
    def test_runtime_exec_tokenization_cannot_use_inline_shell_quotes(self):
        # Runtime.exec(String) tokenizes whitespace; it does not honor shell grouping.
        result = subprocess.run("sh -c 'echo recorder-started & echo $!'".split(), capture_output=True, text=True)
        self.assertEqual(result.stdout, '')
        self.assertNotEqual(result.returncode, 0)
        source = SOURCE.read_text()
        self.assertNotIn('executeShellCommand("sh -c', source)
        self.assertIn('executeShellCommand("sh ${launchScript.absolutePath}")', source)
        self.assertIn('executeShellCommand("sh ${probeScript.absolutePath}")', source)
        self.assertIn('executeShellCommand("sh ${stopScript.absolutePath}")', source)

    def test_actual_generated_launch_returns_pid_and_owned_probe_stop_work(self):
        source = SOURCE.read_text()
        body = re.search(r'launchScript.writeText\("""\n(.*?)\n        """\.trimIndent\(\) \+ "\\n"\)', source, re.S).group(1)
        owns = re.search(r'val ownsProcess = "([^"\n]+)"', source).group(1)
        with tempfile.TemporaryDirectory(prefix='wx-recorder-test-') as folder:
            root = Path(folder); video = root / 'native.mp4'; log = root / 'encoder.log'
            encoder = root / 'screenrecord'
            encoder.write_text(f'#!{sys.executable}\nimport signal,sys,time\n'
                               'signal.signal(signal.SIGINT, lambda *_: sys.exit(0))\n'
                               'open(sys.argv[-1], "wb").write(b"MOCK ENCODER ONLY" * 128)\n'
                               'time.sleep(20)\n')
            encoder.chmod(0o700)
            body = textwrap.dedent(body).replace('${video.absolutePath}', str(video)).replace('${log.absolutePath}', str(log)).replace("${'$'}!", '$!')
            launch = root / 'launch.sh'; launch.write_text(body + '\n')
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ.get('PATH', ''))
            # Same argv shape as UiAutomation: no inline quotation/grouping assumptions.
            result = subprocess.run(f'sh {launch}'.split(), capture_output=True, text=True, timeout=3, env=env)
            self.assertTrue(result.stdout.strip().isdigit(), result)
            pid = int(result.stdout.strip())
            try:
                end = time.monotonic() + 2
                while not video.exists() and time.monotonic() < end:
                    time.sleep(.01)
                self.assertTrue(video.is_file(), log.read_text() if log.exists() else 'no log')
                condition = owns.replace('$pid', str(pid)).replace('${video.absolutePath}', str(video))
                probe = root / 'probe.sh'; probe.write_text(f'if {condition}; then echo running; fi\n')
                self.assertEqual(subprocess.run(f'sh {probe}'.split(), capture_output=True, text=True).stdout.strip(), 'running')
                # A matching PID with another output path must not be considered owned.
                probe.write_text(f'if {condition.replace(str(video), str(root / "other.mp4"))}; then echo running; fi\n')
                self.assertEqual(subprocess.run(f'sh {probe}'.split(), capture_output=True, text=True).stdout.strip(), '')
                stop = root / 'stop.sh'; stop.write_text(f'if {condition}; then kill -2 {pid}; fi\n')
                subprocess.run(f'sh {stop}'.split(), check=True)
                probe.write_text(f'if {condition}; then echo running; fi\n')
                end = time.monotonic() + 2
                while time.monotonic() < end:
                    if not subprocess.run(f'sh {probe}'.split(), capture_output=True, text=True).stdout.strip():
                        break
                    time.sleep(.01)
                self.assertEqual(subprocess.run(f'sh {probe}'.split(), capture_output=True, text=True).stdout.strip(), '')
            finally:
                try:
                    os.kill(pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass


class NativeRecordingProfileContractTest(unittest.TestCase):
    def test_actual_profile_block_preserves_dp_and_restores_on_exit_and_signal(self):
        import json
        script = (ROOT / 'scripts/run-radar-continuity-preview.sh').read_text()
        # Execute the real profile/restore block against a stateful disposable adb mock.
        # No Android or host display state changes are made by this host regression.
        block = script[script.index('original_night='):script.index('for font in 1.0 2.0; do')]
        mock = '''import json, os, sys
p = os.environ['WX_MOCK_DISPLAY']; d = json.load(open(p)); a = sys.argv[1:]
if a[:3] == ['shell','wm','size']:
 if len(a) == 3:
  print('Physical size: 1080x1920')
  if d['size'] is not None: print('Override size: ' + d['size'])
 else: d['size'] = None if a[3] == 'reset' else a[3]
elif a[:3] == ['shell','wm','density']:
 if len(a) == 3:
  print('Physical density: 420')
  if d['density'] is not None: print('Override density: ' + d['density'])
 else: d['density'] = None if a[3] == 'reset' else a[3]
elif a[:4] == ['shell','cmd','uimode','night']:
 if len(a) == 4: print('Night mode: ' + d['night'])
 else: d['night'] = a[4]
elif a[:5] == ['shell','settings','get','system','font_scale']: print(d['font'])
elif a[:5] == ['shell','settings','put','system','font_scale']: d['font'] = a[5]
elif a[:5] == ['shell','settings','delete','system','font_scale']: d['font'] = 'null'
elif a[:3] != ['shell','am','force-stop']: raise AssertionError(a)
json.dump(d, open(p,'w'))
'''
        for ending, code in (('exit 0', 0), ('fail_setup deliberate-test-failure', 2), ('kill -TERM $$', 143)):
            for override in (False, True):
                with self.subTest(ending=ending, override=override), tempfile.TemporaryDirectory() as folder:
                    root = Path(folder); state = root / 'state.json'; adb = root / 'adb'
                    initial = {'size': '1080x1920' if override else None,
                               'density': '420' if override else None, 'night': 'no', 'font': '1.15'}
                    state.write_text(json.dumps(initial)); adb.write_text(f'#!{sys.executable}\n' + mock); adb.chmod(0o700)
                    test = root / 'profile.sh'
                    test.write_text('set -euo pipefail\nfail_setup() { exit 2; }\n' + block +
                                    '\nprintf "PROFILE=%s:%s:%s\\n" "$capture_width" "$capture_height" "$capture_density"\n' + ending + '\n')
                    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ.get('PATH', ''), WX_MOCK_DISPLAY=str(state))
                    result = subprocess.run(['bash', str(test)], capture_output=True, text=True, env=env, timeout=10)
                    self.assertEqual(result.returncode, code, result.stderr)
                    self.assertIn('PROFILE=540:960:210', result.stdout)
                    self.assertEqual(json.loads(state.read_text()), initial)

    def test_measured_action_windows_fit_bounded_180_second_recorder(self):
        source = SOURCE.read_text()
        self.assertIn('--time-limit 180', source)
        self.assertIn('.put("maximumRecordingSeconds", 180)', source)
        self.assertIn('.put("displayDensityDpi", densityDpi)', source)
        self.assertIn('.put("originalDisplaySize",', source)
