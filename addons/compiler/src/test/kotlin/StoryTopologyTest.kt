import kotlinx.coroutines.test.runTest
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.copyNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeNodes
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.renameVariable
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import kotlin.reflect.typeOf
import kotlin.test.*

/** Commands of tests: what a node says of itself, with every parameter taking a string. */
internal class TestCommands(private val kinds: (StoryNode.Call) -> StoryCallKind = { StoryCallKind.SUSPENDING }) :
    StoryFunctions {
    override fun signature(call: StoryNode.Call) = StoryBuiltins.find(call.action.orEmpty()) ?: StoryFunctionSignature(
        call.function, call.function, (call.arguments.keys + "event").map { StoryParam(it, typeOf<String>()) },
        returns = call.returns, block = call.block,
    )

    override fun kind(call: StoryNode.Call) = kinds(call)
}

/** Graphs of the shapes that are easy to get wrong, and the edits that change their shape. */
class StoryTopologyTest : StageTest() {
    private fun call(id: String, function: String, vararg arguments: Pair<String, String>, returns: String? = null) =
        StoryNode.Call(id, function, arguments = mapOf(*arguments), returns = returns)

    private fun log(id: String, text: String = id) = call(id, "log", "event" to "\"$text\"")

    private fun next(from: String, to: String, pin: String = StoryPins.NEXT) = StoryLink(from, pin, to, StoryPins.IN)

    private fun compile(graph: StoryGraph) = StoryGraphCompiler.compile(graph, TestCommands())

    private fun code(graph: StoryGraph) = compile(graph).also { assertEquals(emptyList(), it.problems) }.code

