package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Collects everything a dialogue shows, and answers menus with a scripted list of picks. */
private open class RecordingPresenter(private val picks: MutableList<Int> = mutableListOf()) : DialoguePresenter {
    val lines = mutableListOf<String>()
    val menus = mutableListOf<List<String>>()
    var started = 0
    var ended: SceneResult? = null

    private val current = StringBuilder()
    private var speaker: DialogueCharacter? = null

    override suspend fun onDialogueStart(session: SceneSession) {
        started++
    }

    override suspend fun onDialogueEnd(session: SceneSession, result: SceneResult) {
        ended = result
    }

    override suspend fun beginLine(session: SceneSession, speaker: DialogueCharacter?) {
        current.clear()
        this.speaker = speaker
    }

    override suspend fun appendText(session: SceneSession, text: String) {
        current.append(text)
    }

    override suspend fun waitForInput(session: SceneSession) {
        current.append("<wait>")
    }

    override suspend fun endLine(session: SceneSession) {
        lines += speaker?.let { "${it.name}: $current" } ?: current.toString()
        current.clear()
    }

    override suspend fun showChoices(session: SceneSession, options: List<PresentedChoice>) {
        menus += options.map { it.text }
        val pick = if (picks.isEmpty()) 0 else picks.removeAt(0)
        (session as ScenePlayer).forceChoice(pick)
    }

    /** Every `chosen` the runtime reported when a menu closed, in order. */
    val decided = mutableListOf<Int>()

    override suspend fun hideChoices(session: SceneSession, chosen: Int) {
        decided += chosen
    }
}

/** Records lines and stops the dialogue at the second one, the way a shutdown would. */
private class HaltOnSecondLine : RecordingPresenter() {
    val halted = CompletableDeferred<Unit>()

    override suspend fun endLine(session: SceneSession) {
        super.endLine(session)
        if (lines.size < 2) return
        halted.complete(Unit)
        CompletableDeferred<Unit>().await()
    }
}

/** The commands the runtime tests call, registered as actions like any other. */
object DialogueTestCommands {
    val greeted = mutableListOf<String>()

    @SimpleAction("pause")
    suspend fun waitFor(time: Duration) = delay(time)

    @SimpleAction("greet")
    fun greet(who: DialogueCharacter) {
        greeted += who.name
    }
}

class ScenePlayerTest {
    private fun TestScope.scene(
        source: String,
        presenter: DialoguePresenter,
        extra: Map<String, String> = emptyMap(),
    ): ScenePlayer {
        StoryActions.register(DialogueTestCommands::class.java, "test")
        val sources = mapOf("test:main.story" to source) + extra
        return testScene(backgroundScope, sources, presenter, SceneLibrary { ActionCommands })
    }

