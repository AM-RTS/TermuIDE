package amrts.termuide

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanguageToolInstallerTest {
    private val app get() = Application.getInstance()
    private lateinit var installer: LanguageToolInstaller
    private val prefs get() = app.getSharedPreferences("language_tool_setup", Context.MODE_PRIVATE)
    @Before fun prepare() {
        prefs.edit().clear().commit()
        installer = LanguageToolInstaller(app)
        while (shadowOf(app).nextStartedService != null) { }
    }
    private fun token() = prefs.getString("token", null)!!
    private fun result(exit: Int = 0, stdout: String = "") = Bundle().apply {
        putInt("err", -1); putInt("exitCode", exit); putString("stdout", stdout)
    }

    @Test fun visibleSetupIsVerifiedBeforeSuccessAndDoesNotTouchDrafts() {
        val before = app.documents.state.value
        installer.start(app, "/project", "pylsp", "/project/venv with spaces")
        val install = shadowOf(app).nextStartedService!!
        assertFalse(install.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", true))
        val arguments = install.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS")!!
        assertEquals("/project/venv with spaces", arguments[4])
        assertTrue(arguments[2].contains("termuide"))
        val initial = token()
        installer.receive("stale", result())
        assertEquals(initial, token())
        installer.receive(initial, result())
        assertEquals(LanguageToolInstaller.Stage.CHECKING, installer.state.value.stage)
        val probe = shadowOf(app).nextStartedService!!
        assertTrue(probe.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
        installer.receive(token(), result(stdout = "/project/venv with spaces/bin/python\n"))
        assertEquals(LanguageToolInstaller.Stage.READY, installer.state.value.stage)
        assertFalse(prefs.contains("token"))
        assertEquals(before, app.documents.state.value)
    }

    @Test fun failureDoesNotProbeAndMalformedCallbacksCannotClaimSuccess() {
        installer.start(app, "/project", "java", "")
        shadowOf(app).nextStartedService
        installer.receive(token(), result(7))
        assertEquals(LanguageToolInstaller.Stage.FAILED, installer.state.value.stage)
        assertNull(shadowOf(app).nextStartedService)
        installer.start(app, "/project", "java", "")
        installer.receive(token(), Bundle().apply { putString("err", "-1") })
        assertEquals(LanguageToolInstaller.Stage.FAILED, installer.state.value.stage)
    }

    @Test fun pendingCallbackSurvivesProcessOwnerRecreationAndProbeTimesOut() {
        installer.start(app, "/project", "clangd", "")
        val saved = token()
        val restored = LanguageToolInstaller(app)
        assertEquals(LanguageToolInstaller.Stage.UNKNOWN, restored.state.value.stage)
        restored.receive(saved, result())
        assertEquals(LanguageToolInstaller.Stage.CHECKING, restored.state.value.stage)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RunController.PROBE_TIMEOUT + 1))
        assertEquals(LanguageToolInstaller.Stage.FAILED, restored.state.value.stage)
        assertFalse(prefs.contains("token"))
    }

    @Test fun secondSetupIsRejectedWhileTheFirstIsRunning() {
        installer.start(app, "/project", "java", "")
        val saved = token()
        try { installer.start(app, "/other", "pylsp", ""); fail("Concurrent setup") }
        catch (_: IllegalStateException) { }
        assertEquals(saved, token())
    }

    @Test fun unresolvedTermuxServiceFailsImmediatelyAndAllowsRetry() {
        val absent = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = null
        }
        installer.start(absent, "/project", "java", "")
        assertEquals(LanguageToolInstaller.Stage.FAILED, installer.state.value.stage)
        assertEquals(app.getString(R.string.tools_termux_missing), installer.state.value.detail)
        assertFalse(prefs.contains("token"))
        installer.start(app, "/project", "java", "")
        assertEquals(LanguageToolInstaller.Stage.INSTALLING, installer.state.value.stage)
    }
}
