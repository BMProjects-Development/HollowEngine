package ru.hollowhorizon.hollowengine.addons.narrative.story

import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.scene.commands.FadeState
import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.reflect.typeOf

/** The actions narrative declares register with parameters the journal can keep, or marks as unsaved. */
class StoryActionsTest {
    @Test
    fun `fade snapshot preserves formatted text across serialization`() {
        val serializer = StoryValues.serializer(typeOf<List<FadeState>>())
        val state = FadeState(0x123456, "<scale=2>Title</scale>")
        assertEquals(listOf(state), StoryValues.decode(serializer, StoryValues.encode(serializer, listOf(state))))
    }
    @Test
    fun `old action records use defaults for new optional arguments and snapshots`() {
        StoryActions.register(LegacyActionFixture::class.java, "legacy-test")
        val action = StoryActions.find("legacy-test:sample")!!
        val args = action.encode(arrayOf(LegacyActionFixture, 7, "written"))
        args.remove("text")
        val entry = JournalEntry(0, action.id, "run", Lifetime.RUN, null, args, CompoundTag(), null)
        val replay = ActionReplay(entry, action)
        action.replay(replay)
        replay.scope!!.undo!!.invoke(UndoScope())
        assertEquals("7:default:null", LegacyActionFixture.restored)
    }
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

object LegacyActionFixture {
    var restored = ""

    @Action("sample")
    suspend fun sample(number: Int, text: String = "default") = action(this, number, text) {
        val before = captureOr<String?>(null) { "new" }
        undo { restored = "$number:$text:$before" }
        perform { }
    }
}
