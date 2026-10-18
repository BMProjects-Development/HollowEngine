package ru.hollowhorizon.hollowengine.client.models.bedrock

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.FlyingAnimal
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.animator.MOVEMENT_FACTOR
import ru.hollowhorizon.hollowengine.client.models.internal.animator.calculateSpeedViaDeltaMovement
import ru.hollowhorizon.hollowengine.common.utils.expressions.*
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs
import kotlin.reflect.KProperty

/**
 * What `q.` reads in a Bedrock file: one value source, implemented by whatever the expression is being
 * evaluated for.
 */
interface Query {
    val ground_speed: Float get() = 0f
    val is_moving: Boolean get() = false
    val is_sneaking: Boolean get() = false
    val is_sprinting: Boolean get() = false
    val is_jumping: Boolean get() = false
    val velocity_y: Float get() = 0f
    val velocity_x: Float get() = 0f
    val velocity_z: Float get() = 0f

    /** Vertical speed in blocks per second (same unit convention as [ground_speed]). */
    val vertical_speed: Float get() = 0f
    val health: Float get() = 0f
    val max_health: Float get() = 0f
    val is_flying: Boolean get() = false
    val fall_ticks: Float get() = 0f
    val is_swimming: Boolean get() = false
    val is_in_water: Boolean get() = false
    val is_in_water_or_rain: Boolean get() = false
    val is_sitting: Boolean get() = false
    val is_sleeping: Boolean get() = false
    val is_hurt: Boolean get() = false
    val is_swinging: Boolean get() = false
    val is_alive: Boolean get() = true
    val is_on_ground: Boolean get() = true
    val head_x_rotation: Float get() = 0f
    val head_y_rotation: Float get() = 0f
    val anim_time: Float get() = 0f
    val life_time: Float get() = 0f
    val modified_distance_moved: Float get() = 0f
    val modified_move_speed: Float get() = 0f

    companion object {
        val EMPTY = object : Query {}
        val GLFW_TIME = object : Query {
            override val anim_time: Float
                get() = GLFW.glfwGetTime().toFloat()
        }
    }
}

class LivingEntityQuery(val entity: LivingEntity) : Query {
    override val ground_speed: Float get() = calculateSpeedViaDeltaMovement(entity)
    override val is_moving: Boolean get() = abs(ground_speed) >= MOVEMENT_FACTOR
    override val is_sneaking: Boolean get() = entity.isShiftKeyDown
    override val is_sprinting: Boolean get() = entity.isSprinting
    override val is_jumping: Boolean get() = entity.jumping
    override val velocity_x: Float get() = entity.deltaMovement.x.toFloat()
    override val velocity_y: Float get() = entity.deltaMovement.y.toFloat()
    override val velocity_z: Float get() = entity.deltaMovement.z.toFloat()
    override val vertical_speed: Float get() = entity.deltaMovement.y.toFloat() * 20f
    override val health: Float get() = entity.health
    override val max_health: Float get() = entity.maxHealth
    override val is_flying: Boolean get() = entity is FlyingAnimal && entity.isFlying
    override val fall_ticks: Float get() = entity.fallFlyingTicks.toFloat()
    override val is_swimming: Boolean get() = entity.isSwimming
    override val is_in_water: Boolean get() = entity.isInWater
    override val is_in_water_or_rain: Boolean get() = entity.isInWaterOrRain
    override val is_sitting: Boolean get() = entity.vehicle != null
    override val is_sleeping: Boolean get() = entity.isSleeping
    override val is_hurt: Boolean get() = entity.hurtTime > 0
    override val is_swinging: Boolean get() = entity.swinging
    override val is_alive: Boolean get() = entity.isAlive
    override val is_on_ground: Boolean get() = entity.onGround()
    override val head_x_rotation: Float get() = entity.xRot
    override val head_y_rotation: Float get() = entity.yHeadRot
    override val anim_time: Float get() = entity.tickCount + TickHandler.partialTick
    override val life_time: Float get() = entity.tickCount.toFloat()
    override val modified_distance_moved: Float get() = 0f
    override val modified_move_speed: Float get() = ground_speed
}

/** `v.` storage: named floats a file writes and reads back, and the engine seeds. */
interface Variables {
    fun getOrNull(name: String): Variable?
    fun getOrPut(name: String, initialValue: Float = 0f): Variable
    operator fun get(name: String): Float = getOrNull(name)?.get() ?: Float.NaN
    operator fun set(name: String, value: Float) = getOrPut(name).set(value)
    fun fallbackBackTo(fallback: Variables): Variables = VariablesWithFallback(this, fallback)

