import kotlinx.coroutines.test.runTest
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialoguePresenter
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueSession
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.PresentedChoice
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StoryEngine
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.StorySourceProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A scene is a stage: it resumes at the statement it was at, and it shares the story's saved variables. */
class StageSceneTest : StageTest() {
    /** Writes every line down and holds it until the test releases its text, as a player reading it would. */
    private class HoldingPresenter : DialoguePresenter {
        private val text = StringBuilder()

        override suspend fun beginLine(session: DialogueSession, speaker: DialogueCharacter?) {
            text.clear()
        }

        override suspend fun appendText(session: DialogueSession, text: String) {
            this.text.append(text)
        }

        override suspend fun waitForInput(session: DialogueSession) = Unit

        override suspend fun endLine(session: DialogueSession) {
            val line = text.toString()
            StageTrace.log("say $line")
            StageTrace.await(line)
        }

        override suspend fun showChoices(session: DialogueSession, options: List<PresentedChoice>) = Unit
        override suspend fun hideChoices(session: DialogueSession, chosen: Int) = Unit
    }

    private val stories = HashMap<String, String>()
    private val source = StorySourceProvider { stories[it] }
    private lateinit var presentation: () -> DialoguePresenter

    @BeforeTest
    fun setUpScenes() {
        StoryEngine.addSource(source)
        presentation = StoryEngine.presentation
        StoryEngine.presentation = ::HoldingPresenter
    }

    @AfterTest
    fun tearDownScenes() {
        StoryEngine.library.removeProvider(source)
        StoryEngine.presentation = presentation
    }

    @Test
    fun `a scene resumes at its line and writes the saved variables of the story`() = runTest {
        stories["test:smith.story"] = """
            Кузнец: Раз.
            @set trust = trust + 1
            @set count = 5
            Кузнец: Два {count}.
        """.trimIndent()
        val world = world("scene", $$"""
            var trust by saved(1)

            @State
            suspend fun main() {
                log("before")
                scene("test:smith.story", with = emptyList())
                log("after $trust")
            }
        """)
        world.launch("main")
        assertEquals(listOf("before", "say Раз."), StageTrace.take())

        world.restart()
        assertEquals(listOf("say Раз."), StageTrace.take(), "the interrupted line is shown again, the caller does not repeat")

        world.release("Раз.")
        assertEquals(listOf("say Два 5."), StageTrace.take())

        world.restart()
        assertEquals(listOf("say Два 5."), StageTrace.take(), "a variable of the scene comes back with it")

        world.release("Два 5.")
        assertEquals(listOf("after 2"), StageTrace.take(), "the scene converted its number to the Int the script declared")
        world.stop()
    }

    @Test
    fun `a scene as the last call of a stage resumes as well`() = runTest {
        stories["test:last.story"] = "Кузнец: Последняя."
        val world = world("last", """
            @State
            suspend fun main() {
                log("before")
                scene("test:last.story", with = emptyList())
            }
        """)
        world.launch("main")
        world.restart()
        assertEquals(listOf("before", "say Последняя.", "say Последняя."), StageTrace.take())

        world.release("Последняя.")
        assertEquals(emptyList(), StageTrace.take())
        assertFalse("main" in world.runs.keys, "the story ends with its scene")
        world.stop()
    }

    @Test
    fun `an action of a scene is finished after a restart and not performed again once done`() = runTest {
        stories["test:build.story"] = """
            @build house
            Кузнец: Готово.
        """.trimIndent()
        val world = world("build", $$"""
            @Action
            suspend fun build(what: String) = action {
                perform {
                    log("build $what")
                    await("built")
                }
            }

            @State
            suspend fun main() {
                scene("test:build.story", with = emptyList())
            }
        """
        )
        world.launch("main")
        world.restart()
        assertEquals(listOf("build house", "build house"), StageTrace.take(), "the interrupted action is finished")

        world.release("built")
        assertEquals(listOf("say Готово."), StageTrace.take())

        world.restart()
        assertEquals(listOf("say Готово."), StageTrace.take(), "the done action is not performed again")
        world.stop()
    }

    @Test
    fun `a scene of a story that belongs to no player needs its players`() = runTest {
        stories["test:nobody.story"] = "Кузнец: Никому."
        val world = world("nobody", """
            @State
            suspend fun main() {
                scene("test:nobody.story")
                log("after")
            }
        """)
        val story = world.launch("main")
        assertFalse(story.isActive)
        assertEquals(emptyList(), StageTrace.take())
        world.stop()
    }
}
