package amrts.termuide

import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()


    @Test fun detailsOpenAndCloseFromTouchesWhileActivityIsStopped() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val panel = DebugPanel(activity)
        val view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel) as View
        val params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }.get(panel) as WindowManager.LayoutParams
        val wm = activity.applicationContext.getSystemService(WindowManager::class.java)
        controller.pause().stop()
        try {
            compose.runOnUiThread { wm.addView(view, params) }
            compose.onNodeWithContentDescription(activity.getString(R.string.message))
                .performTouchInput { click() }
            compose.runOnIdle { assertTrue(panel.isDetailsOpen) }
            compose.onNodeWithText(activity.getString(R.string.variable)).assertIsDisplayed()
            compose.onNodeWithContentDescription(activity.getString(R.string.close))
                .performTouchInput { click() }
            compose.runOnIdle { assertFalse(panel.isDetailsOpen) }
            compose.onNodeWithContentDescription(activity.getString(R.string.message)).assertIsDisplayed()
        } finally {
            compose.runOnUiThread { if (view.isAttachedToWindow) wm.removeViewImmediate(view) }
            controller.destroy()
        }
    }

    @Test fun floatingButtonsSendCommandsWhileActivityIsStopped() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val panel = DebugPanel(activity)
        val sender = DebugSession.Sender(java.net.Socket())
        val session = DebugSession { panel.applyState(it) }
        DebugSession::class.java.getDeclaredField("sender").apply { isAccessible = true }.set(session, sender)
        DebugPanel::class.java.getDeclaredField("session").apply { isAccessible = true }.set(panel, session)
        DebugPanel::class.java.getDeclaredField("state").apply { isAccessible = true }.set(panel,
            DebugSession.Snapshot(DebugSession.STOPPED, 0, true, false, emptyList(), emptyList(), "", ""))
        val view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel) as View
        val params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }.get(panel) as WindowManager.LayoutParams
        val wm = activity.applicationContext.getSystemService(WindowManager::class.java)
        controller.pause().stop()
        try {
            compose.runOnUiThread { wm.addView(view, params) }
            val controls = listOf(
                R.string.cont to "-exec-continue", R.string.skip to "-exec-next",
                R.string.step to "-exec-step", R.string.fini to "-exec-finish"
            )
            controls.forEach { (label, action) ->
                compose.onNodeWithContentDescription(activity.getString(label)).assertIsDisplayed()
                    .performTouchInput { click() }
                compose.runOnIdle { assertEquals(action, sender.poll()) }
            }
            compose.runOnUiThread {
                session.accept("*running,thread-id=\"all\"")
            }
            compose.onNodeWithContentDescription(activity.getString(R.string.pause))
                .performTouchInput { click() }
            compose.runOnIdle { assertEquals("-exec-interrupt", sender.poll()) }
            compose.onNodeWithContentDescription(activity.getString(R.string.close))
                .performTouchInput { click() }
            compose.runOnIdle {
                assertFalse(view.isAttachedToWindow)
                assertFalse(panel.isConnected)
                assertEquals("-gdb-exit", sender.poll())
                assertEquals("", sender.poll())
            }
        } finally {
            compose.runOnUiThread { if (view.isAttachedToWindow) wm.removeViewImmediate(view) }
            controller.destroy()
        }
    }
    @Test fun inspectionRepliesUpdateTheVisibleFramesWhileActivityIsStopped() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        val panel = DebugPanel(activity)
        val view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel) as View
        val params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }.get(panel) as WindowManager.LayoutParams
        val wm = activity.applicationContext.getSystemService(WindowManager::class.java)
        controller.pause().stop()
        try {
            compose.runOnUiThread { wm.addView(view, params) }
            compose.onNodeWithContentDescription(activity.getString(R.string.message)).performTouchInput { click() }
            compose.onNodeWithText(activity.getString(R.string.frame)).performTouchInput { click() }
            compose.runOnUiThread {
                panel.applyState(DebugSession.Snapshot(DebugSession.STOPPED, 0, false, false,
                    emptyList(), listOf("#0 increment() debug.c:4"), "", ""))
            }
            compose.onNodeWithText("#0 increment() debug.c:4").assertIsDisplayed()
            compose.runOnUiThread { panel.addBkpt("debug.c", 7) }
            compose.onNodeWithText(activity.getString(R.string.bkpt)).performTouchInput { click() }
            compose.onNodeWithText("debug.c:7").assertIsOn().performTouchInput { click() }
            compose.runOnIdle { assertFalse(panel.isBreakpointEnabled(0)) }
            compose.onNodeWithText("debug.c:7").assertIsOff()
        } finally {
            compose.runOnUiThread { if (view.isAttachedToWindow) wm.removeViewImmediate(view) }
            controller.destroy()
        }
    }

}
