package amrts.termuide

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugPanelStateTest {
    @Test fun hostRecreationRetainsTheSessionAndReleasesTheOldActivity() {
        Application.completion = "n"
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putBoolean(MainActivity.INITAPP, false)
            .putBoolean(MainActivity.TESTAPP, false).commit()
        controller.create().start().postCreate(null).resume().visible()
        drainFileTasks()
        val field = MainActivity::class.java.getDeclaredField("panel").apply { isAccessible = true }
        val panel = field.get(controller.get()) as DebugPanel
        val sender = DebugSession.Sender(java.net.Socket())
        val session = DebugSession { panel.applyState(it) }
        DebugSession::class.java.getDeclaredField("sender").apply { isAccessible = true }.set(session, sender)
        val sessionField = DebugPanel::class.java.getDeclaredField("session").apply { isAccessible = true }
        sessionField.set(panel, session)
        val view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel)
        panel.addBkpt("main.c", 2)
        try {
            controller.recreate()
            drainFileTasks()
            assertSame(panel, field.get(controller.get()))
            assertSame(session, sessionField.get(panel))
            assertNull(sender.poll())
            assertEquals(listOf("main.c:2"), panel.breakpoints)
            assertNotSame(view, DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel))
            assertSame(controller.get(), DebugPanel::class.java.getDeclaredField("mCtx").apply { isAccessible = true }.get(panel))
            panel.onAction("next")
            assertTrue(sender.poll()?.endsWith("-exec-next") == true)
            panel.onAction("close")
            assertNull(sessionField.get(panel))
        } finally { panel.clean(); controller.pause().stop().destroy() }
    }

    @Test fun visibleInspectionRefreshesWhenTheProgramStopsAgain() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val panel = DebugPanel(controller.get())
        val sender = DebugSession.Sender(java.net.Socket())
        val session = DebugSession { panel.applyState(it) }
        DebugSession::class.java.getDeclaredField("sender").apply { isAccessible = true }.set(session, sender)
        DebugPanel::class.java.getDeclaredField("session").apply { isAccessible = true }.set(panel, session)
        DebugPanel::class.java.getDeclaredField("state").apply { isAccessible = true }.set(panel,
            DebugSession.Snapshot(DebugSession.RUNNING, 0, true, false, emptyList(), emptyList()))
        try {
            session.accept("*running")
            panel.onAction("more")
            assertTrue(panel.isDetailsOpen)
            session.accept("*stopped,reason=\"end-stepping-range\"")
            assertTrue(sender.poll()?.endsWith("-stack-list-arguments 0 0 0") == true)
            assertEquals(null, sender.poll())
        } finally { panel.clean(); controller.pause().stop().destroy() }
    }

    @Test fun endingAnExpandedSessionResetsTheReusableWindow() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val panel = DebugPanel(controller.get())
        val params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }
            .get(panel) as android.view.WindowManager.LayoutParams
        // Model the expanded window retained when GDB exits with details visible.
        params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
        params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
        params.gravity = android.view.Gravity.CENTER
        params.flags = android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        DebugPanel::class.java.getDeclaredField("detailsOpen").apply { isAccessible = true }.setBoolean(panel, true)
        try {
            panel.applyState(DebugSession.Snapshot(DebugSession.IDLE, 0, false, true,
                emptyList(), emptyList()))
            assertFalse(panel.isDetailsOpen)
            assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, params.width)
            assertEquals(android.view.WindowManager.LayoutParams.WRAP_CONTENT, params.height)
            assertEquals(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL, params.gravity)
            assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        } finally { panel.clean(); controller.pause().stop().destroy() }
    }

    @Test fun eachConnectionGetsAFreshOverlayComposition() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val panel = DebugPanel(controller.get())
        val field = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }
        try {
            panel.connect()
            val first = field.get(panel)
            panel.hide()
            panel.connect()
            assertNotSame(first, field.get(panel))
        } finally { panel.clean(); controller.pause().stop().destroy() }
    }

}
