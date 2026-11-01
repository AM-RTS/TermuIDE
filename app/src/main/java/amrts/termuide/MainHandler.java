package amrts.termuide;

import android.app.Fragment;
import static amrts.termuide.LspResponses.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import cn.rbc.codeeditor.util.Document;
import cn.rbc.codeeditor.util.TextPositions;
import cn.rbc.codeeditor.util.ErrSpan;
import cn.rbc.codeeditor.util.Tokenizer;
import cn.rbc.codeeditor.view.autocomplete.Edit;
import cn.rbc.codeeditor.view.autocomplete.ListItem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.json.*;

/** Routes JSON-RPC results to the document and revision that requested them. */
public class MainHandler extends Handler {
    private MainActivity ma;
    final Map<String, Set<String>> cacheData = new HashMap<>();
    MainHandler(MainActivity activity) { ma = activity; }
    void updateActivity(MainActivity activity) { ma = activity; }
    void languageServerChanged() { if (ma != null) ma.notifyUiChanged(); }
    CompletableFuture<Boolean> saveForRun(String path) {
        EditFragment target = fragment(path);
        if (target != null) return target.save();
        CompletableFuture<Boolean> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IOException("Document unavailable"));
        return failed;
    }
    int matchRunContext(RunCommands.Request request) { return ma == null ? 0 : ma.matchRunContext(request); }
    void startDebugger(RunCommands.Request request) { if (ma != null) ma.startDebugger(request); }
    void sourceSaved(String path, Document document, long timestamp) {
        EditFragment target = fragment(path);
        if (target != null) target.sourceSaved(document, timestamp);
    }
    void synchronize(String path) {
        EditFragment target = fragment(path);
        if (target != null) target.flushLanguageChange();
    }
    int[] selection(String path) {
        if (ma == null || ma.getEditor() == null || ma.getSelectedItem() < 0 || !path.equals(ma.getTag(ma.getSelectedItem()))) return new int[]{-1, -1};
        return new int[]{ma.getEditor().getSelectionStart(), ma.getEditor().getSelectionEnd()};
    }

    @Override public void handleMessage(Message message) {
        if (!(message.obj instanceof Lsp.Incoming)) return;
        Lsp.Incoming incoming = (Lsp.Incoming)message.obj;
        if (!incoming.isCurrent()) return;
        Lsp lsp = incoming.source;
        if (message.what == Lsp.ERROR) {
            lsp.connectionFailed();
            if (ma != null) for (String path : ma.getOpenFilePaths()) {
                EditFragment fragment = fragment(path);
                if (fragment == null || fragment.getView() == null || !Application.getInstance().languageServers.owns(lsp, fragment.getFile())) continue;
                cacheData.remove(path);
                TextEditor editor = fragment.getView();
                editor.getLanguage().setTypes(EditFragment.DEFTYPES);
                editor.getAutoCompletePanel().dismiss(); editor.getSigHelpPanel().hide();
                editor.getText().setDiag(null); editor.getText().setHighlights(null);
                editor.setSemanticTokens(Collections.emptyList());
            }
            if (ma != null) {
                ma.notifyUiChanged();
            }
            return;
        }
        if (incoming.json == null) return;
        try {
            JSONObject json = new JSONObject(incoming.json);
            String method = json.optString("method");
            if ("textDocument/publishDiagnostics".equals(method)) {
                if (ma != null) diagnostics(lsp, json.getJSONObject("params"));
                return;
            }
            if ("workspace/applyEdit".equals(method)) {
                try {
                    if (ma == null) throw new IOException("Editor unavailable");
                    Object requestId = json.get("id");
                    applyWorkspaceEdit(lsp, null, json.getJSONObject("params").getJSONObject("edit")).whenComplete((ignored, error) -> {
                        if (!incoming.isCurrent()) return;
                        try {
                            JSONObject reply = new JSONObject().put("applied", error == null);
                            if (error != null) reply.put("failureReason", error.toString());
                            lsp.reply(requestId, reply.toString());
                        } catch (JSONException replyError) { Log.e("LSP", "Unable to reply to workspace edit", replyError); }
                    });
                } catch (Exception error) {
                    lsp.reply(json.get("id"), new JSONObject().put("applied", false).put("failureReason", error.toString()).toString());
                }
                return;
            }
            if (!method.isEmpty()) {
                if (json.has("id")) {
                    if ("workspace/configuration".equals(method)) {
                        JSONArray items = json.getJSONObject("params").optJSONArray("items"), values = new JSONArray();
                        if (items != null) for (int i = 0; i < items.length(); i++) values.put(new JSONObject());
                        lsp.reply(json.get("id"), values.toString());
                    } else if ("workspace/workspaceFolders".equals(method)) {
                        JSONArray folders = new JSONArray();
                        if (lsp.root != null) folders.put(new JSONObject().put("uri", new File(lsp.root).toURI().toString()).put("name", new File(lsp.root).getName()));
                        lsp.reply(json.get("id"), folders.toString());
                    } else lsp.replyError(json.get("id"), -32601, "Unsupported client method: " + method);
                }
                return;
            }
            int id = json.optInt("id", -1);
            Lsp.Request request = lsp.takeRequest(id);
            if (request != null && !Application.getInstance().languageServers.owns(lsp, new File(request.path))) return;
            if (id >= 100 && request == null) return; // Superseded request or a previous server session.
            if (request != null && !(request.kind == Lsp.SEMTOK ? sameRevision(request.path, request.revision)
                : isCurrent(request.path, request.revision))) return;
            if (request != null && (request.kind == Lsp.COMPLETION || request.kind == Lsp.SIGN_HELP ||
                request.kind == Lsp.HIGHLIGHT || request.kind == Lsp.CODEACTION)) {
                int[] current = selection(request.path);
                if (current[0] != request.start || current[1] != request.end) return;
            }
            if (json.has("error")) {
                String detail = json.getJSONObject("error").optString("message", "Language-server error");
                if (id == Lsp.INITIALIZE) lsp.initializationFailed(detail);
                else if (ma != null) ma.toast(detail);
                return;
            }
            Object result = json.opt("result");
            if (id == Lsp.INITIALIZE && result instanceof JSONObject) {
                initialize(lsp, ((JSONObject)result).optJSONObject("capabilities"));
                lsp.initialized();
                if (ma != null) for (String path : ma.getOpenFilePaths()) {
                    EditFragment fragment = fragment(path);
                    if (fragment != null && fragment.getView() != null && Application.getInstance().languageServers.owns(lsp, fragment.getFile())) fragment.onOpen(fragment.getView().getText());
                }
                return;
            }
            if (ma == null || request == null) return;
            TextEditor editor = ma.getEditor();
            if (request.kind == Lsp.SEMTOK) {
                EditFragment target = fragment(request.path);
                if (target == null || target.getView() == null) return;
                editor = target.getView();
            }
            switch (request.kind) {
                case Lsp.COMPLETION: completion(incoming, editor, result); break;
                case Lsp.SIGN_HELP: {
                    JSONObject help = result instanceof JSONObject ? (JSONObject)result : null;
                    List<String> labels = new ArrayList<>();
                    JSONArray signatures = help == null ? null : help.optJSONArray("signatures");
                    if (signatures != null) for (int i = 0; i < signatures.length(); i++)
                        labels.add(signatures.getJSONObject(i).getString("label"));
                    editor.getAutoCompletePanel().dismiss();
                    editor.getSigHelpPanel().show(labels, help == null ? 0 : help.optInt("activeSignature"));
                    break;
                }
                case Lsp.FORMAT:
                    if (result instanceof JSONArray) apply(editor, edits(lsp, editor.getText().toString(), (JSONArray)result));
                    break;
                case Lsp.HIGHLIGHT: {
                    List<Edit> highlights = new ArrayList<>();
                    TextPositions positions = new TextPositions(editor.getText().toString());
                    if (result instanceof JSONArray) for (int i = 0; i < ((JSONArray)result).length(); i++)
                        highlights.add(range(lsp, positions, ((JSONArray)result).getJSONObject(i).getJSONObject("range"), null));
                    editor.getText().setHighlights(highlights); editor.postInvalidateOnAnimation();
                    break;
                }
                case Lsp.SEMTOK: semanticTokens(lsp, editor, result, request.path); break;
                case Lsp.DEFIN: definition(lsp, result); break;
                case Lsp.RENAM:
                    if (result instanceof JSONObject) reportWorkspaceResult(applyWorkspaceEdit(lsp, request.revisions, (JSONObject)result));
                    break;
                case Lsp.CODEACTION: codeActions(incoming, result, request); break;
            }
        } catch (Exception error) {
            Log.e("LSP", "Unable to handle language-server response", error);
            if (ma != null) ma.toast(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    private boolean isCurrent(String path, long revision) {
        if (ma == null || ma.getEditor() == null || ma.getSelectedItem() < 0 || !path.equals(ma.getTag(ma.getSelectedItem()))) return false;
        return sameRevision(path, revision);
    }

    private boolean sameRevision(String path, long revision) { return revision(path) == revision; }
    private long revision(String path) {
        String normalized = LanguageServers.path(new File(path));
        for (DocumentSession session : Application.getInstance().getDocuments().getState().getValue())
            if (normalized.equals(LanguageServers.path(new File(session.getPath())))) return session.getRevision();
        return -1;
    }

    private void completion(Lsp.Incoming incoming, TextEditor editor, Object result) throws JSONException {
        editor.getAutoCompletePanel().update(LspResponses.completion(incoming.source,
            editor.getText().toString(), editor.getCaretPosition(), result), incoming);
        if (editor.getAutoCompletePanel().isShow()) editor.getSigHelpPanel().hide();
    }

    private void diagnostics(Lsp lsp, JSONObject params) throws JSONException {
        EditFragment fragment = fragment(file(params.getString("uri")).getPath());
        if (fragment == null || fragment.getView() == null || !Application.getInstance().languageServers.owns(lsp, fragment.getFile())) return;
        if (params.has("version") && !params.isNull("version") && params.getInt("version") != fragment.getVersion()) return;
        if (lsp.documentVersion(fragment.getFile()) >= 0 && lsp.documentVersion(fragment.getFile()) != fragment.getVersion()) return;
        List<ErrSpan> diagnostics = LspResponses.diagnostics(lsp, fragment.getView().getText().toString(), fragment.getVersion(), params.getJSONArray("diagnostics"));
        fragment.getView().getText().setDiag(diagnostics); fragment.getView().postInvalidateOnAnimation();
        ma.notifyUiChanged();
    }

    private void semanticTokens(Lsp lsp, TextEditor editor, Object result, String path) throws JSONException {
        LspResponses.SemanticStyles styles = LspResponses.semanticTokens(lsp, editor.getText().toString(), result);
        cacheData.put(path, styles.types); editor.getLanguage().setTypes(styles.types); editor.setSemanticTokens(styles.tokens);
    }

    private void definition(Lsp lsp, Object result) throws JSONException {
        if (result instanceof JSONArray) result = ((JSONArray)result).opt(0);
        if (!(result instanceof JSONObject)) return;
        JSONObject location = (JSONObject)result;
        File target = file(location.has("targetUri") ? location.getString("targetUri") : location.getString("uri"));
        JSONObject range = location.optJSONObject("targetSelectionRange");
        if (range == null) range = location.getJSONObject("range");
        if (!ma.getOpenFilePaths().contains(target.getPath())) ma.openFile(target);
        else ma.selectItem(ma.getOpenFilePaths().indexOf(target.getPath()));
        ma.getFragmentManager().executePendingTransactions();
        EditFragment fragment = fragment(target.getPath());
        if (fragment == null || fragment.getView() == null) return;
        TextEditor editor = fragment.getView();
        JSONObject start = range.getJSONObject("start");
        int line = start.getInt("line"), column = start.getInt("character");
        Lsp.Incoming origin = lsp.incoming("");
        fragment.whenLoaded().thenAccept(document -> {
            if (!origin.isCurrent() || ma == null || ma.getEditor() != editor || editor.getSoraEditor().isReleased()) return;
            editor.setSelection(lsp.offset(new TextPositions(document.toString()), line, column));
            editor.requestFocus();
        });
    }

    private void codeActions(Lsp.Incoming incoming, Object result, Lsp.Request request) throws JSONException {
        List<Command> actions = new ArrayList<>();
        if (result instanceof JSONArray) for (int i = 0; i < ((JSONArray)result).length(); i++) {
            JSONObject value = ((JSONArray)result).getJSONObject(i);
            if (value.has("disabled")) continue;
            Command action = new Command(); action.title = value.getString("title"); action.edit = value.optJSONObject("edit");
            JSONObject command = value.optJSONObject("command");
            if (command == null && value.opt("command") instanceof String) command = value;
            if (command != null) { action.command = command.getString("command"); action.args = command.optJSONArray("arguments") == null ? "[]" : command.getJSONArray("arguments").toString(); }
            action.origin = incoming; action.revisions = request.revisions;
            action.path = request.path; action.revision = request.revision;
            action.start = request.start; action.end = request.end;
            if (action.edit != null || action.command != null) actions.add(action);
        }
        EditFragment fragment = fragment(request.path);
        if (fragment != null) fragment.setActions(actions);
    }

    void executeCodeAction(Command action) {
        if (!isCurrent(action.path, action.revision) || action.origin == null || !action.origin.isCurrent()
            || !Application.getInstance().languageServers.owns(action.origin.source, new File(action.path))) return;
        int[] current = selection(action.path);
        if (current[0] != action.start || current[1] != action.end) return;
        try {
            CompletableFuture<Void> applied = action.edit == null ? CompletableFuture.completedFuture(null) : applyWorkspaceEdit(action.origin.source, action.revisions, action.edit);
            long appliedRevision = revision(action.path);
            int[] appliedSelection = selection(action.path);
            reportWorkspaceResult(applied.thenRun(() -> {
                if (action.command != null && action.origin.isCurrent() && isCurrent(action.path, appliedRevision)
                    && Arrays.equals(appliedSelection, selection(action.path))) action.origin.source.executeCommand(action);
            }));
        } catch (Exception error) { Log.e("LSP", "Code action failed", error); ma.toast(error.toString()); }
    }

    private void reportWorkspaceResult(CompletableFuture<Void> result) {
        result.whenComplete((ignored, error) -> {
            if (error != null) {
                Log.e("LSP", "Unable to save workspace edit; retaining recovery", error);
                if (ma != null) ma.toast(ma.getString(R.string.save_failed));
            }
        });
    }

    private EditFragment fragment(String path) {
        if (ma == null) return null;
        String normalized = new File(path).toPath().normalize().toString();
        for (String opened : ma.getOpenFilePaths()) if (new File(opened).toPath().normalize().toString().equals(normalized)) {
            Fragment fragment = ma.getFragmentManager().findFragmentByTag(opened);
            return fragment instanceof EditFragment ? (EditFragment)fragment : null;
        }
        return null;
    }

    private static File file(String uri) {
        Uri parsed = Uri.parse(uri);
        if (!"file".equals(parsed.getScheme()) || parsed.getPath() == null) throw new IllegalArgumentException("Unsupported document URI");
        String path = parsed.getPath();
        if (path.startsWith(Utils.PREF) && !path.startsWith("/home", Utils.PREF.length())) path = Utils.PREF + "/home/.." + path.substring(Utils.PREF.length());
        return new File(path);
    }

    private static void apply(TextEditor editor, List<Edit> edits) {
        int caret = EditorTextEdits.apply(editor.getText(), edits, editor.getCaretPosition());
        editor.refreshDocument(); editor.setSelection(caret);
    }

    private static final class WorkspaceChange {
        final File file;
        final TextEditor editor;
        final Document document;
        final List<Edit> edits;
        final Supplier<String> source;
        final Long modified;
        WorkspaceChange(File file, TextEditor editor, Document document, List<Edit> edits) { this(file, editor, document, edits, null); }
        WorkspaceChange(File file, TextEditor editor, Document document, List<Edit> edits, Long modified) {
            this.modified = modified;
            this.file = file; this.editor = editor; this.document = document; this.edits = edits;
            this.source = document::toString;
        }
    }

    CompletableFuture<Void> applyWorkspaceEdit(JSONObject workspace) throws IOException, JSONException {
        return applyWorkspaceEdit(null, null, workspace);
    }
    private void validateWorkspaceTarget(Lsp lsp, Map<String, Long> revisions, File target, EditFragment opened) throws IOException {
        if (revisions != null && revisions.containsKey(LanguageServers.path(target))
            && !sameRevision(target.getAbsolutePath(), revisions.get(LanguageServers.path(target))))
            throw new IOException("Workspace document changed or closed after the request");
        Application app = Application.getInstance();
        if (ma != null && ma.isDeleting()) throw new IOException("Workspace files are changing");
        if (app.saver.isSaving(target.getAbsolutePath())) throw new IOException("Document is being saved");
        Lsp owner = app.languageServers.find(target);
        if (lsp != null && owner != null && owner != lsp) throw new IOException("Workspace document belongs to another server session");
        if (opened == null || opened.getView() == null) {
            for (DocumentSession session : app.getDocuments().getState().getValue())
                if (session.getDirty() && new File(session.getPath()).toPath().normalize().equals(target.toPath().normalize()))
                    throw new IOException("Open the recovered document before applying workspace edits");
        } else if (opened.isLoading()) throw new IOException("Document is still loading");
    }

    CompletableFuture<Void> applyWorkspaceEdit(Lsp lsp, Map<String, Long> revisions, JSONObject workspace) throws IOException, JSONException {
        Map<String, JSONArray> changes = workspaceChanges(workspace);
        Map<String, File> closed = new LinkedHashMap<>();
        for (String uri : changes.keySet()) {
            File target = file(uri); EditFragment opened = fragment(target.getPath());
            validateWorkspaceTarget(lsp, revisions, target, opened);
            if (opened == null || opened.getView() == null) closed.put(uri, target);
        }
        if (closed.isEmpty()) return prepareWorkspaceEdit(lsp, revisions, workspace, changes, Collections.emptyMap());
        Application app = Application.getInstance();
        List<DocumentSession> before = new ArrayList<>(app.getDocuments().getState().getValue());
        Lsp.Incoming origin = lsp == null ? null : lsp.incoming("");
        return app.fileTask(() -> {
            Map<String, FileHelper.Source> sources = new LinkedHashMap<>();
            for (Map.Entry<String, File> entry : closed.entrySet()) sources.put(entry.getKey(), FileHelper.readSource(entry.getValue()));
            return sources;
        }).thenCompose(sources -> {
            try {
                if (!before.equals(app.getDocuments().getState().getValue()) || (origin != null && !origin.isCurrent()))
                    throw new IOException("Workspace changed while reading sources");
                return prepareWorkspaceEdit(lsp, revisions, workspace, changes, sources);
            } catch (IOException | JSONException | RuntimeException error) {
                CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(error); return failed;
            }
        });
    }

    private CompletableFuture<Void> prepareWorkspaceEdit(Lsp lsp, Map<String, Long> revisions, JSONObject workspace,
            Map<String, JSONArray> changes, Map<String, FileHelper.Source> sources) throws IOException, JSONException {
        JSONArray documents = workspace.optJSONArray("documentChanges");
        if (documents != null) for (int i = 0; i < documents.length(); i++) {
            JSONObject change = documents.getJSONObject(i), document = change.optJSONObject("textDocument");
            if (document == null) throw new IOException("File resource operations are not supported");
            EditFragment fragment = fragment(file(document.getString("uri")).getPath());
            if (fragment != null && document.has("version") && !document.isNull("version") && document.getInt("version") != fragment.getVersion())
                throw new IOException("Workspace edit refers to an older document version");
        }
        DocumentSessions sessions = Application.getInstance().getDocuments();
        List<WorkspaceChange> prepared = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JSONArray> change : changes.entrySet()) {
            File file = file(change.getKey()); EditFragment fragment = fragment(file.getPath());
            validateWorkspaceTarget(lsp, revisions, file, fragment);
            if (!seen.add(file.toPath().normalize().toString())) throw new IOException("Duplicate workspace document");
            if (fragment != null && fragment.getView() != null) {
                Document document = fragment.getView().getText();
                if (revisions != null) {
                    Long expected = revisions.get(LanguageServers.path(file));
                    if (expected == null || !sameRevision(fragment.getFile().getAbsolutePath(), expected))
                        throw new IOException("Workspace document changed after the request");
                }
                String synchronizedText = lsp == null ? null : lsp.text(fragment.getFile());
                if (synchronizedText != null && !synchronizedText.equals(document.toString()))
                    throw new IOException("Workspace document has pending local edits");
                List<Edit> edits = edits(lsp, document.toString(), change.getValue());
                EditorTextEdits.validate(document, edits);
                prepared.add(new WorkspaceChange(file, fragment.getView(), document, edits));
                continue;
            }
            FileHelper.Source source = sources.get(change.getKey());
            if (source == null) throw new IOException("Workspace document closed while preparing edits");
            String original = source.text;
            Document document = new Document(null); document.setText(original); document.clearSpans(); document.analyzeWordWrap();
            List<Edit> edits = edits(lsp, original, change.getValue());
            EditorTextEdits.validate(document, edits);
            prepared.add(new WorkspaceChange(file, null, document, edits, source.modified));
        }
        for (WorkspaceChange change : prepared) {
            if (change.editor != null) apply(change.editor, change.edits);
            else {
                EditorTextEdits.apply(change.document, change.edits, 0);
                sessions.open(change.file.getAbsolutePath(), change.source, true);
                sessions.changed(change.file.getAbsolutePath());
            }
        }
        List<CompletableFuture<Boolean>> writes = new ArrayList<>();
        for (WorkspaceChange change : prepared) if (change.editor == null) {
            String path = change.file.getAbsolutePath();
            writes.add(Application.getInstance().saver.save(path, change.document.toString(), change.modified, timestamp -> {
                Application.getInstance().sourceSaved(path, change.document, timestamp);
                sessions.closeIfSource(path, change.source);
            }));
        }
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture[0]));
    }
}
