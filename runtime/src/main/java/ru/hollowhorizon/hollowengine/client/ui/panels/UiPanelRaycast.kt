package ru.hollowhorizon.hollowengine.client.ui.panels

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Uses the panel's reach and rendered camera, including HollowEngine's ProjectileUtil collider hooks. */
internal class UiPanelRaycast(private val source: Entity, private val eye: Vec3, private val look: Vec3) {
    fun isOccluded(distance: Double): Boolean {
        val end = eye.add(look.scale(distance))
        val block = source.level().clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, source))
        val entity = ProjectileUtil.getEntityHitResult(
            source, eye, end, AABB(eye, end).inflate(1.0),
            { !it.isSpectator && it.isPickable }, distance * distance,
        )
        return UiPanelPicking.isOccluded(
            distance,
            block.takeIf { it.type == HitResult.Type.BLOCK }?.location?.distanceTo(eye),
            entity?.location?.distanceTo(eye),
        )
    }
}
