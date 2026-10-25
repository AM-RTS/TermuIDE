package amrts.termuide

import cn.rbc.codeeditor.util.Document
import cn.rbc.codeeditor.view.autocomplete.Edit
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SoraDocumentBridgeTest {
    private fun document(text: String) = Document(object : Document.TextFieldMetrics {
        override fun getAdvance(c: Char, x: Int) = 10
        override fun getRowWidth() = 800
        override fun getLeftOffset() = 0
    }).apply {
        setText(text)
        clearSpans()
        analyzeWordWrap()
        markVersion()
    }

    private fun mirror(document: Document, content: Content) {
        content.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) {}
            override fun afterInsert(content: Content, sl: Int, sc: Int, el: Int, ec: Int, text: CharSequence) {
                EditorDocumentBridge.changed(document, ContentChangeEvent.ACTION_INSERT,
                    document.getLineOffset(sl) + sc, text, System.nanoTime())
            }
            override fun afterDelete(content: Content, sl: Int, sc: Int, el: Int, ec: Int, text: CharSequence) {
                EditorDocumentBridge.changed(document, ContentChangeEvent.ACTION_DELETE,
                    document.getLineOffset(sl) + sc, text, System.nanoTime())
            }
        })
    }

    @Test fun soraInsertDeleteAndMultilineOffsetsMatchRecoveryDocument() {
        val document = document("int main() {\n}\n")
        val content = Content(document.toString()).apply { isUndoEnabled = false }
        mirror(document, content)
        content.insert(1, 0, "  return 0;\n")
        assertEquals(content.toString(), document.toString())
        content.delete(0, 2, 1, 2)
        assertEquals(content.toString(), document.toString())
        assertNotEquals(document.markedVersion, document.currentVersion)
    }

    @Test fun fullReplacementKeepsTheEofSentinelAndCanBeUndone() {
        val document = document("old")
        EditorDocumentBridge.changed(document, ContentChangeEvent.ACTION_SET_NEW_TEXT, 0, "🧪\nnew", 100)
        assertEquals("🧪\nnew", document.toString())
        assertEquals(document.toString().length + 1, document.length)
        document.undo()
        assertEquals("old", document.toString())
        assertEquals(document.markedVersion, document.currentVersion)
    }

    @Test fun modelEditsUndoAndRedoSynchronizeWithoutReplacingSoraContent() {
        val document = document("alpha\nbeta\n")
        val content = Content(document.toString())
        document.beginBatchEdit()
        document.deleteAt(0, 5, 100)
        document.insertBefore("first".toCharArray(), 0, 100)
        document.endBatchEdit()
        EditorDocumentBridge.synchronize(document, content)
        assertEquals("first\nbeta\n", content.toString())
        document.undo()
        EditorDocumentBridge.synchronize(document, content)
        assertEquals("alpha\nbeta\n", content.toString())
        document.redo()
        EditorDocumentBridge.synchronize(document, content)
        assertEquals("first\nbeta\n", content.toString())
    }

    @Test fun unicodeEmptyTextAndUnpairedSurrogatesKeepExactUtf16Units() {
        for (old in listOf("", "a", "🧪", "\uD83D", "one\ntwo\n", "a\r\nb", "a\rb", "\r\n")) {
            for (new in listOf("", "x", "🧪z", "\uDE00", "one\n\ntwo", "a\rb", "a\r\nb", "\r")) {
                val document = document(new)
                val content = Content(old)
                EditorDocumentBridge.synchronize(document, content)
                assertEquals(new, content.toString())
            }
        }
    }

    @Test fun soraChangesStillRecoverMultipleTabsWithoutWritingSourceFiles() {
        val directory = Files.createTempDirectory("sora-recovery").toFile()
        try {
            val originals = listOf("first.c", "second.cpp").map { directory.resolve(it).apply { writeText("original") } }
            DocumentSessions(directory.resolve("drafts"), { throw AssertionError(it) }, 5).use { sessions ->
                originals.forEachIndexed { index, file ->
                    val document = document(file.readText()).apply { markUnsaved() }
                    sessions.open(file.absolutePath) { synchronized(document) { document.toString() } }
                    document.setOnTextChangeListener { _, _, _, _ -> sessions.changed(file.absolutePath) }
                    val content = Content(document.toString())
                    mirror(document, content)
                    content.insert(0, 8, " draft $index 🧪")
                    assertEquals(content.toString(), document.toString())
                }
                sessions.flush()
            }
            DocumentSessions(directory.resolve("drafts"), { throw AssertionError(it) }, 5).use { fresh ->
                originals.forEachIndexed { index, file ->
                    assertEquals("original", file.readText())
                    assertEquals("original draft $index 🧪", fresh.recoveryText(file.absolutePath))
                    assertTrue(fresh.isDirty(file.absolutePath))
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun breakpointLinesFollowSoraMultilineEdits() {
        val document = document("one\ntwo\nthree")
        document.markLine(3)
        val content = Content(document.toString())
        mirror(document, content)
        content.insert(0, 0, "new\n")
        assertEquals(4, document.getMark(0))
        content.delete(0, 4)
        assertEquals(3, document.getMark(0))
    }

    @Test fun completionPrimaryAndAdditionalEditsUseOriginalOffsetsAndOneUndoStep() {
        val document = document("abc function xyz")
        val primary = Edit().apply { start = 4; len = 8; text = "call()" }
        val before = Edit().apply { start = 0; len = 0; text = "header\n" }
        val after = Edit().apply { start = 13; len = 3; text = "tail" }
        val caret = EditorTextEdits.apply(document, listOf(primary, before, after), primary.start + primary.len)
        assertEquals("header\nabc call() tail", document.toString())
        assertEquals("header\nabc call()".length, caret)
        document.undo()
        assertEquals("abc function xyz", document.toString())
        assertEquals(document.markedVersion, document.currentVersion)
    }

    @Test fun malformedEditsAreRejectedBeforeAnyTextChanges() {
        val document = document("abc")
        val valid = Edit().apply { start = 2; len = 1; text = "c" }
        val invalid = Edit().apply { start = 0; len = 3; text = "overlap" }
        try {
            EditorTextEdits.apply(document, listOf(valid, invalid), 0)
            fail("Overlapping edits must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals("abc", document.toString())
            assertFalse(document.canUndo())
        }
    }
}
