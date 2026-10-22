package ru.hollowhorizon.hollowengine.common.plugin.ir.stages

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import ru.hollowhorizon.hollowengine.common.plugin.stages.StageValueTypes

/**
 * Says which values a story can keep. It mirrors what `StoryValues` writes at runtime: whatever kotlinx
 * serialization or the engine's NBT format can write, entities as references, and stage lambdas.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class StageTypes(context: IrPluginContext) {
    private val finder = context.finderForBuiltins()
    private val entity: IrClassSymbol? = finder.findClass(ClassId.topLevel(FqName("net.minecraft.world.entity.Entity")))
    private val tag: IrClassSymbol? = finder.findClass(ClassId.topLevel(FqName("net.minecraft.nbt.Tag")))

    /** Why a story cannot keep a value of [type], or null when it can. */
    fun problem(type: IrType): String? {
        if (isStageLambda(type)) return null
        val simple = type as? IrSimpleType ?: return "${type.render()} cannot be saved"
        val klass = simple.classOrNull?.owner ?: return "${type.render()} is a type parameter, which cannot be saved"
        if (entity != null && type.isSubtypeOfClass(entity)) return null
        if (tag != null && type.isSubtypeOfClass(tag)) return null
        val name = klass.kotlinFqName.asString()
        if (name in StageValueTypes.BASIC) return null
        if (isFunction(type)) {
            return "a function value cannot be saved; pass a lambda to a @State parameter instead"
        }
        if (klass.isPrimitiveArray()) return null
        if (name == "kotlin.Array" || name == StageValueTypes.STORY_BOX || name in StageValueTypes.CONTAINERS || klass.isSerializable() || name in StageValueTypes.CONTEXTUAL) {
            return simple.arguments.firstNotNullOfOrNull { argument ->
                (argument as? IrTypeProjection)?.type?.let(::problem)
                    ?: if (argument is IrTypeProjection) null else "a star projection cannot be saved"
            }
        }
        return "${type.render()} cannot be saved: it is not @Serializable"
    }

    /** Whether [type] is a function type; a story keeps such values only as stage lambdas. */
    fun isFunction(type: IrType): Boolean = type.classOrNull?.owner?.kotlinFqName?.asString().orEmpty().let {
        it.startsWith("kotlin.Function") || it.startsWith("kotlin.coroutines.SuspendFunction") || it.startsWith("kotlin.reflect.KFunction")
    }

    /** Whether [type] is a function type marked `@State`, whose values are stage lambdas. */
    fun isStageLambda(type: IrType): Boolean = type.annotations.any { it.isAnnotation(StageSymbols.STATE) } ||
        type.classOrNull?.owner?.kotlinFqName?.asString().orEmpty().startsWith(StageValueTypes.STAGE_LAMBDA)

    private fun IrClass.isSerializable(): Boolean = kind == ClassKind.ENUM_CLASS || hasAnnotation(StageValueTypes.SERIALIZABLE)

    private fun IrClass.isPrimitiveArray(): Boolean = kotlinFqName.asString() in StageValueTypes.PRIMITIVE_ARRAYS
}
