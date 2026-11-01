package ru.hollowhorizon.hollowengine.addons.narrative.graph

/**
 * The types a variable of a story can have, which are all of those a story can save. A variable of an entity starts
 * empty, since nothing outside a running story can name one.
 */
object StoryVariableTypes {
    /** A type as the editor offers it: [name] picks its label, [type] is its Kotlin, [initial] what a new one holds. */
    class Kind(val name: String, val type: String, val initial: String)

    const val LIST = "list"
    const val MAP = "map"
    private const val EMPTY_LIST = "emptyList()"
    private const val EMPTY_MAP = "emptyMap()"
    private const val ARROW = " to "

    private val VALUES = listOf(
        Kind("number", "Double", "0.0"),
        Kind("text", "String", "\"\""),
        Kind("bool", "Boolean", "false"),
        Kind("vector", "Vec3", "Vec3.ZERO"),
    )
    private val ENTITIES = listOf(
        Kind("npc", "NpcEntity?", "null"),
        Kind("player", "ServerPlayer?", "null"),
        Kind("entity", "Entity?", "null"),
    )
    private val SINGLE = VALUES + ENTITIES

    private val LIST_TYPE = Regex("""^List<(.+)>$""")
    private val MAP_TYPE = Regex("""^Map<([^,]+),\s*(.+)>$""")
    private val LIST_VALUE = Regex("""^listOf\((.*)\)$""")
    private val MAP_VALUE = Regex("""^mapOf\((.*)\)$""")

    /** The name of every type, those of one value first. */
    val names: List<String> = SINGLE.map { it.name } + LIST + MAP

    /** What a list or a map can hold. */
    val elements: List<String> = names - LIST - MAP

    /** What a map can be looked up by. */
    val keys: List<String> = VALUES.take(2).map { it.name }

    private fun kind(name: String) = SINGLE.firstOrNull { it.name == name } ?: VALUES.first()

    private fun element(name: String) = kind(name).type.removeSuffix("?")

    private fun named(type: String) = SINGLE.firstOrNull { it.type.removeSuffix("?") == type.removeSuffix("?") }?.name ?: VALUES.first().name

    /** The name of the type written [type]. */
    fun nameOf(type: String?): String = when {
        type == null -> VALUES.first().name
        LIST_TYPE.matches(type) -> LIST
        MAP_TYPE.matches(type) -> MAP
        else -> named(type)
    }

    /** The name of what the list type [type] holds. */
    fun elementOf(type: String?): String = named(type?.let(LIST_TYPE::matchEntire)?.groupValues?.get(1).orEmpty())

    fun keyOf(type: String?): String = named(type?.let(MAP_TYPE::matchEntire)?.groupValues?.get(1).orEmpty())

    fun valueOf(type: String?): String = named(type?.let(MAP_TYPE::matchEntire)?.groupValues?.get(2).orEmpty())

    /** [variable] as a new variable of the type [name]. */
    fun retyped(variable: StoryVariable, name: String): StoryVariable = when (name) {
        LIST -> list(variable, elements.first())
        MAP -> map(variable, keys.first(), elements.first())
        else -> kind(name).let { variable.copy(type = it.type, initial = it.initial) }
    }

    fun list(variable: StoryVariable, element: String) = variable.copy(type = "List<${element(element)}>", initial = EMPTY_LIST)

    fun map(variable: StoryVariable, key: String, value: String) =
        variable.copy(type = "Map<${element(key)}, ${element(value)}>", initial = EMPTY_MAP)

    /** Whether a variable of [type] starts empty. */
    fun startsEmpty(type: String?): Boolean = type?.endsWith("?") == true

    /** The elements of the Kotlin [initial] of a list as a field shows them, split, or null when it is another expression. */
    fun listParts(initial: String): List<String>? {
        if (initial.trim() == EMPTY_LIST) return emptyList()
        return LIST_VALUE.matchEntire(initial.trim())?.let { StoryKotlin.arguments(it.groupValues[1]) }
    }

    /** The entries of the Kotlin [initial] of a map, or null when it is another expression. */
    fun mapParts(initial: String): List<Pair<String, String>>? {
        if (initial.trim() == EMPTY_MAP) return emptyList()
        val entries = MAP_VALUE.matchEntire(initial.trim())?.let { StoryKotlin.arguments(it.groupValues[1]) } ?: return null
        return entries.map { entry ->
            if (ARROW !in entry) return null
            entry.substringBefore(ARROW).trim() to entry.substringAfter(ARROW).trim()
        }
    }

    private fun shown(written: String, type: String): String {
        val pin = StoryPinType.ofName(type)
        val text = pin.text(written)
        return if (pin == StoryPinType.VECTOR) "[$text]" else text
    }

    /** How a field shows the Kotlin [initial] of a list or a map of [type], or the expression in braces. */
    fun text(initial: String, type: String): String {
        val element = LIST_TYPE.matchEntire(type)?.groupValues?.get(1)
        val listed = element?.let { listParts(initial)?.joinToString { part -> shown(part, it) } }
        if (element != null) return listed ?: "{$initial}"
        val (key, value) = MAP_TYPE.matchEntire(type)?.destructured ?: return initial
        return mapParts(initial)?.joinToString { (at, held) -> "${shown(at, key)} = ${shown(held, value)}" } ?: "{$initial}"
    }

    /** What is typed for a list or a map of [type] as Kotlin. */
    fun expression(text: String, type: String): String {
        val typed = text.trim()
        if (typed.startsWith("{") && typed.endsWith("}")) return typed.substring(1, typed.length - 1)
        val parts = (StoryKotlin.arguments(typed) ?: listOf(typed)).filter(String::isNotBlank)
        val element = LIST_TYPE.matchEntire(type)?.groupValues?.get(1)
        if (element != null) {
            val pin = StoryPinType.ofName(element)
            return if (parts.isEmpty()) EMPTY_LIST else parts.joinToString(prefix = "listOf(", postfix = ")", transform = pin::expression)
        }
        val (key, value) = MAP_TYPE.matchEntire(type)?.destructured ?: return typed
        if (parts.isEmpty()) return EMPTY_MAP
        return parts.joinToString(prefix = "mapOf(", postfix = ")") { part ->
            val at = StoryPinType.ofName(key).expression(part.substringBefore('=').trim())
            "$at$ARROW${StoryPinType.ofName(value).expression(part.substringAfter('=', "").trim())}"
        }
    }
}
