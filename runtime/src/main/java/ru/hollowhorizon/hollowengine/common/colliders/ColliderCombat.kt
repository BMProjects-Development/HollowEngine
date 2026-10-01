package ru.hollowhorizon.hollowengine.common.colliders

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.HitResult

/**
 * Ties the damage of a melee attack or a projectile to the collider it was aimed at, for as long as the
 * attack runs: vanilla builds the damage source deep inside, so the hit is held here and the damage that
 * reaches the same entity picks it up in [resolve].
 */
internal object ColliderCombat {
    private var current: Strike? = null

    private class Strike(val hit: ColliderHit, val dealer: Entity)

    /**
     * A player's attack on [target], through the collider the player claimed. An attack without a claim,
     * as when another mod makes the player attack, lands the vanilla way, on no collider. A claim on a
     * collider that takes no hits, a button, is a miss: the player aimed at that and not at the entity.
     */
    fun attack(player: Player, target: Entity, original: Runnable) {
        if (player.level().isClientSide || !EntityColliders.hasTargets(target, ColliderModes::hit)) return original.run()
        val claim = ColliderClaims.take(player, target) ?: return original.run()
        if (!claim.spec.modes.hit) return
        during(Strike(claim.hit, player), original)
    }

    /** A projectile landing; when it landed on a collider, its damage lands there too. */
    fun projectileHit(projectile: Entity, result: HitResult, original: Runnable) {
        val hit = (result as? ColliderHitResult)?.hit
        if (projectile.level().isClientSide || hit == null) return original.run()
        during(Strike(hit, projectile), original)
    }

    /** [source] as it lands on [entity]: on the collider of the attack under way, when it is the one dealing it. */
    fun resolve(entity: Entity, source: DamageSource): DamageSource {
        if (source is ColliderDamageSource) return source
        val strike = current ?: return source
        if (strike.hit.entity !== entity || source.directEntity !== strike.dealer) return source
        return ColliderDamageSource(source, strike.hit)
    }

    private fun during(strike: Strike, original: Runnable) {
        val previous = current
        current = strike
        try {
            original.run()
        } finally {
            current = previous
        }
    }
}
