package cn.rbc.termuc

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises system input routing; Compose's performTouchInput dispatches directly to its root. */
@RunWith(AndroidJUnit4::class)
class DebugOverlayInputTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun systemTapsReachFloatingControlsWhileActivityIsStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("Grant overlay access before this device check", android.provider.Settings.canDrawOverlays(instrumentation.targetContext))
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var view: View
            lateinit var params: WindowManager.LayoutParams
            lateinit var wm: WindowManager
            val labels = mutableListOf<Pair<String, String>>()
            val received = AtomicReference<String>()
            scenario.onActivity { activity ->
                val panel = object : DebugPanel(activity) {
                    override fun onAction(action: String) { received.set(action) }
                    override fun isConnected() = true
                    override fun isStopped() = true
                }
                view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel) as View
                params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }.get(panel) as WindowManager.LayoutParams
                wm = activity.applicationContext.getSystemService(WindowManager::class.java)
                listOf(
                    R.string.cont to "continue", R.string.skip to "next", R.string.step to "step",
                    R.string.fini to "finish", R.string.message to "more", R.string.close to "close"
                ).forEach { (label, action) -> labels += activity.getString(label) to action }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            try {
                instrumentation.runOnMainSync { wm.addView(view, params) }
                labels.forEach { (label, action) ->
                    val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed()
                        .fetchSemanticsNode().boundsInRoot
                    val origin = IntArray(2)
                    instrumentation.runOnMainSync { view.getLocationOnScreen(origin) }
                    val x = origin[0] + bounds.center.x
                    val y = origin[1] + bounds.center.y
                    val down = SystemClock.uptimeMillis()
                    for (eventAction in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), eventAction, x, y, 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            assertTrue("System rejected $action input", instrumentation.uiAutomation.injectInputEvent(event, true))
                        } finally { event.recycle() }
                    }
                    compose.waitUntil(2_000) { received.get() == action }
                    assertEquals(action, received.get())
                }
            } finally {
                instrumentation.runOnMainSync { if (view.isAttachedToWindow) wm.removeViewImmediate(view) }
            }
        }
    }
}
