package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.common.scripting.ide.isKotlinCodeAt
import ru.hollowhorizon.hollowengine.common.scripting.ide.kotlinLexerModeAt

internal fun indentationBackspaceEdit(text: String, position: Int, size: Int?): TextFieldCodeEdit {
    val start = lineStart(text, position)
    val prefix = text.substring(start, position)
    if (size == null || size <= 0 || prefix.any { it != ' ' && it != '\t' }) {
        return TextFieldCodeEdit(position - 1, position, "")
    }
    if (start > 0 && text.isKotlinCodeAt(position)) {
        val structure = TextFieldCodeStructure(text, position)
        val line = text.substring(start, lineEnd(text, position)).trimStart(' ', '\t')
        val chainIndent = if (line.startsWith('.') || line.startsWith("?.")) continuationIndent(text, start, size) else null
        val previousIndent = text.substring(0, start).lineSequence().lastOrNull { it.isNotBlank() }
            ?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
        val normalIndent = chainIndent ?: structure.blockIndent(position, size) ?: previousIndent
        if (prefix.indentColumns(size) <= normalIndent.indentColumns(size)) {
            val end = lineEnd(text, position)
            val blank = text.substring(position, end).all { it == ' ' || it == '\t' }
            return TextFieldCodeEdit(start - 1, if (blank) end else position, "")
        }
    }
    return TextFieldCodeEdit(indentationBackspaceStart(text, position, size), position, "")
}

/** Deletes only the preceding visual indentation step when the indent exceeds its normal level. */
private fun indentationBackspaceStart(text: String, position: Int, size: Int): Int {
    val start = lineStart(text, position)
    val prefix = text.substring(start, position)
    if (prefix.isEmpty()) return position - 1
    var column = 0
    val columns = prefix.map { char -> column.also { column += if (char == '\t') size - column % size else 1 } }
    val target = (column - 1) / size * size
    return start + columns.indexOfLast { it <= target }.coerceAtLeast(0)
}

/** Adds a continuation level only when the dot actually starts a new call-chain link. */
internal fun continuationDotEdit(text: String, position: Int, size: Int): TextFieldCodeEdit? {
    val start = lineStart(text, position)
    if (start == 0) return null
    val prefix = text.substring(start, position)
    val safeCall = prefix.trimStart(' ', '\t') == "?"
    val indent = if (safeCall) prefix.dropLast(1) else prefix
    if (indent.any { it != ' ' && it != '\t' } || !text.isKotlinCodeAt(position)) return null
    val desired = continuationIndent(text, start, size) ?: return null
    if (indent.indentColumns(size) >= desired.indentColumns(size)) return null
    return TextFieldCodeEdit(start, position, desired + if (safeCall) "?." else ".")
}

private fun continuationIndent(text: String, start: Int, size: Int): String? {
    val previousStart = lineStart(text, start - 1)
    val previous = text.substring(previousStart, start - 1).trimEnd(' ', '\t')
    if (previous.isEmpty()) return null
    val previousEnd = previousStart + previous.length
    var lastTokenEnd = -1
    fun recordTokenEnd(index: Int) {
        if (index in previousStart until previousEnd && !text[index].isWhitespace()) lastTokenEnd = index
    }
    text.kotlinLexerModeAt(previousEnd, onCodeCharacter = ::recordTokenEnd, onLiteralEnd = ::recordTokenEnd)
    val ending = text.getOrNull(lastTokenEnd)
    if (ending == null || !(ending.isLetterOrDigit() || ending in "_)}]\"'`")) return null

    val previousIndent = previous.takeWhile { it == ' ' || it == '\t' }
    val continued = previous.trimStart(' ', '\t').let { it.startsWith('.') || it.startsWith("?.") }
    return previousIndent + if (continued) "" else " ".repeat(size)
}

private fun String.indentColumns(size: Int): Int = fold(0) { column, char ->
    column + if (char == '\t') size - column % size else 1
}
