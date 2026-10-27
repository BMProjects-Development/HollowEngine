package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Saving and resuming. The rules under test are the ones a player notices: an unchanged story resumes
 * exactly, an edited one rolls back to the last label, and a story that lost the label starts over,
 * with the variables kept in every case.
 */
class SceneCheckpointTest {
    /** Stops the dialogue at a chosen line, the way a server shutdown would. */
    private class HaltingPresenter(private val haltAfter: Int) : DialoguePresenter {
        val lines = mutableListOf<String>()
        private val current = StringBuilder()
        private var speaker: DialogueCharacter? = null
        val halted = CompletableDeferred<Unit>()

        override suspend fun beginLine(session: SceneSession, speaker: DialogueCharacter?) {
            current.clear()
            this.speaker = speaker
        }

        override suspend fun appendText(session: SceneSession, text: String) {
            current.append(text)
        }

        override suspend fun waitForInput(session: SceneSession) = Unit

        override suspend fun endLine(session: SceneSession) {
            lines += current.toString()
            current.clear()
            if (lines.size >= haltAfter) {
                halted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }

        override suspend fun showChoices(session: SceneSession, options: List<PresentedChoice>) = Unit
        override suspend fun hideChoices(session: SceneSession, chosen: Int) = Unit
    }

    private fun TestScope.scene(
        sources: Map<String, String>,
        presenter: DialoguePresenter,
        locale: String? = null,
    ): ScenePlayer = testScene(backgroundScope, sources, presenter, locale = locale)

    private val story = """
        Виталик: Раз.
        # Середина
        Виталик: Два.
        Виталик: Три.
        Виталик: Четыре.
    """.trimIndent()

    /** Plays until [haltAfter] lines have been shown, then saves, the way a shutdown would. */
    private suspend fun TestScope.playAndSave(
        sources: Map<String, String>,
        haltAfter: Int,
        variables: Map<String, StoryValue> = emptyMap(),
    ): Pair<CompoundTag, List<String>> {
        val presenter = HaltingPresenter(haltAfter)
        val scene = scene(sources, presenter)
        scene.variables += variables
        val job = backgroundScope.launch { scene.play(emptyList()) }
        presenter.halted.await()
        val tag = assertNotNull(scene.checkpoint()).save()
        job.cancel()
        return tag to presenter.lines
    }

    @Test
    fun `an unchanged story resumes on the statement it was interrupted on`() = runTest {
        val (tag, before) = playAndSave(mapOf("test:main.story" to story), haltAfter = 3)
        assertEquals(listOf("Раз.", "Два.", "Три."), before)

        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val resumed = scene(mapOf("test:main.story" to story), presenter)
        resumed.restore(SceneCheckpoint.load(tag))
        val result = resumed.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(listOf("Три.", "Четыре."), presenter.lines)
    }

    @Test
    fun `an edited story rolls back to the last label it was inside`() = runTest {
        val (tag, _) = playAndSave(mapOf("test:main.story" to story), haltAfter = 3)

        val edited = """
            Виталик: Раз.
            Виталик: Вставка перед меткой.
            # Середина
            Виталик: Два, переписано.
            Виталик: Три.
            Виталик: Четыре.
        """.trimIndent()

        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val resumed = scene(mapOf("test:main.story" to edited), presenter)
        resumed.restore(SceneCheckpoint.load(tag))
        resumed.play(emptyList())

        assertEquals(listOf("Два, переписано.", "Три.", "Четыре."), presenter.lines)
    }

    @Test
    fun `a story that lost the label starts over but keeps the variables`() = runTest {
        val (tag, _) = playAndSave(
            mapOf("test:main.story" to story),
            haltAfter = 3,
            variables = mapOf("money" to StoryNumber(42f)),
        )

        val rewritten = """
            Виталик: Совсем другая история, денег {money}.
        """.trimIndent()

        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val resumed = scene(mapOf("test:main.story" to rewritten), presenter)
        resumed.restore(SceneCheckpoint.load(tag))
        resumed.play(emptyList())

        assertEquals(listOf("Совсем другая история, денег 42."), presenter.lines)
    }

    @Test
    fun `variables survive a save and keep their type`() = runTest {
        val checkpoint = SceneCheckpoint(
            address = "test:main.story",
            sourceHash = "hash",
            locale = null,
            frames = listOf(CheckpointFrame("test:main.story", StoryAnchor("Метка", 3, 7))),
            variables = mapOf(
                "money" to StoryNumber(1f),
                "name" to StoryString("Виталик"),
                "flag" to StoryBool(true),
            ),
        )

        val restored = assertNotNull(SceneCheckpoint.load(checkpoint.save()))

        assertEquals(checkpoint.address, restored.address)
        assertEquals(checkpoint.frames, restored.frames)
        assertEquals(StoryNumber(1f), restored.variables["money"])
        assertEquals(StoryString("Виталик"), restored.variables["name"])
        assertEquals(StoryBool(true), restored.variables["flag"])
    }

    @Test
    fun `finishing clears the checkpoint`() = runTest {
        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val scene = scene(mapOf("test:main.story" to story), presenter)
        scene.play(emptyList())

        assertNull(scene.checkpoint(), "a finished scene must not leave a resumable checkpoint")
    }

    @Test
    fun `a call stack is restored frame by frame`() = runTest {
        val nested = """
            @call #Подпрограмма
            Виталик: После call.
            @jump #Конец
            # Подпрограмма
            Виталик: Внутри раз.
            Виталик: Внутри два.
            @return
            # Конец
            Виталик: Финал.
        """.trimIndent()

        val (tag, before) = playAndSave(mapOf("test:main.story" to nested), haltAfter = 2)
        assertEquals(listOf("Внутри раз.", "Внутри два."), before)

        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val resumed = scene(mapOf("test:main.story" to nested), presenter)
        resumed.restore(SceneCheckpoint.load(tag))
        resumed.play(emptyList())

        assertEquals(listOf("Внутри два.", "После call.", "Финал."), presenter.lines)
    }

    @Test
    fun `a localised story is played and recorded instead of the base one`() = runTest {
        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val scene = scene(
            mapOf(
                "test:main.story" to "Vitalik: Hello!",
                "test:main.ru_ru.story" to "Виталик: Привет!",
            ),
            presenter,
            locale = "ru_ru",
        )

        scene.play(emptyList())

        assertEquals(listOf("Привет!"), presenter.lines)
    }

    @Test
    fun `a missing translation falls back to the base story`() = runTest {
        val presenter = HaltingPresenter(Int.MAX_VALUE)
        val scene = scene(
            mapOf("test:main.story" to "Vitalik: Hello!"),
            presenter,
            locale = "de_de",
        )

        scene.play(emptyList())

        assertEquals(listOf("Hello!"), presenter.lines)
    }

    @Test
    fun `localised addresses are built from the file name`() {
        assertEquals(
            "project:stories/example.ru_ru.story",
            SceneLibrary.localizedAddress("project:stories/example.story", "ru_ru"),
        )
    }
}
