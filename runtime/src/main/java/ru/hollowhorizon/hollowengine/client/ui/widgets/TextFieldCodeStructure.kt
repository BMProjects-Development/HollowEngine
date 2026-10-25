package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinLexerMode
import ru.hollowhorizon.hollowengine.common.scripting.ide.kotlinLexerModeAt

/** Shares the language's lexical rules, so delimiters inside literals and comments never affect edits. */
internal class TextFieldCodeStructure(private val text: String, limit: Int) {
    private val code = BooleanArray(limit)
    private val lineModes = HashMap<Int, KotlinLexerMode>()

    init {
        text.kotlinLexerModeAt(limit, { code[it] = true }, { offset, mode -> lineModes[offset] = mode })
    }

    fun openingsBefore(position: Int): List<Int> {
        val stack = ArrayList<Int>()
        for (index in 0 until position) {
            if (!code[index]) continue
            val char = text[index]
            when {
                char in "{([" -> stack += index
                stack.isNotEmpty() && EditorPairClosings[text[stack.last()]] == char -> stack.removeLast()
            }
        }
        return stack
    }

    fun blockIndent(position: Int, size: Int): String? = openingsBefore(position).lastOrNull()?.let {
        text.indentOfLine(it) + " ".repeat(size.coerceAtLeast(0))
    }

    fun preservesLineIndent(position: Int): Boolean = lineModes[position].let {
        it == KotlinLexerMode.RAW_STRING || it == KotlinLexerMode.REGULAR_STRING
    }

    /** Only edits line prefixes, allowing all other carets and selections to be mapped exactly. */
    fun closingIndents(position: Int, char: Char, size: Int): List<TextFieldIndentEdit> {
        if (char !in "})]") return emptyList()
        val start = lineStart(text, position)
        if (text.substring(start, position).any { it != ' ' && it != '\t' }) return emptyList()
        val opening = openingsBefore(position).lastOrNull() ?: return emptyList()
        if (EditorPairClosings[text[opening]] != char) return emptyList()
        val base = text.indentOfLine(opening)
        var offset = text.indexOf('\n', opening).takeIf { it in (opening + 1) until position }?.plus(1) ?: start
        var depth = 1
        val edits = ArrayList<TextFieldIndentEdit>()
        while (offset <= start) {
            val end = lineEnd(text, offset)
            val first = (offset until end).firstOrNull { text[it] != ' ' && text[it] != '\t' }
            if (first != null && !preservesLineIndent(offset)) {
                val closes = code[first] && text[first] in "})]"
                val indent = if (offset == start) base
                else base + " ".repeat((depth - if (closes) 1 else 0).coerceAtLeast(0) * size.coerceAtLeast(0))
                edits += TextFieldIndentEdit(offset, first, indent)
            }
            for (index in offset until end) {
                if (!code[index]) continue
                if (text[index] in "{([") depth++
                else if (text[index] in "})]") depth--
            }
            offset = end + 1
        }
        return edits
    }
}

internal data class TextFieldIndentEdit(val start: Int, val end: Int, val replacement: String)

internal fun String.indentOfLine(position: Int): String = substring(lineStart(this, position), lineEnd(this, position))
    .takeWhile { it == ' ' || it == '\t' }

internal val EditorPairClosings = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
