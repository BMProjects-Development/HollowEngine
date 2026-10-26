package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.StoryAnchor
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
    var ended: DialogueResult? = null

    private val current = StringBuilder()
    private var speaker: DialogueCharacter? = null

    override suspend fun onDialogueStart(session: DialogueSession) {
        started++
    }

    override suspend fun onDialogueEnd(session: DialogueSession, result: DialogueResult) {
        ended = result
    }

    override suspend fun beginLine(session: DialogueSession, speaker: DialogueCharacter?) {
        current.clear()
        this.speaker = speaker
    }

    override suspend fun appendText(session: DialogueSession, text: String) {
        current.append(text)
    }

    override suspend fun waitForInput(session: DialogueSession) {
        current.append("<wait>")
    }

    override suspend fun endLine(session: DialogueSession) {
        lines += speaker?.let { "${it.name}: $current" } ?: current.toString()
        current.clear()
    }

    override suspend fun showChoices(session: DialogueSession, options: List<PresentedChoice>) {
        menus += options.map { it.text }
        val pick = if (picks.isEmpty()) 0 else picks.removeAt(0)
        (session as DialogueController).forceChoice(pick)
    }

    /** Every `chosen` the runtime reported when a menu closed, in order. */
    val decided = mutableListOf<Int>()

    override suspend fun hideChoices(session: DialogueSession, chosen: Int) {
        decided += chosen
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

class DialogueRuntimeTest {
    private fun controller(
        source: String,
        presenter: DialoguePresenter,
        extra: Map<String, String> = emptyMap(),
    ): DialogueController {
        StoryActions.register(DialogueTestCommands::class.java, "test")
        val sources = mapOf("test:main.story" to source) + extra
        val library = StoryLibrary { ActionCommands }
        library.addProvider { address -> sources[address] }
        return DialogueController("test:main.story", presenter, library)
    }

    @Test
    fun `lines are shown with speaker, interpolation and inline pauses`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            Виталик: Привет!
            Ваш баланс: {money} монет.[-] Точно.
            """.trimIndent(),
            presenter,
        )

        val result = controller.startHeadless { put("money", 7) }

        assertEquals(DialogueResult.Finished, result)
        assertEquals(listOf("Виталик: Привет!", "Ваш баланс: 7 монет.<wait> Точно."), presenter.lines)
        assertEquals(1, presenter.started)
    }

    @Test
    fun `choices show together and only the picked branch runs`() = runTest {
        val presenter = RecordingPresenter(mutableListOf(1))
        val controller = controller(
            """
            @choice "Сейчас день."
                Виталик: С добрым утром.
            @choice "Сейчас ночь."
                Виталик: Спокойной ночи.
            Виталик: Пока.
            """.trimIndent(),
            presenter,
        )

        controller.startHeadless()

        assertEquals(listOf(listOf("Сейчас день.", "Сейчас ночь.")), presenter.menus)
        assertEquals(listOf("Виталик: Спокойной ночи.", "Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `a choice hidden by its condition is not offered`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            @choice "Купить" if=money>10
                Виталик: Держи.
            @choice "Уйти"
                Виталик: Пока.
            """.trimIndent(),
            presenter,
        )

        controller.startHeadless { put("money", 5) }

        assertEquals(listOf(listOf("Уйти")), presenter.menus)
        assertEquals(listOf("Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `choice handlers receive the id`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            @choice "Торговать" id=trade
                Виталик: Смотри товар.
            """.trimIndent(),
            presenter,
        )
        val seen = mutableListOf<String>()
        controller.onChoice { seen += it.key }

        controller.startHeadless()

        assertEquals(listOf("trade"), seen)
    }

    @Test
    fun `while loop runs until its condition fails`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            @while money > 0
                Виталик: Осталось {money}.
                @set money = money - 1
            """.trimIndent(),
            presenter,
        )

        controller.startHeadless { put("money", 3) }

        assertEquals(
            listOf("Виталик: Осталось 3.", "Виталик: Осталось 2.", "Виталик: Осталось 1."),
            presenter.lines,
        )
    }

    @Test
    fun `call returns to the caller while jump does not`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
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

        controller.startHeadless()

        assertEquals(
            listOf("Виталик: Внутри.", "Виталик: После call.", "Виталик: Финал."),
            presenter.lines,
        )
    }

    @Test
    fun `sync hands the track's remainder to the main flow, interrupting a menu`() = runTest {
        val presenter = object : RecordingPresenter() {
            override suspend fun showChoices(session: DialogueSession, options: List<PresentedChoice>) {
                menus += options.map { it.text }
            }
        }
        val controller = controller(
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

        val result = controller.startHeadless()

        assertEquals(DialogueResult.Finished, result)
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
        val controller = controller(
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

        controller.startHeadless()

        assertEquals(listOf(1, 0), presenter.decided)
    }

    @Test
    fun `cancelling a track stops it from preempting`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
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

        controller.startHeadless()

        assertEquals(listOf("Виталик: Успел.", "Виталик: Финал."), presenter.lines)
    }

    @Test
    fun `tagged actions report start and end`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller("@pause 1ms tag=timer", presenter)
        val events = mutableListOf<String>()
        controller.onStart("timer") { events += "start:${it.function}" }
        controller.onEnd("timer") { events += "end:${it.reason}" }

        controller.startHeadless()

        assertEquals(listOf("start:pause", "end:COMPLETED"), events)
    }

    @Test
    fun `an advance that arrives before the wait is not lost`() = runTest {
        val secondLine = CompletableDeferred<Unit>()
        val presenter = object : RecordingPresenter() {
            override suspend fun endLine(session: DialogueSession) {
                super.endLine(session)
                if (lines.size == 2) secondLine.complete(Unit)
                (session as DialogueController).awaitAdvance()
            }
        }
        val controller = controller("Виталик: Раз.\nВиталик: Два.", presenter)

        controller.advance() // before the dialogue even starts
        backgroundScope.launch { controller.startHeadless() }

        withTimeout(10.seconds) { secondLine.await() }

        assertEquals(listOf("Виталик: Раз.", "Виталик: Два."), presenter.lines)
    }

    @Test
    fun `an actor argument resolves both on the main flow and inside a track`() = runTest {
        val presenter = RecordingPresenter()
        DialogueTestCommands.greeted.clear()
        val controller = controller(
            """
            @greet Сторож
            @async name=фон
                @greet Сторож
            @await фон
            Сторож: Готово.
            """.trimIndent(),
            presenter,
        )

        val result = controller.startHeadless {
            character("Сторож", DialogueCharacter.of("Виталик"))
        }

        assertEquals(DialogueResult.Finished, result, "the track must not fail: $result")
        assertEquals(listOf("Виталик", "Виталик"), DialogueTestCommands.greeted)
    }

    @Test
    fun `a character can be written into text and into a command`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller("Меня зовут {Сторож}, флаг {money}.", presenter)

        controller.startHeadless {
            character("Сторож", DialogueCharacter.of("Виталик"))
            put("money", 7)
        }

        assertEquals(listOf("Меня зовут Виталик, флаг 7."), presenter.lines)
    }

    @Test
    fun `a braced speaker names the character through a value`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            {Сторож}: Стой где стоишь.
            @set кто = "Сторож"
            {кто}: И не двигайся.
            """.trimIndent(),
            presenter,
        )

        controller.startHeadless {
            character("Сторож", DialogueCharacter.of("Виталик"))
        }

        assertEquals(
            listOf("Виталик: Стой где стоишь.", "Виталик: И не двигайся."),
            presenter.lines,
        )
    }

    @Test
    fun `a braced speaker nothing is bound to fails instead of speaking as itself`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller("{Смотритель}: Кто здесь?", presenter)

        val result = controller.startHeadless()

        assertTrue(result is DialogueResult.Failed, "a misspelled speaker must be reported, not shown")
    }

    @Test
    fun `asking a name-only character for a uuid fails clearly`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller("Кто это? {Сторож.uuid}", presenter)

        val result = controller.startHeadless {
            character("Сторож", DialogueCharacter.of("???"))
        }

        assertTrue(result is DialogueResult.Failed, "a name has no uuid, so this must not print nonsense")
    }

    @Test
    fun `lists are ordinary variables`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller(
            """
            @set items = ["sword", "shield"]
            @set items = items + "potion"
            {items.size} Items: first - {items[0]}, last - {items[2]}.
            @if items.size > 2
                Vitalik: That weights...
            """.trimIndent(),
            presenter,
        )

        controller.startHeadless()

        assertEquals(
            listOf("3 Items: first - sword, last - potion.", "Vitalik: That weights..."),
            presenter.lines,
        )
    }

    @Test
    fun `a list survives a save and load`() = runTest {
        val checkpoint = StoryCheckpoint(
            address = "test:main.story",
            sourceHash = "hash",
            locale = null,
            frames = listOf(CheckpointFrame("test:main.story", StoryAnchor(null, 0, 0))),
            variables = mapOf(
                "вещи" to StoryList(listOf(StoryString("меч"), StoryNumber(2f), StoryBool(true))),
                "вложенный" to StoryList(listOf(StoryList(listOf(StoryNumber(1f))))),
            ),
        )

        val restored = assertNotNull(StoryCheckpoint.load(checkpoint.save()))

        assertEquals(checkpoint.variables["вещи"], restored.variables["вещи"])
        assertEquals(checkpoint.variables["вложенный"], restored.variables["вложенный"])
    }

    @Test
    fun `metadata parameters reach the handler untouched`() = runTest {
        val presenter = RecordingPresenter()
        val controller = controller("@pause 1ms tag=timer overlay=clock", presenter)
        var overlay: String? = null
        controller.onStart("timer") { overlay = (it.args.metadata["overlay"] as? StoryString)?.value }

        controller.startHeadless()

        assertEquals("clock", overlay)
    }
}
