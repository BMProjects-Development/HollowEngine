package ru.hollowhorizon.hollowengine.common.entities

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.BodyComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.BodyMode
import ru.hollowhorizon.hollowengine.common.attachments.components.bodyComponent
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

/**
 * What [BodyComponent] changes about vanilla: each call takes what vanilla would answer and returns what
 * the entity's body makes of it. An entity without the component keeps vanilla's answer.
 */
object EntityBodies {
    /** The most one tick of walking into a blocking body passes on to it. */
    private const val MAX_SHOVE = 0.5

    fun isPushable(entity: Entity, vanilla: Boolean): Boolean {
        val body = entity.bodyComponent ?: return vanilla
        return vanilla && body.mode == BodyMode.PUSHING && body.pushable
    }

    fun pushesOthers(entity: Entity): Boolean = entity.bodyComponent?.mode?.let { it == BodyMode.PUSHING } ?: true

    fun isSolid(entity: Entity, vanilla: Boolean): Boolean =
        vanilla || entity.bodyComponent?.mode == BodyMode.BLOCKING && entity.isAlive

    fun dimensions(entity: Entity, vanilla: EntityDimensions): EntityDimensions =
        entity.bodyComponent?.resize(vanilla) ?: vanilla

    /**
     * After [mover] tried to move by [wanted] and got [moved]: the blocking bodies it walked into that let
     * themselves be moved take the part of the move they stopped. A client tells the server, which owns them.
     */
    fun afterMove(mover: Entity, wanted: Vec3, moved: Vec3) {
        val blocked = Vec3(wanted.x - moved.x, 0.0, wanted.z - moved.z)
        if (blocked.lengthSqr() < MIN_SHOVE * MIN_SHOVE) return

        val reach = mover.boundingBox.move(moved).expandTowards(blocked).inflate(CONTACT)
        mover.level().getEntities(mover, reach) { other ->
            other.bodyComponent?.let { it.mode == BodyMode.BLOCKING && it.isMovedByOthers } == true &&
                other.rootVehicle !== mover.rootVehicle
        }.forEach { body ->
            val shove = clamp(blocked)
            if (mover.level().isClientSide) BodyShovePacket(body.id, shove.x, shove.z).send()
            else if (mover !is Player) body.push(shove.x, 0.0, shove.z)
        }
    }

    internal fun shoveFromPlayer(player: ServerPlayer, packet: BodyShovePacket) {
        val body = player.serverLevel().getEntity(packet.entityId) ?: return
        val component = body.bodyComponent ?: return
        if (component.mode != BodyMode.BLOCKING || !component.isMovedByOthers) return
        if (!player.boundingBox.inflate(PLAYER_REACH).intersects(body.boundingBox)) return
        val shove = clamp(Vec3(packet.x, 0.0, packet.z))
        body.push(shove.x, 0.0, shove.z)
    }

    private fun clamp(shove: Vec3): Vec3 {
        val length = shove.horizontalDistance()
        return if (length <= MAX_SHOVE) shove else shove.scale(MAX_SHOVE / length)
    }

    /** Resizes [entity] when its components now give it another size than the one it was given last. */
    fun onComponentsChanged(entity: Entity) {
        val runtime = AttachmentRegistry.attachmentsOrNull(entity)?.runtime ?: return
        val size = entity.bodyComponent?.takeIf(BodyComponent::hasSize)?.let { it.width to it.height }
        if (size == runtime.getOrNull<AppliedSize>(AppliedSizeKey)?.size) return

        runtime.remove(AppliedSizeKey)
        runtime.getOrPut(AppliedSizeKey) { AppliedSize(size) }
        entity.refreshDimensions()
    }

    private class AppliedSize(val size: Pair<Float, Float>?)

    private data object AppliedSizeKey

    private const val MIN_SHOVE = 1.0e-3
    private const val CONTACT = 1.0e-3

    /** How close a player has to be to a body it says it pushed. */
    private const val PLAYER_REACH = 1.0
}

/** Sent by a client whose player walked into a blocking body that can be pushed: the part of the step it stopped. */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class BodyShovePacket(val entityId: Int, val x: Double, val z: Double) : HollowPacket {
    override fun handle(player: Player) {
        if (player is ServerPlayer) EntityBodies.shoveFromPlayer(player, this)
    }
}
