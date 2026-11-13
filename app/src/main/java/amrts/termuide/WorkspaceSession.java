package amrts.termuide;

import android.os.Bundle;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Retained workspace coordination; DocumentSessions still owns document revisions and recovery. */
final class WorkspaceSession extends ArrayList<String> {
    static final int CLOSE_FILE = 1, CLOSE_PROJECT = 2, OPEN_PROJECT = 3, RENAME_FILE = 4, DELETE_FILE = 5;
    private String selectedPath;
    String pendingProjectPath;
    String pendingFileName;
    CloseRequest pendingClose;
    Set<String> approvedDiscards = Collections.emptySet();
    boolean savingClose;
    CompletableFuture<Void> sourceClose;
    ProjectClose projectClose;
    DebugPanel debugger;
    private String restoredProjectPath;
    CompletableFuture<Project.Config> projectRestore;

    CompletableFuture<Project.Config> restoreProject() {
        if (projectRestore != null) return projectRestore;
        String root = restoredProjectPath != null ? restoredProjectPath
            : Application.getInstance().getSharedPreferences("workspace", 0).getString("project", null);
        projectRestore = Project.snapshot().rootPath != null || root == null ? CompletableFuture.completedFuture(null)
            : Application.getInstance().fileTask(() -> Project.read(new File(root, Project.PROJ)));
        return projectRestore;
    }

    void activateProject(Project.Config config) {
        Project.publish(config);
        Application.getInstance().getSharedPreferences("workspace", 0).edit().putString("project", config.rootPath).apply();
    }

    final WorkspacePresentation presentation = new WorkspacePresentation();
    CompletableFuture<FileResult> fileOperation;
    private Runnable fileListener;
    void setFileListener(Runnable listener) { fileListener = listener; }
    static final class FileResult {
        final File[] changed;
        final Project.Config previous, created;
        int message;
        boolean configurationChanged;
        List<String> openingTabs;
        List<DocumentSession> openingRevisions;
        Set<String> openingDiscards;
        String retainedPath;
        File originDirectory;
        List<String> reopenPaths;
        String reopenSelected;
        boolean isCurrent(WorkspaceSession workspace, DocumentSessions documents, File directory) {
            return Project.snapshot() == previous && (originDirectory == null || originDirectory.equals(directory))
                && (openingTabs == null || (openingTabs.equals(workspace)
                    && Objects.equals(retainedPath, workspace.getSelectedPath())
                    && openingRevisions.equals(documents.getState().getValue())));
        }
        FileResult(int message, Project.Config previous, Project.Config created, File... changed) {
            this.message = message; this.previous = previous; this.created = created; this.changed = changed;
        }
    }
    CompletableFuture<FileResult> startFileOperation(java.util.concurrent.Callable<FileResult> task) {
        if (fileOperation != null) throw new IllegalStateException("A file operation is already running");
        Application.getInstance().getRuns().cancelPreparation();
        CompletableFuture<FileResult> pending = Application.getInstance().fileTask(task);
        fileOperation = pending;
        pending.whenComplete((result, error) -> { if (fileListener != null) fileListener.run(); });
        return pending;
    }

    void beginProjectOpen(File configuration, DocumentSessions documents, String selected) {
        Project.Config previous = Project.snapshot();
        List<String> tabs = new ArrayList<>(this);
        List<DocumentSession> revisions = new ArrayList<>(documents.getState().getValue());
        Set<String> discards = new HashSet<>(approvedDiscards);
        startFileOperation(() -> {
            FileResult result = new FileResult(0, previous, Project.read(configuration));
            result.openingTabs = tabs; result.openingRevisions = revisions;
            result.openingDiscards = discards; result.retainedPath = selected;
            return result;
        });
    }

    List<String> pathsUnder(File target) {
        java.nio.file.Path root = target.toPath().normalize();
        List<String> paths = new ArrayList<>();
        for (String path : this) if (new File(path).toPath().normalize().startsWith(root)) paths.add(path);
        return paths;
    }

    void beginFileChange(File target, String destinationName, List<String> affected, String selected) {
        java.nio.file.Path targetPath = target.toPath().normalize();
        startFileOperation(() -> {
            boolean succeeded;
            File destination = destinationName == null ? null : new File(target.getParentFile(), destinationName);
            try { succeeded = destination == null ? FileHelper.removeFiles(target) : FileHelper.rename(target, destinationName); }
            catch (RuntimeException error) { android.util.Log.e("Files", "File operation failed", error); succeeded = false; }
            WorkspaceSession.FileResult result = new WorkspaceSession.FileResult(
                destination == null ? (succeeded ? R.string.deleted : R.string.open_failed) : (succeeded ? 0 : R.string.rename_failed),
                null, null, succeeded && destination != null ? new File[]{target, destination} : new File[]{target});
            result.reopenPaths = new ArrayList<>();
            for (String path : affected) {
                String reopening = succeeded && destination != null
                    ? destination.toPath().resolve(targetPath.relativize(new File(path).toPath().normalize())).toString() : path;
                if ((destination != null || !succeeded) && FileHelper.isFile(new File(reopening))) {
                    result.reopenPaths.add(reopening);
                    if (path.equals(selected)) result.reopenSelected = reopening;
                }
            }
            if (succeeded && destination != null) {
                result.configurationChanged = true;
                try {
                    if (!RunConfiguration.relocate(Application.getInstance(), target, destination))
                        result.message = R.string.run_config_relocate_failed;
                } catch (RuntimeException error) {
                    android.util.Log.e("Files", "Unable to relocate run settings", error);
                    result.message = R.string.run_config_relocate_failed;
                }
            }
            return result;
        });
    }

