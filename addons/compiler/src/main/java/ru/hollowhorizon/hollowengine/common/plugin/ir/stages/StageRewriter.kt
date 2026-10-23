package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.defaultValueForType
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Rewrites a normalized stage body so its frame can lead it back to where it was saved.
 *
 * The body keeps its structure. Every statement runs only while the stage is not resuming, or when the position
 * it resumes to lies inside it; a branch or loop that holds that position is entered without evaluating its
 * condition again. A stage call saves the live locals, marks the frame and cStatealls; resuming it either calls the
 * saved callee again or takes the result it had. A tail call leaves the frame to the callee.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageRewriter(
    private val stages: StageScope,
    private val function: IrSimpleFunction,
    private val layout: StageLayout,
) {
    private val symbols = stages.symbols
    private val builtIns = stages.context.irBuiltIns
    private val returnType = function.returnType
    private val saved = layout.saved.toSet()
    private lateinit var frame: IrVariable

    /** Replaces the body of the stage with its rewritten form. */
    fun rewrite(statements: List<IrStatement>, name: String, hash: Int) {
        val builder = stages.builder(function.symbol, function)
        val receiver = function.parameters.first { it.kind == IrParameterKind.DispatchReceiver }
        frame = buildVariable(
            function,
            function.startOffset,
            function.endOffset,
            IrDeclarationOrigin.DEFINED,
            Name.identifier("\$frame"),
            symbols.frameType
        )
        frame.initializer = with(stages) {
            builder.callObject(
                symbols.enter, builder.irGet(receiver), builder.irString(name), builder.irInt(hash),
                builder.irCall(builtIns.arrayOf).apply {
                    typeArguments[0] = builtIns.stringType
                    arguments[0] = builder.irVararg(builtIns.stringType, layout.sites.map { builder.irString(it.name) })
                },
            )
        }

        if (!returnType.isUnit()) reportReturns(statements)
        val rewritten = statements.map(::statement)
        val body = ArrayList<IrStatement>()
        stages.arguments(function).forEach { parameter ->
            body += frameCall(
                builder,
                "saveArg",
                builder.irString(stages.argumentName(function, parameter)),
                serializer(builder, parameter.type),
                builder.irGet(parameter)
            )
        }
        layout.saved.forEach { variable ->
            variable.isVar = true
            variable.initializer = zero(variable.type)
            body += variable
        }
        if (layout.saved.isNotEmpty()) {
            body += builder.irIfThen(builtIns.unitType, frameCall(builder, "isResuming"), builder.irBlock {
                layout.saved.forEach { variable ->
                    val restored = frameCall(
                        this,
                        "restore",
                        irString(layout.keys.getValue(variable)),
                        serializer(this, variable.type),
                        irGet(variable)
                    )
                    // A local the resumed site did not save comes back as the null it was declared with.
                    +irSet(variable, irAs(restored, variable.type.makeNullable()))
                }
            })
        }
        body += rewritten
        if (!returnType.isUnit()) body += frameCall(builder, "lost")

        val failure = buildVariable(
            function,
            function.startOffset,
            function.endOffset,
            IrDeclarationOrigin.CATCH_PARAMETER,
            Name.identifier("\$failure"),
            builtIns.throwableType
        )
        val rethrow = builder.irBlock(resultType = builtIns.nothingType) {
            +frameCall(this, "abort", irGet(failure))
            +IrThrowImpl(startOffset, endOffset, builtIns.nothingType, irGet(failure))
        }
        val guarded = IrTryImpl(
            function.startOffset, function.endOffset,
            if (returnType.isUnit()) builtIns.unitType else builtIns.nothingType,
            IrBlockImpl(function.startOffset, function.endOffset, builtIns.unitType, null, body),
            listOf(IrCatchImpl(function.startOffset, function.endOffset, failure, rethrow)),
            frameCall(builder, "leave"),
        )
        function.body = builder.irBlockBody {
            +frame
            +guarded
        }
    }

    private fun statement(statement: IrStatement): IrStatement {
        siteOf(statement)?.let { return expandSite(it, statement) }
        if (statement is IrVariable && statement in saved) return assignSaved(statement)
        val positions = layout.positions(statement) ?: return guardFresh(statement)
        return when (statement) {
            is IrWhen -> guard(positions, routeWhen(statement))
            is IrWhileLoop -> guard(positions, routeWhile(statement))
            is IrDoWhileLoop -> guard(positions, statement.also { it.body = rewriteBody(it.body) })
            is IrTry -> guard(positions, statement.also { it.tryResult = rewriteBlock(it.tryResult)!! })
            is IrBlock -> guard(positions, statement.also { block ->
                val rewritten = block.statements.map(::statement)
                block.statements.clear()
                block.statements += rewritten
            })

            else -> statement.also {
                stages.reporter.error(
                    it, "A stage cannot be called here; move the call into a statement of its own"
                )
            }
        }
    }

    /** A saved local is declared at the top of the stage, so where it was declared it is only assigned. */
    private fun assignSaved(variable: IrVariable): IrStatement {
        val builder = stages.builder(function.symbol, variable)
        val initializer = variable.initializer ?: return builder.irGetObject(builtIns.unitClass)
        return builder.irIfThen(
            builtIns.unitType, not(builder, frameCall(builder, "isResuming")), builder.irSet(variable, initializer)
        )
    }

    /** A statement without sites runs only when the stage is not resuming. Declarations stay where they are. */
    private fun guardFresh(statement: IrStatement): IrStatement {
        val builder = stages.builder(function.symbol, statement)
        if (statement is IrVariable) {
            val initializer = statement.initializer ?: return statement
            statement.initializer = builder.irIfThenElse(
                statement.type, frameCall(builder, "isResuming"), zero(statement.type), initializer
            )
            return statement
        }
        val expression = statement as? IrExpression ?: return statement
        return builder.irIfThen(builtIns.unitType, not(builder, frameCall(builder, "isResuming")), expression)
    }

    private fun guard(positions: IntRange, statement: IrExpression): IrExpression {
        val builder = stages.builder(function.symbol, statement)
        return builder.irIfThen(
            builtIns.unitType,
            frameCall(builder, "enters", builder.irInt(positions.first), builder.irInt(positions.last)),
            statement
        )
    }

    /** Each branch is taken when the stage resumes into it, or when it is not resuming and its condition holds. */
    private fun routeWhen(expression: IrWhen): IrWhen {
        val builder = stages.builder(function.symbol, expression)
        val branches = expression.branches.map { branch ->
            val positions = layout.positions(branch.result)
            val fresh = and(builder, not(builder, frameCall(builder, "isResuming")), branch.condition)
            val condition = positions?.let {
                or(builder, frameCall(builder, "resumesInto", builder.irInt(it.first), builder.irInt(it.last)), fresh)
            } ?: fresh
            IrBranchImpl(branch.startOffset, branch.endOffset, condition, rewriteBlock(branch.result)!!) as IrBranch
        }
        expression.branches.clear()
        expression.branches += branches
        return expression
    }

    /** A loop the stage resumes into is entered without its condition, which held when the stage was saved. */
    private fun routeWhile(loop: IrWhileLoop): IrExpression = stages.builder(function.symbol, loop).irBlock {
        val entering = irTemporary(frameCall(this, "isResuming"), "resuming", isMutable = true)
        loop.condition = or(this, irGet(entering), loop.condition)
        loop.body = IrBlockImpl(
            loop.startOffset,
            loop.endOffset,
            builtIns.unitType,
            null,
            listOf(irSet(entering, irFalse()), rewriteBlock(loop.body)!!)
        )
        +loop
    }

    private fun rewriteBlock(expression: IrExpression?): IrExpression? = expression?.let {
        IrBlockImpl(it.startOffset, it.endOffset, builtIns.unitType, null, statementsOf(it).map(::statement))
    }

    /** The body of a do-while stays a composite: its condition may read what the body declares. */
    private fun rewriteBody(expression: IrExpression?): IrExpression? = expression?.let {
        if (it !is IrComposite) return rewriteBlock(it)
        IrCompositeImpl(
            it.startOffset,
            it.endOffset,
            builtIns.unitType
        ).apply { statements += statementsOf(it).map(::statement) }
    }

    private fun expandSite(site: StageSite, statement: IrStatement): IrStatement {
        val builder = stages.builder(function.symbol, statement)
        return when (site.form) {
            SiteForm.STATEMENT -> if (site.tail) tailCall(builder, site) else value(builder, site, used = false)
            SiteForm.VARIABLE -> {
                val variable = statement as IrVariable
                val value = value(builder, site, used = true)
                if (variable in saved) assignAt(builder, site, builder.irSet(variable, value))
                else variable.also { it.initializer = value }
            }

            SiteForm.ASSIGN -> assignAt(
                builder,
                site,
                (statement as IrSetValue).also { it.value = value(builder, site, used = true) })

            SiteForm.RETURN -> if (site.tail) tailCall(builder, site) else {
                (statement as IrReturn).also {
                    it.value = returning(builder, value(builder, site, used = true), site.call.type)
                }
            }
        }
    }

    /**
     * Assigns the value of a site only when the stage runs it or resumes to it: resuming elsewhere skips the site,
     * and the variable keeps the value it was restored with.
     */
    private fun assignAt(builder: IrBuilderWithScope, site: StageSite, assignment: IrExpression): IrExpression =
        builder.irIfThen(
            builtIns.unitType,
            frameCall(builder, "enters", builder.irInt(site.callPosition), builder.irInt(site.afterPosition)),
            assignment,
        )

    /** The value of a site: resumed, taken from the save, or computed by calling. */
    private fun value(builder: IrBuilderWithScope, site: StageSite, used: Boolean): IrExpression {
        val type = if (used) site.call.type else builtIns.unitType
        val keepsResult = !type.isUnit() && !type.isNothing()
        if (site.kind == SiteKind.ACTION) return action(builder, site, type, keepsResult)
        val fresh = builder.irBlock(resultType = type) {
            if (site.kind == SiteKind.CHECKPOINT) {
                saveLocals(site)
                val labels = site.call.arguments.getOrNull(1)
                +(if (labels == null) frameCall(this, "checkpoint", irInt(site.index)) else frameCall(
                    this,
                    "checkpoint",
                    irInt(site.index),
                    labels
                ))
            } else {
                // The frame remembers where it was before the locals of the call overwrite the saved ones.
                +frameCall(this, "beginCall", irInt(site.index))
                saveLocals(site)
                val completed = frameCall(this, "completeCall", irInt(site.index), site.call)
                +(if (keepsResult) irAs(completed, type) else completed)
            }
        }
        val after = frameCall(
            builder,
            "resumeAfter",
            builder.irInt(site.index),
            if (keepsResult) serializer(builder, type) else nullSerializer()
        )
        val branches = ArrayList<IrBranch>()
        if (site.kind != SiteKind.CHECKPOINT) {
            val resumed = frameCall(builder, "resumeCall", builder.irInt(site.index))
            branches += builder.irBranch(
                frameCall(builder, "isResumingAt", builder.irInt(site.callPosition)),
                if (keepsResult) builder.irAs(resumed, type) else resumed
            )
        }
        branches += builder.irBranch(
            frameCall(builder, "isResumingAt", builder.irInt(site.afterPosition)),
            if (keepsResult) builder.irAs(after, type) else after
        )
        branches += builder.irBranch(frameCall(builder, "isResuming"), zero(type))
        branches += builder.irElseBranch(fresh)
        return builder.irWhen(type, branches)
    }

    /**
     * The value of an action call: performed, finished from its record when a restart interrupted it, or taken from
     * the save when it was done before the restart.
     */
    private fun action(builder: IrBuilderWithScope, site: StageSite, type: IrType, keepsResult: Boolean): IrExpression {
        val resultSerializer = { at: IrBuilderWithScope -> if (keepsResult) serializer(at, type) else nullSerializer() }
        fun completed(at: IrBuilderWithScope, value: IrExpression): IrExpression =
            frameCall(at, "completeAction", at.irInt(site.index), value, resultSerializer(at)).let { if (keepsResult) at.irAs(it, type) else it }

        val fresh = builder.irBlock(resultType = type) {
            +frameCall(this, "beginAction", irInt(site.index))
            saveLocals(site)
            +completed(this, site.call)
        }
        val after = frameCall(builder, "resumeAfter", builder.irInt(site.index), resultSerializer(builder))
        return builder.irWhen(
            type, listOf(
                builder.irBranch(
                    frameCall(builder, "isResumingAt", builder.irInt(site.callPosition)),
                    completed(builder, frameCall(builder, "resumeAction", builder.irInt(site.index)))
                ),
                builder.irBranch(
                    frameCall(builder, "isResumingAt", builder.irInt(site.afterPosition)),
                    if (keepsResult) builder.irAs(after, type) else after
                ),
                builder.irBranch(frameCall(builder, "isResuming"), zero(type)),
                builder.irElseBranch(fresh),
            )
        )
    }

    /**
     * A call in tail position. When the caller runs tail calls, the frame gives its place to the callee and the
     * stage returns at once; otherwise it is an ordinary call whose result the stage returns.
     */
    private fun tailCall(builder: IrBuilderWithScope, site: StageSite): IrExpression {
        val call = site.call
        val type = call.type
        val callee = call.symbol.owner
        val resumed = builder.irBlock(resultType = builtIns.nothingType) {
            val callBranch = irBranch(
                frameCall(this, "isResumingAt", irInt(site.callPosition)),
                frameCall(this, "resumeCall", irInt(site.index))
            )
            if (type.isUnit()) {
                +irWhen(
                    builtIns.unitType, listOf(
                        callBranch, irElseBranch(frameCall(this, "resumeAfter", irInt(site.index), nullSerializer()))
                    )
                )
                +irReturn(zero(returnType))
            } else {
                val after = frameCall(this, "resumeAfter", irInt(site.index), serializer(this, type))
                val value = irAs(irWhen(builtIns.anyNType, listOf(callBranch, irElseBranch(after))), type)
                +irReturn(returning(this, value, type))
            }
        }
        val fresh = builder.irBlock(resultType = builtIns.nothingType) {
            val arguments = call.arguments.map { argument -> argument?.let { irTemporary(it, "argument") } }
            arguments.forEachIndexed { index, temporary -> call.arguments[index] = temporary?.let(::irGet) }
            val handOver = irBlock(resultType = builtIns.nothingType) {
                val tag = irTemporary(irCallConstructor(symbols.compoundTagConstructor, emptyList()))
                callee.parameters.forEachIndexed { index, parameter ->
                    if (parameter.kind == IrParameterKind.DispatchReceiver) return@forEachIndexed
                    +with(stages) {
                        callObject(
                            symbols.put,
                            irGet(tag),
                            irString(stages.argumentName(callee, parameter)),
                            serializer(parameter.type),
                            irGet(arguments[index]!!)
                        )
                    }
                }
                +frameCall(this, "tailCall", irGet(arguments[0]!!), irString(stages.stageName(callee)), irGet(tag))
                +irReturn(zero(returnType))
            }
            val ordinary = irBlock(resultType = builtIns.nothingType) {
                +frameCall(this, "beginCall", irInt(site.index))
                saveLocals(site)
                val completed = frameCall(this, "completeCall", irInt(site.index), call)
                if (returnType.isUnit()) {
                    +completed
                    +irReturn(zero(returnType))
                } else +irReturn(returning(this, irAs(completed, type), type))
            }
            +irIfThenElse(builtIns.nothingType, frameCall(this, "canTailCall"), handOver, ordinary)
        }
        return builder.irWhen(
            builtIns.unitType, listOf(
                builder.irBranch(
                    frameCall(
                        builder, "resumesInto", builder.irInt(site.callPosition), builder.irInt(site.afterPosition)
                    ), resumed
                ),
                builder.irBranch(frameCall(builder, "isResuming"), zero(builtIns.unitType)),
                builder.irElseBranch(fresh),
            )
        )
    }

    /** Every `return` of the stage that is not a stage call hands its value to the frame as well. */
    private fun reportReturns(statements: List<IrStatement>) {
        val transformer = object : IrElementTransformerVoid() {
            override fun visitFunction(declaration: IrFunction): IrStatement = declaration

            override fun visitReturn(expression: IrReturn): IrExpression {
                expression.transformChildrenVoid(this)
                val site = (expression.value as? IrCall)?.let(layout::site)
                if (expression.returnTargetSymbol == function.symbol && site == null) {
                    expression.value =
                        returning(stages.builder(function.symbol, expression), expression.value, expression.value.type)
                }
                return expression
            }
        }
        IrBlockImpl(
            function.startOffset, function.endOffset, builtIns.unitType, null, statements
        ).transformChildrenVoid(transformer)
    }

    /** Hands the value a stage returns to its frame, which keeps it for a caller resumed after a restart. */
    private fun returning(builder: IrBuilderWithScope, value: IrExpression, type: IrType): IrExpression =
        builder.irAs(frameCall(builder, "returning", value, serializer(builder, returnType)), type)

    private fun IrBlockBuilder.saveLocals(site: StageSite) {
        site.live.forEach { variable ->
            +frameCall(
                this, "save", irString(layout.keys.getValue(variable)), serializer(this, variable.type), irGet(variable)
            )
        }
    }

    private fun siteOf(statement: IrStatement): StageSite? {
        val call = when (statement) {
            is IrCall -> statement
            is IrTypeOperatorCall if statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> statement.argument as? IrCall
            is IrVariable -> statement.initializer as? IrCall
            is IrSetValue -> statement.value as? IrCall
            is IrReturn -> statement.value as? IrCall
            else -> null
        } ?: return null
        return layout.site(call)
    }

    private fun frameCall(builder: IrBuilderWithScope, name: String, vararg arguments: IrExpression): IrCall =
        builder.irCall(symbols.frameFunction(name, arguments.size)).apply {
            this.arguments[0] = builder.irGet(frame)
            arguments.forEachIndexed { index, argument -> this.arguments[index + 1] = argument }
        }

    private fun serializer(builder: IrBuilderWithScope, type: IrType): IrExpression =
        with(stages) { builder.serializer(type) }

    private fun not(builder: IrBuilderWithScope, value: IrExpression): IrExpression =
        builder.irCall(builtIns.booleanNotSymbol).apply { arguments[0] = value }

    private fun and(builder: IrBuilderWithScope, left: IrExpression, right: IrExpression): IrExpression =
        builder.irIfThenElse(builtIns.booleanType, left, right, builder.irFalse(), IrStatementOrigin.ANDAND)

    private fun or(builder: IrBuilderWithScope, left: IrExpression, right: IrExpression): IrExpression =
        builder.irIfThenElse(builtIns.booleanType, left, builder.irTrue(), right, IrStatementOrigin.OROR)

    private fun nullSerializer(): IrExpression =
        IrConstImpl.constNull(function.startOffset, function.endOffset, symbols.serializerType)

    private fun zero(type: IrType): IrExpression =
        if (type.isUnit()) stages.builder(function.symbol, function).irGetObject(builtIns.unitClass)
        else IrConstImpl.defaultValueForType(function.startOffset, function.endOffset, type)

    /** Generates the entry the runtime calls to start or resume the stage with saved arguments. */
    fun entry(name: String): IrSimpleFunction {
        val script = stages.script
        val entry = stages.context.irFactory.buildFun {
            startOffset = function.startOffset
            endOffset = function.endOffset
            this.name = Name.identifier($$"stage$entry$$${function.name.asString()}")
            returnType = builtIns.anyNType
            isSuspend = true
            visibility = DescriptorVisibilities.PUBLIC
        }
        entry.parent = script
        val receiver = entry.buildReceiverParameter { type = script.defaultType }
        entry.parameters = listOf(receiver)
        val args = entry.addValueParameter("args", symbols.compoundTagType)
        entry.annotations = listOf(stages.entryAnnotation(name))
        entry.body = stages.builder(entry.symbol, function).irBlockBody {
            val call = irCall(function.symbol).apply {
                function.parameters.forEachIndexed { index, parameter ->
                    arguments[index] = if (parameter.kind == IrParameterKind.DispatchReceiver) irGet(receiver) else {
                        val value = with(stages) {
                            callObject(
                                symbols.get,
                                irGet(args),
                                irString(stages.argumentName(function, parameter)),
                                serializer(parameter.type)
                            )
                        }
                        irAs(value, parameter.type)
                    }
                }
            }
            if (returnType.isUnit()) {
                +call
                +irReturn(irGetObject(builtIns.unitClass))
            } else +irReturn(call)
        }
        return entry
    }

    /**
     * Generates the call of a stage made from a lambda: the runtime passes the [captures] it saved and the
     * parameters of the call, which the stage takes after the captured values.
     */
    fun lambdaCall(name: String, captures: Int): IrSimpleFunction {
        val script = stages.script
        val call = stages.context.irFactory.buildFun {
            startOffset = function.startOffset
            endOffset = function.endOffset
            this.name = Name.identifier("stage\$call\$${function.name.asString()}")
            returnType = builtIns.anyNType
            isSuspend = true
            visibility = DescriptorVisibilities.PUBLIC
        }
        call.parent = script
        val receiver = call.buildReceiverParameter { type = script.defaultType }
        call.parameters = listOf(receiver)
        val captured = call.addValueParameter("captured", symbols.compoundTagType)
        val arguments = stages.arguments(function)
        val parameters = arguments.drop(captures).map { call.addValueParameter(it.name, it.type) }
        call.annotations = listOf(stages.callAnnotation(name))
        call.body = stages.builder(call.symbol, function).irBlockBody {
            val invocation = irCall(function.symbol).apply {
                this.arguments[0] = irGet(receiver)
                arguments.forEachIndexed { index, parameter ->
                    this.arguments[index + 1] = if (index < captures) {
                        val value = with(stages) {
                            callObject(
                                symbols.get,
                                irGet(captured),
                                irString(stages.argumentName(function, parameter)),
                                serializer(parameter.type)
                            )
                        }
                        irAs(value, parameter.type)
                    } else irGet(parameters[index - captures])
                }
            }
            if (returnType.isUnit()) {
                +invocation
                +irReturn(irGetObject(builtIns.unitClass))
            } else +irReturn(invocation)
        }
        return call
    }
}
