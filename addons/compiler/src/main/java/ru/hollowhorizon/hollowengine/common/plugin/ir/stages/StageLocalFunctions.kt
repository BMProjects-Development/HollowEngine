package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Lets a `@State` lambda call the local functions of the code it is written in. A lifted lambda leaves that code,
 * so every such function gets a copy at the top level of the script, with what it used from around it as explicit
 * parameters; the lambda captures those values, which makes them saved like any capture. The original stays for
 * its other callers.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageLocalFunctions(private val stages: StageScope) {
    private class Copy(val function: IrSimpleFunction, val free: List<IrValueDeclaration>)

    private val copies = HashMap<IrSimpleFunction, Copy>()

    /** The copies made, which belong to the script. */
    val lifted = ArrayList<IrSimpleFunction>()

    /** The local functions declared outside [root] that it calls, directly or through one another. */
    fun reachableFrom(root: IrFunction): Set<IrSimpleFunction> {
        val found = LinkedHashSet<IrSimpleFunction>()
        val queue = ArrayDeque(calledOutside(root))
        while (queue.isNotEmpty()) {
            val function = queue.removeFirst()
            if (found.add(function)) queue += calledOutside(function)
        }
        return found
    }

    /** What [function] and the local functions it reaches read or write from around them, in a stable order. */
    fun freeValues(function: IrSimpleFunction): List<IrValueDeclaration> =
        (setOf(function) + reachableFrom(function)).flatMap(::directFree).distinct()

    /**
     * Points the calls in [root] to local functions declared outside it at their copies. [dispatch] is the script
     * instance there, and [valueOf] gives what to pass for each free value of a callee.
     */
    fun redirect(
        root: IrFunction,
        dispatch: IrValueDeclaration,
        valueOf: (IrValueDeclaration) -> IrValueDeclaration = { it },
    ) {
        root.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                val callee = expression.symbol.owner
                if (!callee.isLocal() || callee.isInside(root)) return expression
                val copy = copyOf(callee)
                return stages.builder(root.symbol, expression).run {
                    callCopy(copy, callee, expression, irGet(dispatch), copy.free.map { irGet(valueOf(it)) })
                }
            }
        })
    }

    private fun copyOf(function: IrSimpleFunction): Copy {
        copies[function]?.let { return it }
        val free = freeValues(function)
        val script = stages.script
        val copy = function.deepCopyWithSymbols(script)
        copy.attributeOwnerId = copy
        copy.name = Name.identifier($$"stage$local$$${function.name.asString()}$$${lifted.size}")
        copy.visibility = DescriptorVisibilities.PRIVATE
        copy.origin = IrDeclarationOrigin.DEFINED
        copy.parent = script
        val dispatch = copy.buildReceiverParameter { type = script.defaultType }
        val own = copy.parameters.toList()
        val names = own.map { it.name.asString() }.toMutableSet()
        val freeParameters = free.map { value ->
            val base = value.name.asString().takeIf { !value.name.isSpecial } ?: "captured"
            copy.addValueParameter(generateSequence(base) { "${it}_" }.first(names::add), value.type)
        }
        copy.parameters =
            listOf(dispatch) + own.filter { it.kind == IrParameterKind.ExtensionReceiver } + freeParameters + own.filter { it.kind == IrParameterKind.Regular }
        val result = Copy(copy, free)
        copies[function] = result
        lifted += copy

        val parameterOf = free.zip(freeParameters).toMap()
        copy.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val value = expression.symbol.owner
                val replacement: IrValueParameter =
                    parameterOf[value] ?: dispatch.takeIf { stages.isScriptThis(value) && value !in declaredIn(copy) }
                    ?: return expression
                return IrGetValueImpl(
                    expression.startOffset,
                    expression.endOffset,
                    replacement.type,
                    replacement.symbol,
                    expression.origin
                )
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                if (expression.symbol != copy.symbol) return expression
                return stages.builder(copy.symbol, expression).run {
                    callCopy(result, function, expression, irGet(dispatch), freeParameters.map { irGet(it) })
                }
            }
        })
        redirect(copy, dispatch) { value -> parameterOf[value] ?: value }
        copy.patchDeclarationParents(script)
        return result
    }

    /** A call of [copy] in place of [call] of [original]: the script, its receiver, the free values, its arguments. */
    private fun IrBuilderWithScope.callCopy(
        copy: Copy,
        original: IrSimpleFunction,
        call: IrCall,
        dispatch: IrExpression,
        free: List<IrExpression>,
    ): IrCall = irCall(copy.function.symbol, call.type).apply {
        var next = 0
        arguments[next++] = dispatch
        original.parameters.forEachIndexed { index, parameter ->
            if (parameter.kind == IrParameterKind.ExtensionReceiver) arguments[next++] = call.arguments[index]
        }
        free.forEach { arguments[next++] = it }
        original.parameters.forEachIndexed { index, parameter ->
            if (parameter.kind == IrParameterKind.Regular) arguments[next++] = call.arguments[index]
        }
    }

    private fun calledOutside(root: IrFunction): Set<IrSimpleFunction> {
        val found = LinkedHashSet<IrSimpleFunction>()
        root.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                val callee = expression.symbol.owner
                if (callee.isLocal() && !callee.isInside(root) && callee !== root) found += callee
            }
        })
        return found
    }

    private fun directFree(function: IrSimpleFunction): List<IrValueDeclaration> {
        val inside = declaredIn(function)
        val free = LinkedHashSet<IrValueDeclaration>()
        function.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

            override fun visitGetValue(expression: IrGetValue) {
                val value = expression.symbol.owner
                if (value !in inside && !stages.isScriptThis(value)) free += value
            }

            override fun visitSetValue(expression: IrSetValue) {
                super.visitSetValue(expression)
                if (expression.symbol.owner !in inside) free += expression.symbol.owner
            }
        })
        return free.toList()
    }
}

/** Whether this is a function declared inside another one. */
internal fun IrFunction.isLocal(): Boolean = parent is IrFunction || visibility == DescriptorVisibilities.LOCAL

/** Whether this is declared inside [owner], at any depth. */
internal fun IrDeclaration.isInside(owner: IrDeclarationParent): Boolean =
    generateSequence(parent) { (it as? IrDeclaration)?.parent }.any { it === owner }
