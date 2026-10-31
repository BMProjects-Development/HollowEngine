package ru.hollowhorizon.hollowengine.addons.narrative.graph

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
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

    /** What is typed into a field of [type] as Kotlin. */
    fun expression(text: String, type: KType?): String {
        val typed = text.trim()
        if (typed.isEmpty()) return ""
        if (typed.startsWith("{") && typed.endsWith("}")) return typed.substring(1, typed.length - 1)
        val kind = type?.classifier as? KClass<*> ?: return StoryGraphCompiler.literal(text)
        return when {
            kind == Boolean::class -> typed
            kind == Int::class || kind == Double::class -> typed
            kind == Long::class -> "${typed}L"
            kind == Float::class -> "${typed}f"
            kind == Short::class -> "$typed.toShort()"
            kind == Byte::class -> "$typed.toByte()"
            kind == Duration::class -> call("duration", typed)
            kind == ResourceLocation::class -> call("location", typed)
            kind == ServerLevel::class -> call("dimension", typed)
            kind == Vec3::class -> "vec(${numbers(typed)})"
            kind == BlockPos::class -> "blockPos(${numbers(typed)})"
            kind.isSubclassOf(DialogueCharacter::class) -> call("character", typed)
            kind.isSubclassOf(Entity::class) -> call("actor", typed)
            kind.java.isEnum -> "${kind.qualifiedName}.${typed.uppercase().replace('-', '_')}"
            kind in COLLECTIONS -> typed.split(',').joinToString(prefix = "listOf(", postfix = ")") {
                expression(it, type.arguments.singleOrNull()?.type)
            }

            else -> StoryGraphCompiler.literal(text)
        }
    }

    /** How a field of [type] shows the Kotlin [expression]: back as it was typed, or in braces. */
    fun text(expression: String, type: KType?): String {
        val written = expression.trim()
        if (written.isEmpty()) return ""
        val kind = type?.classifier as? KClass<*>
        val plain = when {
            kind == null || kind == String::class -> string(written)
            kind == Boolean::class || kind == Int::class || kind == Double::class ->
                written.takeIf { it == "true" || it == "false" || it.toDoubleOrNull() != null }

            kind == Long::class -> written.removeSuffix("L").takeIf { it.toLongOrNull() != null }
            kind == Float::class -> written.removeSuffix("f").takeIf { it.toFloatOrNull() != null }
            kind == Short::class -> written.removeSuffix(".toShort()").takeIf { it.toIntOrNull() != null }
            kind == Byte::class -> written.removeSuffix(".toByte()").takeIf { it.toIntOrNull() != null }
            kind.java.isEnum -> written.substringAfterLast('.').takeIf { written.endsWith(it) && it.uppercase() == it }
                ?.lowercase()?.replace('_', '-')

            kind in COLLECTIONS -> Regex("""^listOf\((.*)\)$""").matchEntire(written)?.groupValues?.get(1)?.split(", ")
                ?.map { text(it, type.arguments.singleOrNull()?.type) }?.joinToString(", ")

            else -> helperArguments(written)
        }
        return plain ?: "{$written}"
    }

    private fun helperArguments(written: String): String? {
        val match = CALL.matchEntire(written) ?: return null
        val inner = match.groupValues[2]
        return if (inner.startsWith("\"")) string(inner) else inner.takeIf { it.none { char -> char == '(' } }
    }

    private fun call(helper: String, text: String) = "$helper(${StoryGraphCompiler.literal(text)})"

    private fun numbers(text: String) = text.removePrefix("[").removeSuffix("]").split(Regex("""[,\s]+"""))
        .filter(String::isNotEmpty).joinToString()

    /** The text of a string literal without inserts, or null for any other expression. */
    private fun string(written: String): String? {
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

    private val COLLECTIONS = setOf(List::class, Collection::class, Iterable::class, Set::class)
}
