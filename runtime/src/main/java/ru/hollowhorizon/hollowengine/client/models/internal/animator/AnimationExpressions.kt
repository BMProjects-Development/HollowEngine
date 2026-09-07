package ru.hollowhorizon.hollowengine.client.models.internal.animator

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.coroutines.scopeAsync
import ru.hollowhorizon.hollowengine.common.utils.expressions.*
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private typealias Ctx = MembersBuilder<AnimatorEvaluationContext>

private fun Ctx.entityFloat(name: String, read: (Entity) -> Float) {
    float(name) { context -> context.entity?.let(read) ?: context.override(name) }
}

private fun Ctx.entityBool(name: String, read: (Entity) -> Boolean) {
    bool(name) { context -> context.entity?.let(read) ?: (context.override(name) != 0f) }
}

private fun Ctx.livingFloat(name: String, read: (LivingEntity) -> Float) {
    float(name) { context -> context.living?.let(read) ?: context.override(name) }
}

private fun Ctx.livingBool(name: String, read: (LivingEntity) -> Boolean) {
    bool(name) { context -> context.living?.let(read) ?: (context.override(name) != 0f) }
}

private fun Ctx.contextFloat(name: String, read: (AnimatorEvaluationContext) -> Float) {
    float(name, read = read)
}

val AnimationDeclarations: Declarations<AnimatorEvaluationContext> = Declarations {
    val query = struct("query") {
        contextFloat("partial_tick") { it.partialTick }
        contextFloat("game_time") { it.gameTime }
        contextFloat("life_time") { it.time }
        contextFloat("age") { it.time }
        contextFloat("anim_time") { it.time }
        contextFloat("layer_time") { it.layerTime }
        contextFloat("layer_age") { it.layerAge }
        contextFloat("layer_weight") { it.layerWeight }
        contextFloat("state_time") { it.stateTime }
        contextFloat("horizontal_speed") { it.horizontalSpeed }
        contextFloat("local_forward_speed") { it.localForwardSpeed }
        contextFloat("local_side_speed") { it.localSideSpeed }
        contextFloat("signed_horizontal_speed") { it.signedHorizontalSpeed }
        contextFloat("movement_animation_speed") { it.signedHorizontalSpeed }
        contextFloat("ground_speed") { it.signedHorizontalSpeed }

        entityFloat("entity_id") { it.id.toFloat() }
        entityBool("is_alive") { it.isAlive }
        entityBool("is_on_ground") { it.onGround() }
        entityFloat("velocity_x") { it.deltaMovement.x.toFloat() }
        entityFloat("velocity_y") { it.deltaMovement.y.toFloat() }
        entityFloat("velocity_z") { it.deltaMovement.z.toFloat() }
        entityFloat("fall_distance") { it.fallDistance }
        entityBool("is_in_water") { it.isInWater }
        entityBool("is_under_water") { it.isUnderWater }
        entityBool("is_in_lava") { it.isInLava }
        entityBool("is_on_fire") { it.isOnFire }
        entityFloat("remaining_fire_ticks") { it.remainingFireTicks.toFloat() }
        entityBool("is_crouching") { it.isCrouching }
        entityBool("is_swimming") { it.isSwimming }
        entityBool("is_visually_swimming") { it.isVisuallySwimming }
        entityBool("is_visually_crawling") { it.isVisuallyCrawling }
        entityBool("is_invisible") { it.isInvisible }
        entityBool("is_glowing") { it.isCurrentlyGlowing }
        entityBool("is_passenger") { it.isPassenger }
        entityBool("is_vehicle") { it.isVehicle }
        entityBool("is_no_gravity") { it.isNoGravity }
        entityBool("is_in_wall") { it.isInWall }
        entityBool("is_shift_key_down") { it.isShiftKeyDown }
        entityBool("is_sneaking") { it.isShiftKeyDown }
        entityFloat("eye_height") { it.eyeHeight }
        entityFloat("bbox_width") { it.bbWidth }
        entityFloat("bbox_height") { it.bbHeight }
        entityBool("horizontal_collision") { it.horizontalCollision }
        entityBool("vertical_collision") { it.verticalCollision }
        entityBool("vertical_collision_below") { it.verticalCollisionBelow }
        entityFloat("move_dist") { it.moveDist }
        entityFloat("fly_dist") { it.flyDist }
        entityFloat("invulnerable_time") { it.invulnerableTime.toFloat() }
        entityFloat("ticks_frozen") { it.ticksFrozen.toFloat() }
        entityFloat("percent_frozen") { it.percentFrozen }
        entityBool("is_fully_frozen") { it.isFullyFrozen }
        entityFloat("air_supply") { it.airSupply.toFloat() }
        entityFloat("max_air_supply") { it.maxAirSupply.toFloat() }
        entityBool("has_custom_name") { it.hasCustomName() }

        contextFloat("head_y_rotation") { it.headYaw }
        contextFloat("head_x_rotation") { it.headPitch }
        contextFloat("body_y_rotation") { it.bodyYaw }
        contextFloat("head_body_y_delta") { it.headBodyYawDelta }
        livingFloat("hurt_time") { it.hurtTime.toFloat() }
        livingBool("is_sprinting") { it.isSprinting }
        livingFloat("health") { it.health }
        livingFloat("max_health") { it.maxHealth }
        livingFloat("health_ratio") { it.health / it.maxHealth.coerceAtLeast(1f) }
        livingBool("is_dead_or_dying") { it.isDeadOrDying }
        livingFloat("death_time") { it.deathTime.toFloat() }
        livingFloat("death_progress") { it.deathTime.toFloat() / LivingEntity.DEATH_DURATION.toFloat() }
        livingFloat("hurt_duration") { it.hurtDuration.toFloat() }
        livingFloat("armor_value") { it.armorValue.toFloat() }
        livingFloat("absorption_amount") { it.absorptionAmount }
        livingFloat("max_absorption") { it.maxAbsorption }
        livingBool("is_using_item") { it.isUsingItem }
        livingFloat("use_item_remaining_ticks") { it.useItemRemainingTicks.toFloat() }
        livingFloat("ticks_using_item") { it.ticksUsingItem.toFloat() }
        livingBool("is_blocking") { it.isBlocking }
        livingFloat("swing_time") { it.swingTime.toFloat() }
        livingBool("is_swinging") { it.swinging }
        livingBool("is_fall_flying") { it.isFallFlying }
        livingFloat("fall_flying_ticks") { it.fallFlyingTicks.toFloat() }
        livingBool("is_autospin_attack") { it.isAutoSpinAttack }
        livingBool("is_climbing") { it.onClimbable() }
        livingBool("is_sleeping") { it.isSleeping }
        livingFloat("no_jump_delay") { it.noJumpDelay.toFloat() }
        livingFloat("jump_boost_power") { it.jumpBoostPower }
        livingFloat("speed") { it.speed }
        livingBool("is_sensitive_to_water") { it.isSensitiveToWater }
        livingFloat("walk_animation_speed") { it.walkAnimation.speed() }
        livingFloat("walk_animation_position") { it.walkAnimation.position() }
        livingBool("is_moving") { abs(it.deltaMovement.horizontalDistance()) > 1.0e-4 }
    }

    val math = mathNamespace()

    val variables = dynamic(
        name = "variables",
        read = { owner, key -> (owner as AnimatorEvaluationContext).variables[key] ?: 0f },
        write = { owner, key, value ->
            (owner as AnimatorEvaluationContext).variables[key] = Coercions.toFloat(value)
        },
    )

    val temporaries = dynamic(
        name = "temporaries",
        read = { owner, key -> (owner as AnimatorEvaluationContext).temporaries[key] ?: 0f },
        write = { owner, key, value ->
            (owner as AnimatorEvaluationContext).temporaries[key] = Coercions.toFloat(value)
        },
    )

    val entityData = dynamic(
        name = "data",
        read = { owner, key -> (owner as AnimatorEvaluationContext).data[key] ?: 0f },
        nested = true,
    )

    property("query", query, alias = "q") { it }
    property("math", math) { it }
    property("variable", variables, alias = "v") { it }
    property("temp", temporaries, alias = "t") { it }
    property("data", entityData, alias = "d") { it }
    receiver("query")
    receiver("math")
}

