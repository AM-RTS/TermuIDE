package amrts.termuide

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanguageServersTest {
    private val servers = LanguageServers()
    @Before fun prepare() {
        Project.close()
        Application.lsp_host = "127.0.0.1"
        Application.lsp_port = 48455
    }
    @After fun clean() { servers.end(); Project.close() }

    @Test fun nativeFilesShareASessionWhilePythonAndOtherFoldersAreIsolated() {
        val c = servers.session(File("/workspace/main.c"), EditFragment.TYPE_C)!!
        assertSame(c, servers.session(File("/workspace/header.hpp"), EditFragment.TYPE_CPP or EditFragment.TYPE_HEADER))
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        val other = servers.session(File("/other/main.py"), EditFragment.TYPE_PYTHON)!!
        assertNotSame(c, python)
        assertNotSame(python, other)
        assertEquals(3, setOf(c.port, python.port, other.port).size)
        val java = servers.session(File("/workspace/Main.java"), EditFragment.TYPE_JAVA)!!
        assertNotSame(c, java)
        assertNotSame(python, java)
        assertSame(java, servers.session(File("/workspace/Helper.java"), EditFragment.TYPE_JAVA))
        assertEquals(4, setOf(c.port, python.port, other.port, java.port).size)
    }

    @Test fun projectRootGroupsNestedFilesAndRebindsARetainedTabWhenItChanges() {
        val file = File("/workspace/src/main.py")
        val loose = servers.session(file, EditFragment.TYPE_PYTHON)!!
        val pending = loose.incoming("{}")
        Project.rootPath = "/workspace"
        val project = servers.session(file, EditFragment.TYPE_PYTHON)!!
        assertNotSame(loose, project)
        assertFalse(pending.isCurrent)
        assertSame(project, servers.session(File("/workspace/lib/helper.py"), EditFragment.TYPE_PYTHON))
        assertEquals(LanguageServers.path(File("/workspace")), project.root)
        assertEquals(LanguageServers.path(File("/workspace-other")), servers.session(File("/workspace-other/main.py"), EditFragment.TYPE_PYTHON)!!.root)
    }

    @Test fun closingTheLastDocumentEndsOnlyItsServer() {
        val file = File("/workspace/main.c")
        val c = servers.session(file, EditFragment.TYPE_C)!!
        val header = File("/workspace/header.h")
        servers.session(header, EditFragment.TYPE_CPP)
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        val nativeEvent = c.incoming("{}")
        val pythonEvent = python.incoming("{}")
        servers.close(file)
        assertTrue(nativeEvent.isCurrent)
        servers.close(header)
        assertFalse(nativeEvent.isCurrent)
        assertTrue(pythonEvent.isCurrent)
    }

    @Test fun configuredPortIsTheBaseAndAllocationWrapsWithoutCollisions() {
        Application.lsp_port = 65535
        val c = servers.session(File("/workspace/main.c"), EditFragment.TYPE_C)!!
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        assertEquals(65535, c.port)
        assertEquals(1024, python.port)
    }

    @Test fun openingAnotherTabRetriesAFailedServerAndRebindsItsExistingDocuments() {
        Application.completion = "s"
        val first = File("/workspace/main.c")
        val other = File("/workspace/other.c")
        val failed = servers.session(first, EditFragment.TYPE_C)!!
        servers.session(other, EditFragment.TYPE_C)
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        val stale = failed.incoming("{}")
        Lsp::class.java.getDeclaredField("started").apply { isAccessible = true }.setBoolean(failed, true)
        Lsp::class.java.getDeclaredField("failed").apply { isAccessible = true }.setBoolean(failed, true)
        val unavailable = object : android.content.ContextWrapper(Application.getInstance()) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName {
                throw SecurityException("No Termux service in this host fixture")
            }
        }
        try {
            servers.ensure(unavailable, other, EditFragment.TYPE_C)
            val replacement = servers.find(first)!!
            assertNotSame(failed, replacement)
            assertSame(replacement, servers.find(other))
            assertFalse(stale.isCurrent)
            assertNotEquals(failed.port, replacement.port)
            assertFalse(replacement.isStarted)
            assertSame(python, servers.find(File("/workspace/main.py")))
        } finally { Application.completion = "n" }
    }

    @Test fun stoppedSessionPortsAreNotImmediatelyReused() {
        val file = File("/workspace/main.py")
        val original = servers.session(file, EditFragment.TYPE_PYTHON)!!
        servers.close(file)
        assertNotEquals(original.port, servers.session(file, EditFragment.TYPE_PYTHON)!!.port)
    }

    @Test fun launchCommandsReuseTheStdioBridgeWithoutPassingClangFlagsToPython() {
        val c = servers.session(File("/workspace/main.c"), EditFragment.TYPE_C)!!
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        assertEquals(listOf("nice", "-n", "-20", "clangd", "--header-insertion-decorators=0"), c.launchArguments().takeLast(5))
        assertEquals(listOf("python") + RunCommands.pythonLanguageServerArguments(), python.launchArguments().drop(8))
        assertFalse(python.launchArguments().contains("nice"))
        val sender = python.connectedSender()
        python.initialize(python.root)
        val initialize = JSONObject(String(sender.single(), Charsets.UTF_8)).getJSONObject("params")
        assertFalse(initialize.has("initializationOptions"))
        assertTrue(initialize.isNull("processId"))
        assertFalse(initialize.getJSONObject("capabilities").getJSONObject("textDocument")
            .getJSONObject("completion").getJSONObject("completionItem").getBoolean("snippetSupport"))
    }

    @Test fun quoteTriggersRemainValidJsonAndNativePositionsRemainUtf16() {
        val file = File("/workspace/main.c")
        val lsp = servers.session(file, EditFragment.TYPE_C)!!
        val sender = lsp.connectedSender()
        lsp.didOpen(file, "c", 0, "/*🧪*/ name")
        lsp.setCompTrigs(charArrayOf('"', '\\'))
        lsp.setSigTrigs(charArrayOf('"', '\\'))
        sender.clear()
        lsp.completionTry(file, 0, 11, '"'.code)
        lsp.signatureHelpTry(file, 0, 11, '\\', false)
        val messages = sender.map { JSONObject(String(it, Charsets.UTF_8)) }
        assertEquals("\"", messages[0].getJSONObject("params").getJSONObject("context").getString("triggerCharacter"))
        assertEquals("\\", messages[1].getJSONObject("params").getJSONObject("context").getString("triggerCharacter"))
        assertEquals(11, messages[0].getJSONObject("params").getJSONObject("position").getInt("character"))
    }

    @Test fun pythonPositionsUseCodePointsAndExplicitUtf16NegotiationTakesPrecedence() {
        val file = File("/workspace/main.py")
        val lsp = servers.session(file, EditFragment.TYPE_PYTHON)!!
        val sender = lsp.connectedSender()
        val text = "label = '🧪'; name; suffix = 1"
        lsp.didOpen(file, "python", 0, text)
        val caret = text.indexOf("; suffix")
        sender.clear()
        lsp.completionTry(file, 0, caret, 'e'.code)
        var message = JSONObject(String(sender.remove(), Charsets.UTF_8)).getJSONObject("params")
        assertEquals(caret - 1, message.getJSONObject("position").getInt("character"))
        val positions = cn.rbc.codeeditor.util.TextPositions(text)
        assertEquals(caret, lsp.offset(positions, 0, caret - 1))
        lsp.setCapabilities(JSONObject().put("positionEncoding", "utf-16").put("completionProvider", JSONObject()))
        lsp.completionTry(file, 0, caret, 'e'.code)
        message = JSONObject(String(sender.remove(), Charsets.UTF_8)).getJSONObject("params")
        assertEquals(caret, message.getJSONObject("position").getInt("character"))
    }

    @Test fun reopeningADocumentKeepsVersionsIncreasingAndRangeChangesKeepTheSnapshotCurrent() {
        val file = File("/workspace/main.py")
        val lsp = servers.session(file, EditFragment.TYPE_PYTHON)!!
        lsp.connectedSender()
        lsp.didOpen(file, "python", 0, "a🧪b")
        assertEquals(1, lsp.documentVersion(file))
        val change = cn.rbc.codeeditor.util.Range().apply { stl = 0; stc = 1; enl = 0; enc = 3; msg = "x" }
        lsp.didChange(file, 2, listOf(change))
        assertEquals("axb", lsp.text(file))
        lsp.didClose(file)
        assertNull(lsp.text(file))
        assertTrue(lsp.didOpen(file, "python", 0, "reopened"))
        assertEquals(3, lsp.documentVersion(file))
    }

    @Test fun shutdownDropsObsoleteQueuedRequestsBeforeSendingExit() {
        val file = File("/workspace/main.c")
        val lsp = servers.session(file, EditFragment.TYPE_C)!!
        val sender = lsp.connectedSender()
        lsp.didOpen(file, "c", 0, "int value;")
        lsp.completionTry(file, 0, 3, 't'.code)
        lsp.end()
        val methods = sender.filter { it.isNotEmpty() }.map { JSONObject(String(it, Charsets.UTF_8)).getString("method") }
        assertEquals(listOf("shutdown", "exit"), methods)
        assertTrue(sender.stopped)
    }

    @Test fun javaUsesTheOptionalLauncherAndKeepsUtf16AndAdvancedActionsSeparate() {
        val file = File("/workspace/Main.java")
        val java = servers.session(file, EditFragment.TYPE_JAVA)!!
        java.configureRuntime("/termux/bin/termuide-java-lsp")
        assertEquals(listOf("nc", "-l", "-s", "127.0.0.1", "-p", java.port.toString(), "-w", "6",
            "/termux/bin/termuide-java-lsp"), java.launchArguments().toList())
        val sender = java.connectedSender()
        java.initialize(java.root)
        val initialization = JSONObject(String(sender.remove(), Charsets.UTF_8)).getJSONObject("params")
        assertFalse(initialization.getJSONObject("capabilities").getJSONObject("textDocument")
            .getJSONObject("completion").getJSONObject("completionItem").getBoolean("snippetSupport"))
        java.initialized()
        sender.remove() // initialized
        val configuration = JSONObject(String(sender.remove(), Charsets.UTF_8)).getJSONObject("params")
        assertEquals(java.root, configuration.getJSONObject("settings").getJSONObject("java")
            .getJSONArray("classPath").getString(0))
        java.setCapabilities(JSONObject().put("completionProvider", JSONObject()).put("renameProvider", true))
        java.didOpen(file, "java", 0, "/*🧪*/ Helper.gre")
        sender.clear()
        assertTrue(java.completionTry(file, 0, 17, 'e'.code))
        val request = JSONObject(String(sender.remove(), Charsets.UTF_8)).getJSONObject("params")
        assertEquals(17, request.getJSONObject("position").getInt("character"))
        java.rename(file, 0, 10, "renamed")
        java.formatting(file, 4, true)
        java.definition(file, 0, 10)
        assertTrue(sender.isEmpty())
    }

    @Test fun restartingJavaPreservesItsLanguageAndLeavesNativeAndPythonSessionsAlone() {
        val file = File("/workspace/Main.java")
        val java = servers.session(file, EditFragment.TYPE_JAVA)!!
        val native = servers.session(File("/workspace/main.c"), EditFragment.TYPE_C)!!
        val python = servers.session(File("/workspace/main.py"), EditFragment.TYPE_PYTHON)!!
        Application.completion = "s"
        val unavailable = object : android.content.ContextWrapper(Application.getInstance()) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName {
                throw SecurityException("No Termux service in this host fixture")
            }
        }
        try {
            servers.restart(unavailable, java.root, "java")
            val replacement = servers.find(file)!!
            assertNotSame(java, replacement)
            assertEquals(LanguageServers.Server.JAVA, replacement.server)
            assertSame(native, servers.find(File("/workspace/main.c")))
            assertSame(python, servers.find(File("/workspace/main.py")))
        } finally { Application.completion = "n" }
    }

    @Test fun pythonCapabilitiesSuppressUnsupportedNativeRequests() {
        val file = File("/workspace/main.py")
        val python = servers.session(file, EditFragment.TYPE_PYTHON)!!
        val sender = python.connectedSender()
        python.setCapabilities(JSONObject().put("completionProvider", JSONObject()))
        python.semanticTokensFull(file)
        python.formatting(file, 4, true)
        python.documentHighlight(file, 0, 0)
        python.rename(file, 0, 0, "newName")
        assertTrue(sender.isEmpty())
        assertTrue(python.completionTry(file, 0, 1, 'a'))
        assertEquals("textDocument/completion", JSONObject(String(sender.single(), Charsets.UTF_8)).getString("method"))
    }

    @Test fun ancestorDirectoryChangesRestartJavaWithoutMatchingNeighbourFolderNames() {
        val file = File("/workspace/src/Main.java")
        val old = servers.session(file, EditFragment.TYPE_JAVA)!!
        val neighbourFile = File("/workspace-other/Main.java")
        val neighbour = servers.session(neighbourFile, EditFragment.TYPE_JAVA)!!
        val nativeFile = File("/workspace/src/main.c")
        val native = servers.session(nativeFile, EditFragment.TYPE_C)!!
        val unavailable = object : android.content.ContextWrapper(Application.getInstance()) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName {
                throw SecurityException("No Termux service in this host fixture")
            }
        }
        Application.completion = "s"
        try {
            servers.javaWorkspaceChanged(unavailable, File("/workspace"))
            val replacement = servers.find(file)!!
            assertNotSame(old, replacement)
            assertSame(neighbour, servers.find(neighbourFile))
            assertSame(native, servers.find(nativeFile))
            servers.javaWorkspaceChanged(unavailable, File("/work"))
            assertSame(replacement, servers.find(file))
            assertSame(neighbour, servers.find(neighbourFile))
        } finally { Application.completion = "n" }
    }
    @Test fun preflightStatusRejectsMalformedResultsAndClosedSessionCallbacks() {
        Application.completion = "s"
        val calls = mutableListOf<android.content.Intent>()
        val context = object : android.content.ContextWrapper(Application.getInstance()) {
            override fun startForegroundService(service: android.content.Intent): android.content.ComponentName {
                calls.add(service)
                return android.content.ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        val file = File("/status-workspace/main.py")
        try {
            servers.ensure(context, file, EditFragment.TYPE_PYTHON)
            val lsp = servers.find(file)!!
            assertEquals(LanguageServers.State.CHECKING, lsp.state())
            servers.ensure(context, file, EditFragment.TYPE_PYTHON)
            assertEquals(1, calls.size)
            val callback = calls.last().getParcelableExtra<android.app.PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
            val token = org.robolectric.Shadows.shadowOf(callback).savedIntent.data!!.lastPathSegment!!
            servers.receive(token, android.os.Bundle().apply { putInt("err", -1); putInt("exitCode", 127); putString("stderr", "Install pylsp") })
            assertEquals(LanguageServers.State.MISSING, lsp.state())
            assertEquals("Install pylsp", lsp.detail())
            servers.restart(context, lsp.root, "pylsp")
            val replacement = servers.find(file)!!
            assertNotSame(lsp, replacement)
            servers.receive(token, android.os.Bundle().apply { putInt("err", -1); putInt("exitCode", 0); putString("stdout", "/bin/python\n") })
            assertEquals(LanguageServers.State.CHECKING, replacement.state())
            val second = calls.last().getParcelableExtra<android.app.PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
            val next = org.robolectric.Shadows.shadowOf(second).savedIntent.data!!.lastPathSegment!!
            servers.receive(next, android.os.Bundle().apply { putInt("err", -1); putInt("exitCode", 0); putString("stdout", "not-an-absolute-path") })
            assertEquals(LanguageServers.State.FAILED, replacement.state())
            servers.restart(context, replacement.root, "pylsp")
            val third = calls.last().getParcelableExtra<android.app.PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
            val stale = org.robolectric.Shadows.shadowOf(third).savedIntent.data!!.lastPathSegment!!
            val stopped = servers.find(file)!!
            servers.close(file)
            servers.receive(stale, android.os.Bundle().apply { putInt("err", -1); putInt("exitCode", 0); putString("stdout", "/bin/python\n") })
            assertEquals(LanguageServers.State.STOPPED, stopped.state())
            assertNull(servers.find(file))
        } finally { Application.completion = "n" }
    }

    @Test fun environmentSelectionRebindsOnlyPythonAndConfiguresJediWithTheSameInterpreter() {
        val app = Application.getInstance()
        val scope = "/environment-workspace"
        val file = File(scope, "main.py")
        val native = File(scope, "main.c")
        try {
            val c = servers.session(native, EditFragment.TYPE_C)!!
            val python = servers.session(file, EditFragment.TYPE_PYTHON)!!
            val stale = python.incoming("{}")
            RunConfiguration.save(app, scope, RunConfiguration(pythonEnvironment = ".venv"))
            val replacement = servers.session(file, EditFragment.TYPE_PYTHON)!!
            assertNotSame(python, replacement)
            assertFalse(stale.isCurrent)
            assertSame(c, servers.find(native))
            assertEquals("$scope/.venv", replacement.environment)
            replacement.configureRuntime("$scope/.venv/bin/python")
            assertEquals(listOf("$scope/.venv/bin/python") + RunCommands.pythonLanguageServerArguments(), replacement.launchArguments().takeLast(5))
            val sender = replacement.connectedSender()
            replacement.initialize(scope)
            val options = JSONObject(String(sender.single(), Charsets.UTF_8)).getJSONObject("params").getJSONObject("initializationOptions")
            assertEquals("$scope/.venv/bin/python", options.getJSONObject("pylsp").getJSONObject("plugins").getJSONObject("jedi").getString("environment"))
            replacement.initialized()
            assertEquals(LanguageServers.State.READY, replacement.state())
            replacement.connectionFailed()
            assertEquals(LanguageServers.State.DISCONNECTED, replacement.state())
        } finally { app.getSharedPreferences("run_configurations", 0).edit().remove(scope).commit() }
    }

    @Test fun preflightTimeoutAndCorruptConfigurationStayVisibleWithoutBlockingEditing() {
        val app = Application.getInstance()
        val scope = "/broken-environment-workspace"
        val file = File(scope, "main.py")
        val prefs = app.getSharedPreferences("run_configurations", 0)
        Application.completion = "s"
        try {
            prefs.edit().putString(scope, "broken JSON").commit()
            val lsp = servers.session(file, EditFragment.TYPE_PYTHON)!!
            servers.ensure(app, file, EditFragment.TYPE_PYTHON)
            assertFalse(lsp.isStarted)
            assertEquals(LanguageServers.State.FAILED, servers.status(scope).single { it.server == LanguageServers.Server.PYTHON }.state)
            assertNotNull(lsp.configurationError)
            prefs.edit().remove(scope).commit()
            val context = object : android.content.ContextWrapper(app) {
                override fun startForegroundService(service: android.content.Intent) = android.content.ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
            servers.ensure(context, file, EditFragment.TYPE_PYTHON)
            val pending = servers.find(file)!!
            assertEquals(LanguageServers.State.CHECKING, pending.state())
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RunController.PROBE_TIMEOUT))
            assertEquals(LanguageServers.State.FAILED, pending.state())
            assertEquals(app.getString(R.string.runtime_timeout), pending.detail())
        } finally { prefs.edit().remove(scope).commit(); Application.completion = "n" }
    }

}
