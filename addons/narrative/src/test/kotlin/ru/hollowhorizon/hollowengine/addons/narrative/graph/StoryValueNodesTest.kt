package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActions
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.addons.narrative.story.ValueOperators
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveResult
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Values registered with `@StoryValue` and the blocks of the types they use, as a story compiles them. */
class StoryValueNodesTest {
    private val namespace = NarrativeActions.NAMESPACE
    private val unpack = TypeOperators.UNPACK + MoveResult::class.qualifiedName

    @BeforeTest
    fun register() = NarrativeActions.register()

    /** A node read by a line of text, so that it is compiled where the line reads it. */
    private fun read(value: StoryNode.Pure, pin: String, vararg extra: StoryNode, links: List<StoryLink> = emptyList()) =
        StoryGraphCompiler.compile(
            StoryGraph(
                nodes = listOf(StoryNode.Start("s"), StoryNode.Say("say"), value) + extra,
                links = listOf(
                    StoryLink("s", StoryPins.NEXT, "say", StoryPins.IN),
                    StoryLink(value.id, pin, "say", StoryPins.TEXT),
                ) + links,
            ),
            StoryFunctions.Builtin,
        )

    @Test
    fun `commands taking blocks, tags and filters register`() {
        listOf("set-block", "fill", "set-data", "merge-nbt", "give-item", "take-items", "set-inventory-slot").forEach { id ->
            assertTrue(StoryActions.find("$namespace:$id")?.reversible == true, "$id is an undoable command")
        }
        listOf("health", "game-mode", "nbt-get", "has-items").forEach { id ->
            assertTrue(StoryActions.find("$namespace:$id")?.value == true, "$id is a value")
        }
    }

    @Test
    fun `an entity left empty is the player of the story and an empty optional input is left out`() {
        val story = read(StoryNode.Pure("time", "$namespace:time-of-day"), ValueOperators.VALUE)
        assertEquals(emptyList(), story.problems)
        assertContains(story.code, "timeOfDay()")
        assertContains(story.code, "import ru.hollowhorizon.hollowengine.addons.narrative.world.timeOfDay")

        val health = read(StoryNode.Pure("health", "$namespace:health"), ValueOperators.VALUE)
        assertContains(health.code, "healthOf(who = storyPlayer())")
    }

    @Test
    fun `a linked number is converted to the type of the parameter`() {
        val story = read(
            StoryNode.Pure("item", "$namespace:with-count", mapOf("item" to "itemOf(\"minecraft:stone\")")),
            ValueOperators.VALUE,
            StoryNode.Pure("sum", "add", mapOf("a" to "1", "b" to "2")),
            links = listOf(StoryLink("sum", "result", "item", "count")),
        )
        assertEquals(emptyList(), story.problems)
        assertContains(story.code, "withCount(item = itemOf(\"minecraft:stone\"), count = (")
        assertContains(story.code, ").toInt())")
    }

    @Test
    fun `a sealed result is taken apart into a flag and nullable fields per class`() {
        val operator = assertNotNull(StoryOperators.find(unpack))
        val closest = MoveResult.Closest::class.qualifiedName
        assertTrue(operator.outputs.any { it.name == TypeOperators.isPin("Arrived") })

        val flag = read(StoryNode.Pure("parts", unpack, mapOf("value" to "result")), TypeOperators.isPin("Closest"))
        assertContains(flag.code, "((result) is $closest)")

        val field = read(StoryNode.Pure("parts", unpack, mapOf("value" to "result")), TypeOperators.fieldPin("Closest", "distance"))
        assertContains(field.code, "((result) as? $closest)?.distance")
    }

    @Test
    fun `a class of a sealed type is put together from its fields`() {
        val closest = MoveResult.Closest::class.qualifiedName!!
        val operator = assertNotNull(StoryOperators.find(TypeOperators.PACK + closest))
        assertEquals(listOf("reason", "distance"), operator.inputs.map { it.name })
        val story = read(
            StoryNode.Pure("made", TypeOperators.PACK + closest, mapOf("distance" to "2.0")),
            ValueOperators.VALUE,
        )
        assertContains(story.code, "import $closest")
        assertContains(story.code, "Closest(")
    }
}
