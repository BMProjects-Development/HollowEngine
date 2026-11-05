package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageMessages

/**
 * Lets `@State` lambdas share the vars of the stage they are written in. Such a var becomes a box
 * and every read and write goes through its value, so the stage and lambda see one value,
 * which the story saves once. Runs before the lambdas are lifted, which then capture the box.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageVarBoxes(private val stages: StageScope) {
    private val symbols = stages.symbols

    /** A `@State` lambda found in a stage and the function it is passed to. */
    private class Lambda(val function: IrSimpleFunction, val callee: IrFunction)

    fun compile() {
        val lambdas = ArrayList<Lambda>()
        stages.script.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                val callee = expression.symbol.owner
                callee.parameters.forEach { parameter ->
                    if (!stages.types.isStageLambda(parameter.type)) return@forEach
                    val lambda = expression.arguments[parameter] as? IrFunctionExpression ?: return@forEach
                    lambdas += Lambda(lambda.function, callee)
                }
            }
        })
        val owners = stages.script.declarations.filterIsInstance<IrSimpleFunction>()
            .filter(stages::isSuspendState) + lambdas.map { it.function }
        owners.forEach { owner -> box(owner, lambdas.filter { it.function !== owner && it.function.isInside(owner) }) }
    }

    private fun box(owner: IrSimpleFunction, lambdas: List<Lambda>) {
        val shared = LinkedHashMap<IrVariable, Lambda>()
        val locals = StageLocalFunctions(stages)
        lambdas.forEach { lambda ->
            val inside = declaredIn(lambda.function)
            val reached = listOf(lambda.function) + locals.reachableFrom(lambda.function)
            reached.forEach { function ->
                function.acceptChildrenVoid(object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

                    override fun visitGetValue(expression: IrGetValue) = share(expression.symbol.owner)

                    override fun visitSetValue(expression: IrSetValue) {
                        super.visitSetValue(expression)
                        share(expression.symbol.owner)
                    }

                    private fun share(value: Any) {
                        if (value is IrVariable && value.isVar && value !in inside && value.parent === owner) {
                            shared.putIfAbsent(value, lambda)
                        }
                    }
                })
            }
        }
        val boxes = shared.filter { (variable, lambda) -> canShare(variable, lambda) }.keys.associateWith(::boxOf)
        if (boxes.isEmpty()) return
        owner.body?.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitVariable(declaration: IrVariable): IrStatement {
                declaration.transformChildrenVoid(this)
                val box = boxes[declaration] ?: return declaration
                box.initializer = stages.builder(owner.symbol, declaration).run {
                    with(stages) {
                        irCall(symbols.box, box.type).apply {
                            typeArguments[0] = declaration.type
                            arguments[0] = irGetObject(symbols.boxes)
                            arguments[1] = irString(declaration.name.asString())
                            arguments[2] = serializer(declaration.type)
                            arguments[3] = declaration.initializer
                        }
                    }
                }
                return box
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val box = boxes[expression.symbol.owner] ?: return expression
                return stages.builder(owner.symbol, expression).run {
                    irCall(symbols.boxGetter, expression.type).apply { arguments[0] = irGet(box) }
                }
            }

            override fun visitSetValue(expression: IrSetValue): IrExpression {
                expression.transformChildrenVoid(this)
                val box = boxes[expression.symbol.owner] ?: return expression
                return stages.builder(owner.symbol, expression).run {
                    irCall(symbols.boxSetter, expression.type).apply {
                        arguments[0] = irGet(box)
                        arguments[1] = expression.value
                    }
                }
            }
        })
    }

    private fun canShare(variable: IrVariable, lambda: Lambda): Boolean {
        val problem = when {
            variable.initializer == null -> "give it a value where it is declared"
            isNewStory(lambda.callee) -> "a story started here keeps copies of what it captures; pass the value instead"
            else -> stages.types.problem(variable.type)?.let { "its value is saved, but $it" }
        } ?: return true
        stages.reporter.error(variable, StageMessages.sharedVar(variable.name.asString(), problem))
        return false
    }

    /** `launchStory` and `StoryRuns.launch` start a story of their own, which cannot share the vars of this one. */
    private fun isNewStory(callee: IrFunction): Boolean {
        val name = callee.name.asString()
        if (name != "launchStory" && name != "launch") return false
        val owner = (callee.parent as? IrClass)?.kotlinFqName ?: callee.kotlinFqName.parent()
        return owner.asString().startsWith(StageSymbols.PACKAGE.asString())
    }

    private fun boxOf(variable: IrVariable): IrVariable = IrVariableImpl(
        variable.startOffset,
        variable.endOffset,
        IrDeclarationOrigin.DEFINED,
        IrVariableSymbolImpl(),
        Name.identifier($$"$${variable.name.asString()}$box"),
        symbols.storyBox.typeWith(variable.type),
        isVar = false,
        isConst = false,
        isLateinit = false,
    ).also { it.parent = variable.parent }
}
