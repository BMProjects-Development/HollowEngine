package ru.hollowhorizon.hollowengine.client.ui.widgets

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinCodeSyntax
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextFieldSmartIndentTest {
    private fun field(source: String, size: Int? = 4, autoPairs: Boolean = true): TextFieldState {
        val position = source.indexOf('|')
        return TextFieldState(source.removeRange(position, position + 1), position,
            multiline = true, indentSize = size, autoPairs = autoPairs, syntax = KotlinCodeSyntax)
    }

    @Test
    fun `backspace joins a normally indented expression to the previous line`() {
        val state = field("foo {\n    |bar()")
        assertTrue(state.backspace())
        assertEquals("foo {bar()", state.text)
        assertEquals(5, state.caret)
        assertTrue(state.undo())
        assertEquals("foo {\n    bar()", state.text)
        assertEquals(10, state.caret)
        assertTrue(state.redo())
        assertEquals("foo {bar()", state.text)
    }

    @Test
    fun `backspace removes excess indent before joining at the normal block indent`() {
        val state = field("foo {\n        |bar()")
        state.backspace()
        assertEquals("foo {\n    bar()", state.text)
        state.backspace()
        assertEquals("foo {bar()", state.text)
    }

    @Test
    fun `backspace removes a blank line including whitespace to the right of the caret`() {
        for (line in listOf("|", "    |", "  |  ", "\t|")) {
            val state = field("foo {\n$line\nbar()")
            state.backspace()
            assertEquals("foo {\nbar()", state.text)
            assertEquals(5, state.caret)
        }
    }

    @Test
    fun `backspace keeps the first line and removes excess blank line indentation in steps`() {
        val first = field("    |")
        first.backspace()
        assertEquals("", first.text)
        val excess = field("foo {\n        |\nbar()")
        excess.backspace()
        assertEquals("foo {\n    \nbar()", excess.text)
        excess.backspace()
        assertEquals("foo {\nbar()", excess.text)
    }

    @Test
    fun `backspace joins at the normal visual indent with a configured tab width`() {
        for (prefix in listOf("  ", "\t")) {
            val state = field("foo {\n$prefix|bar()", size = 2)
            state.backspace()
            assertEquals("foo {bar()", state.text)
        }
    }

    @Test
    fun `backspace recognizes the ordinary continuation indent before a chain link`() {
        for (link in listOf(".gap()", "?.gap()")) {
            val state = field("foo {\n    receiver\n        |$link")
            state.backspace()
            assertEquals("foo {\n    receiver$link", state.text)
        }
        val state = field("receiver\n            |.gap()")
        state.backspace()
        assertEquals("receiver\n        .gap()", state.text)
    }

    @Test
    fun `backspace keeps ordinary text and literal indentation behavior`() {
        val literal = field("val raw = \"\"\"\n    |literal\n\"\"\"")
        literal.backspace()
        assertEquals("val raw = \"\"\"\nliteral\n\"\"\"", literal.text)
        val state = field("foo {\n    bar|()")
        state.backspace()
        assertEquals("foo {\n    ba()", state.text)
    }

    @Test
    fun `dot indents a continuation and undo restores the original caret`() {
        val state = field("Modifier\n|")
        assertTrue(state.typeCharacter('.'))
        assertEquals("Modifier\n    .", state.text)
        assertEquals(state.text.length, state.caret)
        assertTrue(state.undo())
        assertEquals("Modifier\n", state.text)
        assertEquals(state.text.length, state.caret)
    }

    @Test
    fun `continuations align with the first chain link without accumulating indentation`() {
        for (previous in listOf("    .gap(16.px)", "    ?.gap(16.px)")) {
            val state = field("Modifier\n$previous\n|")
            state.typeCharacter('.')
            assertEquals("Modifier\n$previous\n    .", state.text)
        }
        val state = field("fun run() {\n    Modifier\n    |gap()")
        state.typeCharacter('.')
        assertEquals("fun run() {\n    Modifier\n        .gap()", state.text)
    }

    @Test
    fun `safe calls and existing manual indentation are preserved`() {
        val safe = field("receiver\n?|")
        safe.typeCharacter('.')
        assertEquals("receiver\n    ?.", safe.text)
        val manual = field("receiver\n        |")
        manual.typeCharacter('.')
        assertEquals("receiver\n        .", manual.text)
    }

    @Test
    fun `continuation indentation respects configured width and does not depend on automatic pairs`() {
        val state = field("receiver\n|", size = 2, autoPairs = false)
        state.typeCharacter('.')
        assertEquals("receiver\n  .", state.text)
    }

    @Test
    fun `dots inside literals comments and ordinary expressions do not trigger indentation`() {
        for (source in listOf("// comment\n|", "receiver\n\n|", "foo {\n|", "receiver|", "val raw = \"\"\"\n|\n\"\"\"", "receiver\n// |")) {
            val state = field(source)
            val expected = source.replace("|", ".")
            state.typeCharacter('.')
            assertEquals(expected, state.text)
        }
    }

    @Test
    fun `continuation works for literal receivers and expressions with trailing comments`() {
        for (receiver in listOf("\"value\"", "\"value\" // comment", "'x' /* comment */", "\"\"\"value\"\"\" // comment",
            "receiver // comment", "receiver /* comment */")) {
            val state = field("$receiver\n|")
            state.typeCharacter('.')
            assertEquals("$receiver\n    .", state.text)
        }
    }

    @Test
    fun `multiple carets independently join lines and indent continuations`() {
        val source = "foo {\n    bar()\n}\nother {\n    baz()"
        val state = TextFieldState(source, multiline = true, syntax = KotlinCodeSyntax)
        state.setCarets(listOf(UiTextCaret(source.indexOf("bar()")), UiTextCaret(source.indexOf("baz()"))))
        state.backspace()
        assertEquals("foo {bar()\n}\nother {baz()", state.text)

        state.setText("one\n\ntwo\n")
        state.setCarets(listOf(UiTextCaret(4), UiTextCaret(state.text.length)))
        state.typeCharacter('.')
        assertEquals("one\n    .\ntwo\n    .", state.text)
        assertEquals(listOf(9, state.text.length), state.caretRanges.map { it.position })
    }
}
