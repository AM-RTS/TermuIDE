package amrts.termuide;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Selection;
import android.text.method.CharacterPickerDialog;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import cn.rbc.codeeditor.common.OnCaretScrollListener;
import cn.rbc.codeeditor.lang.Formatter;
import cn.rbc.codeeditor.lang.Language;
import cn.rbc.codeeditor.lang.LanguageNonProg;
import cn.rbc.codeeditor.lang.LanguagePython;
import cn.rbc.codeeditor.util.*;
import cn.rbc.codeeditor.view.ColorScheme;
import cn.rbc.codeeditor.view.ColorSchemeLight;
import cn.rbc.codeeditor.view.ColorSchemeDark;
import androidx.core.graphics.ColorUtils;
import io.github.rosemoe.sora.event.*;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.diagnostic.*;
import io.github.rosemoe.sora.lang.styling.*;
import io.github.rosemoe.sora.lang.styling.color.ConstColor;
import io.github.rosemoe.sora.lang.styling.line.LineGutterBackground;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.LineSeparator;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.util.*;

/** Sora owns the view and input; the existing document owns undo, LSP edits and recovery. */
public class TextEditor extends FrameLayout implements Document.TextFieldMetrics {
    private final SoraView sora;
    private Document document;
    private Set<String> completionWords = Collections.emptySet();
    private Language language = LanguageNonProg.getInstance();
    private ColorScheme colors = ColorSchemeLight.getInstance();
    private Formatter formatter;
    private OnEditedListener editedListener;
    private OnCaretScrollListener caretListener;
    private boolean syncing, released, edited, useSpace = true, localCompletion, pureMode;
    private boolean suggestion, autoCaps;
    private int tabSpaces = 4;
    private volatile long generation;
    private final java.util.concurrent.ExecutorService lexer = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final EditorCompletionPanel completion;
    private final EditorSignaturePanel signature;
    private final ClipboardPanel clipboard;
    private final Runnable analyze = this::analyzeSyntax;
    private int surface, foreground, primary, selection, selectionForeground, outline, popupSurface;
    private List<SemanticToken> semanticTokens = Collections.emptyList();
    private CharacterPickerDialog characterPicker;
    private int nativeBatchDepth;

