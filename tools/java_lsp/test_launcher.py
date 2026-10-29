import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="java tools '")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        (self.bundle / "bin").mkdir(parents=True)
        (self.bundle / "lib").mkdir()
        (self.bundle / "lib/java-language-server.jar").touch()
        shutil.copyfile(Path(__file__).with_name("termuide-java-lsp"), self.bundle / "bin/termuide-java-lsp")
        self.fake = self.root / "jdk/bin"
        self.fake.mkdir(parents=True)
        java = self.fake / "java"
        java.write_text('''#!/bin/sh
case "$1" in
  -XshowSettings:properties) printf ' java.specification.version = %s\\n' "$TEST_VERSION" >&2; exit 0;;
  --describe-module) exit "${TEST_COMPILER_EXIT:-0}";;
esac
printf '%s\\0' "$@" > "$TEST_ARGUMENTS"
[ -z "${JAVA_TOOL_OPTIONS-}${JDK_JAVA_OPTIONS-}${_JAVA_OPTIONS-}${CLASSPATH-}" ]
''')
        java.chmod(0o755)

    def run_launcher(self, *args, version="25", compiler="0", prefix=None):
        environment = dict(os.environ, PATH=str(self.fake) + os.pathsep + os.environ["PATH"],
                           TEST_VERSION=version, TEST_COMPILER_EXIT=compiler,
                           TEST_ARGUMENTS=str(self.root / "arguments"),
                           JAVA_TOOL_OPTIONS="unwanted", JDK_JAVA_OPTIONS="unwanted",
                           _JAVA_OPTIONS="unwanted", CLASSPATH="workspace")
        environment["PREFIX"] = str(prefix or self.root / "no-termux-prefix")
        return subprocess.run(["bash", str(self.bundle / "bin/termuide-java-lsp"), *args],
                              capture_output=True, text=True, env=environment, timeout=10)

    def test_preflight_requires_pinned_jdk_and_compiler_without_starting_server(self):
        self.assertEqual(0, self.run_launcher("--check").returncode)
        self.assertFalse((self.root / "arguments").exists())
        for version in ["21", "26"]:
            result = self.run_launcher("--check", version=version)
            self.assertEqual(127, result.returncode)
            self.assertIn("exactly JDK 25", result.stderr)
        self.assertEqual(127, self.run_launcher("--check", compiler="1").returncode)

    def test_launch_preserves_classpath_spaces_and_limits_heap(self):
        self.assertEqual(0, self.run_launcher().returncode)
        args = (self.root / "arguments").read_bytes().split(b"\0")[:-1]
        self.assertIn(b"-Xmx256m", args)
        self.assertEqual(str(self.bundle / "lib/*").encode(), args[args.index(b"-cp") + 1])
        self.assertEqual(b"org.javacs.Main", args[-1])

    def test_missing_jars_and_unknown_arguments_are_rejected(self):
        self.assertEqual(64, self.run_launcher("--unknown").returncode)
        (self.bundle / "lib/java-language-server.jar").unlink()
        self.assertEqual(127, self.run_launcher("--check").returncode)

    def test_installed_termux_jdk25_does_not_require_changing_execution_java(self):
        prefix = self.root / "termux"
        installed = prefix / "lib/jvm/java-25-openjdk/bin/java"
        installed.parent.mkdir(parents=True)
        installed.write_text('#!/bin/sh\nTEST_VERSION=25 exec "' + str(self.fake / "java") + '" "$@"\n')
        installed.chmod(0o755)
        self.assertEqual(0, self.run_launcher("--check", version="21", prefix=prefix).returncode)


if __name__ == "__main__":
    unittest.main()
