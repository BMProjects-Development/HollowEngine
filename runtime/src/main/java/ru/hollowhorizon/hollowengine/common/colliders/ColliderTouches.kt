package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import java.util.WeakHashMap

/** Where a touch between a collider and an entity is at. */
enum class ColliderTouchPhase {
    /** They touch this tick and did not the tick before. */
    START,

    /** They touched the tick before and still do. */
    STAY,

    /** They touched the tick before and no longer do. */
    END,
}

/**
 * A collider of [entity] touching [other], or having gone through it since the last tick, as a swung sword
 * does. Posted on the server once a tick for each touch, whatever the collider's modes, with the [phase]
 * the touch is at: what the touch does is up to whoever listens. [isStanding] is true while [other] rests
 * on top of the collider rather than being in it.
 */
class ColliderTouchEvent(
    entity: Entity,
    val other: Entity,
    val collider: String,
    val bone: String?,
    val phase: ColliderTouchPhase,
    val isStanding: Boolean,
) : EntityEvent(entity), ServerEvent {
    companion object : EventHandler<ColliderTouchEvent>()
}

/** Finds what the colliders of each entity touch tick by tick, for [ColliderTouchEvent]. */
internal object ColliderTouches {
    /** How far around an entity a collider still touches it: one it leans on is a hair away. */
    private const val TOUCH_MARGIN = 1.0e-3

    /** How far below its feet an entity still stands on a collider, the same as it rides one. */
    private const val STAND_DEPTH = 0.05

    /** How high its feet reach, for telling an entity standing on a collider from one in it. */
    private const val FEET = 0.01

    private data class Touch(val host: Entity, val other: Entity, val collider: String, val bone: String?)

    /** What touched on the last tick of each level, and whether it stood. */
    private val last = WeakHashMap<Level, Map<Touch, Boolean>>()

    /** [hosts] of [level] with their colliders over the last ticks, newest first, as the server posed them. */
    fun post(level: Level, hosts: List<Pair<Entity, List<List<EntityCollider>>>>) {
        if (!ColliderTouchEvent.hasListeners) {
            last.remove(level)
            return
        }
        val now = LinkedHashMap<Touch, Boolean>()
        hosts.forEach { (entity, history) -> touches(entity, history, now) }

        val before = last[level].orEmpty()
        now.forEach { (touch, standing) ->
            post(touch, if (touch in before) ColliderTouchPhase.STAY else ColliderTouchPhase.START, standing)
        }
        before.forEach { (touch, standing) -> if (touch !in now) post(touch, ColliderTouchPhase.END, standing) }
        last[level] = now
    }

    private fun touches(entity: Entity, history: List<List<EntityCollider>>, into: MutableMap<Touch, Boolean>) {
        val host = PosedHost.of(entity, history) { true } ?: return
        val reach = host.bounds.inflate(TOUCH_MARGIN).expandTowards(0.0, STAND_DEPTH, 0.0)
        val near = entity.level().getEntities(entity, reach) { other ->
            other is LivingEntity && other.isAlive && !other.isSpectator && other.rootVehicle !== entity.rootVehicle
        }
        if (near.isEmpty()) return

        host.now.forEach { collider ->
            val previous = host.previousOf(collider)
            near.forEach { other ->
                val box = other.boundingBox
                val around = box.inflate(TOUCH_MARGIN).expandTowards(0.0, -STAND_DEPTH, 0.0)
                if (collider.box.firstTouch(previous, around) == null) return@forEach
                into[Touch(entity, other, collider.name, collider.bone)] = isStanding(collider.box, box)
            }
        }
    }

    /** Whether [box] rests on top of [collider]: it reaches under the feet and nowhere above them. */
    private fun isStanding(collider: ColliderBox, box: AABB): Boolean {
        val feet = AABB(box.minX, box.minY - STAND_DEPTH, box.minZ, box.maxX, box.minY + FEET, box.maxZ)
        val body = AABB(box.minX, box.minY + FEET, box.minZ, box.maxX, box.maxY, box.maxZ)
        return collider.penetration(feet) != null && collider.penetration(body) == null
    }

    private fun post(touch: Touch, phase: ColliderTouchPhase, standing: Boolean) {
        ColliderTouchEvent.post(ColliderTouchEvent(touch.host, touch.other, touch.collider, touch.bone, phase, standing))
    }
}
