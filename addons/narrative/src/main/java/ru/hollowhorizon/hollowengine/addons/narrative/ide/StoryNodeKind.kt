package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.resolved
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions

/** What a node of a story is called and how it is painted: [tag] is its style. */
internal enum class StoryNodeKind(val tag: String, icon: String, name: String) {
    PHRASE("phrase", "phrase", "phrase"),
    WAIT("wait", "wait", "wait"),
    CHOICE("choice", "choice", "choice"),
    CONDITION("condition", "condition", "condition"),
    START("start", "label", "start"),
    SET("command", "command", "set"),
    CODE("block", "block", "code"),
    LOOP("block", "block", "loop"),
    BLOCK("block", "block", "block"),
    COMMAND("command", "command", "command");

    val icon = storyIcon(icon)
    val title: String by lazy { storyLang("kind.$name") }

    companion object {
        private const val WAIT_ACTION = "hollowengine-narrative:wait"

        fun of(node: StoryNode): StoryNodeKind = when (node) {
            is StoryNode.Start -> START
            is StoryNode.Say -> PHRASE
            is StoryNode.Choose -> CHOICE
            is StoryNode.Branch -> CONDITION
            is StoryNode.Assign -> SET
            is StoryNode.Code, is StoryNode.Expression, is StoryNode.Pure, is StoryNode.Reroute -> CODE
            is StoryNode.Loop -> LOOP
            is StoryNode.Call -> when {
                StoryActionFunctions.resolved(node).block -> BLOCK
                node.action == WAIT_ACTION -> WAIT
                else -> COMMAND
            }
        }
    }
}
