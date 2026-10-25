package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeSyntax
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinLexerMode
import ru.hollowhorizon.hollowengine.common.scripting.ide.kotlinLexerModeAt

/** Kotlin for code editing, read by the lexer the IDE tells strings and comments apart with. */
object KotlinCodeSyntax : UiCodeSyntax {
    override fun scan(
        text: String,
        limit: Int,
        onCode: ((Int) -> Unit)?,
        onLineStart: ((offset: Int, inString: Boolean) -> Unit)?,
        onLiteralEnd: ((Int) -> Unit)?,
    ): Boolean {
        val lines = onLineStart?.let { report ->
            { offset: Int, mode: KotlinLexerMode ->
                report(offset, mode == KotlinLexerMode.RAW_STRING || mode == KotlinLexerMode.REGULAR_STRING)
            }
        }
        return text.kotlinLexerModeAt(limit, onCode, lines, onLiteralEnd) == KotlinLexerMode.CODE
    }
}
