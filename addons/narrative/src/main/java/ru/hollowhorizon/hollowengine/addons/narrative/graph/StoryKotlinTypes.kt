package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/** Kotlin types as nodes keep them, written out. */
object StoryKotlinTypes {
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
}
