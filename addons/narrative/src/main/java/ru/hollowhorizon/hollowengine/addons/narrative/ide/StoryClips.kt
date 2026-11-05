package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryEntityModel
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryEntityModels
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.editor.WorldNpcModels
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager

internal data class StoryNodeClips(val model: String, val names: List<String>?)

internal data class StoryClips(private val byNode: Map<String, StoryNodeClips>) {
    fun of(node: String): StoryNodeClips? = byNode[node]

    companion object {
        val None = StoryClips(emptyMap())
    }
}

@Composable
internal fun rememberStoryClips(graph: StoryGraph): StoryClips {
    val sources = remember(graph) { StoryEntityModels.of(graph, StoryActionFunctions::signature) }
    if (sources.isEmpty()) return StoryClips.None
    val models = sources.mapValues { (_, source) ->
        when (source) {
            is StoryEntityModel.Path -> source.model
            is StoryEntityModel.Npc -> WorldNpcModels.of(source.id)?.model
        }
    }
    val names = HashMap<String, List<String>?>()
    models.values.filterNotNull().distinct().forEach { path ->
        key(path) { names[path] = clipsOf(path) }
    }
    return StoryClips(models.mapNotNull { (node, path) -> path?.let { node to StoryNodeClips(it, names[it]) } }.toMap())
}

@Composable
private fun clipsOf(path: String): List<String>? {
    val location = remember(path) { ResourceLocation.tryParse(path) } ?: return null
    val model by remember(location) { HollowModelManager.getOrCreate(location) }.collectAsState()
    return model.takeIf { it !== Model.EMPTY }?.animations?.map { it.name }?.sorted()
}
