package amrts.termuide

import android.os.Bundle
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityRecoveryTest {
    private lateinit var directory: File
    private var controller: ActivityController<MainActivity>? = null
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        Application.completion = "n"
        Application.theme = "l"
        directory = Files.createTempDirectory("activity-recovery").toFile()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
    }
    private fun launch(state: Bundle? = null): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java)
        created.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        created.create(state).start()
        if (state != null) created.restoreInstanceState(state)
        controller = created.postCreate(state).resume()
        return created.get()
    }
    @After fun clean() {
        controller?.pause()?.stop()?.destroy()
        app.languageServers.end()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun coldLaunchRendersRecoveredTabsWithoutWritingSources() {
        val files = listOf("one.txt", "two.txt", "main.py", "Main.java").map { directory.resolve(it).apply { writeText("original") } }
        files.forEachIndexed { index, file ->
            app.documents.open(file.path) { "draft $index\r\n🧪" }
            app.documents.changed(file.path)
        }
        app.documents.flush()
        app.documents.close()
        val freshSessions = DocumentSessions(File(app.noBackupFilesDir, "documents"), { throw AssertionError(it) }, 500)
        Application::class.java.getDeclaredField("documents").apply { isAccessible = true }.set(app, freshSessions)
        app.saver.shutdown()
        app.saver = DocumentSaver(freshSessions)
        val activity = launch()
        activity.fragmentManager.executePendingTransactions()
        files.forEachIndexed { index, file ->
            val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
            assertEquals("draft $index\r\n🧪", fragment.view!!.soraEditor.text.toString())
            assertTrue(fragment.view!!.isEdited)
            assertTrue(app.documents.isDirty(file.path))
            assertEquals("original", file.readText())
        }
    }

    @Test fun failedSaveRetainsDraftAndDirtyFlag() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        fragment.view!!.paste("unsaved ")
        val draft = fragment.view!!.text.toString()
        assertTrue(file.delete()); assertTrue(file.mkdir())
        val saving = fragment.save()
        drainSourceSaves()
        assertTrue(saving.isCompletedExceptionally)
        assertTrue(fragment.view!!.isEdited)
        app.documents.flush()
        assertEquals(draft, app.documents.recoveryText(file.path))
    }

    @Test fun savePreservesRealUffffCharactersAndClearsOnlyAfterSuccess() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        fragment.view!!.paste("prefix\uFFFFsuffix ")
        val text = fragment.view!!.text.toString()
        fragment.save()
        drainSourceSaves()
        assertEquals(text, file.readText())
        assertFalse(fragment.view!!.isEdited)
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun activityRecreationPreservesUndoHistoryAndReleasesOldSoraView() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val old = activity.editor.soraEditor
        activity.editor.paste("draft ")
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        assertTrue(old.isReleased)
        val restored = launch(state)
        restored.fragmentManager.executePendingTransactions()
        assertEquals("draft original", restored.editor.soraEditor.text.toString())
        restored.editor.undo()
        assertEquals("original", restored.editor.soraEditor.text.toString())
        assertFalse(restored.editor.isEdited)
    }

    @Test fun completionCannotInsertAtAMovedCaret() {
        val file = directory.resolve("one.txt").apply { writeText("word other") }
        val activity = launch()
        controller!!.visible()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val editor = activity.editor
        editor.setSelection(4)
        val item = cn.rbc.codeeditor.view.autocomplete.ListItem().apply { label = "wording" }
        editor.autoCompletePanel.update(arrayListOf(item))
        assertTrue(editor.autoCompletePanel.isShow)
        editor.setSelection(10)
        editor.autoCompletePanel.select(0)
        assertEquals("word other", editor.text.toString())
    }

    @Test fun languageSyncUsesTheFinishedBufferAndFinalCaret() {
        val file = directory.resolve("one.c").apply { writeText("int value;") }
        val activity = launch()
        controller!!.visible()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val lsp = app.languageServers.session(file, EditFragment.TYPE_C)!!
        val sender = lsp.connectedSender()
        Application.completion = "s"
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        fragment.onOpen(activity.editor.text)
        sender.clear()
        val oldVersion = fragment.version
        activity.editor.setSelection(4)
        activity.editor.soraEditor.commitText("a", false, false)
        assertTrue(fragment.version > oldVersion)
        val stale = org.json.JSONObject().put("documentChanges", org.json.JSONArray().put(org.json.JSONObject()
            .put("textDocument", org.json.JSONObject().put("uri", file.toURI().toString()).put("version", oldVersion))
            .put("edits", org.json.JSONArray())))
        try { app.hand.applyWorkspaceEdit(stale); fail("Pending typing must invalidate old server edits immediately") }
        catch (_: java.io.IOException) {}
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val messages = sender.map { org.json.JSONObject(String(it, Charsets.UTF_8)) }
        val change = messages.single { it.getString("method") == "textDocument/didChange" }
        assertEquals("int avalue;", change.getJSONObject("params").getJSONArray("contentChanges").getJSONObject(0).getString("text"))
        val completion = messages.single { it.getString("method") == "textDocument/completion" }
        val request = lsp.takeRequest(completion.getInt("id"))
        assertEquals(activity.editor.selectionStart, request.start)
        assertEquals(activity.editor.selectionEnd, request.end)
        assertEquals(5, completion.getJSONObject("params").getJSONObject("position").getInt("character"))
        Application.completion = "n"
    }

    @Test fun delayedSemanticTokensUpdateTheirOwnHiddenTab() {
        val first = directory.resolve("one.c").apply { writeText("Foo first;") }
        val second = directory.resolve("two.c").apply { writeText("Bar second;") }
        val activity = launch()
        activity.openFile(first)
        activity.fragmentManager.executePendingTransactions()
        val lsp = app.languageServers.session(first, EditFragment.TYPE_C)!!
        lsp.setSemToks(arrayOf(Lsp.SemToken.TYPE))
        val id = lsp.request(first, Lsp.SEMTOK)
        activity.openFile(second)
        activity.fragmentManager.executePendingTransactions()
        val result = org.json.JSONObject().put("id", id).put("result",
            org.json.JSONObject().put("data", org.json.JSONArray("[0,0,3,0,0]")))
        app.hand.handleMessage(android.os.Message.obtain().apply { obj = lsp.incoming(result.toString()) })
        assertEquals(setOf("Foo"), app.hand.cacheData[first.path])
        val tokens = TextEditor::class.java.getDeclaredField("semanticTokens").apply { isAccessible = true }
        val original = activity.fragmentManager.findFragmentByTag(first.path) as EditFragment
        assertEquals(1, (tokens.get(original.view) as List<*>).size)
        assertTrue((tokens.get(activity.editor) as List<*>).isEmpty())
    }

    @Test fun pythonCompletionUsesRecoveredTextAndKeepsAcceptedEditsRecoverable() {
        val file = directory.resolve("main.py").apply { writeText("# disk source\n") }
        val draft = "import os\nos.pa"
        app.documents.open(file.path) { draft }
        app.documents.changed(file.path)
        app.documents.flush()
        val activity = launch()
        controller!!.visible()
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        val lsp = fragment.languageServer()
        val sender = lsp.connectedSender()
        Application.completion = "s"
        try {
            assertTrue(fragment.onOpen(activity.editor.text))
            assertFalse(fragment.onOpen(activity.editor.text))
            val opened = sender.map { org.json.JSONObject(String(it, Charsets.UTF_8)) }
                .single { it.getString("method") == "textDocument/didOpen" }.getJSONObject("params").getJSONObject("textDocument")
            assertEquals("python", opened.getString("languageId"))
            assertEquals(draft, opened.getString("text"))
            assertEquals(fragment.version, opened.getInt("version"))
            activity.editor.setSelection(draft.length)
            val id = lsp.request(file, Lsp.COMPLETION)
            val completion = org.json.JSONObject().put("label", "path").put("insertText", "path")
            val reply = org.json.JSONObject().put("id", id).put("result", org.json.JSONObject()
                .put("items", org.json.JSONArray().put(completion)))
            app.hand.handleMessage(android.os.Message.obtain().apply { obj = lsp.incoming(reply.toString()) })
            assertTrue(activity.editor.autoCompletePanel.isShow)
            activity.editor.autoCompletePanel.select(0)
            assertEquals("import os\nos.path", activity.editor.text.toString())
            app.documents.flush()
            assertEquals("import os\nos.path", app.documents.recoveryText(file.path))
            assertEquals("# disk source\n", file.readText())
            assertTrue(app.documents.isDirty(file.path))
            activity.editor.undo()
            assertEquals(draft, activity.editor.text.toString())
        } finally { Application.completion = "n" }
    }

    @Test fun completionFromAnEndedSessionCannotBeAccepted() {
        val file = directory.resolve("main.py").apply { writeText("word") }
        val activity = launch()
        controller!!.visible()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        activity.editor.setSelection(4)
        val lsp = app.languageServers.session(file, EditFragment.TYPE_PYTHON)!!
        val item = cn.rbc.codeeditor.view.autocomplete.ListItem().apply { label = "wording" }
        activity.editor.autoCompletePanel.update(arrayListOf(item), lsp.incoming("{}"))
        assertTrue(activity.editor.autoCompletePanel.isShow)
        lsp.end()
        activity.editor.autoCompletePanel.select(0)
        assertEquals("word", activity.editor.text.toString())
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun realPylspCompletesSiblingImportsFromAnUnsavedRecoveredBuffer() {
        org.junit.Assume.assumeTrue("Enabled by the GitHub prerelease workflow", System.getenv("TERMUIDE_PYLSP_INTEGRATION") == "1")
        val file = directory.resolve("main.py").apply { writeText("# original source\n") }
        directory.resolve("sibling.py").writeText("greeting = 'hello'\n")
        directory.resolve("data-file.txt").writeText("example")
        val draft = "import sibling\nlabel = '🧪'; sibling.gre; suffix = 1"
        app.documents.open(file.path) { draft }
        app.documents.changed(file.path)
        app.documents.flush()
        val activity = launch()
        controller!!.visible()
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        val lsp = fragment.languageServer()
        Lsp::class.java.getDeclaredField("mRead").apply { isAccessible = true }.set(lsp, app.hand)
        // Host-only transport smoke: Termux uses the same JSON framing through the existing nc stdio bridge.
        val process = ProcessBuilder("pylsp", "--tcp", "--host", lsp.host, "--port", lsp.port.toString())
            .directory(directory).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = Thread { lsp.run() }
        fun await(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25)
            while (System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (condition()) return
                Thread.sleep(20)
            }
            fail(description)
        }
        Application.completion = "s"
        try {
            lsp.initialize(lsp.root)
            reader.start()
            await("pylsp initialization did not finish") {
                Lsp::class.java.getDeclaredField("ready").apply { isAccessible = true }.getBoolean(lsp)
            }
            assertTrue(lsp.supports("completionProvider"))
            assertFalse(lsp.supports("semanticTokensProvider"))
            val caret = draft.indexOf("; suffix")
            activity.editor.setSelection(caret)
            val position = cn.rbc.codeeditor.util.TextPositions(draft).position(caret)
            assertTrue(lsp.completionTry(file, position[0], position[1], 'e'.code))
            await("pylsp did not provide sibling-module completion") { activity.editor.autoCompletePanel.isShow }
            val items = EditorCompletionPanel::class.java.getDeclaredField("items").apply { isAccessible = true }
                .get(activity.editor.autoCompletePanel) as List<*>
            val index = items.indexOfFirst { (it as cn.rbc.codeeditor.view.autocomplete.ListItem).label == "greeting" }
            assertTrue("Missing greeting completion: $items", index >= 0)
            activity.editor.autoCompletePanel.select(index)
            assertEquals("import sibling\nlabel = '🧪'; sibling.greeting; suffix = 1", activity.editor.text.toString())
            activity.editor.setSelection(0, activity.editor.text.length - 1)
            activity.editor.paste("open('data-fi")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(lsp.completionTry(file, 0, activity.editor.caretPosition, 'i'.code))
            await("pylsp did not provide filename completion") { activity.editor.autoCompletePanel.isShow }
            val files = EditorCompletionPanel::class.java.getDeclaredField("items").apply { isAccessible = true }
                .get(activity.editor.autoCompletePanel) as List<*>
            val fileIndex = files.indexOfFirst { (it as cn.rbc.codeeditor.view.autocomplete.ListItem).label == "data-file.txt'" }
            assertTrue("Missing data-file.txt completion", fileIndex >= 0)
            activity.editor.autoCompletePanel.select(fileIndex)
            assertEquals("open('data-file.txt'", activity.editor.text.toString())
            app.documents.flush()
            assertEquals(activity.editor.text.toString(), app.documents.recoveryText(file.path))
            assertEquals("# original source\n", file.readText())
            assertTrue(app.documents.isDirty(file.path))
        } finally {
            Application.completion = "n"
            lsp.end()
            process.destroyForcibly()
            reader.join(3000)
            assertFalse("LSP reader did not terminate", reader.isAlive)
        }
    }

    @Test fun rotationAfterStateCaptureKeepsServerVersionsAndPendingDraftsCurrent() {
        val file = directory.resolve("main.py").apply { writeText("value = 1") }
        val activity = launch()
        controller!!.visible()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        val lsp = fragment.languageServer()
        lsp.connectedSender()
        Application.completion = "s"
        try {
            fragment.onOpen(activity.editor.text)
            val state = Bundle()
            controller!!.saveInstanceState(state)
            activity.editor.paste("# pending\n")
            controller!!.pause().stop().destroy()
            controller = null
            val synchronizedVersion = lsp.documentVersion(file)
            val restored = launch(state)
            restored.fragmentManager.executePendingTransactions()
            val restoredFragment = restored.fragmentManager.findFragmentByTag(file.path) as EditFragment
            assertTrue(restoredFragment.version >= synchronizedVersion)
            restored.editor.paste("# after rotation\n")
            restoredFragment.flushLanguageChange()
            assertTrue(lsp.documentVersion(file) > synchronizedVersion)
            assertEquals(restoredFragment.version, lsp.documentVersion(file))
            assertEquals(restored.editor.text.toString(), lsp.text(file))
            app.documents.flush()
            assertEquals(restored.editor.text.toString(), app.documents.recoveryText(file.path))
            assertEquals("value = 1", file.readText())
        } finally { Application.completion = "n" }
    }

    @Test fun recreationRequestsNativeSemanticTokensForTheNewView() {
        val file = directory.resolve("main.c").apply { writeText("Foo value;") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        val lsp = fragment.languageServer()
        val sender = lsp.connectedSender()
        lsp.setSemToks(arrayOf(Lsp.SemToken.TYPE))
        Application.completion = "s"
        try {
            fragment.onOpen(activity.editor.text)
            val state = Bundle()
            controller!!.saveInstanceState(state).pause().stop().destroy()
            controller = null
            sender.clear()
            val restored = launch(state)
            restored.fragmentManager.executePendingTransactions()
            val request = sender.map { org.json.JSONObject(String(it, Charsets.UTF_8)) }
                .last { it.getString("method") == "textDocument/semanticTokens/full" }
            val result = org.json.JSONObject().put("id", request.getInt("id")).put("result",
                org.json.JSONObject().put("data", org.json.JSONArray("[0,0,3,0,0]")))
            app.hand.handleMessage(android.os.Message.obtain().apply { obj = lsp.incoming(result.toString()) })
            val tokens = TextEditor::class.java.getDeclaredField("semanticTokens").apply { isAccessible = true }
            assertEquals(1, (tokens.get(restored.editor) as List<*>).size)
        } finally { Application.completion = "n" }
    }

    @Test fun pythonDiagnosticsConvertCodePointColumnsAndRejectUnsynchronizedBuffers() {
        val text = "label = '🧪'; missing"
        val file = directory.resolve("main.py").apply { writeText(text) }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
        val lsp = fragment.languageServer()
        lsp.connectedSender()
        fragment.onOpen(activity.editor.text)
        val start = text.indexOf("missing")
        fun publish(message: String) {
            val diagnostic = org.json.JSONObject().put("message", message).put("range", org.json.JSONObject()
                .put("start", org.json.JSONObject().put("line", 0).put("character", start - 1))
                .put("end", org.json.JSONObject().put("line", 0).put("character", text.length - 1)))
            val notification = org.json.JSONObject().put("method", "textDocument/publishDiagnostics").put("params",
                org.json.JSONObject().put("uri", file.toURI().toString()).put("diagnostics", org.json.JSONArray().put(diagnostic)))
            app.hand.handleMessage(android.os.Message.obtain().apply { obj = lsp.incoming(notification.toString()) })
        }
        publish("unicode diagnostic")
        assertEquals(start, activity.editor.text.diag.single().stc)
        activity.editor.paste("new ")
        publish("stale diagnostic")
        assertEquals("unicode diagnostic", activity.editor.text.diag.single().msg)
    }

    @Test fun staleRenameCannotChangeAnotherEditedTabOrItsRecovery() {
        val first = directory.resolve("first.c").apply { writeText("first") }
        val second = directory.resolve("second.c").apply { writeText("second") }
        val activity = launch()
        activity.openFile(first)
        activity.fragmentManager.executePendingTransactions()
        activity.openFile(second)
        activity.fragmentManager.executePendingTransactions()
        val firstFragment = activity.fragmentManager.findFragmentByTag(first.path) as EditFragment
        val secondFragment = activity.fragmentManager.findFragmentByTag(second.path) as EditFragment
        val firstEditor = requireNotNull(firstFragment.view)
        val secondEditor = requireNotNull(secondFragment.view)
        val lsp = firstFragment.languageServer()
        secondFragment.languageServer()
        lsp.connectedSender()
        activity.selectItem(activity.openFilePaths.indexOf(first.path))
        val id = lsp.request(first, Lsp.RENAM)
        secondEditor.paste("new ")
        fun replace(length: Int) = org.json.JSONArray().put(org.json.JSONObject().put("newText", "wrong")
            .put("range", org.json.JSONObject().put("start", org.json.JSONObject().put("line", 0).put("character", 0))
                .put("end", org.json.JSONObject().put("line", 0).put("character", length))))
        val changes = org.json.JSONObject().put(first.toURI().toString(), replace(5)).put(second.toURI().toString(), replace(6))
        val response = org.json.JSONObject().put("id", id).put("result", org.json.JSONObject().put("changes", changes))
        app.hand.handleMessage(android.os.Message.obtain().apply { obj = lsp.incoming(response.toString()) })
        assertEquals("first", firstEditor.text.toString())
        assertEquals("new second", secondEditor.text.toString())
        app.documents.flush()
        assertEquals("new second", app.documents.recoveryText(second.path))
        assertEquals("second", second.readText())
    }

    @Test fun pythonDisconnectCannotClearNativeDiagnostics() {
        val native = directory.resolve("main.c").apply { writeText("int main() {}") }
        val python = directory.resolve("main.py").apply { writeText("pass") }
        val activity = launch()
        activity.openFile(native)
        activity.fragmentManager.executePendingTransactions()
        val nativeFragment = activity.fragmentManager.findFragmentByTag(native.path) as EditFragment
        app.languageServers.session(native, EditFragment.TYPE_C)
        val diagnostics = listOf(Diagnostic().apply { msg = "native warning"; stl = 1; enl = 1; enc = 1 })
        val nativeEditor = requireNotNull(nativeFragment.view)
        nativeEditor.text.setDiag(diagnostics)
        activity.openFile(python)
        activity.fragmentManager.executePendingTransactions()
        val lsp = app.languageServers.session(python, EditFragment.TYPE_PYTHON)!!
        app.hand.handleMessage(android.os.Message.obtain().apply { what = Lsp.ERROR; obj = lsp.incoming(null) })
        assertSame(diagnostics, nativeEditor.text.diag)
        assertNull(activity.editor.text.diag)
    }

    @Test fun recreatedClosePromptCanCancelOrDiscardTheOriginalTarget() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        activity.editor.paste("draft ")
        activity.closeFile(activity.selectedItem)
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        val restored = launch(state)
        assertEquals(listOf(file.path), restored.pendingClosePaths)
        assertEquals("draft original", restored.editor.text.toString())
        restored.cancelCloseDocuments()
        assertTrue(app.documents.isDirty(file.path))
        restored.closeFile(restored.selectedItem)
        val again = Bundle()
        controller!!.saveInstanceState(again).pause().stop().destroy()
        controller = null
        val final = launch(again)
        final.resolveCloseDocuments(false)
        final.fragmentManager.executePendingTransactions()
        assertFalse(final.openFilePaths.contains(file.path))
        assertFalse(app.documents.state.value.any { it.path == file.path })
        assertEquals("original", file.readText())
    }

    @Test fun recreatedClosePromptCanSaveBeforeClosing() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        activity.editor.paste("draft ")
        activity.closeFile(activity.selectedItem)
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        val restored = launch(state)
        restored.resolveCloseDocuments(true)
        drainSourceSaves()
        restored.fragmentManager.executePendingTransactions()
        assertFalse(restored.openFilePaths.contains(file.path))
        assertEquals("draft original", file.readText())
    }

    @Test fun restoredProjectCloseStillWorksWhenProjectStaticsAreLost() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        activity.editor.paste("draft ")
        Project.rootPath = directory.path
        activity.performAction(R.id.prj_close)
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        Project.close()
        val restored = launch(state)
        assertEquals(listOf(file.path), restored.pendingClosePaths)
        restored.resolveCloseDocuments(false)
        restored.fragmentManager.executePendingTransactions()
        assertTrue(restored.openFilePaths.isEmpty())
        assertEquals("original", file.readText())
    }
}
