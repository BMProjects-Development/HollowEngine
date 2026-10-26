package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.NarrativeActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The actions narrative declares register with parameters the journal can keep, or marks as unsaved. */
class StoryActionsTest {
    @Test
    fun `npc actions keep their receiver and targets and leave the options to their defaults`() {
        NarrativeActions.register()
        val move = StoryActions.find("${NarrativeActions.NAMESPACE}:move-to")!!
        assertTrue(move.reversible)
        assertEquals(listOf("this", "pos", "options"), move.parameterNames)
        listOf("move-to-entity", "move-through", "avoid", "look-at", "look-at-entity").forEach { id ->
            assertTrue(StoryActions.find("${NarrativeActions.NAMESPACE}:$id")?.reversible == true, "$id is undoable")
        }
        assertFalse(StoryActions.find("${NarrativeActions.NAMESPACE}:stop-moving")!!.reversible)
    }

    @Test
    fun `checkpoint labels register as actions of their own`() {
        NarrativeActions.register()
        assertEquals(listOf("this", "entity"), StoryActions.find("${NarrativeActions.NAMESPACE}:position")!!.parameterNames)
        assertEquals(listOf("this", "player"), StoryActions.find("${NarrativeActions.NAMESPACE}:inventory")!!.parameterNames)
    }
}
