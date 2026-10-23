package cn.rbc.termuc

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
class RunCommandsTest {
    private fun project(root: String? = null, run: String = "\$x/\$e") =
        Project.Config(root, "build output", "custom-build \"\$f\"", "custom-compile \"\$f\"", run, 0, emptyList())
    private fun request(action: RunCommands.Action = RunCommands.Action.RUN, type: Int = EditFragment.TYPE_C,
        project: Project.Config = project(), path: String = "/source/quote \"\$test\".c") =
        RunCommands.Request(action, path, type, "/working directory", project, "-lm -Wall", listOf(3, 7))

    @After fun clean() { Project.close() }

    @Test fun languageLookupPreservesLegacyTypesAndSeparatesExecutionFromHighlighting() {
        assertEquals(EditFragment.TYPE_CPP, Languages.fileType("main.cc"))
        assertEquals(EditFragment.TYPE_CPP or EditFragment.TYPE_HEADER, Languages.fileType("main.hpp"))
        assertTrue(Languages.forType(Languages.fileType("main.hpp")!!).hasLsp)
        assertFalse(Languages.canRun(Languages.fileType("main.hpp")!!))
        assertTrue(Languages.canRun(EditFragment.TYPE_C))
        assertTrue(Languages.canRun(EditFragment.TYPE_CPP))
        assertTrue(Languages.canRun(EditFragment.TYPE_JAVA))
        assertTrue(Languages.canRun(Languages.fileType("Main.java")!!))
        assertEquals("java", Languages.forType(EditFragment.TYPE_JAVA or EditFragment.TYPE_HEADER).id)
        assertFalse(Languages.forType(EditFragment.TYPE_JAVA).hasLsp)
        assertEquals(EditFragment.TYPE_PYTHON, Languages.fileType("main.py"))
        assertTrue(Languages.canRun(Languages.fileType("main.py")!!))
        assertFalse(Languages.forType(EditFragment.TYPE_PYTHON).hasLsp)
        assertFalse(Languages.canRun(Languages.fileType("config.json")!!))
        assertFalse(Languages.canRun(Languages.fileType("main.js")!!))
        assertFalse(Languages.canRun(EditFragment.TYPE_TXT))
        assertTrue(Languages.isCodeFile("header.h"))
    }

    @Test fun nativeRunPreservesFlagsStagingAndQuotesTheSourcePath() {
        val req = request()
        val plan = RunCommands.build(req, mapOf("clang" to "/termux/bin/clang"))
        val script = plan.arguments[1]
        assertTrue(script.contains("\"/termux/bin/clang\""))
        assertTrue(script.contains(Utils.escape(File(req.path!!).absolutePath)))
        assertTrue(script.contains("-lm -Wall -g -o \"\$x\" && \"\${x[@]}\""))
        assertTrue(script.contains("\$TMPDIR/m"))
        assertEquals(req.directory, plan.directory)
        assertFalse(plan.background)
    }

    @Test fun scriptPlansUseResolvedRuntimesLiteralPathsAndSourceDirectoryWithoutNativeFlags() {
        for ((extension, runtime) in listOf("py" to "python", "java" to "java")) {
            val req = request(type = Languages.fileType("Main.$extension")!!, path = "/source/quote \"\$test\".$extension")
            assertEquals(listOf(runtime), RunCommands.requiredTools(req))
            val plan = RunCommands.build(req, mapOf(runtime to "/bin/$runtime"))
            assertEquals(File(req.path!!).absoluteFile.parent, plan.directory)
            assertEquals(listOf("termuc-run", "/bin/$runtime") +
                (if (runtime == "java") listOf("-Dfile.encoding=UTF-8") else emptyList()) + File(req.path).absolutePath,
                plan.arguments.drop(2))
            assertTrue(plan.arguments[1].startsWith("\"\$@\"\n"))
            assertFalse(plan.arguments[1].contains(req.path))
            assertFalse(plan.arguments[1].contains("-lm"))
            assertFalse(plan.arguments[1].contains("TMPDIR"))
            assertFalse(plan.background)
            for (action in listOf(RunCommands.Action.DEBUG, RunCommands.Action.BUILD, RunCommands.Action.COMPILE)) {
                assertThrows(IllegalArgumentException::class.java) { RunCommands.requiredTools(req.copy(action = action)) }
            }
        }
    }

