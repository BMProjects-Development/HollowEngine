package ru.hollowhorizon.hollowengine.common.models

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.rig.ClientModelHitboxes
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ModelNodeEntry
import ru.hollowhorizon.hollowengine.common.attachments.binding.NodeRuntimeState
import java.util.UUID
import java.util.function.Predicate
import java.util.function.BooleanSupplier

/** Server-authoritative picking; the pose evaluator is shared with the model renderer. */
object ModelHitboxes {
    private val areaDamageDepth = ThreadLocal.withInitial { 0 }
    private val positionalSources = setOf("player", "mob", "arrow", "trident", "thrown", "fireball", "witherSkull")

    /** Sweeping damage has no single impact point, even though vanilla uses the player damage type. */
    fun areaDamage(damage: BooleanSupplier): Boolean {
        val previous = areaDamageDepth.get()
        areaDamageDepth.set(previous + 1)
        return try { damage.asBoolean } finally { areaDamageDepth.set(previous) }
    }

    private data class PoseKey(val node: UUID, val model: String)
    private object ImpactKey
    private data class Impact(val target: UUID, val tick: Long, val multiplier: Float)

    private class Pose(val model: Model, val assets: ServerHitboxAssets.Assets) {
        val roots = model.scenes.getOrNull(model.scene)?.nodes.orEmpty().map {
            RuntimeNode(it, null, createMeshes = false)
        }
        val nodes = roots.flatMap { it.walk() }
        val target = PoseTarget(nodes.associateBy { it.definition.index }, model.animationsByName, assets.rig.boneByAlias)
        val animator = ModelAnimator()
        val context = AnimatorEvaluationContext()
        var tick = Int.MIN_VALUE

        fun boxes(entity: Entity, entry: ModelNodeEntry): List<HitboxGeometry> {
            val world = resolveNodeWorldTransform(entity, entry.transform, 1f)
            if (tick != entity.tickCount) {
                tick = entity.tickCount
                nodes.forEach(RuntimeNode::resetPose)
                animator.configure(assets.animator, entry.animations)
                fillAnimationVariables(context, entity, 1f)
                context.modelToWorld = world
                animator.applyTo(target, context)
                roots.forEach(RuntimeNode::updateHierarchyMatrices)
            }
            return assets.rig.hitboxes(roots, world.matrixF).map { it.geometry }
        }
    }

    fun boxes(entity: Entity): List<HitboxGeometry> {
        if (entity.level().isClientSide) return ClientModelHitboxes.boxes(entity)
        return buildList {
            NodeRuntimeState.service(entity.level()).forEachModelNodeOf(entity) { _, entry ->
                val assets = ServerHitboxAssets.of(entry.model.model)
                if (!assets.hasHitboxes) return@forEachModelNodeOf
                val model = ServerModelAnimationMetadata.model(entry.model.model) ?: return@forEachModelNodeOf
                val runtime = AttachmentRegistry.attachments(entity).runtime
                val key = PoseKey(entry.nodeId, entry.model.model)
                var pose = runtime.getOrPut(key) { Pose(model, assets) }
                if (pose.model !== model || pose.assets !== assets) {
                    runtime.remove(key)
                    pose = runtime.getOrPut(key) { Pose(model, assets) }
                }
                addAll(pose.boxes(entity, entry))
            }
        }
    }

    fun hasHitboxes(entity: Entity): Boolean = boxes(entity).isNotEmpty()

    /** Runs for every model entity, so limbs outside the vanilla box can also be hit. */
    fun pick(
        level: Level, source: Entity?, start: Vec3, end: Vec3,
        predicate: Predicate<Entity>, maxDistanceSquared: Double, vanilla: EntityHitResult?,
    ): EntityHitResult? {
        var closest = vanilla
        var selected: HitboxGeometry? = null
        var distance = minOf(maxDistanceSquared, vanilla?.location?.distanceToSqr(start) ?: Double.POSITIVE_INFINITY)
        NodeRuntimeState.service(level).records.forEach { record ->
            val entity = record.hostEntity ?: return@forEach
            if (entity === source || entity.isRemoved || !predicate.test(entity)) return@forEach
            if (source != null && entity.rootVehicle === source.rootVehicle) return@forEach
            val hit = boxes(entity).trace(start, end) ?: return@forEach
            val candidate = hit.location.distanceToSqr(start)
            if (candidate <= distance) {
                closest = EntityHitResult(entity, hit.location)
                selected = hit.geometry
                distance = candidate
            }
        }
        if (source is Projectile && !level.isClientSide) {
            val runtime = AttachmentRegistry.attachments(source).runtime
            runtime.remove(ImpactKey)
            val hit = closest
            val box = selected
            if (hit != null && box != null) runtime.getOrPut(ImpactKey) {
                Impact(hit.entity.uuid, level.gameTime, box.spec.damageMultiplier)
            }
        }
        return closest
    }

    /** Negative means a targeted attack missed. Environmental/area damage keeps vanilla behavior. */
    fun damage(entity: Entity, source: DamageSource, amount: Float): Float {
        if (entity.level().isClientSide || amount <= 0f || areaDamageDepth.get() > 0) return amount
        val attacker = source.directEntity ?: return amount
        if (attacker !is Projectile && attacker !is LivingEntity) return amount
        // Explosions, thorns and magic do not carry a point of impact.
        val id = source.msgId
        if (id !in positionalSources) return amount
        if (attacker is Projectile) {
            val runtime = AttachmentRegistry.attachments(attacker).runtime
            val impact = runtime.getOrNull<Impact>(ImpactKey)
            runtime.remove(ImpactKey)
            if (impact?.target == entity.uuid && impact.tick == entity.level().gameTime) return amount * impact.multiplier
        }
        val boxes = boxes(entity)
        if (boxes.isEmpty()) return amount
        val start: Vec3
        val end: Vec3
        when (attacker) {
            is Projectile -> {
                val movement = attacker.deltaMovement
                start = attacker.position().subtract(movement)
                end = attacker.position().add(movement)
            }
            is Player -> {
                start = attacker.eyePosition
                end = start.add(attacker.getViewVector(1f).scale(attacker.entityInteractionRange()))
            }
            else -> {
                start = attacker.eyePosition
                end = entity.boundingBox.center
            }
        }
        val blocked = entity.level().clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker)).location
        val hit = boxes.trace(start, blocked) ?: return -1f
        return amount * hit.geometry.spec.damageMultiplier
    }
}
