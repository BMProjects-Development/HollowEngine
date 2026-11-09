package ru.hollowhorizon.hollowengine.addons.narrative.world

import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.addons.narrative.story.storyLevel
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders

/**
 * What a ray met first, if anything.
 *
 * @param hit whether it met a block or an entity before its end.
 * @param position where it stopped: where it met something, or its end.
 * @param block the block it met, null when it met none.
 * @param face the side of [block] it met.
 * @param entity the entity it met, null when it met none.
 * @param collider the collider of [entity] it met, by name, when the model of the entity has colliders.
 * @param bone the bone [collider] hangs on.
 */
data class StoryHit(
    val hit: Boolean,
    val position: Vec3,
    val block: Vec3? = null,
    val face: Direction? = null,
    val entity: Entity? = null,
    val collider: String? = null,
    val bone: String? = null,
)

/**
 * Casts a ray from [from] along [direction] for [distance] blocks in [level], the level of the story when left out:
 * what it meets first among blocks and, with [entities], entities other than [ignore]. An entity whose model has
 * colliders is met at them rather than at its box. With [fluids] water and lava stop it too.
 */
@EditorCategory(StoryCategory.RAYCAST)
@StoryValue("raycast")
suspend fun raycast(
    from: Vec3,
    direction: Vec3,
    distance: Double = 16.0,
    entities: Boolean = true,
    fluids: Boolean = false,
    ignore: Entity? = null,
    level: ServerLevel? = null,
): StoryHit {
    if (direction.lengthSqr() == 0.0) throw StoryException("A ray needs a direction")
    val world = level ?: storyLevel()
    val end = from.add(direction.normalize().scale(distance))
    val fluid = if (fluids) ClipContext.Fluid.ANY else ClipContext.Fluid.NONE
    val context = ignore?.let { ClipContext(from, end, ClipContext.Block.OUTLINE, fluid, it) } ?: ClipContext(
        from,
        end,
        ClipContext.Block.OUTLINE,
        fluid,
        CollisionContext.empty()
    )
    val blockHit = world.clip(context)
    val reach = if (blockHit.type == HitResult.Type.MISS) end else blockHit.location
    val entityHit = if (entities) entityHit(world, from, reach, ignore) else null
    return when {
        entityHit != null -> entityHit
        blockHit.type == HitResult.Type.BLOCK -> StoryHit(
            true, blockHit.location, Vec3.atLowerCornerOf(blockHit.blockPos), blockHit.direction,
        )

        else -> StoryHit(false, end)
    }
}

/** What [who] looks at within [distance] blocks: the ray from its eyes the way it faces, which does not meet itself. */
@EditorCategory(StoryCategory.RAYCAST)
@StoryValue("look-raycast")
suspend fun lookRaycast(
    who: Entity,
    distance: Double = 16.0,
    entities: Boolean = true,
    fluids: Boolean = false,
): StoryHit = raycast(who.eyePosition, who.lookAngle, distance, entities, fluids, who, who.level() as? ServerLevel)

/** The names of the colliders the model of [who] has now, empty for an entity without them. */
@EditorCategory(StoryCategory.COLLIDERS)
@StoryValue("colliders")
fun collidersOf(who: Entity): List<String> = EntityColliders.of(who).map { it.name }

/** Where the middle of the collider [collider] of [who] is now; null when it has no such collider. */
@EditorCategory(StoryCategory.COLLIDERS)
@StoryValue("collider-position")
fun colliderPositionOf(who: Entity, collider: String): Vec3? =
    EntityColliders.of(who).firstOrNull { it.name == collider }?.volume?.center

/** The entity nearest to [from] that the segment to [to] meets, at its colliders when it has them. */
private fun entityHit(level: ServerLevel, from: Vec3, to: Vec3, ignore: Entity?): StoryHit? {
    val area = AABB(from, to).inflate(1.0)
    var nearest: StoryHit? = null
    var best = Double.MAX_VALUE
    level.getEntities(ignore, area) { it.isPickable && !it.isSpectator }.forEach { entity ->
        val colliders = EntityColliders.of(entity)
        val hits = if (colliders.isEmpty()) {
            listOfNotNull(entity.boundingBox.clip(from, to).orElse(null)?.let { StoryHit(true, it, entity = entity) })
        } else {
            colliders.mapNotNull { collider ->
                collider.volume.clip(from, to)
                    ?.let { StoryHit(true, it, entity = entity, collider = collider.name, bone = collider.bone) }
            }
        }
        hits.forEach { hit ->
            val distance = hit.position.distanceToSqr(from)
            if (distance < best) {
                best = distance
                nearest = hit
            }
        }
    }
    return nearest
}
