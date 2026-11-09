package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
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

    /** The function [name] of the story the qualified script id [file] names, when there is one. */
    fun storyFunction(file: String, name: String): StoryNode.Function? = null

    /** The qualified script ids of the stories that the story [file] calls functions of. */
    fun storyImports(file: String): Set<String> = emptySet()

    /** Knows the functions of the runtime alone; a call of anything else is treated as able to suspend. */
    object Builtin : StoryFunctions {
        override fun signature(call: StoryNode.Call) = call.action?.let(StoryBuiltins::find)

        override fun kind(call: StoryNode.Call) = StoryCallKind.SUSPENDING
    }
}

/** The function [invoke] calls: one of [graph], or one of the story it names. */
fun StoryFunctions.definition(graph: StoryGraph, invoke: StoryNode.Invoke): StoryNode.Function? {
    val file = invoke.file ?: return graph.functions.firstOrNull { it.name == invoke.function }
    return storyFunction(file, invoke.function)
}

/** Whether the story [file] is [target] or imports it, itself or through the stories it imports. */
fun StoryFunctions.reaches(file: String, target: String): Boolean {
    val seen = hashSetOf(file)
    val queue = ArrayDeque(listOf(file))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (next == target) return true
        storyImports(next).filter(seen::add).forEach(queue::addLast)
    }
    return false
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
    private const val FLOW = StoryCategory.FLOW

    val all: List<StoryFunctionSignature> = listOf(
        builtin("checkpoint", param("name"), flag("restart")),
        builtin("rewind", param("checkpoint", optional = true)),
        builtin("temporary", param("until", optional = true)).copy(block = true, plainBlock = true),
        builtin("async", param("name")).copy(returns = HANDLE, block = true),
        builtin("join", handle()).copy(function = "join"),
        builtin("cancel", handle()).copy(function = "cancel"),
        builtin("scene", param("address")),
    )

    fun find(action: String): StoryFunctionSignature? = all.firstOrNull { it.name == action }

    private fun builtin(name: String, vararg params: StoryParam) =
        StoryFunctionSignature(name, "$STORY_PACKAGE.$name", params.toList(), category = FLOW)

    private fun handle() = StoryParam("handle", typeOf<StoryHandle<*>>(), receiver = true)

    private fun param(name: String, optional: Boolean = false) = StoryParam(name, typeOf<String>(), optional)

    private fun flag(name: String) =
        StoryParam(name, typeOf<Boolean>(), optional = true, traits = StoryParamTrait.choices(listOf("true", "false")))
}
