package ru.hollowhorizon.hollowengine.client.shadergraph

import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaderGraphEditsTest {
    private val graph = ShaderGraph(
        nodes = listOf(
            ShaderGraphNode("a", "hollowengine:math/add"),
            ShaderGraphNode("b", "hollowengine:math/add"),
            ShaderGraphNode("c", "hollowengine:math/add"),
        ),
    )

    @Test
    fun `an input takes one link, and a link that would close a loop is not made`() {
        val linked = graph.withLink("a", "Out", "c", "A").withLink("b", "Out", "c", "A")
        assertEquals(listOf(ShaderGraphLink("b", "Out", "c", "A")), linked.links)

        // b feeds c, c feeds a; a feeding b would close the loop.
        val chained = linked.withLink("c", "Out", "a", "A")
        assertEquals(2, chained.links.size)
        assertEquals(chained, chained.withLink("a", "Out", "b", "A"))
    }

    @Test
    fun `a shape has the pins of the shape it is, and a link into a pin it loses goes`() {
        val shape = ShaderGraphNode("s", "hollowengine:procedural/shape", options = mapOf("shape" to "polygon"))
        val withLink = graph.withNode(shape).withLink("a", "Out", "s", "Sides")
        val kind = ShaderNodeTypes.of(shape.type)!!

        assertTrue("Sides" in kind.inputs(shape).map { it.name })
        val ring = withLink.withOption("s", "shape", "ring")
        assertEquals(listOf("UV", "Width", "Height", "Thickness", "Softness"), kind.inputs(ring.node("s")!!).map { it.name })
        assertTrue(ring.links.none { it.to == "s" })
    }

    @Test
    fun `duplicating nodes keeps the links between them and drops the ones that reach outside`() {
        val linked = graph.withLink("a", "Out", "b", "A").withLink("b", "Out", "c", "A")
        val (copied, ids) = linked.withDuplicates(setOf("a", "b"))

        assertEquals(2, ids.size)
        assertEquals(listOf(ShaderGraphLink(ids[0], "Out", ids[1], "A")), copied.links - linked.links.toSet())
    }

    @Test
    fun `deleting reroutes joins the link back up, however many of them are taken out at once`() {
        val once = graph.withLink("a", "Out", "c", "A").withReroute(ShaderGraphLink("a", "Out", "c", "A"), "r1", 0f, 0f)
        val rerouted = once.withReroute(once.links.first { it.to == "c" }, "r2", 0f, 0f)
        assertEquals(3, rerouted.links.size)

        assertEquals(listOf(ShaderGraphLink("a", "Out", "c", "A")), rerouted.withoutNodes(setOf("r1", "r2")).links)
        // A reroute whose source goes too has nothing to join to: the one left keeps only its way out.
        assertEquals(listOf(ShaderGraphLink("r2", "Out", "c", "A")), rerouted.withoutNodes(setOf("a", "r1")).links)
    }

    @Test
    fun `a node is in one group at most, and a group left empty goes`() {
        val (first, one) = graph.withGroup(setOf("a", "b"), "One")
        val (second, two) = first.withGroup(setOf("a", "b", "c"), "Two")

        assertEquals(listOf(two), second.groups.map { it.id })
        assertEquals(listOf("a", "b", "c"), second.group(two)?.nodes)
        assertTrue(one != two)
        assertTrue(second.withoutNodes(setOf("a", "b", "c")).groups.isEmpty())
    }

    @Test
    fun `renaming a property renames what its nodes read`() {
        val withProperty = graph
            .withProperty(ShaderGraphProperty("Speed"))
            .withNode(ShaderGraphNode("p", ShaderNodeLibrary.PROPERTY, options = mapOf("property" to "Speed")))

        val renamed = withProperty.withPropertyChanged("Speed", ShaderGraphProperty("Rate"))

        assertEquals(listOf("Rate"), renamed.properties.map { it.name })
        assertEquals("Rate", renamed.node("p")?.options?.get("property"))
    }

    @Test
    fun `deleting reroutes that feed each other in a loop ends`() {
        val looped = ShaderGraph(
            nodes = listOf(
                ShaderGraphNode("r1", ShaderNodeLibrary.REROUTE),
                ShaderGraphNode("r2", ShaderNodeLibrary.REROUTE),
                ShaderGraphNode("a", "hollowengine:math/add"),
            ),
            links = listOf(
                ShaderGraphLink("r2", ShaderNodeLibrary.REROUTE_OUTPUT, "r1", ShaderNodeLibrary.REROUTE_INPUT),
                ShaderGraphLink("r1", ShaderNodeLibrary.REROUTE_OUTPUT, "r2", ShaderNodeLibrary.REROUTE_INPUT),
                ShaderGraphLink("r1", ShaderNodeLibrary.REROUTE_OUTPUT, "a", "A"),
            ),
        )

        val left = assertTimeoutPreemptively(Duration.ofSeconds(5)) { looped.withoutNodes(setOf("r1", "r2")) }

        assertEquals(listOf("a"), left.nodes.map { it.id })
        assertEquals(emptyList(), left.links)
    }
}
