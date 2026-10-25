package amrts.termuide


import android.preference.PreferenceManager
import android.os.Message
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
import org.json.JSONArray
import org.json.JSONObject

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
        Application.navtab = false
        Application.auto_save = false
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Application.KEY_THEME, "l").putString(Application.KEY_COMPLETION, "n")
            .putBoolean(Application.KEY_NAVTAB, false).putBoolean(Application.KEY_AUTOSAVE, false).commit()
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

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun titlePickerClosesTheRequestedFileAndProtectsDirtyChanges() {
        val first = temporary.root.resolve("first.txt")
        val second = temporary.newFile("second.txt").apply { writeText("second original") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(first)
                activity.fragmentManager.executePendingTransactions()
                activity.openFile(second)
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("draft ")
            }
            compose.onNodeWithContentDescription(app.getString(R.string.open_files)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.close_file, first.path)).performClick()
            scenario.onActivity { activity ->
                assertEquals(listOf(second.path), activity.openFilePaths)
                assertEquals(second.path, activity.selectedFilePath)
                assertTrue(app.documents.isDirty(second.path))
            }
            compose.onNodeWithContentDescription(app.getString(R.string.open_files)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.close_file, second.path)).performClick()
            compose.onNodeWithText(app.getString(R.string.unsaved_changes)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(android.R.string.cancel)).performClick()
            scenario.onActivity { assertEquals(listOf(second.path), it.openFilePaths) }
            compose.onNodeWithContentDescription(app.getString(R.string.open_files)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.close_file, second.path)).performClick()
            compose.onNodeWithText(app.getString(R.string.discard)).performClick()
            scenario.onActivity { activity ->
                app.documents.flush()
                assertTrue(activity.openFilePaths.isEmpty())
                assertFalse(app.documents.isDirty(second.path))
            }
            assertEquals("second original", second.readText())
            assertEquals("original", first.readText())
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun duplicateFileNamesExposeDistinctCloseControlsAndDiscardOnlyTheirTarget() {
        val first = temporary.newFolder("one").resolve("same.c").apply { writeText("first original") }
        val second = temporary.newFolder("two").resolve("same.c").apply { writeText("second original") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(first)
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("draft ")
                activity.openFile(second)
                activity.fragmentManager.executePendingTransactions()
            }
            compose.onNodeWithContentDescription(app.getString(R.string.open_files)).performClick()
            compose.onNodeWithContentDescription(app.getString(R.string.close_file, second.path)).assertIsDisplayed()
            compose.onNodeWithContentDescription(app.getString(R.string.close_file, first.path)).performClick()
            scenario.onActivity { activity ->
                assertEquals(listOf(first.path), activity.pendingClosePaths)
                assertEquals(second.path, activity.selectedFilePath)
            }
            compose.onNodeWithText(app.getString(R.string.discard)).performClick()
            scenario.onActivity { activity ->
                assertEquals(listOf(second.path), activity.openFilePaths)
                assertEquals(second.path, activity.selectedFilePath)
                app.documents.flush()
                assertFalse(app.documents.isDirty(first.path))
            }
            assertEquals("first original", first.readText())
            assertEquals("second original", second.readText())
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun savingFromThePickerCannotCloseOrLoseTheDraftWhenWritingFails() {
        val source = temporary.root.resolve("first.txt")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(source)
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("keep this draft ")
                app.saver.shutdown()
                app.saver = DocumentSaver(app.documents) { _, _ -> throw java.io.IOException("simulated write failure") }
            }
            try {
                compose.onNodeWithContentDescription(app.getString(R.string.open_files)).performClick()
                compose.onNodeWithContentDescription(app.getString(R.string.close_file, source.path)).performClick()
                compose.onNodeWithText(app.getString(R.string.save)).performClick()
                scenario.onActivity { activity ->
                    drainSourceSaves()
                    assertEquals(listOf(source.path), activity.openFilePaths)
                    assertEquals(listOf(source.path), activity.pendingClosePaths)
                    assertTrue(app.documents.isDirty(source.path))
                    app.documents.flush()
                    val record = DraftStore(java.io.File(app.noBackupFilesDir, "documents"))
                        .readAll { throw AssertionError(it) }.single { it.session.path == source.path }
                    assertEquals("keep this draft original", record.text)
                }
                assertEquals("original", source.readText())
            } finally {
                scenario.onActivity { app.saver.shutdown(); app.saver = DocumentSaver(app.documents) }
            }
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun oldDiagnosticsAreHiddenAfterEditsAndOldVersionRepliesCannotRestoreThem() {
        val source = temporary.root.resolve("first.txt")
        val warning = JSONObject().put("message", "old diagnostic").put("severity", 2)
            .put("range", JSONObject()
                .put("start", JSONObject().put("line", 0).put("character", 0))
                .put("end", JSONObject().put("line", 0).put("character", 1)))
        val values = JSONArray().put(warning)
        var oldVersion = -1
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(source)
                activity.fragmentManager.executePendingTransactions()
                val fragment = activity.fragmentManager.findFragmentByTag(source.path) as EditFragment
                oldVersion = fragment.version
                publishDiagnostics(source, values, oldVersion)
            }
            compose.onNodeWithTag("diagnostics-button").performClick()
            compose.onNodeWithText("old diagnostic").assertIsDisplayed()
            scenario.onActivity { activity ->
                activity.editor.paste("draft ")
                publishDiagnostics(source, values, oldVersion)
            }
            compose.onNodeWithText("old diagnostic").assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.no_diagnostics)).assertIsDisplayed()
            scenario.onActivity { activity ->
                val fragment = activity.fragmentManager.findFragmentByTag(source.path) as EditFragment
                publishDiagnostics(source, JSONArray().put(warning.put("message", "fresh diagnostic")), fragment.version)
                assertTrue(app.documents.isDirty(source.path))
            }
            compose.onNodeWithText("fresh diagnostic").assertIsDisplayed()
            assertEquals("original", source.readText())
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun bottomActionsUndoRedoAndSaveTheActiveDocument() {
        val first = temporary.root.resolve("first.txt")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(first)
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("draft ")
            }
            compose.onNodeWithContentDescription(app.getString(R.string.undo)).assertIsEnabled().performClick()
            scenario.onActivity { assertEquals("original", it.editor.text.toString()) }
            compose.onNodeWithContentDescription(app.getString(R.string.redo)).assertIsEnabled().performClick()
            scenario.onActivity { assertEquals("draft original", it.editor.text.toString()) }
            compose.onNodeWithContentDescription(app.getString(R.string.save)).performClick()
            scenario.onActivity {
                drainSourceSaves()
                assertFalse(app.documents.isDirty(first.path))
            }
            assertEquals("draft original", first.readText())
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port")
    fun diagnosticsStayOnDemandUpdateLiveAndNavigateToTheirOwnFile() {
        val source = temporary.newFile("warning.c").apply { writeText("int main() {\nreturn 0;\n}\n") }
        val other = temporary.root.resolve("first.txt")
        fun diagnostic(message: String, severity: Int) = JSONObject()
            .put("message", message).put("severity", severity).put("code", "example-code")
            .put("range", JSONObject()
                .put("start", JSONObject().put("line", 1).put("character", 0))
                .put("end", JSONObject().put("line", 1).put("character", 1)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(source)
                activity.fragmentManager.executePendingTransactions()
                activity.openFile(other)
                activity.fragmentManager.executePendingTransactions()
                publishDiagnostics(source, JSONArray().put(diagnostic("sample warning", 2)))
                assertTrue(activity.editor.soraEditor.lineNumberMarginLeft > 0)
                assertTrue(activity.editor.soraEditor.dividerMarginRight > 0)
            }
            compose.onNodeWithText("sample warning").assertDoesNotExist()
            compose.onNodeWithTag("diagnostics-button").assertTextContains(app.getString(R.string.diagnostics_count, 1)).performClick()
            compose.onNodeWithText("sample warning").assertIsDisplayed()
            compose.onNodeWithText("example-code", substring = true).assertIsDisplayed()
            scenario.onActivity { publishDiagnostics(source, JSONArray()) }
            compose.onNodeWithText(app.getString(R.string.no_diagnostics)).assertIsDisplayed()
            scenario.onActivity { publishDiagnostics(source, JSONArray().put(diagnostic("sample error", 1))) }
            compose.onNodeWithText("sample error").performClick()
            compose.onNodeWithText(app.getString(R.string.no_diagnostics)).assertDoesNotExist()
            scenario.onActivity { activity ->
                assertEquals(source.path, activity.selectedFilePath)
                assertEquals(1, activity.editor.soraEditor.cursor.leftLine)
                assertEquals(0, activity.editor.soraEditor.cursor.leftColumn)
                assertFalse(app.documents.isDirty(source.path))
            }
        }
    }

    private fun publishDiagnostics(source: java.io.File, values: JSONArray, version: Int? = null) {
        val params = JSONObject().put("uri", source.toURI().toString()).put("diagnostics", values)
        if (version != null) params.put("version", version)
        app.hand.handleMessage(Message.obtain().apply {
            obj = JSONObject().put("method", "textDocument/publishDiagnostics").put("params", params).toString()
        })
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
