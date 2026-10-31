package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryCallKind
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptTranslator
import java.io.File
import kotlin.reflect.KType

/**
 * The `.story` files as running scripts: each is compiled and kept running while its text stays the same, so that
 * [saved] variables and the stories in it live on through a reload that did not touch it.
 */
internal object StoryScripts : ScriptTranslator {
    const val EXTENSION = ".story"
    private const val MAIN = "main"
    private const val NARRATIVE = "hollowengine-narrative"

    private class Loaded(val text: String, val scope: CoroutineScope)

    private val loaded = HashMap<ScriptId, Loaded>()

    override fun translate(id: ScriptId, source: File): String {
        val story = StoryGraphCompiler.compile(StoryGraph.decode(source.readText()), ::kindOf, ::paramType)
        if (story.problems.isEmpty()) return story.code
        val problems = story.problems.joinToString("; ") { problem ->
            problem.node?.let { "$it: ${problem.message}" } ?: problem.message
        }
        throw StoryException("Story '${ScriptRegistry.display(id)}' cannot be built: $problems")
    }

    /** Brings the running stories in line with the files. */
    fun reload() {
        val files = ScriptRegistry.list(EXTENSION).toSet()
        (loaded.keys - files).forEach { loaded.remove(it)?.scope?.cancel() }
        files.forEach { id ->
            runCatching { ensure(id) }.onFailure { HollowEngine.LOGGER.error(it.message, it) }
        }
    }

    /** The instance key and the stage [address] names: `path/name.story`, or `path/name.story#stage`. */
    fun resolve(address: String): Pair<String, String> {
        val file = address.substringBefore('#')
        return ensure(find(file)) to address.substringAfter('#', MAIN)
    }

    /** Stops everything, as the server that ran it ends. */
    fun stop() {
        loaded.values.forEach { it.scope.cancel() }
        loaded.clear()
    }

    private fun find(file: String): ScriptId {
        val exact = ScriptRegistry.parse(file)
        if (ScriptRegistry.artifacts(exact) != null) return exact
        val found = ScriptRegistry.list(EXTENSION).filter { it.path == file || it.path.endsWith("/$file") }
        return found.singleOrNull() ?: throw StoryException(
            if (found.isEmpty()) "There is no story '$file'" else "'$file' names several stories: ${found.joinToString()}",
        )
    }

    /** Runs the file of [id] unless it runs already as it is now, and gives its key. */
    private fun ensure(id: ScriptId): String {
        val key = ScriptRegistry.display(id)
        val text = ScriptRegistry.artifacts(id)?.sourceFile?.readText()
        val current = loaded[id]
        if (current != null && (text == null || current.text == text)) return key
        val instance = ScriptLoader.execute<Any>(id).getOrElse { throw failure(id, it) }
        val scope = CoroutineScope(SupervisorJob())
        StoryInstances.register(instance, scope)
        loaded.put(id, Loaded(text.orEmpty(), scope))?.scope?.cancel()
        return key
    }

    private fun failure(id: ScriptId, cause: Throwable): StoryException {
        val hint =
            if (NARRATIVE in ScriptRegistry.source(id.namespace)?.dependencies.orEmpty()) "" else " List '$NARRATIVE' in dependsOn of the project it belongs to."
        return StoryException("Story '${ScriptRegistry.display(id)}' failed to load: ${cause.message}$hint", cause)
    }

    private fun paramType(call: StoryNode.Call, name: String): KType? =
        call.action?.let(ActionCommands::find)?.singleOrNull()?.signature?.params?.firstOrNull { it.name == name }?.type

    private fun kindOf(call: StoryNode.Call): StoryCallKind {
        val action = call.action?.let(ActionCommands::find)?.singleOrNull()?.action ?: return StoryCallKind.SUSPENDING
        return when {
            action.reversible -> StoryCallKind.ACTION
            !action.function.isSuspend -> StoryCallKind.INSTANT
            else -> StoryCallKind.SUSPENDING
        }
    }
}
