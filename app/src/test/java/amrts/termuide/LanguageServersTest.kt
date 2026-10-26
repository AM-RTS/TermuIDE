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
        assertNull(servers.session(File("/workspace/Main.java"), EditFragment.TYPE_JAVA))
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
        assertEquals("pylsp", python.launchArguments().last())
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
}
