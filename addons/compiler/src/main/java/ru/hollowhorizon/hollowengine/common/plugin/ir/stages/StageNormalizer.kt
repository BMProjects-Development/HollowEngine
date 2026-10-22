package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.*
import org.jetbrains.kotlin.name.Name

/**
 * Brings a stage body into the shape [StageRewriter] works on: every stage call and checkpoint becomes a statement
 * of its own: `call()`, `val x = call()`, `x = call()` or `return call()` - whose arguments hold no stage call,
 * and conditions of `if`, `when` and `while` hold none either.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageNormalizer(private val stages: StageScope, private val function: IrSimpleFunction) {
    private val builtIns = stages.context.irBuiltIns
    private val intPlus: IrSimpleFunctionSymbol =
        builtIns.intClass.owner.declarations.filterIsInstance<IrSimpleFunction>()
            .single { it.name.asString() == "plus" && it.parameters.size == 2 && it.parameters[1].type == builtIns.intType }.symbol
    private var temporaries = 0

    /** The copies of the elements `for` loops go over, which a story saves while such a loop runs. */
    val loopCopies = HashSet<IrVariable>()

    /** Statements that must run first, then a value that holds no stage call except possibly at its top. */
    private class Lifted(val before: List<IrStatement>, val value: IrExpression)

    fun normalize(statements: List<IrStatement>): List<IrStatement> = statements.flatMap(::statement)

    /** Whether [element] calls a stage or a checkpoint, not counting nested functions. */
    fun hasSite(element: IrElement): Boolean {
        var found = false
        element.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!found) element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) = Unit

            override fun visitCall(expression: IrCall) {
                if (stages.siteKind(expression) != null) found = true else super.visitCall(expression)
            }
        })
        return found
    }

    private fun isSiteCall(expression: IrExpression) = expression is IrCall && stages.siteKind(expression) != null

    private fun statement(statement: IrStatement): List<IrStatement> {
        if (!hasSite(statement)) return listOf(statement)
        return when (statement) {
            is IrVariable -> value(statement.initializer!!).let { lifted ->
                statement.initializer = lifted.value
                lifted.before + statement
            }

            is IrSetValue -> value(statement.value).let { lifted ->
                statement.value = lifted.value
                lifted.before + statement
            }

            is IrReturn -> value(statement.value).let { lifted ->
                statement.value = lifted.value
                lifted.before + statement
            }

            is IrThrow -> value(statement.value).let { lifted ->
                val operand = materialize(lifted.value)
                statement.value = operand.value
                lifted.before + operand.before + statement
            }

            is IrTypeOperatorCall if statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> value(statement.argument).let { lifted ->
                statement.argument = lifted.value
                lifted.before + statement
            }

            is IrWhen -> listOf(whenStatement(statement))
            is IrWhileLoop -> whileLoop(statement)
            is IrDoWhileLoop -> doWhileLoop(statement)
            is IrBlock if statement.origin == IrStatementOrigin.FOR_LOOP -> forLoop(statement)
            is IrComposite -> normalize(statement.statements)
            is IrBlock -> {
                val normalized = normalize(statement.statements)
                statement.statements.clear()
                statement.statements += normalized
                listOf(statement)
            }

            is IrTry -> {
                statement.tryResult = block(normalize(statementsOf(statement.tryResult)))
                statement.type = builtIns.unitType
                listOf(statement)
            }

            is IrExpression -> value(statement).let { it.before + it.value }
            else -> unsupported(statement)
        }
    }

    /** Lifts the stage calls out of [expression]. */
    private fun value(expression: IrExpression): Lifted {
        if (!hasSite(expression)) return Lifted(emptyList(), expression)
        return when (expression) {
            is IrFunctionAccessExpression -> {
                val lifted = operands(expression.arguments.toList())
                lifted.second.forEachIndexed { index, operand -> expression.arguments[index] = operand }
                Lifted(lifted.first, expression)
            }

            is IrWhen -> whenValue(expression)
            is IrContainerExpression -> {
                val statements = expression.statements
                val last = statements.lastOrNull() as? IrExpression
                if (last == null || expression.type.isUnit()) return Lifted(normalize(statements), unit())
                val lifted = value(last)
                Lifted(normalize(statements.dropLast(1)) + lifted.before, lifted.value)
            }

            is IrTypeOperatorCall -> {
                val lifted = value(expression.argument)
                val operand = materialize(lifted.value)
                expression.argument = operand.value
                Lifted(lifted.before + operand.before, expression)
            }

            is IrGetField -> {
                val lifted = operands(listOf(expression.receiver))
                expression.receiver = lifted.second[0]
                Lifted(lifted.first, expression)
            }

            is IrSetField -> {
                val lifted = operands(listOf(expression.receiver, expression.value))
                expression.receiver = lifted.second[0]
                expression.value = lifted.second[1]!!
                Lifted(lifted.first, expression)
            }

            is IrStringConcatenation -> {
                val lifted = operands(expression.arguments.toList())
                lifted.second.forEachIndexed { index, operand -> expression.arguments[index] = operand!! }
                Lifted(lifted.first, expression)
            }

            is IrReturn, is IrThrow -> Lifted(statement(expression), zero(expression.type))
            is IrTry -> tryValue(expression)
            else -> Lifted(unsupported(expression), expression)
        }
    }

    /**
     * Lifts [operands] in the order they are evaluated. Whatever is evaluated before the last operand that calls a
     * stage moves into a temporary, so resuming that stage does not evaluate it again.
     */
    private fun operands(operands: List<IrExpression?>): Pair<List<IrStatement>, List<IrExpression?>> {
        val last = operands.indexOfLast { it != null && hasSite(it) }
        val before = ArrayList<IrStatement>()
        val result = operands.toMutableList()
        for (index in 0..last) {
            val lifted = value(operands[index] ?: continue)
            before += lifted.before
            result[index] = if (isSiteCall(lifted.value) || index < last && !isTrivial(lifted.value)) {
                val temporary = variable(lifted.value.type, initializer = lifted.value)
                before += temporary
                get(temporary)
            } else lifted.value
        }
        return before to result
    }

    private fun whenValue(expression: IrWhen): Lifted {
        if (expression.type.isUnit() || expression.type.isNothing()) return Lifted(
            listOf(whenStatement(expression)), unit()
        )
        val result = variable(expression.type, isVar = true, initializer = zero(expression.type))
        expression.branches.forEach { branch -> branch.result = assign(result, branch.result) }
        return Lifted(listOf(result, whenStatement(expression)), get(result))
    }

    private fun tryValue(expression: IrTry): Lifted {
        if (expression.type.isUnit()) return Lifted(statement(expression), unit())
        val result = variable(expression.type, isVar = true, initializer = zero(expression.type))
        expression.tryResult = assign(result, expression.tryResult)
        expression.catches.forEach { it.result = assign(result, it.result) }
        return Lifted(listOf(result) + statement(expression), get(result))
    }

    /** [expression] as statements that end by storing its value in [target]. */
    private fun assign(target: IrVariable, expression: IrExpression): IrExpression {
        if (expression is IrContainerExpression) {
            val statements = expression.statements
            val last = statements.lastOrNull() as? IrExpression ?: return expression
            return block(statements.dropLast(1) + assign(target, last))
        }
        if (expression.type.isNothing()) return expression
        return IrSetValueImpl(
            expression.startOffset, expression.endOffset, builtIns.unitType, target.symbol, expression, null
        )
    }

    private fun whenStatement(expression: IrWhen): IrExpression {
        // `&&` and `||` lose the shape their origins promise to the backend once their branches become statements.
        if (expression.origin == IrStatementOrigin.ANDAND || expression.origin == IrStatementOrigin.OROR) expression.origin =
            null
        val branches = ArrayList<IrBranch>()
        for ((index, branch) in expression.branches.withIndex()) {
            if (branch !is IrElseBranch && hasSite(branch.condition)) {
                val lifted = value(branch.condition)
                val condition = materialize(lifted.value)
                val rest = IrWhenImpl(
                    expression.startOffset, expression.endOffset, builtIns.unitType, expression.origin
                ).apply {
                    this.branches += IrBranchImpl(branch.startOffset, branch.endOffset, condition.value, branch.result)
                    this.branches += expression.branches.drop(index + 1)
                }
                val nested = block(lifted.before + condition.before + whenStatement(rest))
                branches += IrElseBranchImpl(branch.startOffset, branch.endOffset, constTrue(), nested)
                break
            }
            branch.result = block(normalize(statementsOf(branch.result)))
            branches += branch
        }
        expression.branches.clear()
        expression.branches += branches
        expression.type = builtIns.unitType
        return expression
    }

    private fun whileLoop(loop: IrWhileLoop): List<IrStatement> {
        val body = normalize(statementsOf(loop.body))
        if (!hasSite(loop.condition)) {
            loop.body = block(body)
            return listOf(loop)
        }
        val lifted = value(loop.condition)
        val condition = materialize(lifted.value)
        val builder = stages.builder(function.symbol, loop)
        val exit = IrWhenImpl(loop.startOffset, loop.endOffset, builtIns.unitType, null).apply {
            branches += IrBranchImpl(
                loop.startOffset, loop.endOffset,
                builder.irCall(builtIns.booleanNotSymbol).apply { arguments[0] = condition.value },
                IrBreakImpl(loop.startOffset, loop.endOffset, builtIns.nothingType, loop),
            )
        }
        loop.condition = constTrue()
        loop.body = block(lifted.before + condition.before + exit + body)
        return listOf(loop)
    }

    /**
     * A condition that calls stages is computed at the end of the body into a value the loop tests. `continue`
     * has to compute it as well, so the body runs once inside an inner loop that `continue` leaves instead.
     * The body stays a composite: the condition may read what the body declared.
     */
    private fun doWhileLoop(loop: IrDoWhileLoop): List<IrStatement> {
        if (!hasSite(loop.condition)) {
            loop.body = loop.body?.let { composite(normalize(statementsOf(it))) }
            return listOf(loop)
        }
        val inner = IrDoWhileLoopImpl(loop.startOffset, loop.endOffset, builtIns.unitType, null)
        inner.body = loop.body
        inner.condition = stages.builder(function.symbol, loop).irFalse()
        inner.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitContinue(jump: IrContinue): IrExpression = if (jump.loop == loop) IrBreakImpl(
                jump.startOffset,
                jump.endOffset,
                builtIns.nothingType,
                inner
            ) else jump
        })
        inner.body = inner.body?.let { composite(normalize(statementsOf(it))) }
        val lifted = value(loop.condition)
        val condition = variable(builtIns.booleanType, initializer = lifted.value, name = "condition")
        loop.body = composite(listOf(inner) + lifted.before + condition)
        loop.condition = get(condition)
        return listOf(loop)
    }

    /**
     * Rewrites a `for` that calls stages to count with an index the frame can save. A range of Int is counted as it
     * is; anything else is copied into a list first (see `StoryLoops.snapshot`), whose elements must then be
     * serializable. A sequence may never end and is an error.
     */
    private fun forLoop(block: IrBlock): List<IrStatement> {
        val iterator = block.statements.firstOrNull() as? IrVariable
        val loop = block.statements.getOrNull(1) as? IrWhileLoop
        val iterable = (iterator?.initializer as? IrCall)?.arguments?.firstOrNull()
        if (iterator?.origin != IrDeclarationOrigin.FOR_LOOP_ITERATOR || loop == null || iterable == null) {
            return unsupported(block)
        }

        val builder = stages.builder(function.symbol, block)
        val index = variable(builtIns.intType, isVar = true, initializer = builder.irInt(-1), name = "index")
        val prologue = ArrayList<IrStatement>()
        val bound: IrExpression
        val element: (IrType) -> IrExpression
        val progressionClass = stages.symbols.intProgression
        val sequenceClass = stages.symbols.sequence
        val elementType = nextCall(loop, iterator)?.type
        when {
            progressionClass == null || !iterable.type.isSubtypeOfClass(progressionClass) -> {
                if (sequenceClass != null && iterable.type.isSubtypeOfClass(sequenceClass) || elementType == null) {
                    stages.reporter.error(
                        block,
                        "A for loop that calls stages cannot go over ${iterable.type.render()}: it may never end; call toList() first"
                    )
                    return listOf(block)
                }
                val listClass = builtIns.listClass.owner
                val snapshot = with(stages) { builder.callObject(stages.symbols.loopSnapshot, iterable) }
                val listType = builtIns.listClass.typeWith(elementType)
                val list = variable(listType, initializer = builder.irImplicitCast(snapshot, listType), name = "list")
                loopCopies += list
                prologue += list
                bound = builder.irCall(listClass.getPropertyGetter("size")!!).apply { arguments[0] = get(list) }
                element = { type ->
                    builder.irImplicitCast(builder.irCall(listClass.getSimpleFunction("get")!!).apply {
                        arguments[0] = get(list)
                        arguments[1] = get(index)
                    }, type)
                }
            }

            else -> {
                val progression = variable(iterable.type, initializer = iterable, name = "range")
                fun property(name: String) = builder.irCall(progressionClass.owner.getPropertyGetter(name)!!).apply {
                    arguments[0] = get(progression)
                }

                val first = variable(builtIns.intType, initializer = property("first"), name = "first")
                val step = variable(builtIns.intType, initializer = property("step"), name = "step")
                val count = variable(
                    builtIns.intType,
                    initializer = with(stages) { builder.callObject(stages.symbols.loopCount, get(progression)) },
                    name = "count"
                )
                prologue += listOf(progression, first, step, count)
                bound = get(count)
                element = {
                    with(stages) {
                        builder.callObject(
                            stages.symbols.loopElement, get(first), get(step), get(index)
                        )
                    }
                }
            }
        }

        loop.condition = builder.irBlock(resultType = builtIns.booleanType) {
            +irSet(index, irCall(intPlus).apply {
                arguments[0] = irGet(index)
                arguments[1] = irInt(1)
            })
            +irCall(builtIns.lessFunByOperandType.getValue(builtIns.intClass)).apply {
                arguments[0] = irGet(index)
                arguments[1] = bound
            }
        }
        loop.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val receiver = expression.arguments.firstOrNull() as? IrGetValue
                return if (expression.origin == IrStatementOrigin.FOR_LOOP_NEXT && receiver?.symbol == iterator.symbol) {
                    element(expression.type)
                } else expression
            }
        })
        loop.origin = null
        loop.body = block(normalize(statementsOf(loop.body)))
        block.statements.clear()
        block.statements += prologue + index + loop
        block.origin = null
        return listOf(block)
    }

    /** The `next()` call of a `for` loop's iterator, which gives the type of its elements. */
    private fun nextCall(loop: IrWhileLoop, iterator: IrVariable): IrCall? {
        var found: IrCall? = null
        loop.body?.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found == null) element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                val receiver = expression.arguments.firstOrNull() as? IrGetValue
                if (expression.origin == IrStatementOrigin.FOR_LOOP_NEXT && receiver?.symbol == iterator.symbol) found =
                    expression
                else super.visitCall(expression)
            }
        })
        return found
    }

    /** Moves a stage call out of an operand into a temporary; anything else stays where it is. */
    private fun materialize(expression: IrExpression): Lifted {
        if (!isSiteCall(expression)) return Lifted(emptyList(), expression)
        val temporary = variable(expression.type, initializer = expression)
        return Lifted(listOf(temporary), get(temporary))
    }

    private fun variable(
        type: IrType,
        isVar: Boolean = false,
        initializer: IrExpression? = null,
        name: String = "tmp",
    ): IrVariable = IrVariableImpl(
        initializer?.startOffset ?: function.startOffset, initializer?.endOffset ?: function.endOffset,
        IrDeclarationOrigin.IR_TEMPORARY_VARIABLE, IrVariableSymbolImpl(),
        Name.identifier("$name\$${temporaries++}"), type, isVar, isConst = false, isLateinit = false,
    ).also {
        it.parent = function
        it.initializer = initializer
    }

    private fun get(variable: IrVariable): IrExpression = stages.builder(function.symbol, variable).irGet(variable)

    private fun isTrivial(expression: IrExpression): Boolean = when (expression) {
        is IrConst, is IrGetObjectValue, is IrGetEnumValue, is IrClassReference, is IrFunctionExpression -> true
        is IrGetValue -> (expression.symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }

    private fun statementsOf(expression: IrExpression?): List<IrStatement> = when (expression) {
        null -> emptyList()
        is IrComposite -> expression.statements
        is IrBlock if expression.origin == null -> expression.statements
        else -> listOf(expression)
    }

    private fun block(statements: List<IrStatement>): IrBlock =
        IrBlockImpl(function.startOffset, function.endOffset, builtIns.unitType, null, statements)

    private fun composite(statements: List<IrStatement>): IrComposite = IrCompositeImpl(
        function.startOffset,
        function.endOffset,
        builtIns.unitType
    ).apply { this.statements += statements }

    private fun zero(type: IrType): IrExpression =
        IrConstImpl.defaultValueForType(function.startOffset, function.endOffset, type)

    private fun unit(): IrExpression = stages.builder(function.symbol, function).irGetObject(builtIns.unitClass)

    private fun constTrue(): IrExpression = stages.builder(function.symbol, function).irTrue()

    private fun unsupported(element: IrElement): List<IrStatement> {
        stages.reporter.error(element, "A stage cannot be called here; move the call into a statement of its own")
        return emptyList()
    }
}
