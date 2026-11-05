package ru.hollowhorizon.hollowengine.addons.narrative.graph

sealed interface StoryEntityModel {
    data class Path(val model: String) : StoryEntityModel
    data class Npc(val id: String) : StoryEntityModel
}

object StoryEntityModels {
    fun of(graph: StoryGraph, signatureOf: (StoryNode.Call) -> StoryFunctionSignature?): Map<String, StoryEntityModel> {
        val plain = graph.dissolved()
        val nodes = plain.nodes.associateBy { it.id }
        val writers = plain.nodes.filterIsInstance<StoryNode.Assign>().groupBy { it.variable }
        val result = HashMap<String, StoryEntityModel>()
        plain.nodes.filterIsInstance<StoryNode.Call>().forEach { call ->
            val signature = signatureOf(call) ?: return@forEach
            if (signature.params.none { it.meaning == StoryParamMeaning.ANIMATION }) return@forEach
            val target = signature.params.firstOrNull { it.receiver } ?: return@forEach
            val sources = Sources(plain, nodes, writers, signatureOf).into(call.id, target.name)
            sources.distinct().singleOrNull()?.let { result[call.id] = it }
        }
        return result
    }

    private class Sources(
        private val graph: StoryGraph,
        private val nodes: Map<String, StoryNode>,
        private val writers: Map<String, List<StoryNode.Assign>>,
        private val signatureOf: (StoryNode.Call) -> StoryFunctionSignature?,
    ) {
        private val seen = HashSet<String>()

        fun into(node: String, pin: String): List<StoryEntityModel> {
            val link = graph.index.values[node to pin] ?: return emptyList()
            return when (val from = nodes[link.from]) {
                is StoryNode.Call -> listOfNotNull(made(from))
                is StoryNode.Expression -> writers[from.code.trim()].orEmpty().filter { seen.add(it.id) }
                    .flatMap { into(it.id, StoryPins.VALUE) }

                else -> emptyList()
            }
        }

        private fun made(call: StoryNode.Call): StoryEntityModel? {
            val params = signatureOf(call)?.params ?: return null
            fun param(meaning: StoryParamMeaning) = params.firstOrNull { it.meaning == meaning }
            fun given(param: StoryParam?) =
                param?.let(call::argument)?.takeIf { StoryLiterals.text(it, param.type).isNotBlank() }
            val model = param(StoryParamMeaning.MODEL)
            val npc = param(StoryParamMeaning.NPC_ID)
            given(model)?.let { return StoryLiterals.literal(it, model?.type)?.let(StoryEntityModel::Path) }
            given(npc)?.let { return StoryLiterals.literal(it, npc?.type)?.let(StoryEntityModel::Npc) }
            return model?.fallback?.let(StoryEntityModel::Path)
        }
    }
}
