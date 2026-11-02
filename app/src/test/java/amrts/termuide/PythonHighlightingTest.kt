package amrts.termuide

import cn.rbc.codeeditor.lang.LanguagePython
import cn.rbc.codeeditor.util.CharSeqReader
import cn.rbc.codeeditor.util.Lexer
import org.junit.Assert.*
import org.junit.Test

class PythonHighlightingTest {
    private fun tokens(text: String): List<Pair<Int, String>> {
        val lexer = LanguagePython.getInstance().newLexer(CharSeqReader(text))
        val result = mutableListOf<Pair<Int, String>>()
        while (true) {
            val kind = lexer.yylex()
            if (kind == Lexer.EOF) break
            assertTrue("Every token must advance", lexer.yylength() > 0)
            result += kind to lexer.yytext()
            assertTrue("Lexer must finish", result.size <= text.length)
        }
        assertEquals("Highlighting must preserve every character", text, result.joinToString("") { it.second })
        return result
    }

    @Test fun screenshotExpressionUsesCodeColorsInsideTheString() {
        val result = tokens("print(f\"Yoo Big {name[:1]}\");")
        assertTrue(result.contains(Lexer.STRING_LITERAL to "f\"Yoo Big "))
        assertTrue(result.contains(Lexer.OPERATOR to "{"))
        assertTrue(result.contains(Lexer.IDENTIFIER to "name"))
        assertTrue(result.contains(Lexer.OPERATOR to ":"))
        assertTrue(result.contains(Lexer.FLOATING_POINT_LITERAL to "1"))
        assertTrue(result.contains(Lexer.OPERATOR to "}"))
        assertEquals(Lexer.OPERATOR to ";", result.last())
    }

    @Test fun escapedBracesRemainLiteralAndFormatFieldsAreHighlighted() {
        val result = tokens("f\"{{literal}} {value!r:>{width}.{precision}f}\"")
        assertTrue(result.contains(Lexer.STRING_LITERAL to "f\"{{literal}} "))
        for (name in listOf("value", "width", "precision"))
            assertTrue(result.contains(Lexer.IDENTIFIER to name))
        for (literal in listOf(">", ".", "f"))
            assertTrue(result.contains(Lexer.STRING_LITERAL to literal))
        val quotedFill = tokens("f\"{value:\\\"^10} {other}\"")
        assertTrue(quotedFill.contains(Lexer.STRING_LITERAL to "\\\"^10"))
        assertTrue(quotedFill.contains(Lexer.IDENTIFIER to "other"))
    }

    @Test fun sameQuoteStringsNestedFStringsAndDictionaryBracesRemainBalanced() {
        for (source in listOf(
            "f\"{mapping[\"key\"]} {other}\"",
            "f\"{f\"{inner}\"} {other}\"",
            "f\"{ {'key': [item]} } {other}\""
        )) {
            val result = tokens(source)
            assertTrue(result.contains(Lexer.IDENTIFIER to "other"))
            assertTrue(result.any { it.first == Lexer.IDENTIFIER && it.second in listOf("mapping", "inner", "item") })
            assertEquals(Lexer.STRING_LITERAL to "\"", result.last())
        }
    }

    @Test fun rawPrefixesUnicodeEscapesAndTripleQuotesKeepTheirBoundaries() {
        val escaped = tokens("f\"\\N{GREEK SMALL LETTER ALPHA} {value}\"")
        assertFalse(escaped.any { it.second == "GREEK" })
        assertTrue(escaped.contains(Lexer.IDENTIFIER to "value"))
        for (prefix in listOf("fr", "rf", "FR", "Rf")) {
            val result = tokens("$prefix\"\\N{name}\"")
            assertTrue(result.contains(Lexer.IDENTIFIER to "name"))
        }
        val multiline = tokens("f'''hello\n{value}\nworld'''")
        assertTrue(multiline.contains(Lexer.IDENTIFIER to "value"))
        assertTrue(multiline.contains(Lexer.STRING_LITERAL to "\nworld'''"))
        val backslash = tokens("f\"\\{value}\"")
        assertTrue(backslash.contains(Lexer.IDENTIFIER to "value"))
    }

    @Test fun expressionCommentsAndKeywordsDoNotConsumeTheClosingString() {
        val result = tokens("f\"{value if ready else None # comment with } and \\\"\n}\"")
        for (word in listOf("if", "else", "None")) assertTrue(result.contains(Lexer.KEYWORD to word))
        assertTrue(result.any { it.first == Lexer.COMMENT && it.second.startsWith("# comment") })
        assertEquals(Lexer.STRING_LITERAL to "\"", result.last())
    }

    @Test fun ordinaryAndIncompleteStringsRetainTextAndResetClearsModes() {
        for (source in listOf("'plain {literal}'", "b\"{bytes}\"", "r'''raw {literal}'''"))
            assertEquals(listOf(Lexer.STRING_LITERAL to source), tokens(source))
        for (source in listOf("f\"", "f\"text {", "f\"{value:", "f\"{value:{width}", "f\"unfinished\nnext = 1"))
            tokens(source)
        val lexer = LanguagePython.getInstance().newLexer(CharSeqReader("f\"{unfinished"))
        while (lexer.yylex() != Lexer.EOF) { }
        lexer.yyreset(CharSeqReader("return 42"))
        assertEquals(Lexer.KEYWORD, lexer.yylex())
        assertEquals("return", lexer.yytext())
    }
}
