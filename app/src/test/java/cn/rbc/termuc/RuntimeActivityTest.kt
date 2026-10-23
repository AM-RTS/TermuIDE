package cn.rbc.termuc

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
class RuntimeActivityTest {
    @org.robolectric.annotation.Implements(FileHelper::class, isInAndroidSdk = false)
    class EntryReadFailure {
        companion object {
            @org.robolectric.annotation.Implementation @JvmStatic
            fun openInputStream(file: File): java.io.InputStream {
                if (file.name == "unreadable.py") throw java.io.IOException("Fixture source read failed")
                return java.io.FileInputStream(file)
            }
        }
    }
    private lateinit var directory: File
    private lateinit var source: File
    private lateinit var host: ActivityController<MainActivity>
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        Application.completion = "n"
        Application.auto_save = false
        Application.theme = "l"
        Project.close()
        directory = Files.createTempDirectory("runtime-activity").toFile()
        source = File(directory, "main.c").apply { writeText("int main(void) { return 0; }") }
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        host = Robolectric.buildActivity(MainActivity::class.java)
        host.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        host.create().start().postCreate(null).resume().visible()
        host.get().openFile(source)
        host.get().fragmentManager.executePendingTransactions()
    }

    private fun idle() {
        val worker = RunController::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(app.runs) as ExecutorService
        repeat(2) {
            worker.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            drainSourceSaves()
        }
    }

    private fun reply(probe: Intent, stdout: String = "/termux/bin/clang\n") {
        val callback = probe.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        callback.send(app, 0, Intent().putExtra("result", Bundle().apply {
            putInt("err", -1); putInt("exitCode", 0); putString("stdout", stdout)
        }))
        idle()
    }

    @After fun clean() {
        idle()
        host.pause().stop().destroy()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        directory.deleteRecursively()
        Project.close()
    }

    @Test fun runUsesTheExistingBackgroundSaveAndActualTermuxIntentContract() {
        host.get().editor.paste("/* saved */ ")
        host.get().performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        assertNotNull(probe)
        assertTrue(source.readText().startsWith("/* saved */ "))
        assertFalse(app.documents.isDirty(source.path))
        assertEquals("com.termux.app.RunCommandService", probe.component!!.className)
        assertTrue(probe.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
        reply(probe)
        val execution = shadowOf(app).nextStartedService
        assertNotNull(execution)
        assertFalse(execution.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", true))
        assertTrue(execution.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!![1].contains("/termux/bin/clang"))
        assertEquals(RunController.Stage.DISPATCHED, app.runs.state.value.stage)
    }

    @Test fun configuredEntryUsesItsRecoveredDraftAndLeavesOtherDirtyTabsIntact() {
        val original = source
        val activity = host.get()
        activity.editor.paste("/* keep this draft */ ")
        val entry = File(directory, "entry.py").apply { writeText("print('disk')") }
        val draft = java.util.function.Supplier { "print('recovered')" }
        app.documents.open(entry.path, draft)
        app.documents.changed(entry.path)
        app.documents.detach(entry.path, draft)
        app.documents.flush()
        assertTrue(entry.delete())
        val config = RunConfiguration("entry.py", listOf("", "two words", "$(not-a-command)"), "data")
        assertTrue(activity.saveRunConfiguration(activity.runConfigurationScope, config))
        activity.performAction(R.id.run)
        idle()
        assertEquals(entry.path, activity.selectedFilePath)
        assertEquals("print('recovered')", entry.readText())
        assertFalse(app.documents.isDirty(entry.path))
        assertTrue(app.documents.isDirty(original.path))
        assertFalse(original.readText().contains("keep this draft"))
        val probe = shadowOf(app).nextStartedService
        assertEquals(File(directory, "data").path, probe.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!![3])
        reply(probe, "/termux/bin/python\n")
        val execution = shadowOf(app).nextStartedService
        assertEquals(File(directory, "data").path, execution.getStringExtra("com.termux.RUN_COMMAND_WORKDIR"))
        assertEquals(config.arguments, execution.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!.takeLast(3))
    }

    @Test fun editingConfigurationCancelsAnOutstandingProbeAndItSurvivesRecreation() {
        val activity = host.get()
        activity.performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        assertEquals(RunController.Stage.CHECKING, app.runs.state.value.stage)
        val config = RunConfiguration(arguments = listOf("new argument"))
        assertTrue(activity.saveRunConfiguration(activity.runConfigurationScope, config))
        assertEquals(RunController.Stage.CANCELLED, app.runs.state.value.stage)
        assertThrows(PendingIntent.CanceledException::class.java) { reply(probe) }
        assertNull(shadowOf(app).nextStartedService)
        host.recreate()
        assertEquals(config, host.get().runConfiguration)
    }

    @Test fun unavailableEntryAndFailedConfiguredSaveCannotDiscardDraftsOrDispatch() {
        val activity = host.get()
        activity.editor.paste("/* recover me */ ")
        assertTrue(activity.saveRunConfiguration(activity.runConfigurationScope, RunConfiguration("missing.py")))
        activity.performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(source.path, activity.selectedFilePath)
        assertTrue(app.documents.isDirty(source.path))
        assertTrue(activity.saveRunConfiguration(activity.runConfigurationScope, RunConfiguration(source.name)))
        assertTrue(source.delete())
        assertTrue(source.mkdir())
        activity.performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.documents.isDirty(source.path))
        app.documents.flush()
        assertTrue(app.documents.recoveryText(source.path)!!.contains("recover me"))
    }

    @Test fun configSettingsDoNotOverwriteProjectConfigurationOrItsUnsavedDocument() {
        val configFile = File(directory, Project.PROJ).apply { writeText("""{"runCmd":"legacy-command"}""") }
        Project.load(configFile, null)
        val activity = host.get()
        activity.openFile(configFile)
        activity.fragmentManager.executePendingTransactions()
        activity.editor.paste("draft ")
        val config = RunConfiguration("main.c", listOf("--extra"), "data")
        assertTrue(activity.saveRunConfiguration(activity.runConfigurationScope, config))
        assertEquals("""{"runCmd":"legacy-command"}""", configFile.readText())
        assertTrue(app.documents.isDirty(configFile.path))
        assertEquals(config, activity.runConfiguration)
    }

    @Test fun configurationChangedOutsideTheDialogCannotDispatchAnOldProbe() {
        val activity = host.get()
        activity.performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        RunConfiguration.save(app, activity.runConfigurationScope, RunConfiguration(arguments = listOf("changed")))
        reply(probe)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(RunController.Stage.CANCELLED, app.runs.state.value.stage)
    }

    @Test fun configuredNativeEntryRetainsItsBreakpointsWhenAnotherLanguageIsSelected() {
        val activity = host.get()
        activity.editor.text.markLine(1)
        val config = RunConfiguration(source.name, listOf("--debug-arg"), "data")
        activity.saveRunConfiguration(activity.runConfigurationScope, config)
        openScript("other.py")
        assertTrue(activity.canDebug())
        activity.performAction(R.id.debug)
        idle()
        assertEquals(File(directory, "main.c").path, activity.selectedFilePath)
        val probe = shadowOf(app).nextStartedService
        val arguments = probe.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!
        assertEquals("clang", arguments[5])
        assertEquals("gdb", arguments[7])
        assertTrue(activity.editor.text.marksCount > 0)
    }

    @Test @Config(shadows = [EntryReadFailure::class])
    fun unreadableConfiguredEntryCannotBeOverwrittenByAnEmptyEditorEvenAfterRecreation() {
        val entry = File(directory, "unreadable.py").apply { writeText("print('keep source')") }
        val activity = host.get()
        activity.saveRunConfiguration(activity.runConfigurationScope, RunConfiguration(entry.name))
        activity.performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("print('keep source')", entry.readText())
        host.get().editor.paste("# keep this draft\n")
        host.get().performAction(R.id.save)
        idle()
        app.documents.flush()
        assertTrue(app.documents.isDirty(entry.path))
        assertTrue(app.documents.recoveryText(entry.path)!!.contains("keep this draft"))
        assertEquals("print('keep source')", entry.readText())
        host.recreate()
        host.get().performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("print('keep source')", entry.readText())
    }

    @Test @Config(shadows = [EntryReadFailure::class])
    fun failedReadRemainsBlockedWhenOnlyDurableRecoverySurvives() {
        val entry = File(directory, "unreadable.py").apply { writeText("print('original source')") }
        host.get().saveRunConfiguration(directory.path, RunConfiguration(entry.name))
        host.get().performAction(R.id.run)
        idle()
        host.get().editor.paste("# partial buffer after read failure\n")
        app.documents.flush()
        host.pause().stop().destroy()
        app.documents.close()
        app.load(entry.path)
        app.load(source.path)
        val restored = DocumentSessions(File(app.noBackupFilesDir, "documents"), { throw AssertionError(it) }, 500)
        Application::class.java.getDeclaredField("documents").apply { isAccessible = true }.set(app, restored)
        app.saver.shutdown()
        app.saver = DocumentSaver(restored)
        host = Robolectric.buildActivity(MainActivity::class.java).create().start().postCreate(null).resume().visible()
        host.get().fragmentManager.executePendingTransactions()
        host.get().performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("print('original source')", entry.readText())
        assertTrue(restored.isDirty(entry.path))
        assertTrue(restored.recoveryText(entry.path)!!.contains("partial buffer"))
    }

    @Test fun configuredEntryReusesAnOpenCleanBufferWhenItsSourceWasDeleted() {
        val entry = source
        val original = entry.readText()
        host.get().saveRunConfiguration(directory.path, RunConfiguration(entry.name))
        openScript("other.py")
        assertTrue(entry.delete())
        host.get().performAction(R.id.run)
        idle()
        assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(original, entry.readText())
        assertEquals(entry.path, host.get().selectedFilePath)
    }

    @Test fun failedSourceSaveCannotLaunchAnyTermuxCommand() {
        host.get().editor.paste("/* draft */ ")
        assertTrue(source.delete())
        assertTrue(source.mkdir())
        host.get().performAction(R.id.run)
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(app.documents.isDirty(source.path))
        app.documents.flush()
        assertTrue(app.documents.recoveryText(source.path)!!.startsWith("/* draft */ "))
    }

    private fun openScript(name: String) {
        source = File(directory, name).apply { writeText("original") }
        host.get().openFile(source)
        host.get().fragmentManager.executePendingTransactions()
    }

    @Test fun pythonAndJavaRunThroughTheExistingSaveAndTerminalFlowWithoutNativeDebug() {
        for ((name, runtime) in listOf("main.py" to "python", "Main.java" to "java")) {
            openScript(name)
            val activity = host.get()
            assertTrue(activity.isRunVisible)
            activity.editor.text.markLine(1)
            assertFalse(activity.canDebug())
            activity.editor.paste("saved ")
            activity.performAction(R.id.run)
            idle()
            assertEquals("saved original", source.readText())
            assertFalse(app.documents.isDirty(source.path))
            val probe = shadowOf(app).nextStartedService
            assertEquals(runtime, probe.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!![5])
            reply(probe, "/termux/bin/$runtime\n")
            val execution = shadowOf(app).nextStartedService
            assertEquals(directory.path, execution.getStringExtra("com.termux.RUN_COMMAND_WORKDIR"))
            assertFalse(execution.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", true))
            assertEquals(listOf("termuc-run", "/termux/bin/$runtime") +
                (if (runtime == "java") listOf("-Dfile.encoding=UTF-8") else emptyList()) + source.path,
                execution.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!.drop(2))
        }
    }

    @Test fun failedPythonAndJavaSavesRetainRecoverableDraftsWithoutLaunchingAnything() {
        for (name in listOf("main.py", "Main.java")) {
            openScript(name)
            host.get().editor.paste("unsaved ")
            assertTrue(source.delete()); assertTrue(source.mkdir())
            host.get().performAction(R.id.run)
            idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(app.documents.isDirty(source.path))
            app.documents.flush()
            assertEquals("unsaved original", app.documents.recoveryText(source.path))
        }
    }

    @Test fun restoringAPreviouslyPlainTextPythonTabEnablesItsRuntimeWithoutLosingEdits() {
        openScript("legacy.py")
        host.get().editor.paste("draft ")
        val fragment = host.get().fragmentManager.findFragmentByTag(source.path) as EditFragment
        fragment.type = EditFragment.TYPE_HEADER
        host.recreate().visible()
        val restored = host.get().fragmentManager.findFragmentByTag(source.path) as EditFragment
        assertEquals(EditFragment.TYPE_PYTHON, restored.type)
        assertTrue(host.get().isRunVisible)
        assertEquals("draft original", restored.view!!.text.toString())
        assertTrue(app.documents.isDirty(source.path))
        assertEquals("original", source.readText())
    }

    @Test fun newerPythonAndJavaEditsDuringRuntimeChecksPreventDispatchAndKeepDrafts() {
        for ((name, runtime) in listOf("main.py" to "python", "Main.java" to "java")) {
            openScript(name)
            host.get().performAction(R.id.run)
            idle()
            val probe = shadowOf(app).nextStartedService
            host.get().editor.paste("newer ")
            reply(probe, "/termux/bin/$runtime\n")
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(RunController.Stage.FAILED, app.runs.state.value.stage)
            app.documents.flush()
            assertEquals("newer original", app.documents.recoveryText(source.path))
            assertEquals("original", source.readText())
        }
    }

    @Test fun lateProbeCannotRunADifferentSelectedTab() {
        host.get().performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        val other = File(directory, "other.c").apply { writeText("int other;") }
        host.get().openFile(other)
        host.get().fragmentManager.executePendingTransactions()
        reply(probe)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(RunController.Stage.CANCELLED, app.runs.state.value.stage)
    }

    @Test fun rotationRetainsThePendingProbeAndRestoredEditor() {
        host.get().editor.paste("/* retained */ ")
        host.get().performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        host.recreate().visible()
        reply(probe)
        assertNotNull(shadowOf(app).nextStartedService)
        assertTrue(host.get().editor.text.toString().startsWith("/* retained */ "))
        assertFalse(app.documents.isDirty(source.path))
    }

    @Test fun editingProjectConfigurationRunsTheCommandThatWasJustSaved() {
        val config = File(directory, Project.PROJ).apply {
            writeText("""{"runCmd":"old-command"}""")
            assertTrue(setLastModified(lastModified() - 10_000))
        }
        Project.load(config, null)
        host.get().openFile(config)
        host.get().fragmentManager.executePendingTransactions()
        val editor = host.get().editor
        editor.setSelection(0, editor.text.length - 1)
        editor.paste("""{"runCmd":"new-command"}""")
        host.get().performAction(R.id.run)
        idle()
        val probe = shadowOf(app).nextStartedService
        val callback = probe.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        callback.send(app, 0, Intent().putExtra("result", Bundle().apply {
            putInt("err", -1); putInt("exitCode", 0); putString("stdout", "")
        }))
        idle()
        val command = shadowOf(app).nextStartedService
        assertTrue(config.readText().contains("new-command"))
        assertTrue(command.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!![1].contains("x=(new-command)"))
        assertEquals("new-command", Project.snapshot().runCmd)
    }
}
