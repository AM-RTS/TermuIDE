package amrts.termuide

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchitectureStateTest {
    @Test fun projectPublicationIsAtomicAndOldSnapshotsRemainUnchanged() {
        val first = Project.Config("/one", "out", "build-one", "compile-one", "run-one", 1, listOf("a.c"))
        val second = Project.Config("/two", "bin", "build-two", "compile-two", "run-two", 2, listOf("b.c"))
        try {
            Project.publish(first)
            assertSame(first, Project.snapshot())
            Project.publish(second)
            assertSame(second, Project.snapshot())
            assertEquals("/one", first.rootPath)
            assertEquals("run-one", first.runCmd)
            assertThrows(UnsupportedOperationException::class.java) { first.opens.add("unsafe.c") }
            Project.close()
            assertNull(Project.snapshot().rootPath)
            assertEquals("", Project.snapshot().runCmd)
        } finally { Project.close() }
    }

    @Test fun bothSetupHostsCanRestoreAndResolveTheSamePromptState() {
        var changes = 0
        val original = TermuxPrompts { changes++ }
        original.showInstall(true)
        original.showInitialize(false)
        val saved = Bundle()
        original.save(saved)
        val restored = TermuxPrompts { changes++ }
        restored.restore(saved)
        assertTrue(restored.install)
        assertTrue(restored.installNoRemind)
        assertTrue(restored.initialize)
        assertFalse(restored.initNoRemind)
        restored.resolveInstall(Application.getInstance(), false, false)
        assertFalse(restored.install)
        assertTrue(restored.initialize)
        restored.resolveInitialize(Application.getInstance(), false, false)
        assertFalse(restored.initialize)
        assertEquals(4, changes)
    }

    @Test fun completionDecodingNeedsNoActivityAndRejectsOverlappingEdits() {
        val item = JSONObject().put("label", "hello").put("insertText", "hello")
        val result = LspResponses.completion(null, "hel", 3, JSONArray().put(item)).single()
        assertEquals(0, result.edits.single().start)
        assertEquals(3, result.edits.single().len)
        assertEquals("hello", result.edits.single().text)
        val range = JSONObject("{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":2}}")
        val edit = JSONObject().put("range", range).put("newText", "x")
        item.put("textEdit", edit).put("additionalTextEdits", JSONArray().put(edit))
        assertThrows(IllegalArgumentException::class.java) { LspResponses.completion(null, "hel", 3, JSONArray().put(item)) }
    }
}
