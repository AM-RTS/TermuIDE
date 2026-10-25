package amrts.termuide

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.preference.PreferenceManager
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ComposeRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var directory: File

    @Before
    fun prepare() {
        Application.getInstance().documents.let { sessions ->
            sessions.state.value.forEach { sessions.discard(it.path) }
            sessions.flush()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } else {
            shell("pm grant ${context.packageName} android.permission.READ_EXTERNAL_STORAGE")
            shell("pm grant ${context.packageName} android.permission.WRITE_EXTERNAL_STORAGE")
        }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val external = requireNotNull(context.getExternalFilesDir(null)) { "The test device needs mounted external storage" }
        directory = File(external, "compose-regression").apply { check(mkdirs() || isDirectory) }
        File(directory, "nested.c").mkdirs()
        File(directory, "first.txt").writeText("first document")
        File(directory, "second.txt").writeText("second document")
        File(directory, "font.ttf").writeText("font picker fixture")
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
            .putString(MainActivity.PWD, directory.path)
            .putBoolean(MainActivity.INITAPP, false).putBoolean(MainActivity.TESTAPP, false).commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear()
            .putString(Application.KEY_THEME, "l").putString(Application.KEY_COMPLETION, "n").commit()
        Application.theme = "l"
        Application.completion = "n"
    }

    @After
    fun removeFixtures() {
        if (::directory.isInitialized) {
            listOf("first.txt", "second.txt", "font.ttf", "nested.c").forEach { File(directory, it).delete() }
            directory.delete()
        }
    }

    @Test
    fun activityRecreationRestoresOpenFileDialogAndName() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(context.getString(R.string.more_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.new_f)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("kept-folder")
            compose.onNodeWithText(context.getString(R.string.folder)).performClick()
            scenario.recreate()
            compose.onNode(hasSetTextAction()).assertTextContains("kept-folder")
            compose.onNodeWithText(context.getString(R.string.folder)).assertIsSelected()
            assertFalse(File(directory, "kept-folder").exists())
        }
    }

    @Test
    fun searchFieldsRestoreWithoutChangingTheRecoveredDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("draft ")
                activity.performAction(R.id.search)
            }
            compose.onNode(hasSetTextAction()).performTextReplacement("document")
            compose.onNodeWithText(context.getString(R.string.replace)).performClick()
            compose.onAllNodes(hasSetTextAction())[1].performTextInput("replacement")
            scenario.recreate()
            compose.onAllNodes(hasSetTextAction())[0].assertTextContains("document")
            compose.onAllNodes(hasSetTextAction())[1].assertTextContains("replacement")
            scenario.onActivity { assertEquals("draft first document", it.editor.text.toString()) }
            assertEquals("first document", File(directory, "first.txt").readText())
        }
    }

    @Test
    fun soraInputUsesTheRecoveryDocumentAndReleasesTheOldView() {
        lateinit var oldEditor: io.github.rosemoe.sora.widget.CodeEditor
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                val editor = activity.editor
                oldEditor = editor.soraEditor
                editor.setSelection(0)
                oldEditor.commitText("🧪 draft\n", false, false)
                assertEquals(oldEditor.text.toString(), editor.text.toString())
                assertTrue(editor.isEdited)
                Application.getInstance().documents.flush()
                assertEquals("first document", File(directory, "first.txt").readText())
                editor.undo()
                assertEquals("first document", oldEditor.text.toString())
                assertFalse(editor.isEdited)
                editor.redo()
                assertEquals(editor.text.toString(), oldEditor.text.toString())
                assertTrue(editor.isEdited)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(oldEditor.isReleased)
                assertEquals("🧪 draft\nfirst document", activity.editor.soraEditor.text.toString())
                assertTrue(activity.editor.isEdited)
            }
        }
    }

    @Test
    fun soraCompletionPreservesAdditionalEditsCaretAndUndo() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                val editor = activity.editor
                editor.setSelection(5)
                val primary = cn.rbc.codeeditor.view.autocomplete.Edit().apply {
                    start = 0; len = 5; text = "completed"
                }
                val additional = cn.rbc.codeeditor.view.autocomplete.Edit().apply {
                    start = 14; len = 0; text = "!"
                }
                val item = cn.rbc.codeeditor.view.autocomplete.ListItem().apply {
                    label = "completed"; edits.add(primary); edits.add(additional)
                }
                editor.autoCompletePanel.update(arrayListOf(item))
                editor.autoCompletePanel.select(0)
                assertEquals("completed document!", editor.soraEditor.text.toString())
                assertEquals(editor.text.toString(), editor.soraEditor.text.toString())
                assertEquals(9, editor.selectionStart)
                editor.undo()
                assertEquals("first document", editor.soraEditor.text.toString())
                assertFalse(editor.isEdited)
            }
        }
    }

    @Test
    fun recreationKeepsBothEditorViewsAndUnsavedText() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.openFile(File(directory, "second.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("unsaved ")
            }
            compose.waitForIdle()
            scenario.recreate()
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val host = activity.findViewById<ViewGroup>(R.id.editFrag)
                assertEquals(2, host.childCount)
                for (name in listOf("first.txt", "second.txt")) {
                    val fragment = activity.fragmentManager.findFragmentByTag(File(directory, name).path)!!
                    assertSame(host, fragment.view!!.parent)
                    assertTrue(fragment.view!!.isAttachedToWindow)
                }
                assertTrue(activity.editor.text.toString().contains("unsaved"))
                assertTrue(activity.editor.width > 0)
            }
        }
    }

    @Test
    fun overlayComposesAndReattachesWhileActivityIsStopped() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var overlay: ComposeView
            lateinit var windowManager: WindowManager
            scenario.onActivity { activity ->
                overlay = TermuIDECompose.debugView(activity, DebugPanel(activity)) as ComposeView
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val params = WindowManager.LayoutParams(
                400, 100,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
            )
            try {
                repeat(2) {
                    instrumentation.runOnMainSync { windowManager.addView(overlay, params) }
                    compose.waitUntil(5_000) {
                        var composed = false
                        instrumentation.runOnMainSync { composed = overlay.childCount > 0 && overlay.width > 0 }
                        composed
                    }
                    instrumentation.runOnMainSync {
                        assertNotNull(overlay.findViewTreeSavedStateRegistryOwner())
                        assertEquals(Lifecycle.State.RESUMED, overlay.findViewTreeLifecycleOwner()!!.lifecycle.currentState)
                        assertTrue(overlay.childCount > 0)
                        windowManager.removeViewImmediate(overlay)
                    }
                }
            } finally {
                instrumentation.runOnMainSync { if (overlay.isAttachedToWindow) windowManager.removeViewImmediate(overlay) }
            }
        }
    }

    @Test
    fun fontPickerRefreshesAfterSystemAndToolbarBack() {
        val intent = Intent(context, FileActivity::class.java).putExtra(FileActivity.FN, File(directory, "font.ttf").path)
        ActivityScenario.launch<FileActivity>(intent).use { scenario ->
            compose.onNodeWithText("nested.c").performClick()
            compose.onNodeWithText(File(directory, "nested.c").path).assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(directory.path).assertExists()
            compose.onNodeWithText("font.ttf").assertIsDisplayed()
            compose.onNodeWithText("nested.c").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.onNodeWithText(directory.path).assertIsDisplayed()
        }
    }

    @Test
    fun browserStaysOpenForFoldersAndClosesForFiles() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(context.getString(R.string.browse_files)).performClick()
            compose.onNodeWithText("nested.c").performClick()
            compose.onNodeWithText(context.getString(R.string.file_browser)).assertIsDisplayed()
            compose.onNodeWithText(File(directory, "nested.c").path).assertExists()
            compose.onNodeWithText("..").performClick()
            compose.onNodeWithText(directory.path).assertExists()
            compose.onNodeWithText("first.txt").performClick()
            compose.onNodeWithText(directory.path).assertDoesNotExist()
        }
    }

    @Test
    fun hardwareShortcutsSaveUndoRedoFindAndReplace() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.editor.requestFocus()
                activity.editor.paste("change ")
                val edited = activity.editor.text.toString()
                assertTrue(shortcut(activity, KeyEvent.KEYCODE_Z))
                assertFalse(activity.editor.text.toString().contains("change"))
                assertTrue(shortcut(activity, KeyEvent.KEYCODE_Z, KeyEvent.META_SHIFT_ON))
                assertEquals(edited, activity.editor.text.toString())
                assertTrue(shortcut(activity, KeyEvent.KEYCODE_S))
                assertEquals(edited, File(directory, "first.txt").readText())
                assertFalse(shortcut(activity, KeyEvent.KEYCODE_R)) // Text files cannot run.
                assertTrue(shortcut(activity, KeyEvent.KEYCODE_F))
            }
            compose.onNodeWithText(context.getString(R.string.find_)).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performClick().performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.H) }
            }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        }
    }

    @Test
    fun changingPreferenceKeepsScrolledRowVisible() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val label = context.getString(R.string.show_hidden)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val before = compose.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText(label).assertIsDisplayed()
            val after = compose.onNodeWithText(label).fetchSemanticsNode().positionInRoot.y
            assertEquals(before, after, 1f)
            assertFalse(PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Application.KEY_SHOW_HIDDEN, true))
        }
    }

    @Test
    fun runButtonUpdatesWhenAvailabilityChanges() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val run = compose.onNodeWithContentDescription(context.getString(R.string.run))
            run.assertIsNotEnabled()
            scenario.onActivity { it.setFileRunnable(true) }
            run.assertIsEnabled()
            scenario.onActivity { it.setFileRunnable(false) }
            run.assertIsNotEnabled()
        }
    }

    @Test
    fun lightThemeUsesDarkSystemBarIcons() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.decorView.systemUiVisibility
                assertTrue(flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0)
                assertTrue(flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0)
            }
        }
    }

    @Test
    fun darkThemeUsesLightSystemBarIcons() {
        Application.theme = "d"
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(Application.KEY_THEME, "d").commit()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.decorView.systemUiVisibility
                assertEquals(0, flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                assertEquals(0, flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
            }
        }
    }

    @Test
    fun minimumSdkAndFloatingClipboardMatchAndroid81Baseline() {
        assertEquals(Build.VERSION_CODES.O_MR1, context.applicationInfo.minSdkVersion)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.editor.requestFocus()
                val panel = amrts.termuide.ClipboardPanel(activity.editor)
                var actionMode: ActionMode? = null
                panel.setCustomCallback(object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        actionMode = mode
                        return true
                    }
                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                    override fun onDestroyActionMode(mode: ActionMode) {}
                }, amrts.termuide.ClipboardPanel.CREATE_BEFORE)
                panel.show()
                assertNotNull(actionMode)
                assertEquals(ActionMode.TYPE_FLOATING, actionMode!!.type)
                panel.invalidateContentRect()
                panel.hide()
            }
        }
    }

    @Test
    fun textSlicesConvertWithoutRecursionOrTrailingSentinel() {
        val buffer = cn.rbc.codeeditor.util.TextBuffer()
        buffer.setSpans(arrayListOf(cn.rbc.codeeditor.util.Pair(0, 0)))
        assertEquals("", buffer.toString())
        buffer.insert("hello".toCharArray(), 0, System.nanoTime(), false)
        assertEquals("hello", buffer.toString())
        assertEquals("ell", buffer.subSequence(1, 4).toString())
        buffer.insert(" X ".toCharArray(), 2, System.nanoTime(), true)
        assertEquals("he X llo", buffer.toString())
        assertEquals("e X l", buffer.subSequence(1, 6).toString())
        buffer.markUnsaved()
        assertNotEquals(buffer.markedVersion, buffer.currentVersion)
        buffer.markVersion()
        assertEquals(buffer.markedVersion, buffer.currentVersion)
    }

    @Test
    fun launchRestoresMultipleDraftsWithoutCachedEditorDocuments() {
        val sessions = Application.getInstance().documents
        listOf("first.txt", "second.txt").forEach { name ->
            val path = File(directory, name).path
            val source = java.util.function.Supplier { "recovered $name" }
            sessions.open(path, source)
            sessions.changed(path)
            sessions.detach(path, source)
        }
        sessions.flush()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                assertEquals(2, activity.itemCount)
                listOf("first.txt", "second.txt").forEach { name ->
                    val fragment = activity.fragmentManager.findFragmentByTag(File(directory, name).path) as EditFragment
                    assertEquals("recovered $name", fragment.view!!.text.toString())
                    assertTrue(fragment.view!!.isEdited)
                }
            }
        }
        assertEquals("first document", File(directory, "first.txt").readText())
        assertEquals("second document", File(directory, "second.txt").readText())
    }

    @Test
    fun closingDirtyTabRequiresSaveDiscardOrCancel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openFile(File(directory, "first.txt"))
                activity.fragmentManager.executePendingTransactions()
                activity.editor.paste("unsaved ")
                activity.closeFile(activity.selectedItem)
                assertFalse(activity.pendingClosePaths.isEmpty())
            }
            compose.onNodeWithText(context.getString(R.string.unsaved_changes)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
            scenario.onActivity { activity ->
                assertEquals(1, activity.itemCount)
                assertTrue(activity.editor.isEdited)
                activity.closeFile(activity.selectedItem)
            }
            compose.onNodeWithText(context.getString(R.string.discard)).performClick()
            scenario.onActivity { assertEquals(0, it.itemCount) }
            assertEquals("first document", File(directory, "first.txt").readText())
            Application.getInstance().documents.flush()
            assertTrue(Application.getInstance().documents.state.value.isEmpty())
        }
    }

    @Test
    fun termuxDraftIsEditableAfterStorageGrantIsLost() {
        val app = Application.getInstance()
        val originalUri = app.treeUri
        val path = "/data/data/com.termux/files/home/recovered.txt"
        val source = java.util.function.Supplier { "keep the inaccessible source untouched" }
        app.documents.open(path, source)
        app.documents.changed(path)
        app.documents.detach(path, source)
        app.documents.flush()
        app.treeUri = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForIdle()
                lateinit var saving: java.util.concurrent.CompletableFuture<Boolean>
                scenario.onActivity { activity ->
                    val fragment = activity.fragmentManager.findFragmentByTag(path) as EditFragment
                    assertEquals("keep the inaccessible source untouched", fragment.view!!.text.toString())
                    saving = fragment.save()
                }
                try {
                    saving.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    fail("Saving needs a storage grant")
                } catch (_: java.util.concurrent.ExecutionException) { }
                scenario.onActivity { activity ->
                    val fragment = activity.fragmentManager.findFragmentByTag(path) as EditFragment
                    assertTrue(app.documents.isDirty(path))
                    assertTrue(fragment.view!!.isEdited)
                }
            }
        } finally { app.treeUri = originalUri }
    }

    @Test
    fun failedProjectSwitchKeepsExistingDirtyDocumentsRecoverable() {
        val broken = File(directory, "broken").apply { mkdirs() }
        File(broken, Project.PROJ).writeText("not valid JSON")
        File(broken, "readme.txt").writeText("project file")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.openFile(File(directory, "first.txt"))
                    activity.fragmentManager.executePendingTransactions()
                    activity.editor.paste("unsaved ")
                    activity.onFileSelected("broken")
                    activity.onFileSelected("readme.txt")
                    activity.fragmentManager.executePendingTransactions()
                }
                compose.onNodeWithText(context.getString(R.string.cont)).performClick()
                compose.onNodeWithText(context.getString(R.string.discard)).performClick()
                scenario.onActivity { activity ->
                    assertEquals(2, activity.itemCount)
                    assertTrue(Application.getInstance().documents.isDirty(File(directory, "first.txt").path))
                }
                Application.getInstance().documents.flush()
            }
        } finally {
            File(broken, Project.PROJ).delete()
            File(broken, "readme.txt").delete()
            broken.delete()
        }
    }

    private fun shortcut(activity: MainActivity, code: Int, modifiers: Int = 0): Boolean =
        activity.dispatchKeyShortcutEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or modifiers))

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
