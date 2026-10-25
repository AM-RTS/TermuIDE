package amrts.termuide

import android.preference.PreferenceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsLoadingTest {
    @Test fun startupAndSettingsUseTheSameNumericValidationAndDefaults() {
        val app = Application.getInstance()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        try {
            for (value in listOf("", "not a number", "0", "65536", "999999999999999")) {
                prefs.edit().putString(Application.KEY_LSP_PORT, value).commit()
                app.initConfs()
                assertEquals(48455, Application.lsp_port)
                val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
                settings.pause().stop().destroy()
                assertEquals(48455, Application.lsp_port)
            }
            prefs.edit().putString(Application.KEY_LSP_PORT, "65535")
                .putString(Application.KEY_TEXTSIZE, "bad").putString(Application.KEY_TABSIZE, "0")
                .remove(Application.KEY_WORDWRAP).commit()
            app.initConfs()
            assertEquals(65535, Application.lsp_port)
            assertEquals(14, Application.textsize)
            assertEquals(4, Application.tabsize)
            assertFalse(Application.wordwrap)
        } finally {
            prefs.edit().remove(Application.KEY_LSP_PORT).remove(Application.KEY_TEXTSIZE)
                .remove(Application.KEY_TABSIZE).remove(Application.KEY_WORDWRAP).commit()
            app.initConfs()
        }
    }
}
