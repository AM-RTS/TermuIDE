package amrts.termuide


import android.preference.PreferenceManager
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AdaptiveUiTest {
    @get:Rule(order = 0) val snapshots = object : ExternalResource() {
        override fun before() {
            Snapshot.sendApplyNotifications()
        }
    }
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @get:Rule(order = 2) val temporary = TemporaryFolder()
    private val app get() = Application.getInstance()

    @Before fun prepare() {
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        app.documents.flush()
        Application.completion = "n"
        Application.theme = "l"
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Application.KEY_THEME, "l").putString(Application.KEY_COMPLETION, "n").commit()
        app.getSharedPreferences("MainActivity", 0).edit().putString(MainActivity.PWD, temporary.root.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        temporary.newFile("first.txt").writeText("original")
    }

    @Test @Config(qualifiers = "w1000dp-h800dp-land")
    fun wideBrowserStaysAlongsideTheEditorAfterOpeningAFile() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("browser-pane").assertIsDisplayed()
            compose.onNodeWithText("first.txt").performClick()
            compose.onNodeWithTag("browser-pane").assertIsDisplayed()
            compose.onNodeWithTag("editor-pane").assertIsDisplayed()
            scenario.onActivity { activity ->
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("draft ")
                assertEquals("draft original", activity.editor.text.toString())
                assertTrue(app.documents.isDirty(activity.selectedFilePath))
                assertEquals("original", temporary.root.resolve("first.txt").readText())
            }
            val browser = compose.onNodeWithTag("browser-pane").getUnclippedBoundsInRoot()
            val editor = compose.onNodeWithTag("editor-pane").getUnclippedBoundsInRoot()
            assertTrue(browser.right <= editor.left)
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun compactBrowserUsesASheetAndClosesAfterOpeningAFile() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("browser-pane").assertDoesNotExist()
            compose.onNodeWithContentDescription(app.getString(R.string.browse_files)).performClick()
            compose.onNodeWithText(app.getString(R.string.file_browser)).assertIsDisplayed()
            compose.onNodeWithText("first.txt").performClick()
            compose.onNodeWithText(app.getString(R.string.file_browser)).assertDoesNotExist()
            compose.onNodeWithTag("editor-pane").assertIsDisplayed()
        }
    }

    @Test @Config(qualifiers = "w1000dp-h240dp-land")
    fun shortWindowWithLargeTextCanScrollPastTheBrowserHeader() {
        val resources = app.resources
        val configuration = android.content.res.Configuration(resources.configuration).apply { fontScale = 2f }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("browser-list").performScrollToNode(hasText("first.txt"))
            compose.onNodeWithText("first.txt").assertIsDisplayed().performClick()
            compose.onNodeWithTag("browser-pane").assertIsDisplayed()
            compose.onNodeWithTag("editor-pane").assertIsDisplayed()
        }
    }

    @Test fun runConfigurationMenuRestoresUnconfirmedValuesAndEnablesTheConfiguredEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(app.getString(R.string.more_options)).performClick()
            compose.onNodeWithText(app.getString(R.string.run_configuration)).performClick()
            compose.onNodeWithText(app.getString(R.string.run_entry_file)).performTextInput("main.py")
            scenario.recreate()
            compose.onNodeWithText(app.getString(R.string.run_entry_file)).assertTextContains("main.py")
            scenario.onActivity { assertEquals(RunConfiguration(), it.runConfiguration) }
            compose.onNodeWithText(app.getString(R.string.save)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.run)).assertIsEnabled()
            scenario.onActivity { assertEquals("main.py", it.runConfiguration.entryFile) }
        }
    }

}
