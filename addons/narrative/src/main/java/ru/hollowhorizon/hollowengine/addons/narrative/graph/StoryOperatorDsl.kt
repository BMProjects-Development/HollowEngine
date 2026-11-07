package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** How the lists of operators are written. */
internal object StoryOperatorDsl {
    fun input(name: String, type: StoryPinType, default: String = "") = StoryOperatorInput(name, type, default)

    fun out(name: String, type: StoryPinType, template: String) = StoryOperatorOutput(name, type, template)

    fun operator(id: String, category: String, inputs: List<StoryOperatorInput>, vararg outputs: StoryOperatorOutput) =
        StoryOperator(id, category, inputs, outputs.toList())

    fun number(name: String, default: String = "0") = input(name, StoryPinType.NUMBER, default)

    /** An input of `T`, or of `T?` when it takes a value that may be [empty]. */
    fun generic(name: String, default: String = "", empty: Boolean = false) =
        StoryOperatorInput(name, StoryPinType.ANY, default, generic = true, takesEmpty = empty)

    /** An output of `T`. */
    fun outOf(name: String, template: String) = StoryOperatorOutput(name, StoryPinType.ANY, template, generic = true)
}
