package ru.hollowhorizon.hollowengine.common.scripting.ide

import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinType.Companion.parse


/**
 * A Kotlin type as analysis resolved it, with classes named in full, so it can be compared and taken apart without the
 * compiler. Its [render]ed text is canonical: [parse] reads it back.
 */
sealed interface KotlinType {
    val nullable: Boolean

    fun nullable(nullable: Boolean): KotlinType

    /** The type with every type parameter named in [arguments] replaced by what it maps to. */
    fun substitute(arguments: Map<String, KotlinTypeArgument>): KotlinType

    fun render(): String

    data class Class(
        val name: String,
        val arguments: List<KotlinTypeArgument> = emptyList(),
        override val nullable: Boolean = false,
    ) : KotlinType {
        override fun nullable(nullable: Boolean) = if (nullable == this.nullable) this else copy(nullable = nullable)

        override fun substitute(arguments: Map<String, KotlinTypeArgument>): Class =
            if (arguments.isEmpty()) this else copy(arguments = this.arguments.map { it.substitute(arguments) })

        override fun render(): String {
            val listed =
                if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">") { it.render() }
            return name + listed + if (nullable) "?" else ""
        }

        override fun toString() = render()
    }

    data class Parameter(val name: String, override val nullable: Boolean = false) : KotlinType {
        override fun nullable(nullable: Boolean) = if (nullable == this.nullable) this else copy(nullable = nullable)

        override fun substitute(arguments: Map<String, KotlinTypeArgument>): KotlinType {
            val type = arguments[name]?.type ?: return this
            return if (nullable) type.nullable(true) else type
        }

        override fun render() = name + if (nullable) "?" else ""

        override fun toString() = render()
    }

    companion object {
        const val ANY = "kotlin.Any"
        const val NOTHING = "kotlin.Nothing"

        /**
         * The type the canonical [text] renders; null when it is not one, as with a function type written with an arrow.
         *
         * @param parameters names that stand for type parameters rather than classes.
         */
        fun parse(text: String, parameters: Set<String> = emptySet()): KotlinType? =
            KotlinTypeParser(text, parameters).whole()
    }
}

enum class KotlinVariance(val keyword: String) {
    INVARIANT(""), OUT("out "), IN("in "),
}

/**
 * A type argument of a class type.
 *
 * @param type null for a star projection.
 */
data class KotlinTypeArgument(val type: KotlinType?, val variance: KotlinVariance = KotlinVariance.INVARIANT) {
    val isStar: Boolean get() = type == null

    fun render(): String = type?.let { variance.keyword + it.render() } ?: "*"

    /**
     * The argument with type parameters replaced. A parameter that is the whole argument takes the projection of what
     * replaces it too, as `Iterable<E>` of `List<out Foo>` is `Iterable<out Foo>`; projections that disagree make a star.
     */
    fun substitute(arguments: Map<String, KotlinTypeArgument>): KotlinTypeArgument {
        val own = type ?: return this
        val replaced =
            (own as? KotlinType.Parameter)?.let { arguments[it.name] } ?: return copy(type = own.substitute(arguments))
        val replacedType = replaced.type ?: return STAR
        val variance = when {
            variance == replaced.variance || replaced.variance == KotlinVariance.INVARIANT -> variance
            variance == KotlinVariance.INVARIANT -> replaced.variance
            else -> return STAR
        }
        return KotlinTypeArgument(if (own.nullable) replacedType.nullable(true) else replacedType, variance)
    }

    override fun toString() = render()

    companion object {
        val STAR = KotlinTypeArgument(null)
    }
}

data class KotlinTypeParameter(val name: String, val variance: KotlinVariance = KotlinVariance.INVARIANT)

data class KotlinClassInfo(
    val name: String,
    val parameters: List<KotlinTypeParameter> = emptyList(),
    val supertypes: List<KotlinType.Class> = emptyList(),
    val isInterface: Boolean = false,
)

private class KotlinTypeParser(private val text: String, private val parameters: Set<String>) {
    private var at = 0

    fun whole(): KotlinType? = type()?.takeIf { at == text.length }

    private fun type(): KotlinType? {
        val name = name() ?: return null
        val arguments = if (take('<')) arguments() ?: return null else emptyList()
        val nullable = take('?')
        if (name in parameters) return KotlinType.Parameter(name, nullable).takeIf { arguments.isEmpty() }
        return KotlinType.Class(name, arguments, nullable)
    }

    private fun arguments(): List<KotlinTypeArgument>? {
        val arguments = ArrayList<KotlinTypeArgument>()
        do {
            take(' ')
            arguments += argument() ?: return null
        } while (take(','))
        return arguments.takeIf { take('>') }
    }

    private fun argument(): KotlinTypeArgument? {
        if (take('*')) return KotlinTypeArgument.STAR
        val variance =
            KotlinVariance.entries.firstOrNull { it != KotlinVariance.INVARIANT && text.startsWith(it.keyword, at) }
        variance?.let { at += it.keyword.length }
        return KotlinTypeArgument(type() ?: return null, variance ?: KotlinVariance.INVARIANT)
    }

    private fun name(): String? {
        val start = at
        do {
            if (at >= text.length || !Character.isJavaIdentifierStart(text[at])) return null
            while (at < text.length && Character.isJavaIdentifierPart(text[at])) at++
        } while (take('.'))
        return text.substring(start, at)
    }

    private fun take(char: Char): Boolean {
        if (at >= text.length || text[at] != char) return false
        at++
        return true
    }
}
