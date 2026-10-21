package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

/**
 * What the caret is sitting on, worked out by scanning the line-up to it.
 */
sealed interface StoryCompletionContext {
    /** The part of the word already typed, which the offered items must start with. */
    val typed: String

    /** Plain dialogue text, or the start of a line: nothing to offer. */
    data object None : StoryCompletionContext {
        override val typed: String get() = ""
    }

    /** The command name itself. */
    data class Command(override val typed: String) : StoryCompletionContext

    /** Label of this file. */
    data class Label(override val typed: String) : StoryCompletionContext

    /**
     * An argument of [command]. [parameter] is set once `name=` has been typed; until then the word
     * could still become either a parameter name or the value at [positional], and the caller decides
     * what is worth offering.
     */
    data class Argument(
        val command: String,
        val parameter: String?,
        val positional: Int,
        override val typed: String,
    ) : StoryCompletionContext

    /**
     * Inside an interpolation or an expression, where variables live. [member] is set when the caret
     * follows a dot, so what belongs there is a member of a value rather than a variable name.
     */
    data class Expression(override val typed: String, val member: Boolean = false) : StoryCompletionContext
}

/**
 * Reads [line] up to [caret] and reports what belongs there.
 */
fun storyCompletionContext(line: String, caret: Int): StoryCompletionContext {
    val end = caret.coerceIn(0, line.length)
    var i = 0

    while (i < end && (line[i] == ' ' || line[i] == '\t')) i++
    if (i >= end || line[i] != '@') {
        return if (hasOpenBrace(line, i, end)) expressionContext(line, end)
        else StoryCompletionContext.None
    }

    val nameStart = ++i
    while (i < end && StoryParser.isNameChar(line[i])) i++
    if (i >= end) return StoryCompletionContext.Command(line.substring(nameStart, end))
    val command = line.substring(nameStart, i)

    var parameter: String? = null
    var positional = 0
    var tokenStart = -1
    var typedFrom = i

    while (i < end) {
        when {
            line[i] == ' ' || line[i] == '\t' -> {
                if (tokenStart >= 0) {
                    if (parameter == null) positional++
                    parameter = null
                    tokenStart = -1
                }
                i++
                typedFrom = i
            }

            line[i] == '"' -> {
                val closing = skipQuoted(line, i, end) ?: return StoryCompletionContext.None
                i = closing
                tokenStart = 0
            }

            line[i] == '{' -> {
                val closing = skipBracketed(line, i, end, '{', '}')
                    ?: return expressionContext(line, end)
                i = closing
                tokenStart = 0
            }

            line[i] == '[' -> {
                val closing = skipBracketed(line, i, end, '[', ']')
                    ?: return StoryCompletionContext.Argument(
                        command,
                        parameter,
                        positional,
                        line.substring(i + 1, end).substringAfterLast(',').trim(),
                    )
                i = closing
                tokenStart = 0
            }

            line[i] == '=' && tokenStart >= 0 && parameter == null -> {
                parameter = line.substring(typedFrom, i)
                i++
                typedFrom = i
                tokenStart = -1
            }

            else -> {
                if (tokenStart < 0) tokenStart = i
                i++
            }
        }
    }

    val typed = line.substring(typedFrom.coerceAtMost(end), end)
    if (command in LABEL_COMMANDS && (typed.startsWith('#') || parameter == null && positional == 0)) {
        return StoryCompletionContext.Label(typed.removePrefix("#"))
    }
    if (command in CONDITION_COMMANDS || parameter == "if") return expressionContext(line, end)
    if (command == "set" && line.indexOf('=').let { it in 0 until end }) return expressionContext(line, end)
    return StoryCompletionContext.Argument(command, parameter, positional, typed)
}

private val LABEL_COMMANDS = setOf("jump", "call")
private val CONDITION_COMMANDS = setOf("if", "else-if", "while")

/** True when a `{` before [end] has not been closed, so the caret sits in an expression. */
private fun hasOpenBrace(line: String, from: Int, end: Int): Boolean {
    var depth = 0
    var i = from
    while (i < end) {
        when (line[i]) {
            '\\' -> i++
            '{' -> depth++
            '}' -> if (depth > 0) depth--
        }
        i++
    }
    return depth > 0
}

/** Position just past the closing quote, or null when the string is still open. */
private fun skipQuoted(line: String, opening: Int, end: Int): Int? {
    var i = opening + 1
    while (i < end) {
        when (line[i]) {
            '\\' -> i++
            '"' -> return i + 1
        }
        i++
    }
    return null
}

private fun skipBracketed(line: String, opening: Int, end: Int, open: Char, close: Char): Int? {
    var depth = 0
    var i = opening
    while (i < end) {
        when (line[i]) {
            '\\' -> i++
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return null
}

/** The identifier before [end], and whether it follows a dot, which decides what is typed. */
private fun expressionContext(line: String, end: Int): StoryCompletionContext.Expression {
    var start = end
    while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
    return StoryCompletionContext.Expression(
        typed = line.substring(start, end),
        member = start > 0 && line[start - 1] == '.',
    )
}
