"""Exercise the actual bundled xterm renderer using the browser already installed on CI."""
from html import unescape
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get('TERMUIDE_CONSOLE_BROWSER_TEST') == '1', 'Enabled in GitHub CI')
class ConsoleRendererTest(unittest.TestCase):
    def test_history_input_and_hidden_renderer_with_real_xterm(self):
        chrome = shutil.which('google-chrome') or shutil.which('chromium') or shutil.which('chromium-browser')
        self.assertIsNotNone(chrome, 'CI must supply Chrome for renderer regression coverage')
        page = Path(__file__).with_name('renderer_harness.html').resolve().as_uri()
        with tempfile.TemporaryDirectory(prefix='termuide-renderer-') as profile:
            process = subprocess.run([chrome, '--headless', '--no-sandbox', '--disable-gpu',
                                      '--allow-file-access-from-files', '--user-data-dir=' + profile,
                                      '--virtual-time-budget=10000', '--dump-dom', page],
                                     capture_output=True, text=True, timeout=30)
        self.assertEqual(0, process.returncode, process.stderr)
        result = re.search(r'<pre id="result">(.*?)</pre>', process.stdout, re.S)
        self.assertIsNotNone(result, process.stdout[-3000:] + process.stderr[-1000:])
        content = unescape(result.group(1))
        self.assertNotEqual('RUNNING', content, 'Renderer failed to acknowledge output: ' + process.stderr[-1000:])
        report = json.loads(content)
        self.assertTrue(report['ok'], report)
        self.assertEqual(29, len(report['checks']), report)


if __name__ == '__main__':
    unittest.main()
