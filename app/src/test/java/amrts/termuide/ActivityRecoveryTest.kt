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
        app.lsp = Lsp()
        val connected = object : java.net.Socket() { override fun isConnected() = true }
        Lsp::class.java.getDeclaredField("sk").apply { isAccessible = true }.set(app.lsp, connected)
        Lsp::class.java.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(app.lsp, true)
        val sender = Lsp::class.java.getDeclaredField("mSndr").apply { isAccessible = true }.get(app.lsp) as Lsp.Sender
        Application.completion = "s"
        val fragment = activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
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
        val request = app.lsp.takeRequest(completion.getInt("id"))
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
        app.lsp = Lsp()
        app.lsp.setSemToks(arrayOf(Lsp.SemToken.TYPE))
        val id = app.lsp.request(first, Lsp.SEMTOK)
        activity.openFile(second)
        activity.fragmentManager.executePendingTransactions()
        val result = org.json.JSONObject().put("id", id).put("result",
            org.json.JSONObject().put("data", org.json.JSONArray("[0,0,3,0,0]")))
        app.hand.handleMessage(android.os.Message.obtain().apply { obj = result.toString() })
        assertEquals(setOf("Foo"), app.hand.cacheData[first.path])
        val tokens = TextEditor::class.java.getDeclaredField("semanticTokens").apply { isAccessible = true }
        val original = activity.fragmentManager.findFragmentByTag(first.path) as EditFragment
        assertEquals(1, (tokens.get(original.view) as List<*>).size)
        assertTrue((tokens.get(activity.editor) as List<*>).isEmpty())
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
