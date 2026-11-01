"""POSIX bridge regressions for GitHub CI; no Termux installation or Android emulator needed."""
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

RUNNER = Path(__file__).resolve().parents[2] / 'app/src/main/assets/console/runner.py'


@unittest.skipUnless(sys.platform.startswith('linux'), 'Requires Linux PTY/process groups')
class ConsoleRunnerTest(unittest.TestCase):
    def setUp(self):
        self.server = socket.socket()
        self.server.bind(('127.0.0.1', 0))
        self.server.listen(1)
        self.server.settimeout(5)
        self.runner = None
        self.peer = None

    def tearDown(self):
        if self.peer:
            self.peer.close()
        self.server.close()
        if self.runner:
            try:
                self.runner.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                self.runner.kill()
                self.runner.communicate()

    def start(self, code, reply=b'OK\n'):
        token = 'a' * 64
        self.runner = subprocess.Popen([sys.executable, '-I', '-S', '-u', str(RUNNER),
                                       str(self.server.getsockname()[1]), token,
                                       sys.executable, '-u', '-c', code], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.peer, _ = self.server.accept()
        self.peer.settimeout(5)
        self.assertEqual(b'TERMUIDE1 ' + token.encode() + b'\n', self.read(75))
        self.peer.sendall(reply)

    def read(self, size):
        result = bytearray()
        while len(result) < size:
            part = self.peer.recv(size - len(result))
            if not part:
                raise EOFError('Bridge disconnected')
            result.extend(part)
        return bytes(result)

    def receive(self):
        size = struct.unpack('!I', self.read(4))[0]
        self.assertTrue(1 <= size <= 32768)
        data = self.read(size)
        return data[:1], data[1:]

    def send(self, kind, data=b''):
        self.peer.sendall(struct.pack('!I', len(data) + 1) + kind + data)

    def until(self, marker):
        output = bytearray()
        while marker not in output:
            kind, data = self.receive()
            self.assertEqual(b'O', kind)
            output.extend(data)
        return output

    def finished(self):
        output = bytearray()
        while True:
            kind, data = self.receive()
            if kind == b'E':
                code = struct.unpack('!i', data)[0]
                _, errors = self.runner.communicate(timeout=5)
                self.assertEqual(0, self.runner.returncode, errors.decode())
                return code, output
            self.assertEqual(b'O', kind)
            output.extend(data)

    @staticmethod
    def alive(pid):
        try:
            # A killed descendant may await the host init process's zombie reaper.
            return Path('/proc/%d/stat' % pid).read_text().split(') ', 1)[1][0] != 'Z'
        except FileNotFoundError:
            return False

    def test_interactive_input_terminal_size_and_nonzero_exit(self):
        self.start("import os,sys; print('TTY',os.isatty(0),os.isatty(1)); value=input('value: '); print(repr(value),os.get_terminal_size()); sys.exit(37)")
        self.assertIn(b'TTY True True', self.until(b'value: '))
        self.send(b'R', struct.pack('!II', 19, 65))
        self.send(b'I', b'hello; $(literal)\n')
        code, output = self.finished()
        self.assertEqual(37, code)
        self.assertIn(b"'hello; $(literal)'", output)
        self.assertIn(b'columns=65, lines=19', output)

    def test_force_stop_kills_the_run_and_its_child_without_killing_a_neighbor(self):
        neighbor = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            self.start("import os,signal,subprocess,sys,time; signal.signal(signal.SIGINT,signal.SIG_IGN); child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)']); print('PIDS',os.getpid(),child.pid,flush=True); time.sleep(30)")
            output = self.until(b'PIDS')
            while b'\n' not in output:
                _, data = self.receive()
                output.extend(data)
            pids = [int(part) for part in output.decode().splitlines()[0].split()[1:]]
            self.send(b'C')
            self.send(b'K')
            code, _ = self.finished()
            self.assertEqual(137, code)
            deadline = time.monotonic() + 2
            while any(self.alive(pid) for pid in pids) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(all(not self.alive(pid) for pid in pids))
            self.assertIsNone(neighbor.poll())
        finally:
            neighbor.terminate()
            neighbor.wait(timeout=5)

    def test_disconnection_cleans_up_a_running_child(self):
        self.start("import os,time; print(os.getpid(),flush=True); time.sleep(30)")
        pid = int(self.until(b'\n').strip())
        self.peer.close()
        self.peer = None
        self.runner.communicate(timeout=5)
        self.assertFalse(self.alive(pid))

    def test_native_run_directory_is_removed_after_force_stop(self):
        self.start("import os,time; from pathlib import Path; root=Path(os.environ['TERMUIDE_RUN_DIR']); (root/'program').write_text('artifact'); print(root,flush=True); time.sleep(30)")
        folder = Path(self.until(b'\n').decode().strip())
        self.assertTrue((folder / 'program').is_file())
        self.send(b'K')
        code, _ = self.finished()
        self.assertEqual(137, code)
        self.assertFalse(folder.exists())

    def test_rejected_authentication_does_not_execute_the_program(self):
        with tempfile.TemporaryDirectory() as folder:
            marker = Path(folder) / 'executed'
            self.start('from pathlib import Path; Path(%r).touch()' % str(marker), reply=b'NO\n')
            self.runner.communicate(timeout=5)
            self.assertNotEqual(0, self.runner.returncode)
            self.assertFalse(marker.exists())

    def test_large_output_is_drained_before_the_exit_frame(self):
        self.start("import os; data=b'x'*8192; [os.write(1,data) for _ in range(80)]")
        code, output = self.finished()
        self.assertEqual(0, code)
        self.assertEqual(b'x' * (8192 * 80), output)

    def test_malformed_control_frame_terminates_the_owned_process(self):
        self.start("import os,time; print(os.getpid(),flush=True); time.sleep(30)")
        pid = int(self.until(b'\n').strip())
        self.send(b'R', struct.pack('!II', 0, 0))
        self.runner.communicate(timeout=5)
        self.assertNotEqual(0, self.runner.returncode)
        self.assertFalse(self.alive(pid))


if __name__ == '__main__':
    unittest.main()
