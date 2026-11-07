package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals

class StoryEntityModelsTest {
    private val text = typeOf<String>()
    private val spawn = StoryFunctionSignature(
        "spawn", "spawn", listOf(
            StoryParam("id", text, optional = true, traits = listOf(StoryParamTrait.NpcId)),
            StoryParam("model", text, optional = true, traits = listOf(StoryParamTrait.Model("default.glb"))),
        ),
    )
    private val play = StoryFunctionSignature(
        "play", "play", listOf(
            StoryParam("who", receiver = true),
            StoryParam("animation", text, traits = listOf(StoryParamTrait.Animation)),
        ),
    )
    private val signatures = mapOf("spawn" to spawn, "play" to play)

    private fun spawn(id: String, vararg arguments: Pair<String, String>) =
        StoryNode.Call(id, action = "spawn", arguments = arguments.toMap())

    private fun play(id: String) = StoryNode.Call(id, action = "play", arguments = mapOf("animation" to "\"wave\""))

    private fun into(from: String, to: String, pin: String = "who") = StoryLink(from, StoryPins.VALUE, to, pin)

    private fun models(nodes: List<StoryNode>, links: List<StoryLink>) =
        StoryEntityModels.of(StoryGraph(nodes = nodes, links = links)) { signatures[it.action] }

    @Test
    fun `the model written where the NPC is spawned reaches the call through a reroute`() {
        val found = models(
            listOf(spawn("npc", "model" to "\"hollowengine:models/npc.glb\""), StoryNode.Reroute("dot", false), play("wave")),
            listOf(into("npc", "dot", StoryPins.VALUE), into("dot", "wave")),
        )
        assertEquals(mapOf("wave" to StoryEntityModel.Path("hollowengine:models/npc.glb")), found)
    }

    @Test
    fun `without a model the id of the NPC is asked of the world`() {
        val found = models(listOf(spawn("npc", "id" to "\"village:smith\""), play("wave")), listOf(into("npc", "wave")))
        assertEquals(mapOf("wave" to StoryEntityModel.Npc("village:smith")), found)
    }

    @Test
    fun `a model given by Kotlin says nothing`() {
        val found = models(listOf(spawn("npc", "model" to "pickModel()"), play("wave")), listOf(into("npc", "wave")))
        assertEquals(emptyMap(), found)
    }

    @Test
    fun `a variable passes on the model of what is written into it, unless the writes disagree`() {
        val nodes = listOf(
            spawn("a", "model" to "\"a.glb\""),
            StoryNode.Assign("set-a", "hero"),
            StoryNode.Expression("read", "hero"),
            play("wave"),
        )
        val links = listOf(into("a", "set-a", StoryPins.VALUE), into("read", "wave"))
        assertEquals(mapOf("wave" to StoryEntityModel.Path("a.glb")), models(nodes, links))

        val disagreeing = models(
            nodes + spawn("b", "model" to "\"b.glb\"") + StoryNode.Assign("set-b", "hero"),
            links + into("b", "set-b", StoryPins.VALUE),
        )
        assertEquals(emptyMap(), disagreeing)
    }

    @Test
    fun `an NPC spawned with no model and no id shows the model it falls back to`() {
        val left = models(listOf(spawn("npc"), play("wave")), listOf(into("npc", "wave")))
        assertEquals(mapOf("wave" to StoryEntityModel.Path("default.glb")), left)
        val blank = models(listOf(spawn("npc", "model" to "\"\""), play("wave")), listOf(into("npc", "wave")))
        assertEquals(mapOf("wave" to StoryEntityModel.Path("default.glb")), blank)
    }
}
