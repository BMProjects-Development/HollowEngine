package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.common.utils.expressions.*

/**
 * What a text field needs to edit an expression of one dialect: colors for its tokens, the names it
 * can complete, and the dialect's own diagnostics. Every editor that takes an expression (the
 * animator, the effect editor) builds one from its [Expression] and hands the three to its field.
 */
class ExpressionEditing<C>(private val language: Expression<C>) {
    val completions: UiCompletionContributor = UiCompletionContributor { context ->
        val before = context.text.take(context.caret.coerceIn(0, context.text.length))
        val member = MemberAccess.find(before)
        val prefix = member?.groupValues?.get(2) ?: before.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val candidates = member?.let { membersOf(it.groupValues[1]) } ?: topLevel()

        candidates
            .filter { prefix.isEmpty() || it.name.startsWith(prefix, ignoreCase = true) }
            .map { UiTextCompletion(label = it.name, detail = it.parameters, tail = it.type) }
    }

    val highlighter: UiSyntaxHighlighter = UiSyntaxHighlighter { text ->
        if (text.isBlank()) return@UiSyntaxHighlighter emptyList()

        Lexer(text, Diagnostics()).tokenize().mapNotNull { token ->
            val color = when (token.type) {
                TokenType.NUMBER -> ExpressionTokenColors.Number
                TokenType.STRING -> ExpressionTokenColors.String
                TokenType.BOOLEAN -> ExpressionTokenColors.Keyword
                TokenType.IDENTIFIER -> ExpressionTokenColors.Name
                TokenType.EOF -> return@mapNotNull null
                else -> ExpressionTokenColors.Operator
            }
            UiTextHighlight(token.span.start, token.span.end, UiInlineStyle().withColor(color))
        }
    }

    fun diagnostics(source: String): List<UiTextDiagnostic> {
        if (source.isBlank()) return emptyList()

        return runCatching { language.bake(source).diagnostics }
            .getOrDefault(emptyList())
            .map { diagnostic ->
                UiTextDiagnostic(
                    start = diagnostic.span.start.coerceIn(0, source.length),
                    end = diagnostic.span.end.coerceIn(0, source.length),
                    message = diagnostic.message,
                    severity = when (diagnostic.severity) {
                        Severity.ERROR -> UiTextDiagnosticSeverity.ERROR
                        Severity.WARNING -> UiTextDiagnosticSeverity.WARNING
                    },
                )
            }
    }

    /** What `root.` offers: the members of the root, or of the receiver of that name. */
    private fun membersOf(root: String): List<Candidate> {
        val declarations = language.declarations
        val type = declarations.root(root)?.type
            ?: declarations.receivers.firstOrNull { it.name == root }?.type
            ?: return emptyList()
        return type.described()
    }

    /** The roots, and the members of the receivers, which are readable without a root. */
    private fun topLevel(): List<Candidate> {
        val declarations = language.declarations
        val roots = declarations.roots.map { (name, field) -> Candidate(name, "", field.type.name) }
        val members = declarations.receivers.flatMap { it.type.described() }
        return (roots + members).distinctBy { it.name }.sortedBy { it.name }
    }

    private fun ExprType.described(): List<Candidate> {
        val fields = members.allFields.flatMap { field -> field.names.map { Candidate(it, "", field.type.name) } }
        val methods = members.allMethods.flatMap { method ->
            val parameters = method.parameters.joinToString(prefix = "(", postfix = ")") { it.name }
            method.names.map { Candidate(it, parameters, method.type.name) }
        }
        return (fields + methods).distinctBy { it.name }.sortedBy { it.name }
    }

    /** One name to offer: the parameters of a function right after it, and its type at the right edge. */
    private class Candidate(val name: String, val parameters: String, val type: String)

    private companion object {
        val MemberAccess = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([A-Za-z0-9_]*)$""")
    }
}

/** The colors of expression tokens, the same in every editor that shows an expression. */
object ExpressionTokenColors {
    val Number = UiColor(0.72f, 0.62f, 0.92f)
    val String = UiColor(0.62f, 0.82f, 0.55f)
    val Keyword = UiColor(0.85f, 0.55f, 0.42f)
    val Name = UiColor(0.42f, 0.72f, 0.92f)
    val Operator = UiColor(0.62f, 0.66f, 0.72f)
}
