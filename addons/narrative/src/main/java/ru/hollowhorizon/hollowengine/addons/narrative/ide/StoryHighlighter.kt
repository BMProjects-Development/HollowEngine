package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryBool
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryNumber
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryString
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryValue
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.scripting.ide.ui.TextSpan
import ru.hollowhorizon.hollowengine.common.utils.expressions.Ast
import ru.hollowhorizon.hollowengine.common.utils.expressions.UnaryOp

/**
 * Coloring for `.story`. It reads the same tree the compiler does, so the editor can never disagree
 * with it about what a piece of text is: a speaker, a command, an argument name or an expression.
 */
internal object StoryHighlighter {
    /** Built-in commands, colored as keywords; anything else is a call into the function registry. */
    val BUILTIN_COMMANDS = listOf(
        "if", "else-if", "else", "while", "set",
        "jump", "call", "return",
        "choice", "async", "await", "cancel", "sync",
        "command",
    )

    fun spans(text: String, catalog: StoryFunctionCatalog = StoryFunctionCatalog.PERMISSIVE): List<TextSpan> {
        val parsed = StoryParser.parse(text)
        val spans = ArrayList<TextSpan>()
        parsed.cst.lines.forEach { line -> lineSpans(text, line, spans, catalog) }
        return spans.sortedBy { it.start }.dropOverlaps()
    }

    /**
     * How a call is drawn: built-in commands as keywords, registered functions as functions, and a
     * name nothing knows about in italics, it may still be registered on a controller, so it is not
     * an error, but the difference should be visible while writing.
     */
    private fun callSpan(start: Int, end: Int, name: String, catalog: StoryFunctionCatalog): TextSpan = when {
        name in BUILTIN_COMMANDS -> TextSpan(start, end, TokenType.KEYWORD)
        catalog.overloads(name) != null -> TextSpan(start, end, TokenType.FUNCTION)
        else -> TextSpan(start, end, TokenType.FUNCTION, italic = true)
    }

    private fun lineSpans(source: String, line: StoryLine, out: MutableList<TextSpan>, catalog: StoryFunctionCatalog) {
        line.commentStart?.let { start ->
            out += TextSpan(line.offset + start, line.offset + line.raw.length, TokenType.COMMENT, italic = true)
        }
        val contentStart = line.offset + line.indent.length
        val contentEnd = line.offset + (line.commentStart ?: line.raw.length)

        when (val kind = line.kind) {
            is StoryLineKind.Label -> {
                out += TextSpan(contentStart, kind.nameSpan.end, TokenType.TOP_LEVEL)
            }

            is StoryLineKind.Dialogue -> {
                val speakerExpr = kind.speakerExpr
                kind.speakerSpan?.let {
                    out += TextSpan(it.start, it.end, if (speakerExpr == null) TokenType.CLASS else TokenType.VARIABLE)
                }
                speakerExpr?.let { exprSpans(it, out) }
                templateSpans(source, kind.text, out, catalog)
            }

            is StoryLineKind.Command -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                templateSpans(source, kind.text, out, catalog, literalType = TokenType.STRING)
            }

            is StoryLineKind.If -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                exprSpans(kind.condition, out)
            }

