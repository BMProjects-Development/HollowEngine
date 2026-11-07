package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperator
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOperatorOutput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryParam
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPinType

object ValueOperators {
    const val VALUE = "value"
    private const val DEFAULT_CATEGORY = "values"

    /** What the entity a value reads is when nothing is given: the player of the story. */
    const val PLAYER = "storyPlayer()"

    private class Built(val actions: List<StoryAction>, val operators: Map<String, StoryOperator>)

    @Volatile
    private var built = Built(emptyList(), emptyMap())

    val all: Collection<StoryOperator> get() = current().values

    fun find(id: String): StoryOperator? {
        val operators = current()
        return operators[id] ?: if (':' in id) null else operators.values.singleOrNull { it.id.substringAfter(':') == id }
    }

    private fun current(): Map<String, StoryOperator> {
        val actions = StoryActions.all
        built.takeIf { it.actions === actions }?.let { return it.operators }
        val operators = actions.filter { it.value }.mapNotNull(::operatorOf).associateBy { it.id }
        built = Built(actions, operators)
        return operators
    }

    private fun operatorOf(action: StoryAction): StoryOperator? {
        val signature = StoryCommand.of(action)?.signature ?: return null
        if (signature.params.any { it.group != null }) return null
        return StoryOperator(
            id = signature.name,
            category = signature.category?.removePrefix(StoryCategory.PREFIX) ?: DEFAULT_CATEGORY,
            inputs = signature.params.map { StoryOperatorInput(it.name, StoryPinType.of(it.type), defaultOf(it), it) },
            outputs = listOf(
                StoryOperatorOutput(VALUE, StoryPinType.of(action.function.returnType), "", kotlin = signature.returns),
            ),
            function = signature.function,
            title = signature.title,
            description = signature.description,
            icon = signature.icon,
        )
    }

    private fun defaultOf(param: StoryParam): String = param.implicit ?: when {
        param.optional -> ""
        StoryPinType.of(param.type) == StoryPinType.ENTITY || StoryPinType.of(param.type) == StoryPinType.PLAYER -> PLAYER
        else -> ""
    }
}
