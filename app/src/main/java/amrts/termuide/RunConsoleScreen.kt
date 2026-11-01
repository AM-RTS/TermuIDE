package amrts.termuide

import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunConsoleScreen(console: RunConsole) {
    val state by console.state.collectAsState()
    if (!state.visible) return
    val context = LocalContext.current
    var confirmStop by rememberSaveable(state.id) { mutableStateOf(false) }
    var rendererFailed by rememberSaveable(state.id) { mutableStateOf(false) }
    Dialog(onDismissRequest = { console.hide() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(Modifier.fillMaxSize().imePadding(), contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(title = { Text(context.getString(R.string.run_console)) }, actions = {
                    IconButton(onClick = { console.hide() }) {
                        Icon(Icons.Filled.Close, context.getString(R.string.console_hide))
                    }
                })
            }, bottomBar = {
                Column {
                    val status = when (state.phase) {
                        RunConsole.Phase.CONNECTING -> context.getString(R.string.console_connecting)
                        RunConsole.Phase.RUNNING -> context.getString(R.string.console_running)
                        RunConsole.Phase.STOPPING -> context.getString(R.string.console_stopping)
                        RunConsole.Phase.FINISHED -> context.getString(R.string.console_finished, state.exitCode ?: -1)
                        else -> context.getString(R.string.console_connection_lost)
                    }
                    Text(status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall)
                    state.detail?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { console.interrupt() }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_interrupt))
                        }
                        TextButton(onClick = { confirmStop = true }, enabled = state.active && state.phase != RunConsole.Phase.STOPPING) {
                            Text(context.getString(R.string.console_force_stop))
                        }
                        TextButton(onClick = { console.input("\u0004") }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_eof))
                        }
                        TextButton(onClick = { console.input("\t") }, enabled = state.phase == RunConsole.Phase.RUNNING) { Text("Tab") }
                        TextButton(onClick = { console.input("\u001b") }, enabled = state.phase == RunConsole.Phase.RUNNING) { Text("Esc") }
                        TextButton(onClick = { console.input("\u001b[A") }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_up))
                        }
                        TextButton(onClick = { console.input("\u001b[B") }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_down))
                        }
                        TextButton(onClick = { console.input("\u001b[D") }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_left))
                        }
                        TextButton(onClick = { console.input("\u001b[C") }, enabled = state.phase == RunConsole.Phase.RUNNING) {
                            Text(context.getString(R.string.console_right))
                        }
                    }
                }
            }) { padding ->
            val colors = MaterialTheme.colorScheme
            key(state.id) {
                if (rendererFailed) Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    Text(context.getString(R.string.console_renderer_failed), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { rendererFailed = false }) { Text(context.getString(R.string.console_retry_renderer)) }
                } else AndroidView(factory = { context ->
                    FrameLayout(context).apply {
                        try {
                            console.attachRenderer(this) { rendererFailed = true }
                        } catch (_: RuntimeException) { post { rendererFailed = true } }
                    }
                }, modifier = Modifier.fillMaxSize().padding(padding),
                    onReset = null, onRelease = { host ->
                        console.detachRenderer(host)
                    }, update = { host ->
                        (host.getChildAt(0) as? ConsoleTerminalView)?.let {
                            it.configure(colors.surface.toArgb(), colors.onSurface.toArgb(), colors.primary.toArgb())
                            it.updateInput()
                        }
                    })
            }
        }
        if (confirmStop && state.active) AlertDialog(onDismissRequest = { confirmStop = false },
            title = { Text(context.getString(R.string.console_force_stop)) },
            text = { Text(context.getString(R.string.console_stop_confirm)) },
            confirmButton = { TextButton(onClick = { confirmStop = false; console.forceStop() }) { Text(context.getString(R.string.console_force_stop)) } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(context.getString(android.R.string.cancel)) } })
    }
}
