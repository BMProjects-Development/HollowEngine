package ru.hollowhorizon.hollowengine.common.scripting.ide

internal fun String.isKotlinCodeAt(offset: Int): Boolean = kotlinLexerModeAt(offset) == KotlinLexerMode.CODE

internal fun String.kotlinLexerModeAt(offset: Int): KotlinLexerMode {
    val limit = offset.coerceIn(0, length)
    var index = 0
    var mode = KotlinLexerMode.CODE
    var blockCommentDepth = 0
    var simpleTemplateParent: KotlinLexerMode? = null
    val templates = ArrayDeque<KotlinTemplateContext>()

    while (index < limit) {
        when (mode) {
            KotlinLexerMode.CODE -> when {
                startsWith("//", index) -> {
                    mode = KotlinLexerMode.LINE_COMMENT
                    index += 2
                }

                startsWith("/*", index) -> {
                    mode = KotlinLexerMode.BLOCK_COMMENT
                    blockCommentDepth = 1
                    index += 2
                }

                startsWith("\"\"\"", index) -> {
                    mode = KotlinLexerMode.RAW_STRING
                    index += 3
                }

                this[index] == '"' -> {
                    mode = KotlinLexerMode.REGULAR_STRING
                    index++
                }

                this[index] == '\'' -> {
                    mode = KotlinLexerMode.CHARACTER
                    index++
                }

                this[index] == '{' && templates.isNotEmpty() -> {
                    templates.last().braceDepth++
                    index++
                }

                this[index] == '}' && templates.isNotEmpty() -> {
                    val template = templates.last()
                    template.braceDepth--
                    index++
                    if (template.braceDepth == 0) {
                        mode = template.parent
                        templates.removeLast()
                    }
                }

                else -> index++
            }

            KotlinLexerMode.REGULAR_STRING -> when {
                this[index] == '\\' -> index = (index + 2).coerceAtMost(limit)
                this[index] == '"' -> {
                    mode = KotlinLexerMode.CODE
                    index++
                }

                this[index] == '\n' || this[index] == '\r' -> {
                    mode = KotlinLexerMode.CODE
                    index++
                }

                this[index] == '$' && getOrNull(index + 1) == '{' -> {
                    templates.addLast(KotlinTemplateContext(mode))
                    mode = KotlinLexerMode.CODE
                    index += 2
                }

                this[index] == '$' && getOrNull(index + 1)?.isKotlinIdentifierStart() == true -> {
                    simpleTemplateParent = mode
                    mode = KotlinLexerMode.SIMPLE_TEMPLATE
                    index++
                }

                else -> index++
            }

            KotlinLexerMode.RAW_STRING -> when {
                startsWith("\"\"\"", index) -> {
                    mode = KotlinLexerMode.CODE
                    index += 3
                }

                this[index] == '$' && getOrNull(index + 1) == '{' -> {
                    templates.addLast(KotlinTemplateContext(mode))
                    mode = KotlinLexerMode.CODE
                    index += 2
                }

                this[index] == '$' && getOrNull(index + 1)?.isKotlinIdentifierStart() == true -> {
                    simpleTemplateParent = mode
                    mode = KotlinLexerMode.SIMPLE_TEMPLATE
                    index++
                }

                else -> index++
            }

            KotlinLexerMode.CHARACTER -> when {
                this[index] == '\\' -> index = (index + 2).coerceAtMost(limit)
                this[index] == '\'' || this[index] == '\n' || this[index] == '\r' -> {
                    mode = KotlinLexerMode.CODE
                    index++
                }

                else -> index++
            }

            KotlinLexerMode.LINE_COMMENT -> {
                if (this[index] == '\n' || this[index] == '\r') mode = KotlinLexerMode.CODE
                index++
            }

            KotlinLexerMode.BLOCK_COMMENT -> when {
                startsWith("/*", index) -> {
                    blockCommentDepth++
                    index += 2
                }

                startsWith("*/", index) -> {
                    blockCommentDepth--
                    index += 2
                    if (blockCommentDepth == 0) mode = KotlinLexerMode.CODE
                }

                else -> index++
            }

            KotlinLexerMode.SIMPLE_TEMPLATE -> {
                if (this[index].isKotlinIdentifierPart()) {
                    index++
                } else {
                    mode = requireNotNull(simpleTemplateParent)
                    simpleTemplateParent = null
                }
            }
        }
    }

    if (mode == KotlinLexerMode.SIMPLE_TEMPLATE && getOrNull(limit)?.isKotlinIdentifierPart() != true) {
        mode = requireNotNull(simpleTemplateParent)
    }
    return mode
}

private fun Char.isKotlinIdentifierStart(): Boolean = Character.isJavaIdentifierStart(this)

private fun Char.isKotlinIdentifierPart(): Boolean = Character.isJavaIdentifierPart(this)

internal enum class KotlinLexerMode {
    CODE,
    REGULAR_STRING,
    RAW_STRING,
    CHARACTER,
    LINE_COMMENT,
    BLOCK_COMMENT,
    SIMPLE_TEMPLATE,
}

private data class KotlinTemplateContext(
    val parent: KotlinLexerMode,
    var braceDepth: Int = 1,
)

