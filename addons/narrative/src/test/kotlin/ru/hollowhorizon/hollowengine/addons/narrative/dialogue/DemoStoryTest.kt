package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryInstruction
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compiles the demo story against the actions the narrative addon ships, registered as the game registers them.
 *
 * It is the one test that exercises the language and the signatures of the addon's own actions together, so an
 * action whose parameters changed shape cannot quietly break every example.
 */
class DemoStoryTest {
    private val source: String = requireNotNull(
        javaClass.getResourceAsStream("/dialogue/demo.story")?.bufferedReader()?.readText(),
    ) { "demo.story is missing from the test resources" }

    @BeforeTest
    fun registerActions() = NarrativeActions.register()

    @Test
    fun `the demo story compiles without a single diagnostic`() {
        val result = StoryCompiler.compile("hollowengine:stories/demo.story", source, ActionCommands)

        assertEquals(emptyList(), result.diagnostics)
        assertNotNull(result.program)
    }

    @Test
    fun `every jump and call in the demo points at a label that exists`() {
        val program = assertNotNull(StoryCompiler.compile("test", source, ActionCommands).program)

        val targets = program.instructions.mapNotNull { instruction ->
            when (instruction) {
                is StoryInstruction.Jump -> instruction.target
                is StoryInstruction.Call -> instruction.target
                else -> null
            }
        }
        assertTrue(targets.isNotEmpty())
        for (target in targets) {
            if (target.address != null) continue // another file, resolved when it is played
            assertTrue(target.label in program.labels, "no label '#${target.label}' in the demo")
        }
    }

    @Test
    fun `the commands of the addon take the arguments scenes write`() {
        val calls = """
            @wait 15s
            @play-sound player minecraft:entity.villager.yes
            @play-sound player minecraft:block.note_block.bell 1.0 0.8
            @move-to Виталик [25, 70, -175] speed=1.1 arrivalDistance=0.6
            @hide-hud player except=[chat, hotbar]
            @use-block Виталик [1, 64, 1] hand=off-hand
        """.trimIndent()
        val result = StoryCompiler.compile("test", calls, ActionCommands)

        assertEquals(emptyList(), result.diagnostics)
    }
}
