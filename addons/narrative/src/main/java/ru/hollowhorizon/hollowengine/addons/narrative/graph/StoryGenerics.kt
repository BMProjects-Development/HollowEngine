package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** How the `T` of a generic block is read off the surrounding types. */
object StoryGenerics {
    private val PARAMETER = Regex("\\bT\\b")
    private val NOTHING = Regex("\\bNothing\\b")
    private const val INTERSECTION = '&'

    fun shaped(shape: String, type: String): String = shape.replace(PARAMETER, type)

    fun elementOf(shape: String, type: String): String? {
        if (shape == "T") return type
        if (shape.substringAfter('<', "").substringBeforeLast('>', "") != "T") return null
        val arguments = type.removeSuffix("?").substringAfter('<', "").substringBeforeLast('>', "")
        var depth = 0
        arguments.forEach { char ->
            when (char) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) return null
            }
        }
        return arguments.trim().removePrefix("out ").trim().takeIf { it.isNotEmpty() && it != "*" }
    }

    fun isKnown(type: String): Boolean =
        type.isNotEmpty() && type.substringAfterLast('.') != "Any" && !NOTHING.containsMatchIn(type) &&
            !PARAMETER.containsMatchIn(type) && INTERSECTION !in type
}
