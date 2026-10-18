package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.common.scripting.ide.isKotlinCodeAt

internal data class NewlineEdit(val start: Int, val end: Int, val replacement: String, val caretOffset: Int)

internal fun newlineEdit(text: String, caret: Int, indentSize: Int): NewlineEdit {
    val position = caret.coerceIn(0, text.length)
    val lineStart = if (position == 0) 0 else text.lastIndexOf('\n', position - 1) + 1
    val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    if (!text.isKotlinCodeAt(position)) {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return NewlineEdit(position, position, "\n$indent", 1 + indent.length)
    }
    val before = text.substring(lineStart, position).trimEnd(' ', '\t')
    val after = text.substring(position, lineEnd).trimStart(' ', '\t')
    var baseIndent = line.takeWhile { it == ' ' || it == '\t' }
    var previousOpens = false
    if (line.isBlank()) {
        val previous = text.substring(0, lineStart).lineSequence().lastOrNull { it.isNotBlank() }
        if (previous != null) {
            baseIndent = previous.takeWhile { it == ' ' || it == '\t' }
            previousOpens = previous.trimEnd().lastOrNull()?.let { it in "{([" } == true
        } else if (indentSize > 0) {
            baseIndent = " ".repeat((position - lineStart) / indentSize * indentSize)
        }
    }
    val opening = before.lastOrNull()
    val closing = after.firstOrNull()
    val opens = opening?.let { it in "{([" } == true || line.isBlank() && previousOpens
    val closes = closing?.let { it in "})]" } == true
    val pair = when (opening) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> null }
    val splitPair = opens && closes && (pair == closing || line.isBlank())
    val indent = if (opens) baseIndent + " ".repeat(indentSize.coerceAtLeast(0)) else baseIndent
    // Whitespace around block delimiters belongs to the old inline layout.
    val start = if (opens && before.isNotEmpty()) lineStart + before.length else position
    val end = if (opens || closes) lineEnd - after.length else position
    val replacement = "\n$indent" + if (splitPair) "\n$baseIndent" else ""
    return NewlineEdit(start, end, replacement, 1 + indent.length)
}
