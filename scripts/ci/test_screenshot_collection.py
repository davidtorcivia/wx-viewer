"""Run the actual screenshot predicates under shell pipefail with large inventories."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path('app/build/outputs/connected_android_test_additional_output/full')
SCRIPTS = ('scripts/ci/run-debug-tests.sh', 'scripts/run-android-e2e.sh')


class ScreenshotCollectionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def probe(self, script, shell='bash'):
        # Execute the exact production if-condition, not a duplicate test implementation.
        lines = (ROOT / script).read_text().splitlines()
        condition, = [line for line in lines if line.startswith('if ') and 'find ' in line and "'*.png'" in line]
        flags = 'set -u\n' + ('set -o pipefail\n' if shell == 'bash' else '')
        source = (flags + 'mode=full\nWX_REQUIRE_SCREENSHOTS=1\n'
                  + f'additional={OUTPUT}\n' + condition
                  + '\nprintf missing\nelse\nprintf present\nfi\n')
        return subprocess.run([shell, '-c', source], cwd=self.root, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout

    def test_thousands_of_long_png_paths_do_not_create_sigpipe_false_negative(self):
        output = self.root / OUTPUT / 'native compositor frames'
        output.mkdir(parents=True)
        for index in range(4096):
            (output / (f'{index:04d}-' + 'weather-frame-' * 12 + '.png')).touch()
        for script in SCRIPTS:
            with self.subTest(script=script):
                self.assertEqual(self.probe(script), 'present')
        self.assertEqual(self.probe(SCRIPTS[1], shell='sh'), 'present')

    def test_missing_or_empty_or_non_png_output_fails_closed(self):
        for state in ('missing', 'empty', 'non-png'):
            if state != 'missing':
                (self.root / OUTPUT).mkdir(parents=True, exist_ok=True)
            if state == 'non-png':
                (self.root / OUTPUT / 'metrics.json').write_text('{}')
                # A directory named .png is not a captured image.
                (self.root / OUTPUT / 'not-an-image.png').mkdir()
            for script in SCRIPTS:
                with self.subTest(script=script, state=state):
                    self.assertEqual(self.probe(script), 'missing')
            self.assertEqual(self.probe(SCRIPTS[1], shell='sh'), 'missing')


if __name__ == '__main__':
    unittest.main()
