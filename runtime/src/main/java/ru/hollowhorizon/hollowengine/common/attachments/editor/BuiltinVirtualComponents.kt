package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.api.Init
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.rl

@Serializable
@SerialName("hollowengine:editor/entity")
@EditorIcon("hollowengine:textures/gui/icons/general.svg")
data class EntityInfo(
    val name: String = "",
    val nameVisible: Boolean = false,
    val invulnerable: Boolean = false,
    val silent: Boolean = false,
    val noGravity: Boolean = false,
    val glowing: Boolean = false,
)

@Serializable
@SerialName("hollowengine:editor/placement")
@EditorIcon("hollowengine:textures/gui/icons/world.svg")
data class EntityPlacement(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    @EditorRange(-180.0, 180.0, slider = true) val yaw: Float = 0f,
    @EditorRange(-90.0, 90.0, slider = true) val pitch: Float = 0f,
)


@Serializable
@SerialName("hollowengine:editor/attributes")
@EditorIcon("hollowengine:textures/gui/icons/pulse.svg")
data class EntityAttributes(
    val health: Float = 20f,
    val attributes: Map<String, Double> = emptyMap(),
)


object BuiltinVirtualComponents {
    @Init
    fun register() {
        VirtualComponentRegistry.register(
            id = "hollowengine:editor/entity".rl,
            type = EntityInfo::class,
            serializer = EntityInfo.serializer(),
            read = { entity ->
                EntityInfo(
                    name = entity.customName?.string.orEmpty(),
                    nameVisible = entity.isCustomNameVisible,
                    invulnerable = entity.isInvulnerable,
                    silent = entity.isSilent,
                    noGravity = entity.isNoGravity,
                    glowing = entity.hasGlowingTag(),
                )
            },
            write = { entity, info ->
                val name = info.name.ifBlank { if (info.nameVisible) entity.name.string else "" }
                entity.customName = name.takeIf { it.isNotBlank() }?.literal
                entity.isCustomNameVisible = info.nameVisible
                entity.isInvulnerable = info.invulnerable
                entity.isSilent = info.silent
                entity.isNoGravity = info.noGravity
                entity.setGlowingTag(info.glowing)
            },
        )

        VirtualComponentRegistry.register(
            id = "hollowengine:editor/placement".rl,
            type = EntityPlacement::class,
            serializer = EntityPlacement.serializer(),
            read = { entity ->
                EntityPlacement(entity.x, entity.y, entity.z, entity.yRot, entity.xRot)
            },
            write = { entity, placement ->
                entity.teleportTo(placement.x, placement.y, placement.z)
                entity.yRot = placement.yaw
                entity.xRot = placement.pitch
                entity.yHeadRot = placement.yaw
                val living = entity as? LivingEntity
                if (living != null) living.yBodyRot = placement.yaw
            },
        )

        VirtualComponentRegistry.register(
            id = "hollowengine:editor/attributes".rl,
            type = EntityAttributes::class,
            serializer = EntityAttributes.serializer(),
            supports = { it is LivingEntity },
            read = { entity -> (entity as? LivingEntity)?.let(::readAttributes) },
            write = { entity, values -> (entity as? LivingEntity)?.let { writeAttributes(it, values) } },
        )
    }

    private fun readAttributes(entity: LivingEntity): EntityAttributes = EntityAttributes(
        health = entity.health,
        attributes = BuiltInRegistries.ATTRIBUTE.entrySet().mapNotNull { (key, _) ->
                val holder = BuiltInRegistries.ATTRIBUTE.getHolder(key).orElse(null) ?: return@mapNotNull null
                if (!entity.attributes.hasAttribute(holder)) return@mapNotNull null
                key.location().toString() to entity.getAttributeBaseValue(holder)
            }.sortedBy { it.first }.toMap(),
    )

    private fun writeAttributes(entity: LivingEntity, values: EntityAttributes) {
        values.attributes.forEach { (id, value) ->
            val key = ResourceKey.create(Registries.ATTRIBUTE, runCatching { id.rl }.getOrNull() ?: return@forEach)
            val holder = BuiltInRegistries.ATTRIBUTE.getHolder(key).orElse(null) ?: return@forEach
            val instance = entity.getAttribute(holder) ?: return@forEach
            instance.baseValue = value
        }
        entity.health = values.health.coerceIn(0f, entity.maxHealth)
    }
}
