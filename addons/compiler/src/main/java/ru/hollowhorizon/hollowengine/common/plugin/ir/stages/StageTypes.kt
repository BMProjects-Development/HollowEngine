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
import org.jetbrains.kotlin.name.Name

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
        if (name in BASIC) return null
        if (isFunction(type)) {
            return "a function value cannot be saved; pass a lambda to a @State parameter instead"
        }
        if (klass.isPrimitiveArray()) return null
        if (name == "kotlin.Array" || name in CONTAINERS || klass.isSerializable() || name in CONTEXTUAL) {
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
        type.classOrNull?.owner?.kotlinFqName?.asString().orEmpty().startsWith(STAGE_LAMBDA)

    private fun IrClass.isSerializable(): Boolean = kind == ClassKind.ENUM_CLASS || hasAnnotation(SERIALIZABLE)

    private fun IrClass.isPrimitiveArray(): Boolean = kotlinFqName.asString() in PRIMITIVE_ARRAYS

    private companion object {
        val SERIALIZABLE = FqName("kotlinx.serialization.Serializable")
        val STAGE_LAMBDA = StageSymbols.PACKAGE.child(Name.identifier("StageLambda")).asString()

        val PRIMITIVE_ARRAYS = setOf(
            "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray", "kotlin.ByteArray", "kotlin.FloatArray",
            "kotlin.DoubleArray", "kotlin.BooleanArray", "kotlin.CharArray",
        )

        val BASIC = setOf(
            "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Float", "kotlin.Double",
            "kotlin.Boolean", "kotlin.Char", "kotlin.String", "kotlin.Unit",
        )

        val CONTAINERS = setOf(
            "kotlin.collections.List", "kotlin.collections.MutableList", "kotlin.collections.Collection",
            "kotlin.collections.Set", "kotlin.collections.MutableSet",
            "kotlin.collections.Map", "kotlin.collections.MutableMap",
            "kotlin.collections.Map.Entry", "kotlin.collections.MutableMap.MutableEntry",
            "kotlin.Pair", "kotlin.Triple",
        )

        /** Classes the engine's NBT format writes without them being `@Serializable`. */
        val CONTEXTUAL = setOf(
            "java.util.UUID",
            "net.minecraft.core.BlockPos",
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.world.phys.Vec3",
            "net.minecraft.network.chat.Component",
            "net.minecraft.world.item.ItemStack",
            "org.joml.Matrix4f",
            "org.joml.Vector3d",
            "org.joml.Vector3f",
            "ru.hollowhorizon.hollowengine.common.npcs.registry.NpcRef",
        )
    }
}
