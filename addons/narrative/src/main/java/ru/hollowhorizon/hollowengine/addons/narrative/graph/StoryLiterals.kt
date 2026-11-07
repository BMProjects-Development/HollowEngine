package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLiterals.seconds
import ru.hollowhorizon.hollowengine.addons.narrative.items.StoryItemFilter
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueCharacter
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.time.Duration

/**
 * Values as the fields of a card show them and as Kotlin writes them. A field holds a plain value of its type, or
 * `{expression}` for anything else, so a writer never sees Kotlin they did not type.
 */
object StoryLiterals {
    private val CALL = Regex("""^(\w+)\((.*)\)$""")
    private const val SECONDS = "s"
    const val POSITION = "pos"
    const val BLOCK = "blockPos"
    private const val ITEM = "itemOf"
    private const val BLOCK_STATE = "blockOf"
    private const val NBT = "nbt"
    private const val FILTER = "itemsMatching"

    /** What is typed into a field of [type] as Kotlin. */
    fun expression(text: String, type: KType?): String {
        val typed = text.trim()
        if (typed.isEmpty()) return ""
        if (typed.startsWith("{") && typed.endsWith("}")) return typed.substring(1, typed.length - 1).ifBlank { typed }
        val kind = type?.classifier as? KClass<*> ?: return StoryGraphCompiler.literal(text)
        return when {
            kind in TEXTS -> StoryGraphCompiler.literal(text)
            kind == Boolean::class || kind == Int::class -> typed
            kind == Double::class -> if (typed.toIntOrNull() != null) "$typed.toDouble()" else typed
            kind == Long::class -> numeric(typed, "L")
            kind == Float::class -> numeric(typed, "f")
            kind == Short::class -> numeric(typed, ".toShort()")
            kind == Byte::class -> numeric(typed, ".toByte()")
            kind == Duration::class -> call("duration", typed)
            kind == ResourceLocation::class -> call("location", typed)
            kind == ServerLevel::class -> call("dimension", typed)
            kind == Vec3::class -> "vec(${numbers(typed)})"
            kind == BlockPos::class -> "blockPos(${numbers(typed)})"
            kind == ItemStack::class -> call(ITEM, typed)
            kind == BlockState::class -> call(BLOCK_STATE, typed)
            kind.isSubclassOf(Tag::class) -> call(NBT, typed)
            kind == StoryItemFilter::class -> call(FILTER, typed)
            kind.isSubclassOf(DialogueCharacter::class) -> call("character", typed)
            kind.isSubclassOf(Entity::class) -> call("actor", typed)
            kind.java.isEnum -> "${kind.qualifiedName}.${typed.uppercase().replace('-', '_')}"

            else -> text
        }
    }

    /** How a field of [type] shows the Kotlin [expression]: back as it was typed, or in braces. */
    fun text(expression: String, type: KType?): String {
        val written = expression.trim()
        if (written.isEmpty()) return ""
        if (written.startsWith("{") && written.endsWith("}") && written.substring(1, written.length - 1)
                .isBlank()
        ) return written
        val kind = type?.classifier as? KClass<*>
        val plain = when {
            kind == null || kind in TEXTS -> string(written)
            kind == Boolean::class || kind == Int::class -> written
            kind == Double::class -> written.removeSuffix(".toDouble()")
            kind == Long::class -> written.removeSuffix("L")
            kind == Float::class -> written.removeSuffix("f")
            kind == Short::class -> written.removeSuffix(".toShort()")
            kind == Byte::class -> written.removeSuffix(".toByte()")
            kind.java.isEnum -> written.substringAfterLast('.').takeIf { written.endsWith(it) && it.uppercase() == it }
                ?.lowercase()?.replace('_', '-')

            kind in HELPED || kind.isSubclassOf(DialogueCharacter::class) || kind.isSubclassOf(Entity::class) || kind.isSubclassOf(
                Tag::class
            ) -> helperArguments(written)

            else -> expression
        }
        return plain ?: "{$written}"
    }

    /** What a field of [type] shows for [expression] when that is a plain value; null for any other Kotlin. */
    fun literal(expression: String, type: KType?): String? =
        text(expression, type).takeIf { it.isNotBlank() && it != "{${expression.trim()}}" }

    /** The strings of a `listOf` of the Kotlin [expression]; anything else holds none. */
    fun listed(expression: String): List<String> {
        val inner = expression.trim().removePrefix("listOf(").takeIf { it.endsWith(")") }?.dropLast(1).orEmpty()
        return StoryKotlin.arguments(inner).orEmpty().mapNotNull { string(it) }
    }

    /** The Kotlin of a list of [values]; with none of them it is left out. */
    fun list(values: List<String>): String = if (values.isEmpty()) "" else values.joinToString(
        prefix = "listOf(",
        postfix = ")"
    ) { StoryGraphCompiler.literal(it) }

    /** [typed] with [suffix] when it is a number; anything else is an expression and stays as it was typed. */
    private fun numeric(typed: String, suffix: String) = if (typed.toDoubleOrNull() != null) "$typed$suffix" else typed

    /** The function that writes a value of [type] from its coordinates, or null when it is no vector. */
    fun vectorHelper(type: KType?): String? = when (type?.classifier) {
        Vec3::class -> POSITION
        BlockPos::class -> BLOCK
        else -> null
    }

    /** The Kotlin of a vector out of its coordinates; with none of them typed it is left out. */
    fun vector(parts: List<String>, helper: String): String =
        if (parts.all(String::isBlank)) "" else parts.joinToString(prefix = "$helper(", postfix = ")") { it.trim() }

    private fun helperArguments(written: String): String? {
        val match = CALL.matchEntire(written) ?: return null
        val inner = match.groupValues[2]
        return if (inner.startsWith("\"")) string(inner) else inner.takeIf { it.none { char -> char == '(' } }
    }

    private fun call(helper: String, text: String) = "$helper(${StoryGraphCompiler.literal(text)})"

    private fun numbers(text: String) =
        text.removePrefix("[").removeSuffix("]").split(Regex("""[,\s]+""")).filter(String::isNotEmpty).joinToString()

    /** The text of a string literal without inserts, or null for any other expression. */
    private fun string(written: String): String? {
        StoryTemplates.content(written).takeIf { written.startsWith(RAW) && written.endsWith(RAW) && '$' !in it }?.let { return it }
        if (written.length < 2 || !written.startsWith("\"") || !written.endsWith("\"")) return null
        val inner = written.substring(1, written.length - 1)
        return buildString {
            var index = 0
            while (index < inner.length) {
                val char = inner[index++]
                if (char == '"') return null
                if (char != '\\') {
                    if (char == '$') return null
                    append(char)
                    continue
                }
                when (val next = inner.getOrNull(index++) ?: return null) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(next)
                }
            }
        }
    }

    /** A number of [seconds] as a field of a duration shows it. */
    fun seconds(seconds: Float): String = seconds.toString().removeSuffix(".0") + SECONDS

    private const val RAW = "\"\"\""

    /** Types a field takes plain text for. */
    private val TEXTS = setOf(String::class, CharSequence::class, Any::class)

    /** Types whose fields are written through a helper function, such as `duration("2s")`. */
    private val HELPED = setOf(
        Duration::class, ResourceLocation::class, ServerLevel::class, Vec3::class, BlockPos::class, ItemStack::class,
        BlockState::class, StoryItemFilter::class,
    )
}
