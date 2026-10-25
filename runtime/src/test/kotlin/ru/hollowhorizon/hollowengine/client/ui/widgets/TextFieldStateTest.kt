package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinCodeSyntax
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the consolidated [TextFieldState] edit model: typing, deletion, newlines,
 * indentation, multi-caret and undo.
 */
class TextFieldStateTest {
    private fun field(
        text: String = "",
        caret: Int = text.length,
        multiline: Boolean = true,
        autoPairs: Boolean = false,
        multiCaret: Boolean = true,
        indentSize: Int? = 4,
        filter: UiTextInputFilter = UiTextInputFilter.ANY,
    ) = TextFieldState(
        initialText = text,
        initialCaret = caret,
        multiline = multiline,
        autoPairs = autoPairs,
        multiCaret = multiCaret,
        indentSize = indentSize,
        filter = filter,
    )

    private fun TextFieldState.type(chars: String) = chars.forEach { typeCharacter(it) }
    private val TextFieldState.caretPositions get() = caretRanges.map { it.position }

    @Test
    fun `typing inserts characters and advances the caret`() {
        val state = field()
        state.type("abc")
        assertEquals("abc", state.text)
        assertEquals(3, state.caret)
    }

    @Test
    fun `insert into the middle keeps surrounding text`() {
        val state = field("ac", caret = 1)
        state.insert("b")
        assertEquals("abc", state.text)
        assertEquals(2, state.caret)
    }

    @Test
    fun `leading and consecutive spaces are preserved`() {
        val state = field()
        state.insert("    a  b")
        assertEquals("    a  b", state.text)
    }

    @Test
    fun `single-line field collapses newlines to spaces`() {
        val state = field(multiline = false)
        state.insert("a\nb")
        assertEquals("a b", state.text)
        assertFalse(state.insertNewlineWithIndent())
    }

    @Test
    fun `backspace removes the character before the caret`() {
        val state = field("abc")
        assertTrue(state.backspace())
        assertEquals("ab", state.text)
        assertEquals(2, state.caret)
    }

    @Test
    fun `backspace at the start of the document is a no-op`() {
        val state = field("abc", caret = 0)
        assertFalse(state.backspace())
        assertEquals("abc", state.text)
    }

    @Test
    fun `backspace deletes the active selection`() {
        val state = field("abcdef")
        state.setSelection(anchor = 1, active = 4)
        assertTrue(state.backspace())
        assertEquals("aef", state.text)
        assertEquals(1, state.caret)
    }

    @Test
    fun `ctrl-backspace deletes the preceding word`() {
        val state = field("foo bar")
        assertTrue(state.backspace(word = true))
        assertEquals("foo ", state.text)
    }

    @Test
    fun `delete removes the character after the caret`() {
        val state = field("abc", caret = 0)
        assertTrue(state.deleteForward())
        assertEquals("bc", state.text)
        assertEquals(0, state.caret)
    }

    @Test
    fun `delete at the end of the document is a no-op`() {
        val state = field("abc")
        assertFalse(state.deleteForward())
    }

    @Test
    fun `ctrl-delete removes the following word`() {
        val state = field("foo bar", caret = 0)
        assertTrue(state.deleteForward(word = true))
        assertEquals(" bar", state.text)
    }

    @Test
    fun `enter does not remove literal or comment whitespace near braces`() {
        for (source in listOf("val text = \"{  }\"", "// comment {  }", "val text = \"\"\"{  }\"\"\"")) {
            val position = source.indexOf("{  }") + 2
            val state = field(source, caret = position).apply { syntax = KotlinCodeSyntax }
            assertTrue(state.insertNewlineWithIndent())
            assertEquals(source.substring(0, position) + "\n" + source.substring(position), state.text)
        }
    }

    @Test
    fun `enter removes inline block padding and undo restores it`() {
        val state = field("    functionWithLambda {   }", caret = 26)
        val original = state.text
        assertTrue(state.insertNewlineWithIndent())
        assertEquals("    functionWithLambda {\n        \n    }", state.text)
        assertEquals(state.text.indexOf("\n    }"), state.caret)
        assertTrue(state.undo())
        assertEquals(original, state.text)
        assertEquals(26, state.caret)
    }

    @Test
    fun `each caret gets its own newline indent`() {
        val state = field("one { }\n    two { }")
        state.setCarets(listOf(UiTextCaret(6), UiTextCaret(17)))
        assertTrue(state.insertNewlineWithIndent())
        assertEquals("one {\n    \n}\n    two {\n        \n    }", state.text)
        assertEquals(listOf(10, 31), state.caretRanges.map { it.position })
    }

