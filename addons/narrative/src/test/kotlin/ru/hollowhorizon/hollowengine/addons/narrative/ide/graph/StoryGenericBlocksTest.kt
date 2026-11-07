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

    @Test
    fun `a block linked to nothing goes back to no type`() {
        val alone = StoryGraph(nodes = listOf(StoryNode.Pure("constant", shortfall, mapOf("value" to "x"))))
        val settled = StoryGenericBlocks.settled(alone, state)
        assertEquals(TypeOperators.ENUM, settled.operatorOf("constant"))
        assertEquals(emptyMap(), (settled.node("constant") as StoryNode.Pure).arguments)
    }

    @Test
    fun `a settled graph is left as it is`() {
        val graph = compared(shortfall)
        assertEquals(graph, StoryGenericBlocks.settled(graph, state))
    }
}
