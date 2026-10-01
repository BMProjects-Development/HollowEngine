package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/** A collider of [entity] that was struck or clicked, and where. */
data class ColliderHit(
    val entity: Entity,
    val collider: String,
    val bone: String?,
    val location: Vec3,
)

/** What the crosshair or a projectile found when it stopped at a collider rather than at the entity's box. */
class ColliderHitResult(entity: Entity, location: Vec3, val collider: EntityCollider) : EntityHitResult(entity, location) {
    val hit: ColliderHit get() = ColliderHit(entity, collider.name, collider.bone, location)
}

/**
 * Damage that landed on a collider: everything about [original] stays the same, so damage type tags,
 * death messages and the combat tracker see the attack they always did, with [hit] on top.
 */
class ColliderDamageSource(val original: DamageSource, val hit: ColliderHit) :
    DamageSource(original.typeHolder(), original.directEntity, original.entity) {
    override fun sourcePositionRaw(): Vec3? = original.sourcePositionRaw()

    override fun getSourcePosition(): Vec3? = original.sourcePosition
}

/** The collider this damage landed on, or null when it did not land on one. */
val DamageSource.colliderHit: ColliderHit? get() = (this as? ColliderDamageSource)?.hit
