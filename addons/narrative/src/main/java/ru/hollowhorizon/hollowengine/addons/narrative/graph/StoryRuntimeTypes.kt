package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Literal adapters for classes already known to the engine. This never loads or resolves a class by name. */
object StoryRuntimeTypes {
    private val builtins = listOf(
        typeOf<String>(), typeOf<CharSequence>(), typeOf<Boolean>(), typeOf<Byte>(), typeOf<Short>(),
        typeOf<Int>(), typeOf<Long>(), typeOf<Float>(), typeOf<Double>(), typeOf<Number>(), typeOf<Any>(),
        typeOf<Vec3>(), typeOf<BlockPos>(), typeOf<ResourceLocation>(), typeOf<ServerLevel>(),
    )

    fun named(name: String?): KType? {
        if (name == null) return null
        val types = builtins + StoryPinType.all.mapNotNull { it.type }
        return types.firstOrNull { type ->
            val full = StoryKotlinTypes.name(type)
            name == full || name == "$full?" ||
                type in builtins && (name == type.toString().substringAfterLast('.') ||
                    name == type.toString().substringAfterLast('.') + "?")
        }
    }
}
