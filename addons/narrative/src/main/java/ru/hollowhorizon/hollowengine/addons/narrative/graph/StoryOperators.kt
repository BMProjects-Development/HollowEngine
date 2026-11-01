package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.typeOf

/** What a pin of a node carries, which says what links to it and how its field writes Kotlin. */
enum class StoryPinType(val type: KType?) {
    FLOW(null),
    TEXT(typeOf<String>()),
    NUMBER(typeOf<Double>()),
    BOOL(typeOf<Boolean>()),
    VECTOR(typeOf<Vec3>()),
    ENTITY(typeOf<Entity>()),
    NPC(typeOf<NpcEntity>()),
    PLAYER(typeOf<ServerPlayer>()),
    LIST(null),
    MAP(null),

    /** A value of a class with no pin of its own; the pins say which, as they do not all fit each other. */
    OBJECT(null),
    ANY(null);

    /** What a value of this type is called in Kotlin, for telling the author. */
    val label: String
        get() = when (this) {
            FLOW, ANY, OBJECT -> "Any?"
            TEXT -> "String"
            NUMBER -> "Double"
            BOOL -> "Boolean"
            VECTOR -> "Vec3"
            ENTITY -> "Entity"
            NPC -> "NpcEntity"
            PLAYER -> "ServerPlayer"
            LIST -> "List<*>"
            MAP -> "Map<*, *>"
        }

    /** Whether a value of this type can be linked into a pin of [input]. */
    fun fits(input: StoryPinType): Boolean = this == input || (input == ENTITY && (this == NPC || this == PLAYER)) ||
            (this != FLOW && input != FLOW && (this == ANY || input == ANY))

    /** What is typed into a field of this type as Kotlin. */
    fun expression(text: String): String {
        if (type != null) return StoryLiterals.expression(text, type)
        val typed = text.trim()
        return when {
            typed.startsWith("{") && typed.endsWith("}") -> typed.substring(1, typed.length - 1)
            typed.isEmpty() || this != ANY -> typed
            typed == "true" || typed == "false" || typed.toDoubleOrNull() != null -> typed
            else -> StoryGraphCompiler.literal(text)
        }
    }

    /** How a field of this type shows the Kotlin [expression]. */
    fun text(expression: String): String {
        if (type != null) return StoryLiterals.text(expression, type)
        val written = expression.trim()
        return when {
            written.isEmpty() || written == "true" || written == "false" || written.toDoubleOrNull() != null -> written
            else -> StoryLiterals.text(written, null).takeIf { this == ANY } ?: written
        }
    }

    companion object {
        /** The pin type of a value whose type is written [name], as a node keeps it. */
        fun ofName(name: String?): StoryPinType {
            val simple = name?.removeSuffix("?")?.substringBefore('<')?.substringAfterLast('.') ?: return ANY
            return when {
                simple == "String" -> TEXT
                simple == "Boolean" -> BOOL
                simple in NUMBERS -> NUMBER
                simple == "Vec3" || simple == "BlockPos" -> VECTOR
                simple == "NpcEntity" -> NPC
                simple.endsWith("Player") -> PLAYER
                simple.endsWith("Entity") -> ENTITY
                simple == "List" || simple == "Set" -> LIST
                simple == "Map" -> MAP
                simple == "Any" -> ANY
                else -> OBJECT
            }
        }

        /** The Kotlin type written [name] with no packages, as the author reads it. */
        fun shortName(name: String): String = PACKAGE.replace(name, "").replace("!", "")

        private val PACKAGE = Regex("""\b[a-z_]\w*\.""")

        private val NUMBERS = setOf("Int", "Long", "Short", "Byte", "Float", "Double")

        /** The pin type of a value of the Kotlin type [type]. */
        fun of(type: KType?): StoryPinType {
            val kind = type?.classifier as? KClass<*> ?: return ANY
            return when {
                kind == String::class -> TEXT
                kind == Boolean::class -> BOOL
                kind.isSubclassOf(Number::class) -> NUMBER
                kind == Vec3::class || kind == BlockPos::class -> VECTOR
                kind.isSubclassOf(NpcEntity::class) -> NPC
                kind.isSubclassOf(Player::class) -> PLAYER
                kind.isSubclassOf(Entity::class) -> ENTITY
                kind.isSubclassOf(Collection::class) || kind == Iterable::class -> LIST
                kind.isSubclassOf(Map::class) -> MAP
                kind == Any::class -> ANY
                else -> OBJECT
            }
        }
    }
}

/** An input of a [StoryOperator]. [default] is the Kotlin it reads with nothing typed or linked. */
data class StoryOperatorInput(val name: String, val type: StoryPinType, val default: String = "")

