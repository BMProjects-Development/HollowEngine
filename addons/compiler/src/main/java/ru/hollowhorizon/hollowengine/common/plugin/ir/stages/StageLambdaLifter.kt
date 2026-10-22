package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Turns every lambda written for a `@State` function type into a stage of its own.
 *
 * The body moves into a new function of the script, and what the lambda captured becomes its parameters, so the
 * story can start the stage again after a restart with the captured values it saved. In place of the lambda the
 * call gets a `StageLambda` naming that function. The stage is named after where the lambda was written
 * (`onJoin$launchStory#1`), so the name survives edits elsewhere in the script.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageLambdaLifter(private val stages: StageScope) : IrElementTransformerVoid() {
    private val functions = ArrayDeque<IrFunction>()
    private val owners = ArrayDeque<String>()
    private val counters = HashMap<String, Int>()

    /** The functions made from lambdas, in the order they were made. */
    val lifted = ArrayList<IrSimpleFunction>()

    override fun visitProperty(declaration: IrProperty): IrStatement {
        owners.addLast(declaration.name.asString())
        return super.visitProperty(declaration).also { owners.removeLast() }
    }

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val named =
            declaration.parent == stages.script && declaration is IrSimpleFunction && declaration.correspondingPropertySymbol == null

        if (named) owners.addLast(declaration.name.asString())
        functions.addLast(declaration)

        return super.visitFunction(declaration).also {
            functions.removeLast()
            if (named) owners.removeLast()
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val callee = expression.symbol.owner
        callee.parameters.forEach { parameter ->
            if (!stages.types.isStageLambda(parameter.type)) return@forEach
            val lambda = expression.arguments[parameter] as? IrFunctionExpression ?: return@forEach
            lift(lambda, callee.name.asString(), parameter)?.let { expression.arguments[parameter] = it }
        }
        return expression
    }

    private fun lift(expression: IrFunctionExpression, callee: String, parameter: IrValueParameter): IrExpression? {
        val lambda = expression.function
        if (lambda.parameters.isNotEmpty()) {
            stages.reporter.error(expression, "A @State lambda cannot take parameters or a receiver")
            return null
        }
        val captured = captures(lambda) ?: return null
        val owner = owners.lastOrNull() ?: "script"
        val key = "$owner$$callee"
        val ordinal = counters.merge(key, 1, Int::plus)!!
        val name = "$key#$ordinal"
        val parameters = createStage(lambda, name, captured)

        val scriptThis = scriptThis()
        return stages.builder(functions.last().symbol, expression).irBlock(resultType = parameter.type) {
            val tag = irTemporary(irCallConstructor(stages.symbols.compoundTagConstructor, emptyList()))
            with(stages) {
                parameters.forEach { (value, copy) ->
                    +callObject(
                        stages.symbols.put,
                        irGet(tag),
                        irString(copy.name.asString()),
                        serializer(value.type),
                        irGet(value)
                    )
                }
                val key = callObject(stages.symbols.keyOf, irGet(scriptThis))
                val lambdaValue =
                    irCallConstructor(stages.symbols.stageLambdaConstructor, listOf(lambda.returnType)).apply {
                        arguments[0] = key
                        arguments[1] = irString(name)
                        arguments[2] = irGet(tag)
                        arguments[3] = serializer(lambda.returnType)
                    }
                +irImplicitCast(lambdaValue, parameter.type)
            }
        }
    }

    /** The values from outside [lambda] that it reads, or null when one of them cannot be saved. */
    private fun captures(lambda: IrSimpleFunction): List<IrValueDeclaration>? {
        val inside = declaredIn(lambda)
        val captured = LinkedHashSet<IrValueDeclaration>()
        var valid = true
        lambda.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitGetValue(expression: IrGetValue) {
                val value = expression.symbol.owner
                if (value !in inside && !stages.isScriptThis(value)) captured += value
            }

            override fun visitSetValue(expression: IrSetValue) {
                super.visitSetValue(expression)
                if (expression.symbol.owner !in inside) {
                    stages.reporter.error(
                        expression,
                        "A @State lambda cannot assign '${expression.symbol.owner.name}': it keeps a saved copy"
                    )
                    valid = false
                }
            }
        })
        captured.forEach { value ->
            if (value is IrVariable && value.isVar) {
                stages.reporter.error(
                    lambda, "A @State lambda cannot capture 'var ${value.name}'; copy it into a val first"
                )
                valid = false
            }
            stages.types.problem(value.type)?.let { problem ->
                stages.reporter.error(
                    lambda, "A @State lambda saves what it captures, but '${value.name}' cannot be saved: $problem"
                )
                valid = false
            }
        }
        return captured.toList().takeIf { valid }
    }

    private fun createStage(
        lambda: IrSimpleFunction,
        name: String,
        captured: List<IrValueDeclaration>,
    ): Map<IrValueDeclaration, IrValueParameter> {
        val script = stages.script
        val stage = stages.context.irFactory.buildFun {
            startOffset = lambda.startOffset
            endOffset = lambda.endOffset
            this.name = Name.identifier($$"stage$lambda$$${lifted.size}")
            returnType = lambda.returnType
            isSuspend = true
            visibility = DescriptorVisibilities.PUBLIC
            origin = IrDeclarationOrigin.DEFINED
        }
        stage.parent = script
        val receiver = stage.buildReceiverParameter { type = script.defaultType }
        stage.parameters = listOf(receiver)
        val names = HashSet<String>()
        val parameters = captured.associateWith { value ->
            val base = value.name.asString().takeIf { value.name.isSpecial.not() } ?: "captured"
            val unique = generateSequence(base) { "${it}_" }.first(names::add)
            stage.addValueParameter(unique, value.type)
        }
        stage.annotations = listOf(stages.stateAnnotation(name))
        val inside = declaredIn(lambda)
        stage.body = lambda.body
        stage.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val value = expression.symbol.owner
                val replacement =
                    parameters[value] ?: receiver.takeIf { stages.isScriptThis(value) && value !in inside }
                    ?: return expression
                return IrGetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    replacement.type,
                    replacement.symbol,
                    expression.origin
                )
            }

            override fun visitReturn(expression: IrReturn): IrExpression {
                expression.transformChildrenVoid(this)
                if (expression.returnTargetSymbol == lambda.symbol) expression.returnTargetSymbol = stage.symbol
                return expression
            }
        })
        stage.patchDeclarationParents(script)
        lifted += stage
        return parameters
    }

    /** The script instance as seen from where the lambda is written. */
    private fun scriptThis(): IrValueParameter {
        functions.reversed().forEach { function ->
            function.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver && stages.isScriptThis(it) }
                ?.let { return it }
        }
        return stages.script.thisReceiver ?: error("Script class ${stages.script.name} has no receiver")
    }
}

/** Every value declared in [root]: its own parameters, its variables and those of anything nested in it. */
internal fun declaredIn(root: IrElement): Set<IrValueDeclaration> {
    val declared = Collections.newSetFromMap(IdentityHashMap<IrValueDeclaration, Boolean>())
    if (root is IrFunction) declared += root.parameters
    root.acceptChildrenVoid(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        override fun visitDeclaration(declaration: IrDeclarationBase) {
            if (declaration is IrValueDeclaration) declared += declaration
            if (declaration is IrFunction) declared += declaration.parameters
            super.visitDeclaration(declaration)
        }
    })
    return declared
}
