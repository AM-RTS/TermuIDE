/*
 * Modified in the TermuIDE fork; see NOTICE.md for provenance.
 * Copyright (c) 2011 Tah Wei Hoon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License Version 2.0,
 * with full text available at http://www.apache.org/licenses/LICENSE-2.0.html
 *
 * This software is provided "as is". Use at your own risk.
 */
package cn.rbc.codeeditor.lang;
import cn.rbc.codeeditor.util.CharSeqReader;
import cn.rbc.codeeditor.util.Lexer;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleton class containing the symbols and operators of the Python language
 */
public class LanguagePython extends Language{
	private static LanguagePython _theOne = null;
	
	private final static String[] keywords = {
		"and", "assert", "break", "class", "continue", "def", "del",
		"elif", "else", "except", "finally", "for", "from", "as", "async", "await", "nonlocal",
		"global", "if", "import", "in", "is", "lambda", "not", "or",
		"pass", "raise", "return", "try", "while", "with",
		"yield", "True", "False", "None"
		};
	
	private final static char[] operators = {
		'(', ')', '{', '}', '.', ',', ';', '=', '+', '-',
		'/', '*', '&', '!', '|', ':', '[', ']', '<', '>',
		'~', '%', '^'
	}; // no ternary operator ? :


	@Override
	public boolean isWordStart(char c){
		return (c == '@');
	}
	
	@Override
	public boolean isLineAStart(char c){
		return false;
	}
	
	@Override
	public boolean isLineBStart(char c){
		return (c == '#');
	}

	@Override
	public boolean isLineStart(char c0, char c1){
		return false;
	}

	@Override
	public boolean isMultilineStartDelimiter(char c0, char c1){
		return false;
	}
	
	public static LanguagePython getInstance(){
		if(_theOne == null){
			_theOne = new LanguagePython();
		}
		return _theOne;
	}
	
	private LanguagePython(){
		setKeywords(keywords);
		setOperators(operators);
	}

    @Override
    public boolean isProgLang() { return true; }

    private static final Set<String> keywordSet = new HashSet<>(Arrays.asList(keywords));
    // Block indentation ignores whole string literals, including interpolation fields.
    private static final Pattern tokens = Pattern.compile(
        "(#[^\\r\\n]*)|((?i:[rubf]{0,2})(?:"
        + "\"\"\"(?:\\\\.|(?!\"\"\")[^\\\\])*+(?:\"\"\"|\\\\?\\z)"
        + "|'''(?:\\\\.|(?!''')[^\\\\])*+(?:'''|\\\\?\\z)"
        + "|\"(?:\\\\.|[^\"\\\\\\r\\n])*+\"?|'(?:\\\\.|[^'\\\\\\r\\n])*+'?))"
        + "|([\\p{L}_][\\p{L}\\p{N}_]*)|([0-9][\\w.]*)|(\\s+)|([\\s\\S])", Pattern.DOTALL);

    private static final Pattern stringStart = Pattern.compile("(?i:br|rb|fr|rf|r|u|b|f)?(\"\"\"|'''|\"|')");
    private static final Pattern codeTokens = Pattern.compile(
        "(#[^\\r\\n]*)|([\\p{L}_][\\p{L}\\p{N}_]*)|([0-9][\\w.]*)|(\\s+)|([\\s\\S])");

    private interface Mode { }
    private static final class StringMode implements Mode {
        final String delimiter;
        final boolean formatted, raw;
        StringMode(String delimiter, String prefix) {
            this.delimiter = delimiter;
            formatted = prefix.indexOf('f') >= 0 || prefix.indexOf('F') >= 0;
            raw = prefix.indexOf('r') >= 0 || prefix.indexOf('R') >= 0;
        }
    }
    private static final class FieldMode implements Mode {
        final StringMode owner;
        int depth;
        boolean format;
        FieldMode(StringMode owner) { this.owner = owner; }
    }

    public boolean opensBlock(CharSequence prefix) {
        Matcher matcher = tokens.matcher(prefix);
        boolean block = false;
        while (matcher.find()) {
            if (matcher.group(1) != null) continue;
            if (matcher.group(5) != null) {
                if (matcher.group().indexOf('\n') >= 0 || matcher.group().indexOf('\r') >= 0) block = false;
            } else block = matcher.group(6) != null && ":".equals(matcher.group());
        }
        return block;
    }

