package cn.rbc.termuc;

import cn.rbc.codeeditor.util.Document;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.text.Content;

/** Converts Sora's UTF-16 edits to the document's offsets, excluding its EOF sentinel. */
final class EditorDocumentBridge {
    static void changed(Document document, int action, int start, CharSequence text, long time) {
        document.setTyping(true);
        if (action == ContentChangeEvent.ACTION_INSERT) {
            document.insertBefore(text.toString().toCharArray(), start, time);
        } else if (action == ContentChangeEvent.ACTION_DELETE) {
            document.deleteAt(start, text.length(), time);
        } else {
            document.beginBatchEdit();
            try {
                document.deleteAt(0, document.length() - 1, time);
                document.insertBefore(text.toString().toCharArray(), 0, time);
            } finally { document.endBatchEdit(); }
        }
    }

    static void synchronize(Document document, Content target) {
        String text = document.toString();
        int start = 0, oldEnd = target.length(), newEnd = text.length();
        while (start < oldEnd && start < newEnd && target.charAt(start) == text.charAt(start)) start++;
        while (oldEnd > start && newEnd > start && target.charAt(oldEnd - 1) == text.charAt(newEnd - 1)) {
            oldEnd--; newEnd--;
        }
        // Sora requires edits to include both UTF-16 units of a CRLF separator.
        if (insideCrLf(target, start) || insideCrLf(text, start)) start--;
        if (insideCrLf(target, oldEnd) || insideCrLf(text, newEnd)) { oldEnd++; newEnd++; }
        if (start != oldEnd || start != newEnd) target.replace(start, oldEnd, text.substring(start, newEnd));
    }

    private static boolean insideCrLf(CharSequence text, int index) {
        return index > 0 && index < text.length() && text.charAt(index - 1) == '\r' && text.charAt(index) == '\n';
    }
}
