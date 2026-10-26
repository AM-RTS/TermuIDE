package amrts.termuide

import android.os.Message
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainHandlerTest {
    class TestActivity : MainActivity() {
        lateinit var field: TextEditor
        val paths = mutableListOf<String>()
        var selected = 0
        override fun getEditor() = field
        override fun getOpenFilePaths(): List<String> = paths.toList()
        override fun getSelectedItem() = if (paths.isEmpty()) -1 else selected
        override fun getTag(index: Int) = paths[index]
    }
    private lateinit var activity: TestActivity
    private lateinit var handler: MainHandler
    private lateinit var lsp: Lsp
    private lateinit var directory: File
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        Application.completion = "n"
        activity = Robolectric.buildActivity(TestActivity::class.java).get()
        activity.field = TextEditor(activity)
        handler = MainHandler(activity)
        app.hand = handler
        directory = Files.createTempDirectory("handler-review").toFile()
        lsp = app.languageServers.session(directory.resolve("first.c"), EditFragment.TYPE_C)!!
    }
    @After fun clean() {
        app.languageServers.end()
        if (::activity.isInitialized) activity.field.release()
        if (::directory.isInitialized) directory.deleteRecursively()
    }
    private fun open(path: String) {
        activity.paths.add(path)
        app.languageServers.session(File(path), Languages.fileType(File(path).name) ?: EditFragment.TYPE_C)
        app.documents.open(path) { activity.field.text.toString() }
        activity.field.text.setOnTextChangeListener { _, _, _, _ -> app.documents.changed(path) }
    }
    private fun edit(line: Int, start: Int, end: Int, text: String) = JSONObject()
        .put("range", JSONObject().put("start", JSONObject().put("line", line).put("character", start))
            .put("end", JSONObject().put("line", line).put("character", end)))
        .put("newText", text)
    private fun response(id: Int, result: Any) {
        handler.handleMessage(Message.obtain().apply { obj = lsp.incoming(JSONObject().put("id", id).put("result", result).toString()) })
    }

    @Test fun delayedFormattingCannotModifyAnotherTab() {
        val first = directory.resolve("first.c").path
        val second = directory.resolve("second.c").path
        open(first)
        activity.field.paste("first")
        val id = lsp.request(File(first), Lsp.FORMAT)
        activity.paths.add(second)
        activity.selected = 1
        response(id, JSONArray().put(edit(0, 0, 5, "wrong")))
        assertEquals("first", activity.field.text.toString())
    }

    @Test fun delayedFormattingCannotOverwriteNewTyping() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("first")
        val id = lsp.request(File(path), Lsp.FORMAT)
        activity.field.paste(" new")
        response(id, JSONArray().put(edit(0, 0, 5, "wrong")))
        assertEquals("first new", activity.field.text.toString())
    }

    @Test fun reloadInvalidatesPendingFormattingEvenWhenItWasAlreadyClean() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("first")
        app.documents.saved(path)
        val id = lsp.request(File(path), Lsp.FORMAT)
        activity.field.text.setText("new disk contents")
        activity.field.text.clearSpans()
        activity.field.text.analyzeWordWrap()
        activity.field.refreshDocument()
        app.documents.saved(path)
        response(id, JSONArray().put(edit(0, 0, 5, "wrong")))
        assertEquals("new disk contents", activity.field.text.toString())
    }

    @Test fun validFormattingIsUndoableAndRetainsRecoveryDirtyState() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("first")
        activity.field.text.markVersion()
        activity.field.setEdited(false)
        response(lsp.request(File(path), Lsp.FORMAT), JSONArray().put(edit(0, 0, 5, "formatted")))
        assertEquals("formatted", activity.field.soraEditor.text.toString())
        assertTrue(activity.field.isEdited)
        app.documents.flush()
        assertEquals("formatted", app.documents.recoveryText(path))
        activity.field.undo()
        assertEquals("first", activity.field.text.toString())
        assertFalse(activity.field.isEdited)
    }

    @Test fun capabilitiesAfterSignatureTriggersAreStillParsed() {
        val capabilities = JSONObject()
            .put("signatureHelpProvider", JSONObject().put("triggerCharacters", JSONArray().put("(")))
            .put("semanticTokensProvider", JSONObject().put("legend", JSONObject().put("tokenTypes", JSONArray().put("type"))))
        response(0, JSONObject().put("capabilities", capabilities))
        assertTrue(lsp.isSigTrig('('))
        assertEquals(cn.rbc.codeeditor.util.Tokenizer.TYPE, lsp.nativeToken(0))
    }

    @Test fun codeActionCannotRunAfterSelectionMoves() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("first")
        val target = directory.resolve("other.c").apply { writeText("original") }
        val action = Command().apply {
            this.path = path
            origin = lsp.incoming("{}")
            revision = app.documents.state.value.single { it.path == path }.revision
            start = activity.field.selectionStart; end = activity.field.selectionEnd
            edit = JSONObject().put("changes", JSONObject().put(target.toURI().toString(), JSONArray().put(edit(0, 0, 8, "wrong"))))
        }
        activity.field.setSelection(0)
        handler.executeCodeAction(action)
        assertEquals("original", target.readText())
    }

    @Test fun workspaceEditsReadLargeUnopenedFilesWithoutCorruption() {
        val file = directory.resolve("large.c").apply { writeText("line\n".repeat(1800) + "tail") }
        val changes = JSONObject().put(file.toURI().toString(), JSONArray().put(edit(1800, 0, 4, "changed")))
        handler.applyWorkspaceEdit(JSONObject().put("changes", changes))
        drainSourceSaves()
        assertEquals("line\n".repeat(1800) + "changed", file.readText())
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun invalidWorkspaceEditsDoNotPartiallyChangeEarlierFiles() {
        val first = directory.resolve("first.c").apply { writeText("first") }
        val second = directory.resolve("second.c").apply { writeText("second") }
        val changes = JSONObject()
            .put(first.toURI().toString(), JSONArray().put(edit(0, 0, 5, "changed")))
            .put(second.toURI().toString(), JSONArray().put(edit(20, 0, 1, "invalid")))
        try { handler.applyWorkspaceEdit(JSONObject().put("changes", changes)); fail("Invalid edit accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test fun unopenedRecoveredDraftIsNeverOverwrittenByWorkspaceEdits() {
        val file = directory.resolve("draft.c").apply { writeText("disk") }
        app.documents.open(file.path) { "unsaved" }
        app.documents.changed(file.path)
        app.documents.flush()
        val changes = JSONObject().put(file.toURI().toString(), JSONArray().put(edit(0, 0, 4, "server")))
        try { handler.applyWorkspaceEdit(JSONObject().put("changes", changes)); fail("Draft overwritten") }
        catch (_: java.io.IOException) {}
        assertEquals("disk", file.readText())
        assertEquals("unsaved", app.documents.recoveryText(file.path))
    }

    @Test fun identicalRequestIdsAreRoutedThroughTheirOriginatingServer() {
        val native = directory.resolve("first.c")
        val python = directory.resolve("main.py")
        open(native.path)
        activity.field.paste("original")
        val nativeRequest = lsp.request(native, Lsp.FORMAT)
        open(python.path)
        activity.selected = 1
        val pythonServer = app.languageServers.session(python, EditFragment.TYPE_PYTHON)!!
        val pythonRequest = pythonServer.request(python, Lsp.FORMAT)
        assertEquals(nativeRequest, pythonRequest)
        response(nativeRequest, JSONArray().put(edit(0, 0, 8, "wrong")))
        assertEquals("original", activity.field.text.toString())
        val result = JSONObject().put("id", pythonRequest).put("result", JSONArray().put(edit(0, 0, 8, "python")))
        handler.handleMessage(Message.obtain().apply { obj = pythonServer.incoming(result.toString()) })
        assertEquals("python", activity.field.text.toString())
    }

    @Test fun queuedResponseFromAnEndedServerCannotModifyTheDocument() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("original")
        val id = lsp.request(File(path), Lsp.FORMAT)
        val incoming = lsp.incoming(JSONObject().put("id", id).put("result", JSONArray().put(edit(0, 0, 8, "wrong"))).toString())
        lsp.end()
        handler.handleMessage(Message.obtain().apply { obj = incoming })
        assertEquals("original", activity.field.text.toString())
    }

    @Test fun pythonEditRangesCountCodePointsWithoutChangingNativeEditRanges() {
        val text = "🧪 name"
        val replacement = JSONArray().put(edit(0, 2, 6, "other"))
        val python = app.languageServers.session(directory.resolve("main.py"), EditFragment.TYPE_PYTHON)!!
        val converted = MainHandler.edits(python, text, replacement).single()
        assertEquals(3, converted.start)
        assertEquals(4, converted.len)
        val native = MainHandler.edits(lsp, text, JSONArray().put(edit(0, 3, 7, "other"))).single()
        assertEquals(converted.start, native.start)
        assertEquals(converted.len, native.len)
    }

    @Test fun completionPrefixesHandleUnicodeIdentifiersAndWholeFileNames() {
        val identifier = "𐐀name"
        assertEquals(0, MainHandler.completionStart(identifier, identifier.length, 6))
        val path = "open('sub/data-file"
        assertEquals(path.indexOf("data-file"), MainHandler.completionStart(path, path.length, 17))
        val include = "#include <dir-name"
        assertEquals(include.indexOf("dir-name"), MainHandler.completionStart(include, include.length, 19))
    }

    @Test fun workspaceEditCannotOverwriteAnotherServerSession() {
        val file = directory.resolve("first.c").apply { writeText("original") }
        open(file.path)
        val python = app.languageServers.session(directory.resolve("main.py"), EditFragment.TYPE_PYTHON)!!
        val changes = JSONObject().put(file.toURI().toString(), JSONArray().put(edit(0, 0, 8, "wrong")))
        try { handler.applyWorkspaceEdit(python, null, JSONObject().put("changes", changes)); fail("Cross-session edit accepted") }
        catch (error: java.io.IOException) { assertTrue(error.message!!.contains("another server")) }
        assertEquals("original", file.readText())
    }

    @Test fun workspaceEditRejectsATabThatChangedOrClosedAfterTheRequest() {
        val file = directory.resolve("first.c").apply { writeText("original") }
        open(file.path)
        val request = lsp.takeRequest(lsp.request(file, Lsp.RENAM))
        val changes = JSONObject().put(file.toURI().toString(), JSONArray().put(edit(0, 0, 8, "wrong")))
        app.documents.changed(file.path)
        try { handler.applyWorkspaceEdit(lsp, request.revisions, JSONObject().put("changes", changes)); fail("Changed tab accepted") }
        catch (_: java.io.IOException) {}
        app.documents.discard(file.path)
        try { handler.applyWorkspaceEdit(lsp, request.revisions, JSONObject().put("changes", changes)); fail("Closed tab accepted") }
        catch (_: java.io.IOException) {}
        assertEquals("original", file.readText())
    }

    @Test fun deferredCodeActionCommandRequiresThePostEditRevisionToRemainCurrent() {
        val path = directory.resolve("first.c").path
        open(path)
        activity.field.paste("original")
        val sender = lsp.connectedSender()
        for (newTyping in listOf(false, true)) {
            val waiting = java.util.concurrent.CompletableFuture<Void>()
            val deferred = object : MainHandler(activity) {
                override fun applyWorkspaceEdit(server: Lsp?, revisions: MutableMap<String, Long>?, workspace: JSONObject): java.util.concurrent.CompletableFuture<Void> = waiting
            }
            val action = Command().apply {
                this.path = path; origin = lsp.incoming("{}")
                revision = app.documents.state.value.single { it.path == path }.revision
                start = activity.field.selectionStart; end = activity.field.selectionEnd
                edit = JSONObject(); command = "test-command"; args = "[]"
            }
            sender.clear()
            deferred.executeCodeAction(action)
            if (newTyping) activity.field.paste(" newer")
            waiting.complete(null)
            assertEquals(if (newTyping) 0 else 1, sender.size)
        }
    }

    @Test fun positionsPreserveCrLfAndLoneCrLineBoundaries() {
        val positions = cn.rbc.codeeditor.util.TextPositions("one\r\ntwo\rthree\n")
        assertEquals(5, positions.offset(1, 0))
        assertEquals(9, positions.offset(2, 0))
        assertArrayEquals(intArrayOf(2, 2), positions.position(11))
    }
}
