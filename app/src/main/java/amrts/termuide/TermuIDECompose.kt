@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package amrts.termuide

import android.content.Context
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.preference.PreferenceManager
import kotlinx.coroutines.launch
import cn.rbc.codeeditor.util.HelperUtils

object TermuIDECompose {
    @JvmStatic
    fun mainView(activity: MainActivity): View {
        val editorHost = FrameLayout(activity).apply { id = R.id.editFrag }
        return FrameLayout(activity).apply {
            // Platform fragments restore before Compose attaches to the window.
            addView(editorHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(composeView(activity) {
                TermuIDETheme(activity) { MainScreen(activity, editorHost) }
            }.apply { id = R.id.main_compose }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    @JvmStatic
    fun settingsView(activity: SettingsActivity): View = composeView(activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val theme = rememberSaveable { mutableStateOf(prefs.getString(Application.KEY_THEME, "l") ?: "l") }
        TermuIDETheme(activity, theme.value) {
            SettingsScreen(activity, prefs) { theme.value = it }
        }
    }.apply { id = R.id.settings_compose }

    @JvmStatic
    fun fileView(activity: FileActivity): View = composeView(activity) {
        TermuIDETheme(activity) { FilePickerScreen(activity) }
    }.apply { id = R.id.picker_compose }

    @JvmStatic
    fun debugView(activity: ComponentActivity, panel: DebugPanel): View = ComposeView(activity).apply {
        val owner = DebugOverlayOwner(activity)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { TermuIDETheme(activity) { DebugOverlay(activity, panel) } }
    }

    private fun composeView(context: Context, content: @Composable () -> Unit): ComposeView =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { content() }
        }
}

private class DebugOverlayOwner(activity: ComponentActivity) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val stateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = stateController.savedStateRegistry

    init {
        stateController.performAttach()
        stateController.performRestore(null)
        // The overlay remains interactive while TermuIDE is stopped behind Termux.
        registry.currentState = Lifecycle.State.RESUMED
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) registry.currentState = Lifecycle.State.DESTROYED
        })
    }
}

private val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp
)

private val LightColorScheme = lightColorScheme()
private val DarkColorScheme = darkColorScheme()

