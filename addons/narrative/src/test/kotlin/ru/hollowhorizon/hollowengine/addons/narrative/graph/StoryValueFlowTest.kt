package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryValueFlowTest {
    private val start = StoryNode.Start("s", "main")
    private val loop = StoryNode.Loop("loop", "listOf(1)")
    private val itemIntoSay = StoryLink("loop", StoryPins.ITEM, "say", StoryPins.TEXT)

    private fun flow(from: String, to: String, pin: String = StoryPins.NEXT) = StoryLink(from, pin, to, StoryPins.IN)

    private fun problems(nodes: List<StoryNode>, links: List<StoryLink>) =
        StoryGraphCompiler.compile(StoryGraph(nodes = nodes, links = links)).problems

    @Test
    fun `the body of a loop reads its item past a branch`() {
        val found = problems(
            listOf(start, loop, StoryNode.Branch("branch", "true"), StoryNode.Say("say")),
            listOf(
                flow("s", "loop"), flow("loop", "branch", StoryPins.BODY), flow("branch", "say", StoryPins.THEN), itemIntoSay,
            ),
        )
        assertEquals(emptyList(), found)
    }

    /** The node gets a stage of its own, and the `for` the item lives in stays in the stage that calls it. */
    @Test
    fun `a node reached from the body and after the loop cannot read the item`() {
        val found = problems(
            listOf(start, loop, StoryNode.Say("say")),
            listOf(flow("s", "loop"), flow("loop", "say", StoryPins.BODY), flow("loop", "say"), itemIntoSay),
        )
        assertEquals(listOf("loop_item_merged"), found.map { it.key })
        assertFalse(found.single().warning)
    }

    @Test
    fun `what follows a merge inside the body cannot read the item`() {
        val found = problems(
            listOf(start, loop, StoryNode.Branch("branch", "true"), StoryNode.Say("merge"), StoryNode.Say("say")),
            listOf(
                flow("s", "loop"),
                flow("loop", "branch", StoryPins.BODY),
                flow("branch", "merge", StoryPins.THEN),
                flow("branch", "merge", StoryPins.ELSE),
                flow("merge", "say"),
                itemIntoSay,
            ),
        )
        assertEquals(listOf("say" to "loop_item_merged"), found.map { it.node to it.key })
    }

    @Test
    fun `nothing after a loop reads its item`() {
        val found = problems(
            listOf(start, loop, StoryNode.Say("say")),
            listOf(flow("s", "loop"), flow("loop", "say"), itemIntoSay),
        )
        assertEquals(listOf("loop_item"), found.map { it.key })
    }

    private fun readAfterBranch(output: String): StoryGraphProblem {
        val found = problems(
            listOf(
                start,
                StoryNode.Branch("branch", "true"),
                StoryNode.Code("code", "x = 1", outputs = mapOf("x" to output)),
                StoryNode.Say("say"),
            ),
            listOf(
                flow("s", "branch"),
                flow("branch", "code", StoryPins.THEN),
                flow("branch", "say", StoryPins.ELSE),
                flow("code", "say"),
                StoryLink("code", "x", "say", StoryPins.TEXT),
            ),
        )
        return found.single { it.key == "other_branch" }
    }

    @Test
    fun `a result a branch may skip stops the story unless it can be null`() {
        assertFalse(readAfterBranch("Int").warning)
        assertTrue(readAfterBranch("Int?").warning)
    }

    @Test
    fun `the block of a call does not read what the call returns`() {
        val found = problems(
            listOf(start, StoryNode.Call("call", "run", returns = "Int", block = true), StoryNode.Say("say")),
            listOf(
                flow("s", "call"),
                flow("call", "say", StoryPins.BODY),
                StoryLink("call", StoryPins.VALUE, "say", StoryPins.TEXT),
            ),
        )
        assertTrue(found.any { it.key == "other_branch" && it.node == "say" && !it.warning }, "$found")
    }
}