    interface Variable {
        fun get(): Float
        fun set(value: Float)

        operator fun getValue(thisRef: Any?, property: KProperty<*>): Float = get()
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) = set(value)
    }
}

class VariablesMap : Variables {
    private val map = mutableMapOf<String, Variable>()

    override fun getOrNull(name: String): Variables.Variable? = map[name]

    override fun getOrPut(name: String, initialValue: Float): Variables.Variable =
        map.getOrPut(name) { Variable(initialValue) }

    private class Variable(var field: Float) : Variables.Variable {
        override fun get(): Float = field
        override fun set(value: Float) {
            field = value
        }
    }
}

private class VariablesWithFallback(val primary: Variables, val fallback: Variables) : Variables {
    override fun getOrNull(name: String): Variables.Variable? = primary.getOrNull(name) ?: fallback.getOrNull(name)

    override fun getOrPut(name: String, initialValue: Float): Variables.Variable =
        getOrNull(name) ?: primary.getOrPut(name, initialValue)
}

/** Everything a Bedrock expression can read: one query and one bag of variables. */
data class BedrockContext(val query: Query, val variables: Variables = VariablesMap()) {
    /** `t.` scratch space, cleared by whoever owns the frame. */
    val temporaries = HashMap<String, Float>()

    companion object {
        val EMPTY = BedrockContext(Query.EMPTY)
    }
}

/**
 * The Bedrock flavour of the expression language, better known as Molang.
 *
 * Permissive on purpose: a resource pack names queries this engine has never heard of, and a model that
 * loses one bone rotation is better than a model that does not load. An unknown name warns once and
 * reads as zero.
 */
val BedrockDeclarations: Declarations<BedrockContext> = Declarations {
    val query = struct<BedrockContext>("query") {
        float("ground_speed") { it.query.ground_speed }
        float("velocity_x") { it.query.velocity_x }
        float("velocity_y") { it.query.velocity_y }
        float("velocity_z") { it.query.velocity_z }
        float("vertical_speed") { it.query.vertical_speed }
        float("health") { it.query.health }
        float("max_health") { it.query.max_health }
        float("fall_ticks") { it.query.fall_ticks }
        float("head_x_rotation") { it.query.head_x_rotation }
        float("head_y_rotation") { it.query.head_y_rotation }
        float("anim_time") { it.query.anim_time }
        float("life_time") { it.query.life_time }
        float("modified_distance_moved") { it.query.modified_distance_moved }
        float("modified_move_speed") { it.query.modified_move_speed }

        bool("is_moving") { it.query.is_moving }
        bool("is_sneaking") { it.query.is_sneaking }
        bool("is_sprinting") { it.query.is_sprinting }
        bool("is_jumping") { it.query.is_jumping }
        bool("is_flying") { it.query.is_flying }
        bool("is_swimming") { it.query.is_swimming }
        bool("is_in_water") { it.query.is_in_water }
        bool("is_in_water_or_rain") { it.query.is_in_water_or_rain }
        bool("is_sitting") { it.query.is_sitting }
        bool("is_sleeping") { it.query.is_sleeping }
        bool("is_hurt") { it.query.is_hurt }
        bool("is_swinging") { it.query.is_swinging }
        bool("is_alive") { it.query.is_alive }
        bool("is_on_ground") { it.query.is_on_ground }
    }

    val math = mathNamespace()

    val variables = dynamic(
        name = "variables",
        read = { owner, key -> (owner as BedrockContext).variables[key].takeUnless(Float::isNaN) ?: 0f },
        write = { owner, key, value -> (owner as BedrockContext).variables[key] = value.asFloat() },
    )

    val temporaries = dynamic(
        name = "temporaries",
        read = { owner, key -> (owner as BedrockContext).temporaries[key] ?: 0f },
        write = { owner, key, value -> (owner as BedrockContext).temporaries[key] = value.asFloat() },
    )

    property("query", query, alias = "q") { it }
    property("math", math) { it }
    property("variable", variables, alias = "v") { it }
    property("temp", temporaries, alias = "t") { it }
    receiver("query")
    receiver("math")
}

val BedrockLanguage: Expression<BedrockContext> = Expression {
    options { unresolvedReferences(References.warnWithDefault(0f)) }
    declarations(BedrockDeclarations)
}

private fun Any?.asFloat(): Float = (this as? Number)?.toFloat() ?: 0f

/**
 * One expression of a Bedrock file.
 *
 * Starts out interpreted and is swapped for compiled bytecode when the file it belongs to finishes
 * loading, see [BedrockExpressions.batch]. A particle evaluates dozens of these per particle per frame,
 * which is the case the bytecode backend exists for.
 */
