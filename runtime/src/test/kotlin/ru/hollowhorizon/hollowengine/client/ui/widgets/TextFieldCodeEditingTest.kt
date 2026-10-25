package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinCodeSyntax
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFieldCodeEditingTest {
    private fun field(text: String, caret: Int = text.length, size: Int? = 4) =
        TextFieldState(text, caret, multiline = true, indentSize = size, syntax = KotlinCodeSyntax)

    @Test
    fun `every opening delimiter surrounds the selection and undo restores its direction`() {
        for ((opening, closing) in EditorPairClosings) {
            for (backwards in listOf(false, true)) {
                val state = field("before value after")
                state.setSelection(if (backwards) 12 else 7, if (backwards) 7 else 12)
                val originalCarets = state.caretRanges
                assertTrue(state.typeCharacter(opening))
                assertEquals("before ${opening}value$closing after", state.text)
                assertEquals("${opening}value$closing", state.selectedText())
                assertEquals(backwards, state.caret < state.selectionAnchor!!)
                assertTrue(state.undo())
                assertEquals("before value after", state.text)
                assertEquals(originalCarets, state.caretRanges)
                assertTrue(state.redo())
                assertEquals("${opening}value$closing", state.selectedText())
            }
        }
    }

    @Test
    fun `multiline block wrapping indents all selected lines and retains nested indentation`() {
        val state = field("one()\nif (ok) {\n    two()\n}")
        state.selectAll()
        assertTrue(state.typeCharacter('{'))
        assertEquals("{\n    one()\n    if (ok) {\n        two()\n    }\n}", state.text)
        assertEquals(state.text, state.selectedText())
    }

    @Test
    fun `multiline quotes preserve selected whitespace`() {
        val state = field("a\n  b")
        state.selectAll()
        state.typeCharacter('"')
        assertEquals("\"a\n  b\"", state.text)
    }

    @Test
    fun `wrapping within an indented block does not double existing indentation or alter literals`() {
        val state = field("fun run() {\n    one()\n    val raw = \"\"\"\n  literal\n\"\"\"\n}")
        state.setSelection(state.text.indexOf("one()"), state.text.lastIndexOf('\n'))
        state.typeCharacter('{')
        assertEquals("fun run() {\n    {\n        one()\n        val raw = \"\"\"\n  literal\n\"\"\"\n    }\n}", state.text)
    }

    @Test
    fun `mixed selections and empty carets each get their own pair behavior`() {
        val state = field("one two ")
        state.setCarets(listOf(UiTextCaret(3, 0), UiTextCaret(8)))
        assertTrue(state.typeCharacter('('))
        assertEquals("(one) two ()", state.text)
        assertEquals(listOf(UiTextCaret(5, 0), UiTextCaret(11)), state.caretRanges)
    }

    @Test
    fun `backspace removes one indentation step on blank lines and before code`() {
        for (tail in listOf("", "value")) {
            val state = field("header\n        $tail", caret = 15)
            assertTrue(state.backspace())
            assertEquals("header\n    $tail", state.text)
            assertEquals(11, state.caret)
            assertTrue(state.backspace())
            assertEquals("header\n$tail", state.text)
        }
    }

    @Test
    fun `backspace uses configured visual stops for tabs and partial indentation`() {
        for ((prefix, remaining) in listOf("      " to "    ", "\t    " to "\t", "  \t" to "", "\t  " to "\t")) {
            val state = field(prefix + "x", caret = prefix.length)
            state.backspace()
            assertEquals(remaining + "x", state.text)
        }
        val state = field("      x", caret = 6, size = 2)
        state.backspace()
        assertEquals("    x", state.text)
    }

    @Test
    fun `backspace keeps ordinary spaces and supports disabling smart indentation`() {
        for (state in listOf(field("x    "), field("    ", size = null))) {
            val original = state.text
            state.backspace()
            assertEquals(original.dropLast(1), state.text)
        }
    }

    @Test
    fun `backspace handles pairs indentation and ordinary text at separate carets`() {
        val state = field("()\n        value\nx")
        state.setCarets(listOf(UiTextCaret(1), UiTextCaret(11), UiTextCaret(state.text.length)))
        state.backspace()
        assertEquals("\n    value\n", state.text)
        assertTrue(state.undo())
        assertEquals("()\n        value\nx", state.text)
    }

    @Test
    fun `enter on a blank line uses the enclosing block rather than the previous chain`() {
        val state = field("fun run() {\n    Modifier\n        .gap(16.px)\n        ")
        state.insertNewlineWithIndent()
        assertTrue(state.text.endsWith("\n        \n    "))
        state.setText("Modifier\n    .gap(16.px)")
        state.insertNewlineWithIndent()
        assertTrue(state.text.endsWith(".gap(16.px)\n"))
    }

    @Test
    fun `repeated enter after opening a block never adds another indentation level`() {
        val state = field("fun run() {")
        repeat(3) { state.insertNewlineWithIndent() }
        assertEquals("fun run() {\n    \n    \n    ", state.text)
    }

    @Test
    fun `closing a block formats its body and aligns with the matching opener`() {
        val state = field("fun run() {\none()\nif (ok) {\ntwo()\n}\n    ")
        val original = state.text
        state.typeCharacter('}')
        assertEquals("fun run() {\n    one()\n    if (ok) {\n        two()\n    }\n}", state.text)
        assertTrue(state.undo())
        assertEquals(original, state.text)
        assertTrue(state.redo())
        assertTrue(state.text.endsWith("\n}"))
    }

    @Test
    fun `skipping an existing closing delimiter also corrects indentation`() {
        for ((opening, closing) in listOf('{' to '}', '(' to ')', '[' to ']')) {
            val state = field("start$opening\nvalue\n    $closing", caret = 17)
            state.moveCaret(state.text.lastIndex)
            assertTrue(state.typeCharacter(closing))
            assertEquals("start$opening\n    value\n$closing", state.text)
            assertEquals(state.text.length, state.caret)
        }
    }

    @Test
    fun `closing nested blocks at multiple carets composes indentation and retains every caret`() {
        val state = field("outer {\ninner {\ncall()\n    }\n    }")
        val first = state.text.indexOf('}')
        state.setCarets(listOf(UiTextCaret(first), UiTextCaret(state.text.lastIndex)))
        assertTrue(state.typeCharacter('}'))
        assertEquals("outer {\n    inner {\n        call()\n    }\n}", state.text)
        assertEquals(listOf(state.text.indexOf('}') + 1, state.text.length), state.caretRanges.map { it.position })
    }

    @Test
    fun `closing block ignores comment braces and preserves multiline string content`() {
        val source = "fun run() {\n// }\nval raw = \"\"\"\n  { literal }\n    untouched\n\"\"\"\n    "
        val state = field(source)
        state.typeCharacter('}')
        assertEquals("fun run() {\n    // }\n    val raw = \"\"\"\n  { literal }\n    untouched\n\"\"\"\n}", state.text)
        val comment = field("//    ")
        comment.typeCharacter('}')
        assertEquals("//    }", comment.text)
    }

    @Test
    fun `read only fields reject wrapping reindentation and deletion`() {
        val state = field("value")
        state.selectAll()
        state.readOnly = true
        assertFalse(state.typeCharacter('('))
        assertFalse(state.backspace())
        assertFalse(state.insertNewlineWithIndent())
        assertEquals("value", state.text)
    }

    @Test
    fun `closing a block keeps call chains and wrapped expressions one step deeper`() {
        val chain = field("fun f() {\n    list\n        .map {\n            it\n        }\n")
        chain.typeCharacter('}')
        assertEquals("fun f() {\n    list\n        .map {\n            it\n        }\n}", chain.text)

        val wrapped = field("fun f() {\nval x = a +\nb\nlist.map { x ->\nx\n}\n")
        wrapped.typeCharacter('}')
        assertEquals("fun f() {\n    val x = a +\n        b\n    list.map { x ->\n        x\n    }\n}", wrapped.text)
    }

    @Test
    fun `text that is not Kotlin keeps plain indentation and pairs no apostrophe`() {
        val story = TextFieldState("# Start\nSmith: What now\n", multiline = true, indentSize = 4)
        repeat(3) { story.typeCharacter('.') }
        assertEquals("# Start\nSmith: What now\n...", story.text, "a line of dialogue may start with an ellipsis")

        val prose = TextFieldState("don", multiline = true, indentSize = 4)
        prose.typeCharacter('\'')
        prose.typeCharacter('(')
        assertEquals("don'()", prose.text)
    }
}
