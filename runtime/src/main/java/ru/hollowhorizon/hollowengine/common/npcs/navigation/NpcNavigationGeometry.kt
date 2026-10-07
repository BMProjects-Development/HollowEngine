package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.CollisionGetter
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.colliders.ColliderPathObstacles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object NpcNavigationGeometry {
    private const val COLLISION_EPSILON = 1.0e-3
    private const val SUPPORT_DEPTH = 0.125
    private const val MAX_SAMPLE_DISTANCE = 0.25
    private const val MIN_SUPPORT_RADIUS = 0.025
    private const val MAX_SUPPORT_RADIUS = 0.1
    private const val CORNER_CLEARANCE = 0.025
    private const val MAX_JUMP_RISE = 1.125
    private const val MAX_JUMP_DISTANCE = 1.75
    private const val MAX_SAFE_DROP = 1.25
    private const val JUMP_CLEARANCE = 0.05

    /** How far under the feet the block that sets the ground friction is looked for, as vanilla does. */
    private const val GROUND_PROBE = 0.5000001

    /**
     * How far around a point without room for the NPC, in blocks, a spot with room is looked for: sideways, and
     * the cells above and below its own.
     */
    private const val SPOT_REACH = 2
    private const val SPOT_RISE = 1
    private const val SPOT_DROP = 2

    /** How many halvings find how far the NPC slides toward a point before something stops it. */
    private const val SLIDE_STEPS = 6

    fun canWalkDirectly(
        level: CollisionGetter,
        mob: Mob,
        from: Vec3,
        to: Vec3,
        maxStepHeight: Double = mob.maxUpStep().toDouble(),
    ): Boolean {
        if (abs(to.y - from.y) > maxStepHeight + COLLISION_EPSILON) return false

        return canWalkSegment(level, mob, from, to)
    }

    fun findWalkableWaypoint(
        level: CollisionGetter,
        mob: Mob,
        from: Vec3,
        to: Vec3,
        maxStepHeight: Double = mob.maxUpStep().toDouble(),
    ): Vec3? {
        if (abs(to.y - from.y) > maxStepHeight + COLLISION_EPSILON) return null
        if (canWalkSegment(level, mob, from, to)) return to

        val dx = to.x - from.x
        val dz = to.z - from.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)
        if (horizontalDistance < COLLISION_EPSILON) return null

        val midpoint = from.lerp(to, 0.5)
        val offset = mob.bbWidth * 0.5 * sqrt(2.0) + CORNER_CLEARANCE
        val perpendicularX = -dz / horizontalDistance * offset
        val perpendicularZ = dx / horizontalDistance * offset
        for (side in CORNER_SIDES) {
            val waypoint = midpoint.add(perpendicularX * side, 0.0, perpendicularZ * side)
            if (canWalkSegment(level, mob, from, waypoint) && canWalkSegment(level, mob, waypoint, to)) {
                return waypoint
            }
        }
        return null
    }

    /**
     * Диагональное "протискивание" сквозь угол: игрок проскакивает такие проходы, идя прямо на
     * скорости, даже если с одной стороны стоит блок, а с другой — щель/лава без опоры под ногами.
     *
     * В отличие от [findWalkableWaypoint] здесь опора под ногами требуется только в конечных точках
     * (там нода реально стоит), а вдоль самого прохода достаточно свободного места под тело — так же
     * как проскакивает игрок, не задерживаясь над щелью. Полностью закрытый угол (блоки с обеих
     * сторон) по-прежнему отбраковывается: свободного зазора там нет.
     */
    fun canSqueezeDiagonally(
        level: CollisionGetter,
        mob: Mob,
        from: Vec3,
        to: Vec3,
        maxStepHeight: Double = mob.maxUpStep().toDouble(),
    ): Boolean {
        if (abs(to.y - from.y) > maxStepHeight + COLLISION_EPSILON) return false
        if (!hasSupport(level, mob, from) || !hasSupport(level, mob, to)) return false
        return findSqueezeWaypoint(level, mob, from, to) != null
    }

    /**
     * Точка, огибая которую тело NPC проходит сквозь угол без столкновений. Опора под ногами не
     * проверяется — проход считается транзитным. Возвращает саму цель, если прямой путь свободен.
     */
    fun findSqueezeWaypoint(level: CollisionGetter, mob: Mob, from: Vec3, to: Vec3): Vec3? {
        if (hasBodyClearance(level, mob, from, to)) return to

        val dx = to.x - from.x
        val dz = to.z - from.z
        val horizontalDistance = sqrt(dx * dx + dz * dz)
        if (horizontalDistance < COLLISION_EPSILON) return null

        val midpoint = from.lerp(to, 0.5)
        val perpendicularX = -dz / horizontalDistance
        val perpendicularZ = dx / horizontalDistance
        val baseOffset = mob.bbWidth * 0.5 * sqrt(2.0)
        for (fraction in SQUEEZE_OFFSET_FRACTIONS) {
            val offset = baseOffset * fraction + CORNER_CLEARANCE
            for (side in CORNER_SIDES) {
                val waypoint = midpoint.add(perpendicularX * offset * side, 0.0, perpendicularZ * offset * side)
                if (hasBodyClearance(level, mob, from, waypoint) && hasBodyClearance(level, mob, waypoint, to)) {
                    return waypoint
                }
            }
        }
        return null
    }

    fun canJumpDirectly(
        level: CollisionGetter,
        mob: Mob,
        from: Vec3,
        to: Vec3,
    ): Boolean {
        val rise = to.y - from.y
        if (rise <= mob.maxUpStep() + COLLISION_EPSILON || rise > MAX_JUMP_RISE) return false
        val dx = to.x - from.x
        val dz = to.z - from.z
        if (sqrt(dx * dx + dz * dz) > MAX_JUMP_DISTANCE) return false

        val elevatedY = to.y + JUMP_CLEARANCE
        val ascent = bodyBox(mob, from).expandTowards(0.0, elevatedY - from.y, 0.0)
        if (!isFree(level, mob, ascent)) return false

        val elevatedFrom = Vec3(from.x, elevatedY, from.z)
        val elevatedTo = Vec3(to.x, elevatedY, to.z)
        if (!hasBodyClearance(level, mob, elevatedFrom, elevatedTo)) return false
        if (!hasBodyClearance(level, mob, to, to)) return false
        return hasSupport(level, mob, to)
    }

    fun canDropDirectly(
        level: CollisionGetter,
        mob: Mob,
        from: Vec3,
        to: Vec3,
    ): Boolean {
        val drop = from.y - to.y
        if (drop <= mob.maxUpStep() + COLLISION_EPSILON || drop > MAX_SAFE_DROP) return false
        val dx = to.x - from.x
        val dz = to.z - from.z
        if (sqrt(dx * dx + dz * dz) > MAX_JUMP_DISTANCE) return false

        val ledgeTarget = Vec3(to.x, from.y, to.z)
        if (!hasBodyClearance(level, mob, from, ledgeTarget)) return false
        val descent = bodyBox(mob, to).expandTowards(0.0, drop, 0.0)
        return isFree(level, mob, descent) && hasSupport(level, mob, to)
    }

    fun hasStepableSurface(
        level: CollisionGetter,
        surfacePos: BlockPos,
        maxStepHeight: Double,
        approachPosition: Vec3,
    ): Boolean {
        val shape = level.getBlockState(surfacePos).getCollisionShape(level, surfacePos)
        if (shape.isEmpty) return false
        val localX = (approachPosition.x - surfacePos.x).coerceIn(COLLISION_EPSILON, 1.0 - COLLISION_EPSILON)
        val localZ = (approachPosition.z - surfacePos.z).coerceIn(COLLISION_EPSILON, 1.0 - COLLISION_EPSILON)
        val surfaceHeight = shape.toAabbs()
            .asSequence()
            .filter { box ->
                localX >= box.minX - COLLISION_EPSILON && localX <= box.maxX + COLLISION_EPSILON &&
                        localZ >= box.minZ - COLLISION_EPSILON && localZ <= box.maxZ + COLLISION_EPSILON
            }
            .maxOfOrNull(AABB::maxY)
            ?: return false
        return surfaceHeight > COLLISION_EPSILON && surfaceHeight <= maxStepHeight + COLLISION_EPSILON
    }

    private fun canWalkSegment(level: CollisionGetter, mob: Mob, from: Vec3, to: Vec3): Boolean {
        val distance = from.distanceTo(to)
        val sampleDistance = min(MAX_SAMPLE_DISTANCE, max(mob.bbWidth * 0.5, COLLISION_EPSILON))
        val sampleCount = max(1, ceil(distance / sampleDistance).toInt())

        for (sample in 1..sampleCount) {
            val progress = sample.toDouble() / sampleCount
            val position = from.lerp(to, progress)
            if (!isFree(level, mob, bodyBox(mob, position))) return false
            if (!hasSupport(level, mob, position)) return false
        }
        return true
    }

    private fun hasBodyClearance(level: CollisionGetter, mob: Mob, from: Vec3, to: Vec3): Boolean {
        val distance = from.distanceTo(to)
        val sampleDistance = min(MAX_SAMPLE_DISTANCE, max(mob.bbWidth * 0.5, COLLISION_EPSILON))
        val sampleCount = max(1, ceil(distance / sampleDistance).toInt())
        for (sample in 0..sampleCount) {
            val position = from.lerp(to, sample.toDouble() / sampleCount)
            if (!isFree(level, mob, bodyBox(mob, position))) return false
        }
        return true
    }

    private fun bodyBox(mob: Mob, position: Vec3): AABB {
        val halfWidth = mob.bbWidth * 0.5 - COLLISION_EPSILON
        val height = mob.bbHeight - COLLISION_EPSILON * 2.0
        return AABB(
            position.x - halfWidth,
            position.y + COLLISION_EPSILON,
            position.z - halfWidth,
            position.x + halfWidth,
            position.y + height,
            position.z + halfWidth,
        )
    }

    private fun hasSupport(level: CollisionGetter, mob: Mob, position: Vec3): Boolean {
        val halfWidth = mob.bbWidth * 0.5 - COLLISION_EPSILON
        val supportRadius = min(MAX_SUPPORT_RADIUS, max(MIN_SUPPORT_RADIUS, halfWidth * 0.25))
        val support = AABB(
            position.x - supportRadius,
            position.y - SUPPORT_DEPTH,
            position.z - supportRadius,
            position.x + supportRadius,
            position.y + COLLISION_EPSILON,
            position.z + supportRadius,
        )
        return !level.noCollision(mob, support)
    }

    /** Whether [box] meets neither blocks nor the colliders and blocking bodies of other entities. */
    fun isFree(level: CollisionGetter, mob: Mob, box: AABB): Boolean =
        level.noCollision(mob, box) && !ColliderPathObstacles.overlaps(mob, box)

    /** What a jump of [mob] is simulated in: the blocks of [level] and the obstacles around them. */
    fun jumpSpace(level: CollisionGetter, mob: Mob): JumpSpace = JumpSpace { box -> !isFree(level, mob, box) }

    /** [mob] running at [speed], vanilla's speed of it, on ground of [friction]. */
    fun jumpBody(mob: Mob, speed: Double, friction: Double) = JumpBody(
        halfWidth = mob.bbWidth * 0.5,
        height = mob.bbHeight.toDouble(),
        speed = speed,
        jumpPower = mob.getAttributeValue(Attributes.JUMP_STRENGTH) + mob.jumpBoostPower,
        gravity = mob.gravity,
        friction = friction,
    )

    /** The friction of the ground under a body standing at [position]. */
    fun frictionUnder(level: BlockGetter, position: Vec3): Double =
        level.getBlockState(BlockPos.containing(position.x, position.y - GROUND_PROBE, position.z)).block.friction.toDouble()

    /**
     * The point nearest [target] where [mob] can stand: on a floor, its body clear of blocks and of what it plans
     * around, within [SPOT_REACH] blocks of it sideways. [target] itself when it is in water, which takes the NPC
     * anywhere; null when nothing near has room.
     */
    fun standingSpot(level: CollisionGetter, mob: Mob, target: Vec3): Vec3? {
        val origin = BlockPos.containing(target.x, target.y + COLLISION_EPSILON, target.z)
        if (!level.getFluidState(origin).isEmpty) return target
        val cell = BlockPos.MutableBlockPos()
        var best: Vec3? = null
        var bestDistance = Double.MAX_VALUE
        for (dy in SPOT_RISE downTo -SPOT_DROP) for (dx in -SPOT_REACH..SPOT_REACH) for (dz in -SPOT_REACH..SPOT_REACH) {
            cell.set(origin.x + dx, origin.y + dy, origin.z + dz)
            val nearX = target.x.coerceIn(cell.x.toDouble(), cell.x + 1.0) - target.x
            val nearZ = target.z.coerceIn(cell.z.toDouble(), cell.z + 1.0) - target.z
            if (nearX * nearX + nearZ * nearZ >= bestDistance) continue
            val spot = spotIn(level, mob, cell, target) ?: continue
            val distance = spot.distanceToSqr(target)
            if (distance < bestDistance) {
                best = spot
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Where in [cell] nearest [target] the NPC stands, coming from the middle of the cell straight toward it, one
     * axis at a time as a body slides along a wall; null when there is no room in the middle.
     */
    private fun spotIn(level: CollisionGetter, mob: Mob, cell: BlockPos, target: Vec3): Vec3? {
        val floor = WalkNodeEvaluator.getFloorLevel(level, cell)
        if (floor <= cell.y - 1.0 + COLLISION_EPSILON) return null
        val middle = nodeCenter(mob, cell.x, floor, cell.z)
        if (!stands(level, mob, middle)) return null
        val aim = Vec3(target.x.coerceIn(cell.x.toDouble(), cell.x + 1.0), floor, target.z.coerceIn(cell.z.toDouble(), cell.z + 1.0))
        if (stands(level, mob, aim)) return aim
        val alongX = Vec3(aim.x, floor, middle.z)
        val alongZ = Vec3(middle.x, floor, aim.z)
        return if (abs(aim.x - middle.x) >= abs(aim.z - middle.z)) {
            approach(level, mob, middle, alongX).let { approach(level, mob, it, Vec3(it.x, floor, aim.z)) }
        } else {
            approach(level, mob, middle, alongZ).let { approach(level, mob, it, Vec3(aim.x, floor, it.z)) }
        }
    }

    /** The farthest point from [from], where the NPC stands, toward [to] that it still stands at, found by halving. */
    private fun approach(level: CollisionGetter, mob: Mob, from: Vec3, to: Vec3): Vec3 {
        if (stands(level, mob, to)) return to
        var free = 0.0
        var blocked = 1.0
        repeat(SLIDE_STEPS) {
            val middle = (free + blocked) * 0.5
            if (stands(level, mob, from.lerp(to, middle))) free = middle else blocked = middle
        }
        return from.lerp(to, free)
    }

    private fun stands(level: CollisionGetter, mob: Mob, position: Vec3): Boolean =
        isFree(level, mob, bodyBox(mob, position)) && hasSupport(level, mob, position)

    fun nodeCenter(mob: Mob, x: Int, floorY: Double, z: Int): Vec3 {
        val horizontalOffset = Mth.floor(mob.bbWidth + 1.0f) / 2.0
        return Vec3(x + horizontalOffset, floorY, z + horizontalOffset)
    }

    private val CORNER_SIDES = doubleArrayOf(-1.0, 1.0)
    private val SQUEEZE_OFFSET_FRACTIONS = doubleArrayOf(0.5, 0.75, 1.0)
}
