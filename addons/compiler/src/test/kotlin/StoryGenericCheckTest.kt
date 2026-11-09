import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryGraphBoxes
import ru.hollowhorizon.hollowengine.addons.narrative.ide.graph.StoryRowField
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentAnalysis
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiInlineStyle
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnosticSeverity
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextHighlight
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.npcs.navigation.BlockCost
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NavigationComponent
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generic blocks as the editor shows them, the story checked by the Kotlin compiler as the editor checks it. A type
 * parameter is fixed by the first value linked into a block, exactly as the type of that value; with nothing linked
 * in, by the place its output goes into; with neither, it is whatever Kotlin infers from the fields.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoryGenericCheckTest {
    private lateinit var compiler: StageCompiler
    private val component = "test:generic_navigation".rl
    private val pack = TypeOperators.PACK + BlockCost::class.qualifiedName
    private val cost = BlockCost::class.qualifiedName!!

    @BeforeAll
    fun setUp() {
        compiler = StageCompiler()
        ComponentDescriptorRegistry.register(
            ComponentDescriptor(
                component, NavigationComponent::class, NavigationComponent.serializer()
            )
        )
    }

    @AfterAll
    fun close() {
        ComponentDescriptorRegistry.unregisterDescriptor(component)
        compiler.close()
    }

    private inner class Opened(private var graph: StoryGraph) {
        val state = StoryEditorState()
        lateinit var analysis: StoryAnalysis
            private set
        private val session = StoryInsight.Session(state, "").also { state.insight = it }

        init {
            session.close()
            update(graph)
        }

        fun update(graph: StoryGraph) {
            this.graph = graph
            session.of(graph)
            while (StoryInsight.take(session, analyzed(session.checked.text))) Unit
            analysis = session.analysis
        }

        private val boxes get() = StoryGraphBoxes(graph, state)

        fun output(node: String, pin: String): String? = boxes.outputKotlin(node, pin)

        fun input(node: String, pin: String): String? = boxes.inputKotlin(node, pin)

        fun inputType(node: String, pin: String): StoryPinType =
            boxes.of(graph.node(node)!!).rows.firstNotNullOf { it.input?.takeIf { it.name == pin } }.type

        fun fixed(node: String, pin: String): String? = analysis.fixed[node to pin]

        fun field(node: String, key: String): StoryRowField =
            boxes.of(graph.node(node)!!).rows.firstNotNullOf { row -> row.field?.takeIf { it.key == key } }

        fun inField(node: String, key: String): List<String> =
            analysis.fields[StoryFieldKey(node, key)].orEmpty().filter { it.severity == UiTextDiagnosticSeverity.ERROR }
                .map { it.message }

        fun ofBlock(node: String): List<String> {
            val fields =
                analysis.fields.filterKeys { it.owner == node }.values.flatten().mapTo(HashSet()) { it.message }
            return analysis.checks.filter { it.node == node && !it.warning && it.message !in fields }.map { it.message }
        }

        fun colored(node: String, key: String): Boolean =
            analysis.colors[StoryFieldKey(node, key)]?.highlights.orEmpty().isNotEmpty()
    }

    private fun analyzed(text: String): KotlinFragmentAnalysis {
        val analyzer = compiler.environment.analyzer
        val starts = text.split('\n').runningFold(0) { at, line -> at + line.length + 1 }
        fun offset(line: Int, column: Int) = (starts.getOrElse(line) { text.length } + column).coerceAtMost(text.length)
        val diagnostics = analyzer.diagnostic(FILE, text).map {
            val start = offset(it.range.start.line, it.range.start.column)
            UiTextDiagnostic(
                start, offset(it.range.end.line, it.range.end.column).coerceAtLeast(start), it.message,
                if (it.severity.isError()) UiTextDiagnosticSeverity.ERROR else UiTextDiagnosticSeverity.WARNING,
            )
        }
        val highlights = analyzer.highlight(FILE, text, 0).flatMapIndexed { line, spans ->
            var at = starts[line]
            spans.spans.mapNotNull { (piece, style) ->
                val start = at
                at += piece.length
                UiTextHighlight(
                    start, at, UiInlineStyle()
                ).takeIf { style.color != TokenType.DEFAULT && piece.isNotBlank() }
            }
        }
        return KotlinFragmentAnalysis(highlights, diagnostics, analyzer.propertyTypes(FILE, text, StoryFieldChecks.PIN))
    }

    private fun costOf(id: String, block: String) =
        StoryNode.Pure(id, pack, mapOf("block" to "\"$block\"", "cost" to "1f"))

    @Test
    fun `with nothing linked or written, a list is a list of T and nothing is wrong with it`() {
        val opened = Opened(StoryGraph(nodes = listOf(StoryNode.Pure("list", "list", count = 2))))
        assertEquals("kotlin.collections.List<T>", opened.output("list", "list"))
        assertEquals("T", opened.input("list", "a"))
        assertEquals(emptyList(), opened.ofBlock("list"))
    }

    @Test
    fun `with nothing linked, the fields tell what Kotlin infers, and nothing is wrong with them`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    StoryNode.Pure(
                        "list", "list", mapOf("a" to "1", "b" to "\"a\""), count = 2
                    )
                )
            )
        )
        assertNull(opened.fixed("list", "a"))
        assertTrue(opened.output("list", "list")!!.contains("java.io.Serializable"))
        assertTrue(opened.input("list", "a")!!.contains("java.io.Serializable"))
        assertEquals(StoryFieldForm.Code, opened.field("list", "b").form)
        assertEquals(emptyList(), opened.inField("list", "a") + opened.inField("list", "b") + opened.ofBlock("list"))
    }

    @Test
    fun `a value linked in fixes the type exactly, and a field of another type is an error in that field`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(costOf("cost", "dirt"), StoryNode.Pure("list", "list", mapOf("b" to "1.0f"), count = 2)),
                links = listOf(StoryLink("cost", TypeOperators.SUBJECT, "list", "a")),
            ),
        )
        assertEquals(cost, opened.output("cost", TypeOperators.SUBJECT), "a block made of a class is of that class")
        assertEquals("kotlin.collections.List<$cost>", opened.output("list", "list"))
        assertEquals(cost, opened.fixed("list", "b"))
        assertTrue(opened.inField("list", "b").isNotEmpty())
        assertEquals(emptyList(), opened.ofBlock("list"))
    }

    @Test
    fun `values of one class linked in are a list of that class`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    costOf("dirt", "dirt"), costOf("grass", "grass"), StoryNode.Pure("list", "list", count = 2)
                ),
                links = listOf(
                    StoryLink("dirt", TypeOperators.SUBJECT, "list", "a"),
                    StoryLink("grass", TypeOperators.SUBJECT, "list", "b"),
                ),
            ),
        )
        assertEquals("kotlin.collections.List<$cost>", opened.output("list", "list"))
        assertEquals(emptyList(), opened.ofBlock("list"))
    }

    @Test
    fun `a field of a fixed type stays Kotlin, colored, with what is wrong in it`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(costOf("cost", "dirt"), StoryNode.Pure("list", "list", mapOf("a" to "1"), count = 2)),
                links = listOf(StoryLink("cost", TypeOperators.SUBJECT, "list", "b")),
            ),
        )
        val field = opened.field("list", "a")
        assertEquals(StoryFieldForm.Code, field.form)
        assertTrue(field.code)
        assertTrue(opened.colored("list", "a"))
        assertTrue(opened.inField("list", "a").single().contains("BlockCost"))
    }

    @Test
    fun `a value linked after the first has to fit what the first fixed`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    StoryNode.Expression("id", "java.util.UUID.randomUUID()"),
                    StoryNode.Expression("text", "\"a\""),
                    StoryNode.Pure("list", "list", count = 2),
                ),
                links = listOf(
                    StoryLink("id", StoryPins.VALUE, "list", "a"),
                    StoryLink("text", StoryPins.VALUE, "list", "b"),
                ),
            ),
        )
        assertEquals("java.util.UUID", opened.fixed("list", "a"), "not the type both have in common")
        assertTrue(opened.ofBlock("list").isNotEmpty())
    }

    @Test
    fun `with nothing linked in, the place the output goes into fixes the type`() {
        val opened = Opened(
            StoryGraph(
                variables = listOf(StoryVariable("names", "listOf()", "kotlin.collections.List<kotlin.String>")),
                nodes = listOf(
                    StoryNode.Pure("list", "list", mapOf("a" to "\"a\"", "b" to "1"), count = 2),
                    StoryNode.Assign("set", "names"),
                ),
                links = listOf(StoryLink("list", "list", "set", StoryPins.VALUE)),
            ),
        )
        assertEquals("kotlin.String", opened.fixed("list", "a"))
        assertEquals(emptyList(), opened.inField("list", "a"))
        assertTrue(opened.inField("list", "b").isNotEmpty())
    }

    @Test
    fun `a block linked to a fixed block reads its type, not what its fields would make`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    StoryNode.Expression("id", "java.util.UUID.randomUUID()"),
                    StoryNode.Pure("list", "list", mapOf("b" to "1f"), count = 2),
                    StoryNode.Pure("get", "list-get", mapOf("index" to "0")),
                ),
                links = listOf(
                    StoryLink("id", StoryPins.VALUE, "list", "a"),
                    StoryLink("list", "list", "get", "list"),
                ),
            ),
        )
        assertEquals("java.util.UUID", opened.fixed("get", "item"))
        assertEquals("java.util.UUID", opened.output("get", "item"))
    }

    @Test
    fun `a block whose output goes into a fixed block takes its type from there`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    StoryNode.Expression("id", "java.util.UUID.randomUUID()"),
                    StoryNode.Pure("choice", "select"),
                    StoryNode.Pure("same", "equal"),
                ),
                links = listOf(
                    StoryLink("choice", "result", "same", "a"),
                    StoryLink("id", StoryPins.VALUE, "same", "b"),
                ),
            ),
        )
        assertEquals("java.util.UUID", opened.fixed("same", "a"), "a value of no type fixes nothing")
        assertEquals("java.util.UUID", opened.fixed("choice", "then"))
    }

    @Test
    fun `an empty list linked in fixes nothing`() {
        val opened = Opened(
            StoryGraph(
                nodes = listOf(
                    StoryNode.Pure("empty", "list", count = 1),
                    StoryNode.Pure("add", "list-add", mapOf("item" to "\"a\"")),
                ),
                links = listOf(StoryLink("empty", "list", "add", "list")),
            ),
        )
        assertNull(opened.fixed("add", "item"))
        assertEquals(emptyList(), opened.inField("add", "item") + opened.ofBlock("add"))
    }

    @Test
    fun `a map link fixes keys and values independently`() {
        val opened = Opened(StoryGraph(
            nodes = listOf(
                StoryNode.Expression("map", "emptyMap<String, Nothing>()"),
                StoryNode.Pure("put", "map-put", mapOf("key" to "1", "value" to "2")),
            ),
            links = listOf(StoryLink("map", StoryPins.VALUE, "put", "map")),
        ))
        assertEquals("kotlin.String", opened.fixed("put", "key"))
        assertNull(opened.fixed("put", "value"))
        assertTrue(opened.inField("put", "key").isNotEmpty())
        assertEquals(emptyList(), opened.inField("put", "value"))
    }

    @Test
    fun `a link fixes one map parameter and its output place fixes the other`() {
        val opened = Opened(StoryGraph(
            variables = listOf(StoryVariable("entries", "emptyMap()", "Map<String, Int>")),
            nodes = listOf(
                StoryNode.Expression("key", "\"key\""),
                StoryNode.Pure("put", "map-put", mapOf("map" to "emptyMap()", "value" to "\"bad\"")),
                StoryNode.Assign("set", "entries"),
            ),
            links = listOf(
                StoryLink("key", StoryPins.VALUE, "put", "key"),
                StoryLink("put", "map", "set", StoryPins.VALUE),
            ),
        ))
        assertEquals("kotlin.String", opened.fixed("put", "key"))
        assertEquals("kotlin.Int", opened.fixed("put", "value"))
        assertTrue(opened.inField("put", "value").isNotEmpty())
    }

    @Test
    fun `nullable map results take their type from the place after link unwrapping`() {
        val opened = Opened(StoryGraph(
            variables = listOf(StoryVariable("number", "0", "Int")),
            nodes = listOf(
                StoryNode.Pure("get", "map-get", mapOf("map" to "mapOf(\"x\" to 1)", "key" to "\"x\"")),
                StoryNode.Assign("set", "number"),
            ),
            links = listOf(StoryLink("get", "value", "set", StoryPins.VALUE)),
        ))
        assertEquals("kotlin.Int?", opened.fixed("get", "value"))
        assertEquals(emptyList(), opened.ofBlock("get") + opened.inField("get", "map"))
    }

    @Test
    fun `fields infer input pins including empty inputs without fixing later edits`() {
        fun graph(value: String) = StoryGraph(nodes = listOf(
            StoryNode.Pure("list", "list", mapOf("a" to value), count = 2),
        ))
        val int = Opened(graph("1"))
        assertEquals("kotlin.Int", int.input("list", "a"))
        assertEquals("kotlin.Int", int.input("list", "b"))
        assertEquals(StoryPinType.NUMBER, int.inputType("list", "a"))
        assertEquals("kotlin.collections.List<kotlin.Int>", int.output("list", "list"))
        assertNull(int.fixed("list", "a"))
        assertEquals(StoryFieldForm.Code, int.field("list", "a").form)
        int.update(graph("4f"))
        assertEquals("kotlin.Float", int.input("list", "a"))
        assertEquals("kotlin.Float", int.input("list", "b"))
        assertEquals(emptyList(), int.ofBlock("list"))
        int.update(graph(""))
        assertEquals("T", int.input("list", "a"))
        assertEquals(StoryPinType.ANY, int.inputType("list", "a"))
        assertEquals("kotlin.collections.List<T>", int.output("list", "list"))
    }

    @Test
    fun `different numeric fields keep K2 intersection types on every pin`() {
        val opened = Opened(StoryGraph(nodes = listOf(
            StoryNode.Pure("list", "list", mapOf("a" to "4f", "b" to "3"), count = 2),
        )))
        val element = opened.input("list", "a")!!
        assertTrue(element.contains("kotlin.Number"), element)
        assertTrue(element.contains("kotlin.Comparable"), element)
        assertEquals(element, opened.input("list", "b"))
        assertEquals(StoryPinType.NUMBER, opened.inputType("list", "a"))
        val output = opened.output("list", "list")!!
        assertTrue(output.contains("kotlin.Number"), output)
        assertTrue(output.contains("kotlin.Comparable"), output)
        assertEquals(emptyList(), opened.ofBlock("list") + opened.inField("list", "a") + opened.inField("list", "b"))
    }

    private companion object {
        const val FILE = "generic.stage.kts"
    }
}
