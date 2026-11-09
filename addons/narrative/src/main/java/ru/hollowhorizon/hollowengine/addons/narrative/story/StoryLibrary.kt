package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File

/**
 * The functions every story of the game declares, read from their files, for a story to call those of another. A file
 * is read again only once it changes.
 */
internal object StoryLibrary {
    /** The functions of a file and the stories it calls, as of the time it was last changed. */
    private class Read(val changed: Long, val functions: List<StoryNode.Function>, val imports: Set<String>)

    private val read = HashMap<String, Read>()

    /** The function [name] of the story the qualified script id [file] names. */
    fun function(file: String, name: String): StoryNode.Function? = functions(file).firstOrNull { it.name == name }

    /** The functions of the story the qualified script id [file] names. */
    fun functions(file: String): List<StoryNode.Function> = read(file)?.functions.orEmpty()

    /** The qualified script ids of the stories the story [file] calls functions of. */
    fun imports(file: String): Set<String> = read(file)?.imports.orEmpty()

    private fun read(file: String): Read? {
        val source = ScriptRegistry.artifacts(ScriptId.parse(file, ScriptRegistry.sandboxNamespace))?.sourceFile
            ?.takeIf(File::isFile) ?: return null
        val changed = source.lastModified()
        synchronized(read) {
            read[file]?.takeIf { it.changed == changed }?.let { return it }
        }
        val graph = runCatching { StoryGraph.decode(source.readText()) }.getOrNull()
        val imports = graph?.flattened()?.nodes.orEmpty().filterIsInstance<StoryNode.Invoke>().mapNotNull { it.file }.toSet()
        val result = Read(changed, graph?.functions.orEmpty(), imports)
        synchronized(read) { read[file] = result }
        return result
    }

    /** Every story of the game by its qualified script id. */
    fun stories(): List<String> = ScriptRegistry.list(StoryScripts.EXTENSION).map { it.qualified }.sorted()
}
