package ru.hollowhorizon.hollowengine.common.attachments.components.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForResourceLocation
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForUuid
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForVec3
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.*

private val EMPTY_UUID: UUID = UUID(0L, 0L)

@Serializable
data class EntityReference(

    val uuid: @Serializable(ForUuid::class) UUID = EMPTY_UUID,

    val level: @Serializable(ForResourceLocation::class) ResourceLocation = "minecraft:overworld".rl,
) {
    fun isEmpty(): Boolean = uuid == EMPTY_UUID

    fun resolve(origin: Level): Entity? {
        if (isEmpty()) return null
        val targetLevel: ServerLevel = if (origin.dimension().location() == level) {
            origin as? ServerLevel ?: return null
        } else {
            origin.server?.getLevel(ResourceKey.create(Registries.DIMENSION, level)) ?: return null
        }
        return targetLevel.getEntity(uuid)
    }
}

enum class LookTargetMode {
    ENTITY,
    POSITION,
}

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:look_at_target")
data class LookAtTargetComponent(

    val enabled: Boolean = true,

    val targetMode: LookTargetMode = LookTargetMode.ENTITY,

    val targetEntity: EntityReference = EntityReference(),

    val targetPosition: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO,

    val yawSpeed: Float = 10f,

    val pitchSpeed: Float = 10f,
)

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:follow_target")
data class FollowTargetComponent(

    val enabled: Boolean = true,

    val targetEntity: EntityReference = EntityReference(),

    val speed: Float = 1f,

    val preferredDistance: Float = 2f,

    val maxDistance: Float = 24f,
)

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:move_to_position")
data class MoveToPositionComponent(

    val enabled: Boolean = true,

    val target: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO,

    val speed: Float = 1f,

    val arrivalRadius: Float = 1.5f,

    val stopOnArrival: Boolean = true,
)

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:attack_target")
data class AttackTargetComponent(

    val enabled: Boolean = true,

    val targetEntity: EntityReference = EntityReference(),

    val attackRange: Float = 2f,

    val chaseRange: Float = 16f,

    val forgetDistance: Float = 32f,

    val cooldownTicks: Int = 20,
)

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:pickup_loot")
data class PickupLootComponent(

    val enabled: Boolean = true,

    val radius: Float = 6f,

    val scanIntervalTicks: Int = 10,
)

@Serializable
data class PatrolPoint(

    val position: @Serializable(ForVec3::class) Vec3 = Vec3.ZERO,

    val waitTicks: Int = 0,

    val lookAtNextPoint: Boolean = true,
)

@Registerable
@Attach(Entity::class)
@Serializable
@SerialName("hollowengine:patrol_path")
data class PatrolPathComponent(

    val enabled: Boolean = true,

    val points: List<PatrolPoint> = emptyList(),

    val loop: Boolean = true,

    val speed: Float = 1f,

    val arrivalRadius: Float = 1.5f,
)
