package ru.hollowhorizon.hollowengine.client.ui.widgets

/** Reads [text] by the language's [syntax], so delimiters inside literals and comments never affect edits. */
internal class TextFieldCodeStructure(private val text: String, limit: Int, syntax: UiCodeSyntax) {
    private val code = BooleanArray(limit)
    private val stringLines = HashSet<Int>()

    init {
        syntax.scan(text, limit, { code[it] = true }, { offset, inString -> if (inString) stringLines += offset })
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

    fun preservesLineIndent(position: Int): Boolean = position in stringLines

    /** Indents the block that the [char] at [position] closes. */
    fun closingIndents(position: Int, char: Char, size: Int): List<TextFieldIndentEdit> {
        if (char !in "})]") return emptyList()
        val start = lineStart(text, position)
        if (text.substring(start, position).any { it != ' ' && it != '\t' }) return emptyList()
        val opening = openingsBefore(position).lastOrNull() ?: return emptyList()
        if (EditorPairClosings[text[opening]] != char) return emptyList()
        val step = " ".repeat(size.coerceAtLeast(0))
        val base = text.indentOfLine(opening)
        val open = arrayListOf(base)
        var offset = text.indexOf('\n', opening).takeIf { it in (opening + 1) until position }?.plus(1) ?: start
        var previousEnd: Int? = null
        val edits = ArrayList<TextFieldIndentEdit>()
        while (offset <= start) {
            val end = lineEnd(text, offset)
            val first = (offset until end).firstOrNull { text[it] != ' ' && text[it] != '\t' }
            var indent = first?.let { text.substring(offset, it) }
            if (first != null && !preservesLineIndent(offset)) {
                val closes = code[first] && text[first] in "})]"
                indent = when {
                    offset == start -> base
                    closes -> open.last()
                    continues(first, previousEnd) -> open.last() + step + step
                    else -> open.last() + step
                }
                edits += TextFieldIndentEdit(offset, first, indent)
            }
            val depth = open.size
            for (index in offset until end) {
                if (!code[index]) continue
                if (text[index] in "{([") open += indent.orEmpty()
                else if (text[index] in "})]" && open.size > 1) open.removeAt(open.lastIndex)
            }
            if (first != null) previousEnd = lastCodeIndex(offset, end).takeIf { open.size <= depth }
            offset = end + 1
        }
        return edits
    }

    /** Whether the line starting with code at [first] goes on with the expression that ended at [previousEnd]. */
    private fun continues(first: Int, previousEnd: Int?): Boolean {
        if (code[first] && ContinuationStarts.any { text.startsWith(it, first) }) return true
        val end = previousEnd ?: return false
        return ContinuationEnds.any { end - it.length + 1 >= 0 && text.startsWith(it, end - it.length + 1) }
    }

    private fun lastCodeIndex(from: Int, to: Int): Int? =
        (to - 1 downTo from).firstOrNull { code[it] && !text[it].isWhitespace() }

    private companion object {
        val ContinuationStarts = listOf(".", "?.", "?:", "&&", "||")
        val ContinuationEnds = listOf("=", "+", "-", "*", "/", "%", "&&", "||", "?:", "->")
    }
}

internal data class TextFieldIndentEdit(val start: Int, val end: Int, val replacement: String)

internal fun String.indentOfLine(position: Int): String =
    substring(lineStart(this, position), lineEnd(this, position)).takeWhile { it == ' ' || it == '\t' }

internal val EditorPairClosings = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
