package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** How the lists of operators are written. */
internal object StoryOperatorDsl {
    const val LIST_OF = "kotlin.collections.List<T>"
    const val MAP_OF = "kotlin.collections.Map<K, V>"

    private val PARAMETER = Regex("""(?<![\w.])[TKV](?![\w.])""")
    private val ARGUMENT = Regex("""(?<![\w.])(?:out |in )?[TKV]\??(?![\w.])""")

    fun input(name: String, type: StoryPinType, default: String = "") = StoryOperatorInput(name, type, default)

    fun out(name: String, type: StoryPinType, template: String) = StoryOperatorOutput(name, type, template)

    fun operator(id: String, category: String, inputs: List<StoryOperatorInput>, vararg outputs: StoryOperatorOutput) =
        StoryOperator(id, category, inputs, outputs.toList())

    fun number(name: String, default: String = "0") = input(name, StoryPinType.NUMBER, default)

    /** An input of the type [shape], as `List<T>`, its pin of [type]. */
    fun typed(name: String, shape: String, type: StoryPinType = StoryPinType.ANY, default: String = "") =
        StoryOperatorInput(name, type, default, shape = shape)

    /** An input of `T`, or of `T?` when it takes a value that may be [empty]. */
    fun generic(name: String, default: String = "", empty: Boolean = false) =
        typed(name, if (empty) "T?" else "T", default = default)

    /** An output of the type [shape], `T` by default. */
    fun outOf(name: String, template: String, shape: String = "T", type: StoryPinType = StoryPinType.ANY) =
        StoryOperatorOutput(name, type, template, shape = shape)

    /** The type parameters [shape] is written with, in the order they first come: `K` and `V` of `Map<K, V>`. */
    fun parametersOf(shape: String): List<String> = PARAMETER.findAll(shape).map { it.value }.distinct().toList()

    /**
     * The class of [shape] with stars for its parameters, `List<*>` of `List<T>`: what it takes while they are not
     * known. Null for a bare parameter, which takes anything then.
     */
    fun starred(shape: String): String? =
        if (PARAMETER.matches(shape.removeSuffix("?"))) null else ARGUMENT.replace(shape, "*")
}
