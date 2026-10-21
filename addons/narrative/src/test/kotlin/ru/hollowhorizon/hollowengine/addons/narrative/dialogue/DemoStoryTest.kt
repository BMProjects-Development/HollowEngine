package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryInstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compiles the demo story that ships in the dev sandbox
 * (`hollowengine/scripts/stories/demo.story`) against the built-in functions.
 *
 * It is the one test that exercises the language and the engine's own function signatures together,
 * so a `@wait`/`@play-sound` whose parameters changed shape cannot quietly break every example.
 */
class DemoStoryTest {
    private val source: String = requireNotNull(
        javaClass.getResourceAsStream("/dialogue/demo.story")?.bufferedReader()?.readText(),
    ) { "demo.story is missing from the test resources" }

    private val functions = StoryFunctionRegistry().also {
        StoryBuiltinFunctions.install(it)
        StoryNpcFunctions.install(it)
        StoryEffectFunctions.install(it)
    }

    @Test
    fun `the demo story compiles without a single diagnostic`() {
        val result = StoryCompiler.compile("hollowengine:stories/demo.story", source, functions)

        assertEquals(emptyList(), result.diagnostics)
        assertNotNull(result.program)
    }

    @Test
    fun `every jump and call in the demo points at a label that exists`() {
        val program = assertNotNull(StoryCompiler.compile("test", source, functions).program)

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
    fun `built-in wait and play-sound accept the calls the demo makes`() {
        assertNotNull(functions.overloads("wait"))
        assertEquals(3, functions.overloads("play-sound")?.size, "name, +volume and +pitch forms")

        val calls = """
            @wait 15s
            @play-sound minecraft:entity.villager.yes
            @play-sound minecraft:entity.villager.yes 1.0
            @play-sound minecraft:block.note_block.bell 1.0 0.8
        """.trimIndent()
        val result = StoryCompiler.compile("test", calls, functions)

        assertEquals(emptyList(), result.diagnostics)
    }
}
