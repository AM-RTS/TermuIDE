package cn.rbc.termuc

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier
import java.util.function.ToIntFunction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunControllerTest {
    private lateinit var directory: File
    private lateinit var docs: DocumentSessions
    private lateinit var controller: RunController
    private lateinit var previous: RunController
    private lateinit var path: String
    private var saving = CompletableFuture<Boolean>()
    private var contextMatch = 1
    private var debugCount = 0
    private val calls = mutableListOf<Pair<RunCommands.Plan, PendingIntent>>()
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        directory = Files.createTempDirectory("runtime-control").toFile()
        path = File(directory, "main.c").apply { writeText("original") }.path
        docs = DocumentSessions(File(directory, "drafts"), { throw AssertionError(it) }, 500)
        docs.open(path) { "draft" }
        docs.changed(path)
        previous = app.runs
        controller = RunController(app, Supplier { docs }, Function { saving }, ToIntFunction { contextMatch },
            Consumer { debugCount++ }, RunController.Transport { plan, callback -> calls.add(plan to callback) })
        Application::class.java.getDeclaredField("runs").apply { isAccessible = true }.set(app, controller)
        Project.close()
    }

    @After fun clean() {
        controller.shutdown()
        Application::class.java.getDeclaredField("runs").apply { isAccessible = true }.set(app, previous)
        docs.close()
        directory.deleteRecursively()
        Project.close()
    }

    private fun request(action: RunCommands.Action = RunCommands.Action.RUN) = RunCommands.Request(
        action, path, EditFragment.TYPE_C, directory.path, Project.snapshot(), "-Wall", listOf(4))

    private fun idle() {
        val worker = RunController::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(controller) as ExecutorService
        worker.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun start(action: RunCommands.Action = RunCommands.Action.RUN) { controller.start(request(action)); idle() }
    private fun saved() { docs.saved(path); saving.complete(true); idle() }
    private fun result(stdout: String = "/bin/clang\n", exit: Int = 0) = Bundle().apply {
        putInt("err", -1); putInt("exitCode", exit); putString("stdout", stdout)
    }
    private fun token(index: Int) = shadowOf(calls[index].second).savedIntent.data!!.lastPathSegment!!
    private fun reply(index: Int, result: Bundle) {
        val original = shadowOf(calls[index].second).savedIntent
        RunResultReceiver().onReceive(app, Intent(original).putExtra("result", result))
        idle()
    }

    @Test fun executionWaitsForSaveAndProbeThenReportsTheRealExitCode() {
        start()
        assertEquals(RunController.Stage.AWAITING_SAVE, controller.state.value.stage)
        assertTrue(calls.isEmpty())
        saved()
        assertTrue(calls.single().first.background)
        reply(0, result())
        assertEquals(2, calls.size)
        assertFalse(calls.last().first.background)
        assertEquals(RunController.Stage.DISPATCHED, controller.state.value.stage)
        reply(1, result(exit = 23))
        assertEquals(RunController.Stage.FINISHED, controller.state.value.stage)
        assertEquals(23, controller.state.value.exitCode)
    }

    @Test fun changingRunConfigurationDoesNotCancelBuildPreparation() {
        val project = Project.Config(directory.path, "build", "true", "true", "true", 0, emptyList())
        controller.start(request(RunCommands.Action.BUILD).copy(project = project))
        assertEquals(RunController.Stage.AWAITING_SAVE, controller.state.value.stage)
        controller.onConfigurationChanged()
        assertEquals(RunController.Stage.AWAITING_SAVE, controller.state.value.stage)
        assertTrue(calls.isEmpty())
    }

    @Test fun failedSaveNeverStartsAProbeAndKeepsDurableRecovery() {
        start()
        saving.completeExceptionally(java.io.IOException("failed"))
        idle()
        assertTrue(calls.isEmpty())
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
        assertTrue(docs.isDirty(path))
        docs.flush()
        DocumentSessions(File(directory, "drafts"), { throw AssertionError(it) }, 500).use {
            assertEquals("draft", it.recoveryText(path))
        }
        assertEquals("original", File(path).readText())
    }

    @Test fun newerEditsDuringProbePreventDispatchWithoutClearingDirtyState() {
        start(); saved()
        docs.changed(path)
        reply(0, result())
        assertEquals(1, calls.size)
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
        assertTrue(docs.isDirty(path))
    }

    @Test fun closedAndReopenedDocumentAtTheSamePathCannotPassTheSavedIdentityCheck() {
        start(); saved()
        docs.closeDocument(path)
        docs.open(path) { "reopened" }
        reply(0, result())
        assertEquals(1, calls.size)
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
    }

    @Test fun switchedContextCannotDispatchAnEarlierCommand() {
        start(); saved()
        contextMatch = -1
        reply(0, result())
        assertEquals(1, calls.size)
        assertEquals(RunController.Stage.CANCELLED, controller.state.value.stage)
    }

    @Test fun preparationCanContinueAfterHostRecreationWithoutOwningAView() {
        start(); saved()
        contextMatch = 0
        reply(0, result())
        assertEquals(1, calls.size)
        contextMatch = 1
        controller.onHostReady()
        assertEquals(2, calls.size)
        assertEquals(RunController.Stage.DISPATCHED, controller.state.value.stage)
    }

    @Test fun timedOutProbesAndLateCallbacksCannotStartPrograms() {
        start(); saved()
        val expired = token(0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RunController.PROBE_TIMEOUT))
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
        controller.receive(expired, result())
        assertEquals(1, calls.size)
    }

    @Test fun cancellationBeforeSaveCompletionCannotResumePreparation() {
        start()
        controller.cancelPreparation()
        saved()
        assertTrue(calls.isEmpty())
        assertEquals(RunController.Stage.CANCELLED, controller.state.value.stage)
    }

    @Test fun duplicateAndUnknownCallbacksAreIgnored() {
        start(); saved()
        controller.receive("unknown", result())
        reply(0, result())
        reply(0, result())
        assertEquals(2, calls.size)
        assertNotEquals(token(0), token(1))
    }

    @Test fun malformedResultCannotLeavePreparationStuckOrDispatchAProgram() {
        start(); saved()
        reply(0, Bundle().apply { putString("err", "not an integer") })
        assertEquals(RunController.Stage.UNKNOWN, controller.state.value.stage)
        assertFalse(controller.state.value.preparing)
        assertEquals(1, calls.size)
    }

    @Test fun missingToolErrorIsVisibleAndDoesNotStartADebugger() {
        start(RunCommands.Action.DEBUG); saved()
        reply(0, result(exit = 127).apply { putString("stderr", "Missing runtime: gdb") })
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
        assertTrue(controller.state.value.detail!!.contains("gdb"))
        assertEquals(0, debugCount)
    }

    @Test fun successfulDebugPreparationPreservesTheGdbPathAndCapturedBreakpoints() {
        start(RunCommands.Action.DEBUG); saved()
        reply(0, result("/bin/clang\n/bin/gdb\n"))
        assertEquals(1, debugCount)
        assertTrue(calls.last().first.arguments[1].contains("/bin/gdb"))
        assertEquals(listOf(4), request(RunCommands.Action.DEBUG).breakpoints)
    }

    @Test fun aNewControllerCannotAssumeAnEarlierExternalProgramStopped() {
        start(); saved(); reply(0, result())
        val fresh = RunController(app, Supplier { docs }, Function { saving }, ToIntFunction { 1 }, Consumer {})
        try {
            assertEquals(RunController.Stage.UNKNOWN, fresh.state.value.stage)
            assertFalse(fresh.state.value.preparing)
        } finally { fresh.shutdown() }
    }

    @Test fun aKnownTermuxLaunchFailureDoesNotBecomeAnUnknownRunningCommandAfterRestart() {
        start(); saved(); reply(0, result())
        reply(1, Bundle().apply { putInt("err", 1); putString("errmsg", "Command could not be started") })
        assertEquals(RunController.Stage.FAILED, controller.state.value.stage)
        val fresh = RunController(app, Supplier { docs }, Function { saving }, ToIntFunction { 1 }, Consumer {})
        try { assertEquals(RunController.Stage.IDLE, fresh.state.value.stage) }
        finally { fresh.shutdown() }
    }

    @Test fun legacyTermuxTransportKeepsArgumentsAndNeedsNoCallback() {
        val args = arrayOf("space argument", "quote\"\$argument")
        Utils.run(app, "/termux/bin/clangd", args, directory.path, true)
        val sent = shadowOf(app).nextStartedService
        assertEquals("com.termux.RUN_COMMAND", sent.action)
        assertEquals("/termux/bin/clangd", sent.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertArrayEquals(args, sent.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS"))
        assertFalse(sent.hasExtra("com.termux.RUN_COMMAND_PENDING_INTENT"))
    }
}
