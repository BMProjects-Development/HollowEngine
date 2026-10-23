package ru.hollowhorizon.hollowengine.client.models.internal.animator

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.FlyingAnimal
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.client.models.bedrock.Query
import ru.hollowhorizon.hollowengine.common.models.MontageMoment
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import kotlin.math.abs

/** Entity queries shared by animator conditions, imported Molang and particle effects. */
class AnimatorEvaluationContext() : Query {
    constructor(entity: Entity, partialTick: Float = 0f) : this() {
        fillAnimationVariables(this, entity, partialTick)
    }

    var entity: Entity? = null
    val living: LivingEntity? get() = entity as? LivingEntity
    var modelToWorld: TrsTransformF? = null

    var partialTick: Float = 0f
    var gameTicks: Long = 0L
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
    var flightPitch: Float = 0f
    var flightBank: Float = 0f
    var bodyYaw: Float = 0f
    var headYaw: Float = 0f
    var headPitch: Float = 0f
    var headBodyYawDelta: Float = 0f

    val variables = HashMap<String, Float>()
    internal var modelVariables: MutableMap<String, Float>? = null

    /** Imported animation expressions share this frame's queries and scratch space. */
    val molang by lazy { animationMolangContext(this) }

    internal fun variable(name: String): Float = variables[name] ?: modelVariables?.get(name) ?: 0f
    internal fun hasVariable(name: String): Boolean = name in variables || modelVariables?.containsKey(name) == true

    internal fun setVariable(name: String, value: Float) {
        val storage = modelVariables?.takeUnless { name in variables } ?: variables
        storage[name] = value
    }

    /** `t.` scratch values live for one frame; model-owned `v.` values persist. */
    val temporaries = HashMap<String, Float>()

    /** Numeric entity data by path, refilled once per frame and read through `d.`. */
    var data: Map<String, Float> = emptyMap()
    val montages = ArrayList<MontageMoment>()
    val isClientSide: Boolean get() = entity?.level()?.isClientSide ?: true

    /** Entity-free previews can supply query values through named variables. */
    internal fun override(name: String): Float = variable(name)

    fun secondsSince(gameTick: Long): Float = ((gameTicks - gameTick).toFloat() + partialTick) / TICKS_PER_SECOND

    private fun entityFloat(name: String, read: (Entity) -> Float): Float = entity?.let(read) ?: override(name)
    private fun livingFloat(name: String, read: (LivingEntity) -> Float): Float = living?.let(read) ?: override(name)
    private fun entityBool(name: String, default: Boolean = false, read: (Entity) -> Boolean): Boolean =
        entity?.let(read) ?: if (hasVariable(name)) override(name) != 0f else default
    private fun livingBool(name: String, read: (LivingEntity) -> Boolean): Boolean =
        living?.let(read) ?: (override(name) != 0f)

    /** Molang time is in seconds; animator-specific tick clocks remain available through [time]. */
    override val anim_time: Float get() = layerTime
    override val life_time: Float get() = time / TICKS_PER_SECOND
    override val ground_speed: Float
        get() = if (entity == null && hasVariable("ground_speed")) override("ground_speed") else signedHorizontalSpeed
    override val is_moving: Boolean
        get() = entityBool("is_moving") { abs(ground_speed) >= MOVEMENT_FACTOR }
    override val velocity_x: Float get() = entityFloat("velocity_x") { it.deltaMovement.x.toFloat() }
    override val velocity_y: Float get() = entityFloat("velocity_y") { it.deltaMovement.y.toFloat() }
    override val velocity_z: Float get() = entityFloat("velocity_z") { it.deltaMovement.z.toFloat() }
    override val vertical_speed: Float get() = entityFloat("vertical_speed") { it.deltaMovement.y.toFloat() * TICKS_PER_SECOND }
    override val health: Float get() = livingFloat("health") { it.health }
    override val max_health: Float get() = livingFloat("max_health") { it.maxHealth }
    override val is_alive: Boolean get() = entityBool("is_alive", default = true) { it.isAlive }
    override val is_on_ground: Boolean get() = entityBool("is_on_ground", default = true) { it.onGround() }
    override val is_sneaking: Boolean get() = entityBool("is_sneaking") { it.isShiftKeyDown }
    override val is_sprinting: Boolean get() = entityBool("is_sprinting") { it.isSprinting }
    override val is_jumping: Boolean get() = livingBool("is_jumping") { it.jumping }
    override val is_swimming: Boolean get() = entityBool("is_swimming") { it.isSwimming }
    override val is_in_water: Boolean get() = entityBool("is_in_water") { it.isInWater }
    override val is_in_water_or_rain: Boolean get() = entityBool("is_in_water_or_rain") { it.isInWaterOrRain }
    override val is_sitting: Boolean get() = entityBool("is_sitting") { it.isPassenger }
    override val is_sleeping: Boolean get() = livingBool("is_sleeping") { it.isSleeping }
    override val is_hurt: Boolean get() = livingBool("is_hurt") { it.hurtTime > 0 }
    override val is_swinging: Boolean get() = livingBool("is_swinging") { it.swinging }
    override val fall_ticks: Float get() = livingFloat("fall_ticks") { it.fallFlyingTicks.toFloat() }
    override val is_flying: Boolean get() = entityBool("is_flying") {
        when (it) {
            is Player -> it.abilities.flying
            is FlyingAnimal -> it.isFlying
            else -> it.isNoGravity && !it.onGround()
        }
    }
    override val head_x_rotation: Float
        get() = if (entity == null && hasVariable("head_x_rotation")) override("head_x_rotation") else headPitch
    override val head_y_rotation: Float
        get() = if (entity == null && hasVariable("head_y_rotation")) override("head_y_rotation") else headYaw
    override val modified_distance_moved: Float get() = entityFloat("modified_distance_moved") { it.moveDist }
    override val modified_move_speed: Float get() = ground_speed

    private companion object {
        const val TICKS_PER_SECOND = 20f
        const val MOVEMENT_FACTOR = 1 / 256f
    }
}
