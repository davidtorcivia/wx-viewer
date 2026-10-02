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

SOURCE = Path(__file__).resolve().parents[2] / 'macrobenchmark/src/main/java/zone/disinfo/wx/macrobenchmark/NativeRadarRecording.kt'


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
