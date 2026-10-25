package amrts.termuide

import android.view.View
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WindowThemeTest {
    @Test fun overlayMovementStaysWithinBoundsAndFollowsPhysicalDirectionInRtl() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        val panel = DebugPanel(activity)
        val view = DebugPanel::class.java.getDeclaredField("ctrlr").apply { isAccessible = true }.get(panel) as View
        val params = DebugPanel::class.java.getDeclaredField("params").apply { isAccessible = true }.get(panel) as android.view.WindowManager.LayoutParams
        view.layout(0, 0, 200, 48)
        val metrics = android.util.DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            view.layoutDirection = direction
            params.x = 0
            val inward = if (direction == View.LAYOUT_DIRECTION_RTL) 10000f else -10000f
            panel.moveOverlay(inward, -10000f)
            assertEquals((metrics.widthPixels - 200).coerceAtLeast(0), params.x)
            assertEquals(-((metrics.heightPixels - 48) / 2).coerceAtLeast(0), params.y)
            panel.moveOverlay(-inward, 10000f)
            assertEquals(0, params.x)
            assertEquals(((metrics.heightPixels - 48) / 2).coerceAtLeast(0), params.y)
        }
    }

    @Test fun settingsRecreationRetainsTheOriginalThemeForItsResult() {
        val app = Application.getInstance()
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putString(Application.KEY_THEME, "l").commit()
        val first = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        prefs.edit().putString(Application.KEY_THEME, "d").commit()
        val state = android.os.Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        val second = Robolectric.buildActivity(SettingsActivity::class.java).create(state).start().restoreInstanceState(state).resume()
        second.get().finishSettings()
        assertEquals(android.app.Activity.RESULT_FIRST_USER, org.robolectric.Shadows.shadowOf(second.get()).resultCode)
        second.pause().stop().destroy()
    }

    @Test fun liveThemeChangesUpdateBarsAndNativeThemeWithoutLosingFullscreenFlags() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
        val window = activity.window
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        for (dark in listOf(false, true, false)) {
            val surface = if (dark) 0xff12150e.toInt() else 0xfffbfdf2.toInt()
            updateWindowTheme(activity, dark, surface)
            assertEquals(surface, window.statusBarColor)
            assertEquals(surface, window.navigationBarColor)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
            assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
            val value = android.util.TypedValue()
            assertTrue(activity.theme.resolveAttribute(android.R.attr.windowLightStatusBar, value, true))
            assertEquals(!dark, value.data != 0)
        }
    }
}