    @Test
    fun `enter preserves tab indentation and trims opening delimiter padding`() {
        val state = field("\tfoo {  ")
        assertTrue(state.insertNewlineWithIndent())
        assertEquals("\tfoo {\n\t    ", state.text)
    }

    @Test
    fun `enter preserves the current line indent`() {
        val state = field("    foo")
        assertTrue(state.insertNewlineWithIndent())
        assertEquals("    foo\n    ", state.text)
        assertEquals(state.text.length, state.caret)
    }

    @Test
    fun `enter after an opening brace increases indent`() {
        val state = field("foo {")
        state.insertNewlineWithIndent()
        assertEquals("foo {\n    ", state.text)
    }

    @Test
    fun `enter between a brace pair opens an indented block`() {
        val state = field("foo {}", caret = 5)
        state.insertNewlineWithIndent()
        assertEquals("foo {\n    \n}", state.text)
        assertEquals(state.text.indexOf('\n', 6), state.caret)
    }

    @Test
    fun `indent with no selection inserts spaces at the caret`() {
        val state = field("foo", caret = 0)
        assertTrue(state.indent())
        assertEquals("    foo", state.text)
        assertEquals(4, state.caret)
    }

    @Test
    fun `indent shifts every selected line`() {
        val state = field("a\nb\nc")
        state.setSelection(anchor = 0, active = 5)
        assertTrue(state.indent())
        assertEquals("    a\n    b\n    c", state.text)
    }

    @Test
    fun `unindent removes one level of leading spaces per line`() {
        val state = field("        a\n    b")
        state.setSelection(anchor = 0, active = state.text.length)
        assertTrue(state.unindent())
        assertEquals("    a\nb", state.text)
    }

    @Test
    fun `unindent ignores lines without leading spaces`() {
        val state = field("ab", caret = 0)
        state.setSelection(anchor = 0, active = 2)
        assertFalse(state.unindent())
        assertEquals("ab", state.text)
    }

    @Test
    fun `typing at multiple carets inserts at each and shifts trailing carets`() {
        val state = field("ab", caret = 0)
        state.addCaret(1)
        assertEquals(listOf(0, 1), state.caretPositions)
        state.typeCharacter('X')
        assertEquals("XaXb", state.text)
        assertEquals(listOf(1, 3), state.caretPositions)
    }

    @Test
    fun `backspace at multiple carets deletes at each`() {
        val state = field("aXbX")
        state.setCarets(listOf(UiTextCaret(2), UiTextCaret(4)))
        state.backspace()
        assertEquals("ab", state.text)
    }

    @Test
    fun `adding a caret at an existing caret removes it`() {
        val state = field("abc", caret = 0)
        state.addCaret(2)
        assertEquals(listOf(0, 2), state.caretPositions)
        state.addCaret(2)
        assertEquals(listOf(0), state.caretPositions)
    }

    @Test
    fun `select all then type replaces the whole document`() {
        val state = field("hello world")
        state.selectAll()
        assertEquals("hello world", state.selectedText())
        state.typeCharacter('!')
        assertEquals("!", state.text)
    }

    @Test
    fun `selected text joins multiple selections with newlines`() {
        val state = field("abcdef")
        state.setCarets(listOf(UiTextCaret(1, 0), UiTextCaret(5, 4)))
        assertEquals("a\ne", state.selectedText())
    }

    @Test
    fun `paste distributes matching clipboard lines across carets`() {
        val state = field("A\nB")
        state.setCarets(listOf(UiTextCaret(1), UiTextCaret(3)))
        assertTrue(state.paste("1\n2"))
        assertEquals("A1\nB2", state.text)
        assertEquals(listOf(2, 5), state.caretRanges.map { it.position })
    }

    @Test
    fun `duplicate selections inserts each selected text after itself and selects inserted copies`() {
        val state = field("foo bar")
        state.setCarets(listOf(UiTextCaret(3, 0), UiTextCaret(7, 4)))
        assertTrue(state.duplicateSelections())
        assertEquals("foofoo barbar", state.text)
        assertEquals(listOf(UiTextCaret(6, 3), UiTextCaret(13, 10)), state.caretRanges)
        assertEquals("foo\nbar", state.selectedText())
    }

