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
    /** The functions of a file as of the time it was last changed. */
    private class Read(val changed: Long, val functions: List<StoryNode.Function>)

    private val read = HashMap<String, Read>()

    /** The function [name] of the story the qualified script id [file] names. */
    fun function(file: String, name: String): StoryNode.Function? = functions(file).firstOrNull { it.name == name }

    /** The functions of the story the qualified script id [file] names. */
    fun functions(file: String): List<StoryNode.Function> {
        val source = ScriptRegistry.artifacts(ScriptId.parse(file, ScriptRegistry.sandboxNamespace))?.sourceFile
            ?.takeIf(File::isFile) ?: return emptyList()
        val changed = source.lastModified()
        synchronized(read) {
            read[file]?.takeIf { it.changed == changed }?.let { return it.functions }
        }
        val functions = runCatching { StoryGraph.decode(source.readText()).functions }.getOrDefault(emptyList())
        synchronized(read) { read[file] = Read(changed, functions) }
        return functions
    }

    /** Every story of the game by its qualified script id. */
    fun stories(): List<String> = ScriptRegistry.list(StoryScripts.EXTENSION).map { it.qualified }.sorted()
}
