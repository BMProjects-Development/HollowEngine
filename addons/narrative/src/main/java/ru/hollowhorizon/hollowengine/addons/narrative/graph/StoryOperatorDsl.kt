package ru.hollowhorizon.hollowengine.addons.narrative.graph

/** How the lists of operators are written. */
internal object StoryOperatorDsl {
    fun input(name: String, type: StoryPinType, default: String = "") = StoryOperatorInput(name, type, default)

    fun out(name: String, type: StoryPinType, template: String) = StoryOperatorOutput(name, type, template)

    fun operator(id: String, category: String, inputs: List<StoryOperatorInput>, vararg outputs: StoryOperatorOutput) =
        StoryOperator(id, category, inputs, outputs.toList())

    fun number(name: String, default: String = "0") = input(name, StoryPinType.NUMBER, default)
}