    public TextEditor(Context context) {
        super(context);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        document = new Document(this);
        document.clearSpans();
        sora = new SoraView(context);
        sora.setTypefaceText(Typeface.MONOSPACE);
        sora.setLineNumberEnabled(true);
        float density = getResources().getDisplayMetrics().density;
        sora.setLineNumberMarginLeft(8 * density);
        sora.setDividerMargin(4 * density, 8 * density);
        sora.setUndoEnabled(false);
        sora.getProps().symbolPairAutoCompletion = false;
        sora.getProps().actionWhenLineNumberClicked = 0;
        sora.getProps().deleteEmptyLineFast = false;
        sora.getProps().deleteMultiSpaces = -1;
        sora.getComponent(EditorAutoCompletion.class).setEnabled(false);
        sora.getComponent(EditorTextActionWindow.class).setEnabled(false);
        // Keep underlines and explicit double-tap messages without popups following the caret.
        sora.getComponent(EditorDiagnosticTooltipWindow.class).setEnabled(false);
        sora.setEditorLanguage(new EmptyLanguage() {
            @Override public boolean useTab() { return !useSpace; }
            @Override public int getIndentAdvance(ContentReference text, int line, int column) {
                if (language instanceof LanguagePython) {
                    StringBuilder prefix = new StringBuilder();
                    for (int i = 0; i < line; i++) prefix.append(text.getLine(i)).append('\n');
                    prefix.append(text.getLine(line), 0, column);
                    return ((LanguagePython)language).opensBlock(prefix) ? tabSpaces : 0;
                }
                String before = text.getLine(line).substring(0, column).trim();
                return before.endsWith("{") ? tabSpaces : 0;
            }
        });
        addView(sora, new LayoutParams(-1, -1));
        completion = new EditorCompletionPanel(this);
        signature = new EditorSignaturePanel(this);
        clipboard = new ClipboardPanel(this);
        sora.subscribeEvent(TextSizeChangeEvent.class, (event, receipt) -> signature.setTextSize(event.getNewTextSize()));
        sora.subscribeEvent(ContentChangeEvent.class, (event, receipt) -> contentChanged(event));
        sora.subscribeEvent(SelectionChangeEvent.class, (event, receipt) -> {
            if (syncing || released) return;
            if (caretListener != null) caretListener.updateCaret(getCaretPosition());
            if (document.getHighlights() == null) sora.setHighlightTexts(null);
            if (event.isSelected()) post(() -> { if (!released && isShown() && sora.isTextSelected()) clipboard.show(); });
            else if (event.getCause() == SelectionChangeEvent.CAUSE_LONG_PRESS) post(() -> { if (!released && isShown()) clipboard.show(); });
            else clipboard.hide();
            clipboard.invalidateContentRect();
        });
        sora.subscribeEvent(ScrollEvent.class, (event, receipt) -> clipboard.invalidateContentRect());
        sora.subscribeEvent(ClickEvent.class, (event, receipt) -> {
            if (event.getMotionRegion() == EditorMotionEvent.REGION_LINE_NUMBER) {
                document.markLine(event.getLine() + 1);
                renderStyles();
                ((MainActivity)getContext()).notifyUiChanged();
                event.intercept();
            } else if ((event.getCausingEvent().getMetaState() & KeyEvent.META_CTRL_ON) != 0 && "s".equals(Application.completion)) {
                java.io.File file = openedFile();
                Integer type = file == null ? null : Languages.fileType(file.getName());
                if (type != null && Languages.forType(type).getHasLsp())
                    Application.getInstance().languageServers.session(file, type).definition(file, event.getLine(), event.getColumn());
                event.intercept();
            }
        });
        sora.subscribeEvent(DoubleClickEvent.class, (event, receipt) -> {
            List<ErrSpan> diagnostics = document.getDiag();
            if (diagnostics == null) return;
            for (ErrSpan diagnostic : diagnostics) {
                int start = offset(diagnostic.stl - 1, diagnostic.stc);
                int end = offset(diagnostic.enl - 1, diagnostic.enc);
                if (event.getIndex() >= start && event.getIndex() <= end && diagnostic.msg != null) {
                    Toast.makeText(context, diagnostic.msg, Toast.LENGTH_LONG).show();
                    break;
                }
            }
        });
        surface = 0xfffaf8ff; foreground = 0xff1c1b20; primary = 0xff6750a4;
        selection = 0xffeaddff; outline = 0xff79747e;
        selectionForeground = 0xff1d192b; popupSurface = surface;
        applyColors();
    }

    private java.io.File openedFile() {
        MainActivity activity = (MainActivity)getContext();
        int index = activity.getSelectedItem();
        return index < 0 ? null : new java.io.File(activity.getTag(index));
    }

    private void contentChanged(ContentChangeEvent event) {
        if (syncing || released) return;
        semanticTokens = Collections.emptyList();
        completion.dismiss();
        if (event.getAction() == ContentChangeEvent.ACTION_DELETE || event.getChangedText().toString().contains("\n")) signature.hide();
        EditorDocumentBridge.changed(document, event.getAction(), event.getChangeStart().index,
            event.getChangedText(), System.nanoTime());
        setEdited(document.getMarkedVersion() != document.getCurrentVersion());
        scheduleSyntax();
        if (localCompletion && event.getAction() == ContentChangeEvent.ACTION_INSERT) {
            post(() -> { if (!released) completion.updatePrefix(); });
        }
    }

    public Document getText() { return document; }
    public CodeEditor getSoraEditor() { return sora; }
    public Language getLanguage() { return language; }
    Set<String> getCompletionWords() { return completionWords; }
    public void setLanguage(Language value) { if (language != value) completionWords = Collections.emptySet(); language = value; Tokenizer.setLanguage(value); scheduleSyntax(); }
    public void setDocument(Document value) {
        document = value;
        document.setMetrics(this);
        document.setWordWrap(false);
        document.analyzeWordWrap();
        refreshDocument();
    }

