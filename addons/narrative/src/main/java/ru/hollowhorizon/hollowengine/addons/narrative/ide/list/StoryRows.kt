package ru.hollowhorizon.hollowengine.addons.narrative.ide.list

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFlow
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPins
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryStep
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StorySelection
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyIcon
import ru.hollowhorizon.hollowengine.addons.narrative.ide.storyLang

/** What a row of the list looks like. [tag] is its style, and a card of no color of its own has none. */
internal enum class StoryCardKind(val tag: String, icon: String, name: String) {
    PHRASE("phrase", "phrase", "phrase"),
    WAIT("wait", "wait", "wait"),
    CHOICE("choice", "choice", "choice"),
    CONDITION("condition", "condition", "condition"),
    START("start", "label", "start"),
    SET("command", "command", "set"),
    CODE("block", "block", "code"),
    BLOCK("block", "block", "block"),
    COMMAND("command", "command", "command"),
    JUMP("label", "label", "jump");

    val icon = storyIcon(icon)
    val title: String by lazy { storyLang("kind.$name") }

    companion object {
        private const val WAIT_ACTION = "hollowengine-narrative:wait"

        fun of(node: StoryNode): StoryCardKind = when (node) {
            is StoryNode.Start -> START
            is StoryNode.Say -> PHRASE
            is StoryNode.Choose -> CHOICE
            is StoryNode.Branch -> CONDITION
            is StoryNode.Assign -> SET
            is StoryNode.Code, is StoryNode.Expression -> CODE
            is StoryNode.Call -> when {
                node.block -> BLOCK
                node.action == WAIT_ACTION -> WAIT
                else -> COMMAND
            }
        }
    }
}

internal enum class StoryRowRole { START, CARD, PIN, JUMP }

/**
 * One row of the list.
 *
 * @param node the node it shows; of a [StoryRowRole.JUMP], the one the jump leads to.
 * @param pin the pin it stands for, on a [StoryRowRole.PIN] or a [StoryRowRole.JUMP].
 * @param from the node a [StoryRowRole.JUMP] leaves.
 * @param guides the kinds of what it is nested in.
 */
internal class StoryRow(
    val role: StoryRowRole,
    val kind: StoryCardKind,
    val node: StoryNode,
    val pin: String? = null,
    val from: String? = null,
    val guides: List<StoryCardKind> = emptyList(),
) {
    val key: String = listOfNotNull(from, node.id, pin).joinToString("/")
    val selection = StorySelection(from ?: node.id, pin)
    val tag: String get() = kind.tag

    /** Whether it can be dragged and moved: what is a place in the flow rather than a part of a card. */
    val moves: Boolean get() = role == StoryRowRole.CARD
}

/** The rows of a story from top to bottom, leaving out what is folded away. */
internal fun storyRows(flow: StoryFlow, folded: (String) -> Boolean): List<StoryRow> = buildList {
    fun walk(steps: List<StoryStep>, guides: List<StoryCardKind>) {
        steps.forEach { step ->
            when (step) {
                is StoryStep.Plain -> add(StoryRow(StoryRowRole.CARD, StoryCardKind.of(step.node), step.node, guides = guides))
                is StoryStep.Jump -> add(
                    StoryRow(StoryRowRole.JUMP, StoryCardKind.JUMP, step.target, step.pin, step.from, guides),
                )

                is StoryStep.Scoped -> {
                    val kind = StoryCardKind.of(step.node)
                    add(StoryRow(StoryRowRole.CARD, kind, step.node, guides = guides))
                    if (!folded(step.id)) walk(step.body, guides + kind)
                }

                is StoryStep.Split -> {
                    val kind = StoryCardKind.of(step.node)
                    add(StoryRow(StoryRowRole.CARD, kind, step.node, guides = guides))
                    if (folded(step.id)) return@forEach
                    step.pins.forEachIndexed { index, pin ->
                        add(StoryRow(StoryRowRole.PIN, kind, step.node, pin, guides = guides + kind))
                        if (!folded("${step.id}/$pin")) walk(step.branches[index], guides + kind + kind)
                    }
                }
            }
        }
    }
    flow.sections.forEach { section ->
        section.start?.let { add(StoryRow(StoryRowRole.START, StoryCardKind.START, it)) }
        walk(section.steps, emptyList())
    }
}

/** The text of [node] and its parts, for the search. */
internal fun searchText(node: StoryNode): String = when (node) {
    is StoryNode.Start -> node.name
    is StoryNode.Say -> "${node.speaker} ${node.text}"
    is StoryNode.Call -> "${node.function} ${node.arguments.values.joinToString(" ")}"
    is StoryNode.Choose -> node.options.joinToString(" ") { it.text }
    is StoryNode.Branch -> node.condition
    is StoryNode.Assign -> "${node.variable} ${node.value}"
    is StoryNode.Code -> node.code
    is StoryNode.Expression -> node.code
}

/** The nodes whose text holds [query], from the top of the list. */
internal fun storyMatches(flow: StoryFlow, query: String): List<StorySelection> {
    if (query.isBlank()) return emptyList()
    return storyRows(flow) { false }.filter { it.role != StoryRowRole.PIN && it.role != StoryRowRole.JUMP }
        .filter { searchText(it.node).contains(query, ignoreCase = true) }.map { it.selection }
}

/** What a [StoryRowRole.PIN] of a branch says. */
internal fun branchPinTitle(pin: String) = storyLang(if (pin == StoryPins.THEN) "branch.if" else "branch.else")
