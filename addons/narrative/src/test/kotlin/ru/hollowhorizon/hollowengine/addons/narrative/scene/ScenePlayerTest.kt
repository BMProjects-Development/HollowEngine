package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import ru.hollowhorizon.hollowengine.addons.narrative.poll.ChoiceEvent
import ru.hollowhorizon.hollowengine.addons.narrative.poll.Poll
import ru.hollowhorizon.hollowengine.addons.narrative.poll.PollProvider
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.speech.Line
import ru.hollowhorizon.hollowengine.addons.narrative.speech.SayEvent
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Takes over every line and poll while [scope] lives: records them, and answers polls with a scripted list of picks. */
private open class Recorder(scope: CoroutineScope, private val picks: MutableList<Int> = mutableListOf()) {
    val lines = mutableListOf<String>()
    val menus = mutableListOf<List<String>>()

    /** Every option a poll closed with, in order. */
    val decided = mutableListOf<Int>()

    private val polls = object : PollProvider {
        override fun show(poll: Poll) {
            menus += poll.options.map { it.text }
            poll.decide(if (picks.isEmpty()) 0 else picks.removeAt(0))
        }

        override fun close(poll: Poll, chosen: Int) {
            decided += chosen
        }
    }

    init {
        SayEvent.subscribe(scope) { event -> event.sendLine { say(it) } }
        ChoiceEvent.subscribe(scope) { it.sendPoll(polls) }
    }

    open suspend fun say(line: Line) {
        lines += line.speaker?.let { "${it.name}: ${line.markup}" } ?: line.markup
    }
}

/** Records lines and stops the scene once, at its second line, the way a shutdown would. */
private class HaltOnSecondLine(scope: CoroutineScope) : Recorder(scope) {
    val halted = CompletableDeferred<Unit>()

    override suspend fun say(line: Line) {
        super.say(line)
        if (lines.size < 2 || halted.isCompleted) return
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

    val counted = mutableListOf<Float>()

    @SimpleAction("double")
    fun double(value: Float): Float {
        counted += value
        return value * 2
    }

    @SimpleAction("add-one")
    fun addOne(value: Float): Float = value + 1
}

class ScenePlayerTest {
    private fun TestScope.scene(
        source: String,
        extra: Map<String, String> = emptyMap(),
    ): ScenePlayer {
        StoryActions.register(DialogueTestCommands::class.java, "test")
        val sources = mapOf("test:main.story" to source) + extra
        return testScene(backgroundScope, sources, SceneLibrary { ActionCommands })
    }

    @Test
    fun `lines are shown with speaker and interpolation`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            Виталик: Привет!
            Ваш баланс: {money} монет.
            """.trimIndent(),
        )

        scene.variables["money"] = StoryNumber(7f)
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(listOf("Виталик: Привет!", "Ваш баланс: 7 монет."), presenter.lines)
    }

    @Test
    fun `choices show together and only the picked branch runs`() = runTest {
        val presenter = Recorder(backgroundScope, mutableListOf(1))
        val scene = scene(
            """
            @choice "Сейчас день."
                Виталик: С добрым утром.
            @choice "Сейчас ночь."
                Виталик: Спокойной ночи.
            Виталик: Пока.
            """.trimIndent(),
        )

        scene.play(emptyList())

        assertEquals(listOf(listOf("Сейчас день.", "Сейчас ночь.")), presenter.menus)
        assertEquals(listOf("Виталик: Спокойной ночи.", "Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `a choice hidden by its condition is not offered`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            @choice "Купить" if=money>10
                Виталик: Держи.
            @choice "Уйти"
                Виталик: Пока.
            """.trimIndent(),
        )

        scene.variables["money"] = StoryNumber(5f)
        scene.play(emptyList())

