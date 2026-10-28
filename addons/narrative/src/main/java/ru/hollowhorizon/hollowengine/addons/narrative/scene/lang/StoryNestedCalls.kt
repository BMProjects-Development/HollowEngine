package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

import ru.hollowhorizon.hollowengine.common.utils.expressions.Ast
import ru.hollowhorizon.hollowengine.common.utils.expressions.Diagnostics
import ru.hollowhorizon.hollowengine.common.utils.expressions.Severity
import ru.hollowhorizon.hollowengine.common.utils.expressions.parseExpression

/**
 * A call written inside an expression, `npc([0, 64, 0], id="guard")`. The compiler runs it ahead of the statement
 * that reads it and puts its value into [variable], which the expression reads in its place.
 *
 * @param span where the call is written, its name and parentheses included.
 */
class StoryNestedCall(val call: StoryLineKind.FuncCall, val variable: String, val span: StorySpan)

/**
 * Takes calls out of expressions. A call is replaced by a variable name exactly as long as it, so every position the
 * expression language reports stays where the author wrote it.
 */
internal object StoryNestedCalls {
    /** The expression [fragment] starting at [start] of the file as written, or null when it does not parse. */
    fun ast(fragment: String, start: Int): Ast? {
        val diagnostics = Diagnostics()
        val ast = parseExpression(fragment, diagnostics, StoryLanguage.options, start)
        return ast.takeIf { diagnostics.all.none { it.severity == Severity.ERROR } }
    }

    /** The outermost calls of [ast], whose arguments are parsed from [text], the line starting at [offset]. */
    fun extract(ast: Ast, text: String, offset: Int, line: Int): List<StoryNestedCall> {
        val calls = ArrayList<Ast.Call>()
        collect(ast, calls)
        return calls.map { call ->
            val args = call.arguments.map { arg ->
                val name = (arg as? Ast.Assign)?.target as? Ast.Name
                val value = if (name != null) (arg as Ast.Assign).value else arg
                StoryArg(
                    name?.name,
                    name?.let { StorySpan(it.span.start, it.span.end, line) },
                    StoryExpressions.parse(text, offset, line, value.span.start - offset, value.span.end - offset),
                    StorySpan(arg.span.start, arg.span.end, line),
                )
            }
            val nameSpan = StorySpan(call.span.start, call.span.start + call.name.length, line)
            StoryNestedCall(
                StoryLineKind.FuncCall(call.name, nameSpan, args),
                variableFor(call.span.start - offset, call.span.end - call.span.start),
                StorySpan(call.span.start, call.span.end, line),
            )
        }
    }

    /** [fragment], starting at [start] of the file, with each of [calls] replaced by its variable. */
    fun replace(fragment: String, start: Int, calls: List<StoryNestedCall>): String {
        if (calls.isEmpty()) return fragment
        val text = StringBuilder(fragment)
        calls.sortedByDescending { it.span.start }.forEach { call ->
            text.replace(call.span.start - start, call.span.end - start, call.variable)
        }
        return text.toString()
    }

    private fun collect(ast: Ast, into: MutableList<Ast.Call>) {
        if (ast is Ast.Call && ast.target == null) {
            into += ast
            return
        }
        when (ast) {
            is Ast.ListLit -> ast.items.forEach { collect(it, into) }
            is Ast.Access -> collect(ast.target, into)
            is Ast.Index -> {
                collect(ast.target, into)
                collect(ast.index, into)
            }

            is Ast.Call -> {
                ast.target?.let { collect(it, into) }
                ast.arguments.forEach { collect(it, into) }
            }

            is Ast.Unary -> collect(ast.operand, into)
            is Ast.Binary -> {
                collect(ast.left, into)
                collect(ast.right, into)
            }

            is Ast.Conditional -> {
                collect(ast.condition, into)
                collect(ast.ifTrue, into)
                collect(ast.ifFalse, into)
            }

            is Ast.Assign -> {
                collect(ast.target, into)
                collect(ast.value, into)
            }

            is Ast.Sequence -> ast.statements.forEach { collect(it, into) }
            is Ast.NumberLit, is Ast.BoolLit, is Ast.StringLit, is Ast.Name -> Unit
        }
    }

    /**
     * The variable of a call at [column] of its line, padded to [length]: unique on the line, which is all it needs,
     * since its value is read by the statement right after the call.
     */
    private fun variableFor(column: Int, length: Int): String =
        ("_" + column.toString(Character.MAX_RADIX)).padEnd(length, '_')
}