/**
 * An output of a [StoryOperator]. In [template], `$name` stands for what the input of that name reads.
 *
 * @param kotlin its exact type, until analysis tells it for the inputs at hand.
 * @param empty what it reads with none of the inputs of a variadic operator given.
 */
data class StoryOperatorOutput(
    val name: String,
    val type: StoryPinType,
    val template: String,
    val kotlin: String? = null,
    val empty: String? = null,
)

/**
 * A value worked out on the spot: no flow goes through it, and every node reading it reads it anew.
 *
 * @param variadic whether the author can add inputs like the last of [inputs]; `$*` in a template stands for all of them.
 */
data class StoryOperator(
    val id: String,
    val category: String,
    val inputs: List<StoryOperatorInput>,
    val outputs: List<StoryOperatorOutput>,
    val variadic: Boolean = false,
)

/** The operators graphs are built from. */
object StoryOperators {
    private val NUMBER = StoryPinType.NUMBER
    private val TEXT = StoryPinType.TEXT
    private val BOOL = StoryPinType.BOOL
    private val VECTOR = StoryPinType.VECTOR
    private val ENTITY = StoryPinType.ENTITY
    private val LIST = StoryPinType.LIST
    private val MAP = StoryPinType.MAP
    private val ANY = StoryPinType.ANY

    private fun input(name: String, type: StoryPinType, default: String = "") = StoryOperatorInput(name, type, default)

    private fun out(name: String, type: StoryPinType, template: String) = StoryOperatorOutput(name, type, template)

    private fun operator(id: String, category: String, inputs: List<StoryOperatorInput>, vararg outputs: StoryOperatorOutput) =
        StoryOperator(id, category, inputs, outputs.toList())

    private fun number(name: String, default: String = "0") = input(name, NUMBER, default)

    private fun entity(name: String = "entity") = input(name, ENTITY, "storyPlayer()")

    /** Two numbers into one, written over `$a` and `$b` as doubles. */
    private fun math(id: String, template: String, b: String = "0") = operator(
        id, "math", listOf(number("a"), number("b", b)),
        out("result", NUMBER, template.replace("A", "$" + "a.toDouble()").replace("B", "$" + "b.toDouble()")),
    )

    /** A number into another, written over `$a` as a double. */
    private fun rounded(id: String, template: String) = operator(
        id, "math", listOf(number("a")), out("result", NUMBER, template.replace("A", "$" + "a.toDouble()")),
    )

    private fun compare(id: String, sign: String) = operator(
        id, "logic", listOf(number("a"), number("b")), out("result", BOOL, "$" + "a.toDouble() $sign $" + "b.toDouble()"),
    )

    private fun logic(id: String, sign: String) = operator(
        id, "logic", listOf(input("a", BOOL, "false"), input("b", BOOL, "false")), out("result", BOOL, "$" + "a $sign $" + "b"),
    )

    private fun vectors(id: String, template: String) = operator(
        id, "vector", listOf(input("a", VECTOR, "Vec3.ZERO"), input("b", VECTOR, "Vec3.ZERO")), out("result", VECTOR, template),
    )

