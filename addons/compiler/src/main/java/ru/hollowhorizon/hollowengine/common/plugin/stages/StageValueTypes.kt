package ru.hollowhorizon.hollowengine.common.plugin.stages

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Value categories shared by FIR validation and IR persistence analysis. */
internal object StageValueTypes {
    val SERIALIZABLE = FqName("kotlinx.serialization.Serializable")
    val SERIALIZABLE_ID = ClassId.topLevel(SERIALIZABLE)
    val STAGE_LAMBDA = FqName("ru.hollowhorizon.hollowengine.addons.narrative.story").child(Name.identifier("StageLambda")).asString()

    /** The box a shared var becomes; it is saved as its value. */
    val STORY_BOX = FqName("ru.hollowhorizon.hollowengine.addons.narrative.story").child(Name.identifier("StoryBox")).asString()

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
