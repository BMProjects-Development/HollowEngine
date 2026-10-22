package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.isAnnotation
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.name.Name

/** What the stage passes over one script class share: symbols, error reporting, the script itself. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageScope(
    val context: IrPluginContext,
    val symbols: StageSymbols,
    val types: StageTypes,
    val reporter: StageReporter,
    val script: IrClass,
) {
    fun builder(symbol: IrSymbol, at: IrElement): DeclarationIrBuilder =
        DeclarationIrBuilder(context, symbol, at.startOffset, at.endOffset)

    /** Whether [function] is a stage: a suspend function marked `@State`. */
    fun isSuspendState(function: IrFunction): Boolean = function.isSuspend && stateAnnotation(function) != null

    /** The name saves know a stage by. */
    fun stageName(function: IrFunction): String {
        val explicit = stateAnnotation(function)?.getValueArgument(Name.identifier("name")) as? IrConst
        return (explicit?.value as? String)?.takeIf(String::isNotBlank) ?: function.name.asString()
    }

    /** What [call] is from the point of view of the stage it is in, or null when it is an ordinary call. */
    fun siteKind(call: IrCall): SiteKind? {
        val callee = call.symbol.owner
        return when {
            symbols.isCheckpoint(callee) -> SiteKind.CHECKPOINT
            isSuspendState(callee) -> SiteKind.STAGE
            callee.name.asString() == "invoke" && call.dispatchReceiver?.type?.let(types::isStageLambda) == true -> SiteKind.LAMBDA
            else -> null
        }
    }

    /** The parameters a stage saves as its arguments: all of them but the script it belongs to. */
    fun arguments(function: IrFunction): List<IrValueParameter> =
        function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }

    /** The name [parameter] of [function] is saved under: its own name, `this` for a receiver. */
    fun argumentName(function: IrFunction, parameter: IrValueParameter): String = when {
        parameter.kind == IrParameterKind.ExtensionReceiver -> "this"
        parameter.name.isSpecial -> "argument${function.parameters.indexOf(parameter)}"
        else -> parameter.name.asString()
    }

    /** Whether [value] is the script instance itself. */
    fun isScriptThis(value: IrValueDeclaration): Boolean =
        value is IrValueParameter && value.type.classOrNull == script.symbol

    /** The serializer of [type]; function types are kept as the stage lambdas they must be. */
    fun IrBuilderWithScope.serializer(type: IrType): IrExpression = irCall(symbols.storySerializer).apply {
        typeArguments[0] = if (types.isStageLambda(type) || types.isFunction(type)) symbols.stageLambda.owner.defaultType else type
    }

    /** Calls [function] with [arguments] for all its parameters in order, receivers first. */
    fun IrBuilderWithScope.call(function: IrSimpleFunctionSymbol, vararg arguments: IrExpression?): IrCall =
        irCall(function).apply { arguments.forEachIndexed { index, argument -> this.arguments[index] = argument } }

    /** Calls a member of an object such as `StoryValues`. */
    fun IrBuilderWithScope.callObject(function: IrSimpleFunctionSymbol, vararg arguments: IrExpression?): IrCall {
        val owner = function.owner.parent as IrClass
        return call(function, irGetObject(owner.symbol), *arguments)
    }

    fun stateAnnotation(name: String): IrAnnotation = annotation(symbols.stateConstructor, name)

    fun entryAnnotation(name: String): IrAnnotation = annotation(symbols.stageEntry, name)

    fun callAnnotation(name: String): IrAnnotation = annotation(symbols.stageCall, name)

    fun scriptAnnotation(path: String): IrAnnotation = annotation(symbols.stageScript, path)

    private fun annotation(constructor: IrConstructorSymbol, value: String): IrAnnotation =
        builder(constructor, script).run { irAnnotation(constructor).apply { arguments[0] = irString(value) } }

    private fun stateAnnotation(function: IrFunction): IrConstructorCall? =
        function.annotations.firstOrNull { it.isAnnotation(StageSymbols.STATE) }
}

internal enum class SiteKind { STAGE, LAMBDA, CHECKPOINT }
