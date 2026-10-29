package amrts.termuide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComposeUiStateTest {
    @Test fun invalidPortStaysInTheDialogUntilCorrected() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme {
                TextSetting("Port", "48455", validationError = {
                    if (it.toIntOrNull()?.let { port -> port in 1..65535 } == true) null else "Invalid port"
                }) { saved = it }
            }
        }
        compose.onNodeWithText("Port").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("70000")
        compose.onNodeWithText(Application.getInstance().getString(android.R.string.ok)).performClick()
        compose.onNodeWithText("Invalid port").assertIsDisplayed()
        assertNull(saved)
        compose.onNode(hasSetTextAction()).performTextReplacement("48456")
        compose.onNodeWithText(Application.getInstance().getString(android.R.string.ok)).performClick()
        compose.runOnIdle { assertEquals("48456", saved) }
    }
    @get:Rule(order = 0) val snapshots = object : ExternalResource() {
        override fun before() {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun selectionRowsExposeOneLabeledControlWithStateAndAdequateTargets() {
        val checked = mutableStateOf(false)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.selectableGroup()) {
                    SwitchSetting("Wrap", checked.value) { checked.value = it; changes++ }
                    CheckSetting("Generate config", checked.value) { checked.value = it; changes++ }
                    RadioChoice("Folder", checked.value) { checked.value = true; changes++ }
                }
            }
        }
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText("Wrap").assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        compose.onNodeWithText("Wrap").assertIsOn()
        compose.onNodeWithText("Generate config").assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        compose.onNodeWithText("Folder").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        assertEquals(1, changes)
        for (label in listOf("Wrap", "Generate config", "Folder")) {
            val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }

    @Test fun projectNameAndOptionsSurviveSavedStateRestoration() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { NewProjectDialog(activity) {} } }
        compose.onNode(hasSetTextAction()).performTextInput("kept-project")
        compose.onNodeWithText(activity.getString(R.string.with_clangd)).performClick()
        compose.onNodeWithText(activity.getString(R.string.with_format)).performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("kept-project")
        compose.onNodeWithText(activity.getString(R.string.with_clangd)).assertIsOn()
        compose.onNodeWithText(activity.getString(R.string.with_format)).assertIsOn()
    }

    @Test fun largeFontRtlControlsKeepTheirLabelsAndTouchTargets() {
        var changes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Column(Modifier.width(280.dp)) {
                        SwitchSetting("Wrap long source lines", false) { changes++ }
                        CheckSetting("Generate configuration for this project", false) { changes++ }
                        RadioChoice("Create a folder inside this directory", false) { changes++ }
                    }
                }
            }
        }
        listOf("Wrap long source lines", "Generate configuration for this project", "Create a folder inside this directory").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().performClick()
            val bounds = compose.onNodeWithText(it).getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        assertEquals(3, changes)
    }

    @Test fun newFileNameAndFolderChoiceSurviveSavedStateRestoration() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val restore = StateRestorationTester(compose)
        restore.setContent { MaterialTheme { NewFileDialog(activity) {} } }
        compose.onNode(hasSetTextAction()).performTextInput("kept-folder")
        compose.onNodeWithText(activity.getString(R.string.folder)).performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("kept-folder")
        compose.onNodeWithText(activity.getString(R.string.folder)).assertIsSelected()
    }

    @Test fun openSettingDialogAndUnconfirmedTextSurviveSavedStateRestoration() {
        val restore = StateRestorationTester(compose)
        var saved: String? = null
        restore.setContent { MaterialTheme { TextSetting("Flags", "-Wall", onSave = { saved = it }) } }
        compose.onNodeWithText("Flags").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("-Wall -Wextra")
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("-Wall -Wextra")
        assertNull(saved)
    }

    @Test fun autoSaveSettingPersistsAndTakesEffectImmediately() {
        val app = Application.getInstance()
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(Application.KEY_AUTOSAVE, false).commit()
        Application.auto_save = false
        try {
            val activity = Robolectric.buildActivity(SettingsActivity::class.java).get()
            val restore = StateRestorationTester(compose)
            restore.setContent { MaterialTheme { SettingsScreen(activity, prefs) {} } }
            compose.onNodeWithText(app.getString(R.string.auto_save)).performScrollTo().assertIsOff().performClick()
            assertTrue(prefs.getBoolean(Application.KEY_AUTOSAVE, false))
            assertTrue(Application.auto_save)
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(app.getString(R.string.auto_save)).assertIsOn().performClick()
            assertFalse(prefs.getBoolean(Application.KEY_AUTOSAVE, true))
            assertFalse(Application.auto_save)
        } finally {
            prefs.edit().putBoolean(Application.KEY_AUTOSAVE, false).commit()
            Application.auto_save = false
        }
    }

    @Test fun restoredSearchFieldsLeaveTheDirtyDocumentAndSourceIntact() {
        val app = Application.getInstance()
        app.documents.state.value.forEach { app.documents.discard(it.path) }
        Application.completion = "n"
        val directory = java.nio.file.Files.createTempDirectory("search-state").toFile()
        val file = directory.resolve("one.txt").apply { writeText("original") }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().getPreferences(0).edit().putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        controller.create().start().postCreate(null).resume()
        try {
            val activity = controller.get()
            activity.openFile(file)
            activity.fragmentManager.executePendingTransactions()
            activity.editor.paste("draft ")
            val restore = StateRestorationTester(compose)
            restore.setContent { MaterialTheme { SearchDialog(activity, file.path) {} } }
            compose.onNode(hasSetTextAction()).performTextReplacement("original")
            compose.onNodeWithText(activity.getString(R.string.replace)).performClick()
            compose.onAllNodes(hasSetTextAction())[1].performTextInput("replacement")
            restore.emulateSavedInstanceStateRestore()
            compose.onAllNodes(hasSetTextAction())[0].assertTextContains("original")
            compose.onAllNodes(hasSetTextAction())[1].assertTextContains("replacement")
            assertEquals("draft original", activity.editor.text.toString())
            assertTrue(app.documents.isDirty(file.path))
            assertEquals("original", file.readText())
        } finally {
            controller.pause().stop().destroy()
            app.documents.discard(file.path)
            app.documents.flush()
            directory.deleteRecursively()
        }
    }

    @Test fun runConfigurationRestoresDraftFieldsAndLiteralArgumentRows() {
        val app = Application.getInstance()
        val restore = StateRestorationTester(compose)
        var saved: RunConfiguration? = null
        var dismissed = false
        restore.setContent {
            MaterialTheme {
                RunConfigurationDialog("/project", RunConfiguration(), false,
                    onSave = { saved = it; true }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText(app.getString(R.string.run_entry_file)).performTextInput("src/main.py")
        compose.onNodeWithText(app.getString(R.string.run_working_directory)).performTextInput("data")
        compose.onNodeWithText(app.getString(R.string.run_add_argument)).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.run_argument, 1)).performScrollTo().performTextInput("two words;$(literal)")
        compose.onNodeWithText(app.getString(R.string.run_add_argument)).performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(app.getString(R.string.run_entry_file)).performScrollTo().assertTextContains("src/main.py")
        compose.onNodeWithText(app.getString(R.string.run_argument, 1)).performScrollTo().assertTextContains("two words;$(literal)")
        compose.onNodeWithText(app.getString(R.string.run_argument, 2)).performScrollTo().assertTextContains("")
        assertNull(saved)
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        assertEquals(RunConfiguration("src/main.py", listOf("two words;$(literal)", ""), "data"), saved)
        assertTrue(dismissed)
    }

    @Test fun runConfigurationSaveFailureKeepsDialogAndDefaultsDoNotSaveUntilConfirmed() {
        val app = Application.getInstance()
        var dismissed = false
        var attempted: RunConfiguration? = null
        compose.setContent {
            MaterialTheme {
                RunConfigurationDialog("/project", RunConfiguration("main.c", listOf("arg"), "data", ".venv"), true,
                    onSave = { attempted = it; false }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText(app.getString(R.string.run_use_defaults)).performScrollTo().performClick()
        assertNull(attempted)
        compose.onNodeWithText(app.getString(R.string.save)).performClick()
        assertEquals(RunConfiguration(pythonEnvironment = ".venv"), attempted)
        compose.onNodeWithText(app.getString(R.string.run_config_save_failed)).performScrollTo().assertIsDisplayed()
        assertFalse(dismissed)
    }

    @Test fun runConfigurationArgumentsRemainAccessibleWithLargeTextInRtl() {
        val app = Application.getInstance()
        var saved: RunConfiguration? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    RunConfigurationDialog("/project", RunConfiguration(arguments = List(8) { "value $it" }), false,
                        onSave = { saved = it; true }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.run_argument, 8)).performScrollTo().performTextReplacement("kept")
        val remove = compose.onNodeWithContentDescription(app.getString(R.string.run_remove_argument, 8)).performScrollTo()
        val bounds = remove.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        remove.performClick()
        compose.onNodeWithText(app.getString(R.string.save)).assertIsDisplayed().performClick()
        assertEquals(List(7) { "value $it" }, saved!!.arguments)
    }
    @Test fun languageToolsPreservesDraftsAndDisablesRestartUntilEnvironmentIsSaved() {
        val app = Application.getInstance()
        val restore = StateRestorationTester(compose)
        var saved: String? = null
        var succeed = false
        var restarts = 0
        val configurationError = mutableStateOf<String?>(null)
        val statuses = listOf(
            LanguageServers.Info(LanguageServers.Server.PYTHON, LanguageServers.State.MISSING, "Install pylsp", true),
            LanguageServers.Info(LanguageServers.Server.JAVA, LanguageServers.State.READY, null, true)
        )
        restore.setContent {
            MaterialTheme { LanguageToolsDialog("/project", "", statuses,
                onSave = { saved = it; succeed }, onRestart = { restarts++ }, onDismiss = {}, initialError = configurationError.value) }
        }
        compose.onNodeWithTag("lsp-restart-pylsp").performScrollTo().performClick()
        assertEquals(1, restarts)
        compose.onNodeWithTag("python-environment").performScrollTo().performTextInput(".venv")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("python-environment").performScrollTo().assertTextContains(".venv")
        compose.onNodeWithTag("lsp-restart-pylsp").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(app.getString(R.string.language_tools_java)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lsp-restart-java").performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, restarts)
        compose.onNodeWithTag("python-environment-apply").performClick()
        assertEquals(".venv", saved)
        compose.onNodeWithText(app.getString(R.string.run_config_save_failed)).performScrollTo().assertIsDisplayed()
        succeed = true
        compose.onNodeWithTag("python-environment-apply").performClick()
        compose.onNodeWithTag("python-environment-apply").assertIsNotEnabled()
        compose.onNodeWithTag("lsp-restart-pylsp").performScrollTo().assertIsEnabled()
        compose.runOnIdle { configurationError.value = "Unreadable Python configuration" }
        compose.onNodeWithTag("lsp-restart-pylsp").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("lsp-restart-java").performScrollTo().assertIsEnabled()
    }

}
