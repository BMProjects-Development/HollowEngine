package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * A collider of [entity] touching [other] this tick, or having gone through it since the last one, as a
 * swung sword does. Posted on the server once a tick for as long as they touch, whatever the collider's
 * modes: what the touch does is up to whoever listens.
 */
class ColliderTouchEvent(entity: Entity, val other: Entity, val collider: String, val bone: String?) :
    EntityEvent(entity), ServerEvent {
    companion object : EventHandler<ColliderTouchEvent>()
}

/** Finds what the colliders of each entity touched over the last tick, for [ColliderTouchEvent]. */
internal object ColliderTouches {
    /** [hosts] with their colliders over the last ticks, newest first, as the server posed them. */
    fun post(hosts: List<Pair<Entity, List<List<EntityCollider>>>>) {
        if (!ColliderTouchEvent.hasListeners) return
        hosts.forEach { (entity, history) ->
            val host = PosedHost.of(entity, history) { true } ?: return@forEach
            val touched = host.entity.level().getEntities(host.entity, host.bounds) { other ->
                other is LivingEntity && other.isAlive && !other.isSpectator && other.rootVehicle !== host.entity.rootVehicle
            }
            if (touched.isEmpty()) return@forEach
            host.now.forEach { collider ->
                val previous = host.previousOf(collider)
                touched.forEach { other ->
                    if (collider.box.firstTouch(previous, other.boundingBox) != null) {
                        ColliderTouchEvent.post(ColliderTouchEvent(host.entity, other, collider.name, collider.bone))
                    }
                }
            }
        }
    }
}
