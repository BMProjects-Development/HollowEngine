package ru.hollowhorizon.hollowengine.common.scripting.ide

/**
 * Subtyping between [KotlinType]s by the [classes] analysis described.
 */
class KotlinTypeSystem(val classes: Map<String, KotlinClassInfo>) {
    private val ancestors: Map<String, List<String>> = classes.keys.associateWith(::ancestorsOf)
    private val complete: Set<String> = ancestors.filterValues { line -> line.all(::knows) }.keys

    fun knows(name: String): Boolean = name in classes || name == KotlinType.ANY || name == KotlinType.NOTHING

    fun knows(type: KotlinType): Boolean = when (type) {
        is KotlinType.Parameter -> true
        is KotlinType.Class -> knows(type.name) && type.arguments.all { it.type?.let(::knows) ?: true }
    }

    fun ancestors(name: String): List<String>? = ancestors[name] ?: when (name) {
        KotlinType.ANY -> listOf(KotlinType.ANY)
        else -> null
    }

    /**
     * [type] seen as its supertype of the class [name], with the arguments that follow from its own.
     */
    fun supertype(type: KotlinType.Class, name: String): KotlinType.Class? {
        if (type.name == name) return type
        if (name == KotlinType.ANY) return KotlinType.Class(KotlinType.ANY, nullable = type.nullable)
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(type.nullable(false)))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next.name)) continue
            if (next.name == name) return next.nullable(type.nullable)
            val info = classes[next.name] ?: continue
            val arguments =
                info.parameters.zip(next.arguments) { parameter, argument -> parameter.name to argument }.toMap()
            info.supertypes.mapTo(queue) { it.substitute(arguments) }
        }
        return null
    }

    /**
     * Whether a value of [sub] can go where [sup] is taken.
     *
     * @return null when that turns on a class that is not described.
     */
    fun isSubtype(sub: KotlinType, sup: KotlinType): Boolean? {
        if (sub.nullable && !sup.nullable) return false
        if (sub is KotlinType.Parameter || sup is KotlinType.Parameter) {
            return if (sub is KotlinType.Parameter && sup is KotlinType.Parameter && sub.name == sup.name) true else null
        }
        sub as KotlinType.Class
        sup as KotlinType.Class
        if (sub.name == KotlinType.NOTHING || sup.name == KotlinType.ANY) return true
        val ancestors = ancestors(sub.name) ?: return if (sub.name == sup.name) arguments(sub, sup) else null
        if (sup.name !in ancestors) return if (sub.name in complete) false else null
        return arguments(supertype(sub, sup.name) ?: return null, sup)
    }

    /**
     * The nearest type values of every one of [types] have, as Kotlin infers it for `if (a) x else y`.
     *
     * @param ignored classes that do not count as having anything in common, such as `java.io.Serializable`, which
     * values of all sorts are.
     */
    fun commonSupertype(types: List<KotlinType>, ignored: Set<String> = emptySet()): KotlinType? =
        common(types, ignored, 0)

    /**
     * What the type parameters of [shape] stand for in [actual], as `T` of `List<T>` is `Foo` in `ArrayList<Foo>`.
     * Those [actual] does not tell are left out. Null when [actual] is not of the shape, or that is not known.
     */
    fun match(shape: KotlinType, actual: KotlinType): Map<String, KotlinType>? {
        val found = HashMap<String, KotlinType>()
        return found.takeIf { bind(shape, actual, found) }
    }

    private fun bind(shape: KotlinType, actual: KotlinType, found: MutableMap<String, KotlinType>): Boolean =
        when (shape) {
            is KotlinType.Parameter -> {
                val type = if (shape.nullable) actual.nullable(false) else actual
                found.putIfAbsent(shape.name, type)?.let { it == type } ?: true
            }

            is KotlinType.Class -> {
                val seen = (actual as? KotlinType.Class)?.let { supertype(it, shape.name) }
                seen != null && shape.arguments.zip(seen.arguments).all { (wanted, given) ->
                    val type = wanted.type
                    val value = given.type
                    type == null || value == null || bind(type, value, found)
                }
            }
        }

    private fun arguments(sub: KotlinType.Class, sup: KotlinType.Class): Boolean? {
        if (sup.arguments.isEmpty() || sub.arguments.size != sup.arguments.size) return true
        val parameters = classes[sup.name]?.parameters
        var answer: Boolean? = true
        sup.arguments.forEachIndexed { index, wanted ->
            val declared = parameters?.getOrNull(index)?.variance ?: KotlinVariance.INVARIANT
            when (contains(sub.arguments[index], wanted, declared)) {
                false -> return false
                null -> answer = null
                true -> Unit
            }
        }
        return answer
    }

    private fun contains(given: KotlinTypeArgument, wanted: KotlinTypeArgument, declared: KotlinVariance): Boolean? {
        val type = wanted.type ?: return true
        val variance = when {
            wanted.variance == KotlinVariance.INVARIANT -> declared
            declared == KotlinVariance.INVARIANT || declared == wanted.variance -> wanted.variance
            else -> return true
        }
        val value = given.type ?: return if (variance == KotlinVariance.OUT) isSubtype(ANY_NULLABLE, type) else false
        return when (variance) {
            KotlinVariance.OUT -> if (given.variance == KotlinVariance.IN) false else isSubtype(value, type)
            KotlinVariance.IN -> if (given.variance == KotlinVariance.OUT) false else isSubtype(type, value)
            KotlinVariance.INVARIANT -> {
                if (given.variance != KotlinVariance.INVARIANT) return false
                val down = isSubtype(value, type) ?: return null
                val up = isSubtype(type, value) ?: return null
                down && up
            }
        }
    }

    private fun common(types: List<KotlinType>, ignored: Set<String>, depth: Int): KotlinType? {
        val nullable = types.any { it.nullable }
        val values = types.map { it.nullable(false) }.filterNot { it == NOTHING }.distinct()
        values.singleOrNull()?.let { return it.nullable(nullable) }
        if (values.isEmpty()) return NOTHING.nullable(nullable)
        val classes = values.map { it as? KotlinType.Class ?: return null }
        val lines = classes.map { ancestors(it.name) ?: return null }
        val shared = lines.first().filter { name -> name !in ignored && lines.all { name in it } }
        val nearest =
            shared.filter { name -> shared.none { other -> other != name && name in ancestors(other).orEmpty() } }
        val name =
            nearest.firstOrNull { this.classes[it]?.isInterface == false } ?: nearest.firstOrNull() ?: KotlinType.ANY
        val seen = classes.map { supertype(it, name) ?: return null }
        val parameters = this.classes[name]?.parameters.orEmpty()
        val arguments = parameters.indices.map { index ->
            val given = seen.map { it.arguments.getOrNull(index) ?: return null }
            commonArgument(given, parameters[index].variance, ignored, depth)
        }
        return KotlinType.Class(name, arguments, nullable)
    }

    private fun commonArgument(
        given: List<KotlinTypeArgument>,
        declared: KotlinVariance,
        ignored: Set<String>,
        depth: Int,
    ): KotlinTypeArgument {
        given.distinct().singleOrNull()?.let { return it }
        if (depth >= MAX_DEPTH || declared == KotlinVariance.IN || given.any { it.isStar || it.variance == KotlinVariance.IN }) {
            return KotlinTypeArgument.STAR
        }
        val type = common(given.map { it.type!! }, ignored, depth + 1) ?: return KotlinTypeArgument.STAR
        return KotlinTypeArgument(
            type,
            if (declared == KotlinVariance.OUT) KotlinVariance.INVARIANT else KotlinVariance.OUT
        )
    }

    private fun ancestorsOf(name: String): List<String> {
        val found = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(name))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!found.add(next)) continue
            classes[next]?.supertypes?.mapTo(queue) { it.name }
        }
        found += KotlinType.ANY
        return found.toList()
    }

    companion object {
        /** How deep arguments of arguments are made common before they become stars, as `Comparable<*>` of numbers. */
        private const val MAX_DEPTH = 2

        private val ANY_NULLABLE = KotlinType.Class(KotlinType.ANY, nullable = true)
        private val NOTHING = KotlinType.Class(KotlinType.NOTHING)

        val EMPTY = KotlinTypeSystem(emptyMap())
    }
}
