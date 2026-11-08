package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** How the lists of operators are written. */
internal object StoryOperatorDsl {
    val T = StoryTypeShape.parameter("T")
    val K = StoryTypeShape.parameter("K")
    val V = StoryTypeShape.parameter("V")

    fun input(name: String, type: StoryPinType, default: String = "") = StoryOperatorInput(name, type, default)

    fun out(name: String, type: StoryPinType, template: String) = StoryOperatorOutput(name, type, template)

    fun operator(id: String, category: String, inputs: List<StoryOperatorInput>, vararg outputs: StoryOperatorOutput) =
        StoryOperator(id, category, inputs, outputs.toList())

    fun number(name: String, default: String = "0") = input(name, StoryPinType.NUMBER, default)

    /** An input of the type [shape], as `List<T>`, its pin of [type]. */
    fun typed(name: String, shape: StoryTypeShape, type: StoryPinType = StoryPinType.ANY, default: String = "") =
        StoryOperatorInput(name, type, default, shape = shape)

    /** An input of `T`, or of `T?` when it takes a value that may be [empty]. */
    fun generic(name: String, default: String = "", empty: Boolean = false) =
        typed(name, if (empty) T.nullable else T, default = default)

    /** An output of the type [shape], `T` by default. */
    fun outOf(name: String, template: String, shape: StoryTypeShape = T, type: StoryPinType = StoryPinType.ANY) =
        StoryOperatorOutput(name, type, template, shape = shape)
}