@Composable
private fun DebugOverlay(activity: ComponentActivity, panel: DebugPanel) {
    var revision by remember { mutableIntStateOf(0) }
    var command by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val moveStep = with(LocalDensity.current) { 48.dp.toPx() }
    BackHandler(enabled = panel.isDetailsOpen) { panel.closeDetails() }
    LaunchedEffect(panel.isDetailsOpen) { if (panel.isDetailsOpen) focus.requestFocus() }
    DisposableEffect(panel) {
        panel.setUiRefresh { revision++ }
        onDispose { panel.setUiRefresh(null) }
    }
    if (revision == Int.MIN_VALUE) Spacer(Modifier.size(0.dp))
    Box(
        modifier = if (panel.isDetailsOpen) Modifier.fillMaxSize() else Modifier.wrapContentSize(),
        contentAlignment = if (panel.isDetailsOpen) Alignment.Center else Alignment.CenterEnd
    ) {
        if (panel.isDetailsOpen) {
            Box(Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Back || it.key == Key.Escape)) {
                    panel.closeDetails(); true
                } else false
            }.focusRequester(focus).focusable().semantics {
                paneTitle = activity.getString(R.string.debug)
                isTraversalGroup = true
            }) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable { panel.closeDetails() }.clearAndSetSemantics { })
                Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    DebugDetails(panel, panel.sessionState, revision, command) { command = it }
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.semantics {
                    contentDescription = activity.getString(R.string.debug)
                    customActions = listOf(
                        CustomAccessibilityAction(activity.getString(R.string.move_up)) { panel.moveOverlay(0f, -moveStep); true },
                        CustomAccessibilityAction(activity.getString(R.string.move_down)) { panel.moveOverlay(0f, moveStep); true },
                        CustomAccessibilityAction(activity.getString(R.string.move_left)) { panel.moveOverlay(-moveStep, 0f); true },
                        CustomAccessibilityAction(activity.getString(R.string.move_right)) { panel.moveOverlay(moveStep, 0f); true }
                    )
                }.pointerInput(panel) {
                    detectDragGestures { _, delta -> panel.moveOverlay(delta.x, delta.y) }
                }
            ) {
                FlowRow(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    DebugControl(
                        description = activity.getString(if (panel.isStopped) R.string.cont else R.string.pause),
                        enabled = panel.isConnected,
                        action = { panel.onAction("continue") }
                    ) {
                        if (panel.isStopped) Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        else Icon(painterResource(R.drawable.ic_pause_24), contentDescription = null)
                    }
                    DebugControl(activity.getString(R.string.skip), panel.isStopped, { panel.onAction("next") }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                    DebugControl(activity.getString(R.string.step), panel.isStopped, { panel.onAction("step") }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                    DebugControl(activity.getString(R.string.fini), panel.isStopped, { panel.onAction("finish") }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    }
                    DebugControl(activity.getString(R.string.message), action = { panel.onAction("more") }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DebugControl(activity.getString(R.string.close), action = { panel.onAction("close") }) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugControl(
    description: String,
    enabled: Boolean = true,
    action: () -> Unit,
    icon: @Composable () -> Unit
) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        icon()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugDetails(panel: DebugPanel, state: DebugSession.Snapshot, revision: Int, command: String, onCommand: (String) -> Unit) {
    val context = LocalContext.current
    val breakpoints = remember(panel, revision) { panel.breakpoints }
    var selectedTab by rememberSaveable { mutableIntStateOf(panel.selectedTab) }
    Card(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = 720.dp).fillMaxHeight()
            .pointerInput(Unit) { detectTapGestures { } },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.debug), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { panel.closeDetails() }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.close) }) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            ScrollableTabRow(selectedTabIndex = selectedTab) {
                val labels = listOf(R.string.variable, R.string.frame, R.string.bkpt, R.string.console, R.string.message)
                labels.forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = {
                        selectedTab = index
                        panel.selectTab(index)
                    }, text = { Text(context.getString(label)) })
                }
            }
            when (selectedTab) {
                0 -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.variables, key = { it.id }) { variable ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                                if (variable.hasChildren) Modifier.clickable(onClickLabel = context.getString(R.string.expand_variable)) { panel.expandVariable(variable) }
                                else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        if (variable.hasChildren) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(variable.expression, style = MaterialTheme.typography.bodyLarge)
                                Text(variable.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(variable.type, style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
                1 -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(state.frames) { _, frame ->
                        Text(frame, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), style = CodeTextStyle)
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
                2 -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(breakpoints.size, key = { "$it:${breakpoints[it]}" }) { index ->
                        CheckSetting(breakpoints[index], panel.isBreakpointEnabled(index)) { panel.toggleBreakpoint(index, it) }
                    }
                }
                3 -> {
                    Text(state.console, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), style = CodeTextStyle)
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = onCommand,
                            modifier = Modifier.weight(1f),
                            label = { Text(context.getString(R.string.gdb_cmd)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { panel.sendConsole(command); onCommand("") })
                        )
                        TextButton(onClick = { panel.sendConsole(command); onCommand("") }) { Text(context.getString(R.string.send_command)) }
                    }
                }
                else -> Text(state.debug, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TermuIDETheme(
    context: Context,
    preference: String = Application.theme ?: "l",
    content: @Composable () -> Unit
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (preference) {
        "d" -> true
        "s" -> systemDark
        else -> false
    }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColorScheme else LightColorScheme
    DisposableEffect(context, dark, scheme.surface) {
        (context as? Activity)?.let { updateWindowTheme(it, dark, scheme.surface.toArgb()) }
        onDispose { }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

internal fun updateWindowTheme(activity: Activity, dark: Boolean, surface: Int) {
    Utils.setNightMode(activity, if (dark) "d" else "l")
    val window = activity.window
    window.statusBarColor = surface
    window.navigationBarColor = surface
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: MainActivity, editorHost: FrameLayout) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 840.dp
    val narrowToolbar = maxWidth < 360.dp
    val documents by Application.getInstance().documents.state.collectAsState()
    val execution by Application.getInstance().runs.state.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    var wideBrowserOpen by rememberSaveable { mutableStateOf(true) }
    val browserListState = rememberLazyListState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchPath by rememberSaveable { mutableStateOf<String?>(null) }
    var newFileOpen by rememberSaveable { mutableStateOf(false) }
    var newProjectOpen by rememberSaveable { mutableStateOf(false) }
    var runConfigurationScope by rememberSaveable { mutableStateOf<String?>(null) }
    var languageToolsScope by rememberSaveable { mutableStateOf<String?>(null) }
    var renameTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(activity) {
        activity.setUiRefresh { revision++ }
        activity.setSearchUi { searchPath = activity.selectedFilePath; searchOpen = true }
        onDispose {
            activity.setUiRefresh(null)
            activity.setSearchUi(null)
        }
    }

    if (revision < 0) return@BoxWithConstraints
        val paths = activity.openFilePaths
        val dirtyPaths = documents.filter { it.dirty }.map { it.path }.toSet()
        val labels = paths.map { path -> (if (path in dirtyPaths) "*" else "") + java.io.File(path).name }
        val selected = activity.selectedItem
        val diagnostics = paths.flatMap { path ->
            val fragment = activity.fragmentManager.findFragmentByTag(path) as? EditFragment
            fragment?.view?.text?.diag.orEmpty().filter { diagnosticIsCurrent(it, fragment) }
                .map { path to it }
        }
        val editorColors = MaterialTheme.colorScheme
        SideEffect {
            paths.forEach { path ->
                (activity.fragmentManager.findFragmentByTag(path) as? EditFragment)?.view?.setMaterialColors(
                    editorColors.surface.toArgb(), editorColors.onSurface.toArgb(),
                    editorColors.primary.toArgb(), editorColors.secondaryContainer.toArgb(),
                    editorColors.onSecondaryContainer.toArgb(), editorColors.onSurfaceVariant.toArgb(),
                    editorColors.surfaceContainerHigh.toArgb()
                )
            }
        }
        val projectPrompt = activity.pendingProjectPath
        val reloadPrompt = activity.pendingReload
        val renamePrompt = activity.pendingRename
        val runVisible = activity.isRunVisible
        val hasProject = activity.hasProject()
        val canDebug = activity.canDebug()
        val canCompile = activity.canCompile()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        OpenFilesTitle(activity, paths, labels, dirtyPaths, selected)
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (wide) wideBrowserOpen = !wideBrowserOpen else browserOpen = true }) {
                            Icon(painterResource(R.drawable.ic_folder_24), context.getString(R.string.browse_files))
                        }
                    },
                    actions = {
                        IconButton(
                            enabled = runVisible && !execution.preparing,
                            onClick = { activity.performAction(R.id.run) }
                        ) {
                            Icon(painterResource(R.drawable.ic_play_24), context.getString(R.string.run))
                        }
                        if (!narrowToolbar) IconButton(
                            enabled = selected >= 0,
                            onClick = { searchPath = activity.selectedFilePath; searchOpen = true }
                        ) {
                            Icon(painterResource(R.drawable.ic_find_replace_24), context.getString(R.string.find))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = context.getString(R.string.more_options))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (narrowToolbar) ActionMenuItem(context.getString(R.string.find), selected >= 0) {
                                    searchPath = activity.selectedFilePath; searchOpen = true; menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.undo), selected >= 0) {
                                    activity.performAction(R.id.undo); menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.redo), selected >= 0) {
                                    activity.performAction(R.id.redo); menuOpen = false
                                }
                                HorizontalDivider()
                                ActionMenuItem(context.getString(R.string.debug), canDebug && !execution.preparing) {
                                    activity.performAction(R.id.debug); menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.build), hasProject && !execution.preparing) {
                                    activity.performAction(R.id.build); menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.compile), hasProject && canCompile && !execution.preparing) {
                                    activity.performAction(R.id.compile); menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.run_configuration), true) {
                                    runConfigurationScope = activity.runConfigurationScope; menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.language_tools), true) {
                                    languageToolsScope = activity.languageToolsScope; menuOpen = false
                                }
                                HorizontalDivider()
                                ActionMenuItem(context.getString(R.string.new_f), true) {
                                    newFileOpen = true; menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.new_prj), true) {
                                    newProjectOpen = true; menuOpen = false
                                }
                                if (hasProject) {
                                    ActionMenuItem(context.getString(R.string.prj_attr), true) {
                                        activity.performAction(R.id.prj_attr); menuOpen = false
                                    }
                                    ActionMenuItem(context.getString(R.string.prj_close), true) {
                                        activity.performAction(R.id.prj_close); menuOpen = false
                                    }
                                }
                                ActionMenuItem(context.getString(R.string.close), selected >= 0) {
                                    activity.performAction(R.id.close); menuOpen = false
                                }
                                ActionMenuItem(context.getString(R.string.settings), true) {
                                    activity.performAction(R.id.settings); menuOpen = false
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                Column {
                    if (execution.stage != RunController.Stage.IDLE) {
                        val message = executionMessage(context, execution)
                        Surface(tonalElevation = 2.dp) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { Application.getInstance().runs.dismissStatus() }) {
                                    Icon(Icons.Filled.Close, context.getString(if (execution.preparing) android.R.string.cancel else R.string.runtime_dismiss))
                                }
                            }
                        }
                    }
                    if (Application.navtab && paths.isNotEmpty()) {
                        ScrollableTabRow(selectedTabIndex = selected.coerceIn(paths.indices)) {
                            paths.forEachIndexed { index, path ->
                                Tab(
                                    selected = index == selected,
                                    onClick = { activity.selectItem(index) },
                                    modifier = Modifier.semantics {
                                        stateDescription = context.getString(if (path in dirtyPaths) R.string.unsaved_changes else R.string.saved)
                                    },
                                    text = { Text(labels[index], maxLines = 1) }
                                )
                            }
                        }
                    }
                    Surface(tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { diagnosticsOpen = true }, modifier = Modifier.weight(1f).testTag("diagnostics-button")) {
                                Text(context.getString(R.string.diagnostics_count, diagnostics.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(enabled = activity.editor?.text?.canUndo() == true, onClick = { activity.performAction(R.id.undo) }) {
                                Icon(painterResource(R.drawable.ic_undo_24), context.getString(R.string.undo))
                            }
                            IconButton(enabled = activity.editor?.text?.canRedo() == true, onClick = { activity.performAction(R.id.redo) }) {
                                Icon(painterResource(R.drawable.ic_redo_24), context.getString(R.string.redo))
                            }
                            IconButton(enabled = selected >= 0, onClick = { activity.performAction(R.id.save) }) {
                                Icon(painterResource(R.drawable.ic_file_24), context.getString(R.string.save))
                            }
                        }
                    }
                    Surface(tonalElevation = 2.dp) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val keys = (listOf("⇥") + (Application.syms ?: "").map { it.toString() })
                            itemsIndexed(keys) { _, key ->
                                TextButton(
                                    enabled = selected >= 0,
                                    onClick = { activity.inputKey(key) },
                                    modifier = if (key == "⇥") Modifier.semantics { contentDescription = context.getString(R.string.insert_tab) } else Modifier,
                                ) { Text(key, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && wideBrowserOpen) {
                    Surface(Modifier.width(320.dp).fillMaxHeight().testTag("browser-pane"), tonalElevation = 1.dp) {
                        FileBrowserContent(activity, revision, browserListState,
                            onDismiss = { wideBrowserOpen = false },
                            onOpen = { activity.onFileSelected(it); browserOpen = false },
                            onNewFile = { newFileOpen = true }, onNewProject = { newProjectOpen = true },
                            onRename = { renameTarget = java.io.File(activity.directoryPath, it).absolutePath },
                            onDelete = { deleteTarget = java.io.File(activity.directoryPath, it).absolutePath })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("editor-pane")) {
                AndroidView(
                    factory = {
                        (editorHost.parent as? ViewGroup)?.removeView(editorHost)
                        editorHost
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (paths.isEmpty()) {
                    Text(
                        text = context.getString(R.string.no_open_files),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
            }
        }

        if (browserOpen && !wide) {
            FileBrowserSheet(
                activity = activity,
                revision = revision,
                state = sheetState,
                listState = browserListState,
                onDismiss = { browserOpen = false },
                onOpen = { name ->
                    val canOpen = activity.isOpenableFile(name)
                    activity.onFileSelected(name)
                    if (canOpen && activity.pendingProjectPath == null) {
                        coroutineScope.launch { sheetState.hide(); browserOpen = false }
                    }
                },
                onNewFile = { newFileOpen = true },
                onNewProject = { newProjectOpen = true },
                onRename = { renameTarget = java.io.File(activity.directoryPath, it).absolutePath },
                onDelete = { deleteTarget = java.io.File(activity.directoryPath, it).absolutePath }
            )
        }
        if (newFileOpen) NewFileDialog(activity, onDismiss = { newFileOpen = false })
        if (diagnosticsOpen) DiagnosticsSheet(diagnostics, execution, onDismiss = { diagnosticsOpen = false }) { path, diagnostic ->
            val index = activity.openFilePaths.indexOf(path)
            val fragment = activity.fragmentManager.findFragmentByTag(path) as? EditFragment
            if (!diagnosticIsCurrent(diagnostic, fragment)) return@DiagnosticsSheet
            if (index >= 0) {
                activity.selectItem(index)
                activity.editor?.let { editor ->
                    val offset = runCatching {
                        cn.rbc.codeeditor.util.TextPositions(editor.text.toString()).offset(diagnostic.stl - 1, diagnostic.stc)
                    }.getOrNull()
                    if (offset != null) editor.setSelection(offset) else editor.gotoLine(diagnostic.stl)
                    editor.requestFocus()
                }
            }
            diagnosticsOpen = false
        }
        if (newProjectOpen) NewProjectDialog(activity, onDismiss = { newProjectOpen = false })
        languageToolsScope?.let { scope ->
            key(scope) {
                val loaded = remember(scope) { runCatching { RunConfiguration.load(context, scope) } }
                LanguageToolsDialog(scope, loaded.getOrDefault(RunConfiguration()).pythonEnvironment, activity.languageServerStatus,
                    onSave = { activity.savePythonEnvironment(scope, it) },
                    onRestart = { activity.restartLanguageServer(scope, it) }, onDismiss = { languageToolsScope = null },
                    initialError = loaded.exceptionOrNull()?.let { context.getString(R.string.run_config_invalid) })
            }
        }
        runConfigurationScope?.let { scope ->
            key(scope) {
                val loaded = remember(scope) { runCatching { RunConfiguration.load(context, scope) } }
                RunConfigurationDialog(scope, loaded.getOrDefault(RunConfiguration()), hasProject,
                    onSave = { activity.saveRunConfiguration(scope, it) }, onDismiss = { runConfigurationScope = null },
                    initialError = loaded.exceptionOrNull()?.let { context.getString(R.string.run_config_invalid) })
            }
        }
        renameTarget?.let { target -> RenameDialog(activity, target) { renameTarget = null } }
        deleteTarget?.let { target -> DeleteDialog(activity, target) { deleteTarget = null } }
        if (searchOpen) searchPath?.let { path -> SearchDialog(activity, path) { searchOpen = false } }
        SetupDialogs(activity, revision)
        if (activity.pendingClosePaths.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { activity.cancelCloseDocuments() },
                title = { Text(context.getString(R.string.unsaved_changes)) },
                text = {
                    Text(
                        context.getString(R.string.save_before_close, activity.pendingClosePaths.joinToString("\n") { java.io.File(it).name }),
                        Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = { TextButton(enabled = !activity.isSavingClose, onClick = { activity.resolveCloseDocuments(true) }) { Text(context.getString(if (activity.isSavingClose) R.string.saving else R.string.save)) } },
                dismissButton = {
                    FlowRow {
                        TextButton(enabled = !activity.isSavingClose, onClick = { activity.resolveCloseDocuments(false) }) { Text(context.getString(R.string.discard)) }
                        TextButton(enabled = !activity.isSavingClose, onClick = { activity.cancelCloseDocuments() }) { Text(context.getString(android.R.string.cancel)) }
                    }
                }
            )
        }
        reloadPrompt?.let { fragment ->
            AlertDialog(
                onDismissRequest = { activity.dismissReload() },
                title = { Text(fragment.file.name) },
                text = { Text(context.getString(R.string.file_modified, fragment.file.name)) },
                confirmButton = { TextButton(onClick = { activity.confirmReload() }) { Text(context.getString(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { activity.dismissReload() }) { Text(context.getString(android.R.string.cancel)) } }
            )
        }
        renamePrompt?.let {
            key(it.file.absolutePath) { SymbolRenameDialog(activity, activity.pendingRenameText ?: "") }
        }
        projectPrompt?.let { path ->
            AlertDialog(
                onDismissRequest = { activity.dismissProjectPrompt() },
                title = { Text(context.getString(R.string.open_prj)) },
                text = { Text(context.getString(R.string.confirm_open, path)) },
                confirmButton = {
                    TextButton(onClick = { activity.confirmOpenProject() }) { Text(context.getString(R.string.cont)) }
                },
                dismissButton = {
                    TextButton(onClick = { activity.dismissProjectPrompt() }) { Text(context.getString(android.R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
internal fun RunConfigurationDialog(
    scope: String,
    initial: RunConfiguration,
    project: Boolean,
    onSave: (RunConfiguration) -> Boolean,
    onDismiss: () -> Unit,
    initialError: String? = null
) {
    val context = LocalContext.current
    var entry by rememberSaveable(scope) { mutableStateOf(initial.entryFile) }
    var directory by rememberSaveable(scope) { mutableStateOf(initial.workingDirectory) }
    var arguments by rememberSaveable(scope) { mutableStateOf(ArrayList(initial.arguments)) }
    var error by rememberSaveable(scope) { mutableStateOf(initialError) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.run_configuration)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(context.getString(R.string.run_config_scope, scope), style = MaterialTheme.typography.bodySmall)
                Text(context.getString(R.string.run_config_paths), style = MaterialTheme.typography.bodySmall)
                if (project) Text(context.getString(R.string.run_config_project), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = entry, onValueChange = { entry = it }, singleLine = true,
                    label = { Text(context.getString(R.string.run_entry_file)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = directory, onValueChange = { directory = it }, singleLine = true,
                    label = { Text(context.getString(R.string.run_working_directory)) }, modifier = Modifier.fillMaxWidth())
                Text(context.getString(R.string.run_arguments), style = MaterialTheme.typography.titleSmall)
                Text(context.getString(R.string.run_arguments_help), style = MaterialTheme.typography.bodySmall)
                arguments.forEachIndexed { index, argument ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = argument, onValueChange = { value ->
                            arguments = ArrayList(arguments).apply { set(index, value) }
                        }, singleLine = true, label = { Text(context.getString(R.string.run_argument, index + 1)) }, modifier = Modifier.weight(1f))
                        IconButton(onClick = { arguments = ArrayList(arguments).apply { removeAt(index) } },
                            modifier = Modifier.size(48.dp).semantics { contentDescription = context.getString(R.string.run_remove_argument, index + 1) }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
                TextButton(onClick = { arguments = ArrayList(arguments).apply { add("") } }) {
                    Text(context.getString(R.string.run_add_argument))
                }
                TextButton(onClick = { entry = ""; directory = ""; arguments = arrayListOf(); error = null }) {
                    Text(context.getString(R.string.run_use_defaults))
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val configuration = RunConfiguration(entry, arguments, directory, initial.pythonEnvironment)
                    if (onSave(configuration)) onDismiss() else error = context.getString(R.string.run_config_save_failed)
                } catch (_: IllegalArgumentException) { error = context.getString(R.string.run_config_invalid) }
                catch (changed: IllegalStateException) { error = changed.message ?: context.getString(R.string.run_config_invalid) }
            }) { Text(context.getString(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
internal fun LanguageToolsDialog(
    scope: String, initialEnvironment: String, statuses: List<LanguageServers.Info>,
    onSave: (String) -> Boolean, onRestart: (String) -> Unit, onDismiss: () -> Unit, initialError: String? = null
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var environment by rememberSaveable(scope) { mutableStateOf(initialEnvironment) }
    var savedEnvironment by rememberSaveable(scope) { mutableStateOf(initialEnvironment) }
    var error by rememberSaveable(scope) { mutableStateOf(initialError) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.language_tools)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(context.getString(R.string.python_environment_scope, scope), style = MaterialTheme.typography.bodySmall)
                statuses.forEach { info ->
                    val label = context.getString(when (info.server) {
                        LanguageServers.Server.PYTHON -> R.string.language_tools_python
                        LanguageServers.Server.JAVA -> R.string.language_tools_java
                        else -> R.string.language_tools_native
                    })
                    val status = context.getString(when (info.state) {
                        LanguageServers.State.CHECKING -> R.string.lsp_checking
                        LanguageServers.State.STARTING -> R.string.lsp_starting
                        LanguageServers.State.READY -> R.string.lsp_ready
                        LanguageServers.State.MISSING -> R.string.lsp_missing
                        LanguageServers.State.DISCONNECTED -> R.string.lsp_disconnected
                        LanguageServers.State.FAILED -> R.string.lsp_failed
                        LanguageServers.State.DISABLED -> R.string.lsp_disabled
                        else -> R.string.lsp_stopped
                    })
                    ListItem(headlineContent = { Text(label) }, supportingContent = {
                        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(status, modifier = Modifier.testTag("lsp-status-${info.server.id}"))
                            info.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }, trailingContent = {
                        TextButton(onClick = {
                            try { onRestart(info.server.id); haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                            catch (changed: IllegalStateException) { error = changed.message }
                        }, enabled = info.restartable && info.state !in listOf(LanguageServers.State.CHECKING, LanguageServers.State.STARTING, LanguageServers.State.DISABLED)
                            && (info.server != LanguageServers.Server.PYTHON || (initialError == null && environment == savedEnvironment)),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("lsp-restart-${info.server.id}").semantics { contentDescription = context.getString(R.string.lsp_restart_description, label) }) {
                            Text(context.getString(R.string.lsp_restart))
                        }
                    })
                }
                OutlinedTextField(value = environment, onValueChange = { environment = it }, singleLine = true,
                    label = { Text(context.getString(R.string.python_environment)) },
                    supportingText = { Text(context.getString(R.string.python_environment_help)) },
                    modifier = Modifier.fillMaxWidth().testTag("python-environment"))
                Text(context.getString(R.string.python_environment_applies), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { environment = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(context.getString(R.string.run_use_defaults)) }
                Text(context.getString(R.string.language_tools_java_help), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    if (onSave(environment)) {
                        savedEnvironment = environment; error = null
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else error = context.getString(R.string.run_config_save_failed)
                } catch (invalid: IllegalArgumentException) { error = context.getString(R.string.run_config_invalid) }
                catch (changed: IllegalStateException) { error = changed.message }
            }, enabled = initialError == null && environment != savedEnvironment, modifier = Modifier.testTag("python-environment-apply")) { Text(context.getString(R.string.language_tools_apply)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.close)) } })
}

@Composable
private fun OpenFilesTitle(activity: MainActivity, paths: List<String>, labels: List<String>, dirtyPaths: Set<String>, selected: Int) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = paths.isNotEmpty(), role = Role.Button) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = context.getString(R.string.open_files) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(labels.getOrNull(selected) ?: context.getString(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                paths.getOrNull(selected)?.let { path ->
                    Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (paths.isNotEmpty()) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded && paths.isNotEmpty(), onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 240.dp, max = 360.dp)) {
            paths.forEachIndexed { index, path ->
                val active = index == selected
                DropdownMenuItem(
                    modifier = Modifier.semantics {
                        this.selected = active
                        stateDescription = context.getString(if (path in dirtyPaths) R.string.unsaved_changes else R.string.saved)
                    },
                    text = {
                        Column {
                            Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    leadingIcon = { if (active) Icon(Icons.Filled.Check, contentDescription = null) },
                    trailingIcon = {
                        IconButton(enabled = !activity.isSavingClose, onClick = {
                            expanded = false
                            activity.closeFile(activity.openFilePaths.indexOf(path))
                        }) {
                            Icon(Icons.Filled.Close, context.getString(R.string.close_file, path))
                        }
                    },
                    onClick = { activity.selectItem(activity.openFilePaths.indexOf(path)); expanded = false }
                )
            }
        }
    }
}

private fun diagnosticIsCurrent(diagnostic: cn.rbc.codeeditor.util.ErrSpan, fragment: EditFragment?): Boolean =
    fragment != null && (diagnostic !is Diagnostic || diagnostic.documentVersion < 0 || diagnostic.documentVersion == fragment.version)

private fun executionMessage(context: Context, execution: RunController.Status): String = when (execution.stage) {
    RunController.Stage.CHECKING -> context.getString(R.string.runtime_checking)
    RunController.Stage.AWAITING_SAVE -> context.getString(R.string.runtime_saving)
    RunController.Stage.DISPATCHED -> context.getString(R.string.runtime_dispatched)
    RunController.Stage.FINISHED -> context.getString(R.string.runtime_finished, execution.exitCode ?: -1)
    else -> execution.detail.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(
    diagnostics: List<Pair<String, cn.rbc.codeeditor.util.ErrSpan>>,
    execution: RunController.Status,
    onDismiss: () -> Unit,
    onDiagnostic: (String, cn.rbc.codeeditor.util.ErrSpan) -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.diagnostics), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, context.getString(R.string.close)) }
                }
            }
            if (execution.stage != RunController.Stage.IDLE) item {
                ListItem(headlineContent = { Text(context.getString(R.string.execution_status)) },
                    supportingContent = { Text(executionMessage(context, execution)) })
                HorizontalDivider()
            }
            if (diagnostics.isEmpty()) item {
                Text(context.getString(R.string.no_diagnostics), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(diagnostics) { (path, diagnostic) ->
                val severity = context.getString(when (diagnostic.severity) {
                    0 -> R.string.diagnostic_error
                    1 -> R.string.diagnostic_warning
                    2 -> R.string.diagnostic_info
                    else -> R.string.diagnostic_hint
                })
                val code = (diagnostic as? Diagnostic)?.code?.takeUnless { it == org.json.JSONObject.NULL }
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onDiagnostic(path, diagnostic) },
                    headlineContent = { Text(diagnostic.msg.orEmpty()) },
                    supportingContent = {
                        Text(context.getString(R.string.diagnostic_location, severity, path, diagnostic.stl, diagnostic.stc + 1) +
                            (code?.let { " · $it" } ?: ""))
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ActionMenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowserSheet(
    activity: MainActivity,
    revision: Int,
    state: SheetState,
    listState: LazyListState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNewFile: () -> Unit,
    onNewProject: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            FileBrowserContent(activity, revision, listState, onDismiss, onOpen, onNewFile, onNewProject, onRename, onDelete)
        }
    }
}

@Composable
private fun FileBrowserContent(
    activity: MainActivity,
    revision: Int,
    listState: LazyListState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNewFile: () -> Unit,
    onNewProject: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    val path = remember(revision) { activity.directoryPath }
    val names = remember(revision) { activity.directoryItems }
    var storageMenu by remember { mutableStateOf(false) }
    var previousPath by rememberSaveable { mutableStateOf(path) }
    LaunchedEffect(path) {
        if (previousPath != path) { listState.scrollToItem(0); previousPath = path }
    }
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp).semantics { paneTitle = context.getString(R.string.file_browser) }) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("browser-list"), state = listState) {
            item(key = "browser-header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.file_browser), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { storageMenu = true }) {
                        Icon(painterResource(R.drawable.ic_folder_24), context.getString(R.string.storage_locations))
                    }
                    DropdownMenu(expanded = storageMenu, onDismissRequest = { storageMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.external_storage)) },
                            onClick = { activity.performAction(R.id.exstorage); storageMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.termux_storage)) },
                            onClick = { activity.performAction(R.id.tmxstorage); storageMenu = false }
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, context.getString(R.string.close)) }
            }
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onNewFile) { Text(context.getString(R.string.new_f)) }
                TextButton(onClick = onNewProject) { Text(context.getString(R.string.new_prj)) }
            }
            }
                itemsIndexed(names, key = { _, name -> "$path/$name" }) { index, name ->
                    var actions by remember(name) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(name) }.padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painterResource(activity.getDirectoryIcon(index)),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(name, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name != "..") {
                            Box {
                                IconButton(onClick = { actions = true }, modifier = Modifier.semantics {
                                    contentDescription = context.getString(R.string.file_options, name)
                                }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                                    DropdownMenuItem(text = { Text(context.getString(R.string.rename)) }, onClick = { actions = false; onRename(name) })
                                    DropdownMenuItem(text = { Text(context.getString(R.string.delete)) }, onClick = { actions = false; onDelete(name) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
            if (activity.isDeleting) {
                Text(context.getString(R.string.deleting, ""), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
}

@Composable
internal fun NewFileDialog(activity: MainActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.new_f)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it }, label = { Text(context.getString(R.string.hint_filename)) }, singleLine = true)
                Column(Modifier.selectableGroup()) {
                    RadioChoice(context.getString(R.string.file), !directory) { directory = false }
                    RadioChoice(context.getString(R.string.folder), directory) { directory = true }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { activity.createFile(name.trim(), directory); onDismiss() }) { Text(context.getString(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
internal fun NewProjectDialog(activity: MainActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val templates = remember { context.resources.getStringArray(R.array.templates).toList() }
    var name by rememberSaveable { mutableStateOf("") }
    var template by rememberSaveable { mutableStateOf(templates.firstOrNull() ?: "Clang") }
    var clangd by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.new_prj)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it }, label = { Text(context.getString(R.string.prj_name)) }, singleLine = true)
                Box {
                    TextButton(onClick = { expanded = true }) { Text("${context.getString(R.string.template)}: $template") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        templates.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { template = item; expanded = false })
                        }
                    }
                }
                CheckSetting(context.getString(R.string.with_clangd), clangd) { clangd = it }
                CheckSetting(context.getString(R.string.with_format), format) { format = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (activity.createProject(name.trim(), template, clangd, format)) onDismiss() }) { Text(context.getString(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
private fun RenameDialog(activity: MainActivity, from: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(from) { mutableStateOf(java.io.File(from).name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.rename)) },
        text = { OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { activity.renameFile(from, name.trim()); onDismiss() }) { Text(context.getString(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
private fun SymbolRenameDialog(activity: MainActivity, initial: String) {
    val context = LocalContext.current
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { activity.dismissRename() },
        title = { Text(context.getString(R.string.rename)) },
        text = { OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { activity.confirmRename(name) }) { Text(context.getString(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { activity.dismissRename() }) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
private fun DeleteDialog(activity: MainActivity, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.delete)) },
        text = { Text(context.getString(R.string.confirm_delete, java.io.File(name).name)) },
        confirmButton = { TextButton(onClick = { activity.deleteBrowserEntry(name); onDismiss() }) { Text(context.getString(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

@Composable
internal fun SearchDialog(activity: MainActivity, path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val editor = (activity.fragmentManager.findFragmentByTag(path) as? EditFragment)?.view
    if (editor == null) return
    var query by rememberSaveable(path, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(editor.selectedText)) }
    var replacement by rememberSaveable(path) { mutableStateOf("") }
    var index by rememberSaveable(path) { mutableIntStateOf(editor.selectionStart) }
    var replaceVisible by rememberSaveable(path) { mutableStateOf(false) }
    fun find(backwards: Boolean) {
        if (query.text.isEmpty()) return
        val doc = editor.text
        val from = if (backwards) index - 1 else index + 1
        var found = if (backwards) findPrevious(doc, query.text, from) else findNext(doc, query.text, from)
        if (found < 0) found = if (backwards) findPrevious(doc, query.text, doc.length - query.text.length) else findNext(doc, query.text, 0)
        if (found >= 0) {
            editor.setSelection(found, query.text.length)
            index = found
        } else {
            HelperUtils.show(android.widget.Toast.makeText(context, R.string.find_completed, android.widget.Toast.LENGTH_SHORT))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.find_)) },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_H &&
                event.nativeKeyEvent.hasModifiers(android.view.KeyEvent.META_CTRL_ON)) {
                replaceVisible = !replaceVisible
                true
            } else false
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = query,
                    onValueChange = { query = it; index = editor.selectionStart },
                    label = { Text(context.getString(R.string.find)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { find(false) })
                )
                if (replaceVisible) {
                    OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = replacement, onValueChange = { replacement = it }, label = { Text(context.getString(R.string.replace)) }, singleLine = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { find(true) }) { Text(context.getString(R.string.prev)) }
                    TextButton(onClick = { find(false) }) { Text(context.getString(R.string.next)) }
                    TextButton(onClick = { replaceVisible = !replaceVisible }) { Text(context.getString(R.string.replace)) }
                }
            }
        },
        confirmButton = {
            if (replaceVisible) TextButton(onClick = { replaceAll(editor, query.text, replacement); onDismiss() }) { Text(context.getString(R.string.replace)) }
            else TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) } }
    )
}

private fun findNext(doc: CharSequence, needle: String, from: Int): Int {
    if (needle.isEmpty()) return -1
    val start = from.coerceAtLeast(0)
    for (i in start..(doc.length - needle.length)) {
        var same = true
        for (j in needle.indices) if (doc[i + j] != needle[j]) { same = false; break }
        if (same) return i
    }
    return -1
}

private fun findPrevious(doc: CharSequence, needle: String, from: Int): Int {
    if (needle.isEmpty()) return -1
    for (i in from.coerceAtMost(doc.length - needle.length) downTo 0) {
        var same = true
        for (j in needle.indices) if (doc[i + j] != needle[j]) { same = false; break }
        if (same) return i
    }
    return -1
}

private fun replaceAll(editor: TextEditor, needle: String, replacement: String) {
    if (needle.isEmpty()) return
    val doc = editor.text
    val chars = replacement.toCharArray()
    val time = System.nanoTime()
    var cursor = 0
    doc.setTyping(false)
    doc.beginBatchEdit()
    try {
        while (cursor <= doc.length - needle.length) {
            val found = findNext(doc, needle, cursor)
            if (found < 0) break
            doc.deleteAt(found, needle.length, time)
            doc.insertBefore(chars, found, time)
            cursor = found + chars.size
        }
    } finally {
        doc.endBatchEdit()
    }
    editor.refreshDocument()
    editor.setEdited(doc.markedVersion != doc.currentVersion)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(activity: SettingsActivity, prefs: android.content.SharedPreferences, onTheme: (String) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val values = remember(revision) { prefs.all }
    fun string(key: String, fallback: String): String = values[key] as? String ?: fallback
    fun boolean(key: String, fallback: Boolean): Boolean = values[key] as? Boolean ?: fallback
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    DisposableEffect(activity) {
        activity.setUiRefresh { revision++ }
        onDispose { activity.setUiRefresh(null) }
    }
    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        if (key == Application.KEY_THEME) onTheme(value)
        revision++
    }
    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        if (key == Application.KEY_AUTOSAVE) Application.auto_save = value
        revision++
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { activity.finishSettings() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    SettingsGroup(context.getString(R.string.global)) {
                        SelectSetting(context.getString(R.string.theme), string(Application.KEY_THEME, "l") ?: "l", listOf("l" to context.getString(R.string.light), "d" to context.getString(R.string.dark), "s" to context.getString(R.string.sys))) { setString(Application.KEY_THEME, it) }
                        SwitchSetting(context.getString(R.string.pure_mode), boolean(Application.KEY_PUREMODE, false)) { setBoolean(Application.KEY_PUREMODE, it) }
                        SwitchSetting(context.getString(R.string.navtab), boolean(Application.KEY_NAVTAB, false)) { setBoolean(Application.KEY_NAVTAB, it) }
                    }
                }
                item {
                    SettingsGroup(context.getString(R.string.editor)) {
                        SwitchSetting(context.getString(R.string.auto_save), boolean(Application.KEY_AUTOSAVE, false),
                            context.getString(R.string.auto_save_summary)) { setBoolean(Application.KEY_AUTOSAVE, it) }
                        SelectSetting(context.getString(R.string.font), string(Application.KEY_FONT, "m") ?: "m", listOf("n" to context.getString(R.string.sans), "s" to context.getString(R.string.serif), "m" to context.getString(R.string.monospace), "c" to context.getString(R.string.custom))) {
                            if (it == "c") activity.pickFont() else setString(Application.KEY_FONT, it)
                        }
                        SelectSetting(context.getString(R.string.textsize), string(Application.KEY_TEXTSIZE, "14") ?: "14", (8..32).map { it.toString() to it.toString() }) { setString(Application.KEY_TEXTSIZE, it) }
                        SwitchSetting(context.getString(R.string.wordwrap), boolean(Application.KEY_WORDWRAP, false)) { setBoolean(Application.KEY_WORDWRAP, it) }
                        SwitchSetting(context.getString(R.string.whitespace), boolean(Application.KEY_WHITESPACE, false)) { setBoolean(Application.KEY_WHITESPACE, it) }
                        SwitchSetting(context.getString(R.string.usespace), boolean(Application.KEY_USESPACE, false)) { setBoolean(Application.KEY_USESPACE, it) }
                        SelectSetting(context.getString(R.string.tabsize), string(Application.KEY_TABSIZE, "4") ?: "4", listOf("2" to "2", "4" to "4", "8" to "8")) { setString(Application.KEY_TABSIZE, it) }
                        SwitchSetting(context.getString(R.string.keyboard_suggestion), boolean(Application.KEY_SUGGUESTION, false)) { setBoolean(Application.KEY_SUGGUESTION, it) }
                        SwitchSetting(context.getString(R.string.auto_caps), boolean(Application.KEY_AUTOCAPS, false)) { setBoolean(Application.KEY_AUTOCAPS, it) }
                        TextSetting(context.getString(R.string.custom_sym), string(Application.KEY_SYMS, "{}();,.=\"|&![]<>#+-/*?:_") ?: "") { setString(Application.KEY_SYMS, it) }
                    }
                }
                item {
                    SettingsGroup(context.getString(R.string.file_browser)) {
                        SwitchSetting(context.getString(R.string.show_hidden), boolean(Application.KEY_SHOW_HIDDEN, true)) { setBoolean(Application.KEY_SHOW_HIDDEN, it) }
                    }
                }
                item {
                    SettingsGroup(context.getString(R.string.compiler)) {
                        SettingAction(context.getString(R.string.check_app)) { activity.checkTermux() }
                        SettingAction(context.getString(R.string.init_termux)) { activity.initializeTermux() }
                        TextSetting(context.getString(R.string.cflags), string(Application.KEY_CFLAGS, "-lm -Wall") ?: "") { setString(Application.KEY_CFLAGS, it) }
                    }
                }
                item {
                    SettingsGroup(context.getString(R.string.analysis)) {
                        SelectSetting(context.getString(R.string.completion), string(Application.KEY_COMPLETION, "s") ?: "s", listOf("s" to context.getString(R.string.lang_serv), "l" to context.getString(R.string.builtin), "n" to context.getString(R.string.none))) { setString(Application.KEY_COMPLETION, it) }
                        TextSetting(context.getString(R.string.lsp_host), string(Application.KEY_LSP_HOST, "127.0.0.1") ?: "127.0.0.1") { setString(Application.KEY_LSP_HOST, it) }
                        TextSetting(context.getString(R.string.lsp_port), string(Application.KEY_LSP_PORT, "48455") ?: "48455", KeyboardType.Number, validationError = {
                            if (it.toIntOrNull()?.let { port -> port in 1..65535 } == true) null else context.getString(R.string.invalid_port)
                        }) { setString(Application.KEY_LSP_PORT, it) }
                    }
                }
                item {
                    SettingsGroup(context.getString(R.string.about)) {
                        SettingAction(context.getString(R.string.about_termuide)) { showAbout = true }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
    if (showAbout) AboutDialog { showAbout = false }
    SetupDialogs(activity, revision)
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selectedDocument by rememberSaveable { mutableIntStateOf(-1) }
    val documents = listOf(
        R.string.acknowledgements to "NOTICE.txt",
        R.string.license_gpl to "GPL-3.0.txt",
        R.string.license_lgpl to "LGPL-2.1.txt",
        R.string.license_apache to "Apache-2.0.txt"
    )
    val document = documents.getOrNull(selectedDocument)
    if (document != null) {
        val text = remember(document.second) {
            context.assets.open("licenses/${document.second}").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { selectedDocument = -1 },
            title = { Text(context.getString(document.first)) },
            text = {
                SelectionContainer {
                    Text(text, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { selectedDocument = -1 }) { Text(context.getString(R.string.back)) } }
        )
        return
    }
    val version = remember {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    fun openSource() {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/AM-RTS/TermuIDE")))
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, R.string.no_browser, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.about_termuide)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(context.getString(R.string.about_version, version, context.packageName), style = MaterialTheme.typography.labelLarge)
                Text(context.getString(R.string.about_fork), style = MaterialTheme.typography.bodyMedium)
                Text(context.getString(R.string.about_differences), style = MaterialTheme.typography.bodyMedium)
                Text(context.getString(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
                Text(context.getString(R.string.about_credits), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { openSource() }) { Text(context.getString(R.string.fork_source)) }
                documents.forEachIndexed { index, (title, _) ->
                    TextButton(onClick = { selectedDocument = index }) { Text(context.getString(title)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.close)) } }
    )
}

@Composable
private fun SetupDialogs(activity: MainActivity, revision: Int) {
    if (revision == Int.MIN_VALUE) return
    val context = LocalContext.current
    if (activity.isInstallPromptOpen) {
        InstallPromptDialog(
            activity.isInstallPromptNoRemind,
            onDismiss = { activity.resolveInstallPrompt(false, false) },
            onInstall = { activity.resolveInstallPrompt(true, false) },
            onNever = { activity.resolveInstallPrompt(false, true) }
        )
    } else if (activity.isInitPromptOpen) {
        InitPromptDialog(
            activity.isInitPromptNoRemind,
            onDismiss = { activity.resolveInitPrompt(false, false) },
            onInitialize = { activity.resolveInitPrompt(true, false) },
            onAlready = { activity.resolveInitPrompt(false, true) }
        )
    }
}

@Composable
private fun SetupDialogs(activity: SettingsActivity, revision: Int) {
    if (revision == Int.MIN_VALUE) return
    if (activity.isInstallPromptOpen()) {
        InstallPromptDialog(
            activity.isInstallPromptNoRemind(),
            onDismiss = { activity.resolveInstallPrompt(false, false) },
            onInstall = { activity.resolveInstallPrompt(true, false) },
            onNever = { activity.resolveInstallPrompt(false, true) }
        )
    } else if (activity.isInitPromptOpen()) {
        InitPromptDialog(
            activity.isInitPromptNoRemind(),
            onDismiss = { activity.resolveInitPrompt(false, false) },
            onInitialize = { activity.resolveInitPrompt(true, false) },
            onAlready = { activity.resolveInitPrompt(false, true) }
        )
    }
}

@Composable
private fun InstallPromptDialog(showNever: Boolean, onDismiss: () -> Unit, onInstall: () -> Unit, onNever: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.install_app)) },
        text = { Text(context.getString(R.string.confirm_install)) },
        confirmButton = { TextButton(onClick = onInstall) { Text(context.getString(android.R.string.ok)) } },
        dismissButton = {
            FlowRow {
                TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) }
                if (showNever) TextButton(onClick = onNever) { Text(context.getString(R.string.no_remind)) }
            }
        }
    )
}

@Composable
private fun InitPromptDialog(showAlready: Boolean, onDismiss: () -> Unit, onInitialize: () -> Unit, onAlready: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.init_termux)) },
        text = { Text(context.getString(R.string.init_inform)) },
        confirmButton = { TextButton(onClick = onInitialize) { Text(context.getString(R.string.copy_jump)) } },
        dismissButton = {
            FlowRow {
                TextButton(onClick = onDismiss) { Text(context.getString(android.R.string.cancel)) }
                if (showAlready) TextButton(onClick = onAlready) { Text(context.getString(R.string.initialized)) }
            }
        }
    )
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun SwitchSetting(title: String, checked: Boolean, supportingText: String? = null, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val toggle: (Boolean) -> Unit = { value ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onChange(value)
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = toggle),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun RadioChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(title, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
internal fun CheckSetting(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(title, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun SelectSetting(title: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    options.forEach { (option, name) ->
                        RadioChoice(name, option == value) { onSelect(option); expanded = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(context.getString(android.R.string.cancel)) } }
        )
    }
}

@Composable
internal fun TextSetting(title: String, value: String, keyboardType: KeyboardType = KeyboardType.Text, validationError: (String) -> String? = { null }, onSave: (String) -> Unit) {
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { editing = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
    if (editing) {
        var draft by rememberSaveable(value) { mutableStateOf(value) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = { OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = draft, onValueChange = { draft = it; error = null }, singleLine = true, isError = error != null,
                supportingText = { error?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboardType)) },
            confirmButton = { TextButton(onClick = { error = validationError(draft); if (error == null) { onSave(draft); editing = false } }) { Text(context.getString(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(context.getString(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun SettingAction(title: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(title, Modifier.fillMaxWidth()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerScreen(activity: FileActivity) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    key(activity.directoryRevision) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(context.getString(R.string.select_font))
                            Text(activity.directoryPath, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { activity.navigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back))
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                itemsIndexed(activity.directoryItems) { index, name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { activity.selectItem(name) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(painterResource(activity.getDirectoryIcon(index)), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text(name, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
            }
        }
    }
}