    @Test
    fun `lines are shown with speaker, interpolation and inline pauses`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            Виталик: Привет!
            Ваш баланс: {money} монет.[-] Точно.
            """.trimIndent(),
            presenter,
        )

        scene.variables["money"] = StoryNumber(7f)
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(listOf("Виталик: Привет!", "Ваш баланс: 7 монет.<wait> Точно."), presenter.lines)
        assertEquals(1, presenter.started)
    }

    @Test
    fun `choices show together and only the picked branch runs`() = runTest {
        val presenter = RecordingPresenter(mutableListOf(1))
        val scene = scene(
            """
            @choice "Сейчас день."
                Виталик: С добрым утром.
            @choice "Сейчас ночь."
                Виталик: Спокойной ночи.
            Виталик: Пока.
            """.trimIndent(),
            presenter,
        )

        scene.play(emptyList())

        assertEquals(listOf(listOf("Сейчас день.", "Сейчас ночь.")), presenter.menus)
        assertEquals(listOf("Виталик: Спокойной ночи.", "Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `a choice hidden by its condition is not offered`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @choice "Купить" if=money>10
                Виталик: Держи.
            @choice "Уйти"
                Виталик: Пока.
            """.trimIndent(),
            presenter,
        )

        scene.variables["money"] = StoryNumber(5f)
        scene.play(emptyList())

        assertEquals(listOf(listOf("Уйти")), presenter.menus)
        assertEquals(listOf("Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `the choice event carries the id`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @choice "Торговать" id=trade
                Виталик: Смотри товар.
            """.trimIndent(),
            presenter,
        )
        val seen = mutableListOf<String>()
        SceneEvent.Chosen.subscribe(backgroundScope) { if (it.session === scene) seen += it.key }

        scene.play(emptyList())

        assertEquals(listOf("trade"), seen)
    }

    @Test
    fun `while loop runs until its condition fails`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @while money > 0
                Виталик: Осталось {money}.
                @set money = money - 1
            """.trimIndent(),
            presenter,
        )

        scene.variables["money"] = StoryNumber(3f)
        scene.play(emptyList())

        assertEquals(
            listOf("Виталик: Осталось 3.", "Виталик: Осталось 2.", "Виталик: Осталось 1."),
            presenter.lines,
        )
    }

    @Test
    fun `call returns to the caller while jump does not`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @call #Подпрограмма
            Виталик: После call.
            @jump #Конец

            # Подпрограмма
            Виталик: Внутри.
            @return

            # Конец
            Виталик: Финал.
            """.trimIndent(),
            presenter,
        )

        scene.play(emptyList())

        assertEquals(
            listOf("Виталик: Внутри.", "Виталик: После call.", "Виталик: Финал."),
            presenter.lines,
        )
    }

    @Test
    fun `sync hands the track's remainder to the main flow, interrupting a menu`() = runTest {
        val presenter = object : RecordingPresenter() {
            override suspend fun showChoices(session: SceneSession, options: List<PresentedChoice>) {
                menus += options.map { it.text }
            }
        }
        val scene = scene(
            """
            @async name=timer
                @pause 50ms
                @sync
                @jump #Поздно
            @choice "Успеть"
                Виталик: Успел.
            # Поздно
            Виталик: Не успел.
            """.trimIndent(),
            presenter,
        )

        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(listOf(listOf("Успеть")), presenter.menus)
        assertEquals(listOf("Виталик: Не успел."), presenter.lines)
        // Nobody decided anything, so the UI has no winner to celebrate.
        assertEquals(listOf(-1), presenter.decided)
    }

    @Test
    fun `a closing menu is told which option won`() = runTest {
        // The dialogue UI keeps the winner on screen for a beat before clearing the menu, which it
        // can only do if closing the menu carries the result rather than just the fact it closed.
        val presenter = RecordingPresenter(mutableListOf(1, 0))
        val scene = scene(
            """
            @choice "Направо"
                @jump #Развилка
            @choice "Налево"
                @jump #Развилка
            # Развилка
            @choice "Дальше"
                Виталик: Пошли.
            """.trimIndent(),
            presenter,
        )

        scene.play(emptyList())

        assertEquals(listOf(1, 0), presenter.decided)
    }

    @Test
    fun `cancelling a track stops it from preempting`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @async name=timer
                @pause 10s
                @sync
                @jump #Поздно
            Виталик: Успел.
            @cancel timer
            @jump #Конец
            # Поздно
            Виталик: Не успел.
            # Конец
            Виталик: Финал.
            """.trimIndent(),
            presenter,
        )

        scene.play(emptyList())

        assertEquals(listOf("Виталик: Успел.", "Виталик: Финал."), presenter.lines)
    }

    @Test
    fun `tagged actions report start and end`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene("@pause 1ms tag=timer", presenter)
        val events = mutableListOf<String>()
        SceneEvent.ActionStarted.subscribe(backgroundScope) { if (it.tag == "timer") events += "start:${it.function}" }
        SceneEvent.ActionEnded.subscribe(backgroundScope) { if (it.tag == "timer") events += "end:${it.reason}" }

        scene.play(emptyList())

        assertEquals(listOf("start:pause", "end:COMPLETED"), events)
    }

    @Test
    fun `an advance that arrives before the wait is not lost`() = runTest {
        val presenter = RecordingPresenter()
        val answers = SceneAnswers(scene("", presenter), presenter)

        answers.advance()

        withTimeout(10.seconds) { answers.awaitAdvance() }
    }

    @Test
    fun `an actor argument resolves both on the main flow and inside a track`() = runTest {
        val presenter = RecordingPresenter()
        DialogueTestCommands.greeted.clear()
        val scene = scene(
            """
            @greet Сторож
            @async name=фон
                @greet Сторож
            @await фон
            Сторож: Готово.
            """.trimIndent(),
            presenter,
        )

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result, "the track must not fail: $result")
        assertEquals(listOf("Виталик", "Виталик"), DialogueTestCommands.greeted)
    }

    @Test
    fun `a character can be written into text and into a command`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene("Меня зовут {Сторож}, флаг {money}.", presenter)

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        scene.variables["money"] = StoryNumber(7f)
        scene.play(emptyList())

        assertEquals(listOf("Меня зовут Виталик, флаг 7."), presenter.lines)
    }

    @Test
    fun `a braced speaker names the character through a value`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            {Сторож}: Стой где стоишь.
            @set кто = "Сторож"
            {кто}: И не двигайся.
            """.trimIndent(),
            presenter,
        )

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        scene.play(emptyList())

        assertEquals(
            listOf("Виталик: Стой где стоишь.", "Виталик: И не двигайся."),
            presenter.lines,
        )
    }

    @Test
    fun `a braced speaker nothing is bound to fails instead of speaking as itself`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene("{Смотритель}: Кто здесь?", presenter)

        val result = scene.play(emptyList())

        assertTrue(result is SceneResult.Failed, "a misspelled speaker must be reported, not shown")
    }

    @Test
    fun `asking a name-only character for a uuid fails clearly`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene("Кто это? {Сторож.uuid}", presenter)

        scene.character("Сторож", DialogueCharacter.of("???"))
        val result = scene.play(emptyList())

        assertTrue(result is SceneResult.Failed, "a name has no uuid, so this must not print nonsense")
    }

    @Test
    fun `lists are ordinary variables`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @set items = ["sword", "shield"]
            @set items = items + "potion"
            {items.size} Items: first - {items[0]}, last - {items[2]}.
            @if items.size > 2
                Vitalik: That weights...
            """.trimIndent(),
            presenter,
        )

        scene.play(emptyList())

        assertEquals(
            listOf("3 Items: first - sword, last - potion.", "Vitalik: That weights..."),
            presenter.lines,
        )
    }

    @Test
    fun `a list survives a save and load`() = runTest {
        val checkpoint = SceneCheckpoint(
            address = "test:main.story",
            sourceHash = "hash",
            locale = null,
            frames = listOf(CheckpointFrame("test:main.story", StoryAnchor(null, 0, 0))),
            variables = mapOf(
                "вещи" to StoryList(listOf(StoryString("меч"), StoryNumber(2f), StoryBool(true))),
                "вложенный" to StoryList(listOf(StoryList(listOf(StoryNumber(1f))))),
            ),
        )

        val restored = assertNotNull(SceneCheckpoint.load(checkpoint.save()))

        assertEquals(checkpoint.variables["вещи"], restored.variables["вещи"])
        assertEquals(checkpoint.variables["вложенный"], restored.variables["вложенный"])
    }

    @Test
    fun `a command out of the time of its timeout is given up and the story goes on after the block`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @timeout 50ms
                @pause 10s
                @greet Сторож
            Виталик: Дальше.
            """.trimIndent(),
            presenter,
        )

        DialogueTestCommands.greeted.clear()
        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(emptyList(), DialogueTestCommands.greeted, "the rest of the block is skipped")
        assertEquals(listOf("Виталик: Дальше."), presenter.lines)
    }

    @Test
    fun `a define plays with its parameters and hands its value back`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene(
            """
            @import common/greetings.story
            @set who = "снаружи"
            @call greet(Виталик)
            @call twice(ха) result=смех
            Виталик: {смех}, {who}.

            @define twice(what)
                @return what + what
            """.trimIndent(),
            presenter,
            extra = mapOf("test:common/greetings.story" to "@define greet(who)\n    {who}: Привет, {who}."),
        )

        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result, result.toString())
        assertEquals(listOf("Виталик: Привет, Виталик.", "Виталик: хаха, снаружи."), presenter.lines)
    }

    @Test
    fun `a define resumes inside its body with its parameters`() = runTest {
        val presenter = HaltOnSecondLine()
        val source = mapOf(
            "test:main.story" to """
                @call count(3)
                Виталик: Конец.

                @define count(n)
                    Виталик: {n}.
                    Виталик: {n - 1}.
            """.trimIndent(),
        )
        val first = scene(source.getValue("test:main.story"), presenter)
        val job = backgroundScope.launch { first.play(emptyList()) }
        presenter.halted.await()
        val tag = assertNotNull(first.checkpoint()).save()
        job.cancel()

        val resumed = RecordingPresenter()
        val second = scene(source.getValue("test:main.story"), resumed)
        second.restore(SceneCheckpoint.load(tag))
        second.play(emptyList())

        assertEquals(listOf("Виталик: 2.", "Виталик: Конец."), resumed.lines)
    }

    @Test
    fun `metadata parameters reach the handler untouched`() = runTest {
        val presenter = RecordingPresenter()
        val scene = scene("@pause 1ms tag=timer overlay=clock", presenter)
        var overlay: String? = null
        SceneEvent.ActionStarted.subscribe(backgroundScope) {
            if (it.tag == "timer") overlay = (it.args.metadata["overlay"] as? StoryString)?.value
        }

        scene.play(emptyList())

        assertEquals("clock", overlay)
    }
}
