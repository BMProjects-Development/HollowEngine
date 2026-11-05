package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.isAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Compiles the `@Action` functions of a script. The journal calls an action again to undo it, so it saves the
 * receivers and parameters of every call: they must be serializable, and `action { }` or `label { }` gets them
 * passed here, which a script cannot get wrong.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageActions(private val stages: StageScope) {
    private val reporter = stages.reporter

    fun compile() {
        stages.script.acceptChildrenVoid(object : IrVisitorVoid() {
            private val functions = ArrayDeque<IrFunction>()
            private val bodies = HashMap<IrFunction, Int>()

            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitFunction(declaration: IrFunction) {
                val action = isAction(declaration)
                if (action) check(declaration as IrSimpleFunction)
                functions.addLast(declaration)
                super.visitFunction(declaration)
                functions.removeLast()
                if (action && bodies[declaration] == null) reporter.error(
                    declaration,
                    "The body of an @Action is action { } or label { }, which the journal calls it again through"
                )
            }

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                if (!stages.symbols.isActionBody(expression.symbol.owner)) return
                val function = functions.lastOrNull()
                if (function == null || !isAction(function)) {
                    reporter.error(
                        expression,
                        "action { } and label { } must be the body of an @Action function, written directly in it"
                    )
                    return
                }
                bodies.merge(function, 1, Int::plus)
                pass(function, expression)
            }
        })
    }

    /** Passes [call] every parameter of [function], receivers first, unless the script passed some itself. */
    private fun pass(function: IrFunction, call: IrCall) {
        val given = call.arguments[0] as? IrVararg
        if (given != null && given.elements.isNotEmpty()) {
            reporter.error(
                call,
                "The compiler passes the receivers and parameters of the action to action { }; leave its parentheses empty"
            )
            return
        }
        val builder = stages.builder(function.symbol, call)
        val elementType = stages.context.irBuiltIns.anyNType
        call.arguments[0] = builder.irVararg(elementType, function.parameters.map { builder.irGet(it) })
    }

    private fun check(function: IrSimpleFunction) {
        val owner = function.parent
        val problem = when {
            !function.isSuspend -> "An action must be a suspend function"
            owner != stages.script && (owner as? IrClass)?.kind != ClassKind.OBJECT -> "An action must be declared at the top level of a script or in an object"

            function.typeParameters.isNotEmpty() -> "An action cannot have type parameters: the journal saves its arguments by their declared types"
            function.parameters.any { it.kind == IrParameterKind.Context } -> "An action cannot take context parameters"
            else -> null
        }
        problem?.let { reporter.error(function, it); return }
        function.parameters.filter { it.kind == IrParameterKind.ExtensionReceiver || it.kind == IrParameterKind.Regular }
            .filter { it.type.classOrNull != stages.symbols.checkpointScope }.forEach { parameter ->
                val name = stages.argumentName(function, parameter)
                if (parameter.annotations.any { it.isAnnotation(StageSymbols.UNSAVED) }) {
                    if (parameter.defaultValue == null) reporter.error(
                        parameter,
                        "The journal calls the action with the default of the unsaved '$name', so it needs one"
                    )
                    return@forEach
                }
                stages.types.problem(parameter.type)?.let {
                    reporter.error(
                        parameter,
                        "The journal saves the arguments of an action, but '$name' cannot be saved: $it; mark it @Unsaved if undoing does not need it"
                    )
                }
            }
    }

    private fun isAction(function: IrFunction): Boolean =
        function.annotations.any { it.isAnnotation(StageSymbols.ACTION) }
}
