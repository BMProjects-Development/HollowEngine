import kotlinx.coroutines.test.runTest
import ru.hollowhorizon.hollowengine.addons.narrative.graph.GeneratedStory
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryLink
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPins
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryTypedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Functions of a story: their parameters reach every stage of the body, and their results get back to the call. */
class StoryFunctionTest : StageTest() {
    private fun call(id: String, function: String, vararg arguments: Pair<String, String>) =
        StoryNode.Call(id, function, arguments = mapOf(*arguments))

    private fun log(id: String, text: String) = call(id, "log", "event" to text)

    private fun next(from: String, to: String, pin: String = StoryPins.NEXT) = StoryLink(from, pin, to, StoryPins.IN)

    private fun generate(graph: StoryGraph, functions: TestCommands = TestCommands()): GeneratedStory =
        StoryGraphCompiler.compile(StoryGraph.decode(graph.encode()), functions)
            .also { assertEquals(emptyList(), it.problems, it.code) }

    private fun function(name: String, params: List<StoryTypedName>, results: List<StoryTypedName>, body: StoryGraph) =
        StoryNode.Function("f_$name", name, params, results, body = body)

    /** start → [invoke] → log of the result [shown]. */
    private fun calling(function: StoryNode.Function, invoke: StoryNode.Invoke, shown: String) = StoryGraph(
        nodes = listOf(StoryNode.Start("s"), invoke, call("show", "log"), function),
        links = listOf(next("s", invoke.id), next(invoke.id, "show"), StoryLink(invoke.id, shown, "show", "event")),
    )

    @Test
    fun `a parameter outlives a pause inside the function`() = runTest {
        val body = StoryGraph(
            nodes = listOf(
                StoryNode.Parameters("p"),
                call("wait", "await", "name" to "\"step\""),
                log("hello", "\"hello \$name\""),
            ),
            links = listOf(next("p", "wait"), next("wait", "hello")),
        )
        val greet = function("greet", listOf(StoryTypedName("name", "String")), emptyList(), body)
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s"),
                StoryNode.Invoke("ask", "greet", arguments = mapOf("name" to "\"Bob\"")),
                log("after", "\"after\""),
                greet,
            ),
            links = listOf(next("s", "ask"), next("ask", "after")),
        )
        val code = generate(graph).code
        assertTrue(Regex("suspend fun step_\\w+\\(name: String\\)").containsMatchIn(code), code)
        val world = world("function-pause", code)
        world.launch("main")
        world.restart()
        world.release("step")
        assertEquals(listOf("hello Bob", "after"), StageTrace.take())
    }

    @Test
    fun `a function gives back its result`() = runTest {
        val body = StoryGraph(
            nodes = listOf(StoryNode.Parameters("p"), StoryNode.Return("r", mapOf("text" to "\"twice \${x * 2}\""))),
            links = listOf(next("p", "r")),
        )
        val twice = function("twice", listOf(StoryTypedName("x", "Int")), listOf(StoryTypedName("text", "String")), body)
        val graph = calling(twice, StoryNode.Invoke("use", "twice", arguments = mapOf("x" to "21")), "text")
        val world = world("function-result", generate(graph).code)
        world.launch("main")
        assertEquals(listOf("twice 42"), StageTrace.take())
    }

    @Test
    fun `several results come back as one record`() = runTest {
        val body = StoryGraph(
            nodes = listOf(
                StoryNode.Parameters("p"),
                call("wait", "await", "name" to "\"step\""),
                StoryNode.Return("r", mapOf("first" to "\"a\"", "second" to "\"b \$x\"")),
            ),
            links = listOf(next("p", "wait"), next("wait", "r")),
        )
        val results = listOf(StoryTypedName("first", "String"), StoryTypedName("second", "String"))
        val pair = function("pair", listOf(StoryTypedName("x", "Int")), results, body)
        val graph = calling(pair, StoryNode.Invoke("use", "pair", arguments = mapOf("x" to "7")), "second")
        val code = generate(graph).code
        assertTrue("data class PairResult" in code, code)
        val world = world("function-record", code)
        world.launch("main")
        world.restart()
        world.release("step")
        assertEquals(listOf("b 7"), StageTrace.take())
    }

    @Test
    fun `a flow of a function runs what the call links on from it each time it is signaled`() = runTest {
        val body = StoryGraph(
            nodes = listOf(StoryNode.Parameters("p"), StoryNode.Loop("each", "1..times"), StoryNode.Signal("fire", "tick")),
            links = listOf(next("p", "each"), next("each", "fire", StoryPins.BODY)),
        )
        val repeat = StoryNode.Function("f_repeat", "repeat", listOf(StoryTypedName("times", "Int")), flows = listOf("tick"), body = body)
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s"),
                StoryNode.Invoke("timer", "repeat", arguments = mapOf("times" to "2")),
                call("wait", "await", "name" to "\"step\""),
                log("tick", "\"tick\""),
                log("done", "\"done\""),
                repeat,
            ),
            links = listOf(next("s", "timer"), next("timer", "wait", "tick"), next("wait", "tick"), next("timer", "done")),
        )
        val world = world("function-flow", generate(graph).code)
        world.launch("main")
        world.restart()
        StageTrace.release("step")
        StageTrace.hold("step")
        world.settle()
        assertEquals(listOf("tick"), StageTrace.take())
        world.release("step")
        assertEquals(listOf("tick", "done"), StageTrace.take())
    }

    @Test
    fun `a function that may end without its result is reported`() {
        val body = StoryGraph(
            nodes = listOf(StoryNode.Parameters("p"), StoryNode.Branch("if", "true"), StoryNode.Return("r", mapOf("v" to "1"))),
            links = listOf(next("p", "if"), next("if", "r", StoryPins.THEN)),
        )
        val value = function("value", emptyList(), listOf(StoryTypedName("v", "Int")), body)
        val graph = StoryGraph(nodes = listOf(StoryNode.Start("s"), value))
        val problems = StoryGraphCompiler.compile(graph, TestCommands()).problems
        assertEquals(listOf("no_return"), problems.map { it.key })
    }

    @Test
    fun `a function of another story is imported from its file`() {
        val shared = function("greet", listOf(StoryTypedName("name", "String")), emptyList(), StoryGraph())
        val functions = object : TestCommands() {
            override fun storyFunction(file: String, name: String) = shared.takeIf { file == "quests:lib/common.story" }
        }
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s"),
                StoryNode.Invoke("ask", "greet", "quests:lib/common.story", mapOf("name" to "\"Bob\"")),
            ),
            links = listOf(next("s", "ask")),
        )
        val code = generate(graph, functions).code
        assertTrue("@file:Import(\"quests:lib/common.story\")" in code, code)
        assertTrue("`story`.`quests`.`lib`.`common`.greet(name = \"Bob\")" in code, code)
    }
}
