package ru.hollowhorizon.hollowengine.common.entities

import kotlinx.serialization.Serializable
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.BodyComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.BodyMode
import ru.hollowhorizon.hollowengine.common.attachments.components.bodyComponent
import ru.hollowhorizon.hollowengine.common.colliders.ColliderBox
import ru.hollowhorizon.hollowengine.common.colliders.ColliderContacts
import ru.hollowhorizon.hollowengine.common.colliders.ColliderModes
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders
import ru.hollowhorizon.hollowengine.common.colliders.SolidColliders
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import kotlin.math.abs

/**
 * What [BodyComponent] changes about vanilla: each call takes what vanilla would answer and returns what
 * the entity's body makes of it. An entity without the component keeps vanilla's answer.
 */
object EntityBodies {
    /** The most one tick of walking into a blocking body passes on to it. */
    private const val MAX_SHOVE = 0.5

    /**
     * Vanilla's pushing, box against box. An entity whose colliders act on bodies takes no part in it: its
     * colliders are its shape, and its box would push and be pushed where nothing of it is.
     */
    fun isPushable(entity: Entity, vanilla: Boolean): Boolean {
        if (hasPhysicalColliders(entity)) return false
        val body = entity.bodyComponent ?: return vanilla
        return vanilla && body.mode == BodyMode.PUSHING && body.pushable
    }

    fun pushesOthers(entity: Entity): Boolean {
        if (hasPhysicalColliders(entity)) return false
        return entity.bodyComponent?.let { it.mode == BodyMode.PUSHING } ?: true
    }

    /** A blocking body stops others with its box, unless it has colliders that act on bodies: then they are its shape. */
    fun isSolid(entity: Entity, vanilla: Boolean): Boolean =
        vanilla || entity.bodyComponent?.mode == BodyMode.BLOCKING && entity.isAlive && !hasPhysicalColliders(entity)

    /**
     * Whether others may move [entity], by walking into it or by their colliders: its body says, and without
     * one an entity with colliders that act on bodies can be moved and any other as far as vanilla pushes it.
     */
    fun isMovedByOthers(entity: Entity): Boolean {
        if (!entity.isAlive || entity.isSpectator) return false
        val body = entity.bodyComponent ?: return hasPhysicalColliders(entity) || entity.isPushable
        return body.isMovedByOthers
    }

    fun dimensions(entity: Entity, vanilla: EntityDimensions): EntityDimensions =
        entity.bodyComponent?.resize(vanilla) ?: vanilla

    /**
     * After [mover] tried to move by [wanted] and got [moved]: the bodies it walked into that let themselves
     * be moved take the part of the move they stopped. A body only takes it from the side: what stands on a
     * body does not push it, however it leans on the rest of it. A client tells the server, which owns the
     * bodies.
     */
    fun afterMove(mover: Entity, wanted: Vec3, moved: Vec3) {
        if (ColliderContacts.isDisplacing) return
        val blocked = Vec3(wanted.x - moved.x, 0.0, wanted.z - moved.z)
        if (blocked.lengthSqr() < MIN_SHOVE * MIN_SHOVE) return

        val after = mover.boundingBox.move(moved)
        val reach = after.expandTowards(blocked).inflate(SHAPE_REACH)
        mover.level().getEntities(mover, reach) { other ->
            other.rootVehicle !== mover.rootVehicle && isMovedByOthers(other)
        }.forEach { body ->
            val shapes = shapesOf(body)
            if (shapes.none { blocks(it, after, blocked) } || shapes.any { holdsUp(it, after) }) return@forEach
            val shove = clamp(blocked)
            if (mover.level().isClientSide) BodyShovePacket(body.id, shove.x, shove.z).send()
            else if (mover !is Player) body.push(shove.x, 0.0, shove.z)
        }
    }

    internal fun shoveFromPlayer(player: ServerPlayer, packet: BodyShovePacket) {
        val body = player.serverLevel().getEntity(packet.entityId) ?: return
        if (!isMovedByOthers(body)) return
        val near = player.boundingBox.inflate(PLAYER_REACH)
        if (shapesOf(body).none { it.bounds.intersects(near) }) return
        val shove = clamp(Vec3(packet.x, 0.0, packet.z))
        body.push(shove.x, 0.0, shove.z)
    }

    private fun clamp(shove: Vec3): Vec3 {
        val length = shove.horizontalDistance()
        return if (length <= MAX_SHOVE) shove else shove.scale(MAX_SHOVE / length)
    }

    /** What a body stops others with: its solid colliders, or its box when it is blocking and has no colliders that act on bodies. */
    private fun shapesOf(body: Entity): List<ColliderBox> {
        if (hasPhysicalColliders(body)) return SolidColliders.solidBoxes(body)
        if (body.bodyComponent?.mode != BodyMode.BLOCKING) return emptyList()
        val box = body.boundingBox
        return listOf(ColliderBox.aligned(box.center, Vec3(box.xsize / 2.0, box.ysize / 2.0, box.zsize / 2.0)))
    }

    private fun hasPhysicalColliders(entity: Entity): Boolean = EntityColliders.hasTargets(entity, ColliderModes::isPhysical)

    /** Whether [shape] is what stopped [box] from going on by [blocked]. */
    private fun blocks(shape: ColliderBox, box: AABB, blocked: Vec3): Boolean {
        val inner = box.deflate(CONTACT)
        return stops(shape, inner, Direction.Axis.X, blocked.x) || stops(shape, inner, Direction.Axis.Z, blocked.z)
    }

    private fun stops(shape: ColliderBox, box: AABB, axis: Direction.Axis, distance: Double): Boolean {
        if (abs(distance) < MIN_SHOVE) return false
        val reach = distance + Math.copySign(CONTACT * 2.0, distance)
        return abs(shape.sweep(box, axis, reach)) < abs(reach) - CONTACT
    }

    /** Whether [box] stands on [shape]. */
    private fun holdsUp(shape: ColliderBox, box: AABB): Boolean =
        shape.penetration(box.deflate(CONTACT, 0.0, CONTACT).move(0.0, -SUPPORT_DEPTH, 0.0)) != null

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

    /** How far below its feet a mover still stands on a body. */
    private const val SUPPORT_DEPTH = 0.05

    /** How far a body's colliders may reach out of its box and still be walked into. */
    private const val SHAPE_REACH = 4.0

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
