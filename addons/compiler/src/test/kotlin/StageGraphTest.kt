import kotlinx.coroutines.test.runTest
import ru.hollowhorizon.hollowengine.addons.narrative.graph.GeneratedStory
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLink
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryCallKind
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryOption
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPins
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A graph is generated into stages, so it runs, resumes and fails to compile as a hand-written script does. */
class StageGraphTest : StageTest() {
    private fun call(id: String, function: String, vararg arguments: Pair<String, String>) =
        StoryNode.Call(id, function, arguments = mapOf(*arguments))

    private fun log(id: String, text: String) = call(id, "log", "event" to text)

    private fun next(from: String, to: String, pin: String = StoryPins.NEXT) = StoryLink(from, pin, to, StoryPins.IN)

    private fun generate(graph: StoryGraph): GeneratedStory =
        StoryGraphCompiler.compile(StoryGraph.decode(graph.encode())).also { assertEquals(emptyList(), it.problems) }

    /** start → count → (trust < 3 ? count : done), with a wait inside the loop. */
    private val loop = StoryGraph(
        variables = listOf(StoryVariable("trust", "0")),
        nodes = listOf(
            StoryNode.Start("s", "main"),
            StoryNode.Assign("add", "trust", "trust + 1"),
            log("say", "\"trust \$trust\""),
            call("wait", "await", "name" to "\"step\""),
            StoryNode.Branch("more", "trust < 3"),
            log("done", "\"done\""),
        ),
        links = listOf(
            next("s", "add"), next("add", "say"), next("say", "wait"), next("wait", "more"),
            next("more", "add", StoryPins.THEN), next("more", "done", StoryPins.ELSE),
        ),
    )

    @Test
    fun `a loop of the graph runs and resumes after a restart`() = runTest {
        val world = world("loop", generate(loop).code)
        world.launch("main")
        assertEquals(listOf("trust 1"), StageTrace.take())

        world.restart()
        assertEquals(emptyList(), StageTrace.take())
        StageTrace.release("step")
        world.settle()
        assertEquals(listOf("trust 2", "trust 3", "done"), StageTrace.take())
    }

    @Test
    fun `a long loop does not pile up frames`() = runTest {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("count", "0")),
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Assign("add", "count", "count + 1"),
                StoryNode.Branch("more", "count < 5000"),
                log("done", "\"done \$count\""),
            ),
            links = listOf(
                next("s", "add"), next("add", "more"),
                next("more", "add", StoryPins.THEN), next("more", "done", StoryPins.ELSE),
            ),
        )
        world("long", generate(graph).code).launch("main")
        assertEquals(listOf("done 5000"), StageTrace.take())
    }

    @Test
    fun `values travel between stages and through a code node`() = runTest {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Code(
                    "made", "doubled = base * 2\nlog(\"code \$base\")",
                    inputs = mapOf("base" to "21"), outputs = mapOf("doubled" to "Int"),
                ),
                StoryNode.Branch("again", "false"),
                call("wait", "await", "name" to "\"after\""),
                call("show", "log"),
                StoryNode.Expression("text", "\"\" + 1"),
                StoryNode.Code("sum", "log(\"sum \$value\")", inputs = mapOf("value" to "")),
            ),
            links = listOf(
                next("s", "made"), next("made", "again"),
                next("again", "wait", StoryPins.THEN), next("again", "wait", StoryPins.ELSE),
                next("wait", "show"), next("show", "sum"),
                StoryLink("text", StoryPins.VALUE, "show", "event"),
                StoryLink("made", "doubled", "sum", "value"),
            ),
        )
        val world = world("values", generate(graph).code)
        world.launch("main")
        assertEquals(listOf("code 21"), StageTrace.take())
        world.restart()
        world.release("after")
        assertEquals(listOf("1", "sum 42"), StageTrace.take())
    }

    @Test
    fun `a choice compiles into branches of its options`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Choose("ask", listOf(StoryOption("Да \"точно\""), StoryOption("Цена: $5"))),
                log("yes", "\"yes\""),
                log("after", "\"after\""),
            ),
            links = listOf(
                next("s", "ask"), next("ask", "yes", StoryPins.option(0)),
                next("ask", "after", StoryPins.option(1)), next("yes", "after"),
            ),
        )
        val story = generate(graph)
        compiler.compile("choice", story.code)
        assertEquals(1, Regex("suspend fun block_after").findAll(story.code).count())
    }

    @Test
    fun `a line with a speaker and the name of the player compiles`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Say("hello", "Guide", "Hello, {player}!")),
            links = listOf(next("s", "hello")),
        )
        compiler.compile("line", generate(graph).code)
    }

    @Test
    fun `steps that do not pause share a stage and a pause gets its own`() {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("count", "0")),
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Assign("first", "count", "1"),
                log("second", "\"instant\""),
                StoryNode.Assign("third", "count", "2"),
                call("wait", "await", "name" to "\"step\""),
                StoryNode.Say("line", text = "Hello"),
            ),
            links = listOf(
                next("s", "first"), next("first", "second"), next("second", "third"),
                next("third", "wait"), next("wait", "line"),
            ),
        )
        val kinds: (StoryNode.Call) -> StoryCallKind = {
            if (it.function == "log") StoryCallKind.INSTANT else StoryCallKind.SUSPENDING
        }
        val stages = Regex("suspend fun (step_\\w+)").findAll(StoryGraphCompiler.compile(graph, kinds).code)
        assertEquals(listOf("step_first", "step_wait", "step_line"), stages.map { it.groupValues[1] }.toList())
    }

    @Test
    fun `an error of the Kotlin compiler leads back to its node`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), log("fine", "\"ok\""), call("broken", "log", "event" to "missing")),
            links = listOf(next("s", "fine"), next("fine", "broken")),
        )
        val story = generate(graph)
        val failure = compiler.errors(story.code, "located")
        val line = story.code.lines().indexOfFirst { "missing" in it } + 1
        assertEquals("broken", story.nodeAt(line))
        assertTrue("missing" in failure, failure)
    }

    @Test
    fun `problems of the graph are reported before Kotlin sees them`() {
        val story = StoryGraphCompiler.compile(
            StoryGraph(
                nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Assign("set", "nothing", "1"), call("a", "x"), call("b", "y")),
                links = listOf(next("s", "set"), next("set", "a"), next("set", "b"), StoryLink("gone", "value", "a", "x")),
            )
        )
        assertEquals(listOf(null, "set", "set"), story.problems.map { it.node }.sortedBy { it.orEmpty() })
    }
}
