package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The actions narrative declares register with parameters the journal can keep, or marks as unsaved. */
class StoryActionsTest {
    @Test
    fun `npc actions keep their receiver and targets and leave the options to their defaults`() {
        StoryActions.register(Class.forName("ru.hollowhorizon.hollowengine.addons.narrative.npcs.NpcActionsKt"), "test")
        val move = StoryActions.find("test:move_to")!!
        assertTrue(move.reversible)
        assertEquals(listOf("this", "pos", "options"), move.parameterNames)
        listOf("move_to_entity", "move_through", "avoid", "look_at", "look_at_entity").forEach { id ->
            assertTrue(StoryActions.find("test:$id")?.reversible == true, "$id is undoable")
        }
        assertFalse(StoryActions.find("test:stop_moving")!!.reversible)
    }

    @Test
    fun `checkpoint labels register as actions of their own`() {
        StoryActions.register(Class.forName("ru.hollowhorizon.hollowengine.addons.narrative.story.StoryLabelsKt"), "test")
        assertEquals(listOf("this", "entity"), StoryActions.find("test:position")!!.parameterNames)
        assertEquals(listOf("this", "player"), StoryActions.find("test:inventory")!!.parameterNames)
    }
}