    @Test fun selectingPythonOrJavaInAProjectRetainsConfiguredNativeCommands() {
        for (name in listOf("main.py", "Main.java")) {
            val req = request(type = Languages.fileType(name)!!, project = project("/project", "custom-entry --argument"))
            assertTrue(RunCommands.requiredTools(req).isEmpty())
            assertEquals("/project", RunCommands.workingDirectory(req))
            assertTrue(RunCommands.build(req, emptyMap()).arguments[1].contains("x=(custom-entry --argument)"))
            assertFalse(RunCommands.probe(req).arguments[1].contains("jdk.compiler"))
        }
    }

    @Test fun cppDebugKeepsGdbMiAndOnlyRequiresItsOwnTools() {
        val req = request(RunCommands.Action.DEBUG, EditFragment.TYPE_CPP)
        assertEquals(listOf("clang++", "gdb"), RunCommands.requiredTools(req))
        val script = RunCommands.build(req, mapOf("clang++" to "/bin/clang++", "gdb" to "/bin/gdb")).arguments[1]
        assertTrue(script.contains("\"/bin/clang++\""))
        assertTrue(script.contains("127.0.0.1 -p 48456"))
        assertTrue(script.contains("\"/bin/gdb\" -q -i=mi -ret -tty `tty` --args \"\${x[@]}\""))
    }

    @Test fun projectRunKeepsCustomCommandsAndNativeArtifactsWithoutAssumingACompiler() {
        val req = request(project = project("/project root", "custom-run \"\$p/file with space\" --flag"))
        assertTrue(RunCommands.requiredTools(req).isEmpty())
        val plan = RunCommands.build(req, emptyMap())
        assertEquals("/project root", plan.directory)
        assertTrue(plan.arguments[1].contains("find \"\$o\""))
        assertTrue(plan.arguments[1].contains("install -D -t \"\$x\""))
        assertTrue(plan.arguments[1].contains("x=(custom-run \"\$p/file with space\" --flag)"))
        assertTrue(plan.arguments[1].contains("o=\"build output\""))
    }

    @Test fun defaultProjectRunRetainsSpacesInTheExecutablePath() {
        val plan = RunCommands.build(request(project = project("/project root")), emptyMap())
        assertTrue(plan.arguments[1].contains("x=(\"\$x/\$e\")"))
    }

    @Test fun buildAndCompileKeepTheirConfiguredCommandsAndCreateOutputInsideTermux() {
        val config = project("/project")
        for ((action, expected) in listOf(RunCommands.Action.BUILD to config.buildCmd, RunCommands.Action.COMPILE to config.compileCmd)) {
            val plan = RunCommands.build(request(action, project = config), emptyMap())
            assertTrue(plan.arguments[1].contains("mkdir -p -- \"\$o\" && $expected"))
            assertEquals("/project", plan.directory)
        }
    }

    @Test fun probeUsesPositionalArgumentsAndRechecksCachedExecutables() {
        val req = request()
        val plan = RunCommands.probe(req, mapOf("clang" to "/cached/clang"))
        assertTrue(plan.background)
        assertFalse(plan.arguments[1].contains(req.path!!))
        assertEquals(listOf(req.directory, req.path, "clang", "/cached/clang"), plan.arguments.drop(3))
        assertTrue(plan.arguments[1].contains("[ ! -x \"\$resolved\" ]"))
    }

    @Test fun invalidProjectLoadCannotPartiallyReplaceAWorkingConfigurationOrOpenList() {
        val directory = Files.createTempDirectory("runtime-project").toFile()
        try {
            val config = File(directory, Project.PROJ)
            config.writeText("""{"buildCmd":"working","opens":["one.c"]}""")
            val opens = mutableListOf<String>()
            Project.load(config, opens)
            config.writeText("""{"buildCmd":"broken","opens":["two.c"],"runCmd":[]} """)
            try { Project.load(config, opens); fail("Invalid project accepted") } catch (_: java.io.IOException) { }
            assertEquals("working", Project.snapshot().buildCmd)
            assertEquals(listOf("one.c"), opens)
            assertEquals(directory.path, Project.snapshot().rootPath)
        } finally { directory.deleteRecursively() }
    }

