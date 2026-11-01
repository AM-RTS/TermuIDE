import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[2] / 'app/src/main/assets'
spec = importlib.util.spec_from_file_location('installer', ASSETS / 'install-java-tools.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
REVISION = 'a' * 40


def archive(revision=REVISION, extra=None):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w:gz') as bundle:
        for name, data in {
            'termuide-java-lsp/SOURCE.txt': f'Source: https://github.com/AM-RTS/java-language-server/tree/{revision}\n'.encode(),
            'termuide-java-lsp/bin/termuide-java-lsp': b'#!/bin/sh\nexit 0\n',
            'termuide-java-lsp/lib/java-language-server.jar': b'fixture',
        }.items():
            item = tarfile.TarInfo(name); item.size = len(data)
            bundle.addfile(item, io.BytesIO(data))
        if extra is not None:
            bundle.addfile(extra, io.BytesIO(b'x' * extra.size))
    return output.getvalue()


class JavaInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="installer space '")
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.prefix = self.home / 'prefix'
        (self.prefix / 'bin').mkdir(parents=True)
        self.jdk = self.prefix / 'lib/jvm/java-25-openjdk/bin/java'
        self.jdk.parent.mkdir(parents=True); self.jdk.touch()
        self.old = self.home / 'old'; self.old.write_text('old server')
        self.command = self.prefix / 'bin/termuide-java-lsp'
        self.command.symlink_to(self.old)

    def run_install(self, data, digest=None, run_error=False, needs_jdk=False):
        release = [{'tag_name': 'ci-fixture', 'prerelease': True, 'assets': [{
            'name': 'termuide-java-lsp.tar.gz', 'browser_download_url': installer.DOWNLOAD_PREFIX + 'ci-fixture/tools.tar.gz',
            'digest': 'sha256:' + (digest or hashlib.sha256(data).hexdigest())}]}]
        def fetch(url, path, limit):
            path.write_bytes(json.dumps(release).encode() if url == installer.RELEASES else data)
        with patch.object(installer, 'fetch', side_effect=fetch), patch.object(installer.subprocess, 'run',
                side_effect=RuntimeError('preflight failed') if run_error else None) as run:
            installer.install(REVISION, self.home, self.prefix)
            self.assertEqual(2 if needs_jdk else 1, run.call_count)
            if needs_jdk:
                self.assertEqual(['apt-get', 'install', '--no-install-recommends', 'openjdk-25'], run.call_args_list[0].args[0])
            self.assertEqual('--check', run.call_args_list[-1].args[0][-1])

    def test_verified_prerelease_atomically_replaces_link_and_keeps_old_folder(self):
        self.run_install(archive())
        self.assertNotEqual(self.old, self.command.resolve())
        self.assertTrue(self.old.is_file())
        self.assertTrue(os.access(self.command, os.X_OK))

    def test_checksum_and_revision_mismatch_preserve_existing_tools(self):
        for data, digest in [(archive(), '0' * 64), (archive('b' * 40), None)]:
            with self.subTest(digest=digest), self.assertRaises(ValueError):
                self.run_install(data, digest)
            self.assertEqual(self.old, self.command.resolve())
            self.assertFalse(list((self.home / '.local/share').glob('termuide-java-tools-*')))

    def test_failed_preflight_does_not_publish_or_leave_partial_install(self):
        with self.assertRaises(RuntimeError): self.run_install(archive(), run_error=True)
        self.assertEqual(self.old, self.command.resolve())
        self.assertFalse(list((self.home / '.local/share').glob('termuide-java-tools-*')))

    def test_jdk_is_requested_only_after_a_matching_verified_archive_is_available(self):
        self.jdk.unlink()
        self.run_install(archive(), needs_jdk=True)

    def test_rejects_traversal_symlinks_and_duplicate_entries(self):
        link = tarfile.TarInfo('termuide-java-lsp/link'); link.type = tarfile.SYMTYPE; link.linkname = '/etc'
        for entry in [tarfile.TarInfo('../outside'), link, tarfile.TarInfo('termuide-java-lsp/SOURCE.txt')]:
            with self.subTest(name=entry.name), self.assertRaises(ValueError): self.run_install(archive(extra=entry))
            self.assertEqual(self.old, self.command.resolve())
        self.assertFalse((self.home.parent / 'outside').exists())

    def test_invalid_revision_never_contacts_network(self):
        with patch.object(installer, 'fetch') as fetch, self.assertRaises(ValueError):
            installer.install('master', self.home, self.prefix)
        fetch.assert_not_called()


class SetupScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="setup space '")
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.prefix = self.home / 'usr'; self.bin = self.prefix / 'bin'; self.bin.mkdir(parents=True)
        self.log = self.home / 'commands'
        for name in ['mkdir', 'flock', 'readlink', 'grep']:
            (self.bin / name).symlink_to(shutil.which(name))
        self.fake('apt-get', 'printf "apt %s\\n" "$*" >> "$TEST_LOG"\n')

    def fake(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body); path.chmod(0o755)
        return path

    def run_setup(self, server, environment=''):
        return subprocess.run(['bash', str(ASSETS / 'language-tools.sh'), server, str(environment), REVISION, 'pass'],
            env=dict(os.environ, HOME=str(self.home), PREFIX=str(self.prefix), TEST_LOG=str(self.log)),
            capture_output=True, text=True, timeout=10)

    def test_native_installs_existing_toolchain_packages(self):
        self.fake('clangd', 'exit 0\n')
        result = self.run_setup('clangd')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('--reinstall clang gdb make', self.log.read_text())
        self.assertNotIn('--no-upgrade', self.log.read_text())

    def test_missing_selected_python_does_not_install_global_packages(self):
        result = self.run_setup('pylsp', self.home / 'missing environment')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('selected Python environment is missing', result.stderr)
        self.assertFalse(self.log.exists())

    def test_selected_python_receives_literal_arguments_and_not_global_install(self):
        self.fake('clang', 'exit 0\n')
        environment = self.home / "venv with ' quote"; (environment / 'bin').mkdir(parents=True)
        python = environment / 'bin/python'; python.write_text('#!/bin/sh\nprintf "%s\\n" "$@" >> "$TEST_LOG"\n'); python.chmod(0o755)
        result = self.run_setup('pylsp', environment)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('apt ', self.log.read_text())
        self.assertIn('python-lsp-server==1.15.0', self.log.read_text())
        self.assertIn('-I', self.log.read_text())
        self.assertIn('--force-reinstall', self.log.read_text())

    def test_default_python_bootstraps_compiler_for_android_pip_dependencies(self):
        self.fake('python', 'exit 0\n')
        result = self.run_setup('pylsp')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('python python-pip', self.log.read_text())
        self.assertIn('--no-upgrade clang', self.log.read_text())

    def test_old_java_alternatives_fail_before_package_mutation(self):
        alternatives = self.prefix / 'var/lib/dpkg/alternatives'; alternatives.mkdir(parents=True)
        (alternatives / 'java-profile').write_text('old master')
        result = self.run_setup('java')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('separate alternatives', result.stderr)
        self.assertFalse(self.log.exists())

    def test_failure_restores_existing_java_selection(self):
        old = self.fake('java', 'exit 0\n')
        self.fake('update-alternatives', 'if [ "$1" = --query ]; then printf "Alternative: %s\\n" "' + str(old) + '"; else printf "restore %s\\n" "$*" >> "$TEST_LOG"; fi\n')
        self.fake('python', 'exit 12\n')
        result = self.run_setup('java')
        self.assertEqual(12, result.returncode)
        self.assertIn('restore --set java', self.log.read_text())

    def test_python_setup_ignores_pip_destinations_and_installs_into_selected_environment(self):
        self.fake('clang', 'exit 0\n')
        environment = self.home / "real venv with ' quote"
        subprocess.run([sys.executable, '-m', 'venv', str(environment)], check=True)
        python = environment / 'bin/python'
        real_python = environment / 'bin/python-real'
        python.rename(real_python)
        wheel = self.home / 'python_lsp_server-1.15.0-py3-none-any.whl'
        info = 'python_lsp_server-1.15.0.dist-info/'
        files = {'pylsp/__init__.py': '__version__ = "fixture"\n',
                 info + 'METADATA': 'Metadata-Version: 2.1\nName: python-lsp-server\nVersion: 1.15.0\n',
                 info + 'WHEEL': 'Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: py3-none-any\n'}
        files[info + 'RECORD'] = ''.join(name + ',,\n' for name in [*files, info + 'RECORD'])
        with zipfile.ZipFile(wheel, 'w') as bundle:
            for name, contents in files.items(): bundle.writestr(name, contents)
        # Use real pip with a local wheel, so this regression needs no network.
        python.write_text(f'''#!{sys.executable}
import os, subprocess, sys
args = sys.argv[1:]
if '-m' in args and args[args.index('-m') + 1] == 'pip':
    assert not any(name in os.environ for name in ['PIP_TARGET', 'PIP_PREFIX', 'PIP_ROOT', 'PIP_USER'])
    assert os.environ.get('PIP_CONFIG_FILE') == '/dev/null'
    args = [{str(wheel)!r} if arg == 'python-lsp-server==1.15.0' else arg for arg in args]
    args += ['--no-index', '--no-deps']
raise SystemExit(subprocess.run([{str(real_python)!r}, *args], stdin=sys.stdin).returncode)
''')
        python.chmod(0o755)
        outside = self.home / 'wrong-destination'
        config = f'[global]\ntarget = {outside}\n'
        (environment / 'pip.conf').write_text(config)
        user_config = self.home / '.config/pip/pip.conf'
        user_config.parent.mkdir(parents=True); user_config.write_text(config)
        poison = self.home / 'custom-pip.conf'; poison.write_text(config)
        with patch.dict(os.environ, {'PIP_TARGET': str(outside), 'PIP_PREFIX': str(outside),
                                    'PIP_ROOT': str(outside), 'PIP_USER': '1', 'PIP_CONFIG_FILE': str(poison)}):
            result = self.run_setup('pylsp', environment)
        self.assertEqual(0, result.returncode, result.stderr)
        location = subprocess.run([str(real_python), '-I', '-c', 'import pylsp; print(pylsp.__file__)'],
                                  capture_output=True, text=True, check=True).stdout.strip()
        self.assertTrue(Path(location).is_relative_to(environment))
        self.assertFalse(outside.exists())

    def test_python_origin_verification_rejects_an_existing_external_module(self):
        self.fake('clang', 'exit 0\n')
        environment = self.home / 'venv'; (environment / 'bin').mkdir(parents=True)
        foreign = self.home / 'outside.py'; foreign.write_text('')
        python = environment / 'bin/python'
        python.write_text(f'''#!{sys.executable}
import sys, types
if sys.argv[1:] == ['-I', '-']:
    sys.modules['pylsp'] = types.SimpleNamespace(__file__={str(foreign)!r})
    exec(sys.stdin.read())
''')
        python.chmod(0o755)
        result = self.run_setup('pylsp', environment)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('outside the selected interpreter', result.stderr)


if __name__ == '__main__': unittest.main()