    @Override
    public Lexer newLexer(CharSeqReader reader) {
        return new Lexer() {
            private Reader source = reader;
            private String text;
            private Matcher code, opening;
            private int offset;
            private final ArrayDeque<Mode> modes = new ArrayDeque<>();
            private String token = "";

            @Override public int yylex() throws IOException {
                if (text == null) {
                    StringBuilder input = new StringBuilder();
                    char[] buffer = new char[4096];
                    for (int count; (count = source.read(buffer)) != -1;) input.append(buffer, 0, count);
                    text = input.toString();
                    code = codeTokens.matcher(text);
                    opening = stringStart.matcher(text);
                }
                while (offset < text.length()) {
                    Mode mode = modes.peek();
                    if (mode instanceof StringMode) {
                        int result = string((StringMode) mode, offset);
                        if (result != -1) return result;
                        continue;
                    }
                    FieldMode field = mode instanceof FieldMode ? (FieldMode) mode : null;
                    char c = text.charAt(offset);
                    if (field != null) {
                        if (field.format) {
                            int end = offset;
                            while (end < text.length() && text.charAt(end) != '{' && text.charAt(end) != '}'
                                && !text.startsWith(field.owner.delimiter, end))
                                end = text.charAt(end) == '\\' ? escapeEnd(field.owner, end) : end + 1;
                            if (end > offset) return take(end, STRING_LITERAL);
                            if (text.startsWith(field.owner.delimiter, offset)) { modes.pop(); continue; }
                            if (c == '{') modes.push(new FieldMode(field.owner));
                            else modes.pop();
                            return take(offset + 1, OPERATOR);
                        }
                        if (c == '}' && field.depth == 0) { modes.pop(); return take(offset + 1, OPERATOR); }
                        if (c == ':' && field.depth == 0) { field.format = true; return take(offset + 1, OPERATOR); }
                    }
                    opening.region(offset, text.length());
                    if (opening.lookingAt()) {
                        StringMode literal = new StringMode(opening.group(1), opening.group());
                        modes.push(literal);
                        return string(literal, opening.end());
                    }
                    code.region(offset, text.length());
                    code.lookingAt();
                    int kind;
                    if (code.group(1) != null) kind = COMMENT;
                    else if (code.group(2) != null) kind = keywordSet.contains(code.group()) ? KEYWORD : IDENTIFIER;
                    else if (code.group(3) != null) kind = FLOATING_POINT_LITERAL;
                    else if (code.group(4) != null) kind = WHITE_SPACE;
                    else {
                        kind = OPERATOR;
                        if (field != null) {
                            if (c == '(' || c == '[' || c == '{') field.depth++;
                            else if ((c == ')' || c == ']' || c == '}') && field.depth > 0) field.depth--;
                        }
                    }
                    return take(code.end(), kind);
                }
                token = "";
                return EOF;
            }

            private int string(StringMode mode, int end) {
                while (end < text.length()) {
                    if (text.startsWith(mode.delimiter, end)) {
                        modes.pop();
                        return take(end + mode.delimiter.length(), STRING_LITERAL);
                    }
                    char c = text.charAt(end);
                    if (mode.delimiter.length() == 1 && (c == '\n' || c == '\r')) {
                        modes.pop();
                        return end > offset ? take(end, STRING_LITERAL) : -1;
                    }
                    if (mode.formatted && (c == '{' || c == '}')) {
                        if (end + 1 < text.length() && text.charAt(end + 1) == c) { end += 2; continue; }
                        if (c == '{') {
                            if (end > offset) return take(end, STRING_LITERAL);
                            modes.push(new FieldMode(mode));
                            return take(end + 1, OPERATOR);
                        }
                    }
                    end = c == '\\' ? escapeEnd(mode, end) : end + 1;
                }
                return take(end, STRING_LITERAL);
            }

            private int escapeEnd(StringMode mode, int start) {
                if (start + 1 >= text.length()) return start + 1;
                char next = text.charAt(start + 1);
                if (!mode.raw && next == 'N' && start + 2 < text.length() && text.charAt(start + 2) == '{') {
                    int close = text.indexOf('}', start + 3);
                    if (close >= 0) return close + 1;
                }
                return mode.formatted && (next == '{' || next == '}') ? start + 1 : start + 2;
            }

            private int take(int end, int kind) {
                token = text.substring(offset, end);
                offset = end;
                return kind;
            }
            @Override public String yytext() { return token; }
            @Override public int yylength() { return token.length(); }
            @Override public void yyreset(Reader value) {
                source = value; text = null; offset = 0; modes.clear(); token = "";
            }
        };
    }
}
