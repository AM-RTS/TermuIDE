package cn.rbc.codeeditor.util;

import java.util.ArrayList;
import java.util.Arrays;

/** Logical UTF-16 positions shared by LSP and Sora, including CRLF and lone CR. */
public final class TextPositions {
    private final String text;
    private final int[] starts;
    public TextPositions(String text) {
        this.text = text;
        ArrayList<Integer> lines = new ArrayList<>(); lines.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                lines.add(i + 1);
            } else if (text.charAt(i) == '\n') lines.add(i + 1);
        }
        starts = new int[lines.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = lines.get(i);
    }
    public int offset(int line, int column) {
        if (line < 0 || line >= starts.length || column < 0) throw new IllegalArgumentException("Position outside document");
        int end = line + 1 < starts.length ? starts[line + 1] : text.length();
        while (end > starts[line] && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) end--;
        if (column > end - starts[line]) throw new IllegalArgumentException("Column outside line");
        return starts[line] + column;
    }
    public int codePointColumn(int line, int utf16Column) {
        return text.codePointCount(offset(line, 0), offset(line, utf16Column));
    }
    public int codePointOffset(int line, int column) {
        if (column < 0) throw new IllegalArgumentException("Negative code-point column");
        int start = offset(line, 0);
        int result;
        try { result = text.offsetByCodePoints(start, column); }
        catch (IndexOutOfBoundsException error) { throw new IllegalArgumentException("Column outside document", error); }
        offset(line, result - start);
        return result;
    }
    public int[] position(int index) {
        if (index < 0 || index > text.length()) throw new IllegalArgumentException("Offset outside document");
        int line = Arrays.binarySearch(starts, index);
        if (line < 0) line = -line - 2;
        return new int[]{line, index - starts[line]};
    }
}
