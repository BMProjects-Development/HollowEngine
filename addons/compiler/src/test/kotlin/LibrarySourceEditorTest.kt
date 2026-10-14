import com.intellij.openapi.util.registry.Registry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import java.io.File
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibrarySourceEditorTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = (System.getProperty("java.class.path")
                .split(File.pathSeparator) + System.getProperty("hollowengine.ide.testClasspath").orEmpty()
                .split(File.pathSeparator)).filter(String::isNotBlank).map(::File).filter(File::exists),
            scriptTypes = listOf(
                ScriptClassProvider("kts", "kotlin.Any"),
                ScriptClassProvider(
                    "node.kts",
                    NodeScript::class.qualifiedName!!,
                    defaultImports = listOf("ru.hollowhorizon.hollowengine.common.scripting.nodes.*")
                ),
            ),
            mappings = Mappings.EMPTY,
        )
    }
    private val analyzer get() = fixture.environment.analyzer
    private var previousManifest: String? = null

    @BeforeAll
    fun start() {
        previousManifest = System.getProperty("hollowengine.ide.sourceRoots")
        System.clearProperty("hollowengine.ide.sourceRoots")
        fixture.start()
    }

    @AfterAll
    fun close() {
        try {
            fixture.close()
        } finally {
            if (previousManifest == null) System.clearProperty("hollowengine.ide.sourceRoots")
            else System.setProperty("hollowengine.ide.sourceRoots", previousManifest)
        }
    }

    @Test
    fun `ordinary Kotlin source files resolve their own overloads without script definitions`() {
        val text = "fun choose(value: String) = value\nfun choose(value: Int) = value\nfun run() = choose(42)"
        val path = "library-sources/example/Declarations.kt"
        assertFalse(analyzer.diagnostic(path, text).any { it.severity.isError() })
        val target = assertNotNull(analyzer.definition(path, text, text.lastIndexOf("choose") + 2))
        assertEquals(path, target.path)
        assertEquals(text.indexOf("choose(value: Int)"), target.offset)
        assertNotNull(analyzer.hover(path, text, text.lastIndexOf("choose") + 2))
    }

    @Test
    fun `an opened NodeHandlers source supports diagnostics highlighting hover and further navigation`() {
        val target = assertNotNull(analyzer.definition("open.node.kts", "onStart { }", 2))
        assertTrue(target.path.endsWith("NodeHandlers.kt"), target.path)
        val text = target.text!!
        assertFalse('\r' in text)
        assertTrue(text.startsWith("onStart(", target.offset))
        val diagnostics = analyzer.diagnostic(target.path, text)
        assertFalse(diagnostics.any { it.severity.isError() }, diagnostics.toString())
        val spans = analyzer.highlight(target.path, text, text.indexOf("onStart(")).flatMap { it.spans }
        assertTrue(spans.any { (part, style) -> part == "onStart" && style.color == TokenType.TOP_LEVEL })
        val node = text.indexOf("NodeScript")
        assertNotNull(analyzer.hover(target.path, text, node + 2))
        val next = assertNotNull(analyzer.definition(target.path, text, node + 2))
        assertTrue(next.path.endsWith("NodeScript.kt"), next.path)
        val structure = analyzer.structure(target.path, text)
        assertTrue(structure.folds.isNotEmpty())
        assertTrue(structure.markers.any { it.kind == GutterMarkerKind.COROUTINE })
        assertTrue(structure.runTargets.isEmpty())
    }

    @Test
    fun `hovering Java package references uses their qualified names`() {
        val text = "package net.minecraft.client; class Example {}"
        val hover = assertNotNull(
            analyzer.javaAnalyzer.hover(
                "library-sources/Example.java",
                text,
                text.indexOf("minecraft") + 2
            )
        )
        assertEquals("net.minecraft", hover.signature)
    }

    @Test
    fun `Java calls navigate to the actual Minecraft method in attached sources`() {
        val text = "class Example { void run() { net.minecraft.client.Minecraft.getInstance().getWindow(); } }"
        val target =
            assertNotNull(analyzer.javaAnalyzer.definition("Example.java", text, text.indexOf("getWindow") + 2))
        assertTrue(target.path.endsWith("!/net/minecraft/client/Minecraft.java"), target.path)
        assertTrue(target.text!!.startsWith("getWindow(", target.offset))
    }

    @Test
    fun `Kotlin source modules are replaced and evicted with their declaration providers`() {
        val path = "library-sources/Lifecycle.kt"
        val original = "fun oldName() = 1\nfun call() = oldName()"
        assertNotNull(analyzer.definition(path, original, original.lastIndexOf("oldName") + 2))
        val updated = "fun newName() = 2\nfun call() = newName()"
        assertNotNull(analyzer.definition(path, updated, updated.lastIndexOf("newName") + 2))
        repeat(6) { index ->
            assertFalse(
                analyzer.diagnostic("Source$index.kt", "fun value$index() = $index").any { it.severity.isError() })
        }
        assertFalse(analyzer.projectStructureProvider.allSourceFiles.any { it.name == path })
        assertNotNull(analyzer.definition(path, updated, updated.lastIndexOf("newName") + 2))
    }

    @Test
    fun `Java registry defaults include capture conversion and inference settings`() {
        assertFalse(Registry.`is`("unsound.capture.conversion.java.spec.change"))
        assertTrue(Registry.`is`("javac.fresh.variables.for.captured.wildcards.only"))
    }

    @Test
    fun `the opened Minecraft source supports its editor analysis operations`() {
        val call = "net.minecraft.client.Minecraft.getInstance()"
        val target = assertNotNull(analyzer.definition("minecraft-editor.kts", call, call.indexOf("getInstance") + 2))
        assertTrue(target.path.endsWith("!/net/minecraft/client/Minecraft.java"), target.path)
        val text = target.text!!
        assertTrue(text.startsWith("getInstance(", target.offset))
        assertFalse(analyzer.javaAnalyzer.diagnostic(target.path, text).any { it.severity.isError() })
        val hover =
            assertNotNull(analyzer.javaAnalyzer.hover(target.path, text, text.indexOf("package net.minecraft") + 14))
        assertTrue("net.minecraft" in hover.signature, hover.signature)
        val spans = analyzer.javaAnalyzer.highlight(target.path, text, target.offset).flatMap { it.spans }
        assertTrue(spans.any { (part, style) -> part == "getInstance" && style.color == TokenType.METHOD })
        val structure = analyzer.javaAnalyzer.structure(target.path, text)
        assertTrue(structure.folds.isNotEmpty())
        assertTrue(structure.markers.any { it.kind == GutterMarkerKind.OVERRIDE })
    }
}
