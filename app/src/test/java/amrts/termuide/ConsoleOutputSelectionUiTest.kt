package amrts.termuide

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConsoleOutputSelectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun copyAllCopiesPlainUnicodeAndActualNewlinesWithFeedback() {
        val app = Application.getInstance()
        val text = "first line\n界🙂 \"quoted\" \\path"
        compose.setContent { MaterialTheme { ConsoleOutputSelection(RunConsole.OutputSelection(text)) } }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.console_copy_all)).performClick()
        compose.runOnIdle {
            assertEquals(text, app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText(app.getString(R.string.console_selection_copied)).assertIsDisplayed()
    }

    @Test fun shortWindowsWithLargeTextCanReachBothCopyAndOutput() {
        val app = Application.getInstance()
        val text = "Selectable output"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.size(width = 240.dp, height = 120.dp)) {
                        ConsoleOutputSelection(RunConsole.OutputSelection(text))
                    }
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.console_copy_all)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
