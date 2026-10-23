package cn.rbc.termuc

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SoraViewTest {
    private lateinit var editor: TextEditor

    @Before fun createEditor() {
        Application.completion = "n"
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        editor = TextEditor(activity)
        activity.setContentView(editor)
        editor.layout(0, 0, 800, 600)
    }

    @After fun releaseEditor() {
        if (::editor.isInitialized) editor.release()
    }

    @Test fun tabsWithTheSameLanguageKeepTheirOwnCompletionWords() {
        val host = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val first = TextEditor(host.get())
        val second = TextEditor(host.get())
        host.get().setContentView(android.widget.FrameLayout(host.get()).apply { addView(first); addView(second) })
        try {
            val language = cn.rbc.codeeditor.lang.LanguagePython.getInstance()
            first.setLanguage(language)
            second.setLanguage(language)
            first.paste("alpha_local = 1")
            second.paste("beta_local = 2")
            val analyze = TextEditor::class.java.getDeclaredMethod("analyzeSyntax").apply { isAccessible = true }
            val lexer = TextEditor::class.java.getDeclaredField("lexer").apply { isAccessible = true }
            for (view in listOf(first, second)) {
                analyze.invoke(view)
                (lexer.get(view) as java.util.concurrent.ExecutorService).submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(first.completionWords.contains("alpha_local"))
            assertFalse(first.completionWords.contains("beta_local"))
            assertTrue(second.completionWords.contains("beta_local"))
            assertFalse(second.completionWords.contains("alpha_local"))
        } finally { first.release(); second.release(); host.pause().stop().destroy() }
    }

    @Test fun nativeInputUndoAndSaveBoundaryMatchTheRecoveryDocument() {
        editor.soraEditor.commitText("original", false, false)
        editor.text.markVersion()
        editor.setEdited(false)
        editor.soraEditor.commitText(" draft", false, false)
        assertTrue(editor.isEdited)
        assertEquals("original draft", editor.text.toString())
        editor.undo()
        assertEquals("original", editor.soraEditor.text.toString())
        assertFalse(editor.isEdited)
        editor.redo()
        assertEquals("original draft", editor.soraEditor.text.toString())
        assertTrue(editor.isEdited)
    }

    @Test fun pythonFormattingPreservesSignificantIndentation() {
        editor.setLanguage(cn.rbc.codeeditor.lang.LanguagePython.getInstance())
        val source = "if True:\n    print('kept')\n        # indentation remains untouched\n"
        editor.paste(source)
        editor.format()
        assertEquals(source, editor.soraEditor.text.toString())
        assertEquals(source, editor.text.toString())
    }

    @Test fun pythonNewlineIndentationHonorsCommentsAndMultilineStrings() {
        editor.setLanguage(cn.rbc.codeeditor.lang.LanguagePython.getInstance())
        editor.setTabSpaces(4)
        for ((text, expected) in listOf(
            "if True: # block" to 4,
            "# comment:" to 0,
            "doc = '''text\nnot a block:" to 0,
            "if True:\n    " to 0,
            "value = 'not a block:'" to 0
        )) {
            val native = editor.soraEditor
            val content = io.github.rosemoe.sora.text.Content(text)
            val line = content.lineCount - 1
            assertEquals(text, expected, native.editorLanguage.getIndentAdvance(
                io.github.rosemoe.sora.text.ContentReference(content), line, content.getColumnCount(line)))
        }
        editor.setLanguage(cn.rbc.codeeditor.lang.c.CppLanguage.getInstance())
        val nativeSource = io.github.rosemoe.sora.text.Content("int main() {")
        assertEquals(4, editor.soraEditor.editorLanguage.getIndentAdvance(
            io.github.rosemoe.sora.text.ContentReference(nativeSource), 0, nativeSource.getColumnCount(0)))
    }

    @Test fun diagnosticsRemainAvailableWithoutAutomaticTypingOverlays() {
        val native = editor.soraEditor
        val tooltip = native.getComponent(io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow::class.java)
        assertFalse("Diagnostic tooltips must not interrupt typing", tooltip.isEnabled)
        native.commitText("bad", false, false)
        val warning = cn.rbc.codeeditor.util.ErrSpan().apply {
            stl = 1; enl = 1; stc = 0; enc = 3
            severity = cn.rbc.codeeditor.util.ErrSpan.WARNING
            msg = "Example warning"
        }
        editor.text.setDiag(listOf(warning))
        editor.postInvalidateOnAnimation()
        val regions = arrayListOf<io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion>()
        native.diagnostics!!.queryInRegion(regions, 0, 3)
        assertEquals(1, regions.size)
        assertEquals(io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING, regions[0].severity)
        assertNotNull(regions[0].detail)
        editor.setSelection(1)
        native.commitText("x", false, false)
        assertEquals("bxad", editor.text.toString())
        assertEquals("Example warning", editor.text.diag[0].msg)
        assertFalse(tooltip.isShowing)
    }

    @Test fun hardwareBackspaceRemovesBlankLineIndentBeforeItsNewlineAndCanUndo() {
        val native = editor.soraEditor
        editor.setTabSpaces(4)
        native.commitText("before\n        \nafter", false, false)
        editor.text.markVersion()
        editor.setSelection("before\n        ".length)
        native.setLayoutBusy(false)
        fun backspace() = native.onKeyDown(android.view.KeyEvent.KEYCODE_DEL,
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL))
        assertTrue(backspace())
        assertEquals("before\n    \nafter", editor.text.toString())
        assertTrue(editor.isEdited)
        editor.undo()
        assertEquals("before\n        \nafter", native.text.toString())
        assertFalse(editor.isEdited)
        editor.setSelection("before\n        ".length)
        backspace()
        backspace()
        assertEquals("before\n\nafter", editor.text.toString())
        backspace()
        assertEquals("before\nafter", editor.text.toString())
    }

    @Test fun imeBackspaceRemovesOneLiteralTabBeforeRemovingTheLine() {
        editor.setUseSpace(false)
        editor.soraEditor.commitText("before\n\t\t\nafter", false, false)
        editor.setSelection("before\n\t\t".length)
        editor.soraEditor.setLayoutBusy(false)
        val input = editor.soraEditor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
        assertTrue(input.deleteSurroundingText(1, 0))
        assertEquals("before\n\t\nafter", editor.text.toString())
        assertTrue(input.deleteSurroundingText(1, 0))
        assertEquals("before\n\nafter", editor.text.toString())
    }

    @Test fun backspaceUsesConfiguredIndentWidthButDeletesTrailingSpacesIndividually() {
        editor.setTabSpaces(2)
        editor.soraEditor.commitText("word    \n    value", false, false)
        editor.setSelection("word    \n    ".length)
        editor.delete()
        assertEquals("word    \n  value", editor.text.toString())
        editor.setSelection("word    ".length)
        editor.delete()
        assertEquals("word   \n  value", editor.text.toString())
    }

    @Test fun replacingSelectionIsOneUndoStep() {
        editor.soraEditor.commitText("original", false, false)
        editor.text.markVersion()
        editor.setSelection(0, 8)
        editor.soraEditor.commitText("replacement", false, false)
        editor.undo()
        assertEquals("original", editor.soraEditor.text.toString())
        assertFalse(editor.isEdited)
    }

    @Test fun nativeBatchEditsUndoTogether() {
        editor.soraEditor.commitText("one\ntwo", false, false)
        editor.text.markVersion()
        val content = editor.soraEditor.text
        content.beginBatchEdit()
        content.insert(0, 0, "  ")
        content.insert(1, 0, "  ")
        content.endBatchEdit()
        editor.undo()
        assertEquals("one\ntwo", editor.soraEditor.text.toString())
        assertFalse(editor.isEdited)
    }

    @Test fun loneCrLinesKeepGotoAndBreakpointPositionsThroughEditingAndUndo() {
        editor.soraEditor.commitText("one\rtwo\rthree", false, false)
        editor.text.markVersion()
        editor.text.markLine(1)
        editor.text.markLine(3)
        editor.gotoLine(3)
        assertEquals(8, editor.selectionStart)
        editor.soraEditor.commitText("\r", false, false)
        assertEquals(1, editor.text.getMark(0))
        assertEquals(4, editor.text.getMark(1))
        editor.undo()
        assertEquals(1, editor.text.getMark(0))
        assertEquals(3, editor.text.getMark(1))
    }

    @Test fun recoveredTextIsRenderedAndRemainsDirtyAfterUndo() {
        editor.text.setText("draft\r\n🧪")
        editor.text.clearSpans()
        editor.text.analyzeWordWrap()
        editor.text.markUnsaved()
        editor.refreshDocument()
        assertEquals("draft\r\n🧪", editor.soraEditor.text.toString())
        editor.setSelection(editor.soraEditor.text.length)
        editor.paste("!")
        editor.undo()
        assertEquals("draft\r\n🧪", editor.soraEditor.text.toString())
        assertTrue(editor.isEdited)
    }

    @Test fun recoveredTextCanLoadWhileNativeLayoutIsStillInitializing() {
        editor.soraEditor.setLayoutBusy(true)
        editor.text.setText("draft\r\n🧪\r\nsecond line")
        editor.text.clearSpans()
        editor.text.analyzeWordWrap()
        editor.text.markUnsaved()
        editor.refreshDocument()
        assertEquals("draft\r\n🧪\r\nsecond line", editor.soraEditor.text.toString())
        assertTrue(editor.isEdited)
    }

    @Test fun enterPreservesLoadedCrLfLineEndings() {
        editor.text.setText("one\r\ntwo")
        editor.text.clearSpans()
        editor.text.analyzeWordWrap()
        editor.refreshDocument()
        editor.setSelection(editor.soraEditor.text.length)
        assertEquals("\r\n", editor.soraEditor.lineSeparator.content)
        // This editor is intentionally unattached, so its asynchronous layout cannot finish posting.
        editor.soraEditor.setLayoutBusy(false)
        editor.soraEditor.onKeyDown(android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        assertEquals("one\r\ntwo\r\n", editor.text.toString())
        editor.undo()
        assertEquals("one\r\ntwo", editor.soraEditor.text.toString())
    }

    @Test fun stylesStillRenderAfterNativeInputAndAnalysis() {
        editor.setLanguage(cn.rbc.codeeditor.lang.c.CLanguage.getInstance())
        editor.soraEditor.commitText("int main() {\nreturn 0;\n}", false, false)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertEquals(editor.text.toString(), editor.soraEditor.text.toString())
    }

    @Test fun materialTextColorsMeetContrastOnNormalCurrentAndHighlightedLines() {
        val palettes = listOf(
            listOf("#FFFBFE", "#1C1B1F", "#6750A4", "#E8DEF8", "#1D192B", "#49454F", "#ECE6F0"),
            listOf("#1C1B1F", "#E6E1E5", "#D0BCFF", "#4A4458", "#E8DEF8", "#CAC4D0", "#2B2930"),
            listOf("#FBFDF2", "#191D15", "#4C662B", "#D9E7CB", "#141E0C", "#44483D", "#E8EBDD"),
            listOf("#12150E", "#E2E3D8", "#B1D18A", "#3D4B33", "#D9E7CB", "#C5C8BA", "#282B23")
        ).map { values -> values.map(android.graphics.Color::parseColor) }
        for (palette in palettes) for (pure in listOf(false, true)) {
            editor.setPureMode(pure)
            editor.setMaterialColors(palette[0], palette[1], palette[2], palette[3], palette[4], palette[5], palette[6])
            val scheme = editor.soraEditor.colorScheme
            val background = scheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND)
            val line = androidx.core.graphics.ColorUtils.compositeColors(
                scheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE), background)
            for (token in listOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 20, 21, 30, 40, 50, 51)) {
                for (surface in listOf(background, line, palette[3])) {
                    val contrast = androidx.core.graphics.ColorUtils.calculateContrast(scheme.getColor(100 + token), surface)
                    assertTrue("Token $token, pure $pure, surface ${surface.toUInt().toString(16)}: $contrast", contrast >= 4.5)
                }
            }
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                scheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_SELECTED), palette[3]) >= 4.5)
        }
    }

    @Test fun changingMaterialThemePreservesBufferSelectionUndoAndDirtyState() {
        editor.paste("original")
        editor.text.markVersion()
        editor.setEdited(false)
        editor.paste(" draft")
        editor.setSelection(2, 3)
        val native = editor.soraEditor.text
        val document = editor.text
        editor.setMaterialColors(0xff1c1b1f.toInt(), 0xffe6e1e5.toInt(), 0xffd0bcff.toInt(),
            0xff4a4458.toInt(), 0xffe8def8.toInt(), 0xffcac4d0.toInt(), 0xff2b2930.toInt())
        assertSame(native, editor.soraEditor.text)
        assertSame(document, editor.text)
        assertTrue(editor.colorScheme.isDark)
        assertEquals("original draft", editor.text.toString())
        assertEquals(2, editor.selectionStart)
        assertEquals(5, editor.selectionEnd)
        assertTrue(editor.isEdited)
        editor.undo()
        assertEquals("original", editor.text.toString())
        assertFalse(editor.isEdited)
    }
}
