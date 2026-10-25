package amrts.termuide;

import cn.rbc.codeeditor.util.Document;
import cn.rbc.codeeditor.view.autocomplete.Edit;
import java.util.*;

final class EditorTextEdits {
    /** LSP offsets describe the same original document; apply from the end as one undo step. */
    static int apply(Document document, Collection<Edit> input, int caret) {
        List<Edit> edits = new ArrayList<>(input);
        edits.sort(Comparator.comparingInt((Edit edit) -> edit.start).reversed());
        validate(document, edits);
        document.setTyping(false);
        document.beginBatchEdit();
        try {
            long time = System.nanoTime();
            for (Edit edit : edits) {
                document.deleteAt(edit.start, edit.len, time);
                document.insertBefore(edit.text.toCharArray(), edit.start, time);
                if (edit.start + edit.len <= caret) caret += edit.text.length() - edit.len;
                else if (edit.start < caret) caret = edit.start + edit.text.length();
            }
        } finally { document.endBatchEdit(); }
        return caret;
    }

    static void validate(Document document, Collection<Edit> input) {
        List<Edit> edits = new ArrayList<>(input);
        edits.sort(Comparator.comparingInt((Edit edit) -> edit.start).reversed());
        int nextStart = document.length() - 1;
        for (Edit edit : edits) {
            if (edit.text == null || edit.start < 0 || edit.len < 0 || edit.start > nextStart
                || edit.len > nextStart - edit.start) throw new IllegalArgumentException("Invalid or overlapping editor edits");
            nextStart = edit.start;
        }
    }
}
