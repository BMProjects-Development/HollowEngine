package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.resolved
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.UiColor

/**
 * What a node of a story is called and how it is painted: [tag] is its style, [color] that of its title bar and of its
 * icon in the menus, unless its category gives it one.
 */
internal enum class StoryNodeKind(val tag: String, icon: String, name: String, color: Int) {
    PHRASE("phrase", "phrase", "phrase", 0x3B8950),
    WAIT("wait", "wait", "wait", 0x3E86C4),
    CHOICE("choice", "choice", "choice", 0xBF8246),
    CONDITION("condition", "condition", "condition", 0x7A5FB5),
    START("start", "label", "start", 0xCF5E54),
    FUNCTION("block", "block", "function", 0x2B8A9E),
    PARAMETERS("start", "label", "parameters", 0x2B8A9E),
    RETURN("start", "label", "return", 0x2B8A9E),
    SIGNAL("command", "command", "signal", 0x2B8A9E),
    INVOKE("command", "command", "invoke", 0x2B8A9E),
    SET("command", "command", "set", 0x4C5566),
    CODE("block", "block", "code", 0x5F59B1),
    VALUE("pure", "block", "expression", 0x2F7D80),
    LOOP("block", "block", "loop", 0x5F59B1),
    BLOCK("block", "block", "block", 0x5F59B1),
    COMMAND("command", "command", "command", 0x4C5566);

    val icon = storyIcon(icon)
    val color = UiColor.fromArgb(0xFF shl 24 or color)
    val title: String by lazy { storyLang("kind.$name") }

    companion object {
        private const val WAIT_ACTION = "hollowengine-narrative:wait"

        fun of(node: StoryNode): StoryNodeKind = when (node) {
            is StoryNode.Start -> START
            is StoryNode.Function -> FUNCTION
            is StoryNode.Parameters -> PARAMETERS
            is StoryNode.Return -> RETURN
            is StoryNode.Signal -> SIGNAL
            is StoryNode.Invoke -> INVOKE
            is StoryNode.Say -> PHRASE
            is StoryNode.Choose -> CHOICE
            is StoryNode.Branch -> CONDITION
            is StoryNode.Assign -> SET
            is StoryNode.Code -> CODE
            is StoryNode.Expression, is StoryNode.Pure, is StoryNode.Reroute -> VALUE
            is StoryNode.Loop -> LOOP
            is StoryNode.Call -> when {
                StoryActionFunctions.resolved(node).block -> BLOCK
                node.action == WAIT_ACTION -> WAIT
                else -> COMMAND
            }
        }
    }
}
