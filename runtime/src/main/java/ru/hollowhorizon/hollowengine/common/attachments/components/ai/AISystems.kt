package ru.hollowhorizon.hollowengine.common.attachments.components.ai

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NpcPathNavigation
import ru.hollowhorizon.hollowengine.common.npcs.navigation.faceTowards
import java.util.*

private object AIRuntimeState {
    val attackCooldowns = hashMapOf<UUID, Int>()
    val patrolIndices = hashMapOf<UUID, Int>()
    val patrolWaits = hashMapOf<UUID, Int>()
    val lootScanCooldowns = hashMapOf<UUID, Int>()
}

object AIComponentSystems {
    /** How many points ahead a patrol plans its pace along. */
    private const val PATROL_LOOKAHEAD = 4

    private val attackId by lazy { descriptorId(AttackTargetComponent::class) }
    private val followId by lazy { descriptorId(FollowTargetComponent::class) }
    private val moveToId by lazy { descriptorId(MoveToPositionComponent::class) }
    private val patrolId by lazy { descriptorId(PatrolPathComponent::class) }
    private val lookAtId by lazy { descriptorId(LookAtTargetComponent::class) }
    private val pickupId by lazy { descriptorId(PickupLootComponent::class) }

    fun tickEntity(entity: MCEntity, components: Map<ResourceLocation, Any>) {
        val attack = components[attackId] as? AttackTargetComponent
        val follow = components[followId] as? FollowTargetComponent
        val moveTo = components[moveToId] as? MoveToPositionComponent
        val patrol = components[patrolId] as? PatrolPathComponent
        val lookAt = components[lookAtId] as? LookAtTargetComponent
        val pickup = components[pickupId] as? PickupLootComponent

        handleAttack(entity, attack)
        handleFollow(entity, follow, attack)
        handleMoveTo(entity, moveTo)
        handlePatrol(entity, patrol, moveTo)
        handleLookAt(entity, lookAt)
        handlePickup(entity, pickup)
        cleanupMissing(entity.uuid, attack, patrol, pickup)
    }

    fun cleanup(entity: MCEntity) {
        val id = entity.uuid
        AIRuntimeState.attackCooldowns.remove(id)
        AIRuntimeState.patrolIndices.remove(id)
        AIRuntimeState.patrolWaits.remove(id)
        AIRuntimeState.lootScanCooldowns.remove(id)
    }

    private fun handleAttack(entity: MCEntity, attack: AttackTargetComponent?) {
        if (attack?.enabled != true) return
        val mob = entity as? Mob ?: return
        val target = attack.targetEntity.resolve(entity.level()) as? LivingEntity ?: return
        if (!target.isAlive || target.isRemoved) {
            mob.target = null
            (mob as? PathfinderMob)?.navigation?.stop()
            return
        }

        val distance = entity.distanceTo(target)
        if (distance > attack.forgetDistance) {
            mob.target = null
            (mob as? PathfinderMob)?.navigation?.stop()
            return
        }

        mob.target = target
        mob.faceTowards(target)

        val cooldown = AIRuntimeState.attackCooldowns.getOrDefault(entity.uuid, 0)
        if (cooldown > 0) {
            AIRuntimeState.attackCooldowns[entity.uuid] = cooldown - 1
        }

        if (distance > attack.attackRange) {
            if (distance <= attack.chaseRange) {
                (mob as? PathfinderMob)?.navigation?.moveTo(
                    target.x,
                    target.y,
                    target.z,
                    attack.attackRange.toInt(),
                    1.0
                )
            } else {
                (mob as? PathfinderMob)?.navigation?.stop()
            }
            return
        }

        (mob as? PathfinderMob)?.navigation?.stop()
        if (cooldown <= 0) {
            mob.swing(InteractionHand.MAIN_HAND)
            mob.doHurtTarget(target)
            AIRuntimeState.attackCooldowns[entity.uuid] = attack.cooldownTicks.coerceAtLeast(1)
        }
    }

    private fun handleFollow(entity: MCEntity, follow: FollowTargetComponent?, attack: AttackTargetComponent?) {
        if (follow?.enabled != true) return
        if (attack?.enabled == true) return
        val mob = entity as? PathfinderMob ?: return
        val target = follow.targetEntity.resolve(entity.level()) ?: return
        if (target.isRemoved) return

        val distance = entity.distanceTo(target)
        if (distance > follow.maxDistance) {
            mob.navigation.stop()
            return
        }

        if (distance > follow.preferredDistance) {
            mob.navigation.moveTo(
                target.x,
                target.y,
                target.z,
                follow.preferredDistance.toInt(),
                follow.speed.toDouble()
            )
        } else {
            mob.navigation.stop()
        }
    }

    private fun handleMoveTo(entity: MCEntity, move: MoveToPositionComponent?) {
        if (move?.enabled != true) return
        val mob = entity as? PathfinderMob ?: return
        if (mob.navigation.moveTo(
                move.target.x,
                move.target.y,
                move.target.z,
                move.arrivalRadius.toInt(),
                move.speed.toDouble()
            ) && move.stopOnArrival
        ) {
            AttachmentRegistry.componentsById(entity)
                .remove(ComponentDescriptorRegistry.idFor(MoveToPositionComponent::class))
        }
    }

