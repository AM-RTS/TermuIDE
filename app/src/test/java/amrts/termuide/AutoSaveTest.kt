package amrts.termuide

import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoSaveTest {
    private lateinit var directory: File
    private var controller: ActivityController<MainActivity>? = null
    private val app get() = Application.getInstance()

    private fun configureAutoSave(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean("autosave", enabled).putString(Application.KEY_COMPLETION, "n").commit()
        Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume().pause().stop().destroy()
    }

    @Before fun prepare() {
        directory = Files.createTempDirectory("autosave").toFile()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        configureAutoSave(false)
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

    private fun open(activity: MainActivity, file: File): TextEditor {
        activity.openFile(file)
        activity.fragmentManager.executePendingTransactions()
        return activity.editor
    }

    private fun idle(millis: Long = 1_100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        drainSourceSaves()
    }

    @After fun clean() {
        controller?.pause()?.stop()?.destroy()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        configureAutoSave(false)
        directory.deleteRecursively()
    }

    @Test fun disabledAutoSaveKeepsSourceUnchangedAndDraftDirty() {
        val file = directory.resolve("one.txt").apply { writeText("original") }
        open(launch(), file).paste("draft ")
        idle()
        assertEquals("original", file.readText())
        assertTrue(app.documents.isDirty(file.path))
    }

    @Test fun enabledAutoSaveDebouncesEditsAndSavesHiddenTabs() {
        configureAutoSave(true)
        val first = directory.resolve("one.txt").apply { writeText("original") }
        val second = directory.resolve("two.txt").apply { writeText("second") }
        val activity = launch()
        val editor = open(activity, first)
        editor.paste("A")
        idle(600)
        assertEquals("original", first.readText())
        editor.paste("B")
        open(activity, second).paste("C")
        idle(600)
        assertEquals("original", first.readText())
        idle(600)
        assertEquals("ABoriginal", first.readText())
        assertEquals("Csecond", second.readText())
        assertFalse(app.documents.isDirty(first.path))
        assertFalse(app.documents.isDirty(second.path))
        assertFalse(editor.isEdited)
    }

    @Test fun failedAutoSaveRetainsADurableDirtyDraft() {
        configureAutoSave(true)
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val timestamp = file.lastModified()
        val editor = open(launch(), file)
        editor.paste("draft ")
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertTrue(file.setLastModified(timestamp))
        idle()
        app.documents.flush()
        assertTrue(editor.isEdited)
        assertTrue(app.documents.isDirty(file.path))
        DocumentSessions(File(app.noBackupFilesDir, "documents"), { throw AssertionError(it) }, 500).use {
            assertEquals("draft original", it.recoveryText(file.path))
        }
    }

    @Test fun disablingAutoSaveCancelsAnAlreadyQueuedSave() {
        configureAutoSave(true)
        val file = directory.resolve("one.txt").apply { writeText("original") }
        open(launch(), file).paste("draft ")
        configureAutoSave(false)
        idle()
        assertEquals("original", file.readText())
        assertTrue(app.documents.isDirty(file.path))
    }

    @Test fun autoSaveDoesNotWriteBehindADiscardPromptOrAfterDiscard() {
        configureAutoSave(true)
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val activity = launch()
        open(activity, file).paste("draft ")
        activity.closeFile(activity.selectedItem)
        idle()
        assertEquals("original", file.readText())
        assertFalse(activity.pendingClosePaths.isEmpty())
        activity.resolveCloseDocuments(false)
        activity.fragmentManager.executePendingTransactions()
        idle()
        assertEquals("original", file.readText())
        assertFalse(app.documents.state.value.any { it.path == file.path })
    }

    @Test fun externallyChangedSourcesAreNotSilentlyOverwritten() {
        configureAutoSave(true)
        val file = directory.resolve("one.txt").apply { writeText("original") }
        open(launch(), file).paste("draft ")
        val originalTime = file.lastModified()
        file.writeText("external change")
        assertTrue(file.setLastModified(originalTime + 10_000))
        idle()
        assertEquals("external change", file.readText())
        assertTrue(app.documents.isDirty(file.path))
    }

    @Test fun recreationPreservesRecoveredDraftWithoutAutoWritingIt() {
        configureAutoSave(true)
        val file = directory.resolve("one.txt").apply { writeText("original") }
        open(launch(), file).paste("draft ")
        val state = Bundle()
        controller!!.saveInstanceState(state).pause().stop().destroy()
        controller = null
        val restored = launch(state)
        idle()
        assertEquals("original", file.readText())
        assertEquals("draft original", restored.editor.text.toString())
        assertTrue(app.documents.isDirty(file.path))
    }
}
