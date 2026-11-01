package amrts.termuide

import cn.rbc.codeeditor.util.ErrSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable presentation snapshots; Compose does not inspect Fragment/document state. */
internal class WorkspacePresentation {
    data class Entry(val name: String, val icon: Int)
    data class State(
        val paths: List<String> = emptyList(), val selected: Int = -1,
        val directory: String = "", val entries: List<Entry> = emptyList(), val directoryLoading: Boolean = false,
        val fileBusy: Boolean = false, val runVisible: Boolean = false, val project: Boolean = false,
        val debug: Boolean = false, val compile: Boolean = false, val undo: Boolean = false, val redo: Boolean = false,
        val diagnostics: List<Pair<String, ErrSpan>> = emptyList(),
        val projectPrompt: String? = null, val reloadPath: String? = null, val renamePath: String? = null, val renameText: String? = null,
        val closing: List<String> = emptyList(), val savingClose: Boolean = false,
        val installPrompt: Boolean = false, val installReminder: Boolean = false,
        val initPrompt: Boolean = false, val initReminder: Boolean = false, val sourceLoading: Boolean = false, val servers: List<LanguageServers.Info> = emptyList(), val sourceUnavailable: Boolean = false
    )
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun publish(activity: MainActivity) {
        val paths = activity.openFilePaths
        val diagnostics = paths.flatMap { path ->
            val fragment = activity.fragmentManager.findFragmentByTag(path) as? EditFragment
            fragment?.view?.text?.diag.orEmpty().filter { current(it, fragment) }.map { path to it }
        }
        val selected = paths.getOrNull(activity.selectedItem)?.let { activity.fragmentManager.findFragmentByTag(it) } as? EditFragment
        val editor = activity.editor
        val prompts = activity.termuxPrompts
        mutableState.value = State(paths.toList(), activity.selectedItem, activity.directoryPath,
            activity.directoryItems.mapIndexed { index, name -> Entry(name, activity.getDirectoryIcon(index)) }, activity.isDirectoryLoading,
            activity.isDeleting, activity.isRunVisible, activity.hasProject(), activity.canDebug(), activity.canCompile(),
            editor?.text?.canUndo() == true, editor?.text?.canRedo() == true, diagnostics,
            activity.pendingProjectPath, activity.pendingReload?.file?.absolutePath, activity.pendingRename?.file?.absolutePath, activity.pendingRenameText,
            activity.pendingClosePaths.toList(), activity.isSavingClose,
            prompts.install, prompts.installNoRemind, prompts.initialize, prompts.initNoRemind,
            selected?.isLoading == true, activity.languageServerStatus.toList(), selected != null && !selected.isLoading && !selected.isSourceLoaded)
    }
    companion object {
        @JvmStatic internal fun current(diagnostic: ErrSpan, fragment: EditFragment?): Boolean =
            fragment != null && (diagnostic !is Diagnostic || diagnostic.documentVersion < 0 || diagnostic.documentVersion == fragment.version)
    }
}
