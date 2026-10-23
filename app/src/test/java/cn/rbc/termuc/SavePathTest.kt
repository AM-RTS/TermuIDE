package cn.rbc.termuc

import android.os.Bundle
import java.io.File
import java.io.IOException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavePathTest {
    private lateinit var directory: File
    private var controller: ActivityController<MainActivity>? = null
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        Application.completion = "n"
        Application.auto_save = false
        Application.theme = "l"
        directory = Files.createTempDirectory("source-save").toFile()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            SourceWriter.write(file, text)
        }
    }

    private fun launch(state: Bundle? = null): MainActivity {
        val created = Robolectric.buildActivity(MainActivity::class.java)
        created.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        created.create(state).start()
        if (state != null) created.restoreInstanceState(state)
        controller = created.postCreate(state).resume().visible()
        return created.get()
    }

    private fun open(): Pair<File, EditFragment> {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        return file to activity.fragmentManager.findFragmentByTag(file.path) as EditFragment
    }

    @After fun clean() {
        release.countDown()
        drainSourceSaves()
        controller?.pause()?.stop()?.destroy()
        app.saver.shutdown()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        directory.deleteRecursively()
    }

    @Test fun newerTypingStaysDirtyAndRecoverableWhileSourceSaveRunsInBackground() {
        val (file, fragment) = open()
        fragment.view!!.paste("saved ")
        val saving = fragment.save()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fragment.view!!.paste("newer ")
        assertFalse(saving.isDone)
        assertEquals("original", file.readText())
        app.documents.flush()
        DocumentSessions(File(app.noBackupFilesDir, "documents"), { throw AssertionError(it) }, 500).use {
            assertEquals("saved newer original", it.recoveryText(file.path))
        }
        release.countDown()
        drainSourceSaves()
        assertFalse(saving.join())
        assertEquals("saved original", file.readText())
        assertTrue(fragment.view!!.isEdited)
        assertTrue(app.documents.isDirty(file.path))
        fragment.view!!.undo()
        assertEquals("saved original", fragment.view!!.text.toString())
        assertFalse(fragment.view!!.isEdited)
    }

    @Test fun replacingAnUndoBranchAtTheSameDepthCannotMarkNewTextClean() {
        val (file, fragment) = open()
        fragment.view!!.paste("saved ")
        fragment.save()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fragment.view!!.undo()
        fragment.view!!.paste("different ")
        release.countDown()
        drainSourceSaves()
        assertEquals("saved original", file.readText())
        assertEquals("different original", fragment.view!!.text.toString())
        assertTrue(fragment.view!!.isEdited)
        assertTrue(app.documents.isDirty(file.path))
    }

    @Test fun aSecondSaveWaitsThenCapturesTheLatestRevision() {
        val (file, fragment) = open()
        fragment.view!!.paste("first ")
        val first = fragment.save()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        fragment.view!!.paste("second ")
        val second = fragment.save()
        release.countDown()
        drainSourceSaves()
        assertFalse(first.join())
        assertTrue(second.join())
        assertEquals("first second original", file.readText())
        assertFalse(fragment.view!!.isEdited)
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun saveBeforeCloseWaitsAndKeepsNewerEditsOpen() {
        val (file, fragment) = open()
        val activity = controller!!.get()
        fragment.view!!.paste("saved ")
        activity.closeFile(activity.selectedItem)
        activity.resolveCloseDocuments(true)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(activity.isSavingClose)
        activity.resolveCloseDocuments(false)
        fragment.view!!.paste("newer ")
        release.countDown()
        drainSourceSaves()
        activity.fragmentManager.executePendingTransactions()
        assertTrue(activity.openFilePaths.contains(file.path))
        assertFalse(activity.isSavingClose)
        assertTrue(activity.pendingClosePaths.contains(file.path))
        assertTrue(app.documents.isDirty(file.path))
    }

    @Test fun rotationDuringSaveUpdatesTheReplacementEditor() {
        val (file, fragment) = open()
        fragment.view!!.paste("saved ")
        val saving = fragment.save()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        val activity = launch(state)
        activity.fragmentManager.executePendingTransactions()
        release.countDown()
        drainSourceSaves()
        assertTrue(saving.join())
        assertEquals("saved original", file.readText())
        assertFalse(activity.editor.isEdited)
        assertFalse(app.documents.isDirty(file.path))
    }

    @Test fun failedSaveCannotCloseTheTabOrDiscardItsRecovery() {
        val (file, fragment) = open()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { _, _ -> throw IOException("simulated write failure") }
        val activity = controller!!.get()
        fragment.view!!.paste("draft ")
        activity.closeFile(activity.selectedItem)
        activity.resolveCloseDocuments(true)
        drainSourceSaves()
        activity.fragmentManager.executePendingTransactions()
        assertTrue(activity.openFilePaths.contains(file.path))
        assertEquals("original", file.readText())
        assertTrue(fragment.view!!.isEdited)
        app.documents.flush()
        assertEquals("draft original", app.documents.recoveryText(file.path))
    }
}