    static final class CloseRequest {
        final int kind;
        final String target;
        final List<String> paths;
        CloseRequest(List<String> paths, int kind, String target) {
            this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
            this.kind = kind; this.target = target;
        }
    }

    static final class ProjectClose {
        final String root;
        final List<String> tabs;
        final List<DocumentSession> revisions;
        final Set<String> discards;
        final CompletableFuture<Boolean> result;
        ProjectClose(String root, List<String> tabs, List<DocumentSession> revisions,
                Set<String> discards, CompletableFuture<Boolean> result) {
            this.root = root; this.tabs = tabs; this.revisions = revisions;
            this.discards = discards; this.result = result;
        }
        boolean isCurrent(WorkspaceSession workspace, DocumentSessions documents, DocumentSaver saver) {
            return Objects.equals(root, Project.snapshot().rootPath) && tabs.equals(workspace)
                && tabs.stream().noneMatch(saver::isSaving)
                && revisions.equals(documentState(documents, tabs));
        }
    }

    int getCount() { return size(); }
    String getItem(int index) { return get(index); }

    String getSelectedPath() { return contains(selectedPath) ? selectedPath : null; }
    int getSelectedIndex() { return indexOf(selectedPath); }
    boolean select(int index) {
        if (index < 0 || index >= size()) return false;
        selectedPath = getItem(index);
        return true;
    }

    @Override public boolean add(String path) {
        return !contains(path) && super.add(path);
    }

    @Override public boolean remove(Object path) {
        int index = indexOf(path);
        if (index < 0) return false;
        if (Objects.equals(selectedPath, path))
            selectedPath = size() == 1 ? null : getItem(index == 0 ? 1 : index - 1);
        return super.remove(path);
    }

    @Override public void clear() {
        selectedPath = null;
        super.clear();
    }

    List<String> pendingClosePaths() {
        return pendingClose == null ? Collections.emptyList() : pendingClose.paths;
    }

    boolean requestClose(List<String> paths, int kind, String target, DocumentSessions documents) {
        List<String> dirty = new ArrayList<>();
        for (String path : paths)
            if (documents.isDirty(path) && !approvedDiscards.contains(path)) dirty.add(path);
        if (dirty.isEmpty()) return false;
        pendingClose = new CloseRequest(dirty, kind, target);
        return true;
    }

    void cancelClose() {
        if (!savingClose) { pendingClose = null; pendingFileName = null; }
    }

    CloseRequest takeClose(boolean save) {
        CloseRequest request = pendingClose;
        if (!save) approvedDiscards = new HashSet<>(request.paths);
        pendingClose = null;
        return request;
    }

    void closeDocument(String path, DocumentSessions documents) {
        if (approvedDiscards.contains(path)) documents.discard(path);
        else documents.closeDocument(path);
    }

    void beginProjectClose(DocumentSessions documents) {
        List<String> tabs = new ArrayList<>(this);
        projectClose = new ProjectClose(Project.snapshot().rootPath, tabs, documentState(documents, tabs),
            new HashSet<>(approvedDiscards), Project.save(tabs));
        savingClose = true;
    }

    CompletableFuture<?> pendingResult() {
        return sourceClose != null ? sourceClose : projectClose == null ? null : projectClose.result;
    }

    ProjectClose completeCloseSave(DocumentSessions documents, DocumentSaver saver) {
        CompletableFuture<?> pending = pendingResult();
        ProjectClose close = projectClose;
        sourceClose = null; projectClose = null; savingClose = false;
        Object saved = pending.join();
        if (close != null && !Boolean.TRUE.equals(saved))
            throw new java.util.concurrent.CompletionException(new java.io.IOException("Project save failed"));
        if (close != null ? !close.isCurrent(this, documents, saver)
                : pendingClosePaths().stream().anyMatch(documents::isDirty))
            throw new IllegalStateException("Documents changed while saving");
        return close;
    }

    void clearProject() {
        Project.close();
        restoredProjectPath = null; projectRestore = null;
        Application.getInstance().getSharedPreferences("workspace", 0).edit().remove("project").apply();
        clear();
    }

    private static List<DocumentSession> documentState(DocumentSessions documents, List<String> paths) {
        List<DocumentSession> state = new ArrayList<>();
        for (DocumentSession session : documents.getState().getValue())
            if (paths.contains(session.getPath())) state.add(session);
        return state;
    }

    void restore(Bundle state) {
        List<String> paths = state.getStringArrayList(MainActivity.FILES);
        if (paths != null) for (String path : paths) add(path);
        String selected = state.getString("selectedPath");
        if (selected != null && contains(selected)) selectedPath = selected;
        else if (!select(state.getInt("selected", 0))) select(0);
        pendingProjectPath = state.getString("pendingProject");
        restoredProjectPath = state.getString("activeProject");
        pendingFileName = state.getString("pendingFileName");
        List<String> closing = state.getStringArrayList("pendingClosePaths");
        if (closing != null && !closing.isEmpty())
            pendingClose = new CloseRequest(closing, state.getInt("pendingCloseKind"), state.getString("pendingCloseTarget"));
    }

    void save(Bundle state) {
        state.putStringArrayList(MainActivity.FILES, new ArrayList<>(this));
        state.putString("selectedPath", getSelectedPath());
        state.putInt("selected", getSelectedIndex());
        state.putString("pendingProject", pendingProjectPath);
        state.putString("activeProject", Project.snapshot().rootPath != null ? Project.snapshot().rootPath : restoredProjectPath);
        state.putString("pendingFileName", pendingFileName);
        state.putStringArrayList("pendingClosePaths", new ArrayList<>(pendingClosePaths()));
        state.putInt("pendingCloseKind", pendingClose == null ? 0 : pendingClose.kind);
        state.putString("pendingCloseTarget", pendingClose == null ? null : pendingClose.target);
    }
}
