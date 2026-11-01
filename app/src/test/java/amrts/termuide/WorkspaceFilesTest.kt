package amrts.termuide

import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WorkspaceFilesTest.Deletes::class])
class WorkspaceFilesTest {
    companion object {
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
    }
    @Implements(FileHelper::class, isInAndroidSdk = false)
    class Deletes {
        companion object {
            @Implementation @JvmStatic fun removeFiles(file: File): Boolean {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return file.delete()
            }
        }
    }
    private lateinit var directory: File
    private lateinit var controller: ActivityController<MainActivity>
    private val app get() = Application.getInstance()
    @Before fun prepare() {
        Application.completion = "n"; Application.auto_save = false
        entered = CountDownLatch(1); release = CountDownLatch(1)
        directory = Files.createTempDirectory("workspace-files").toFile()
        controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        controller.create().start().postCreate(null).resume().visible()
        drainFileTasks()
    }
    @After fun clean() {
        release.countDown()
        drainFileTasks()
        controller.pause().stop().destroy()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        directory.deleteRecursively()
    }
    private fun open(file: File): EditFragment {
        controller.get().openFile(file)
        controller.get().fragmentManager.executePendingTransactions()
        drainFileTasks()
        return controller.get().fragmentManager.findFragmentByTag(file.path) as EditFragment
    }

    @Test fun deletionFinishesOnTheReplacementHostAndKeepsDirtyRecovery() {
        val source = directory.resolve("draft.txt").apply { writeText("original") }
        val deleting = directory.resolve("remove.txt").apply { writeText("remove") }
        val editor = open(source)
        editor.view!!.paste("draft ")
        val draft = editor.view!!.text.toString()
        val oldHost = controller.get()
        oldHost.deleteBrowserEntry(deleting.path)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        controller.recreate()
        val newHost = controller.get()
        assertNotSame(oldHost, newHost)
        assertTrue(oldHost.isDestroyed)
        assertTrue(newHost.isDeleting)
        release.countDown()
        drainFileTasks()
        assertFalse(deleting.exists())
        assertFalse(newHost.isDeleting)
        assertEquals(listOf(source.path), newHost.uiState.value.paths)
        assertFalse(newHost.uiState.value.entries.any { it.name == deleting.name })
        app.documents.flush()
        assertEquals(draft, app.documents.recoveryText(source.path))
        assertEquals("original", source.readText())
    }

