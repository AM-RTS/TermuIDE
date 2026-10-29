package amrts.termuide

import cn.rbc.codeeditor.lang.LanguagePython
import cn.rbc.codeeditor.util.CharSeqReader
import cn.rbc.codeeditor.util.Lexer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScriptRuntimeTest {
    private val directory = Files.createTempDirectory("runtime scripts '").toFile()
    private val bash = File("C:/Program Files/Git/bin/bash.exe").takeIf { it.isFile } ?: File("/bin/bash")
    private val config = Project.Config(null, "build", "", "", "", 0, emptyList())
    private data class Result(val exit: Int, val stdout: String, val stderr: String)

    @After fun clean() { directory.deleteRecursively() }

    private fun request(name: String, text: String = ""): RunCommands.Request {
        val source = File(directory, name).apply { writeText(text) }
        return RunCommands.Request(RunCommands.Action.RUN, source.path, Languages.fileType(name)!!,
            File(directory, "unrelated-browser").path, config, "-Wall -lm", emptyList())
    }

    private fun bashPath(file: File): String = file.absolutePath.replace('\\', '/').let {
        if (it.length > 1 && it[1] == ':') "/${it[0].lowercaseChar()}${it.substring(2)}" else it
    }

    // Set positional parameters inside the fixture: Windows ProcessBuilder also reinterprets embedded argument quotes.
    private fun execute(plan: RunCommands.Plan, stdin: String = "", noPath: Boolean = false, environment: Map<String, String> = emptyMap()): Result {
        assertTrue("Host bash is required", bash.isFile)
        val positional = plan.arguments.drop(3).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val script = File.createTempFile("wrapper", ".sh", directory).apply {
            writeText((if (noPath) "PATH=/no-installed-runtimes\n" else "") + "set -- $positional\n" + plan.arguments[1])
        }
        val process = ProcessBuilder(bash.path, bashPath(script)).directory(if (plan.background) directory else File(plan.directory))
            .apply { environment().putAll(environment) }.start()
        process.outputStream.bufferedWriter().use { it.write(stdin) }
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Runtime regression timed out") }
        return Result(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
    }

    private fun fakeRuntime(text: String): String = File(directory, "fake-runtime").apply {
        writeText("#!/bin/bash\n$text\n")
        setExecutable(true)
    }.let(::bashPath)

    private fun hostJava(): String = File(System.getProperty("java.home"),
        if (File.separatorChar == '\\') "bin/java.exe" else "bin/java").also {
            assertTrue("Host JDK is required", it.isFile)
        }.let(::bashPath)

    private fun hostPython(): String {
        val python = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, if (File.separatorChar == '\\') "python.exe" else "python3") }.firstOrNull { it.isFile }
        assertNotNull("Host Python 3 is required", python)
        return bashPath(python!!)
    }

    @Test fun javaLanguageServerProbeChecksTheOptionalLauncherWithoutChangingExecutionRequirements() {
        val tools = File(directory, "tools").apply { mkdirs() }
        val launcher = File(tools, "termuide-java-lsp").apply {
            writeText("#!/bin/bash\n[ \"\$1\" = --check ] || exit 99\nprintf '%s' checked > \"${Utils.escape(bashPath(File(directory, "checked")))}\"\nexit \"\${TERMUIDE_FAKE_EXIT:-0}\"\n")
            setExecutable(true)
        }
        val environment = mapOf("PATH" to bashPath(tools) + ":" + System.getenv("PATH"))
        val plan = RunCommands.languageServerProbe("java", bashPath(directory), "")
        val ready = execute(plan, environment = environment)
        assertEquals(ready.stderr, 0, ready.exit)
        assertEquals(bashPath(launcher), ready.stdout.trim())
        assertEquals("checked", File(directory, "checked").readText())
        assertEquals(127, execute(plan, environment = environment + ("TERMUIDE_FAKE_EXIT" to "1")).exit)
        assertEquals(127, execute(plan, noPath = true).exit)
        assertFalse(RunCommands.probe(request("Main.java", "class Main {}" )).arguments[1].contains("termuide-java-lsp"))
    }

    @Test fun pythonProbeChecksVersionReadabilityAndMissingRuntimeWithoutRunningTheSource() {
        val env = File(directory, "environment with spaces").apply { resolve("bin").mkdirs() }
        val runtime = env.resolve("bin/python").apply { writeText("#!/bin/bash\nexit 0\n"); setExecutable(true) }.let(::bashPath)
        val req = request("unsafe.py", "raise RuntimeError('Do not run during a probe')").copy(pythonEnvironment = bashPath(env))
        val probe = RunCommands.probe(req, mapOf("python" to runtime))
        val ready = execute(probe)
        assertEquals(ready.stderr, 0, ready.exit)
        assertEquals("$runtime\n", ready.stdout)
        env.resolve("bin/python").writeText("#!/bin/bash\nexit 1\n")
        val oldPython = execute(probe)
        assertEquals(127, oldPython.exit)
        assertTrue(oldPython.stderr.contains("Python 3"))
        val missing = execute(RunCommands.probe(req.copy(pythonEnvironment = "")), noPath = true)
        assertEquals(127, missing.exit)
        assertTrue(missing.stderr.contains("Missing runtime: python"))
        File(req.path!!).delete()
        val unreadable = execute(probe)
        assertEquals(126, unreadable.exit)
        assertTrue(unreadable.stderr.contains("Source is not readable"))
    }

    @Test fun javaProbeRejectsOldLaunchersAndCompilerlessRuntimes() {
        val req = request("Main.java")
        val runtime = fakeRuntime("exit 1")
        val probe = RunCommands.probe(req, mapOf("java" to runtime))
        val oldJava = execute(probe)
        assertEquals(127, oldJava.exit)
        assertTrue(oldJava.stderr.contains("JDK 11"))
        fakeRuntime("if [ \"\$1\" = --describe-module ]; then exit 1; fi\nexit 0")
        val noCompiler = execute(probe)
        assertEquals(127, noCompiler.exit)
        assertTrue(noCompiler.stderr.contains("jdk.compiler"))
        fakeRuntime("printf 'version details\\n' >&2\nexit 0")
        val ready = execute(probe)
        assertEquals(ready.stderr, 0, ready.exit)
        assertEquals("$runtime\n", ready.stdout)
    }

    @Test fun pythonProbeDoesNotExecuteStartupHooksButProgramsKeepTheirEnvironment() {
        val req = request("main.py", "print('program ran')")
        val marker = File(directory, "startup-was-run")
        File(directory, "sitecustomize.py").writeText("open('startup-was-run', 'w').write('started')")
        val environment = mapOf("PYTHONPATH" to directory.path)
        val tools = mapOf("python" to hostPython())
        val probe = execute(RunCommands.probe(req, tools), environment = environment)
        assertEquals(probe.stderr, 0, probe.exit)
        assertFalse("Runtime discovery must not run project/user startup code", marker.exists())
        val program = execute(RunCommands.build(req, tools), environment = environment)
        assertEquals(program.stderr, 0, program.exit)
        assertTrue(program.stdout.contains("program ran"))
        assertTrue("Actual execution must retain the user's Python environment", marker.exists())
    }

    @Test fun javaReadsEditorUtf8SourcesEvenWhenTheRuntimeDefaultIsAscii() {
        val req = request("Unicode.java", "class Unicode { public static void main(String[] args) { System.out.println(\"é\".codePointAt(0)); } }")
        val program = execute(RunCommands.build(req, mapOf("java" to hostJava())),
            environment = mapOf("JAVA_TOOL_OPTIONS" to "-Dfile.encoding=US-ASCII"))
        assertEquals(program.stderr, 0, program.exit)
        assertTrue(program.stdout.contains("233"))
    }

    @Test fun realJavaSourceLaunchSupportsStdinRelativeFilesPackagesAndNonzeroExit() {
        File(directory, "data.txt").writeText("relative data")
        val req = request("Program '\$value;.java", """
            package demo;
            class Program {
                public static void main(String[] args) throws Exception {
                    System.out.println(new java.util.Scanner(System.in).nextLine());
                    System.out.println(java.nio.file.Files.readString(java.nio.file.Path.of("data.txt")));
                    System.exit(9);
                }
            }
        """.trimIndent())
        val tools = mapOf("java" to hostJava())
        val probe = execute(RunCommands.probe(req, tools))
        assertEquals(probe.stderr, 0, probe.exit)
        assertEquals("${tools.getValue("java")}\n", probe.stdout)
        val ran = execute(RunCommands.build(req, tools), "terminal input\n")
        assertEquals(ran.stderr, 9, ran.exit)
        assertTrue(ran.stdout.contains("terminal input"))
        assertTrue(ran.stdout.contains("relative data"))
        assertFalse(directory.listFiles()!!.any { it.extension == "class" })
    }

    @Test fun realPythonSupportsSiblingImportsStdinRelativeFilesAndNonzeroExit() {
        File(directory, "sibling.py").writeText("value = 'sibling import'")
        File(directory, "data.txt").writeText("relative data")
        val req = request("program '\$value;.py", """
            import sibling, sys
            print(sibling.value)
            print(input())
            print(open('data.txt').read())
            sys.exit(7)
        """.trimIndent())
        val tools = mapOf("python" to hostPython())
        val probe = execute(RunCommands.probe(req, tools))
        assertEquals(probe.stderr, 0, probe.exit)
        val ran = execute(RunCommands.build(req, tools), "terminal input\n")
        assertEquals(ran.stderr, 7, ran.exit)
        assertTrue(ran.stdout.contains("sibling import"))
        assertTrue(ran.stdout.contains("terminal input"))
        assertTrue(ran.stdout.contains("relative data"))
    }

    @Test fun syntaxErrorsReachTheTerminalWithAFailingExitCode() {
        val python = execute(RunCommands.build(request("broken.py", "if :"), mapOf("python" to hostPython())))
        assertNotEquals(0, python.exit)
        assertTrue(python.stderr.contains("SyntaxError"))
        val java = execute(RunCommands.build(request("Broken.java", "class Broken { invalid }"), mapOf("java" to hostJava())))
        assertNotEquals(0, java.exit)
        assertTrue(java.stderr.contains("error:"))
    }

    @Test fun fixedWrapperPreservesArgumentBoundariesWithoutInterpretingShellCharacters() {
        val req = request("literal.py")
        val runtime = fakeRuntime("printf '%s\\n' \"\$@\"\nexit 17")
        val plan = RunCommands.build(req, mapOf("python" to runtime))
        val argument = "quote\" '\$HOME `touch unexpected` ; argument"
        val ran = execute(plan.copy(arguments = plan.arguments + argument))
        assertEquals(ran.stderr, 17, ran.exit)
        assertEquals(listOf(req.path, argument), ran.stdout.trimEnd().lines())
        assertFalse(File(directory, "unexpected").exists())
    }

    @Test fun configuredPythonAndJavaReceiveLiteralArgumentsAndReadFromTheConfiguredDirectory() {
        val data = File(directory, "runtime data").apply { mkdir() }
        File(data, "data.txt").writeText("configured cwd")
        val arguments = listOf("", "two words", "\$HOME '\";$(touch ARG_INJECTION)")
        val configuration = RunConfiguration(arguments = arguments, workingDirectory = data.path)
        val python = request("configured.py", """
            import sys
            print(open('data.txt').read())
            for value in sys.argv[1:]: print('[' + value + ']')
            sys.exit(7)
        """.trimIndent()).copy(configuration = configuration)
        val java = request("Configured.java", """
            class Configured {
                public static void main(String[] args) throws Exception {
                    System.out.println(java.nio.file.Files.readString(java.nio.file.Path.of("data.txt")));
                    for (String value : args) System.out.println("[" + value + "]");
                    System.exit(9);
                }
            }
        """.trimIndent()).copy(configuration = configuration)
        for ((req, tools, exit) in listOf(Triple(python, mapOf("python" to hostPython()), 7), Triple(java, mapOf("java" to hostJava()), 9))) {
            val probe = execute(RunCommands.probe(req, tools))
            assertEquals(probe.stderr, 0, probe.exit)
            val ran = execute(RunCommands.build(req, tools))
            assertEquals(ran.stderr, exit, ran.exit)
            assertEquals(listOf("configured cwd") + arguments.map { "[$it]" }, ran.stdout.trimEnd().lines())
            assertFalse(File(data, "ARG_INJECTION").exists())
            assertFalse(File(directory, "ARG_INJECTION").exists())
        }
        val bad = python.copy(configuration = RunConfiguration(workingDirectory = File(directory, "missing").path))
        assertNotEquals(0, execute(RunCommands.probe(bad, mapOf("python" to hostPython()))).exit)
    }

    @Test fun nativeShellFlowCompilesInTheOriginalDirectoryAndLaunchesWithConfiguredCwdAndArguments() {
        val data = File(directory, "runtime data").apply { mkdir() }
        val stage = File(directory, "native stage").apply { mkdir() }
        val reportDirectory = if (File.separatorChar == '\\') "pwd -W" else "pwd"
        val program = File(directory, "program-template").apply {
            writeText("#!/bin/bash\nprintf '%s\\0' \"\$($reportDirectory)\" \"\$@\"\nexit 19\n")
        }
        val log = File(directory, "compiler-cwd")
        val compiler = fakeRuntime("""
            $reportDirectory > "${'$'}COMPILER_CWD"
            while [ "${'$'}#" -gt 0 ]; do
                if [ "${'$'}1" = -o ]; then shift; output="${'$'}1"; fi
                shift
            done
            cp -- "${'$'}PROGRAM_TEMPLATE" "${'$'}output" && chmod +x "${'$'}output"
        """.trimIndent())
        val arguments = listOf("", "two words", "\$HOME '\";$(touch NATIVE_INJECTION)")
        val configuration = RunConfiguration(arguments = arguments, workingDirectory = data.path)
        val environment = mapOf("TMPDIR" to bashPath(stage), "PROGRAM_TEMPLATE" to bashPath(program), "COMPILER_CWD" to bashPath(log))
        for ((name, tool) in listOf("native.c" to "clang", "native.cpp" to "clang++")) {
            val req = request(name).copy(directory = directory.path, configuration = configuration)
            val ran = execute(RunCommands.build(req, mapOf(tool to compiler)), environment = environment)
            assertEquals(ran.stderr, 19, ran.exit)
            assertEquals(directory.canonicalFile, File(log.readText().trim()).canonicalFile)
            val output = ran.stdout.split('\u0000').dropLast(1)
            assertEquals(data.canonicalFile, File(output.first()).canonicalFile)
            assertEquals(arguments, output.drop(1))
            assertFalse(File(data, "NATIVE_INJECTION").exists())
        }
        val output = File(directory, "build").apply { mkdir() }
        File(output, "native").apply { writeText(program.readText()); setExecutable(true) }
        val project = Project.Config(directory.path, "build", "unused-build", "unused-compile", "\$x/\$e", 0, emptyList())
        val req = request("native.cpp").copy(directory = directory.path, project = project, configuration = configuration)
        val ran = execute(RunCommands.build(req, emptyMap()), environment = environment)
        assertEquals(ran.stderr, 19, ran.exit)
        val values = ran.stdout.split('\u0000').dropLast(1)
        assertEquals(data.canonicalFile, File(values.first()).canonicalFile)
        assertEquals(arguments, values.drop(1))
        assertTrue(File(stage, "termuide/native").isFile)
        val legacyPositional = Project.Config(directory.path, "build", "unused-build", "unused-compile",
            "\"\$x/\$e\" \"\$@\"", 0, emptyList())
        val legacy = execute(RunCommands.build(req.copy(project = legacyPositional), emptyMap()), environment = environment)
        assertEquals(legacy.stderr, 19, legacy.exit)
        assertEquals(arguments, legacy.stdout.split('\u0000').drop(1).dropLast(1))
        val relativeCommand = Project.Config(directory.path, "build", "unused-build", "unused-compile",
            "./build/native", 0, emptyList())
        val relative = execute(RunCommands.build(req.copy(project = relativeCommand), emptyMap()), environment = environment)
        assertEquals(relative.stderr, 19, relative.exit)
        assertEquals(arguments, relative.stdout.split('\u0000').drop(1).dropLast(1))
    }

    @Test fun emptyProjectCommandCannotTurnProgramArgumentsIntoAnExecutable() {
        File(directory, "build").mkdir()
        val marker = File(directory, "argument-was-executed")
        val executable = fakeRuntime("printf executed > \"\$ARGUMENT_MARKER\"\nexit 0")
        for (command in listOf("", "   ", "\"\"")) {
            val project = Project.Config(directory.path, "build", "", "", command, 0, emptyList())
            val req = request("empty-command.c").copy(directory = directory.path, project = project,
                configuration = RunConfiguration(arguments = listOf(executable)))
            val ran = execute(RunCommands.build(req, emptyMap()), environment = mapOf("ARGUMENT_MARKER" to bashPath(marker)))
            assertNotEquals(0, ran.exit)
            assertTrue(ran.stderr, ran.stderr.contains("Project run command is empty"))
            assertFalse(marker.exists())
        }
    }

    @Test fun pythonHighlightingUsesCurrentLexerAndRetainsEveryCharacter() {
        val language = Languages.forType(EditFragment.TYPE_PYTHON).highlighting()
        assertTrue(language is LanguagePython)
        val text = "async def greet():\n    # comment\n    return '''multi\nline''' + f\"hello\"\n"
        val scanner = language.newLexer(CharSeqReader(text))
        val tokens = mutableListOf<Pair<Int, String>>()
        while (true) {
            val token = scanner.yylex()
            if (token == Lexer.EOF) break
            assertTrue(scanner.yylength() > 0)
            tokens += token to scanner.yytext()
        }
        assertEquals(text, tokens.joinToString("") { it.second })
        assertTrue(tokens.contains(Lexer.KEYWORD to "async"))
        assertTrue(tokens.contains(Lexer.COMMENT to "# comment"))
        assertTrue(tokens.contains(Lexer.STRING_LITERAL to "'''multi\nline'''"))
        assertTrue(tokens.contains(Lexer.STRING_LITERAL to "f\"hello\""))
        scanner.yyreset(CharSeqReader("await task"))
        assertEquals(Lexer.KEYWORD, scanner.yylex())
        val longString = "\"" + "a".repeat(100_000) + "\""
        scanner.yyreset(CharSeqReader(longString))
        assertEquals(Lexer.STRING_LITERAL, scanner.yylex())
        assertEquals(longString.length, scanner.yylength())
    }

    @Test fun pythonStringsWithEscapedTripleQuotesAndContinuedLinesRemainSingleTokens() {
        for (text in listOf("'''before \\''' still\ninside'''", "\"first\\\nsecond\"")) {
            val scanner = LanguagePython.getInstance().newLexer(CharSeqReader(text))
            assertEquals(Lexer.STRING_LITERAL, scanner.yylex())
            assertEquals(text, scanner.yytext())
            assertEquals(Lexer.EOF, scanner.yylex())
        }
    }

    @Test fun simultaneousEditorAnalysesCannotResetEachOthersScanners() {
        for (type in listOf(EditFragment.TYPE_JAVA, EditFragment.TYPE_C, EditFragment.TYPE_CPP,
            EditFragment.TYPE_JAVASCRIPT, EditFragment.TYPE_JSON, EditFragment.TYPE_PYTHON)) {
            val language = Languages.forType(type).highlighting()
            val first = language.newLexer(CharSeqReader("class First {}"))
            val second = language.newLexer(CharSeqReader("class Second {}"))
            assertNotSame("Each editor needs its own ${Languages.forType(type).id} scanner", first, second)
            fun text(scanner: Lexer): String = buildString {
                while (scanner.yylex() != Lexer.EOF) append(scanner.yytext())
            }
            assertEquals("class First {}", text(first))
            assertEquals("class Second {}", text(second))
            if (first is cn.rbc.codeeditor.lang.c.CppLexer) {
                assertEquals(type == EditFragment.TYPE_C || type == EditFragment.TYPE_CPP, first.trackPreprocessor)
                assertEquals(type == EditFragment.TYPE_JAVASCRIPT, first.backquoteString)
            }
        }
    }
    @Test fun selectedPythonEnvironmentNeverFallsBackAndUsesTheSamePackagesAsPylsp() {
        val missing = request("main.py").copy(pythonEnvironment = "/definitely-missing-termuide-venv")
        val result = execute(RunCommands.probe(missing))
        assertEquals(127, result.exit)
        assertTrue(result.stderr.contains("Selected Python environment"))
        val defaultProbe = RunCommands.probe(missing.copy(pythonEnvironment = ""), mapOf("python" to "/old-venv/bin/python"))
        assertEquals("", defaultProbe.arguments.last())
        val env = System.getenv("TERMUIDE_TEST_PYTHON_ENV")
        org.junit.Assume.assumeNotNull(env)
        val req = request("environment.py", "import termuide_env_marker, os; print(termuide_env_marker.env_specific_value); print(os.environ['VIRTUAL_ENV'])")
            .copy(pythonEnvironment = env!!)
        val probe = execute(RunCommands.probe(req))
        assertEquals(probe.stderr, 0, probe.exit)
        assertEquals("$env/bin/python\n", probe.stdout)
        val serverProbe = execute(RunCommands.languageServerProbe("pylsp", directory.path, env))
        assertEquals(serverProbe.stderr, 0, serverProbe.exit)
        assertEquals(probe.stdout, serverProbe.stdout)
        val ran = execute(RunCommands.build(req, mapOf("python" to probe.stdout.trim())), "x")
        assertEquals(ran.stderr, 0, ran.exit)
        assertTrue(ran.stdout.contains("termuide environment package"))
        assertTrue(ran.stdout.contains(env))
    }

    @Test fun projectCommandsInheritTheChosenEnvironmentWithoutChangingTheCommand() {
        val env = File(directory, "env with ' and $; spaces").apply { resolve("bin").mkdirs() }
        env.resolve("bin/python").apply { writeText("#!/bin/bash\nexit 0\n"); setExecutable(true) }
        val project = Project.Config(directory.path, "build", "printf '%s\\n' \"\$VIRTUAL_ENV\";command -v python", "", "", 0, emptyList())
        val req = request("main.c").copy(project = project, action = RunCommands.Action.BUILD, pythonEnvironment = bashPath(env))
        assertEquals(listOf("python"), RunCommands.requiredTools(req))
        val probe = execute(RunCommands.probe(req))
        assertEquals(probe.stderr, 0, probe.exit)
        val plan = RunCommands.build(req, mapOf("python" to probe.stdout.trim()))
        val ran = execute(plan, "x", environment = mapOf("PYTHONHOME" to "/invalid-python-home"))
        assertEquals(ran.stderr, 0, ran.exit)
        assertTrue(ran.stdout.contains(bashPath(env)))
        assertTrue(ran.stdout.contains(bashPath(env.resolve("bin/python"))))
    }

    private fun toolCommand(arguments: List<String>) = RunCommands.Plan("unused-by-host-fixture",
        listOf("-c", "\"\$@\"", "termuide-tool") + arguments, directory.path, true)

    @Test fun pythonToolingIgnoresWorkspaceAndPythonPathBeforeStartupHooksRun() {
        val env = System.getenv("TERMUIDE_TEST_PYTHON_ENV")
        org.junit.Assume.assumeNotNull(env)
        val marker = File(directory, "unsafe-import")
        val extra = File(directory, "python-path").apply { mkdirs() }
        for (folder in listOf(directory, extra)) for (module in listOf("pylsp.py", "sitecustomize.py", "usercustomize.py", "runpy.py"))
            File(folder, module).writeText("open(${org.json.JSONObject.quote(marker.path)}, 'w').write('unsafe')\nraise RuntimeError('Workspace code imported')\n")
        val poisoned = mapOf("PYTHONPATH" to extra.path + File.pathSeparator + directory.path, "PYTHONHOME" to "/invalid-python-home")
        for (selection in listOf(env!!, "")) {
            val probe = execute(RunCommands.languageServerProbe("pylsp", directory.path, selection), environment = poisoned)
            assertEquals(probe.stderr, 0, probe.exit)
            val lsp = Lsp(LanguageServers.Server.PYTHON, directory.path, "127.0.0.1", 48455, selection)
            lsp.configureRuntime(probe.stdout.trim())
            val launch = lsp.launchArguments().toList()
            val interpreter = launch.indexOf(probe.stdout.trim())
            assertTrue(interpreter >= 0)
            val version = execute(toolCommand(launch.drop(interpreter) + "--version"), environment = poisoned)
            assertEquals(version.stderr, 0, version.exit)
            assertTrue(version.stdout.contains("1.15.0"))
            assertFalse("Opening or restarting must not import workspace modules", marker.exists())
        }
        val base = execute(toolCommand(listOf("$env/bin/python", "-E", "-S", "-c", "import sys;print(sys._base_executable)")))
        assertEquals(base.stderr, 0, base.exit)
        val empty = File(directory, "empty-environment")
        val create = execute(toolCommand(listOf(base.stdout.trim(), "-E", "-m", "venv", "--without-pip", empty.path)))
        assertEquals(create.stderr, 0, create.exit)
        val missing = execute(RunCommands.languageServerProbe("pylsp", directory.path, empty.path), environment = poisoned)
        assertEquals(127, missing.exit)
        assertTrue(missing.stderr.contains("Install python-lsp-server"))
        assertFalse(marker.exists())
    }

    @Test fun pythonToolingStillFindsPackagesInstalledInTheNormalUserSite() {
        val env = System.getenv("TERMUIDE_TEST_PYTHON_ENV")
        org.junit.Assume.assumeNotNull(env)
        val home = File(directory, "user-home").apply { mkdirs() }
        val base = execute(toolCommand(listOf("$env/bin/python", "-E", "-S", "-c", "import sys;print(sys._base_executable)")))
        assertEquals(base.stderr, 0, base.exit)
        val userSite = execute(toolCommand(listOf(base.stdout.trim(), "-E", "-S", "-c", "import site;print(site.getusersitepackages())")), environment = mapOf("HOME" to home.path))
        assertEquals(userSite.stderr, 0, userSite.exit)
        val installed = File(userSite.stdout.trim(), "pylsp").apply { mkdirs() }
        installed.resolve("__init__.py").writeText("")
        installed.resolve("__main__.py").writeText("print('user-site server')\n")
        val python = File(directory, "bin/python").apply {
            parentFile.mkdirs()
            writeText("#!/bin/bash\nexec '${base.stdout.trim().replace("'", "'\\''")}' \"\$@\"\n")
            setExecutable(true)
        }
        val environment = mapOf("HOME" to home.path, "PATH" to python.parent + File.pathSeparator + System.getenv("PATH"))
        val probe = execute(RunCommands.languageServerProbe("pylsp", directory.path, ""), environment = environment)
        assertEquals(probe.stderr, 0, probe.exit)
        val launched = execute(toolCommand(listOf(probe.stdout.trim()) + RunCommands.pythonLanguageServerArguments()), environment = environment)
        assertEquals(launched.stderr, 0, launched.exit)
        assertEquals("user-site server\n", launched.stdout)
    }

}
