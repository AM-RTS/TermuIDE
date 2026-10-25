package amrts.termuide

import android.os.Looper
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
class ProjectSaveTest {
    private lateinit var directory: File
    private lateinit var configuration: File
    private var host: ActivityController<MainActivity>? = null
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        Application.completion = "n"
        Application.auto_save = false
        Application.theme = "l"
        Project.close()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents)
        directory = Files.createTempDirectory("project-save").toFile()
        configuration = File(directory, Project.PROJ).apply { writeText("""{"buildCmd":"original-build","runCmd":"custom-run"}""") }
        Project.load(configuration, null)
    }

    private fun launch(): Pair<MainActivity, EditFragment> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        host = controller.create().start().postCreate(null).resume().visible()
        val activity = controller.get()
        val source = File(directory, "main.c").apply { writeText("int main(void) { return 0; }") }
        activity.openFile(source)
        activity.fragmentManager.executePendingTransactions()
        return activity to activity.fragmentManager.findFragmentByTag(source.path) as EditFragment
    }

    @After fun clean() {
        release.countDown()
        drainSourceSaves()
        host?.pause()?.stop()?.destroy()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        app.saver.shutdown()
        directory.deleteRecursively()
        Project.close()
    }

    @Test fun openingProjectReindexesTheRetainedTabBeforeLanguageRequests() {
        val (activity, _) = launch()
        repeat(3) { index ->
            val file = File(directory, "extra$index.c").apply { writeText("int extra$index;") }
            activity.openFile(file)
            activity.fragmentManager.executePendingTransactions()
        }
        val retained = activity.selectedFilePath!!
        MainActivity::class.java.getDeclaredMethod("openProjFiles", List::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(activity, listOf(retained), retained)
        activity.fragmentManager.executePendingTransactions()
        assertEquals(listOf(retained), activity.openFilePaths)
        assertEquals(0, activity.selectedItem)
        assertArrayEquals(intArrayOf(0, 0), app.hand.selection(retained))
    }

    @Test fun projectSaveUsesTheBackgroundWriterAndRetainsRecentlySavedCommands() {
        configuration.writeText("""{"buildCmd":"new-build","runCmd":"new-run","outputDir":"new-output"}""")
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
            SourceWriter.write(file, text)
        }
        val paths = mutableListOf("main.c")
        val saved = Project.save(paths)
        paths.clear()
        drainSourceSaves()
        assertTrue(saved.join())
        val parsed = Project.read(configuration)
        assertEquals("new-build", parsed.buildCmd)
        assertEquals("new-build", Project.snapshot().buildCmd)
        assertEquals("new-run", parsed.runCmd)
        assertEquals("new-output", parsed.outputDir)
        assertEquals(listOf("main.c"), parsed.opens)
    }

    @Test fun failedConfigurationSaveKeepsTheProjectTabsAndOriginalFile() {
        val (activity, fragment) = launch()
        val original = configuration.readText()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { _, _ -> throw IOException("Storage failure before publication") }
        activity.performAction(R.id.prj_close)
        drainSourceSaves()
        assertTrue(activity.hasProject())
        assertEquals(listOf(fragment.file.path), activity.openFilePaths)
        assertEquals(original, configuration.readText())
        assertFalse(activity.isSavingClose)
    }

    @Test fun typingDuringConfigurationSaveCancelsClosingAndRetainsRecovery() {
        val (activity, fragment) = launch()
        val original = fragment.file.readText()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(activity.isSavingClose)
        fragment.view!!.paste("// kept draft\n")
        val draft = fragment.view!!.text.toString()
        release.countDown()
        drainSourceSaves()
        assertTrue(activity.hasProject())
        assertEquals(listOf(fragment.file.path), activity.openFilePaths)
        assertTrue(app.documents.isDirty(fragment.file.path))
        app.documents.flush()
        assertEquals(draft, app.documents.recoveryText(fragment.file.path))
        assertEquals(original, fragment.file.readText())
    }

    @Test fun explicitDiscardClosesOnlyAfterProjectSaveSucceeds() {
        val (activity, fragment) = launch()
        val original = fragment.file.readText()
        val path = fragment.file.path
        fragment.view!!.paste("// discard\n")
        activity.performAction(R.id.prj_close)
        assertEquals(listOf(path), activity.pendingClosePaths)
        activity.resolveCloseDocuments(false)
        drainSourceSaves()
        assertFalse(activity.hasProject())
        assertTrue(activity.openFilePaths.isEmpty())
        assertEquals(original, File(path).readText())
        app.documents.flush()
        assertFalse(app.documents.isDirty(path))
        assertEquals(listOf(path), Project.read(configuration).opens)
    }

    @Test fun malformedExternalConfigurationIsKeptAndReportedAsFailure() {
        configuration.writeText("broken configuration")
        val saved = Project.save(emptyList())
        drainSourceSaves()
        assertTrue(saved.isCompletedExceptionally)
        assertEquals("broken configuration", configuration.readText())
    }

    @Test fun rejectedSaveWorkerCannotLeaveAPermanentlyPendingWrite() {
        val stopped = DocumentSaver(app.documents)
        stopped.shutdown()
        val saving = stopped.saveFile(configuration.path) { "must not replace the original" }
        assertTrue(saving.isCompletedExceptionally)
        assertFalse(stopped.isSaving)
        assertTrue(configuration.readText().contains("original-build"))
    }

    @Test fun completedCloseWaitsUntilActivityResumesAfterStateWasSaved() {
        val (activity, _) = launch()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        host!!.pause().saveInstanceState(android.os.Bundle()).stop()
        release.countDown()
        drainSourceSaves()
        assertTrue("Do not discard sessions or commit fragments after state saving", activity.openFilePaths.isNotEmpty())
        host!!.restart().start().resume()
        activity.fragmentManager.executePendingTransactions()
        assertTrue(activity.openFilePaths.isEmpty())
        assertFalse(activity.hasProject())
    }

    @Test fun pendingProjectCloseFollowsActivityRecreation() {
        val (activity, _) = launch()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        host!!.recreate()
        val restored = host!!.get()
        assertNotSame(activity, restored)
        assertTrue(restored.isSavingClose)
        release.countDown()
        drainSourceSaves()
        restored.fragmentManager.executePendingTransactions()
        assertTrue(restored.openFilePaths.isEmpty())
        assertFalse(restored.hasProject())
    }

    @Test fun anApprovedDiscardSurvivesRotationWhileConfigurationIsSaving() {
        val (activity, fragment) = launch()
        val source = fragment.file
        val original = source.readText()
        fragment.view!!.paste("// explicit discard\n")
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        activity.resolveCloseDocuments(false)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        host!!.recreate()
        release.countDown()
        drainSourceSaves()
        assertTrue(host!!.get().openFilePaths.isEmpty())
        assertEquals(original, source.readText())
        assertFalse(app.documents.isDirty(source.path))
    }

    @Test fun autoSaveCannotPublishDiscardedEditsDuringProjectConfigurationSaving() {
        val (activity, fragment) = launch()
        val source = fragment.file
        val original = source.readText()
        Application.auto_save = true
        fragment.view!!.paste("// must discard\n")
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        activity.resolveCloseDocuments(false)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse("Discard must keep auto-save paused throughout metadata publication", app.saver.isSaving(source.path))
        release.countDown()
        drainSourceSaves()
        assertEquals(original, source.readText())
        assertTrue(activity.openFilePaths.isEmpty())
    }

    @Test fun aLaterExplicitSaveCancelsDiscardCloseAndKeepsItsDocumentOwner() {
        val (activity, fragment) = launch()
        fragment.view!!.paste("// user decided to save instead\n")
        val expected = fragment.view!!.text.toString()
        app.saver.shutdown()
        app.saver = DocumentSaver(app.documents) { file, text ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); SourceWriter.write(file, text)
        }
        activity.performAction(R.id.prj_close)
        activity.resolveCloseDocuments(false)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val saving = fragment.save()
        release.countDown()
        drainSourceSaves()
        assertTrue(saving.join())
        assertEquals(expected, fragment.file.readText())
        assertTrue(activity.hasProject())
        assertEquals(listOf(fragment.file.path), activity.openFilePaths)
    }
}
