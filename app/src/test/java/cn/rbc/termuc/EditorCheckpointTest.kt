package cn.rbc.termuc

import cn.rbc.codeeditor.util.Pair
import cn.rbc.codeeditor.util.TextBuffer
import org.junit.Assert.*
import org.junit.Test

class EditorCheckpointTest {
    @Test fun savedUndoBranchCannotBeConfusedWithANewBranchAtTheSameDepth() {
        val buffer = TextBuffer().apply { setSpans(arrayListOf(Pair(0, 0))) }
        buffer.insert("a".toCharArray(), 0, 10, true)
        buffer.markVersion()
        buffer.undo()
        buffer.insert("b".toCharArray(), 0, 11, true)
        assertNotEquals(buffer.markedVersion, buffer.currentVersion)
    }

    @Test fun backgroundSavePointKeepsLaterTypingSeparateAndSupportsUndoToSavedText() {
        val buffer = TextBuffer().apply { setSpans(arrayListOf(Pair(0, 0))) }
        buffer.insert("a".toCharArray(), 0, 10, true)
        val point = buffer.captureSavePoint()
        buffer.insert("b".toCharArray(), 1, 11, true)
        buffer.markSaved(point)
        assertNotEquals(buffer.markedVersion, buffer.currentVersion)
        buffer.undo()
        assertEquals("a", buffer.toString())
        assertEquals(buffer.markedVersion, buffer.currentVersion)
    }
    @Test fun readersAndSelectionsPreserveRealUffffAndSkipOnlyTheBufferSentinel() {
        val text = "prefix\uFFFFsuffix\uFFFF"
        cn.rbc.codeeditor.util.CharSeqReader(text).use { assertEquals(text, it.readText()) }
        val buffer = TextBuffer().apply { setSpans(arrayListOf(Pair(0, 0))) }
        buffer.insert(text.toCharArray(), 0, 10, true)
        assertEquals(text, buffer.subSequence(0, text.length).toString())
        cn.rbc.codeeditor.util.CharSeqReader(buffer).use {
            assertEquals(text, it.readText())
            assertEquals(0, it.read(CharArray(0), 0, 0))
        }
        buffer.delete(text.length - 1, 1, 20, true)
        buffer.undo()
        assertEquals(text, buffer.toString())
    }

    @Test fun typingAfterSaveCannotMergeIntoTheSavedUndoVersion() {
        val buffer = TextBuffer().apply { setSpans(arrayListOf(Pair(0, 0))) }
        buffer.insert("a".toCharArray(), 0, 10, true)
        buffer.markVersion()
        buffer.insert("b".toCharArray(), 1, 11, true)
        assertNotEquals(buffer.markedVersion, buffer.currentVersion)
        buffer.undo()
        assertEquals("a", buffer.toString())
        assertEquals(buffer.markedVersion, buffer.currentVersion)
    }

    @Test fun recoveredBufferStaysDirtyWhenAnEditIsUndone() {
        val buffer = TextBuffer().apply { setSpans(arrayListOf(Pair(0, 0))) }
        buffer.insert("draft".toCharArray(), 0, 10, false)
        buffer.markUnsaved()
        buffer.insert("!".toCharArray(), 5, 11, true)
        buffer.undo()
        assertEquals("draft", buffer.toString())
        assertNotEquals(buffer.markedVersion, buffer.currentVersion)
        buffer.markVersion()
        assertEquals(buffer.markedVersion, buffer.currentVersion)
    }
}
