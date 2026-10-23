package cn.rbc.termuc;

import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.widget.*;
import cn.rbc.codeeditor.lang.Language;
import cn.rbc.codeeditor.util.Document;
import cn.rbc.codeeditor.view.autocomplete.Edit;
import cn.rbc.codeeditor.view.autocomplete.ListItem;
import java.util.*;

/** Retains clangd's edit ranges and local keyword completion with Sora as its anchor. */
public final class EditorCompletionPanel {
    public int _off;
    private final TextEditor editor;
    private final ListPopupWindow popup;
    private final ArrayAdapter<String> adapter;
    private List<ListItem> items = Collections.emptyList();
    private int foreground;
    private int version;
    private int selectionStart, selectionEnd;
    private int selected;
    private final Typeface icons;

    EditorCompletionPanel(TextEditor editor) {
        this.editor = editor;
        icons = Typeface.createFromAsset(editor.getContext().getAssets(), "codicon.ttf");
        popup = new ListPopupWindow(editor.getContext());
        popup.setAnchorView(editor.getSoraEditor());
        adapter = new ArrayAdapter<String>(editor.getContext(), android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row = new LinearLayout(editor.getContext());
                int unit = (int)(16 * editor.getResources().getDisplayMetrics().density);
                row.setPadding(unit, unit / 2, unit, unit / 2);
                row.setMinimumHeight(unit * 3);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView icon = new TextView(editor.getContext());
                icon.setTypeface(icons); icon.setText(iconResource(items.get(position).kind)); icon.setTextColor(foreground);
                row.addView(icon, new LinearLayout.LayoutParams(unit * 2, -2));
                TextView label = new TextView(editor.getContext());
                label.setText(getItem(position)); label.setTextColor(foreground); label.setTypeface(Application.typeface());
                row.addView(label, new LinearLayout.LayoutParams(-1, -2));
                return row;
            }
        };
        popup.setAdapter(adapter);
        popup.setOnItemClickListener((parent, view, position, id) -> select(position));
        popup.setWidth(Math.min(editor.getResources().getDisplayMetrics().widthPixels,
            (int)(420 * editor.getResources().getDisplayMetrics().density)));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
    }

    public void update(ArrayList<ListItem> values) {
        dismiss();
        if (values == null || values.isEmpty() || !editor.isAttachedToWindow() || !editor.isShown()) return;
        items = new ArrayList<>(values);
        version = editor.getText().getCurrentVersion();
        selectionStart = editor.getSelectionStart(); selectionEnd = editor.getSelectionEnd();
        int caret = editor.getCaretPosition(), start = caret;
        while (start > 0 && Character.isUnicodeIdentifierPart(editor.getText().charAt(start - 1))) start--;
        _off = caret - start;
        adapter.clear();
        for (ListItem item : items) adapter.add(item.label);
        int rowHeight = (int)(48 * editor.getResources().getDisplayMetrics().density);
        popup.setHeight(Math.min(4, items.size()) * rowHeight);
        // ListPopupWindow positions itself above the IME when there is insufficient room below.
        popup.setVerticalOffset(-editor.getHeight() + editor.getBoundingBox(editor.getCaretPosition()).bottom);
        popup.show();
        selected = 0;
        popup.setSelection(selected);
    }

    public void updatePrefix() {
        Document document = editor.getText();
        int end = editor.getCaretPosition(), start = end;
        while (start > 0 && Character.isJavaIdentifierPart(document.charAt(start - 1))) start--;
        if (start == end) { dismiss(); return; }
        _off = end - start;
        String prefix = document.subSequence(start, end).toString().toLowerCase(Locale.ROOT);
        Language language = editor.getLanguage();
        Set<String> candidates = new LinkedHashSet<>();
        Collections.addAll(candidates, language.getKeywords());
        Collections.addAll(candidates, language.getNames());
        candidates.addAll(editor.getCompletionWords());
        ArrayList<ListItem> matches = new ArrayList<>();
        for (String candidate : candidates) {
            boolean keyword = candidate.startsWith("[K]");
            String label = keyword ? candidate.substring(3) : candidate;
            if (!label.equals(prefix) && label.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                ListItem item = new ListItem(); item.label = label; item.kind = keyword ? 14 : 1;
                matches.add(item);
            }
        }
        update(matches);
    }

    public void select(int position) {
        if (position < 0 || position >= items.size()) return;
        Document doc = editor.getText();
        if (version != doc.getCurrentVersion() || selectionStart != editor.getSelectionStart() ||
            selectionEnd != editor.getSelectionEnd()) { dismiss(); return; }
        ListItem item = items.get(position);
        if (item.edits.isEmpty()) {
            String label = item.label;
            int arguments = label.indexOf('(');
            if (arguments >= 0) label = label.substring(0, arguments + 1);
            editor.replaceText(Math.max(0, editor.getCaretPosition() - _off), _off, label);
        } else {
            Edit primary = item.edits.peekFirst();
            int caret;
            try {
                caret = EditorTextEdits.apply(doc, item.edits, primary.start + primary.len);
            } catch (IllegalArgumentException error) {
                android.util.Log.w("Editor", "Rejected invalid completion edits", error);
                dismiss();
                return;
            }
            editor.refreshDocument();
            editor.setSelection(caret);
        }
        dismiss();
        editor.requestFocus();
    }

    public void setColors(int background, int foreground, int selection) {
        this.foreground = foreground;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(12 * editor.getResources().getDisplayMetrics().density);
        popup.setBackgroundDrawable(shape);
        popup.setListSelector(new android.graphics.drawable.ColorDrawable(selection));
        adapter.notifyDataSetChanged();
    }
    boolean handleKey(int code, KeyEvent event) {
        if (!isShow() || event.isCtrlPressed() || event.isAltPressed()) return false;
        if (code == KeyEvent.KEYCODE_ESCAPE) { dismiss(); return true; }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_TAB) { select(selected); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            selected = Math.max(0, Math.min(items.size() - 1, selected + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
            popup.setSelection(selected);
            return true;
        }
        return false;
    }
    public void dismiss() { popup.dismiss(); }
    public boolean isShow() { return popup.isShowing(); }
    private static int iconResource(int kind) {
        switch (kind) {
            case 2: case 3: case 4: return cn.rbc.codeeditor.R.string.icon_method;
            case 5: return cn.rbc.codeeditor.R.string.icon_field;
            case 6: return cn.rbc.codeeditor.R.string.icon_variable;
            case 7: return cn.rbc.codeeditor.R.string.icon_class;
            case 8: return cn.rbc.codeeditor.R.string.icon_interface;
            case 9: return cn.rbc.codeeditor.R.string.icon_module;
            case 12: case 13: return cn.rbc.codeeditor.R.string.icon_enum;
            case 14: return cn.rbc.codeeditor.R.string.icon_keyword;
            case 15: return cn.rbc.codeeditor.R.string.icon_snippet;
            case 17: return cn.rbc.codeeditor.R.string.icon_file;
            case 21: return cn.rbc.codeeditor.R.string.icon_constant;
            case 25: return cn.rbc.codeeditor.R.string.icon_parameter;
            default: return cn.rbc.codeeditor.R.string.icon_text;
        }
    }
}