    @Test fun autoSaveRetriesAfterAnUnrelatedBrowserOperationFinishes() {
        val file = directory.resolve("autosave.txt").apply { writeText("original") }
        val deleting = directory.resolve("remove.txt").apply { writeText("remove") }
        val fragment = open(file)
        Application.auto_save = true
        fragment.view!!.paste("draft ")
        controller.get().deleteBrowserEntry(deleting.path)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertEquals("original", file.readText())
        assertTrue(app.documents.isDirty(file.path))
        release.countDown()
        drainFileTasks()
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        drainSourceSaves()
        assertEquals("draft original", file.readText())
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun sourceReadSurvivesRotationWithoutPublishingTheEmptyLoadingBuffer() {
        val file = directory.resolve("read.txt").apply { writeText("disk content 🧪") }
        val blocked = CountDownLatch(1)
        app.fileTask { entered.countDown(); check(blocked.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.get().openFile(file)
            controller.get().fragmentManager.executePendingTransactions()
            val fragment = controller.get().fragmentManager.findFragmentByTag(file.path) as EditFragment
            assertTrue(fragment.isLoading)
            assertFalse(fragment.view!!.soraEditor.isEditable)
            val saving = fragment.save()
            assertFalse(saving.isDone)
            controller.recreate()
            assertEquals("disk content 🧪", file.readText())
            blocked.countDown()
            drainFileTasks()
            assertTrue(saving.isCompletedExceptionally)
            val replacement = controller.get().fragmentManager.findFragmentByTag(file.path) as EditFragment
            assertFalse(replacement.isLoading)
            assertEquals("disk content 🧪", replacement.view!!.text.toString())
            assertTrue(app.documents.isSourceLoaded(file.path))
            assertEquals("disk content 🧪", file.readText())
        } finally { blocked.countDown() }
    }

    @Test fun confirmedReloadCanFinishAfterRotationWithoutLeavingAnUnsaveableDraft() {
        val file = directory.resolve("reload.txt").apply { writeText("original") }
        val fragment = open(file)
        fragment.view!!.paste("discard this ")
        val blocked = CountDownLatch(1)
        app.fileTask { entered.countDown(); check(blocked.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.get().requestReload(fragment)
            controller.get().confirmReload()
            controller.recreate()
            blocked.countDown()
            drainFileTasks()
            val replacement = controller.get().fragmentManager.findFragmentByTag(file.path) as EditFragment
            assertTrue(replacement.isSourceLoaded)
            assertEquals("original", replacement.view!!.text.toString())
            assertFalse(app.documents.isDirty(file.path))
            assertFalse(controller.get().uiState.value.sourceUnavailable)
        } finally { blocked.countDown() }
    }

    @Test fun binarySourceIsRejectedWithoutChangingTheOriginal() {
        val file = directory.resolve("binary.bin").apply { writeBytes(byteArrayOf(1, 0, 2, 3)) }
        controller.get().openFile(file)
        controller.get().fragmentManager.executePendingTransactions()
        drainFileTasks()
        assertFalse(controller.get().openFilePaths.contains(file.path))
        assertArrayEquals(byteArrayOf(1, 0, 2, 3), file.readBytes())
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun editorChangesPublishUndoRedoStateWithoutAComposeRefreshCounter() {
        val file = directory.resolve("state.txt").apply { writeText("original") }
        val fragment = open(file)
        assertFalse(controller.get().uiState.value.undo)
        fragment.view!!.paste("draft ")
        assertTrue(controller.get().uiState.value.undo)
        fragment.view!!.undo()
        assertFalse(controller.get().uiState.value.undo)
        assertTrue(controller.get().uiState.value.redo)
        assertEquals("original", file.readText())
    }

    @Test fun languageStatusChangesAreIncludedInWorkspaceSnapshots() {
        val file = directory.resolve("status.c").apply { writeText("int main() {}") }
        open(file)
        val server = app.languageServers.session(file, EditFragment.TYPE_C)!!
        Application.completion = "s"
        try {
            server.setState(LanguageServers.State.FAILED, "analysis stopped")
            val failed = controller.get().uiState.value
            assertTrue(failed.servers.any { it.state == LanguageServers.State.FAILED && it.detail == "analysis stopped" })
            server.setState(LanguageServers.State.FAILED, "analysis stopped")
            assertSame(failed, controller.get().uiState.value)
        } finally { Application.completion = "n" }
    }

    @Test fun editingWhileProjectParsingIsPendingCannotDiscardThoseEdits() {
        val source = directory.resolve("draft.txt").apply { writeText("original") }
        val fragment = open(source)
        fragment.view!!.paste("draft ")
        val project = directory.resolve("project").apply { mkdir() }
        project.resolve(Project.PROJ).writeText("{}")
        project.resolve("readme.txt").writeText("readme")
        val host = controller.get()
        // Refresh the browser after creating the fixture outside the app.
        controller.pause().resume()
        drainFileTasks()
        host.onFileSelected("project")
        drainFileTasks()
        host.onFileSelected("readme.txt")
        host.fragmentManager.executePendingTransactions()
        drainFileTasks()
        val blocked = CountDownLatch(1)
        app.fileTask { entered.countDown(); check(blocked.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            host.confirmOpenProject()
            assertTrue(host.pendingClosePaths.contains(source.path))
            host.resolveCloseDocuments(false)
            assertTrue(host.isDeleting)
            host.editor.paste("newer ")
            blocked.countDown()
            drainFileTasks()
            assertFalse(host.hasProject())
            assertTrue(app.documents.isDirty(source.path))
            assertTrue(app.documents.isDirty(project.resolve("readme.txt").path))
            assertEquals("original", source.readText())
            assertEquals("readme", project.resolve("readme.txt").readText())
        } finally { blocked.countDown() }
    }

    @Test fun lateDirectoryResultsCannotReplaceTheNewerListing() {
        val first = directory.resolve("first").apply { mkdir() }
        val second = directory.resolve("second").apply { mkdir() }
        first.resolve("one.c").writeText("")
        second.resolve("two.py").writeText("")
        val adapter = FileAdapter(first)
        adapter.setPath(second)
        drainFileTasks()
        assertTrue((0 until adapter.count).any { adapter.getItem(it).name == "two.py" })
        assertFalse((0 until adapter.count).any { adapter.getItem(it).name == "one.c" })
        adapter.release()
    }
}
