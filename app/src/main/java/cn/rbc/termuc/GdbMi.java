package cn.rbc.termuc;

import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.util.*;

/** GDB/MI values use quoted C strings, tuples and lists of values or named results. */
final class GdbMi {
    final char kind;
    final String token, name, text;
    final Map<String, Object> values;

    private GdbMi(char kind, String token, String name, String text, Map<String, Object> values) {
        this.kind = kind; this.token = token; this.name = name; this.text = text; this.values = values;
    }

    static GdbMi parse(String line) throws IOException {
        int start = 0;
        while (start < line.length() && Character.isDigit(line.charAt(start))) start++;
        if (start == line.length() || "^*=+~@&".indexOf(line.charAt(start)) < 0) return null;
        char kind = line.charAt(start);
        boolean quoted = false, escaped = false;
        for (int i = start + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) escaped = false;
            else if (quoted && c == '\\') escaped = true;
            else if (c == '"') quoted = !quoted;
        }
        if (quoted || escaped) throw new IOException("Unclosed MI string");
        Parser parser = new Parser(line.substring(start + 1));
        if (kind == '~' || kind == '@' || kind == '&') {
            String text = parser.string();
            if (parser.current != '"' || parser.next() != StreamTokenizer.TT_EOF) throw new IOException("Invalid MI stream");
            return new GdbMi(kind, "", "", text, Collections.emptyMap());
        }
        String name = parser.string();
        Map<String, Object> values = new LinkedHashMap<>();
        while (parser.next() == ',') parser.field(values);
        if (parser.current != StreamTokenizer.TT_EOF) throw new IOException("Invalid MI record");
        return new GdbMi(kind, line.substring(0, start), name, "", values);
    }

    private static final class Parser {
        final StreamTokenizer input;
        int current;
        Parser(String text) {
            input = new StreamTokenizer(new StringReader(text));
            input.resetSyntax();
            input.wordChars(33, 65535);
            input.whitespaceChars(0, 32);
            for (char c : new char[]{'=', ',', '{', '}', '[', ']', '"'}) input.ordinaryChar(c);
            input.quoteChar('"');
        }
        int next() throws IOException { return current = input.nextToken(); }
        String string() throws IOException {
            if (next() != '"' && current != StreamTokenizer.TT_WORD) throw new IOException("Expected MI string");
            return input.sval;
        }
        void field(Map<String, Object> target) throws IOException {
            String key = string();
            if (next() != '=') throw new IOException("Expected MI assignment");
            target.put(key, value());
        }
        Object value() throws IOException {
            int type = next();
            if (type == '"') return input.sval;
            if (type == '{') {
                Map<String, Object> tuple = new LinkedHashMap<>();
                if (next() == '}') return tuple;
                input.pushBack();
                do { field(tuple); } while (next() == ',');
                if (current != '}') throw new IOException("Unclosed MI tuple");
                return tuple;
            }
            if (type == '[') {
                List<Object> list = new ArrayList<>();
                if (next() == ']') return list;
                input.pushBack();
                do {
                    if (next() == StreamTokenizer.TT_WORD) {
                        String key = input.sval;
                        if (next() != '=') throw new IOException("Expected MI list assignment");
                        list.add(Collections.singletonMap(key, value()));
                    } else { input.pushBack(); list.add(value()); }
                } while (next() == ',');
                if (current != ']') throw new IOException("Unclosed MI list");
                return list;
            }
            if (type == StreamTokenizer.TT_WORD) return input.sval;
            throw new IOException("Expected MI value");
        }
    }
}
