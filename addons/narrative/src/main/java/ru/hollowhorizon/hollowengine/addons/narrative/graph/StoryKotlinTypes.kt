package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/** Kotlin types as nodes keep them, written out, with what links need to know of them. */
object StoryKotlinTypes {
    private const val ANY = "Any"
    private const val STAR = "*"

    /** How Kotlin writes [type]; null when no text can name it, as with a type parameter or a function. */
    fun name(type: KType): String? {
        val kind = type.classifier as? KClass<*> ?: return null
        if (kind.isSubclassOf(Function::class)) return null
        val base = kind.qualifiedName ?: return null
        val arguments = type.arguments.map { argument -> argument.type?.let { name(it) ?: return null } ?: STAR }
        val listed = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">")
        return base + listed + if (type.isMarkedNullable) "?" else ""
    }

    private fun base(type: String) = type.substringBefore('<').removeSuffix("?").trim()

    private fun arguments(type: String): List<String> {
        val inner = type.substringAfter('<', "").substringBeforeLast('>', "")
        if (inner.isEmpty()) return emptyList()
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        inner.forEachIndexed { index, char ->
            if (char == '<') depth++
            if (char == '>') depth--
            if (char == ',' && depth == 0) {
                parts += inner.substring(start, index).trim()
                start = index + 1
            }
        }
        return parts + inner.substring(start).trim()
    }

    /** Whether a value of the type [output] can go into a place of the type [input], with arguments told apart. */
    fun fits(output: String, input: String): Boolean {
        val from = StoryPinType.shortName(output)
        val to = StoryPinType.shortName(input)
        val fromPin = StoryPinType.ofName(from)
        val toPin = StoryPinType.ofName(to)
        if (fromPin == StoryPinType.ANY || toPin == StoryPinType.ANY) return true
        if (!fromPin.fits(toPin)) return false
        if (fromPin == StoryPinType.OBJECT && base(from) != base(to)) return false
        return sameArguments(from, to)
    }

    private fun sameArguments(from: String, to: String): Boolean {
        val given = arguments(from)
        val wanted = arguments(to)
        return given.size != wanted.size || given.indices.all { argumentFits(given[it], wanted[it]) }
    }

    private fun argumentFits(given: String, wanted: String): Boolean {
        val to = wanted.removePrefix("out ").removePrefix("in ")
        if (to == STAR || to == "$ANY?") return true
        if (given == STAR || (given.endsWith("?") && !to.endsWith("?"))) return false
        if (base(to) == ANY) return true
        if (base(given) == base(to)) return sameArguments(given, to)
        val pin = StoryPinType.ofName(given)
        return pin != StoryPinType.NUMBER && pin != StoryPinType.OBJECT && pin.fits(StoryPinType.ofName(to))
    }
}