    @Test
    fun `duplicate selections is a no-op without selection`() {
        val state = field("foo")
        assertFalse(state.duplicateSelections())
        assertEquals("foo", state.text)
    }

    @Test
    fun `typing an opening bracket inserts the pair and places the caret inside`() {
        val state = field(autoPairs = true)
        state.typeCharacter('(')
        assertEquals("()", state.text)
        assertEquals(1, state.caret)
    }

    @Test
    fun `typing the closing bracket over an auto-pair skips instead of inserting`() {
        val state = field(autoPairs = true)
        state.typeCharacter('(')
        state.typeCharacter(')')
        assertEquals("()", state.text)
        assertEquals(2, state.caret)
    }

    @Test
    fun `backspace removes both halves of an auto-pair`() {
        val state = field(autoPairs = true)
        state.typeCharacter('(')
        assertTrue(state.backspace())
        assertEquals("", state.text)
    }

    @Test
    fun `integer filter rejects non-numeric input`() {
        val state = field(filter = UiTextInputFilter.INTEGER)
        assertFalse(state.insert("a"))
        assertTrue(state.insert("42"))
        assertEquals("42", state.text)
    }

    @Test
    fun `rapid typing stays ordered and undoes as one history group`() {
        var clock = 0L
        val state = TextFieldState(historyMergeWindowNanos = 1_000L, nanoTime = { clock++ })

        repeat(200) { state.insert("a") }

        assertEquals("a".repeat(200), state.text)
        assertEquals(200, state.caret)
        assertTrue(state.undo())
        assertEquals("", state.text)
        assertTrue(state.redo())
        assertEquals("a".repeat(200), state.text)
        assertEquals(200, state.caret)
    }

    @Test
    fun `undo reverts an edit and redo re-applies it`() {
        var clock = 0L
        val state = TextFieldState(historyMergeWindowNanos = 0L, nanoTime = { clock++ })
        state.insert("a")
        state.insert("b")
        assertEquals("ab", state.text)

        assertTrue(state.undo())
        assertEquals("a", state.text)
        assertTrue(state.undo())
        assertEquals("", state.text)
        assertFalse(state.undo())

        assertTrue(state.redo())
        assertEquals("a", state.text)
        assertTrue(state.redo())
        assertEquals("ab", state.text)
    }

    @Test
    fun `setText resets undo history`() {
        val state = field("abc")
        state.insert("d")
        state.setText("fresh")
        assertEquals("fresh", state.text)
        assertFalse(state.undo())
        assertEquals("fresh", state.text)
    }

    @Test
    fun `clearing a selection keeps the caret at its position`() {
        val state = field("abcdef")
        state.setSelection(anchor = 1, active = 4)
        assertTrue(state.hasSelection)
        state.clearSelection()
        assertFalse(state.hasSelection)
        assertEquals(4, state.caret)
        assertNull(state.selectionAnchor)
    }

    @Test
    fun `caret ranges can be removed and the last range can be updated`() {
        val state = field("abcdef", caret = 0)
        state.addCaretRange(UiTextCaret(4, 1))
        assertEquals("bcd", state.selectedText())

        state.updateLastCaretRange(anchor = 2, active = 5)
        assertEquals("cde", state.selectedText())

        assertTrue(state.removeCaretRange(UiTextCaret(5, 2)))
        assertEquals(listOf(UiTextCaret(0)), state.caretRanges)
        assertFalse(state.removeCaretRange(UiTextCaret(5, 2)))

        state.addCaretRange(UiTextCaret(4, 1))
        assertTrue(state.removeCaretRangeAt(3))
        assertEquals(listOf(UiTextCaret(0)), state.caretRanges)
        assertFalse(state.removeCaretRangeAt(3))
    }

    @Test
    fun `adding a selection range removes overlapping ranges`() {
        val state = field("foo bar", caret = 0)
        state.addCaretRange(UiTextCaret(3, 0))
        state.addCaretRange(UiTextCaret(7, 0))
        assertEquals(listOf(UiTextCaret(7, 0)), state.caretRanges)
        assertEquals("foo bar", state.selectedText())
    }

    @Test
    fun `focus state controls caret visibility without changing selection`() {
        val state = field("abcdef")
        state.setSelection(anchor = 1, active = 4)
        assertFalse(state.focused)

        state.focus()
        assertTrue(state.focused)
        assertEquals("bcd", state.selectedText())

        state.unfocus()
        assertFalse(state.focused)
        assertEquals("bcd", state.selectedText())
    }
}
