package cn.rbc.codeeditor.util;

import java.io.*;

public class CharSeqReader extends Reader {
	int offset = 0;
	CharSequence src;

	public CharSeqReader(CharSequence src) {
		this.src = src;
	}

	@Override
	public void close() {
		src = null;
		offset = 0;
	}

	@Override
	public int read(char[] chars, int i, int i1) throws IOException {
        if (src == null) throw new IOException("Reader is closed");
        if (i < 0 || i1 < 0 || i > chars.length || i1 > chars.length - i) throw new IndexOutOfBoundsException();
        if (i1 == 0) return 0;
        // Only TextBuffer's final unit is a sentinel; U+FFFF elsewhere is real document content.
        int length = src.length() - (src instanceof TextBuffer ? 1 : 0);
        int len = Math.min(length - offset, i1);
        if (len <= 0) return -1;
        for (int n = 0; n < len; n++) chars[i + n] = src.charAt(offset++);
		return len;
	}
}