val AnimationExpressionLanguage: Expression<AnimatorEvaluationContext> = Expression {
    options {
        unresolvedReferences(
            References.warnWithDefault(0f)
        )
    }
    declarations(AnimationDeclarations)
}

class AnimatorEvaluationContext {
    var entity: Entity? = null
    val living: LivingEntity? get() = entity as? LivingEntity
    var modelToWorld: TrsTransformF? = null

    var partialTick: Float = 0f
    var gameTime: Float = 0f
    var time: Float = 0f
    var deltaTime: Float = 0f

    var layerTime: Float = 0f
    var layerAge: Float = 0f
    var layerWeight: Float = 0f
    var stateTime: Float = 0f

    var horizontalSpeed: Float = 0f
    var localForwardSpeed: Float = 0f
    var localSideSpeed: Float = 0f
    var signedHorizontalSpeed: Float = 0f

    var bodyYaw: Float = 0f
    var headYaw: Float = 0f
    var headPitch: Float = 0f
    var headBodyYawDelta: Float = 0f

    val variables = HashMap<String, Float>()

    /** Molang scratch space: `t.` values live for one frame, unlike `v.` which persists. */
    val temporaries = HashMap<String, Float>()

    /**
     * The numeric leaves of the entity's data document by path, refilled once per frame. Server-owned
     * and read-only here; `d.` reads it, `v.` does not.
     */
    var data: Map<String, Float> = emptyMap()

