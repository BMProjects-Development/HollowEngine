package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCaret
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextPasteTransformer
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinLexerMode
import ru.hollowhorizon.hollowengine.common.scripting.ide.kotlinLexerModeAt

internal object KotlinStringPasteTransformer : UiTextPasteTransformer {
    override fun transform(document: String, caret: UiTextCaret, pastedText: String): String {
        return when (document.kotlinStringContextAt(caret.position)) {
            KotlinStringContext.REGULAR -> pastedText.escapeForRegularKotlinString()
            KotlinStringContext.RAW -> pastedText.escapeForRawKotlinString()
            null -> pastedText
        }
    }
}

internal fun String.escapeForRegularKotlinString(): String = buildString(length) {
    for (char in this@escapeForRegularKotlinString) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> {
                append('\\')
                append('$')
            }

            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (char.isUnsafeSourceControl()) appendUnicodeEscape(char) else append(char)
        }
    }
}

private fun String.escapeForRawKotlinString(): String = buildString(length) {
    var index = 0
    while (index < this@escapeForRawKotlinString.length) {
        val char = this@escapeForRawKotlinString[index]
        when {
            char == '$' -> appendRawCharacterExpression('$')
            char == '"' -> {
                var quoteEnd = index + 1
                while (this@escapeForRawKotlinString.getOrNull(quoteEnd) == '"') quoteEnd++
                var remaining = quoteEnd - index
                while (remaining >= 3) {
                    appendRawCharacterExpression('"')
                    remaining--
                }
                repeat(remaining) { append('"') }
                index = quoteEnd - 1
            }

            char.isUnsafeSourceControl() && char != '\n' && char != '\t' -> appendRawUnicodeExpression(char)
            else -> append(char)
        }
        index++
    }
}

private fun Char.isUnsafeSourceControl(): Boolean = code < 0x20 || code == 0x7F || this == '\u2028' || this == '\u2029'

private fun StringBuilder.appendUnicodeEscape(char: Char) {
    append("\\u")
    append(char.code.toString(16).padStart(4, '0'))
}

private fun StringBuilder.appendRawCharacterExpression(char: Char) {
    append('$')
    append('{')
    append('\'')
    append(char)
    append('\'')
    append('}')
}

private fun StringBuilder.appendRawUnicodeExpression(char: Char) {
    append('$')
    append("{'\\u")
    append(char.code.toString(16).padStart(4, '0'))
    append("'}")
}

private fun String.kotlinStringContextAt(offset: Int): KotlinStringContext? = when (kotlinLexerModeAt(offset)) {
    KotlinLexerMode.REGULAR_STRING -> KotlinStringContext.REGULAR
    KotlinLexerMode.RAW_STRING -> KotlinStringContext.RAW
    else -> null
}

internal fun String.isKotlinSource(): Boolean =
    endsWith(".kt", ignoreCase = true) || endsWith(".kts", ignoreCase = true)

private enum class KotlinStringContext { REGULAR, RAW }
