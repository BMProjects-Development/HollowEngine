package ru.hollowhorizon.hollowengine.common.scripting.nodes

import kotlinx.coroutines.launch
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.common.colliders.ColliderHit
import ru.hollowhorizon.hollowengine.common.colliders.ColliderTouchEvent
import ru.hollowhorizon.hollowengine.common.colliders.ColliderTouchPhase
import ru.hollowhorizon.hollowengine.common.colliders.colliderHit
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.entity.LivingEntityDeathEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerInteractEvent

/**
 * Fires when a player interacts with the bound entity. Filtered to the server side and the main hand,
 * so it runs once per interaction.
 */
context(entity: LivingEntity, script: NodeScript)
fun onInteract(block: suspend (PlayerInteractEvent.EntityInteract) -> Unit) {
    PlayerInteractEvent.EntityInteract.subscribe(script) { event ->
        if (event.target === entity && event.hand == InteractionHand.MAIN_HAND && !event.player.level().isClientSide) {
            script.launch { block(event) }
        }
    }
}

/** Fires when the bound entity takes damage. */
context(entity: LivingEntity, script: NodeScript)
fun onHurt(block: suspend (EntityEvent.Hurt) -> Unit) {
    EntityEvent.Hurt.subscribe(script) { event ->
        if (event.entity === entity) script.launch { block(event) }
    }
}

/**
 * Fires when the bound entity takes damage on one of [colliders], or on any of its colliders when none
 * is named. Damage that did not land on a collider, like a fall or an explosion, does not fire it.
 */
context(entity: LivingEntity, script: NodeScript)
fun onColliderHit(vararg colliders: String, block: suspend (EntityEvent.Hurt, ColliderHit) -> Unit) {
    EntityEvent.Hurt.subscribe(script) { event ->
        val hit = event.source.colliderHit ?: return@subscribe
        if (event.entity === entity && (colliders.isEmpty() || hit.collider in colliders)) {
            script.launch { block(event, hit) }
        }
    }
}

/**
 * Fires when a player clicks one of [colliders] of the bound entity, or any of its clickable colliders
 * when none is named. Filtered like [onInteract].
 */
context(entity: LivingEntity, script: NodeScript)
fun onColliderInteract(vararg colliders: String, block: suspend (PlayerInteractEvent.EntityInteract, ColliderHit) -> Unit) {
    PlayerInteractEvent.EntityInteract.subscribe(script) { event ->
        val hit = event.collider ?: return@subscribe
        if (event.target === entity && event.hand == InteractionHand.MAIN_HAND && !event.player.level().isClientSide &&
            (colliders.isEmpty() || hit.collider in colliders)
        ) {
            script.launch { block(event, hit) }
        }
    }
}

/**
 * Fires every tick one of [colliders] of the bound entity touches a living entity, or any of its colliders
 * when none is named. A fast collider that went through the entity during the tick counts too, so a swung
 * sword finds what it swept, and so does an entity standing on a collider. Whatever the colliders' modes,
 * the touch does nothing by itself.
 */
context(entity: LivingEntity, script: NodeScript)
fun onCollideTick(vararg colliders: String, block: suspend (ColliderTouchEvent) -> Unit) =
    onColliderTouch(colliders, ColliderTouchPhase.START, ColliderTouchPhase.STAY, block = block)

/** Fires once when one of [colliders] of the bound entity starts touching a living entity; see [onCollideTick]. */
context(entity: LivingEntity, script: NodeScript)
fun onColliderEnter(vararg colliders: String, block: suspend (ColliderTouchEvent) -> Unit) =
    onColliderTouch(colliders, ColliderTouchPhase.START, block = block)

/** Fires once when one of [colliders] of the bound entity stops touching a living entity; see [onCollideTick]. */
context(entity: LivingEntity, script: NodeScript)
fun onColliderExit(vararg colliders: String, block: suspend (ColliderTouchEvent) -> Unit) =
    onColliderTouch(colliders, ColliderTouchPhase.END, block = block)

context(entity: LivingEntity, script: NodeScript)
private fun onColliderTouch(
    colliders: Array<out String>,
    vararg phases: ColliderTouchPhase,
    block: suspend (ColliderTouchEvent) -> Unit,
) {
    ColliderTouchEvent.subscribe(script) { event ->
        if (event.entity === entity && event.phase in phases && (colliders.isEmpty() || event.collider in colliders)) {
            script.launch { block(event) }
        }
    }
}

/** Fires when the bound entity dies. */
context(entity: LivingEntity, script: NodeScript)
fun onDie(block: suspend (LivingEntityDeathEvent) -> Unit) {
    LivingEntityDeathEvent.subscribe(script) { event ->
        if (event.entity === entity) script.launch { block(event) }
    }
}
