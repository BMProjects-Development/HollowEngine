package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemFilter
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf

/**
 * What a pin of a node carries, which says what links to it, how it is painted and how its field writes Kotlin.
 * Types live in a registry an addon adds its own to with [register]; a value fits the pin of any type whose class it
 * is of, so a pin of a player links into one of an entity.
 *
 * @param id what tells it apart, in the tags of its pins too.
 * @param kind the class of its values; null for the types of flow and of values of any class.
 * @param color what its pins and the links out of them are painted, as `0xAARRGGBB`.
 * @param label what a value of it is called in Kotlin, for telling the author.
 * @param converts whether a link of it gets to the type of its place by conversion, whatever Kotlin says of the two,
 * as a number does to an `Int`.
 * @param classes the classes of values it is the pin of besides those of [kind].
 */
class StoryPinType(
    val id: String,
    val kind: KClass<*>?,
    val color: Int,
    val label: String = kind?.simpleName ?: "Any?",
    val converts: Boolean = false,
    private val classes: List<KClass<*>> = emptyList(),
) {
    val type: KType? = kind?.let { it.createType(it.typeParameters.map { KTypeProjection.STAR }) }

    /** How far down the classes [kind] is: a pin of a deeper class is the closer match of a value. */
    private val depth = kind?.allSuperclasses?.size ?: -1

    fun holds(value: KClass<*>): Boolean = kind != null && (value.isSubclassOf(kind) || classes.any(value::isSubclassOf))

    fun fits(input: StoryPinType): Boolean = when {
        this === input -> true
        this === FLOW || input === FLOW -> false
        this === ANY || input === ANY -> true
        else -> kind != null && input.kind != null && input.holds(kind)
    }

    fun expression(text: String): String {
        if (type != null) return StoryLiterals.expression(text, type)
        val typed = text.trim()
        return when {
            typed.startsWith("{") && typed.endsWith("}") -> typed.substring(1, typed.length - 1).ifBlank { typed }
            typed.isEmpty() || this !== ANY -> typed
            typed == "true" || typed == "false" || typed.toDoubleOrNull() != null -> typed
            else -> StoryGraphCompiler.literal(text)
        }
    }

    fun text(expression: String): String {
        if (type != null) return StoryLiterals.text(expression, type)
        val written = expression.trim()
        return when {
            written.isEmpty() || written == "true" || written == "false" || written.toDoubleOrNull() != null -> written
            else -> StoryLiterals.text(written, null).takeIf { this === ANY } ?: written
        }
    }

    override fun toString(): String = id

    companion object {
        private val registered = ArrayList<StoryPinType>()

        val all: List<StoryPinType> get() = registered

        fun register(type: StoryPinType): StoryPinType {
            registered.removeAll { it.id == type.id }
            registered += type
            return type
        }

        val FLOW = register(StoryPinType("flow", null, 0xFFE6E8EE.toInt()))
        val TEXT = register(StoryPinType("text", String::class, 0xFFE89AC0.toInt()))
        val NUMBER = register(StoryPinType("number", Double::class, 0xFF44BC74.toInt(), converts = true, classes = listOf(Number::class)))
        val BOOL = register(StoryPinType("bool", Boolean::class, 0xFFD9605A.toInt()))
        val VECTOR = register(StoryPinType("vector", Vec3::class, 0xFFF0CF60.toInt(), classes = listOf(BlockPos::class)))
        val ENTITY = register(StoryPinType("entity", Entity::class, 0xFF4FA8E0.toInt(), converts = true))
        val NPC = register(StoryPinType("npc", NpcEntity::class, 0xFF4FD0D0.toInt(), converts = true))
        val PLAYER = register(StoryPinType("player", ServerPlayer::class, 0xFF6F8CF0.toInt(), converts = true))
        val ITEM = register(StoryPinType("item", ItemStack::class, 0xFFE0A050.toInt()))
        val BLOCK = register(StoryPinType("block", BlockState::class, 0xFF9C8A6A.toInt()))
        val NBT = register(StoryPinType("nbt", Tag::class, 0xFF7FB8A0.toInt()))
        val ITEM_FILTER = register(StoryPinType("item_filter", StoryItemFilter::class, 0xFFD8B070.toInt()))
        val LIST = register(
            StoryPinType("list", Collection::class, 0xFFC9874A.toInt(), label = "List<*>", classes = listOf(Iterable::class)),
        )
        val MAP = register(StoryPinType("map", Map::class, 0xFFB48EE0.toInt(), label = "Map<*, *>"))

        /** A value of a class with no pin of its own; the pins say which, as they do not all fit each other. */
        val OBJECT = register(StoryPinType("object", null, 0xFF8FA0B8.toInt()))
        val ANY = register(StoryPinType("any", null, 0xFFA5ABC0.toInt(), converts = true))

        /** The type of the pins of values of [type]: the one of the closest class, objects of others. */
        fun of(type: KType?): StoryPinType = (type?.classifier as? KClass<*>)?.let(::of) ?: ANY

        /** The type of the pins of values of the class [kind]. */
        fun of(kind: KClass<*>): StoryPinType {
            if (kind == Any::class) return ANY
            return registered.filter { it.holds(kind) }.maxByOrNull { it.depth } ?: OBJECT
        }

        fun ofName(name: String?): StoryPinType {
            val written = name?.removeSuffix("?")?.substringBefore('<')?.replace("!", "") ?: return ANY
            if (written.substringAfterLast('.') == "Any") return ANY
            val kind = classOf(written) ?: SHORT[written.substringAfterLast('.')]
                ?: return ANY_NAMED.firstOrNull { (suffix, _) -> written.endsWith(suffix) }?.second ?: OBJECT
            return of(kind)
        }

        fun classOf(name: String): KClass<*>? {
            val written = name.removeSuffix("?").substringBefore('<').replace("!", "")
            if ('.' !in written) return SHORT[written]
            return loaded.getOrPut(written) { load(written) }
        }

        private fun load(name: String): KClass<*>? {
            var binary = name
            while (true) {
                runCatching { return Class.forName(binary).kotlin }
                val dot = binary.lastIndexOf('.')
                if (dot < 0) return null
                binary = binary.substring(0, dot) + '$' + binary.substring(dot + 1)
            }
        }

        fun shortName(name: String): String = PACKAGE.replace(name, "").replace("!", "")

        private val loaded = HashMap<String, KClass<*>?>()

        private val PACKAGE = Regex("""\b[a-z_]\w*\.""")

        private val SHORT: Map<String, KClass<*>> = mapOf(
            "String" to String::class, "Boolean" to Boolean::class, "Int" to Int::class, "Long" to Long::class,
            "Short" to Short::class, "Byte" to Byte::class, "Float" to Float::class, "Double" to Double::class,
            "List" to List::class, "MutableList" to List::class, "Set" to Set::class, "Collection" to Collection::class,
            "Iterable" to Iterable::class, "Map" to Map::class, "MutableMap" to Map::class,
        )

        private val ANY_NAMED get() = listOf("Player" to PLAYER, "Entity" to ENTITY, "Tag" to NBT)
    }
}