    val all: List<StoryOperator> = listOf(
        operator(
            "player", "data", emptyList(),
            out("player", StoryPinType.PLAYER, "storyPlayer()"),
            out("level", StoryPinType.OBJECT, "storyLevel()").copy(kotlin = "ServerLevel"),
            out("dimension", TEXT, "storyLevel().dimension().location().toString()"),
            out("day_time", NUMBER, "storyLevel().dayTime.toDouble()"),
            out("raining", BOOL, "storyLevel().isRaining"),
        ),
        operator("find-player", "data", listOf(input("name", TEXT)), out("player", StoryPinType.PLAYER, "findStoryPlayer(\$name)")),
        operator(
            "entity-position", "data", listOf(entity()),
            out("position", VECTOR, "\$entity.position()"),
            out("x", NUMBER, "\$entity.x"),
            out("y", NUMBER, "\$entity.y"),
            out("z", NUMBER, "\$entity.z"),
            out("eyes", VECTOR, "\$entity.eyePosition"),
        ),
        operator(
            "entity-rotation", "data", listOf(entity()),
            out("yaw", NUMBER, "\$entity.yRot.toDouble()"),
            out("pitch", NUMBER, "\$entity.xRot.toDouble()"),
            out("look", VECTOR, "\$entity.lookAngle"),
            out("velocity", VECTOR, "\$entity.deltaMovement"),
        ),
        operator(
            "entity-state", "data", listOf(entity()),
            out("name", TEXT, "\$entity.name.string"),
            out("uuid", TEXT, "\$entity.stringUUID"),
            out("dimension", TEXT, "\$entity.level().dimension().location().toString()"),
            out("alive", BOOL, "\$entity.isAlive"),
            out("on_ground", BOOL, "\$entity.onGround()"),
            out("in_water", BOOL, "\$entity.isInWater"),
            out("sneaking", BOOL, "\$entity.isShiftKeyDown"),
            out("sprinting", BOOL, "\$entity.isSprinting"),
        ),
        operator(
            "entity-health", "data", listOf(entity()),
            out("health", NUMBER, "entityHealth(\$entity)"),
            out("max_health", NUMBER, "entityMaxHealth(\$entity)"),
        ),
        operator(
            "entity-attribute", "data", listOf(entity(), input("attribute", TEXT, "\"minecraft:generic.max_health\"")),
            out("value", NUMBER, "entityAttribute(\$entity, \$attribute)"),
        ),
        operator(
            "player-state", "data", listOf(entity("player")),
            out("food", NUMBER, "playerFood(\$player)"),
            out("level", NUMBER, "playerLevel(\$player)"),
            out("game_mode", TEXT, "playerGameMode(\$player)"),
        ),
        operator("held-item", "items", listOf(entity("player")), out("held_item", TEXT, "playerHeldItem(\$player)")),

        math("add", "A + B"),
        math("subtract", "A - B"),
        math("multiply", "A * B", "1"),
        math("divide", "A / B", "1"),
        math("modulo", "A.mod(B)", "1"),
        math("power", "Math.pow(A, B)", "1"),
        math("min", "minOf(A, B)"),
        math("max", "maxOf(A, B)"),
        rounded("abs", "Math.abs(A)"),
        rounded("sqrt", "Math.sqrt(A)"),
        rounded("floor", "Math.floor(A)"),
        rounded("ceil", "Math.ceil(A)"),
        rounded("round", "Math.rint(A)"),
        rounded("sin", "Math.sin(A)"),
        rounded("cos", "Math.cos(A)"),
        operator(
            "clamp", "math", listOf(number("value"), number("min"), number("max", "1")),
            out("result", NUMBER, "\$value.toDouble().coerceIn(\$min.toDouble(), \$max.toDouble())"),
        ),
        operator(
            "lerp", "math", listOf(number("from"), number("to", "1"), number("t", "0.5")),
            out("result", NUMBER, "\$from.toDouble() + (\$to.toDouble() - \$from.toDouble()) * \$t.toDouble()"),
        ),
        operator(
            "random", "math", listOf(number("min"), number("max", "1")),
            out("result", NUMBER, "\$min.toDouble() + Math.random() * (\$max.toDouble() - \$min.toDouble())"),
        ),

        compare("less", "<"),
        compare("less-equal", "<="),
        compare("greater", ">"),
        compare("greater-equal", ">="),
        operator("is-null", "logic", listOf(input("value", ANY)), out("result", BOOL, "\$value == null")),
        operator("equal", "logic", listOf(input("a", ANY, "0"), input("b", ANY, "0")), out("result", BOOL, "\$a == \$b")),
        operator("not-equal", "logic", listOf(input("a", ANY, "0"), input("b", ANY, "0")), out("result", BOOL, "\$a != \$b")),
        logic("and", "&&"),
        logic("or", "||"),
        logic("xor", "!="),
        operator("not", "logic", listOf(input("a", BOOL, "false")), out("result", BOOL, "!\$a")),
        operator(
            "select", "logic", listOf(input("condition", BOOL, "false"), input("then", ANY, "0"), input("else", ANY, "0")),
            out("result", ANY, "if (\$condition) \$then else \$else"),
        ),

        operator(
            "vector", "vector", listOf(number("x"), number("y"), number("z")),
            out("vector", VECTOR, "vec(\$x, \$y, \$z)"),
        ),
        operator(
            "vector-split", "vector", listOf(input("vector", VECTOR, "Vec3.ZERO")),
            out("x", NUMBER, "\$vector.x"), out("y", NUMBER, "\$vector.y"), out("z", NUMBER, "\$vector.z"),
        ),
        vectors("vector-add", "\$a.add(\$b)"),
        vectors("vector-subtract", "\$a.subtract(\$b)"),
        operator(
            "vector-scale", "vector", listOf(input("vector", VECTOR, "Vec3.ZERO"), number("factor", "1")),
            out("result", VECTOR, "\$vector.scale(\$factor.toDouble())"),
        ),
        operator(
            "distance", "vector", listOf(input("a", VECTOR, "Vec3.ZERO"), input("b", VECTOR, "Vec3.ZERO")),
            out("result", NUMBER, "\$a.distanceTo(\$b)"),
        ),
        operator(
            "vector-length", "vector", listOf(input("vector", VECTOR, "Vec3.ZERO")),
            out("length", NUMBER, "\$vector.length()"), out("normalized", VECTOR, "\$vector.normalize()"),
        ),
        operator(
            "vector-lerp", "vector",
            listOf(input("from", VECTOR, "Vec3.ZERO"), input("to", VECTOR, "Vec3.ZERO"), number("t", "0.5")),
            out("result", VECTOR, "\$from.lerp(\$to, \$t.toDouble())"),
        ),

        operator("join-text", "text", listOf(input("a", ANY, "\"\""), input("b", ANY, "\"\"")), out("result", TEXT, "\"\${\$a}\${\$b}\"")),
        operator(
            "format-text", "text",
            listOf(input("format", TEXT), input("a", ANY, "\"\""), input("b", ANY, "\"\""), input("c", ANY, "\"\""), input("d", ANY, "\"\"")),
            out("result", TEXT, "\$format.format(\$a, \$b, \$c, \$d)"),
        ),
        operator("to-text", "text", listOf(input("value", ANY, "\"\"")), out("result", TEXT, "\$value.toString()")),
        operator("to-number", "text", listOf(input("text", TEXT, "\"0\"")), out("result", NUMBER, "(\$text.toDoubleOrNull() ?: 0.0)")),
        operator("text-length", "text", listOf(input("text", TEXT, "\"\"")), out("length", NUMBER, "\$text.length.toDouble()")),
        operator(
            "text-contains", "text", listOf(input("text", TEXT, "\"\""), input("part", TEXT, "\"\"")),
            out("result", BOOL, "\$text.contains(\$part)"),
        ),
        operator(
            "text-replace", "text", listOf(input("text", TEXT, "\"\""), input("from", TEXT, "\"\""), input("to", TEXT, "\"\"")),
            out("result", TEXT, "\$text.replace(\$from, \$to)"),
        ),

        operator(
            "list", "list", listOf(input("a", ANY), input("b", ANY), input("c", ANY)),
            out("list", LIST, "listOf(\$*)").copy(kotlin = "List<Any?>", empty = "emptyList<Any?>()"),
        ).copy(variadic = true),
        operator(
            "range", "list", listOf(number("from"), number("to", "9")),
            out("list", LIST, "(\$from.toInt()..\$to.toInt()).toList()").copy(kotlin = "List<Int>"),
        ),
        operator("list-size", "list", listOf(input("list", LIST)), out("size", NUMBER, "\$list.size.toDouble()")),
        operator("list-get", "list", listOf(input("list", LIST), number("index")), out("item", ANY, "\$list[\$index.toInt()]")),
        operator("list-contains", "list", listOf(input("list", LIST), input("item", ANY)), out("result", BOOL, "\$item in \$list")),
        operator("list-add", "list", listOf(input("list", LIST), input("item", ANY)), out("list", LIST, "\$list + \$item")),
        operator("list-remove", "list", listOf(input("list", LIST), input("item", ANY)), out("list", LIST, "\$list - \$item")),

        operator("map-get", "list", listOf(input("map", MAP), input("key", ANY)), out("value", ANY, "\$map[\$key]")),
        operator("map-has", "list", listOf(input("map", MAP), input("key", ANY)), out("result", BOOL, "\$key in \$map")),
        operator("map-put", "list", listOf(input("map", MAP), input("key", ANY), input("value", ANY)), out("map", MAP, "\$map + (\$key to \$value)")),
        operator("map-remove", "list", listOf(input("map", MAP), input("key", ANY)), out("map", MAP, "\$map - \$key")),
        operator(
            "map-keys", "list", listOf(input("map", MAP)),
            out("keys", LIST, "\$map.keys.toList()").copy(kotlin = "List<Any?>"), out("size", NUMBER, "\$map.size.toDouble()"),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): StoryOperator? = byId[id]

    /** The name of the input at [index] of a variadic operator. */
    fun inputName(index: Int): String = if (index < LETTERS) "${'a' + index}" else "item$index"

    /** The inputs of the operator of [node], with those its author added. */
    fun inputsOf(node: StoryNode.Pure): List<StoryOperatorInput> {
        val operator = find(node.operator) ?: return emptyList()
        val last = operator.inputs.lastOrNull()
        if (!operator.variadic || last == null) return operator.inputs
        return List(node.count ?: operator.inputs.size) { last.copy(name = inputName(it)) }
    }

    private const val LETTERS = 26
}
