package ru.hollowhorizon.hollowengine.client.models.internal.animator

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.scopeAsync
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.expressions.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.concurrent.ConcurrentHashMap

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
        float("ground_speed") { it.molang.queryFloat("ground_speed") { it.ground_speed } }
        float("vertical_speed") { it.molang.queryFloat("vertical_speed") { it.vertical_speed } }
        float("modified_distance_moved") { it.molang.queryFloat("modified_distance_moved") { it.modified_distance_moved } }
        float("modified_move_speed") { it.molang.queryFloat("modified_move_speed") { it.modified_move_speed } }
        float("fall_ticks") { it.molang.queryFloat("fall_ticks") { it.fall_ticks } }
        bool("is_jumping") { it.molang.queryBool("is_jumping") { it.is_jumping } }
        bool("is_in_water_or_rain") { it.molang.queryBool("is_in_water_or_rain") { it.is_in_water_or_rain } }
        bool("is_sitting") { it.molang.queryBool("is_sitting") { it.is_sitting } }
        bool("is_hurt") { it.molang.queryBool("is_hurt") { it.is_hurt } }

        entityFloat("entity_id") { it.id.toFloat() }
        bool("is_alive") { it.molang.queryBool("is_alive") { it.is_alive } }
        bool("is_on_ground") { it.molang.queryBool("is_on_ground") { it.is_on_ground } }
        float("velocity_x") { it.molang.queryFloat("velocity_x") { it.velocity_x } }
        float("velocity_y") { it.molang.queryFloat("velocity_y") { it.velocity_y } }
        float("velocity_z") { it.molang.queryFloat("velocity_z") { it.velocity_z } }
        entityFloat("fall_distance") { it.fallDistance }
        bool("is_in_water") { it.molang.queryBool("is_in_water") { it.is_in_water } }
        entityBool("is_under_water") { it.isUnderWater }
        entityBool("is_in_lava") { it.isInLava }
        entityBool("is_on_fire") { it.isOnFire }
        entityFloat("remaining_fire_ticks") { it.remainingFireTicks.toFloat() }
        entityBool("is_crouching") { it.isCrouching }
        bool("is_swimming") { it.molang.queryBool("is_swimming") { it.is_swimming } }
        entityBool("is_visually_swimming") { it.isVisuallySwimming }
        entityBool("is_visually_crawling") { it.isVisuallyCrawling }
        entityBool("is_invisible") { it.isInvisible }
        entityBool("is_glowing") { it.isCurrentlyGlowing }
        entityBool("is_passenger") { it.isPassenger }
        entityBool("is_vehicle") { it.isVehicle }
        entityBool("is_no_gravity") { it.isNoGravity }
        bool("is_flying") { it.molang.queryBool("is_flying") { it.is_flying } }
        contextFloat("flight_pitch") { it.flightPitch }
        contextFloat("flight_bank") { it.flightBank }
        entityBool("is_in_wall") { it.isInWall }
        entityBool("is_shift_key_down") { it.isShiftKeyDown }
        bool("is_sneaking") { it.molang.queryBool("is_sneaking") { it.is_sneaking } }
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

        float("head_y_rotation") { it.molang.queryFloat("head_y_rotation") { it.head_y_rotation } }
        float("head_x_rotation") { it.molang.queryFloat("head_x_rotation") { it.head_x_rotation } }
        contextFloat("body_y_rotation") { it.bodyYaw }
        contextFloat("head_body_y_delta") { it.headBodyYawDelta }
        livingFloat("hurt_time") { it.hurtTime.toFloat() }
        bool("is_sprinting") { it.molang.queryBool("is_sprinting") { it.is_sprinting } }
        float("health") { it.molang.queryFloat("health") { it.health } }
        float("max_health") { it.molang.queryFloat("max_health") { it.max_health } }
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
        bool("is_swinging") { it.molang.queryBool("is_swinging") { it.is_swinging } }
        livingBool("is_fall_flying") { it.isFallFlying }
        livingFloat("fall_flying_ticks") { it.fallFlyingTicks.toFloat() }
        livingBool("is_autospin_attack") { it.isAutoSpinAttack }
        livingBool("is_climbing") { it.onClimbable() }
        bool("is_sleeping") { it.molang.queryBool("is_sleeping") { it.is_sleeping } }
        livingFloat("no_jump_delay") { it.noJumpDelay.toFloat() }
        livingFloat("jump_boost_power") { it.jumpBoostPower }
        livingFloat("speed") { it.speed }
        livingBool("is_sensitive_to_water") { it.isSensitiveToWater }
        livingFloat("walk_animation_speed") { it.walkAnimation.speed() }
        livingFloat("walk_animation_position") { it.walkAnimation.position() }
        bool("is_moving") { it.molang.queryBool("is_moving") { it.is_moving } }
    }

    val math = mathNamespace()

    val variables = dynamic(
        name = "variables",
        nested = true,
        read = { owner, key -> (owner as AnimatorEvaluationContext).variable(key) },
        write = { owner, key, value ->
            (owner as AnimatorEvaluationContext).setVariable(key, Coercions.toFloat(value))
        },
    )

    val temporaries = dynamic(
        name = "temporaries",
        nested = true,
        read = { owner, key -> (owner as AnimatorEvaluationContext).molang.temporary(key) },
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

    /**
     * Whether [source] holds, compiled on the spot the first time rather than in the background: a rule of a
     * section is asked once, at its boundary, and has no later frame to get it right.
     */
    fun condition(source: String, context: AnimatorEvaluationContext): Boolean {
        if (source.isBlank()) return true
        source.toFloatOrNull()?.let { return it != 0f }
        val ready = bakedBooleans[source] ?: run {
            prepareNow(listOf(source))
            bakedBooleans[source]
        } ?: return false
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