    /** Call after a model batch (formatting, workspace edit, undo, load, or recovery). */
    public void refreshDocument() {
        if (released) return;
        int start = getSelectionStart(), end = getSelectionEnd();
        syncing = true;
        try {
            String text = document.toString();
            if (!text.contentEquals(sora.getText())) {
                semanticTokens = Collections.emptyList();
                // Incremental edits require Sora's asynchronous line layout to be ready.
                if (!sora.isEditable()) sora.setText(text);
                else EditorDocumentBridge.synchronize(document, sora.getText());
            }
            LineSeparator separator = sora.getText().getLine(0).getLineSeparator();
            if (separator != LineSeparator.NONE) sora.setLineSeparator(separator);
            setSelectionRange(start, end);
        } finally { syncing = false; }
        setEdited(document.getMarkedVersion() != document.getCurrentVersion());
        scheduleSyntax();
    }

    private void scheduleSyntax() {
        generation++;
        removeCallbacks(analyze);
        if (!released) postDelayed(analyze, 60);
    }

    private void analyzeSyntax() {
        if (released) return;
        final long expected = generation;
        final String text = document.toString();
        final Language current = language;
        lexer.execute(() -> {
            List<Pair> spans = new ArrayList<>();
            cn.rbc.codeeditor.util.Lexer scanner = current.newLexer(new CharSeqReader(text));
            int position = 0, previous = -1;
            Set<String> words = new LinkedHashSet<>();
            try {
                for (int token; scanner != null && (token = scanner.yylex()) != cn.rbc.codeeditor.util.Lexer.EOF;) {
                    if (released || expected != generation) return;
                    int style = tokenStyle(token);
                    if (token == cn.rbc.codeeditor.util.Lexer.IDENTIFIER) {
                        String word = scanner.yytext();
                        words.add(word);
                        if (current.isType(word)) style = Tokenizer.TYPE;
                    }
                    if (style != previous) { spans.add(new Pair(position, style)); previous = style; }
                    int length = scanner.yylength();
                    if (length <= 0) break;
                    position += length;
                }
            } catch (java.io.IOException error) { android.util.Log.w("Editor", "Syntax analysis failed", error); }
            if (spans.isEmpty()) spans.add(new Pair(0, Tokenizer.NORMAL));
            post(() -> {
                if (released || expected != generation) return;
                List<Pair> converted = new ArrayList<>();
                for (Pair span : spans) converted.add(new Pair(document.logicalToRealIndex(span.first), span.second));
                document.setSpans(converted);
                completionWords = Collections.unmodifiableSet(new LinkedHashSet<>(words));
                renderStyles();
            });
        });
    }

    private static int tokenStyle(int token) {
        switch (token) {
            case cn.rbc.codeeditor.util.Lexer.KEYWORD: return Tokenizer.KEYWORD;
            case cn.rbc.codeeditor.util.Lexer.TYPE: return Tokenizer.TYPE;
            case cn.rbc.codeeditor.util.Lexer.COMMENT: return Tokenizer.DOUBLE_SYMBOL_DELIMITED_MULTILINE;
            case cn.rbc.codeeditor.util.Lexer.STRING_LITERAL:
            case cn.rbc.codeeditor.util.Lexer.CHARACTER_LITERAL: return Tokenizer.SINGLE_SYMBOL_DELIMITED_A;
            case cn.rbc.codeeditor.util.Lexer.INTEGER_LITERAL:
            case cn.rbc.codeeditor.util.Lexer.FLOATING_POINT_LITERAL: return Tokenizer.NUMBER;
            case cn.rbc.codeeditor.util.Lexer.PRETREATMENT_LINE:
            case cn.rbc.codeeditor.util.Lexer.DEFINE_LINE: return Tokenizer.SINGLE_SYMBOL_LINE_A;
            case cn.rbc.codeeditor.util.Lexer.OPERATOR: return Tokenizer.OPERATOR;
            default: return Tokenizer.NORMAL;
        }
    }

