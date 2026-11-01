package amrts.termuide;

import cn.rbc.codeeditor.util.TextPositions;
import cn.rbc.codeeditor.util.ErrSpan;
import cn.rbc.codeeditor.util.Tokenizer;
import cn.rbc.codeeditor.view.autocomplete.Edit;
import cn.rbc.codeeditor.view.autocomplete.ListItem;
import java.util.*;
import org.json.*;

/** Protocol decoding takes text/positions, never an Activity or editor view. */
final class LspResponses {
    private LspResponses() {}
    static void initialize(Lsp lsp, JSONObject capabilities) throws JSONException {
        lsp.setCapabilities(capabilities);
        if (capabilities == null) return;
        lsp.setCompTrigs(triggers(capabilities.optJSONObject("completionProvider")));
        lsp.setSigTrigs(triggers(capabilities.optJSONObject("signatureHelpProvider")));
        JSONObject semantic = capabilities.optJSONObject("semanticTokensProvider");
        JSONObject legend = semantic == null ? null : semantic.optJSONObject("legend");
        JSONArray names = legend == null ? null : legend.optJSONArray("tokenTypes");
        Lsp.SemToken[] types = new Lsp.SemToken[names == null ? 0 : names.length()];
        for (int i = 0; i < types.length; i++) types[i] = Lsp.semValueOf(names.getString(i).toUpperCase(Locale.ROOT));
        lsp.setSemToks(types);
    }

    private static char[] triggers(JSONObject provider) throws JSONException {
        JSONArray values = provider == null ? null : provider.optJSONArray("triggerCharacters");
        StringBuilder text = new StringBuilder();
        if (values != null) for (int i = 0; i < values.length(); i++) text.append(values.getString(i));
        return text.toString().toCharArray();
    }

