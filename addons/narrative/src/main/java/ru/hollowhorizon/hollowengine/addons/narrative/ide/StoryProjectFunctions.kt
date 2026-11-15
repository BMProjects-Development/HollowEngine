package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryLibrary
import ru.hollowhorizon.hollowengine.addons.narrative.story.importedStories
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.common.addons.project.ProjectProperties
import ru.hollowhorizon.hollowengine.common.scripting.source.SandboxScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.IOException

/** Files reached by calls from the open story. The project browser continues loading folders on demand. */
internal class StoryProjectFunctions private constructor(
    private val project: SandboxScriptSource.SandboxDescriptor,
    private val stories: Map<ScriptId, StoryGraph>,
) : StoryFunctions by StoryActionFunctions {
    override fun storyFunction(file: String, name: String): StoryNode.Function? =
        functions(file).firstOrNull { it.name == name }

    override fun storyImports(file: String): Set<String> {
        val id = ScriptId.parse(file, project.id)
        return if (id.namespace == project.id) stories[id]?.importedStories().orEmpty()
        else if (isClientDependency(id)) StoryLibrary.imports(id.qualified) else emptySet()
    }

    fun functions(file: String): List<StoryNode.Function> {
        val id = ScriptId.parse(file, project.id)
        return if (id.namespace == project.id) stories[id]?.functions.orEmpty()
        else if (isClientDependency(id)) StoryLibrary.functions(id.qualified) else emptyList()
    }

    fun sources(): List<String> = stories.keys.map(ScriptId::qualified).sorted()

    fun sourceId(path: String): String = ScriptId(
        project.id, path.removePrefix("${SandboxScriptSource.SCRIPTS_DIRECTORY}/"),
    ).qualified

    private fun isClientDependency(id: ScriptId): Boolean =
        id.namespace != ScriptRegistry.sandboxNamespace && id.namespace in project.dependencies

    companion object {
        val Empty = StoryProjectFunctions(SandboxScriptSource.descriptor(ProjectProperties()), emptyMap())

        suspend fun read(file: HollowIdeOpenFile, imports: Set<String>): StoryProjectFunctions {
            return try {
                val descriptor =
                    file.readProjectFile(ProjectProperties.PATH)?.inputStream()?.use(ProjectProperties::read)
                        ?: ProjectProperties()
                val project = SandboxScriptSource.descriptor(descriptor)
                val stories = LinkedHashMap<ScriptId, StoryGraph>()
                val visited = HashSet<ScriptId>()
                val queue = ArrayDeque(imports)
                while (queue.isNotEmpty()) {
                    val id = runCatching { ScriptId.parse(queue.removeFirst(), project.id) }.getOrNull() ?: continue
                    if (id.namespace != project.id || !visited.add(id)) continue
                    val bytes = file.readProjectFile("${SandboxScriptSource.SCRIPTS_DIRECTORY}/${id.path}") ?: continue
                    val graph = runCatching { StoryGraph.decode(bytes.decodeToString()) }.getOrNull() ?: continue
                    stories[id] = graph
                    queue.addAll(graph.importedStories())
                }
                StoryProjectFunctions(project, stories)
            } catch (failure: IOException) {
                HollowEngine.LOGGER.warn("Could not read dependencies of story {}", file.projectPath, failure)
                Empty
            } catch (failure: IllegalArgumentException) {
                HollowEngine.LOGGER.warn("Could not read project descriptor for story {}", file.projectPath, failure)
                Empty
            }
        }
    }
}