    private void renderStyles() {
        if (released) return;
        MappedSpans.Builder spans = new MappedSpans.Builder(sora.getLineCount());
        TreeMap<Integer, Integer> boundaries = new TreeMap<>();
        boundaries.put(0, Tokenizer.NORMAL);
        List<Pair> tokens = document.getSpans();
        if (tokens != null) for (Pair token : tokens) {
            int index = document.realToLogicalIndex(token.first);
            if (index < 0 || index >= document.length()) continue;
            boundaries.put(index, token.second);
        }
        for (SemanticToken token : semanticTokens) {
            if (token.start < 0 || token.end > sora.getText().length() || token.start >= token.end) continue;
            int after = boundaries.floorEntry(token.end).getValue();
            boundaries.subMap(token.start, true, token.end, false).clear();
            boundaries.put(token.start, token.type);
            boundaries.put(token.end, after);
        }
        for (Map.Entry<Integer, Integer> boundary : boundaries.entrySet()) {
            int index = boundary.getKey();
            CharPosition position = sora.getText().getIndexer().getCharPosition(index);
            spans.addIfNeeded(position.line, position.column, 100 + boundary.getValue());
        }
        spans.determine(sora.getLineCount() - 1);
        Styles styles = new Styles(spans.build());
        for (int i = 0; i < document.getMarksCount(); i++) {
            int line = document.getMark(i) - 1;
            if (line >= 0 && line < sora.getLineCount())
                styles.addLineStyle(new LineGutterBackground(line, new ConstColor(selection)));
        }
        styles.finishBuilding();
        sora.setStyles(styles);
        DiagnosticsContainer diagnostics = new DiagnosticsContainer();
        List<ErrSpan> errors = document.getDiag();
        if (errors != null) for (ErrSpan error : errors) {
            int start = offset(error.stl - 1, error.stc), end = offset(error.enl - 1, error.enc);
            short severity = error.severity == 0 ? DiagnosticRegion.SEVERITY_ERROR
                : error.severity == 1 ? DiagnosticRegion.SEVERITY_WARNING : DiagnosticRegion.SEVERITY_TYPO;
            DiagnosticRegion region = new DiagnosticRegion(start, Math.max(start, end), severity);
            region.detail = new DiagnosticDetail(error.msg == null ? "" : error.msg, null, null, null);
            diagnostics.addDiagnostic(region);
        }
        sora.setDiagnostics(diagnostics);
        HighlightTextContainer highlights = new HighlightTextContainer();
        List<cn.rbc.codeeditor.view.autocomplete.Edit> ranges = document.getHighlights();
        if (ranges != null) for (cn.rbc.codeeditor.view.autocomplete.Edit range : ranges) {
            int start = Math.max(0, Math.min(range.start, sora.getText().length()));
            int end = Math.max(start, Math.min(range.start + range.len, sora.getText().length()));
            CharPosition left = sora.getText().getIndexer().getCharPosition(start);
            CharPosition right = sora.getText().getIndexer().getCharPosition(end);
            highlights.add(new HighlightTextContainer.HighlightText(left.line, left.column, right.line, right.column,
                new ConstColor(selection), new ConstColor(0)));
        }
        sora.setHighlightTexts(highlights);
    }

    private int offset(int line, int column) {
        line = Math.max(0, Math.min(sora.getLineCount() - 1, line));
        return sora.getText().getCharIndex(line, Math.max(0, Math.min(column, sora.getText().getColumnCount(line))));
    }

    public void setMaterialColors(int surface, int foreground, int primary, int selection,
                                  int selectionForeground, int outline, int popupSurface) {
        boolean dark = ColorUtils.calculateLuminance(surface) < .5;
        if (this.surface == surface && this.foreground == foreground && this.primary == primary
            && this.selection == selection && this.selectionForeground == selectionForeground
            && this.outline == outline && this.popupSurface == popupSurface && colors.isDark() == dark) return;
        this.surface = surface; this.foreground = foreground; this.primary = primary;
        this.selection = selection; this.selectionForeground = selectionForeground;
        this.outline = outline; this.popupSurface = popupSurface;
        colors = dark ? ColorSchemeDark.getInstance() : ColorSchemeLight.getInstance();
        applyColors();
        renderStyles();
    }

    static final class SemanticToken {
        final int start, end, type;
        SemanticToken(int start, int end, int type) { this.start = start; this.end = end; this.type = type; }
    }
    void setSemanticTokens(List<SemanticToken> tokens) { semanticTokens = tokens; renderStyles(); }

