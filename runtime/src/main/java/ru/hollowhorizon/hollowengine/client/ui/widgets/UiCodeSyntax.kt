package ru.hollowhorizon.hollowengine.client.ui.widgets

/**
 * The syntax code editing reads from a field's text: where code is, as opposed to literals and comments, and which
 * lines a string literal runs through, whose indent belongs to the string. A field without one edits plain text: it
 * keeps the indent of the line and pairs delimiters, but knows no blocks, call chains or literals.
 */
interface UiCodeSyntax {
    /**
     * Reads [text] up to [limit] and returns whether [limit] is in code. [onCode] gets each offset that is code,
     * [onLiteralEnd] the last offset of each literal, and [onLineStart] each line start with whether a string runs
     * through it.
     */
    fun scan(
        text: String,
        limit: Int,
        onCode: ((Int) -> Unit)? = null,
        onLineStart: ((offset: Int, inString: Boolean) -> Unit)? = null,
        onLiteralEnd: ((Int) -> Unit)? = null,
    ): Boolean
}

internal fun UiCodeSyntax.isCodeAt(text: String, offset: Int): Boolean = scan(text, offset)
