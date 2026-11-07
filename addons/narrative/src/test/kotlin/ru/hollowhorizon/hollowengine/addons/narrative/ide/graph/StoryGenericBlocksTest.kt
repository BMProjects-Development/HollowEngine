package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLink
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryEditorState
import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveResult
import ru.hollowhorizon.hollowengine.common.npcs.navigation.Shortfall
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Blocks of types take the type of what they are linked to, and give it up when nothing is linked to them. */
class StoryGenericBlocksTest {
    private val state = StoryEditorState()
    private val unpack = TypeOperators.UNPACK + MoveResult::class.qualifiedName
    private val shortfall = TypeOperators.ENUM + Shortfall::class.qualifiedName

    @BeforeTest
    fun register() = NarrativeActions.register()

    /** A reason a move fell short, compared by `equal` with a constant of no type yet. */
    private fun compared(constant: String) = StoryGraph(
        nodes = listOf(
            StoryNode.Pure("parts", unpack, mapOf("value" to "result")),
            StoryNode.Pure("same", "equal"),
            StoryNode.Pure("constant", constant),
        ),
        links = listOf(
            StoryLink("parts", TypeOperators.fieldPin("Closest", "reason"), "same", "a"),
            StoryLink("constant", TypeOperators.SUBJECT, "same", "b"),
        ),
    )

    private fun StoryGraph.operatorOf(node: String) = (node(node) as StoryNode.Pure).operator

    @Test
    fun `a constant compared with an enum becomes a constant of that enum`() {
        val settled = StoryGenericBlocks.settled(compared(TypeOperators.ENUM), state)
        assertEquals(shortfall, settled.operatorOf("constant"))
    }

    /** Nothing goes into `select` but the constant, so its type comes from where the choice goes. */
    @Test
    fun `a constant takes the type a generic block gives on`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Pure("parts", unpack, mapOf("value" to "result")),
                StoryNode.Pure("same", "equal"),
                StoryNode.Pure("choice", "select"),
                StoryNode.Pure("constant", TypeOperators.ENUM),
            ),
            links = listOf(
                StoryLink("choice", "result", "same", "a"),
                StoryLink("parts", TypeOperators.fieldPin("Closest", "reason"), "same", "b"),
                StoryLink("constant", TypeOperators.SUBJECT, "choice", "then"),
            ),
        )
        assertEquals(shortfall, StoryGenericBlocks.settled(graph, state).operatorOf("constant"))
    }

    @Test
    fun `a generic output nothing tells the type of gives T`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Pure("items", "list"), StoryNode.Pure("choice", "select")),
        )
        val boxes = StoryGraphBoxes(graph, state)
        assertEquals("List<T>", boxes.outputKotlin("items", "list"))
        assertEquals("T", boxes.outputKotlin("choice", "result"))
    }

    @Test
    fun `a block linked to nothing goes back to no type and keeps its fields`() {
        val alone = StoryGraph(nodes = listOf(StoryNode.Pure("constant", shortfall, mapOf("value" to "x"))))
        val settled = StoryGenericBlocks.settled(alone, state)
        assertEquals(TypeOperators.ENUM, settled.operatorOf("constant"))
        val linked = settled.copy(
            nodes = settled.nodes + listOf(StoryNode.Pure("parts", unpack, mapOf("value" to "result")), StoryNode.Pure("same", "equal")),
            links = listOf(
                StoryLink("parts", TypeOperators.fieldPin("Closest", "reason"), "same", "a"),
                StoryLink("constant", TypeOperators.SUBJECT, "same", "b"),
            ),
        )
        val typed = StoryGenericBlocks.settled(linked, state)
        assertEquals(shortfall, typed.operatorOf("constant"))
        assertEquals(mapOf("value" to "x"), (typed.node("constant") as StoryNode.Pure).arguments)
    }

    @Test
    fun `a settled graph is left as it is`() {
        val graph = compared(shortfall)
        assertEquals(graph, StoryGenericBlocks.settled(graph, state))
    }
}