    private void applyColors() {
        EditorColorScheme scheme = new EditorColorScheme(colors.isDark()) {};
        int background = pureMode ? (colors.isDark() ? 0xff000000 : 0xffffffff) : surface;
        int currentLine = ColorUtils.compositeColors((primary & 0xffffff) | 0x14000000, background);
        int textColor = readableColor(foreground, background, currentLine, selection);
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, background);
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, background);
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, textColor);
        scheme.setColor(EditorColorScheme.TEXT_SELECTED, readableColor(selectionForeground, selection));
        scheme.setColor(EditorColorScheme.LINE_NUMBER, readableColor(outline, background, selection));
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, readableColor(primary, background, currentLine, selection));
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, primary);
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, primary);
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection);
        scheme.setColor(EditorColorScheme.CURRENT_LINE, (primary & 0xffffff) | 0x14000000);
        scheme.setColor(EditorColorScheme.NON_PRINTABLE_CHAR, outline);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND, surface);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG, foreground);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_DETAILED_MSG, outline);
        scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_ACTION, primary);
        for (int token : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 10, 20, 21, 30, 40, 50, 51})
            scheme.setColor(100 + token, readableColor(token == 0 ? foreground : colors.getTokenColor(token),
                background, currentLine, selection));
        sora.setColorScheme(scheme);
        int popupText = readableColor(foreground, popupSurface, selection);
        completion.setColors(popupSurface, popupText, selection);
        signature.setColors(popupSurface, popupText, primary);
    }

    private static int readableColor(int color, int... backgrounds) {
        if (minimumContrast(color, backgrounds) >= 4.5) return color;
        int target = minimumContrast(android.graphics.Color.BLACK, backgrounds) >=
            minimumContrast(android.graphics.Color.WHITE, backgrounds) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
        float low = 0, high = 1;
        for (int i = 0; i < 12; i++) {
            float amount = (low + high) / 2;
            if (minimumContrast(ColorUtils.blendARGB(color, target, amount), backgrounds) >= 4.5) high = amount;
            else low = amount;
        }
        return ColorUtils.blendARGB(color, target, high);
    }

    private static double minimumContrast(int color, int[] backgrounds) {
        double contrast = Double.MAX_VALUE;
        for (int background : backgrounds) contrast = Math.min(contrast, ColorUtils.calculateContrast(color, background));
        return contrast;
    }

    public int getSelectionStart() { return sora.getCursor().getLeft(); }
    public int getSelectionEnd() { return sora.getCursor().getRight(); }
    public int getCaretPosition() { return getSelectionEnd(); }
    public String getSelectedText() { return document.subSequence(getSelectionStart(), getSelectionEnd()).toString(); }
    public void setSelection(int index) { setSelection(index, 0); }
    public void setSelection(int index, int length) { setSelectionRange(index, index + length); }
    private void setSelectionRange(int start, int end) {
        int length = sora.getText().length();
        CharPosition left = sora.getText().getIndexer().getCharPosition(Math.max(0, Math.min(start, length)));
        CharPosition right = sora.getText().getIndexer().getCharPosition(Math.max(0, Math.min(end, length)));
        sora.setSelectionRegion(left.line, left.column, right.line, right.column);
    }
    public void moveCaret(int position) { refreshDocument(); setSelection(position); }
    public void gotoLine(int line) { sora.setSelection(Math.max(0, Math.min(line - 1, sora.getLineCount() - 1)), 0); }
    public boolean isSelectText2() { return sora.isTextSelected(); }
    public void selectAll() { sora.selectAll(); }
    public void selectText(boolean selected) { if (!selected) setSelection(getCaretPosition()); }
    public void cut() { sora.cutText(); }
    public void copy() { sora.copyText(); }
    public void paste() { sora.pasteText(); }
    public void paste(String text) { sora.commitText(text, false, false); }
    public void delete() { sora.deleteText(); }
    public void sendPrintableChar(char value) { if (value == '\t') sora.indentOrCommitTab(); else sora.commitText(String.valueOf(value)); }
    public void replaceText(int start, int length, CharSequence text) { setSelection(start, length); paste(text.toString()); }
    public void undo() { int position = document.undo(); if (position >= 0) { refreshDocument(); setSelection(position); } }
    public void redo() { int position = document.redo(); if (position >= 0) { refreshDocument(); setSelection(position); } }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean value) { edited = value; if (editedListener != null) editedListener.onEdited(this, value); }
    public void setOnEditedListener(OnEditedListener listener) { editedListener = listener; }
    public void addCaretListener(OnCaretScrollListener listener) { caretListener = listener; }
    public void setFormatter(Formatter value) { formatter = value; }
    public void format() { (formatter == null ? language.getFormatter() : formatter).format(document, tabSpaces); refreshDocument(); }
    public void setAutoComplete(boolean enabled) { localCompletion = enabled; if (!enabled) completion.dismiss(); }
    public void setColorScheme(ColorScheme value) { colors = value; applyColors(); }
    public ColorScheme getColorScheme() { return colors; }
    public void setPureMode(boolean pure) { pureMode = pure; applyColors(); }
    public void setTypeface(Typeface font) { sora.setTypefaceText(font); sora.setTypefaceLineNumber(font); }
    public void setTextSize(int pixels) { sora.setTextSizePx(pixels); signature.setTextSize(pixels); }
    public float getTextSize() { return sora.getTextSizePx(); }
    public void setWordWrap(boolean wrap) { sora.setWordwrap(wrap); document.setWordWrap(false); }
    public boolean isWordWrap() { return sora.isWordwrap(); }
    public void setShowNonPrinting(boolean show) { sora.setNonPrintablePaintingFlags(show ? 1 | 2 | 4 | 8 | 16 | 64 : 0); }
    public void setUseSpace(boolean value) { useSpace = value; }
    public boolean isUseSpace() { return useSpace; }
    public void setTabSpaces(int spaces) { tabSpaces = Math.max(1, spaces); sora.setTabWidth(tabSpaces); }
    public void setSuggestion(boolean enabled) { suggestion = enabled; updateInputType(); }
    public void setAutoCaps(boolean enabled) { autoCaps = enabled; updateInputType(); }
    private void updateInputType() {
        sora.getProps().disallowSuggestions = !suggestion;
        sora.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | (suggestion ? 0 : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            | (autoCaps ? InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0));
    }
    public EditorCompletionPanel getAutoCompletePanel() { return completion; }
    public EditorSignaturePanel getSigHelpPanel() { return signature; }
    public ClipboardPanel getClipboardPanel() { return clipboard; }
    public void setClipboardCallback(ActionMode.Callback callback, int flags) { clipboard.setCustomCallback(callback, flags); }
    public Rect getBoundingBox(int index) {
        CharPosition p = sora.getText().getIndexer().getCharPosition(Math.min(index, sora.getText().length()));
        int x = (int)sora.getCharOffsetX(p.line, p.column), y = (int)sora.getCharOffsetY(p.line, p.column);
        return new Rect(x, y - sora.getRowHeight(), x + 2, y);
    }
    @Override public boolean requestFocus(int direction, Rect rectangle) { return sora != null && sora.requestFocus(direction, rectangle); }
    @Override public void clearFocus() { if (sora != null) sora.clearFocus(); super.clearFocus(); }
    @Override public int getAdvance(char c, int x) { return (int)(getTextSize() * .6f); }
    @Override public int getRowWidth() { return getWidth(); }
    @Override public int getLeftOffset() { return 0; }
    @Override public void postInvalidateOnAnimation() { super.postInvalidateOnAnimation(); if (sora != null) renderStyles(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (!isShown() && completion != null) { completion.dismiss(); signature.hide(); clipboard.hide(); }
    }
    public void release() {
        if (released) return;
        released = true; generation++; removeCallbacks(analyze); lexer.shutdownNow();
        completion.dismiss(); signature.hide(); clipboard.hide(); sora.release();
        if (characterPicker != null) characterPicker.dismiss();
        editedListener = null; caretListener = null;
    }
    interface OnEditedListener { void onEdited(TextEditor editor, boolean edited); }

    private final class SoraView extends CodeEditor {
        private boolean replacing;
        SoraView(Context context) { super(context); }
        @Override public void setText(CharSequence text, boolean reuse, android.os.Bundle arguments) {
            if (nativeBatchDepth > 0) { document.endBatchEdit(); nativeBatchDepth = 0; }
            super.setText(new EditorContent(text), true, arguments);
        }
        @Override public void beforeReplace(Content content) {
            super.beforeReplace(content);
            if (!syncing && !released) { beginNativeBatch(); replacing = true; }
        }
        @Override public void afterInsert(Content content, int sl, int sc, int el, int ec, CharSequence text) {
            try { super.afterInsert(content, sl, sc, el, ec, text); }
            finally { if (replacing) { endNativeBatch(); replacing = false; } }
        }
        @Override public void undo() { if (document != null) TextEditor.this.undo(); }
        @Override public void redo() { if (document != null) TextEditor.this.redo(); }
        @Override public boolean canUndo() { return document != null && document.canUndo(); }
        @Override public boolean canRedo() { return document != null && document.canRedo(); }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            MainActivity activity = (MainActivity)getContext();
            if (event.isCtrlPressed() && !event.isAltPressed()) {
                if ((code == KeyEvent.KEYCODE_Z || code == KeyEvent.KEYCODE_S || code == KeyEvent.KEYCODE_F || code == KeyEvent.KEYCODE_R)
                    && activity.onKeyShortcut(code, event)) return true;
                if (code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_MINUS) return onKeyShortcut(code, event);
            }
            if (event.isAltPressed() && ((code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                || (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9))
                && activity.onKeyDown(code, event)) return true;
            if (completion != null && completion.handleKey(code, event)) return true;
            int unicode = event.getUnicodeChar();
            if (code == KeyEvent.KEYCODE_SYM || unicode == KeyCharacterMap.PICKER_DIALOG_INPUT) {
                showVariants(CharacterVariants.get(KeyCharacterMap.PICKER_DIALOG_INPUT), false);
                return true;
            }
            String variants = CharacterVariants.get(unicode);
            if (!event.isCtrlPressed() && !event.isAltPressed() && event.getRepeatCount() > 0 && variants != null) {
                if (event.getRepeatCount() == 1) showVariants(variants, true);
                return true;
            }
            return super.onKeyDown(code, event);
        }
        @Override public boolean onKeyShortcut(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_EQUALS) { setTextSizePx(getTextSizePx() + getResources().getDisplayMetrics().density); return true; }
            if (code == KeyEvent.KEYCODE_MINUS) { setTextSizePx(Math.max(8, getTextSizePx() - getResources().getDisplayMetrics().density)); return true; }
            return super.onKeyShortcut(code, event);
        }
    }

    private void beginNativeBatch() { if (nativeBatchDepth++ == 0) document.beginBatchEdit(); }
    private void endNativeBatch() { if (nativeBatchDepth > 0 && --nativeBatchDepth == 0) document.endBatchEdit(); }
    private final class EditorContent extends Content {
        private int batches;
        EditorContent(CharSequence text) { super(text); }
        @Override public boolean beginBatchEdit() {
            if (!syncing && !released) { beginNativeBatch(); batches++; }
            return super.beginBatchEdit();
        }
        @Override public boolean endBatchEdit() {
            boolean result = super.endBatchEdit();
            if (batches > 0) { batches--; endNativeBatch(); }
            return result;
        }
        @Override public void resetBatchEdit() {
            super.resetBatchEdit();
            while (batches > 0) { batches--; endNativeBatch(); }
        }
    }

    private void showVariants(String variants, boolean replacePrevious) {
        if (variants == null || released) return;
        int start = getSelectionStart(), end = getSelectionEnd();
        if (replacePrevious && start == end && start > 0) start--;
        final int replaceStart = start, replaceEnd = end;
        SpannableStringBuilder selected = new SpannableStringBuilder();
        Selection.setSelection(selected, 0);
        characterPicker = new CharacterPickerDialog(getContext(), sora, selected, variants, true);
        characterPicker.setOnDismissListener(dialog -> {
            if (!released && selected.length() > 0) replaceText(replaceStart, replaceEnd - replaceStart, selected);
            characterPicker = null;
        });
        characterPicker.show();
    }
}