    @Test fun terminalPausePreservesNonzeroExitStatus() {
        val bash = File("C:/Program Files/Git/bin/bash.exe").takeIf { it.isFile } ?: File("/bin/bash")
        assertTrue("A host bash is required for the shell regression", bash.isFile)
        val plan = RunCommands.build(request(RunCommands.Action.BUILD, project = project("/project")), emptyMap())
        val suffix = plan.arguments[1].substringAfter('\n')
        val script = Files.createTempFile("termuc-exit", ".sh").toFile()
        try {
            // Capture the prompt argument even without a host TTY; read only prints -p on a TTY.
            script.writeText("read() { printf '%s' \"\${@: -1}\"; return 1; }\n(exit 17)\n$suffix\n")
            val process = ProcessBuilder(bash.path, script.absolutePath.replace('\\', '/')).redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Shell regression timed out") }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 17, process.exitValue())
            assertEquals("\nPress any key to exit...", output)
        } finally { script.delete() }
    }

    @Test fun runConfigurationsPassLiteralArgumentsAndKeepNativePreparationInItsOriginalDirectory() {
        val arguments = listOf("", "two words", "'\";$(touch UNWANTED)\\é")
        val run = RunConfiguration(arguments = arguments, workingDirectory = "runtime data")
        for (type in listOf(EditFragment.TYPE_C, EditFragment.TYPE_CPP, EditFragment.TYPE_PYTHON, EditFragment.TYPE_JAVA)) {
            val req = request(type = type).copy(configuration = run)
            val language = Languages.forType(type)
            val plan = RunCommands.build(req, mapOf(language.runtime!! to "/bin/${language.runtime}"))
            assertEquals(arguments, plan.arguments.takeLast(arguments.size))
            assertFalse(plan.arguments[1].contains("UNWANTED"))
            assertEquals(run.directoryPath(req.directory), RunCommands.workingDirectory(req))
            if (language.hasLsp) {
                assertEquals(req.directory, plan.directory)
                assertTrue(plan.arguments[1].contains("cd -- "))
                assertTrue(plan.arguments[1].contains("\"\${x[@]}\" \"\${termuc_program_args[@]}\""))
            } else assertEquals(RunCommands.workingDirectory(req), plan.directory)
            assertEquals(RunCommands.workingDirectory(req), RunCommands.probe(req).arguments[3])
        }
        val debug = request(RunCommands.Action.DEBUG).copy(configuration = run)
        assertTrue(RunCommands.build(debug, mapOf("clang" to "/bin/clang", "gdb" to "/bin/gdb"))
            .arguments[1].contains("--args \"\${x[@]}\" \"\${termuc_program_args[@]}\""))
    }

    @Test fun projectCommandsAndBuildCompileDefaultsSurviveRunOverrides() {
        val config = project("/project root", "custom-run --existing")
        val run = RunConfiguration("other.cpp", listOf("--extra"), "data")
        val req = request(project = config).copy(configuration = run)
        val plan = RunCommands.build(req, emptyMap())
        assertEquals(config.rootPath, plan.directory)
        assertTrue(plan.arguments[1].contains("x=(custom-run --existing)"))
        assertTrue(plan.arguments[1].indexOf("install -D") < plan.arguments[1].indexOf("cd --"))
        assertEquals(listOf(plan.executable, "--extra"), plan.arguments.drop(2))
        for (action in listOf(RunCommands.Action.BUILD, RunCommands.Action.COMPILE)) {
            assertEquals(RunCommands.build(request(action, project = config), emptyMap()),
                RunCommands.build(request(action, project = config).copy(configuration = run), emptyMap()))
            assertEquals(config.rootPath, RunCommands.workingDirectory(req.copy(action = action)))
        }
    }
}