    @Test
    fun `branches that meet go on together`() = runTest {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Branch("b", "true"),
                log("a"),
                log("c"),
                log("merge"),
                log("after")
            ),
            links = listOf(
                next("s", "b"), next("b", "a", StoryPins.THEN), next("b", "c", StoryPins.ELSE),
                next("a", "merge"), next("c", "merge"), next("merge", "after"),
            ),
        )
        world("diamond", code(graph)).launch("main")
        assertEquals(listOf("a", "merge", "after"), StageTrace.take())
    }

    @Test
    fun `a branch inside a branch finds its way back`() = runTest {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"), StoryNode.Branch("outer", "true"), StoryNode.Branch("inner", "false"),
                log("x"), log("y"), log("z"), log("end"),
            ),
            links = listOf(
                next("s", "outer"), next("outer", "inner", StoryPins.THEN), next("outer", "z", StoryPins.ELSE),
                next("inner", "x", StoryPins.THEN), next("inner", "y", StoryPins.ELSE),
                next("x", "end"), next("y", "end"), next("z", "end"),
            ),
        )
        world("nested", code(graph)).launch("main")
        assertEquals(listOf("y", "end"), StageTrace.take())
    }

    @Test
    fun `a link back repeats what it leads to`() = runTest {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("count", "0")),
            nodes = listOf(
                StoryNode.Start("s", "main"), StoryNode.Assign("add", "count", "count + 1"),
                call("say", "log", "event" to "\"turn \$count\""), StoryNode.Branch("more", "count < 2"), log("end"),
            ),
            links = listOf(
                next("s", "add"), next("add", "say"), next("say", "more"),
                next("more", "add", StoryPins.THEN), next("more", "end", StoryPins.ELSE),
            ),
        )
        world("back-edge", code(graph)).launch("main")
        assertEquals(listOf("turn 1", "turn 2", "end"), StageTrace.take())
    }

    @Test
    fun `a chain of reroutes survives losing a link of it`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Reroute("r1", flow = true),
                StoryNode.Reroute("r2", flow = true),
                StoryNode.Reroute("r3", flow = true),
                log("a"),
            ),
            links = listOf(next("s", "r1"), next("r1", "r2"), next("r2", "r3"), next("r3", "a")),
        )
        val shorter = graph.removeNodes(setOf("r2"))
        assertEquals(setOf("s", "r1", "r3", "a"), shorter.nodes.mapTo(HashSet()) { it.id })
        assertEquals(listOf(listOf("s", "a")), shorter.dissolved().links.map { listOf(it.from, it.to) })
    }

    @Test
    fun `a copy links up inside and keeps reading from outside`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"), StoryNode.Expression("value", "1"), log("a"), call("b", "log"), log("c")
            ),
            links = listOf(
                next("s", "a"), next("a", "b"), next("b", "c"), StoryLink("value", StoryPins.VALUE, "b", "event")
            ),
        )
        val (copied, ids) = graph.copyNodes(setOf("a", "b"), StoryPoint(10f, 10f))
        val a = ids.getValue("a")
        val b = ids.getValue("b")
        assertTrue(next(a, b) in copied.links)
        assertTrue(StoryLink("value", StoryPins.VALUE, b, "event") in copied.links)
        assertFalse(copied.links.any { it.from == b && it.to == "c" }, "flow out of a copy waits to be linked")
        assertFalse(copied.links.any { it.from == "s" && it.to == a }, "and so does flow into it")
    }

    @Test
    fun `a link beats what is typed into the receiver`() {
        val call = call("use", "show", "target" to "typed").copy(receiver = "target")
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Expression("value", "linked"), call),
            links = listOf(next("s", "use"), StoryLink("value", StoryPins.VALUE, "use", "target")),
        )
        val generated = code(graph)
        assertTrue("linked" in generated, generated)
        assertFalse("typed" in generated, generated)
    }

    @Test
    fun `a result that can be empty stays empty`() = runTest {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("found", "null", "String?")),
            nodes = listOf(
                StoryNode.Start("s", "main"),
                call("get", "nothing", returns = "kotlin.String?"),
                StoryNode.Assign("keep", "found"),
                StoryNode.Branch("empty", "found == null"),
                log("none"),
                log("some"),
            ),
            links = listOf(
                next("s", "get"), next("get", "keep"), next("keep", "empty"),
                next("empty", "none", StoryPins.THEN), next("empty", "some", StoryPins.ELSE),
                StoryLink("get", StoryPins.VALUE, "keep", StoryPins.VALUE),
            ),
        )
        val generated = code(graph)
        assertFalse("!!" in generated.substringAfter("found = "), generated)
        world("nullable", "$generated\nfun nothing(): String? = null").launch("main")
        assertEquals(listOf("none"), StageTrace.take())
    }

    @Test
    fun `a required result is asserted to be there`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"), call("get", "name", returns = "kotlin.String"), call("say", "log")
            ),
            links = listOf(next("s", "get"), next("get", "say"), StoryLink("get", StoryPins.VALUE, "say", "event")),
        )
        assertTrue("!!" in code(graph))
    }

    @Test
    fun `moving nodes around does not change what a story means`() {
        val graph = StoryGraph(nodes = listOf(StoryNode.Start("s", "main"), log("a")), links = listOf(next("s", "a")))
        val moved = graph.copy(layout = mapOf("a" to StoryPoint(100f, 40f)), collapsed = setOf("a"))
        assertEquals(graph.revision(), moved.revision())
        assertNotEquals(graph.revision(), graph.copy(nodes = graph.nodes.dropLast(1) + log("a", "other")).revision())
    }

    @Test
    fun `renaming a variable reaches what reads it and nothing else`() {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("trust", "0")),
            nodes = listOf(
                StoryNode.Say("say", text = "{trust} and trust, {trust + 1}"),
                StoryNode.Assign("set", "trust", "trust + npc.trust + \"trust \$trust\".length + mistrust"),
                StoryNode.Code("own", "val trust = 1\nlog(trust)"),
            ),
        ).renameVariable("trust", "faith")
        assertEquals("0", graph.variables.single().initial)
        assertEquals("{faith} and trust, {faith + 1}", (graph.node("say") as StoryNode.Say).text)
        val set = graph.node("set") as StoryNode.Assign
        assertEquals("faith", set.variable)
        assertEquals("faith + npc.trust + \"trust \$faith\".length + mistrust", set.value)
        assertEquals("val trust = 1\nlog(trust)", (graph.node("own") as StoryNode.Code).code)
    }

    @Test
    fun `a suspending value keeps a write out of the instant steps next to it`() {
        val graph = StoryGraph(
            variables = listOf(StoryVariable("value", "\"\"", "String")),
            nodes = listOf(
                StoryNode.Start("s", "main"), log("first"), StoryNode.Assign("set", "value"), log("last"),
                call("ask", "question", returns = "kotlin.String"),
            ),
            links = listOf(
                next("s", "first"), next("first", "set"), next("set", "last"),
                StoryLink("ask", StoryPins.VALUE, "set", StoryPins.VALUE),
            ),
        )
        val kinds = TestCommands { if (it.function == "log") StoryCallKind.INSTANT else StoryCallKind.SUSPENDING }
        val stages = Regex("suspend fun (step_\\w+)").findAll(StoryGraphCompiler.compile(graph, kinds).code)
        assertTrue("step_set" in stages.map { it.groupValues[1] }.toList())
    }

    @Test
    fun `a value from another branch is pointed out`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Branch("b", "true"),
                call("get", "name", returns = "kotlin.String"),
                call("say", "log"),
                log("end"),
            ),
            links = listOf(
                next("s", "b"), next("b", "get", StoryPins.THEN), next("b", "say", StoryPins.ELSE),
                StoryLink("get", StoryPins.VALUE, "say", "event"), next("get", "end"), next("say", "end"),
            ),
        )
        val problem = compile(graph).problems.single()
        assertEquals("say", problem.node)
        assertTrue(problem.warning)
    }

    @Test
    fun `an item is out of reach after its loop`() {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"), StoryNode.Loop("each", "listOf(1)"), log("body"), call("after", "log")
            ),
            links = listOf(
                next("s", "each"), next("each", "body", StoryPins.BODY), next("each", "after"),
                StoryLink("each", StoryPins.ITEM, "after", "event"),
            ),
        )
        assertFalse(compile(graph).problems.single().warning)
    }

    @Test
    fun `a checkpoint that asks for it takes a restart back to itself`() = runTest {
        val world = world(
            "checkpoint-restart", """
            @State
            suspend fun main() {
                log("before")
                checkpoint("question", restart = true)
                log("asked")
                await("answer")
                log("answered")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("before", "asked"), StageTrace.take())
        world.restart()
        assertEquals(listOf("asked"), StageTrace.take())
        world.release("answer")
        assertEquals(listOf("answered"), StageTrace.take())
    }

    @Test
    fun `the next checkpoint turns going back off`() = runTest {
        val world = world(
            "checkpoint-restart-off", """
            @State
            suspend fun main() {
                checkpoint("question", restart = true)
                log("asked")
                await("answer")
                checkpoint("answered")
                log("after")
                await("end")
                log("done")
            }
        """
        )
        world.launch("main")
        world.release("answer")
        assertEquals(listOf("asked", "after"), StageTrace.take())
        world.restart()
        world.release("end")
        assertEquals(
            listOf("after", "done"), StageTrace.take(), "it goes on from the last checkpoint, not the one before"
        )
    }

    @Test
    fun `a checkpoint node can ask for it too`() = runTest {
        val graph = StoryGraph(
            nodes = listOf(
                StoryNode.Start("s", "main"),
                StoryNode.Call(
                    "mark", action = "checkpoint", arguments = mapOf("name" to "\"question\"", "restart" to "true")
                ),
                log("asked"), call("wait", "await", "name" to "\"answer\""),
            ),
            links = listOf(next("s", "mark"), next("mark", "asked"), next("asked", "wait")),
        )
        val world = world("checkpoint-node", code(graph))
        world.launch("main")
        assertEquals(listOf("asked"), StageTrace.take())
        world.restart()
        assertEquals(listOf("asked"), StageTrace.take())
    }

    @Test
    fun `what a command says beats what an old file holds`() {
        val stale = StoryNode.Call(
            "mark", "old.name", action = "checkpoint", arguments = mapOf("name" to "\"a\""), block = true
        )
        val graph = StoryGraph(nodes = listOf(StoryNode.Start("s", "main"), stale), links = listOf(next("s", "mark")))
        val generated = code(graph)
        assertTrue("checkpoint(" in generated, generated)
        assertFalse("old.name" in generated, generated)
    }

    @Test
    fun `the parser splits arguments the way Kotlin reads them`() {
        assertEquals(
            listOf("1", "", "z"), StoryLiterals.vectorParts(StoryLiterals.vector(listOf("1", "", "z"), "vec"), "vec")
        )
        assertEquals(
            listOf("max(1, 2)", "\"a,b\".length", "3"),
            StoryLiterals.vectorParts("vec(max(1, 2), \"a,b\".length, 3)", "vec")
        )
        assertEquals(null, StoryKotlin.arguments("a), (b"))
    }

    @Test
    fun `a list field keeps the Kotlin it was given`() {
        val written = "listOf(\"a\",  \"b\")"
        val type = typeOf<List<String>>()
        assertEquals(written, StoryLiterals.expression(written, type))
        assertEquals(written, StoryLiterals.text(written, type))
        assertEquals(listOf("a", "b"), StoryLiterals.listed(written))
        assertEquals("listOf(\"a\", \"c\")", StoryLiterals.list(listOf("a", "c")))
        assertEquals("", StoryLiterals.list(emptyList()))
    }

    @Test
    fun `code with no flow is an expression where it is read`() = runTest {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Code("c", "\"value\""), call("say", "log")),
            links = listOf(next("s", "say"), StoryLink("c", StoryPins.VALUE, "say", "event")),
        )
        world("code-value", code(graph)).launch("main")
        assertEquals(listOf("value"), StageTrace.take())
    }

    @Test
    fun `code in the flow has no untyped value to keep`() {
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), StoryNode.Code("c", "\"value\""), call("say", "log")),
            links = listOf(next("s", "c"), next("c", "say"), StoryLink("c", StoryPins.VALUE, "say", "event")),
        )
        assertTrue(compile(graph).problems.any { it.node == "c" || it.node == "say" })
    }

    @Test
    fun `a list takes as many inputs as its author added`() {
        val list = StoryNode.Pure("l", "list", mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4"), count = 4)
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), list, StoryNode.Pure("x", "list"), call("use", "log")),
            links = listOf(
                next("s", "use"),
                StoryLink("l", "list", "use", "event"),
                StoryLink("x", "list", "l", "d"),
            ),
        )
        assertContains(compile(graph).code, "listOf((1), (2), (3), (emptyList<Any?>()))")
        val shorter = graph.removeInput("l", 1)
        assertEquals(mapOf("a" to "1", "b" to "3", "c" to "4"), (shorter.node("l") as StoryNode.Pure).arguments)
        assertEquals("c", shorter.links.single { it.from == "x" }.toPin)
    }

    @Test
    fun `pins read what they carry without the flow that made it`() {
        val list = StoryNode.Pure("l", "list", mapOf("a" to "\"x\""), count = 1)
        val loop = StoryNode.Loop("each", "")
        val sum = StoryNode.Pure("sum", "add", mapOf("b" to "1"))
        val code = StoryNode.Code("c", "", outputs = mapOf("found" to "Int"))
        val graph = StoryGraph(
            nodes = listOf(StoryNode.Start("s", "main"), list, loop, sum, code),
            links = listOf(
                next("s", "c"),
                next("c", "each"),
                StoryLink("l", "list", "each", StoryPins.ITEMS),
                StoryLink("c", "found", "sum", "a"),
            ),
        )
        val pins = StoryGraphCompiler.pins(graph, TestCommands())
        assertEquals("listOf((\"x\"))", pins["l" to "list"])
        assertEquals("(listOf((\"x\"))).iterator().next()", pins["each" to StoryPins.ITEM])
        assertContains(pins.getValue("sum" to "result"), "(null as Int?)")
    }

    @Test
    fun `only a nullable variable can start empty`() {
        val start = listOf(StoryNode.Start("s", "main"))
        val empty = StoryGraph(variables = listOf(StoryVariable("a", "", "String?")), nodes = start)
        assertContains(code(empty), "var a by saved<String?>(null)")
        val required = StoryGraph(variables = listOf(StoryVariable("a", "", "String")), nodes = start)
        assertTrue(compile(required).problems.isNotEmpty())
    }

    @Test
    fun `the parser finds what has a color of its own`() {
        val tokens = StoryKotlin.syntax("val a = \"x\" + 1 // note")!!.tokens
        assertEquals(TokenType.KEYWORD, tokens.first { it.start == 0 }.type)
        assertEquals(listOf(TokenType.KEYWORD, TokenType.STRING, TokenType.NUMERIC_LITERAL, TokenType.COMMENT), tokens.map { it.type }.distinct())
    }

    @Test
    fun `broken Kotlin is told from fine Kotlin`() {
        listOf(
            "x + 1", "listOf(1, 2).size", "val a = 1\nlog(a)", "\"}\""
        ).forEach { assertTrue(StoryKotlin.syntax(it)!!.valid, it) }
        listOf("x +", "foo(", "\"open", "} fun other() {").forEach { assertFalse(StoryKotlin.syntax(it)!!.valid, it) }
    }
}
