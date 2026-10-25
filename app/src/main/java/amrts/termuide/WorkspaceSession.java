package amrts.termuide;

import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Retained workspace coordination; DocumentSessions still owns document revisions and recovery. */
final class WorkspaceSession extends HeaderAdapter {
    static final int CLOSE_FILE = 1, CLOSE_PROJECT = 2, OPEN_PROJECT = 3;
    private String selectedPath;
    String pendingProjectPath;
    CloseRequest pendingClose;
    Set<String> approvedDiscards = Collections.emptySet();
    boolean savingClose;
    CompletableFuture<Void> sourceClose;
    ProjectClose projectClose;

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
            return Objects.equals(root, Project.rootPath) && tabs.equals(workspace)
                && tabs.stream().noneMatch(saver::isSaving)
                && revisions.equals(documentState(documents, tabs));
        }
    }

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
        if (!savingClose) pendingClose = null;
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

    List<String> openProject(File configuration) throws IOException {
        List<String> opens = new ArrayList<>();
        Project.load(configuration, opens);
        return opens;
    }

    void beginProjectClose(DocumentSessions documents) {
        List<String> tabs = new ArrayList<>(this);
        projectClose = new ProjectClose(Project.rootPath, tabs, documentState(documents, tabs),
            new HashSet<>(approvedDiscards), Project.save(tabs));
        savingClose = true;
    }

    CompletableFuture<?> pendingResult() {
        return sourceClose != null ? sourceClose : projectClose == null ? null : projectClose.result;
    }

    void clearProject() {
        Project.close();
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
        List<String> closing = state.getStringArrayList("pendingClosePaths");
        if (closing != null && !closing.isEmpty())
            pendingClose = new CloseRequest(closing, state.getInt("pendingCloseKind"), state.getString("pendingCloseTarget"));
    }

    void save(Bundle state) {
        state.putStringArrayList(MainActivity.FILES, new ArrayList<>(this));
        state.putString("selectedPath", getSelectedPath());
        state.putInt("selected", getSelectedIndex());
        state.putString("pendingProject", pendingProjectPath);
        state.putStringArrayList("pendingClosePaths", new ArrayList<>(pendingClosePaths()));
        state.putInt("pendingCloseKind", pendingClose == null ? 0 : pendingClose.kind);
        state.putString("pendingCloseTarget", pendingClose == null ? null : pendingClose.target);
    }
}
