package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KClass
import kotlin.reflect.KVariance

/** A declaration of a pin type. Parameters are explicit, so no Kotlin text has to be guessed or parsed here. */
sealed interface StoryTypeShape {
    val kotlin: String
    val parameters: List<String>

    /** The type accepted before parameters are inferred; null means that it imposes no constraint. */
    val starred: String?

    val nullable: StoryTypeShape get() = this as? Nullable ?: Nullable(this)
    val nonNullable: StoryTypeShape get() = if (this is Nullable) type.nonNullable else this

    data class Parameter(val name: String) : StoryTypeShape {
        init {
            require(name.isNotBlank())
        }

        override val kotlin get() = name
        override val parameters get() = listOf(name)
        override val starred: String? get() = null
    }

    sealed interface Constructed : StoryTypeShape {
        val name: String
        val arguments: List<Projection>

        override val parameters get() = arguments.flatMap { it.type?.parameters.orEmpty() }.distinct()
        override val kotlin get() = render(false)
        override val starred get() = render(true)

        private fun render(erase: Boolean): String = name + if (arguments.isEmpty()) "" else
            arguments.joinToString(prefix = "<", postfix = ">") { argument ->
                val type = argument.type
                val text = if (erase) type?.starred else type?.kotlin
                val variance = when (argument.variance) {
                    KVariance.IN -> "in "
                    KVariance.OUT -> "out "
                    KVariance.INVARIANT -> ""
                }
                if (text == null) "*" else variance + text
            }
    }

    data class Applied(val kind: KClass<*>, override val arguments: List<Projection>) : Constructed {
        init {
            require(arguments.size == kind.typeParameters.size) { "Wrong number of type arguments for $kind" }
        }

        override val name get() = requireNotNull(StoryKotlinTypes.className(kind)) { "A pin type must have a qualified name" }
    }

    /** A classifier on the compiler classpath. Its syntax, arity and bounds are checked by K2 with the graph. */
    data class Named(override val name: String, override val arguments: List<Projection>) : Constructed

    data class Nullable(val type: StoryTypeShape) : StoryTypeShape {
        override val kotlin get() = "${type.kotlin}?"
        override val parameters get() = type.parameters
        override val starred get() = type.starred?.let { "$it?" }
    }

    data class Projection(val type: StoryTypeShape?, val variance: KVariance = KVariance.INVARIANT) {
        init {
            require(type != null || variance == KVariance.INVARIANT)
        }
    }

    companion object {
        fun parameter(name: String) = Parameter(name)
        fun of(kind: KClass<*>, vararg arguments: StoryTypeShape) =
            Applied(kind, arguments.map { Projection(it) })

        fun projected(kind: KClass<*>, vararg arguments: Projection) = Applied(kind, arguments.toList())
        fun named(name: String, vararg arguments: StoryTypeShape) = Named(name, arguments.map { Projection(it) })
        fun listOf(element: StoryTypeShape) = of(List::class, element)
        fun mapOf(key: StoryTypeShape, value: StoryTypeShape) = of(Map::class, key, value)
        fun mutableListOf(element: StoryTypeShape) = named("kotlin.collections.MutableList", element)
        fun mutableMapOf(key: StoryTypeShape, value: StoryTypeShape) = named("kotlin.collections.MutableMap", key, value)
    }
}
