import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptRunKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import java.io.File
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorStructureTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(javaHome = File(System.getProperty("java.home")),
            classpath = (System.getProperty("java.class.path").split(File.pathSeparator) +
                System.getProperty("hollowengine.ide.testClasspath").orEmpty().split(File.pathSeparator))
                .filter(String::isNotBlank).map(::File).filter(File::exists),
            scriptTypes = listOf(ScriptClassProvider("kts", "kotlin.Any"),
                ScriptClassProvider("ui.kts", "ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript"),
                ScriptClassProvider("node.kts", NodeScript::class.qualifiedName!!,
                    defaultImports = listOf("ru.hollowhorizon.hollowengine.common.scripting.annotations.*"))),
            mappings = Mappings.EMPTY)
    }
    private val analyzer get() = fixture.environment.analyzer
    @BeforeAll fun start() = fixture.start()
    @AfterAll fun close() = fixture.close()

    @Test
    fun `scripts fold only real braces and a single import group`() {
        assertTrue(analyzer.structure("Hello.kts", "println(\"Hello world\")").folds.isEmpty())
        val imports = "import java.io.File\nimport java.net.URI\nimport java.util.UUID\nprintln(42)"
        val group = analyzer.structure("Imports.kts", imports).folds.single()
        assertEquals(0, group.start)
        assertEquals("import ".length, group.prefixLength)
        assertTrue(group.collapsedByDefault)
        assertEquals(imports.indexOf("\nprintln"), group.end)
        val lambda = "onStart {\n println(42)\n}\n"
        val fold = analyzer.structure("Lambda.node.kts", lambda).folds.single()
        assertEquals(lambda.indexOf('{'), fold.start)
        assertEquals(lambda.lastIndexOf('}') + 1, fold.end)
        assertEquals("{ … }", fold.placeholder)
        for ((name, source) in listOf("One.kts" to "import java.io.File", "One.java" to "import java.io.File;\nclass One {}")) {
            val single = if (name.endsWith("java")) analyzer.javaAnalyzer.structure(name, source).folds.single()
                else analyzer.structure(name, source).folds.single()
            assertTrue(single.collapsedByDefault)
            assertEquals("import ".length, single.prefixLength)
        }
    }

    @Test
    fun `folding includes nested blocks imports multiline strings and documentation`() {
        val text = "import java.io.File\nimport java.net.URI\n/** Doc\n * text\n */\nclass Example {\n fun call() {\n  println(42)\n }\n}\n" +
            "val text = \"\"\"first\nsecond\"\"\""
        val folds = analyzer.structure("Folds.kt", text).folds
        for (start in listOf("import java.io.File", "/**", "{\n fun", "{\n  println", "\"\"\"first")) {
            assertTrue(folds.any { it.start == text.indexOf(start) }, "$start: $folds")
        }
        assertTrue(folds.all { it.end <= text.length && '\n' in text.substring(it.start, it.end) })
    }

    @Test
    fun `override navigation selects the real overload and property getter`() {
        val text = "open class Base {\n open fun call(value: String) {}\n open fun call(value: Int) {}\n" +
            " open val value: Int\n  get() = 1\n}\nclass Child: Base() {\n override fun call(value: Int) {}\n" +
            " override val value: Int\n  get() = 2\n}"
        val markers = analyzer.structure("Overrides.kt", text).markers.filter { it.kind == GutterMarkerKind.OVERRIDE }
        assertEquals(3, markers.size)
        val function = markers.single { it.offset == text.lastIndexOf("call(value") }
        assertEquals(text.indexOf("call(value: Int)"), function.targets.single().location.offset)
        val getter = markers.single { it.offset == text.lastIndexOf("get()") }
        assertEquals(text.indexOf("get()"), getter.targets.single().location.offset)
    }

    @Test
    fun `all direct overridden declarations are offered for multiple interfaces`() {
        val text = "interface A { fun call() }\ninterface B { fun call() }\nclass Child: A, B { override fun call() {} }"
        val marker = analyzer.structure("Multiple.kt", text).markers.single { it.kind == GutterMarkerKind.OVERRIDE }
        assertEquals(setOf(text.indexOf("call()"), text.indexOf("call()", text.indexOf("interface B"))),
            marker.targets.map { it.location.offset }.toSet())
    }

    @Test
    fun `constructor overrides and destructuring declarations do not lose gutter analysis`() {
        val text = "interface Base { val value: Int }\nclass Child(override val value: Int): Base\n" +
            "fun destructure() { val (a, b) = 1 to 2 }"
        val marker = analyzer.structure("Constructor.kt", text).markers.single { it.kind == GutterMarkerKind.OVERRIDE }
        assertEquals(text.indexOf("value:"), marker.targets.single().location.offset)
    }

    @Test
    fun `only resolved suspend calls receive coroutine indicators`() {
        val text = "suspend fun suspendCall() {}\nfun accept(block: suspend () -> Unit) {}\n" +
            "fun call() { accept {\n suspendCall()\n} }\nsuspend fun invoke(block: suspend () -> Unit) {\n block()\n}\n" +
            "suspend infix fun Int.await(value: Int) {}\nsuspend fun infixCall() {\n 1 await 2\n}"
        val markers = analyzer.structure("Coroutines.kt", text).markers.filter { it.kind == GutterMarkerKind.COROUTINE }
        assertEquals(setOf(text.lastIndexOf("suspendCall()"), text.lastIndexOf("block()"), text.lastIndexOf("await 2")),
            markers.map { it.offset }.toSet())
        val node = "import ru.hollowhorizon.hollowengine.common.scripting.nodes.onStart\n" +
            "onStart {\n println(42)\n}\nonStart {\n kotlinx.coroutines.delay(1)\n}"
        val definition = assertNotNull(analyzer.definition("Suspension.node.kts", node, node.indexOf("onStart {") + 2))
        assertTrue(definition.path.endsWith("NodeHandlers.kt"), definition.path)
        val nodeMarkers = analyzer.structure("Suspension.node.kts", node).markers.filter { it.kind == GutterMarkerKind.COROUTINE }
        assertEquals(listOf(node.indexOf("delay(1)")), nodeMarkers.map { it.offset })
    }

    @Test
    fun `event annotations are resolved semantically including aliases`() {
        val text = "import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent as EventHandler\n" +
            "@EventHandler fun handle(event: ru.hollowhorizon.hollowengine.common.events.Event) {}\n" +
            "annotation class SubscribeEvent\n@SubscribeEvent fun other() {}"
        val markers = analyzer.structure("Events.kt", text).markers.filter { it.kind == GutterMarkerKind.EVENT }
        assertEquals(listOf(text.indexOf("handle(")), markers.map { it.offset })
    }

    @Test
    fun `run targets include only supported scripts and valid node states with constant names`() {
        assertEquals(ScriptRunKind.PLAIN, analyzer.structure("example.kts", "println(42)").runTargets.single().kind)
        assertTrue(analyzer.structure("example.kt", "fun call() {}").runTargets.isEmpty())
        assertTrue(analyzer.structure("example.ui.kts", "").runTargets.isEmpty())
        val text = "import ru.hollowhorizon.hollowengine.common.scripting.annotations.State as Entry\n" +
            "object Names { const val ENTRY = \"Begin\" }\n@Entry(Names.ENTRY) suspend fun first() {}\n" +
            "@Entry suspend fun second() {}\n@Entry fun invalid() {}"
        val targets = analyzer.structure("states.node.kts", text).runTargets
        assertEquals(listOf(null, "Begin", "second"), targets.map { it.state })
        assertEquals(0, targets.first().offset)
    }

    @Test
    fun `attached nodes have no server run targets`() {
        val text = "@file:Attached(java.net.URI::class)\n" +
            "import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach as Attached\n" +
            "@State suspend fun first() {}"
        assertTrue(analyzer.structure("attached.node.kts", text).runTargets.isEmpty())
    }

    @Test
    fun `a restored engine source retains library visibility without a preceding definition request`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("settings.gradle.kts").isRegularFile() }
        val file = root.resolve("runtime/src/main/java/ru/hollowhorizon/hollowengine/common/scripting/nodes/NodeHandlers.kt")
        val diagnostics = analyzer.diagnostic(file.toString(), file.readText())
        assertFalse(diagnostics.any { it.severity.isError() }, diagnostics.toString())
    }

    @Test
    fun `Java folding and override navigation use PSI declarations`() {
        val text = "class Base { int getValue() { return 1; } void call(String value) {} void call(int value) {} }\n" +
            "class Child extends Base {\n @Override int getValue() {\n return 2;\n }\n @Override void call(int value) {}\n}"
        val structure = analyzer.javaAnalyzer.structure("Overrides.java", text)
        assertTrue(structure.folds.size >= 2)
        val methods = structure.markers.filter { it.kind == GutterMarkerKind.OVERRIDE }
        assertEquals(2, methods.size)
        assertEquals(setOf(text.indexOf("getValue()"), text.indexOf("call(int value)")),
            methods.map { it.targets.single().location.offset }.toSet())
    }

    @Test
    fun `all multiline comment and KDoc tokens receive comment colors including TODO`() {
        val text = "/* First\n nested /* inner */ TODO finish\n last */\n/** Summary\n * @param value link [String]\n */\nfun call(value: String) {}"
        val lines = analyzer.highlight("Comments.kt", text, -1)
        val spans = lines.take(6).flatMap { it.spans }.filter { it.first.isNotBlank() }
        assertTrue(spans.all { it.second.color in setOf(TokenType.COMMENT, TokenType.TODO_COMMENT) }, spans.toString())
        assertTrue(spans.any { "TODO" in it.first && it.second.color == TokenType.TODO_COMMENT })
        assertEquals(text.lines().take(6), lines.take(6).map { line -> line.spans.joinToString("") { it.first } })
        assertFalse(lines.last().spans.all { it.second.color == TokenType.COMMENT })
    }
}
