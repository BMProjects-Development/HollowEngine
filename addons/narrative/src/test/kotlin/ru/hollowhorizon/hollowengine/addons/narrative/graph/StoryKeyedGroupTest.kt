package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StoryKeyedGroupTest {
    private val group = StoryParamGroup("fields", "test.componentFields", keyed = true, typed = "test.componentField")

    private val functions = object : StoryFunctions {
        override fun signature(call: StoryNode.Call) = StoryFunctionSignature(
            "component/test:mover", "configureComponent",
            listOf(
                StoryParam("speed", typeOf<Float>(), optional = true, group = group),
                StoryParam("label", typeOf<String>(), optional = true, group = group),
            ),
            bound = mapOf("component" to "\"test:mover\""),
        )

        override fun kind(call: StoryNode.Call) = StoryCallKind.ACTION
    }

    private fun compile(call: StoryNode.Call, vararg extra: StoryNode, links: List<StoryLink> = emptyList()) =
        StoryGraphCompiler.compile(
            StoryGraph(
                nodes = listOf(StoryNode.Start("s"), call) + extra,
                links = listOf(StoryLink("s", StoryPins.NEXT, call.id, StoryPins.IN)) + links,
            ),
            functions,
        )

    @Test
    fun `written and linked fields go by name, the rest are left out`() {
        val call = StoryNode.Call(
            "set", "configureComponent", "component/test:mover",
            groups = mapOf("fields" to StoryGroup("componentFields", mapOf("speed" to "2.5f"))),
        )
        val story = compile(
            call, StoryNode.Pure("text", "to-text", mapOf("value" to "1")),
            links = listOf(StoryLink("text", "result", "set", "label")),
        )
        assertEquals(emptyList(), story.problems)
        assertContains(
            story.code,
            "configureComponent(component = \"test:mover\", fields = componentFields(\"speed\" to componentField<kotlin.Float>(2.5f), \"label\" to componentField<kotlin.String>(",
        )
        assertEquals(true, story.code.lines().containsAll(listOf("import test.componentField", "import test.componentFields")))
        assertEquals(false, "label = " in story.code)
    }

    @Test
    fun `a call that fills no field still names the component`() {
        val story = compile(StoryNode.Call("set", "configureComponent", "component/test:mover"))
        assertContains(story.code, "configureComponent(component = \"test:mover\", fields = componentFields())")
    }
}