        assertEquals(listOf(listOf("Уйти")), presenter.menus)
        assertEquals(listOf("Виталик: Пока."), presenter.lines)
    }

    @Test
    fun `the choice event carries the id`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            @choice "Торговать" id=trade
                Виталик: Смотри товар.
            """.trimIndent(),
        )
        val seen = mutableListOf<String>()
        SceneEvent.Chosen.subscribe(backgroundScope) { if (it.session === scene) seen += it.key }

        scene.play(emptyList())

        assertEquals(listOf("trade"), seen)
    }

    @Test
    fun `while loop runs until its condition fails`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            @while money > 0
                Виталик: Осталось {money}.
                @set money = money - 1
            """.trimIndent(),
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
        val presenter = Recorder(backgroundScope)
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
        )

        scene.play(emptyList())

        assertEquals(
            listOf("Виталик: Внутри.", "Виталик: После call.", "Виталик: Финал."),
            presenter.lines,
        )
    }

    @Test
    fun `a closing menu is told which option won`() = runTest {
        val presenter = Recorder(backgroundScope, mutableListOf(1, 0))
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
        )

        scene.play(emptyList())

        assertEquals(listOf(1, 0), presenter.decided)
    }

    @Test
    fun `a cancelled track does not go on`() = runTest {
        DialogueTestCommands.greeted.clear()
        val scene = scene(
            """
            @async name=timer
                @pause 10s
                @greet Сторож
            @cancel timer
            @pause 20s
            """.trimIndent(),
        )

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result)
        assertEquals(emptyList(), DialogueTestCommands.greeted)
    }

    @Test
    fun `tagged actions report start and end`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene("@pause 1ms tag=timer")
        val events = mutableListOf<String>()
        SceneEvent.ActionStarted.subscribe(backgroundScope) { if (it.tag == "timer") events += "start:${it.function}" }
        SceneEvent.ActionEnded.subscribe(backgroundScope) { if (it.tag == "timer") events += "end:${it.reason}" }

        scene.play(emptyList())

        assertEquals(listOf("start:pause", "end:COMPLETED"), events)
    }

    @Test
    fun `an actor argument resolves both on the main flow and inside a track`() = runTest {
        val presenter = Recorder(backgroundScope)
        DialogueTestCommands.greeted.clear()
        val scene = scene(
            """
            @greet Сторож
            @async name=фон
                @greet Сторож
            @await фон
            Сторож: Готово.
            """.trimIndent(),
        )

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result, "the track must not fail: $result")
        assertEquals(listOf("Виталик", "Виталик"), DialogueTestCommands.greeted)
    }

    @Test
    fun `a character can be written into text and into a command`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene("Меня зовут {Сторож}, флаг {money}.")

        scene.character("Сторож", DialogueCharacter.of("Виталик"))
        scene.variables["money"] = StoryNumber(7f)
        scene.play(emptyList())

        assertEquals(listOf("Меня зовут Виталик, флаг 7."), presenter.lines)
    }

    @Test
    fun `a braced speaker names the character through a value`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            {Сторож}: Стой где стоишь.
            @set кто = "Сторож"
            {кто}: И не двигайся.
            """.trimIndent(),
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
        val presenter = Recorder(backgroundScope)
        val scene = scene("{Смотритель}: Кто здесь?")

        val result = scene.play(emptyList())

        assertTrue(result is SceneResult.Failed, "a misspelled speaker must be reported, not shown")
    }

    @Test
    fun `asking a name-only character for a uuid fails clearly`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene("Кто это? {Сторож.uuid}")

        scene.character("Сторож", DialogueCharacter.of("???"))
        val result = scene.play(emptyList())

        assertTrue(result is SceneResult.Failed, "a name has no uuid, so this must not print nonsense")
    }

    @Test
    fun `lists are ordinary variables`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            @set items = ["sword", "shield"]
            @set items = items + "potion"
            {items.size} Items: first - {items[0]}, last - {items[2]}.
            @if items.size > 2
                Vitalik: That weights...
            """.trimIndent(),
        )

        scene.play(emptyList())

        assertEquals(
            listOf("3 Items: first - sword, last - potion.", "Vitalik: That weights..."),
            presenter.lines,
        )
    }

    @Test
    fun `a command gives its value to an expression, nested calls first`() = runTest {
        val presenter = Recorder(backgroundScope)
        DialogueTestCommands.counted.clear()
        val scene = scene(
            """
            @set a = double(3)
            @set b = double(double(1)) + 1
            Итог {a}, {b}, {double(5)}, {add_one(1)}.
            """.trimIndent(),
        )

        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result, result.toString())
        assertEquals(listOf("Итог 6, 5, 10, 2."), presenter.lines)
        assertEquals(listOf(3f, 1f, 2f, 5f), DialogueTestCommands.counted, "inner calls run before outer ones")
    }

    @Test
    fun `a call in a loop condition runs on every pass`() = runTest {
        val presenter = Recorder(backgroundScope)
        DialogueTestCommands.counted.clear()
        val scene = scene(
            """
            @set n = 0
            @while double(n) < 6
                @set n = n + 1
            Готово: {n}.
            """.trimIndent(),
        )

        scene.play(emptyList())

        assertEquals(listOf("Готово: 3."), presenter.lines)
        assertEquals(listOf(0f, 1f, 2f, 3f), DialogueTestCommands.counted)
    }

    @Test
    fun `a command that gives nothing back cannot be a value`() {
        StoryActions.register(DialogueTestCommands::class.java, "test")
        val result = StoryCompiler.compile("test", "@set x = pause(1s)", ActionCommands)

        assertTrue(result.diagnostics.any { "gives nothing back" in it.message }, result.diagnostics.toString())
    }

    @Test
    fun `an entity survives a save and load by its uuid`() {
        val entity = StoryEntity(UUID.randomUUID(), "Стражник")
        val checkpoint = SceneCheckpoint(
            address = "test:main.story",
            sourceHash = "hash",
            locale = null,
            frames = listOf(CheckpointFrame("test:main.story", StoryAnchor(null, 0, 0))),
            variables = mapOf("guard" to entity),
        )

        assertEquals(entity, assertNotNull(SceneCheckpoint.load(checkpoint.save())).variables["guard"])
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
        val presenter = Recorder(backgroundScope)
        val scene = scene(
            """
            @timeout 50ms
                @pause 10s
                @greet Сторож
            Виталик: Дальше.
            """.trimIndent(),
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
        val presenter = Recorder(backgroundScope)
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
            extra =mapOf("test:common/greetings.story" to "@define greet(who)\n    {who}: Привет, {who}."),
        )

        val result = scene.play(emptyList())

        assertEquals(SceneResult.Finished, result, result.toString())
        assertEquals(listOf("Виталик: Привет, Виталик.", "Виталик: хаха, снаружи."), presenter.lines)
    }

    @Test
    fun `a define resumes inside its body with its parameters`() = runTest {
        val presenter = HaltOnSecondLine(backgroundScope)
        val source = mapOf(
            "test:main.story" to """
                @call count(3)
                Виталик: Конец.

                @define count(n)
                    Виталик: {n}.
                    Виталик: {n - 1}.
            """.trimIndent(),
        )
        val first = scene(source.getValue("test:main.story"))
        val job = backgroundScope.launch { first.play(emptyList()) }
        presenter.halted.await()
        val tag = assertNotNull(first.checkpoint()).save()
        job.cancel()

        presenter.lines.clear()
        val second = scene(source.getValue("test:main.story"))
        second.restore(SceneCheckpoint.load(tag))
        second.play(emptyList())

        assertEquals(listOf("Виталик: 2.", "Виталик: Конец."), presenter.lines)
    }

    @Test
    fun `metadata parameters reach the handler untouched`() = runTest {
        val presenter = Recorder(backgroundScope)
        val scene = scene("@pause 1ms tag=timer overlay=clock")
        var overlay: String? = null
        SceneEvent.ActionStarted.subscribe(backgroundScope) {
            if (it.tag == "timer") overlay = (it.args.metadata["overlay"] as? StoryString)?.value
        }

        scene.play(emptyList())

        assertEquals("clock", overlay)
    }
}
