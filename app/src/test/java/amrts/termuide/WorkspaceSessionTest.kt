package amrts.termuide

import android.os.Bundle
import java.io.File
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspaceSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recovery get() = File(temporary.root, "recovery")
    private fun documents() = DocumentSessions(recovery, { throw AssertionError(it) }, 60_000)

    @Test fun activeProjectRestoresFromDiskAndClosingClearsItsPersistentIdentity() {
        val config = temporary.newFile(Project.PROJ).apply { writeText("""{"buildCmd":"custom build","runCmd":"custom run"}""") }
        val workspace = WorkspaceSession()
        workspace.activateProject(Project.read(config))
        val state = Bundle().also { workspace.save(it) }
        Project.close()
        val cold = WorkspaceSession()
        val pending = cold.restoreProject()
        drainFileTasks()
        assertEquals("custom build", pending.join().buildCmd)
        cold.activateProject(pending.join())
        assertEquals(config.parent, Project.snapshot().rootPath)
        cold.clearProject()
        assertNull(WorkspaceSession().restoreProject().join())
        // Saved Android state can independently restore the project after statics were lost.
        val restored = WorkspaceSession().apply { restore(state) }
        val saved = restored.restoreProject()
        drainFileTasks()
        assertEquals("custom run", saved.join().runCmd)
        restored.clearProject()
    }

    @Test fun missingProjectDoesNotDiscardRecoveredDocuments() {
        val config = temporary.newFile(Project.PROJ).apply { writeText("{}") }
        val workspace = WorkspaceSession()
        workspace.activateProject(Project.read(config))
        Project.close()
        assertTrue(config.delete())
        documents().use { owner ->
            val path = temporary.newFile("draft.c").path
            owner.open(path) { "unsaved" }; owner.changed(path)
            val pending = WorkspaceSession().restoreProject()
            drainFileTasks()
            assertTrue(pending.isCompletedExceptionally)
            assertEquals("unsaved", owner.recoveryText(path))
        }
        workspace.clearProject()
    }

    @Test fun coldActivityRestoresProjectBeforeEnablingExecutionAndKeepsItsDraft() {
        Application.completion = "n"
        val app = Application.getInstance()
        val source = temporary.newFile("main.c").apply { writeText("disk") }
        val config = temporary.newFile(Project.PROJ).apply { writeText("""{"buildCmd":"custom compiler"}""") }
        val workspace = WorkspaceSession()
        workspace.activateProject(Project.read(config))
        Project.close()
        app.documents.open(source.path) { "unsaved draft" }
        app.documents.changed(source.path)
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putString(MainActivity.PWD, temporary.root.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        app.fileTask {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            controller.create().start().postCreate(null).resume().visible()
            assertFalse(controller.get().isRuntimeHostReady)
            assertFalse(controller.get().canDebug())
            release.countDown()
            drainFileTasks()
            assertTrue(controller.get().isRuntimeHostReady)
            assertEquals(config.parent, Project.snapshot().rootPath)
            assertEquals("custom compiler", Project.snapshot().buildCmd)
            assertEquals("unsaved draft", controller.get().editor.text.toString())
            assertTrue(app.documents.isDirty(source.path))
        } finally {
            release.countDown()
            drainFileTasks()
            controller.pause().stop().destroy()
            app.documents.discard(source.path)
            workspace.clearProject()
        }
    }

    @Test fun removingTabsKeepsSelectionOnTheSameDocumentOrItsNeighbour() {
        val first = temporary.newFile("first.c").path
        val second = temporary.newFile("second.py").path
        val third = temporary.newFile("third.java").path
        val workspace = WorkspaceSession()
        listOf(first, second, third).forEach { workspace.add(it) }
        assertTrue(workspace.select(2))
        assertFalse(workspace.add(third))
        workspace.remove(first)
        assertEquals(third, workspace.selectedPath)
        assertEquals(1, workspace.selectedIndex)
        assertFalse(workspace.select(8))
        assertEquals(third, workspace.selectedPath)
        workspace.remove(third)
        assertEquals(second, workspace.selectedPath)
        assertEquals(0, workspace.selectedIndex)
        workspace.remove(second)
        assertNull(workspace.selectedPath)
        assertEquals(-1, workspace.selectedIndex)
    }

    @Test fun restoredProjectSwitchPromptKeepsTheSelectedTabAndDurableDraft() {
        val first = temporary.newFile("first.c").apply { writeText("disk first") }
        val second = temporary.newFile("second.py").apply { writeText("disk second") }
        val target = File(temporary.root, "other-project").path
        val state = Bundle()
        documents().use { owner ->
            owner.open(first.path) { "unsaved first" }
            owner.open(second.path) { "disk second" }
            owner.changed(first.path)
            val workspace = WorkspaceSession()
            workspace.add(first.path)
            workspace.add(second.path)
            workspace.select(1)
            workspace.pendingProjectPath = target
            assertTrue(workspace.requestClose(listOf(first.path), WorkspaceSession.OPEN_PROJECT, target, owner))
            workspace.save(state)
        }
        documents().use { restored ->
            val workspace = WorkspaceSession().apply { restore(state) }
            assertEquals(listOf(first.path, second.path), workspace.toList())
            assertEquals(second.path, workspace.selectedPath)
            assertEquals(target, workspace.pendingProjectPath)
            assertEquals(target, workspace.pendingClose.target)
            assertEquals(WorkspaceSession.OPEN_PROJECT, workspace.pendingClose.kind)
            assertEquals(listOf(first.path), workspace.pendingClosePaths())
            assertThrows(IllegalStateException::class.java) { workspace.closeDocument(first.path, restored) }
            workspace.cancelClose()
            assertTrue(workspace.pendingClosePaths().isEmpty())
            assertEquals("unsaved first", restored.recoveryText(first.path))
        }
        assertEquals("disk first", first.readText())
        assertEquals("disk second", second.readText())
    }

    @Test fun processRestoreRequiresFreshDiscardApprovalAndDropsOldSaveFutures() {
        val source = temporary.newFile("main.c").apply { writeText("original") }
        val state = Bundle()
        documents().use { owner ->
            owner.open(source.path) { "keep this draft" }
            owner.changed(source.path)
            val workspace = WorkspaceSession()
            workspace.add(source.path)
            workspace.select(0)
            assertTrue(workspace.requestClose(listOf(source.path), WorkspaceSession.CLOSE_PROJECT, null, owner))
            workspace.takeClose(false)
            workspace.savingClose = true
            workspace.sourceClose = CompletableFuture<Void>()
            workspace.save(state)
        }
        documents().use { restored ->
            val workspace = WorkspaceSession().apply { restore(state) }
            assertFalse(workspace.savingClose)
            assertNull(workspace.pendingResult())
            assertTrue(workspace.approvedDiscards.isEmpty())
            assertTrue(workspace.requestClose(listOf(source.path), WorkspaceSession.CLOSE_PROJECT, null, restored))
            assertThrows(IllegalStateException::class.java) { workspace.closeDocument(source.path, restored) }
            assertEquals("keep this draft", restored.recoveryText(source.path))
        }
        assertEquals("original", source.readText())
    }

    @Test fun discardingOneTabCannotDiscardAnotherTabsDurableDraft() {
        val first = temporary.newFile("discard.c").apply { writeText("original first") }
        val second = temporary.newFile("keep.py").apply { writeText("original second") }
        documents().use { owner ->
            owner.open(first.path) { "discard this draft" }
            owner.open(second.path) { "keep this draft" }
            owner.changed(first.path)
            owner.changed(second.path)
            val workspace = WorkspaceSession()
            workspace.add(first.path)
            workspace.add(second.path)
            assertTrue(workspace.requestClose(listOf(first.path), WorkspaceSession.CLOSE_FILE, first.path, owner))
            workspace.takeClose(false)
            workspace.closeDocument(first.path, owner)
            assertThrows(IllegalStateException::class.java) { workspace.closeDocument(second.path, owner) }
        }
        documents().use { restored ->
            assertEquals(listOf(second.path), restored.state.value.map { it.path })
            assertEquals("keep this draft", restored.recoveryText(second.path))
            assertNull(restored.recoveryText(first.path))
        }
        assertEquals("original first", first.readText())
        assertEquals("original second", second.readText())
    }
}