            is StoryLineKind.ElseIf -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                exprSpans(kind.condition, out)
            }

            is StoryLineKind.While -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                exprSpans(kind.condition, out)
            }

            is StoryLineKind.Set -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                out += TextSpan(kind.variableSpan.start, kind.variableSpan.end, TokenType.VARIABLE)
                exprSpans(kind.value, out)
            }

            is StoryLineKind.Jump -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                out += TextSpan(kind.target.span.start, kind.target.span.end, TokenType.STRING)
            }

            is StoryLineKind.Call -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                out += TextSpan(kind.target.span.start, kind.target.span.end, TokenType.STRING)
            }

            is StoryLineKind.Choice -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                // The quotes belong to the string but sit outside the template's span.
                out += TextSpan(kind.text.span.start - 1, kind.text.span.start, TokenType.STRING)
                out += TextSpan(kind.text.span.end, kind.text.span.end + 1, TokenType.STRING)
                templateSpans(source, kind.text, out, catalog, literalType = TokenType.STRING)
                kind.args.forEach { argSpans(it, out) }
            }

            is StoryLineKind.FuncCall -> {
                out += callSpan(contentStart, kind.functionSpan.end, kind.function, catalog)
                kind.args.forEach { argSpans(it, out) }
            }

            is StoryLineKind.Async -> {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
                kind.inline?.let { call ->
                    out += callSpan(call.functionSpan.start, call.functionSpan.end, call.function, catalog)
                    call.args.forEach { argSpans(it, out) }
                }
            }

            is StoryLineKind.Await, is StoryLineKind.Cancel, is StoryLineKind.Sync,
            is StoryLineKind.Return, is StoryLineKind.Else,
                -> out += commandKeyword(line, contentStart, contentEnd, catalog)

            is StoryLineKind.Blank, is StoryLineKind.CommentOnly -> Unit

            // A line the parser could not read still shows its `@name`, so typing stays readable.
            is StoryLineKind.Broken -> if (contentEnd > contentStart && line.raw[line.indent.length] == '@') {
                out += commandKeyword(line, contentStart, contentEnd, catalog)
            }
        }
    }

    private fun commandKeyword(
        line: StoryLine,
        contentStart: Int,
        contentEnd: Int,
        catalog: StoryFunctionCatalog,
    ): TextSpan {
        var end = contentStart + 1 // past '@'
        val limit = contentEnd - line.offset
        var index = line.indent.length + 1
        while (index < limit && StoryParser.isNameChar(line.raw[index])) {
            index++
            end++
        }
        return callSpan(contentStart, end, line.raw.substring(line.indent.length + 1, index), catalog)
    }

    private fun templateSpans(
        source: String,
        template: TextTemplate,
        out: MutableList<TextSpan>,
        catalog: StoryFunctionCatalog,
        literalType: TokenType? = null,
    ) {
        for (part in template.parts) {
            when (part) {
                is TextPart.Literal -> literalSpans(source, part, out, literalType)
                is TextPart.WaitInput -> out += TextSpan(part.span.start, part.span.end, TokenType.KEYWORD)
                is TextPart.Interpolation -> {
                    out += TextSpan(part.span.start, part.span.end, TokenType.VARIABLE)
                    exprSpans(part.expr, out)
                }

                is TextPart.InlineCall -> {
                    out += callSpan(part.span.start, part.span.end, part.call.function, catalog)
                    part.call.args.forEach { argSpans(it, out) }
                }
            }
        }
    }

    private fun literalSpans(
        source: String,
        part: TextPart.Literal,
        out: MutableList<TextSpan>,
        literalType: TokenType?,
    ) {
        val raw = source.substring(part.span.start, part.span.end)
        var cursor = part.span.start
        FormattingTag.findAll(raw).forEach { match ->
            val start = part.span.start + match.range.first
            val end = part.span.start + match.range.last + 1
            if (literalType != null && cursor < start) out += TextSpan(cursor, start, literalType)
            out += TextSpan(start, end, TokenType.ANNOTATION)
            cursor = end
        }
        if (literalType != null && cursor < part.span.end) out += TextSpan(cursor, part.span.end, literalType)
    }

    private fun argSpans(arg: StoryArg, out: MutableList<TextSpan>) {
        arg.nameSpan?.let { out += TextSpan(it.start, it.end, TokenType.VALUE_ARGUMENT_NAME) }
        exprSpans(arg.expr, out)
    }

    private fun exprSpans(expr: StoryExpression, out: MutableList<TextSpan>) {
        expr.parts.forEach { exprSpans(it, out) }
        expr.ast?.let { astSpans(it, out) }
            ?: expr.constant?.let { out += TextSpan(expr.span.start, expr.span.end, it.tokenType()) }
    }

    /** Colors the tree the language itself parsed. */
    private fun astSpans(ast: Ast, out: MutableList<TextSpan>) {
        when (ast) {
            is Ast.StringLit -> out += TextSpan(ast.span.start, ast.span.end, TokenType.STRING)
            is Ast.NumberLit -> out += TextSpan(ast.span.start, ast.span.end, TokenType.NUMERIC_LITERAL)
            is Ast.BoolLit -> out += TextSpan(ast.span.start, ast.span.end, TokenType.KEYWORD)

            is Ast.Name -> out += TextSpan(ast.span.start, ast.span.end, TokenType.VARIABLE)

            is Ast.Unary -> {
                val operand = ast.operand
                if (ast.op == UnaryOp.NEGATE && operand is Ast.NumberLit) {
                    out += TextSpan(ast.span.start, ast.span.end, TokenType.NUMERIC_LITERAL)
                } else {
                    astSpans(operand, out)
                }
            }

            is Ast.Binary -> {
                astSpans(ast.left, out)
                astSpans(ast.right, out)
            }

            is Ast.ListLit -> ast.items.forEach { astSpans(it, out) }

            is Ast.Index -> {
                astSpans(ast.target, out)
                astSpans(ast.index, out)
            }

            is Ast.Access -> {
                astSpans(ast.target, out)
                out += TextSpan(ast.nameSpan.start, ast.nameSpan.end, TokenType.PROPERTY_IDENTIFIER)
            }

            is Ast.Call -> {
                ast.target?.let { astSpans(it, out) }
                ast.arguments.forEach { astSpans(it, out) }
            }

            is Ast.Conditional -> {
                astSpans(ast.condition, out)
                astSpans(ast.ifTrue, out)
                astSpans(ast.ifFalse, out)
            }

            is Ast.Assign -> {
                astSpans(ast.target, out)
                astSpans(ast.value, out)
            }

            is Ast.Sequence -> ast.statements.forEach { astSpans(it, out) }
        }
    }

    private fun StoryValue.tokenType(): TokenType = when (this) {
        is StoryString -> TokenType.STRING
        is StoryNumber -> TokenType.NUMERIC_LITERAL
        is StoryBool -> TokenType.KEYWORD
        else -> TokenType.DEFAULT
    }

    private fun List<TextSpan>.dropOverlaps(): List<TextSpan> {
        val result = ArrayList<TextSpan>(size)
        var cursor = 0
        for (span in this) {
            if (span.end <= span.start) continue
            val start = maxOf(span.start, cursor)
            if (start >= span.end) continue
            result += if (start == span.start) span else span.copy(start = start)
            cursor = span.end
        }
        return result
    }

    private val FormattingTag = Regex("<[^>\\r\\n]+>")
}