    private fun handlePatrol(entity: MCEntity, patrol: PatrolPathComponent?, moveTo: MoveToPositionComponent?) {
        if (patrol?.enabled != true || patrol.points.isEmpty()) return
        if (moveTo?.enabled == true) return
        val mob = entity as? PathfinderMob ?: return
        val entityId = entity.uuid
        val currentIndex = AIRuntimeState.patrolIndices.getOrDefault(entityId, 0).coerceIn(0, patrol.points.lastIndex)
        val point = patrol.points[currentIndex]
        val arrivalDistanceSqr = patrol.arrivalRadius * patrol.arrivalRadius

        if (entity.distanceToSqr(point.position) <= arrivalDistanceSqr.toDouble()) {
            val waitTicks = AIRuntimeState.patrolWaits.getOrElse(entityId) { point.waitTicks.coerceAtLeast(0) }
            if (waitTicks > 0) {
                AIRuntimeState.patrolWaits[entityId] = waitTicks - 1
                mob.navigation.stop()
                if (point.lookAtNextPoint) {
                    patrol.points.getOrNull((currentIndex + 1).coerceAtMost(patrol.points.lastIndex))
                        ?.let { nextPoint ->
                            mob.faceTowards(nextPoint.position)
                        }
                }
                return
            }

            AIRuntimeState.patrolWaits.remove(entityId)
            val nextIndex = currentIndex + 1
            AIRuntimeState.patrolIndices[entityId] = when {
                nextIndex <= patrol.points.lastIndex -> nextIndex
                patrol.loop -> 0
                else -> patrol.points.lastIndex
            }
            if (!patrol.loop && currentIndex == patrol.points.lastIndex) {
                mob.navigation.stop()
                return
            }
        }

        val activeIndex =
            AIRuntimeState.patrolIndices.getOrDefault(entityId, currentIndex).coerceIn(0, patrol.points.lastIndex)
        val activePoint = patrol.points[activeIndex]
        (mob.navigation as? NpcPathNavigation)?.aim(activePoint.position, passedThrough(patrol, activeIndex), patrol.arrivalRadius.toDouble())
        mob.navigation.moveTo(activePoint.position.x, activePoint.position.y, activePoint.position.z, patrol.arrivalRadius.toInt(), patrol.speed.toDouble())
    }

    private fun passedThrough(patrol: PatrolPathComponent, index: Int): List<Vec3> {
        if (patrol.points[index].waitTicks > 0) return emptyList()
        val points = ArrayList<Vec3>()
        var next = index
        while (points.size < PATROL_LOOKAHEAD) {
            next++
            if (next > patrol.points.lastIndex) {
                if (!patrol.loop) break
                next = 0
            }
            if (next == index) break
            val point = patrol.points[next]
            points += point.position
            if (point.waitTicks > 0) break
        }
        return points
    }

    private fun handleLookAt(entity: MCEntity, lookAt: LookAtTargetComponent?) {
        if (lookAt?.enabled != true) return
        val mob = entity as? Mob ?: return
        when (lookAt.targetMode) {
            LookTargetMode.ENTITY -> {
                val target = lookAt.targetEntity.resolve(entity.level()) ?: return
                mob.faceTowards(target, lookAt.yawSpeed)
            }

            LookTargetMode.POSITION -> mob.faceTowards(lookAt.targetPosition, lookAt.yawSpeed)
        }
    }

    private fun handlePickup(entity: MCEntity, pickup: PickupLootComponent?) {
        if (pickup?.enabled != true) return
        val mob = entity as? Mob ?: return
        val pathfinder = entity as? PathfinderMob ?: return
        val remainingCooldown = AIRuntimeState.lootScanCooldowns.getOrDefault(entity.uuid, 0)
        if (remainingCooldown > 0) {
            AIRuntimeState.lootScanCooldowns[entity.uuid] = remainingCooldown - 1
            return
        }

        mob.setCanPickUpLoot(true)
        val radius = pickup.radius.toDouble()
        val item = entity.level().getEntitiesOfClass(ItemEntity::class.java, entity.boundingBox.inflate(radius))
            .asSequence()
            .filter { !it.isRemoved && !it.item.isEmpty && !it.hasPickUpDelay() }
            .minByOrNull { it.distanceToSqr(entity) }

        if (item != null) {
            pathfinder.navigation.moveTo(item, 1.0)
            mob.faceTowards(item)
        }

        AIRuntimeState.lootScanCooldowns[entity.uuid] = pickup.scanIntervalTicks.coerceAtLeast(1)
    }

    private fun cleanupMissing(
        entityId: UUID,
        attack: AttackTargetComponent?,
        patrol: PatrolPathComponent?,
        pickup: PickupLootComponent?,
    ) {
        if (attack == null) AIRuntimeState.attackCooldowns.remove(entityId)
        if (patrol == null) {
            AIRuntimeState.patrolIndices.remove(entityId)
            AIRuntimeState.patrolWaits.remove(entityId)
        }
        if (pickup == null) AIRuntimeState.lootScanCooldowns.remove(entityId)
    }

    private fun descriptorId(type: kotlin.reflect.KClass<*>): ResourceLocation =
        ComponentDescriptorRegistry.idFor(type)
            ?: error("Component descriptor not found for ${type.qualifiedName}")
}
