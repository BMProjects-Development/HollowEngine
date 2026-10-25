package ru.hollowhorizon.hollowengine.client.ui.widgets

internal data class NewlineEdit(val start: Int, val end: Int, val replacement: String, val caretOffset: Int)

/**
 * Breaks the line at [caret], one step deeper after an opening delimiter. With a [syntax], a blank line and a call
 * chain take the indent of the block they are in, and literals and comments keep the indent of their line.
 */
internal fun newlineEdit(text: String, caret: Int, indentSize: Int, syntax: UiCodeSyntax?): NewlineEdit {
    val position = caret.coerceIn(0, text.length)
    val lineStart = if (position == 0) 0 else text.lastIndexOf('\n', position - 1) + 1
    val lineEnd = text.indexOf('\n', position).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    if (syntax != null && !syntax.isCodeAt(text, position)) {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return NewlineEdit(position, position, "\n$indent", 1 + indent.length)
    }
    val before = text.substring(lineStart, position).trimEnd(' ', '\t')
    val after = text.substring(position, lineEnd).trimStart(' ', '\t')
    val structuralIndent = syntax?.let { TextFieldCodeStructure(text, position, it).blockIndent(position, indentSize) }
    val currentIndent = line.takeWhile { it == ' ' || it == '\t' }
    val opening = before.lastOrNull()
    val opens = opening?.let { it in "{([" } == true
    val baseIndent = when {
        line.isBlank() && syntax != null -> structuralIndent.orEmpty()
        line.isBlank() -> text.substring(0, lineStart).lineSequence().lastOrNull { it.isNotBlank() }
            ?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
        syntax != null && !opens && before.trimStart().startsWith('.') -> structuralIndent.orEmpty()
        else -> currentIndent
    }
    val closing = after.firstOrNull()
    val closes = closing?.let { it in "})]" } == true
    val pair = when (opening) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> null }
    val splitPair = opens && closes && pair == closing
    val indent = if (opens) baseIndent + " ".repeat(indentSize.coerceAtLeast(0)) else baseIndent
    // Whitespace around block delimiters belongs to the old inline layout.
    val start = if (opens && before.isNotEmpty()) lineStart + before.length else position
    val end = if (opens || closes) lineEnd - after.length else position
    val replacement = "\n$indent" + if (splitPair) "\n$baseIndent" else ""
    return NewlineEdit(start, end, replacement, 1 + indent.length)
}
