import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.ide.session.definition.LibrarySourceRepository
import ru.hollowhorizon.hollowengine.common.ide.session.definition.SourceDeclarationIdentity
import ru.hollowhorizon.hollowengine.common.ide.session.definition.SourceDeclarationIdentity.Kind
import ru.hollowhorizon.hollowengine.common.ide.session.definition.SourceDeclarationMatcher
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.DiagnosticFix
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import java.io.File
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdeCodeInsightTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = (System.getProperty("java.class.path")
                .split(File.pathSeparator) + System.getProperty("hollowengine.ide.testClasspath").orEmpty()
                .split(File.pathSeparator)).filter(String::isNotBlank).map(::File)
                .filter(File::exists) + directory.resolve("classes").toFile(),
            scriptTypes = listOf(
                ScriptClassProvider("kts", "kotlin.Any"),
                ScriptClassProvider(
                    "node.kts",
                    NodeScript::class.qualifiedName!!,
                    defaultImports = listOf("ru.hollowhorizon.hollowengine.common.scripting.nodes.*")
                ),
            ),
            mappings = Mappings.EMPTY
        )
    }
    private val analyzer get() = fixture.environment.analyzer
    private val javaAnalyzer get() = fixture.environment.javaAnalyzer
    private lateinit var directory: Path
    private var previousSourceManifest: String? = null
    private val sourceManifestProperty = "hollowengine.ide.sourceRoots"

    @BeforeAll
    fun start(@TempDir root: Path) {
        directory = root
        previousSourceManifest = System.getProperty(sourceManifestProperty)
        val emptyManifest = directory.resolve("empty-source-roots.txt").also { it.writeText("") }
        System.setProperty(sourceManifestProperty, emptyManifest.toString())
        val source = directory.resolve("NavigationFixture.java").also { it.writeText(JavaFixtureSource) }
        val classes = directory.resolve("classes").createDirectories()
        assertEquals(
            0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString())
        )
        fixture.start()
    }

    @AfterAll
    fun close() {
        try {
            fixture.close()
        } finally {
            if (previousSourceManifest == null) System.clearProperty(sourceManifestProperty)
            else System.setProperty(sourceManifestProperty, previousSourceManifest)
        }
    }

    @Test
    fun `Kotlin navigation chooses the overload matching the arguments`() {
        val text = "fun choose(value: String) = value\nfun choose(value: Int) = value\nchoose(42)"
        val target = assertNotNull(analyzer.definition("overloads.kts", text, text.lastIndexOf("choose") + 2))
        assertEquals(text.indexOf("choose(value: Int)"), target.offset)
    }

    @Test
    fun `completion inside identifiers uses only the text before the caret`() {
        val text = "fun someOtherFunction() = Unit\nsomeOldFunction()"
        val items = analyzer.collectCompletions("middle.kts", text, text.lastIndexOf("some") + 4)
        assertTrue(items.any { it.name == "someOtherFunction" }, items.toString())
        assertEquals("someOtherFunction", items.first { it.name == "someOtherFunction" }.insert)
    }

    @Test
    fun `expression snippets fit only where an expression of their type may go`() {
        val types = listOf("kotlin.String", "kotlin.Int")
        fun accepted(text: String) =
            analyzer.acceptedExpressionTypes("slots.kts", text.replace("|", ""), text.indexOf('|'), types)

        assertEquals(listOf("kotlin.Int"), accepted("fun takesInt(value: Int) = value\ntakesInt(|)"))
        assertEquals(listOf("kotlin.String"), accepted("val text: String = po|"))
        assertEquals(types, accepted("val number = 1\npo|"))
        assertEquals(types, accepted("fun run(block: () -> Unit) = block()\nrun { po| }"))
        assertNull(accepted("val text = \"abc\".le|"))
        assertNull(accepted("val po|"))
    }

    @Test
    fun `completion preserves existing lambda bodies and callable references`() {
        for (usage in listOf("someOldFunction { println(42) }", "::someOldFunction")) {
            val text = "fun someOtherFunction(block: () -> Unit) = block()\nval action = $usage"
            val items = analyzer.collectCompletions("call-shape.kts", text, text.lastIndexOf("someOld") + 4)
            assertEquals("someOtherFunction", items.first { it.name == "someOtherFunction" }.insert)
        }
    }

    @Test
    fun `intentions offer exact Java imports and keep file annotations and package`() {
        val text = "@file:Suppress(\"unused\")\n\npackage sample\n\nval uri: URI? = null"
        val actions = analyzer.intentionActions("imports.kts", text, text.indexOf("URI") + 1)
        val fix = assertNotNull(actions.firstOrNull { "java.net.URI" in it.titleArgs }, actions.toString())
        val edited = text.apply(fix)
        assertTrue(edited.indexOf("package sample") < edited.indexOf("import java.net.URI"), edited)
        assertFalse(analyzer.diagnostic("imports.kts", edited).any { it.severity.isError() }, edited)
    }

    @Test
    fun `intentions expose cleanup away from the imports and preserve initializer effects`() {
        val text = "import java.net.URI\n\nfun run() {\n    val unused = println(\"effect\")\n}\n"
        val actions = analyzer.intentionActions("unused.kts", text, text.indexOf("unused") + 2)
        val remove = assertNotNull(unusedVariableFix("unused.kts", text), actions.toString())
        assertTrue("println(\"effect\")" in text.apply(remove))
        assertFalse("val unused" in text.apply(remove))
        assertTrue(actions.any { it.title.endsWith("remove_unused_imports") })
    }

    @Test
    fun `removing an unused variable preserves its initializer expected type`() {
        val text = "fun run() { val unused: List<Int> = emptyList() }"
        val action = assertNotNull(
            unusedVariableFix("typed-unused.kts", text)
        )
        val edited = text.apply(action)
        assertFalse(analyzer.diagnostic("typed-unused.kts", edited).any { it.severity.isError() }, edited)
    }

    @Test
    fun `removing an unused variable discards literals and constant expressions`() {
        for (initializer in listOf(
            "\"unused\"",
            "42",
            "true",
            "null",
            "1 + 2",
            "insight.NavigationFixture.TEXT",
            "insight.NavigationFixture.OBJECT"
        )) {
            val text = "fun run() {\n    val unused = $initializer\n}"
            val action = assertNotNull(
                unusedVariableFix("constant-unused.kts", text), initializer
            )
            val edited = text.apply(action)
            assertEquals("fun run() {\n}", edited, initializer)
            assertFalse(analyzer.diagnostic("constant-unused.kts", edited).any { it.severity.isError() }, edited)
        }
    }

    @Test
    fun `removing an unused variable keeps calls mutable fields and interpolated effects`() {
        for (initializer in listOf(
            "println(42)",
            "insight.NavigationFixture.mutable",
            "insight.NavigationFixture(42).dynamic",
            "\"\${println(42)}\""
        )) {
            val text = "fun run() { val unused = $initializer }"
            val action = assertNotNull(
                unusedVariableFix("effect-unused.kts", text), initializer
            )
            assertEquals("fun run() { $initializer }", text.apply(action), initializer)
        }
    }

    @Test
    fun `node handler definitions resolve through their context and suspend JVM signatures`() {
        for (name in listOf("onStart", "onStop", "onSave", "onLoad", "onUpdate")) {
            val text = "$name { }"
            val target = assertNotNull(analyzer.definition("navigation.node.kts", text, 2), name)
            assertTrue(target.path.startsWith("decompiled/"), target.path)
            val declaration = target.text!!.substring(target.offset).substringBefore('{')
            if (name == "onUpdate") {
                assertTrue(declaration.startsWith("cfr_renamed_"), declaration)
                assertTrue(
                    "NodeScript" in declaration && "int" in declaration && "Function2" in declaration, declaration
                )
                assertFalse("\$default" in declaration, declaration)
            } else assertTrue(declaration.startsWith("$name("), declaration)
        }
    }

    @Test
    fun `node handler definitions use the development source manifest`() {
        val sourceRoot =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }.map { it.resolve("runtime/src/main/java") }
                .first { it.isDirectory() }
        val manifest = directory.resolve("source-roots.txt").also { it.writeText(sourceRoot.toString()) }
        val property = "hollowengine.ide.sourceRoots"
        val previous = System.getProperty(property)
        System.setProperty(property, manifest.toString())
        try {
            for (name in listOf("onStart", "onStop", "onSave", "onLoad", "onUpdate")) {
                val target = assertNotNull(analyzer.definition("development.node.kts", "$name { }", 2), name)
                assertTrue(target.path.endsWith("NodeHandlers.kt"), target.path)
                assertTrue(target.text!!.startsWith("$name(", target.offset), target.path)
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    @Test
    fun `source matching distinguishes boxed primitive overloads and qualified parameter types`() {
        val matcher = SourceDeclarationMatcher(analyzer.project)
        val kotlin = "fun select(x: Int) = x\nfun select(x: Int?) = x"
        val target = SourceDeclarationIdentity("select", emptyList(), Kind.FUNCTION, listOf("java.lang.Integer"))
        assertEquals(kotlin.indexOf("select(x: Int?)"), matcher.offset("Example.kt", kotlin, target))
        val java = "class Example { void select(java.sql.Date value) {} }"
        val wrongPackage =
            SourceDeclarationIdentity("select", listOf("Example"), Kind.FUNCTION, listOf("java.util.Date"))
        assertNull(matcher.offset("Example.java", java, wrongPackage))
        val aliased = "import java.util.Date as Timestamp\nfun select(x: Timestamp) = x"
        assertEquals(
            aliased.indexOf("select"), matcher.offset(
                "Example.kt", aliased, target.copy(parameters = listOf("java.util.Date"))
            )
        )
    }

    @Test
    fun `source matching preserves offsets across line separators and context parameters`() {
        val matcher = SourceDeclarationMatcher(analyzer.project)
        for (separator in listOf("\n", "\r\n", "\r")) {
            val kotlin = listOf(
                "package sample",
                "context(script: NodeScript)",
                "fun start(block: suspend CoroutineScope.() -> Unit) {}"
            ).joinToString(separator)
            val target = SourceDeclarationIdentity(
                "start",
                emptyList(),
                Kind.FUNCTION,
                listOf("sample.NodeScript", "kotlin.jvm.functions.Function2"),
                "sample"
            )
            assertEquals(kotlin.indexOf("start("), matcher.offset("Handlers.kt", kotlin, target), separator)
            val java =
                listOf("package sample;", "class Example {", "void start(int value) {}", "}").joinToString(separator)
            assertEquals(
                java.indexOf("start("), matcher.offset(
                    "Example.java", java, target.copy(owners = listOf("Example"), parameters = listOf("int"))
                ), separator
            )
        }
    }

    @Test
    fun `Java navigation resolves local overloads by parameter types`() {
        val text = """
            class Example {
                void run() { select(42); }
                void select(String value) {}
                void select(int value) {}
            }
        """.trimIndent()
        val target = assertNotNull(javaAnalyzer.definition("Example.java", text, text.indexOf("select") + 2))
        assertEquals(text.indexOf("select(int"), target.offset)
        assertNotNull(javaAnalyzer.hover("Example.java", text, text.indexOf("select") + 2))
        assertEquals(2, javaAnalyzer.occurrences("Example.java", text, text.indexOf("select") + 2).size)
    }

    @Test
    fun `Java syntax diagnostics report invalid declarations`() {
        assertTrue(javaAnalyzer.diagnostic("Broken.java", "class Broken { void run( }").isNotEmpty())
    }

    @Test
    fun `Java highlighting resolves chained calls and generic expression receivers`() {
        val text = """
            class Model {
                int size(java.util.List<String> values) {
                    return values.get(0).substring(1).length();
                }
            }
        """.trimIndent()
        val spans = javaAnalyzer.highlight("decompiled/sample/Model.java", text, 0).flatMap { it.spans }
        assertTrue(
            spans.any { (text, style) -> text == "substring" && style.color == TokenType.METHOD }, spans.toString()
        )
        assertTrue(spans.any { (text, style) -> text == "length" && style.color == TokenType.METHOD }, spans.toString())
    }

    @Test
    fun `the decompiled engine Model supports semantic Java highlighting`() {
        val text = "val model: ru.hollowhorizon.hollowengine.common.attachments.components.Model? = null"
        val definition = assertNotNull(analyzer.definition("decompiled-model.kts", text, text.indexOf("Model") + 2))
        assertTrue(definition.path.startsWith("decompiled/"), definition.path)
        val spans = javaAnalyzer.highlight(definition.path, definition.text!!, definition.offset).flatMap { it.spans }
        assertTrue(spans.any { (text, style) -> text == "Model" && style.color == TokenType.CLASS }, spans.toString())
        assertTrue(spans.any { (text, style) -> text == "getRig" && style.color == TokenType.METHOD }, spans.toString())
        assertEquals(definition.text, javaAnalyzer.documentText(definition.path), "A restored tab is decompiled again")
    }

    @Test
    fun `development navigation finds actual engine and Minecraft sources without JVM options`() {
        val previous = System.getProperty(sourceManifestProperty)
        System.clearProperty(sourceManifestProperty)
        try {
            val engine = assertNotNull(analyzer.definition("automatic.node.kts", "onStart { }", 2))
            assertTrue(engine.path.endsWith("NodeHandlers.kt"), engine.path)
            val modelText = "val model: ru.hollowhorizon.hollowengine.common.attachments.components.Model? = null"
            val model = assertNotNull(analyzer.definition("model.kts", modelText, modelText.indexOf("Model") + 2))
            assertTrue(model.path.endsWith("Model.kt"), model.path)
            val gameText = "val point = net.minecraft.world.phys.Vec3.ZERO"
            val game = assertNotNull(analyzer.definition("minecraft.kts", gameText, gameText.indexOf("Vec3") + 2))
            assertTrue(
                game.path.startsWith("library-sources/") && game.path.endsWith("!/net/minecraft/world/phys/Vec3.java"),
                game.path
            )
            assertTrue(game.text!!.startsWith("Vec3", game.offset), game.path)
            for ((arguments, signature) in listOf(
                "1.0, 2.0, 3.0" to "add(double", "net.minecraft.world.phys.Vec3.ZERO" to "add(Vec3"
            )) {
                val call = "net.minecraft.world.phys.Vec3.ZERO.add($arguments)"
                val method =
                    assertNotNull(analyzer.definition("minecraft-call.kts", call, call.indexOf("add") + 1), call)
                assertTrue(method.path.startsWith("library-sources/"), method.path)
                assertTrue(
                    method.text!!.startsWith(signature, method.offset), method.text!!.substring(method.offset).take(150)
                )
            }
        } finally {
            if (previous == null) System.clearProperty(sourceManifestProperty) else System.setProperty(
                sourceManifestProperty, previous
            )
        }
    }

    @Test
    fun `library navigation prefers attached source archives and selects the called overload`() {
        val archive = DirectoryManager.LIBRARY_SOURCES.resolve("navigation-test-sources.jar")
        JarOutputStream(archive.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("insight/NavigationFixture.java"))
            jar.write(JavaFixtureSource.toByteArray())
            jar.closeEntry()
        }
        try {
            val text = "insight.NavigationFixture.select(42)"
            val target = assertNotNull(analyzer.definition("library.kts", text, text.indexOf("select") + 1))
            assertTrue(target.path.contains("navigation-test-sources.jar"), target.path)
            assertEquals(JavaFixtureSource.indexOf("select(int"), target.offset)
            val java = "class Use { void run() { insight.NavigationFixture.select(\"text\"); } }"
            val javaTarget = assertNotNull(javaAnalyzer.definition("Use.java", java, java.indexOf("select") + 1))
            assertEquals(JavaFixtureSource.indexOf("select(String"), javaTarget.offset)
        } finally {
            archive.toFile().delete()
        }
    }

    @Test
    fun `decompiler navigation selects a signature rather than the first name occurrence`() {
        val text = "insight.NavigationFixture.select(42)"
        val target = assertNotNull(analyzer.definition("decompiled.kts", text, text.indexOf("select") + 1))
        assertTrue(target.path.startsWith("decompiled/"), target.path)
        assertTrue(target.text!!.substring(target.offset).startsWith("select(int"), target.text)
    }

    @Test
    fun `Kotlin library navigation handles file facades suspend functions and JVM names`() {
        for ((text, name, expected) in listOf(
            Triple("navigationKotlinOverload(42)", "navigationKotlinOverload", "navigationKotlinOverload(int"),
            Triple(
                "suspend fun call() = navigationKotlinSuspending(42)",
                "navigationKotlinSuspending",
                "navigationKotlinSuspending(int"
            ),
            Triple("navigationKotlinRenamed(42)", "navigationKotlinRenamed", "renamedNavigation(int"),
            Triple("val result = navigationKotlinProperty", "navigationKotlinProperty", "getNavigationKotlinProperty("),
        )) {
            val target = assertNotNull(analyzer.definition("kotlin-library.kts", text, text.indexOf(name) + 2), name)
            assertTrue(
                target.text!!.startsWith(expected, target.offset),
                "${target.path}: ${target.text?.substring(target.offset)}"
            )
        }
    }

    @Test
    fun `Kotlin JVM navigation distinguishes value class overloads with the same underlying type`() {
        for ((type, expected) in listOf("NavigationTicks" to "ticks", "NavigationDistance" to "distance")) {
            for (call in listOf("navigationValueOverload($type(42))", "NavigationValueOwner().choose($type(42))")) {
                val name = if (call.startsWith("NavigationValueOwner")) "choose" else "navigationValueOverload"
                val target = assertNotNull(analyzer.definition("value-class.kts", call, call.indexOf(name) + 2), call)
                assertTrue(target.path.startsWith("decompiled/"), target.path)
                val declaration = target.text!!.substring(target.offset).substringBefore('}')
                assertTrue("return \"$expected\"" in declaration, declaration)
            }
        }
    }

    @Test
    fun `Java resolves generic receivers inherited methods and constructors`() {
        val text = """
            class Use {
                void run() {
                    var list = new java.util.ArrayList<String>();
                    list.add("value");
                    new insight.NavigationFixture(42);
                }
            }
        """.trimIndent()
        val method = assertNotNull(javaAnalyzer.definition("Use.java", text, text.indexOf("add(") + 1))
        assertTrue(method.text!!.startsWith("add(", method.offset), method.path)
        val constructor =
            assertNotNull(javaAnalyzer.definition("Use.java", text, text.lastIndexOf("NavigationFixture") + 2))
        assertTrue(constructor.text!!.startsWith("NavigationFixture(int", constructor.offset), constructor.text)
    }

    @Test
    fun `source matcher ignores comments calls and overloads of the same arity`() {
        val text = """
            package sample;
            class Example {
                // select is intentionally used before its declaration
                void run() { select(42); }
                void select(String value) {}
                void select(int value) {}
                class Nested { void select(int value) {} }
            }
        """.trimIndent()
        val matcher = SourceDeclarationMatcher(analyzer.project)
        val target = SourceDeclarationIdentity("select", listOf("Example"), Kind.FUNCTION, listOf("int"))
        assertEquals(text.indexOf("select(int"), matcher.offset("Example.java", text, target))
        val nested = target.copy(owners = listOf("Example", "Nested"))
        assertEquals(text.lastIndexOf("select(int"), matcher.offset("Example.java", text, nested))
        assertNull(matcher.offset("Example.java", text, target.copy(parameters = listOf("long"))))
    }

    @Test
    fun `source matcher handles constructors arrays and Kotlin overloads`() {
        val matcher = SourceDeclarationMatcher(analyzer.project)
        val java = "class Example { Example(String x) {} Example(int... x) {} }"
        val constructor = SourceDeclarationIdentity("Example", listOf("Example"), Kind.CONSTRUCTOR, listOf("int[]"))
        assertEquals(java.indexOf("Example(int"), matcher.offset("Example.java", java, constructor))
        val implicit = "class Example {}"
        assertEquals(
            implicit.indexOf("Example"),
            matcher.offset("Example.java", implicit, constructor.copy(parameters = emptyList()))
        )
        val record = "record Example(int value) {}"
        assertEquals(
            record.indexOf("Example"),
            matcher.offset("Example.java", record, constructor.copy(parameters = listOf("int")))
        )
        val kotlin = "fun select(x: String) = x\nfun select(x: Int) = x"
        val target = SourceDeclarationIdentity("select", emptyList(), Kind.FUNCTION, listOf("int"))
        assertEquals(kotlin.indexOf("select(x: Int)"), matcher.offset("Example.kt", kotlin, target))
    }

    @Test
    fun `source archives can be attached and replaced without restarting the analyzer`() {
        val jar = directory.resolve("mod-sources.jar")
        val target = SourceDeclarationIdentity("select", listOf("Example"), Kind.FUNCTION, listOf("int"))
        val repository =
            LibrarySourceRepository(SourceDeclarationMatcher(analyzer.project), emptyList()) { listOf(jar) }

        fun write(text: String) {
            JarOutputStream(jar.outputStream()).use { archive ->
                archive.putNextEntry(JarEntry("sample/Example.java"))
                archive.write(text.toByteArray())
                archive.closeEntry()
            }
        }
        assertNull(repository.find(target, null, "sample"))
        val original = "package sample; class Example { void select(int value) {} }"
        write(original)
        val found = assertNotNull(repository.find(target, null, "sample"))
        assertEquals(original.indexOf("select"), found.offset)
        assertEquals(original, found.text)
        val updated = "// updated\n$original"
        write(updated)
        assertEquals(updated.indexOf("select"), repository.find(target, null, "sample")?.offset)
    }

    @Test
    fun `a broken archive does not prevent navigation to another source archive`() {
        val broken = directory.resolve("broken-sources.jar").also { it.writeText("not a zip") }
        val archive = directory.resolve("valid-sources.jar")
        JarOutputStream(archive.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("insight/NavigationFixture.java"))
            jar.write(JavaFixtureSource.toByteArray())
            jar.closeEntry()
        }
        val target = SourceDeclarationIdentity("select", listOf("NavigationFixture"), Kind.FUNCTION, listOf("int"))
        val repository =
            LibrarySourceRepository(SourceDeclarationMatcher(analyzer.project), emptyList()) { listOf(broken, archive) }
        val location = assertNotNull(repository.find(target, null, "insight"))
        assertTrue(location.path.contains("valid-sources.jar"), location.path)
        assertEquals(JavaFixtureSource.indexOf("select(int"), location.offset)
    }

    // The editor offers diagnostic fixes from the diagnostics it already holds.
    private fun unusedVariableFix(name: String, text: String) =
        analyzer.diagnostic(name, text).flatMap { it.fixes }.firstOrNull { it.title.endsWith("remove_unused_variable") }

    private fun String.apply(fix: DiagnosticFix): String = fix.edits.sortedByDescending { it.start }
        .fold(this) { text, edit -> text.replaceRange(edit.start, edit.end, edit.replacement) }
}

private val JavaFixtureSource = """
    package insight;
    public class NavigationFixture {
        public static final String TEXT = "constant";
        public static final Object OBJECT = new Object();
        public static int mutable = 1;
        public final String dynamic = System.getProperty("user.name");
        public NavigationFixture(String value) {}
        public NavigationFixture(int value) {}
        public static String select(String value) { return value; }
        public static int select(int value) { return value; }
    }
""".trimIndent()

fun navigationKotlinOverload(value: String): String = value
fun navigationKotlinOverload(value: Int): Int = value
suspend fun navigationKotlinSuspending(value: Int): Int = value

@JvmName("renamedNavigation")
fun navigationKotlinRenamed(value: Int): Int = value
val navigationKotlinProperty: Int get() = 42

@JvmInline
value class NavigationTicks(val count: Int)

@JvmInline
value class NavigationDistance(val count: Int)

fun navigationValueOverload(value: NavigationTicks): String = "ticks"
fun navigationValueOverload(value: NavigationDistance): String = "distance"
class NavigationValueOwner {
    fun choose(value: NavigationTicks): String = "ticks"
    fun choose(value: NavigationDistance): String = "distance"
}
