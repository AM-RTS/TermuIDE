package amrts.termuide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h800dp-mdpi")
class RunConsoleDrawerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeAndButtonToggleTheSameConsoleRun() {
        val app = Application.getInstance()
        val console = RunConsole(app)
        val title = app.getString(R.string.run_console)
        val minimize = app.getString(R.string.console_minimize)
        try {
            console.prepare(RunCommands.Plan("/bin/true", emptyList(), "/tmp"), "/bin/python") { _, _ -> }
            val runId = console.state.value.id
            console.hide()
            compose.setContent {
                val state by console.state.collectAsState()
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        CollapsedRunConsole(state, console::show)
                    }
                    RunConsoleScreen(console)
                }
            }

            compose.onNodeWithText(app.getString(R.string.console_short_connecting)).assertIsDisplayed()
            compose.onNodeWithText(title).performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
            compose.onNodeWithContentDescription(minimize).assertIsDisplayed()
            compose.onNodeWithText(title).performTouchInput { swipeDown(startY = centerY, endY = centerY + 120f) }
            compose.onNodeWithContentDescription(minimize).assertDoesNotExist()

            compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription(minimize).performClick()
            compose.onNodeWithText(app.getString(R.string.console_short_connecting)).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(runId, console.state.value.id)
                assertEquals(RunConsole.Phase.CONNECTING, console.state.value.phase)
            }
        } finally {
            compose.runOnIdle { console.shutdown() }
        }
    }
}