    /**
     * The value of an entity-derived name when there is no entity, as in a model preview: whatever was
     * put in [variables] under that name, or zero.
     */
    internal fun override(name: String): Float = variables[name] ?: 0f
}

/**
 * Evaluates the expressions of an [Animator].
 */
object AnimatorExpressionEvaluator {
    private val baked = ConcurrentHashMap<String, FloatExpression<AnimatorEvaluationContext>>()
    private val bakedBooleans = ConcurrentHashMap<String, BoolExpression<AnimatorEvaluationContext>>()
    private val preparing = ConcurrentHashMap.newKeySet<String>()

    fun float(expression: AnimationExpression, context: AnimatorEvaluationContext, default: Float = 0f): Float {
        val source = expression.source
        if (source.isBlank()) return default
        source.toFloatOrNull()?.let { return it }
        val ready = baked[source] ?: run { bakeLater(source); return default }
        return ready.eval(context)
    }

    fun boolean(
        expression: AnimationExpression,
        context: AnimatorEvaluationContext,
        default: Boolean = false,
    ): Boolean {
        val source = expression.source
        if (source.isBlank()) return default
        val ready = bakedBooleans[source] ?: run { bakeLater(source); return default }
        return ready.eval(context)
    }

    fun vector(expression: AnimationVectorExpression, context: AnimatorEvaluationContext): Vec3f = Vec3f(
        float(expression.x, context),
        float(expression.y, context),
        float(expression.z, context),
    )

    /** Bakes every expression of [layers] in one background pass. */
    fun prepare(layers: List<AnimatorLayerSpec>) {
        bakeLater(sourcesOf(layers))
    }

    /** Bakes and waits. For callers already off the render thread, such as resource loading. */
    fun prepareNow(animator: Animator) = prepareNow(sourcesOf(animator.layers))

    fun prepareNow(sources: Collection<String>) {
        val pending = sources.filter { it.isNotBlank() && it.toFloatOrNull() == null }
        preparing.addAll(pending)
        compile(pending)
    }

    private fun sourcesOf(layers: List<AnimatorLayerSpec>): List<String> {
        val sources = LinkedHashSet<String>()
        layers.forEach { layer -> layer.expressions().forEach { sources += it.source } }
        return sources.filter { it.isNotBlank() && it.toFloatOrNull() == null }
    }

    fun clear() {
        baked.clear()
        bakedBooleans.clear()
        preparing.clear()
    }

    private fun bakeLater(source: String) = bakeLater(listOf(source))

    private fun bakeLater(sources: Collection<String>) {
        val pending = sources.filter { preparing.add(it) }
        if (pending.isEmpty()) return

        scopeAsync { compile(pending) }
    }

    /** One class for the whole batch, so an animator costs a single class however many layers it has. */
    private fun compile(sources: Collection<String>) {
        if (sources.isEmpty()) return

        val unit = AnimationExpressionLanguage.compile(sources)
        unit.sources.forEach { source ->
            unit.diagnostics(source).forEach { diagnostic ->
                HollowEngine.LOGGER.warn("Animation expression '{}': {}", source, diagnostic)
            }
            baked[source] = unit.float(source)
            bakedBooleans[source] = unit.bool(source)
        }
    }
}
