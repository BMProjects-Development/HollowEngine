package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryHandle
import kotlin.reflect.typeOf

/** How a call behaves when a restart interrupts the story. */
enum class StoryCallKind {
    /** Cannot suspend, so nothing can happen between it and the steps next to it. */
    INSTANT,

    /** An `@Action`: the stage compiler already keeps it from repeating once done. */
    ACTION,

    /** May suspend with nothing to mark how far it got, such as `say` waiting to be read. */
    SUSPENDING,
}

/** What is known of the commands a story calls. A node names its command, and everything else about it comes from here. */
interface StoryFunctions {
    fun signature(call: StoryNode.Call): StoryFunctionSignature?

    fun kind(call: StoryNode.Call): StoryCallKind

    /** Knows the functions of the runtime alone; a call of anything else is treated as able to suspend. */
    object Builtin : StoryFunctions {
        override fun signature(call: StoryNode.Call) = call.action?.let(StoryBuiltins::find)

        override fun kind(call: StoryNode.Call) = StoryCallKind.SUSPENDING
    }
}

/** [call] with what its [StoryFunctions.signature] says, which beats anything a file written by an older version holds. */
fun StoryFunctions.resolved(call: StoryNode.Call): StoryNode.Call {
    val signature = signature(call) ?: return call
    return call.copy(
        function = signature.function,
        returns = signature.returns,
        block = signature.block,
        receiver = signature.params.firstOrNull { it.receiver }?.name,
    )
}

/** The functions of the story runtime that steer a story rather than act on the world. */
object StoryBuiltins {
    private const val STORY_PACKAGE = "ru.hollowhorizon.hollowengine.addons.narrative.story"
    private const val HANDLE = "$STORY_PACKAGE.StoryHandle<kotlin.Unit>"

    val all: List<StoryFunctionSignature> = listOf(
        builtin("checkpoint", param("name"), flag("restart")),
        builtin("rewind", param("checkpoint", optional = true)),
        builtin("temporary", param("until", optional = true)).copy(block = true),
        builtin("async", param("name")).copy(returns = HANDLE, block = true),
        builtin("join", handle()).copy(function = "join"),
        builtin("cancel", handle()).copy(function = "cancel"),
        builtin("scene", param("address")),
    )

    fun find(action: String): StoryFunctionSignature? = all.firstOrNull { it.name == action }

    private fun builtin(name: String, vararg params: StoryParam) =
        StoryFunctionSignature(name, "$STORY_PACKAGE.$name", params.toList())

    private fun handle() = StoryParam("handle", typeOf<StoryHandle<*>>(), receiver = true)

    private fun param(name: String, optional: Boolean = false) = StoryParam(name, typeOf<String>(), optional)

    private fun flag(name: String) = StoryParam(name, typeOf<Boolean>(), optional = true, suggestions = listOf("true", "false"))
}