@Serializable(FloatExprSerializer::class)
class FloatExpr private constructor(
    private val source: String?,
    @Volatile private var evaluator: FloatExpression<BedrockContext>,
) {
    fun eval(context: BedrockContext): Float = evaluator.eval(context)

    internal fun compiled(): String? = source

    internal fun replaceWith(compiled: FloatExpression<BedrockContext>) {
        evaluator = compiled
    }

    companion object {
        fun literal(value: Float): FloatExpr = FloatExpr(null, FloatExpression { value })
        val ZERO = literal(0f)
        val ONE = literal(1f)

        internal fun of(source: String, evaluator: FloatExpression<BedrockContext>) = FloatExpr(source, evaluator)
    }
}

@Serializable(FloatVec3ExprSerializer::class)
class FloatVec3Expr(val x: FloatExpr, val y: FloatExpr, val z: FloatExpr) {
    fun eval(context: BedrockContext) = Vec3f(x.eval(context), y.eval(context), z.eval(context))

    companion object {
        val ZERO = FloatVec3Expr(FloatExpr.ZERO, FloatExpr.ZERO, FloatExpr.ZERO)
        val UNIT_X = FloatVec3Expr(FloatExpr.ONE, FloatExpr.ZERO, FloatExpr.ZERO)
        val UNIT_Y = FloatVec3Expr(FloatExpr.ZERO, FloatExpr.ONE, FloatExpr.ZERO)
        val UNIT_Z = FloatVec3Expr(FloatExpr.ZERO, FloatExpr.ZERO, FloatExpr.ONE)
    }
}

object BedrockExpressions {
    private val open = ThreadLocal<MutableList<FloatExpr>?>()

    /**
     * Runs [load] and compiles every expression it parsed as one class.
     *
     * The expressions arrive one at a time through the deserializer, so the batch is collected here
     * rather than passed around: a whole particle file ends up in a single generated class instead of
     * one per curve.
     */
    fun <T> batch(load: () -> T): T {
        val collected = mutableListOf<FloatExpr>()
        val previous = open.get()
        open.set(collected)
        val result = try {
            load()
        } finally {
            open.set(previous)
        }

        val sources = collected.mapNotNull { it.compiled() }.distinct()
        if (sources.isEmpty()) return result

        val unit = BedrockLanguage.compile(sources)
        unit.sources.forEach { source ->
            unit.diagnostics(source).forEach { HollowEngine.LOGGER.warn("Bedrock expression '{}': {}", source, it) }
        }
        collected.forEach { expression ->
            expression.compiled()?.let { expression.replaceWith(unit.float(it)) }
        }
        return result
    }

    fun parse(source: String): FloatExpr {
        val baked = BedrockLanguage.bake(source)
        baked.diagnostics.forEach { HollowEngine.LOGGER.warn("Bedrock expression '{}': {}", source, it) }
        return FloatExpr.of(source, baked.asFloat()).also { open.get()?.add(it) }
    }
}

fun JsonPrimitive.parseBedrockExpression(): FloatExpr = BedrockExpressions.parse(content)

object FloatExprSerializer : KSerializer<FloatExpr> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): FloatExpr =
        ((decoder as JsonDecoder).decodeJsonElement() as JsonPrimitive).parseBedrockExpression()

    override fun serialize(encoder: Encoder, value: FloatExpr) =
        throw UnsupportedOperationException("Bedrock expressions are read-only")
}

object FloatVec3ExprSerializer : KSerializer<FloatVec3Expr> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): FloatVec3Expr = parse((decoder as JsonDecoder).decodeJsonElement())
    override fun serialize(encoder: Encoder, value: FloatVec3Expr) = throw UnsupportedOperationException()

    private fun parse(json: JsonElement): FloatVec3Expr = when (json) {
        is JsonArray -> {
            val first = (json[0] as JsonPrimitive).parseBedrockExpression()
            val second = (json.getOrNull(1) as JsonPrimitive?)?.parseBedrockExpression() ?: first
            val third = (json.getOrNull(2) as JsonPrimitive?)?.parseBedrockExpression() ?: second
            FloatVec3Expr(first, second, third)
        }

        is JsonPrimitive -> when (json.content) {
            "x" -> FloatVec3Expr.UNIT_X
            "y" -> FloatVec3Expr.UNIT_Y
            "z" -> FloatVec3Expr.UNIT_Z
            else -> json.parseBedrockExpression().let { FloatVec3Expr(it, it, it) }
        }

        else -> throw SerializationException("Expected array or primitive, got $json")
    }
}
