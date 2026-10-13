package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.api.Init
import ru.hollowhorizon.hollowengine.common.entities.objects.ObjectPose
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3d
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.rotateByEulers
import ru.hollowhorizon.hollowengine.common.utils.math.toEulers
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.mutableAttributes
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntityAndSelf
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

/** Where a world object is, relative to its parent or to the world. Rotation is in degrees. */
@Serializable
@SerialName("hollowengine:editor/object")
@EditorIcon("hollowengine:textures/gui/icons/box.svg")
data class ObjectPlacement(
    @EditorDescription("hollowengine.component.hollowengine.editor.object.position.hint")
    val position: Vec3d = Vec3d.ZERO,
    val rotation: Vec3f = Vec3f.ZERO,
    val scale: Vec3f = Vec3f.ONES,
)

@Serializable
@SerialName("hollowengine:editor/attributes")
@EditorIcon("hollowengine:textures/gui/icons/pulse.svg")
data class EntityAttributes(
    val health: Float = 20f,
    val attributes: Map<String, Double> = emptyMap(),
    @EditorHidden val defaults: Map<String, Double> = emptyMap(),
    @EditorHidden val removable: Set<String> = emptySet(),
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
            supports = { it !is WorldObjectEntity },
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
            id = "hollowengine:editor/object".rl,
            type = ObjectPlacement::class,
            serializer = ObjectPlacement.serializer(),
            supports = { it is WorldObjectEntity },
            read = { entity -> (entity as? WorldObjectEntity)?.localPose?.let(::objectPlacement) },
            write = { entity, placement -> (entity as? WorldObjectEntity)?.setLocalPose(objectPose(placement)) },
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

    private fun objectPlacement(pose: ObjectPose): ObjectPlacement {
        val rotation = pose.rotation.let { QuatF(it.x, it.y, it.z, it.w) }.toEulers()
        return ObjectPlacement(
            position = pose.position.let { Vec3d(it.x, it.y, it.z) },
            rotation = Vec3f(rotation),
            scale = pose.scale.let { Vec3f(it.x, it.y, it.z) },
        )
    }

    private fun objectPose(placement: ObjectPlacement): ObjectPose {
        val rotation = MutableQuatF().setIdentity().rotateByEulers(placement.rotation)
        return ObjectPose(
            position = placement.position.let { Vector3d(it.x, it.y, it.z) },
            rotation = Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w),
            scale = placement.scale.let { Vector3f(it.x, it.y, it.z) },
        )
    }

    private fun readAttributes(entity: LivingEntity): EntityAttributes {
        val attributes = entity.attributes.mutableAttributes
        return EntityAttributes(
            health = entity.health,
            attributes = EntityAttributeEditing.read(attributes),
            defaults = EntityAttributeEditing.defaults(attributes),
            removable = attributes.additionalAttributes().mapTo(linkedSetOf()) { it.unwrapKey().orElseThrow().location().toString() },
        )
    }

    private fun writeAttributes(entity: LivingEntity, values: EntityAttributes) {
        val removed = EntityAttributeEditing.replace(entity.attributes.mutableAttributes, values.attributes)
        if (removed.isNotEmpty()) RemovedEntityAttributesPacket(entity.id, removed).sendTrackingEntityAndSelf(entity)
        if (values.health.isFinite()) entity.health = values.health.coerceIn(0f, entity.maxHealth)
    }
}