    static List<ListItem> completion(Lsp lsp, String text, int caret, Object result) throws JSONException {
        JSONArray values = result instanceof JSONArray ? (JSONArray)result
            : result instanceof JSONObject ? ((JSONObject)result).optJSONArray("items") : null;
        ArrayList<ListItem> items = new ArrayList<>();
        TextPositions positions = new TextPositions(text);
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if (value.optInt("insertTextFormat", 1) != 1) continue;
            ListItem item = new ListItem(); item.label = value.getString("label"); item.kind = value.optInt("kind", 1);
            JSONObject edit = value.optJSONObject("textEdit");
            if (edit != null) {
                JSONObject bounds = edit.optJSONObject("range");
                if (bounds == null) bounds = edit.getJSONObject("replace");
                item.edits.add(range(lsp, positions, bounds, edit.getString("newText")));
            } else if (value.has("insertText")) {
                int end = caret, start = completionStart(text, end, item.kind);
                Edit insertion = new Edit(); insertion.start = start; insertion.len = end - start;
                insertion.text = value.getString("insertText"); item.edits.add(insertion);
            }
            JSONArray additional = value.optJSONArray("additionalTextEdits");
            if (additional != null) {
                if (item.edits.isEmpty()) {
                    int end = caret, start = completionStart(text, end, item.kind);
                    Edit insertion = new Edit(); insertion.start = start; insertion.len = end - start; insertion.text = item.label;
                    item.edits.add(insertion);
                }
                item.edits.addAll(edits(lsp, text, additional));
            }
            if (item.edits.isEmpty()) {
                Edit insertion = new Edit(); insertion.start = completionStart(text, caret, item.kind);
                insertion.len = caret - insertion.start; insertion.text = item.label;
                item.edits.add(insertion);
            }
            EditorTextEdits.validate(text.length(), item.edits);
            items.add(item);
        }
        return items;
    }

    static int completionStart(String text, int end, int kind) {
        int start = end;
        while (start > 0) {
            int codePoint = text.codePointBefore(start);
            if (kind == 17 || kind == 19) {
                if (codePoint == '/' || codePoint == '\\' || codePoint == '<' || codePoint == '>' || codePoint == '\'' || codePoint == '"' || codePoint == '\n' || codePoint == '\r') break;
            } else if (!Character.isUnicodeIdentifierPart(codePoint)) break;
            start -= Character.charCount(codePoint);
        }
        return start;
    }

    static List<Edit> edits(String text, JSONArray values) throws JSONException { return edits(null, text, values); }
    static List<Edit> edits(Lsp lsp, String text, JSONArray values) throws JSONException {
        List<Edit> edits = new ArrayList<>();
        TextPositions positions = new TextPositions(text);
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i); edits.add(range(lsp, positions, value.getJSONObject("range"), value.getString("newText")));
        }
        return edits;
    }
    static Edit range(Lsp lsp, TextPositions positions, JSONObject range, String replacement) throws JSONException {
        JSONObject start = range.getJSONObject("start"), end = range.getJSONObject("end");
        Edit edit = new Edit();
        edit.start = lsp == null ? positions.offset(start.getInt("line"), start.getInt("character")) : lsp.offset(positions, start.getInt("line"), start.getInt("character"));
        int right = lsp == null ? positions.offset(end.getInt("line"), end.getInt("character")) : lsp.offset(positions, end.getInt("line"), end.getInt("character"));
        edit.len = right - edit.start; edit.text = replacement;
        if (edit.len < 0) throw new IllegalArgumentException("Reversed text range");
        return edit;
    }
    static Map<String, JSONArray> workspaceChanges(JSONObject workspace) throws JSONException, java.io.IOException {
        Map<String, JSONArray> changes = new LinkedHashMap<>();
        JSONObject simple = workspace.optJSONObject("changes");
        if (simple != null) {
            Iterator<String> keys = simple.keys();
            while (keys.hasNext()) { String uri = keys.next(); changes.put(uri, simple.getJSONArray(uri)); }
        }
        JSONArray documents = workspace.optJSONArray("documentChanges");
        if (documents != null) for (int i = 0; i < documents.length(); i++) {
            JSONObject change = documents.getJSONObject(i), document = change.optJSONObject("textDocument");
            if (document == null) throw new java.io.IOException("File resource operations are not supported");
            changes.put(document.getString("uri"), change.getJSONArray("edits"));
        }
        return changes;
    }
    static List<cn.rbc.codeeditor.util.ErrSpan> diagnostics(Lsp lsp, String text, int version, JSONArray values) throws JSONException {
        TextPositions positions = new TextPositions(text);
        List<ErrSpan> diagnostics = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i), range = value.getJSONObject("range");
            JSONObject start = range.getJSONObject("start"), end = range.getJSONObject("end");
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.documentVersion = version;
            diagnostic.stl = start.getInt("line") + 1;
            diagnostic.stc = lsp.codePointPositions() ? positions.position(lsp.offset(positions, diagnostic.stl - 1, start.getInt("character")))[1] : start.getInt("character");
            diagnostic.enl = end.getInt("line") + 1;
            diagnostic.enc = lsp.codePointPositions() ? positions.position(lsp.offset(positions, diagnostic.enl - 1, end.getInt("character")))[1] : end.getInt("character");
            diagnostic.severity = Math.max(0, Math.min(3, value.optInt("severity", 1) - 1));
            diagnostic.msg = value.optString("message"); diagnostic.code = value.opt("code");
            diagnostics.add(diagnostic);
        }
        Collections.sort(diagnostics);
        return diagnostics;
    }

    static final class SemanticStyles {
        final List<TextEditor.SemanticToken> tokens;
        final Set<String> types;
        SemanticStyles(List<TextEditor.SemanticToken> tokens, Set<String> types) { this.tokens = tokens; this.types = types; }
    }
    static SemanticStyles semanticTokens(Lsp lsp, String text, Object result) throws JSONException {
        JSONArray values = result instanceof JSONObject ? ((JSONObject)result).optJSONArray("data") : null;
        List<TextEditor.SemanticToken> tokens = new ArrayList<>();
        Set<String> types = new HashSet<>();
        TextPositions positions = new TextPositions(text);
        int line = 0, column = 0;
        if (values != null) for (int i = 0; i + 4 < values.length(); i += 5) {
            int delta = values.getInt(i); line += delta; if (delta != 0) column = 0;
            column += values.getInt(i + 1);
            int kind = lsp.nativeToken(values.getInt(i + 3));
            if (kind == Tokenizer.UNKNOWN) continue;
            int start = lsp.offset(positions, line, column);
            int end = lsp.offset(positions, line, column + values.getInt(i + 2));
            tokens.add(new TextEditor.SemanticToken(start, end, kind));
            if (kind == Tokenizer.TYPE) types.add(text.substring(start, end));
        }
        return new SemanticStyles(tokens, types);
    }
}
