package ru.hollowhorizon.hollowengine.client.ui.graph

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.shadergraph.*
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.UiEvent
import ru.hollowhorizon.hollowengine.client.ui.UiEventKind
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph.ShaderGraphEditorModel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph.ShaderGraphScene
import ru.hollowhorizon.hollowengine.client.ui.ide.files.shadergraph.ShaderNodeLayout
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The controller over a material graph, the way the editor drives it. */
class GraphEditorControllerTest {
    private val nodes = listOf(
        ShaderGraphNode("a", "hollowengine:math/add"),
        ShaderGraphNode("b", "hollowengine:math/add", x = 300f),
        ShaderGraphNode("c", "hollowengine:math/add", x = 600f),
        ShaderGraphNode("out", ShaderNodeLibrary.SURFACE_OUTPUT, x = 900f),
    )

    @ParameterizedTest
    @ValueSource(ints = [GLFW.GLFW_MOD_CONTROL, GLFW.GLFW_MOD_SHIFT])
    fun `deselecting the last node closes the gesture and keeps undo usable`(modifiers: Int) {
        val fixture = Fixture(ShaderGraph(nodes = nodes))
        fixture.document.edit { it.withNodeAt("b", 320f, 20f) }
        fixture.refresh()
        fixture.controller.select(GraphSelection.of("a"))

        fixture.controller.pressNode("a", GraphNodeGesture(0f, 0f, 0f, 0f, modifiers))
        fixture.controller.releaseNode()

        assertTrue(fixture.controller.selection.nodes.isEmpty())
        assertTrue(fixture.document.history.undo(), "A selection-only press must not leave an open undo transaction")
        assertEquals(300f, fixture.document.graph.node("b")?.x)
    }

    @Test
    fun `select all includes nodes hidden in collapsed groups`() {
        val fixture = Fixture(
            ShaderGraph(
                nodes = nodes,
                groups = listOf(ShaderGraphGroup("group", "Group", listOf("a", "b"), collapsed = true)),
            )
        )

        assertTrue(fixture.controller.handleKey(key(GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL)))

        assertEquals(nodes.map { it.id }.toSet(), fixture.controller.selection.nodes)
    }

    @Test
    fun `escape while rewiring restores the detached link`() {
        val original = ShaderGraphLink("a", "Out", "b", "A")
        val fixture = Fixture(ShaderGraph(nodes = nodes, links = listOf(original)))
        val input = fixture.controller.model.scene.pins.first { it.node == "b" && it.name == "A" && !it.output }
        fixture.controller.pressPin(input, 0f, 0f)
        assertTrue(fixture.document.graph.links.isEmpty(), "The test must actually pick up the existing wire")
        fixture.refresh()

        assertTrue(fixture.controller.handleKey(key(GLFW.GLFW_KEY_ESCAPE)))

        assertEquals(
            listOf(original),
            fixture.document.graph.links,
            "Escape should cancel the rewire, including its eager detach"
        )
        assertFalse(fixture.document.history.canUndo, "A cancelled gesture should not become an edit")
    }

    @Test
    fun `creating a reroute from the link menu is one undo step`() {
        val fixture = Fixture(ShaderGraph(nodes = nodes))
        val pending = GraphPendingLink("a", "Out", true, 400f, 400f, 400f, 400f)
        val items = fixture.controller.model.offerNodes(pending, 400f, 400f)

        items.first().onClick()
        assertEquals(1, fixture.document.graph.links.size)
        assertEquals(nodes.size + 1, fixture.document.graph.nodes.size)
        assertTrue(fixture.document.history.undo())

        assertEquals(
            nodes, fixture.document.graph.nodes, "Undo must remove the newly created node together with its wire"
        )
        assertTrue(fixture.document.graph.links.isEmpty())
    }

    @Test
    fun `undoing another wire removal keeps the selected wire identity`() {
        val first = ShaderGraphLink("a", "Out", "b", "A")
        val selected = ShaderGraphLink("a", "Out", "c", "A")
        val last = ShaderGraphLink("b", "Out", "c", "B")
        val fixture = Fixture(ShaderGraph(nodes = nodes, links = listOf(first, selected, last)))
        fixture.document.edit { it.withoutLink(first) }
        fixture.refresh()
        fixture.controller.select(GraphSelection.ofLink(fixture.controller.model.scene.links.first().key))

        assertTrue(fixture.document.history.undo())
        fixture.refresh()
        fixture.controller.deleteSelection()

        assertEquals(
            listOf(first, last), fixture.document.graph.links, "Delete must still act on the wire selected before undo"
        )
    }

    @Test
    fun `ordinary node selection closes its gesture and keeps undo usable`() {
        val fixture = Fixture(ShaderGraph(nodes = nodes))
        fixture.document.edit { it.withNodeAt("b", 320f, 20f) }
        fixture.refresh()

        fixture.controller.pressNode("a", GraphNodeGesture(0f, 0f, 0f, 0f, 0))
        fixture.controller.releaseNode()

        assertTrue(fixture.document.history.undo())
        assertEquals(300f, fixture.document.graph.node("b")?.x)
    }

    private fun key(code: Int, modifiers: Int = 0) = UiKeyInput(
        UiEvent(UiEventKind.KEY_PRESSED, BoxNode(), key = code, modifiers = modifiers),
    )

    private class Fixture(graph: ShaderGraph) {
        val document = HollowIdeShaderGraphDocument(ShaderGraphFormat.write(graph).toByteArray())
        val controller = GraphEditorController("test-graph", GraphEditorState())

        init {
            refresh()
        }

        fun refresh() {
            val graph = document.graph
            val code = ShaderGraphCompiler.compile(graph)
            val boxes = graph.nodes.associate { node ->
                node.id to ShaderNodeLayout.of(graph, node, checkNotNull(ShaderNodeTypes.of(node.type)), code.types)
            }
            controller.model =
                ShaderGraphEditorModel(document, code, ShaderGraphScene.of(graph, boxes), controller::select)
        }
    }
}
