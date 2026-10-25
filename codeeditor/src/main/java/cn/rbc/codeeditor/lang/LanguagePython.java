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
    // shortcut: lexical highlighting only; f-string expressions use string color until richer Python tooling is added.
    private static final Pattern tokens = Pattern.compile(
        "(#[^\\r\\n]*)|((?i:[rubf]{0,2})(?:"
        + "\"\"\"(?:\\\\.|(?!\"\"\")[^\\\\])*+(?:\"\"\"|\\\\?\\z)"
        + "|'''(?:\\\\.|(?!''')[^\\\\])*+(?:'''|\\\\?\\z)"
        + "|\"(?:\\\\.|[^\"\\\\\\r\\n])*+\"?|'(?:\\\\.|[^'\\\\\\r\\n])*+'?))"
        + "|([\\p{L}_][\\p{L}\\p{N}_]*)|([0-9][\\w.]*)|(\\s+)|([\\s\\S])", Pattern.DOTALL);

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
            private Matcher matcher;
            private String token = "";

            @Override public int yylex() throws IOException {
                if (matcher == null) {
                    StringBuilder text = new StringBuilder();
                    char[] buffer = new char[4096];
                    for (int count; (count = source.read(buffer)) != -1;) text.append(buffer, 0, count);
                    matcher = tokens.matcher(text);
                }
                if (!matcher.find()) { token = ""; return EOF; }
                token = matcher.group();
                if (matcher.group(1) != null) return COMMENT;
                if (matcher.group(2) != null) return STRING_LITERAL;
                if (matcher.group(3) != null) return keywordSet.contains(token) ? KEYWORD : IDENTIFIER;
                if (matcher.group(4) != null) return FLOATING_POINT_LITERAL;
                if (matcher.group(5) != null) return WHITE_SPACE;
                return OPERATOR;
            }
            @Override public String yytext() { return token; }
            @Override public int yylength() { return token.length(); }
            @Override public void yyreset(Reader value) { source = value; matcher = null; token = ""; }
        };
    }
}
