package ru.hollowhorizon.hollowengine.common.colliders

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler

/**
 * Sent by a client just before it attacks or interacts with an entity through one of its colliders.
 */
@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class ColliderClaimPacket(
    val entityId: Int,
    val collider: String,
    val bone: String? = null,
    val x: Double,
    val y: Double,
    val z: Double,
) : HollowPacket {
    override fun handle(player: Player) {
        if (player is ServerPlayer) ColliderClaims.receive(player, this)
    }
}

/** A collider a player says it aimed at, once the server agrees it could have. */
internal class ColliderClaim(val hit: ColliderHit, val spec: ColliderAttachmentSpec, val gameTime: Long)

/**
 * What players claim to have aimed at. A claim holds only if the collider was within reach and near the
 * point on one of the ticks the player could have been looking at; it lasts for the tick it came in.
 */
internal object ColliderClaims {
    /** How far the claimed point may be from the box, for the ticks between two poses. */
    private const val TOLERANCE = 0.5

    /** How much farther than its reach a player may aim, the same leeway vanilla gives. */
    private const val REACH_BUFFER = 1.0

    internal fun receive(player: ServerPlayer, packet: ColliderClaimPacket) {
        val runtime = AttachmentRegistry.attachments(player).runtime
        runtime.remove(ClaimKey)

        val target = player.serverLevel().getEntity(packet.entityId)?.takeUnless { it.isRemoved } ?: return
        val point = Vec3(packet.x, packet.y, packet.z)
        val reach = player.entityInteractionRange() + REACH_BUFFER
        if (player.eyePosition.distanceToSqr(point) > reach * reach) return

        val collider = ServerColliderPoses.recent(target).firstNotNullOfOrNull { tick ->
            tick.firstOrNull { it.name == packet.collider && it.bone == packet.bone && it.box.distanceTo(point) <= TOLERANCE }
        } ?: return

        val hit = ColliderHit(target, collider.name, collider.bone, point)
        runtime.getOrPut(ClaimKey) { ColliderClaim(hit, collider.spec, player.level().gameTime) }
    }

    /** The claim [player] holds on [target] this tick. */
    fun peek(player: Player, target: Entity): ColliderClaim? {
        val claim = AttachmentRegistry.attachmentsOrNull(player)?.runtime?.getOrNull<ColliderClaim>(ClaimKey) ?: return null
        return claim.takeIf { it.hit.entity === target && player.level().gameTime - it.gameTime <= MAX_AGE }
    }

    /** Like [peek], and spends the claim, so one claim makes one attack. */
    fun take(player: Player, target: Entity): ColliderClaim? =
        peek(player, target)?.also { AttachmentRegistry.attachments(player).runtime.remove(ClaimKey) }

    /** The box vanilla measures a player's reach to: the claimed point, when there is a claim. */
    fun reachBounds(player: Player, target: Entity, vanilla: AABB): AABB {
        val point = peek(player, target)?.hit?.location ?: return vanilla
        return AABB(point, point)
    }

    private const val MAX_AGE = 1L

    private data object ClaimKey
}
