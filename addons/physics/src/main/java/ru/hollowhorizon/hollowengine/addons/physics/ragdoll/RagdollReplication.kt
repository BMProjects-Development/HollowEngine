package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.network.HollowAddonPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import ru.hollowhorizon.hollowengine.common.network.sendTrackingEntity
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** Where the bodies of an entity's ragdoll are on server tick [tick], sent every tick they move. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_CLIENT)
@Serializable
class RagdollPosePacket(
    val entityId: Int,
    val tick: Long,
    val originX: Double,
    val originY: Double,
    val originZ: Double,
    val nodes: IntArray,
    val values: FloatArray,
) : HollowAddonPacket {
    override fun handle(player: Player) =
        RagdollReplicas.receive(entityId, tick, RagdollSnapshot(originX, originY, originZ, nodes, values), RagdollReplicas.clientNow())
}

/** What the server does with a ragdoll it simulates, once a tick. */
internal object RagdollReplication {
    /** How often a ragdoll at rest is sent anyway, for players who start seeing it only now. */
    private const val KEYFRAME_TICKS = 20L

    /** Sends where the bodies of [entity]'s ragdoll are to everyone who sees it, while they move. */
    fun publish(entity: Entity, instance: RagdollInstance, fresh: Boolean) {
        val keyframe = (entity.level().gameTime + entity.id) % KEYFRAME_TICKS == 0L
        if (!fresh && !keyframe && !instance.isMoving) return
        val snapshot = instance.snapshot() ?: return
        RagdollPosePacket(entity.id, entity.level().gameTime, snapshot.originX, snapshot.originY, snapshot.originZ, snapshot.nodes, snapshot.values)
            .sendTrackingEntity(entity)
    }

    fun anchor(entity: Entity, instance: RagdollInstance): Vec3 {
        if (entity is Player) return Vec3.ZERO
        val place = instance.restingPlace() ?: return Vec3.ZERO
        val target = Vec3(place.x, place.floor, place.z)
        val moved = target.subtract(entity.position())
        entity.setPos(target)
        entity.deltaMovement = Vec3.ZERO
        if (entity is Mob) {
            entity.navigation.stop()
            entity.xxa = 0f
            entity.zza = 0f
        }
        return moved
    }

    fun impulseOf(entity: Entity): Vec3f? {
        val motion = entity.deltaMovement
        val impulse = Vec3f(motion.x.toFloat(), maxOf(motion.y, 0.0).toFloat(), motion.z.toFloat()) * TICKS_PER_SECOND
        return impulse.takeIf { it.length() > MIN_IMPULSE }
    }

    private const val TICKS_PER_SECOND = 20f

    /** Slower than this, in blocks a second, is what is left of the entity's own steps, not a push. */
    private const val MIN_IMPULSE = 0.05f
}
