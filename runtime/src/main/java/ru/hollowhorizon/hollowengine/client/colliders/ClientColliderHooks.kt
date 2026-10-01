package ru.hollowhorizon.hollowengine.client.colliders

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.HitResult
import ru.hollowhorizon.hollowengine.common.colliders.ColliderClaimPacket
import ru.hollowhorizon.hollowengine.common.colliders.ColliderHit
import ru.hollowhorizon.hollowengine.common.colliders.ColliderHitResult
import ru.hollowhorizon.hollowengine.common.colliders.ColliderPush
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent

/**
 * What the client does about colliders: tells the server which one the player aimed at before the
 * vanilla attack or interaction goes out, and lets pushing colliders shove the player.
 */
object ClientColliderHooks {
    /** The clickable collider of [target] under the crosshair. */
    fun interacted(target: Entity): ColliderHit? =
        aimedAt(Minecraft.getInstance().hitResult, target)?.takeIf { it.collider.spec.modes.interact }?.hit

    private fun aimedAt(result: HitResult?, target: Entity): ColliderHitResult? =
        (result as? ColliderHitResult)?.takeIf { it.entity === target }

    /** Sent right before the vanilla packet, which the server handles next. */
    fun claim(result: HitResult?, target: Entity) {
        val hit = aimedAt(result ?: Minecraft.getInstance().hitResult, target)?.hit ?: return
        ColliderClaimPacket(target.id, hit.collider, hit.bone, hit.location.x, hit.location.y, hit.location.z).send()
    }

    @SubscribeEvent
    @ClientOnly
    fun onClientTick(event: TickEvent.Client) {
        val player = event.minecraft.player ?: return
        if (!player.isSpectator) ColliderPush.pushPlayer(player)
    }
}
