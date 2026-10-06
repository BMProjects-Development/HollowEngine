package ru.hollowhorizon.hollowengine.common.colliders

import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
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

    /** How far, as a cosine, the claimed point may be from where the player looks: 60 degrees. */
    private const val MIN_VIEW_COSINE = 0.5

    internal fun receive(player: ServerPlayer, packet: ColliderClaimPacket) {
        val runtime = AttachmentRegistry.attachments(player).runtime
        runtime.remove(ClaimKey)

        val target = player.serverLevel().getEntity(packet.entityId)?.takeUnless { it.isRemoved } ?: return
        val point = Vec3(packet.x, packet.y, packet.z)
        val eye = player.eyePosition
        val reach = player.entityInteractionRange() + REACH_BUFFER
        if (eye.distanceToSqr(point) > reach * reach || !isInView(player, eye, point) || isWalledOff(player, eye, point)) return

        val collider = ServerColliderPoses.recent(target).firstNotNullOfOrNull { tick ->
            tick.firstOrNull { it.name == packet.collider && it.bone == packet.bone && it.volume.distanceTo(point) <= TOLERANCE }
                ?.takeIf { claimed -> isFirstOnTheWay(tick, claimed, eye, point) }
        } ?: return

        val hit = ColliderHit(target, collider.name, collider.bone, point)
        runtime.getOrPut(ClaimKey) { ColliderClaim(hit, collider.spec, player.level().gameTime) }
    }

    /**
     * Whether [point] is roughly where [player] looks. The server hears of a turn a tick after the client
     * made it, and a fast flick of the mouse moves the view a long way in a tick, so this only rules out
     * points to the side of or behind the player.
     */
    private fun isInView(player: ServerPlayer, eye: Vec3, point: Vec3): Boolean {
        val toPoint = point.subtract(eye)
        if (toPoint.lengthSqr() < TOLERANCE * TOLERANCE) return true
        return player.getViewVector(1f).dot(toPoint.normalize()) >= MIN_VIEW_COSINE
    }

    /** Whether a block stands between [eye] and [point], short of where the point is anyway. */
    private fun isWalledOff(player: ServerPlayer, eye: Vec3, point: Vec3): Boolean {
        val hit = player.level().clip(ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return false
        val open = eye.distanceTo(point) - TOLERANCE
        return open > 0.0 && eye.distanceToSqr(hit.location) < open * open
    }

    /**
     * Whether [claimed] is the first of the colliders of its entity in [tick] that the line from [eye] to
     * [point] meets, so a player cannot claim a part of an entity hidden behind another part of it.
     */
    private fun isFirstOnTheWay(tick: List<EntityCollider>, claimed: EntityCollider, eye: Vec3, point: Vec3): Boolean {
        val open = eye.distanceTo(point) - TOLERANCE
        if (open <= 0.0) return true
        return tick.none { other ->
            other !== claimed && other.spec.modes.isTarget &&
                other.volume.clip(eye, point)?.let { eye.distanceToSqr(it) < open * open } == true
        }
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
