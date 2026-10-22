package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.Name

/** A saved lambda refers to this generated factory to recover its result serializer after a restart. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun StageScope.lambdaResult(function: IrSimpleFunction, name: String): IrSimpleFunction {
    val factory = context.irFactory.buildFun {
        startOffset = function.startOffset
        endOffset = function.endOffset
        this.name = Name.identifier("stage\$result\$${function.name.asString()}")
        returnType = symbols.serializerType
        visibility = DescriptorVisibilities.PUBLIC
    }
    factory.parent = script
    factory.parameters = listOf(factory.buildReceiverParameter { type = script.defaultType })
    factory.annotations = listOf(resultAnnotation(name))
    factory.body = builder(factory.symbol, function).irBlockBody {
        +irReturn(serializer(function.returnType))
    }
    return factory
}
