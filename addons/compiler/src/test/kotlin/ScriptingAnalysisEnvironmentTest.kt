import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class AnalysisScript

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptingAnalysisEnvironmentTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = testClasspath(),
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = ".analysis.kts",
                    baseClass = AnalysisScript::class.qualifiedName!!,
                    defaultImports = listOf(File::class.qualifiedName!!, "java.nio.file.*"),
                )
            ),
            mappings = Mappings.EMPTY,
        )
    }

    @BeforeAll
    fun startEnvironment() = fixture.start()

    @AfterEach
    fun resetEnvironment() = fixture.reset()

    @AfterAll
    fun closeEnvironment() = fixture.close()

    @Test
    fun `class literal attachment resolves default imports explicit imports and aliases for analysis`() {
        withEnvironment { environment ->
            val scripts = listOf(
                "default-class.analysis.kts" to """
                    @file:Attach(File::class)

                    val attachedName = name
                """.trimIndent(),
                "default-imported.analysis.kts" to """
                    @file:Attach(Path::class)

                    val attachedName = fileName
                """.trimIndent(),
                "imported.analysis.kts" to """
                    @file:Attach(URI::class)

                    import java.net.URI

                    val attachedScheme = scheme
                """.trimIndent(),
                "aliased.analysis.kts" to """
                    @file:Attach(AttachedUri::class)

                    import java.net.URI as AttachedUri

                    val attachedScheme = scheme
                """.trimIndent(),
            )

            scripts.forEach { (name, source) ->
                val diagnostics = environment.analyzer.diagnostic(name, source)

                assertFalse(diagnostics.any { it.severity.isError() }, "$name: $diagnostics")
            }
        }
    }

    @Test
    fun `analysis environment can warm up in background`() {
        withEnvironment { environment ->
            runBlocking {
                withTimeout(60_000) {
                    environment.warmUpAnalysis(this).join()
                }
            }

            assertSame(environment.analyzer, environment.analyzer)
        }
    }

    @Test
    fun `diagnostic reports type errors through analysis api`() {
        withEnvironment { environment ->
            val diagnostics = environment.analyzer.diagnostic(
                "broken.analysis.kts",
                """val number: Int = "not a number"""",
            )

            assertTrue(diagnostics.any { it.severity.isError() }, diagnostics.toString())
        }
    }

    @Test
    fun `diagnostic rejects empty when subject parentheses`() {
        withEnvironment { environment ->
            val invalid = "val value = when() { else -> 1 }"
            val valid = "val value = when { else -> 1 }"

            val invalidDiagnostics = environment.analyzer.diagnostic("invalid-when.analysis.kts", invalid)
            val validDiagnostics = environment.analyzer.diagnostic("valid-when.analysis.kts", valid)

            assertTrue(invalidDiagnostics.any { it.severity.isError() }, invalidDiagnostics.toString())
            assertFalse(validDiagnostics.any { it.severity.isError() }, validDiagnostics.toString())
        }
    }

    @Test
    fun `highlight resolves basic kotlin tokens through analysis api`() {
        withEnvironment { environment ->
            val lines = environment.analyzer.highlight(
                "highlight.analysis.kts",
                """
                    val answer = 42
                    answer
                """.trimIndent(),
                4,
            )

            val spans = lines.flatMap { line -> line.spans }

            assertTrue(spans.any { (text, style) -> text == "val" && style.color == TokenType.KEYWORD }, spans.toString())
            assertTrue(spans.any { (text, style) -> text == "42" && style.color == TokenType.NUMERIC_LITERAL }, spans.toString())
        }
    }

    @Test
    fun `highlight keeps inlay hints after leading newline`() {
        withEnvironment { environment ->
            val text = "\nval answer = 42"

            val lines = environment.analyzer.highlight("leading-newline.analysis.kts", text, text.length)

            val secondLineHints = lines.getOrNull(1)?.hints.orEmpty()
            assertTrue(secondLineHints.any { it.index == "val answer".length }, secondLineHints.toString())
        }
    }

    @Test
    fun `highlight selects the matching brace pair under caret`() {
        withEnvironment { environment ->
            val text = "fun run() { repeat(3) {} }"
            val innerOpen = text.indexOf("{}")
            val lines = environment.analyzer.highlight(
                "brace-pair.analysis.kts",
                text,
                innerOpen + 1,
            )

            val spans = lines.flatMap { line -> line.spans }
            val highlightedBraces = spans.count { (segment, style) ->
                (segment == "{" || segment == "}") && style.highlight
            }
            assertEquals(2, highlightedBraces, spans.toString())
        }
    }

    @Test
    fun `highlight without caret does not select the first brace pair`() {
        withEnvironment { environment ->
            val text = "fun run() { repeat(3) {} }"
            val full = environment.analyzer.highlight(
                "no-caret-full.analysis.kts",
                text,
                -1,
            )

            assertFalse(full.flatMap { it.spans }.any { (_, style) -> style.highlight }, full.toString())
        }
    }

    @Test
    fun `occurrences returns all usages of the variable under caret`() {
        withEnvironment { environment ->
            val text = "val answer = 42\nval copy = answer + answer"
            val ranges = environment.analyzer.occurrences(
                "occurrences.analysis.kts",
                text,
                text.indexOf("answer") + 2,
            )

            val expected = listOf(
                text.indexOf("answer"),
                text.indexOf("answer", text.indexOf("copy")),
                text.lastIndexOf("answer"),
            ).map { start -> start to start + "answer".length }

            assertEquals(expected, ranges.map { it.start to it.end }, ranges.toString())
        }
    }

    @Test
    fun `occurrences returns matching bracket pair`() {
        withEnvironment { environment ->
            val text = "fun run() { repeat(3) {} }"
            val innerOpen = text.indexOf("{}")
            val ranges = environment.analyzer.occurrences(
                "occurrences-brackets.analysis.kts",
                text,
                innerOpen + 1,
            )

            assertEquals(
                listOf(innerOpen to innerOpen + 1, innerOpen + 1 to innerOpen + 2),
                ranges.map { it.start to it.end },
                ranges.toString(),
            )
        }
    }

    @Test
    fun `occurrences inside comment is empty`() {
        withEnvironment { environment ->
            val text = "// comment\nval answer = 42"
            val ranges = environment.analyzer.occurrences("occurrences-comment.analysis.kts", text, 3)

            assertTrue(ranges.isEmpty(), ranges.toString())
        }
    }

    @Test
    fun `signature help describes constructor parameters and active argument`() {
        withEnvironment { environment ->
            val text = """
                class Sample(val name: String = "demo", val count: Int)
                val sample = Sample()
            """.trimIndent()
            val caret = text.lastIndexOf(')')

            val help = assertNotNull(environment.analyzer.signatureHelp("signature.analysis.kts", text, caret))
            val signature = help.signatures.single()

            assertTrue(signature.label.startsWith("Sample("), signature.label)
            assertTrue(signature.label.contains("name: String = ..."), signature.label)
            assertTrue(signature.label.contains("count: Int"), signature.label)
            assertEquals("(name: String = ..., count: Int)", signature.label.substring(
                signature.presentation.start,
                signature.presentation.end,
            ))
            assertEquals(
                TokenType.VALUE_ARGUMENT_NAME,
                signature.highlights.single { highlight ->
                    signature.label.substring(highlight.range.start, highlight.range.end) == "name"
                }.tokenType,
            )
            assertEquals(
                TokenType.DEFAULT,
                signature.highlights.single { highlight ->
                    signature.label.substring(highlight.range.start, highlight.range.end) == "..."
                }.tokenType,
            )
            assertEquals(0, help.activeParameter)
        }
    }

    @Test
    fun `signature help includes every constructor overload`() {
        withEnvironment { environment ->
            val text = """
                class Test() {
                    constructor(value: Int) : this()
                    constructor(value: String) : this()
                }
                val test = Test()
            """.trimIndent()
            val caret = text.lastIndexOf(')')

            val help = assertNotNull(environment.analyzer.signatureHelp("constructors.analysis.kts", text, caret))

            assertEquals(
                setOf("Test()", "Test(value: Int)", "Test(value: String)"),
                help.signatures.mapTo(linkedSetOf()) { it.label },
            )
        }
    }

    @Test
    fun `signature help includes every function overload`() {
        withEnvironment { environment ->
            val text = """
                fun choose(value: Int) = value
                fun choose(value: String) = value
                val result = choose()
            """.trimIndent()
            val caret = text.lastIndexOf(')')

            val help = assertNotNull(environment.analyzer.signatureHelp("functions.analysis.kts", text, caret))

            assertEquals(
                setOf("choose(value: Int): Int", "choose(value: String): String"),
                help.signatures.mapTo(linkedSetOf()) { it.label },
            )
        }
    }

    @Test
    fun `hover returns symbol signature and plain kdoc`() {
        withEnvironment { environment ->
            val text = """
                /** Combines a name with a count. */
                fun combine(name: String, count: Int) = name.repeat(count)
                val result = combine("demo", 2)
            """.trimIndent()
            val usage = text.lastIndexOf("combine")

            val hover = assertNotNull(environment.analyzer.hover("hover.analysis.kts", text, usage + 2))

            assertTrue(hover.signature.startsWith("combine("), hover.signature)
            assertTrue(hover.signature.contains("name: String"), hover.signature)
            assertEquals(
                TokenType.FUNCTION,
                hover.highlights.single { highlight ->
                    hover.signature.substring(highlight.range.start, highlight.range.end) == "combine"
                }.tokenType,
            )
            assertEquals(
                TokenType.VALUE_ARGUMENT_NAME,
                hover.highlights.first { highlight ->
                    hover.signature.substring(highlight.range.start, highlight.range.end) == "name"
                }.tokenType,
            )
            assertEquals("Combines a name with a count.", hover.documentation)
            assertEquals(usage, hover.start)
            assertEquals(usage + "combine".length, hover.end)
        }
    }

    @Test
    fun `caret inside comment does not highlight the comment`() {
        withEnvironment { environment ->
            val text = "// comment\nval answer = 42"
            val lines = environment.analyzer.highlight(
                "comment-caret.analysis.kts",
                text,
                3,
            )

            assertFalse(lines.flatMap { it.spans }.any { (_, style) -> style.highlight }, lines.toString())
        }
    }

    @Test
    fun `full highlight resolves edited declarations and inlay hints`() {
        withEnvironment { environment ->
            val text = "val answer = 42\nval copy = answer"
            val lines = environment.analyzer.highlight(
                "edited-full.analysis.kts",
                text,
                text.lastIndexOf("answer"),
            )

            val spans = lines.flatMap { line -> line.spans }
            val secondLineHints = lines.getOrNull(1)?.hints.orEmpty()

            assertTrue(
                spans.any { (segment, style) -> segment == "answer" && style.color in setOf(TokenType.VARIABLE, TokenType.PROPERTY_IDENTIFIER) && style.highlight },
                spans.toString(),
            )
            assertTrue(
                secondLineHints.any { hint -> hint.index == "val copy".length && hint.text.contains("Int") },
                secondLineHints.toString(),
            )
        }
    }

    @Test
    fun `diagnostic resolves declarations from imported scripts`() {
        withEnvironment { environment ->
            writeSandboxScript("shared.analysis.kts", "val importedValue = 21")
            writeSandboxScript("other.analysis.kts", "val otherValue = 21")
            val text = """
                @file:Import("shared.analysis.kts", "other.analysis.kts")
                val answer = importedValue + otherValue
            """.trimIndent()

            val diagnostics = environment.analyzer.diagnostic("scripts/main.analysis.kts", text)

            assertFalse(
                diagnostics.any { diagnostic ->
                    diagnostic.message.contains("importedValue") || diagnostic.message.contains("otherValue")
                },
                diagnostics.toString(),
            )
        }
    }

    @Test
    fun `diagnostic resolves declarations reached through another script`() {
        withEnvironment { environment ->
            writeSandboxScript("deep.analysis.kts", "class Frame(val biome: String)\nfun newest() = Frame(\"river\")")
            writeSandboxScript("middle.analysis.kts", "@file:Import(\"deep.analysis.kts\")\nval ready = true")
            val text = """
                @file:Import("middle.analysis.kts")
                val answer = newest().biome
            """.trimIndent()

            val diagnostics = environment.analyzer.diagnostic("scripts/main.analysis.kts", text)

            assertFalse(
                diagnostics.any { diagnostic -> diagnostic.message.contains("newest") },
                diagnostics.toString(),
            )
        }
    }

    @Test
    fun `packages tell apart equal names from two imported scripts`() {
        withEnvironment { environment ->
            writeSandboxScript("first.analysis.kts", "package mypack.first\nval CUSTOM_ITEM = \"first\"")
            writeSandboxScript("second.analysis.kts", "package mypack.second\nval CUSTOM_ITEM = \"second\"")
            val text = """
                @file:Import("first.analysis.kts", "second.analysis.kts")

                import mypack.first.CUSTOM_ITEM as FIRST_ITEM

                val both = FIRST_ITEM + mypack.second.CUSTOM_ITEM
            """.trimIndent()

            val diagnostics = environment.analyzer.diagnostic("scripts/main.analysis.kts", text)

            assertFalse(diagnostics.any { it.severity.isError() }, diagnostics.toString())
        }
    }

    @Test
    fun `highlight provides hints for declarations using imported scripts`() {
        withEnvironment { environment ->
            writeSandboxScript("shared.analysis.kts", "val importedValue = 21")
            val text = """
                @file:Import("shared.analysis.kts")
                val answer = importedValue * 2
            """.trimIndent()

            val lines = environment.analyzer.highlight("scripts/main.analysis.kts", text, text.length)

            val answerHints = lines.getOrNull(1)?.hints.orEmpty()
            assertTrue(
                answerHints.any { hint -> hint.index == "val answer".length && hint.text.contains("Int") },
                answerHints.toString(),
            )
        }
    }

    @Test
    fun `missing imports report diagnostics without breaking hints`() {
        withEnvironment { environment ->
            val text = """
                @file:Import("missing.analysis.kts")
                val answer = 42
            """.trimIndent()

            val diagnostics = environment.analyzer.diagnostic("scripts/main.analysis.kts", text)
            val lines = environment.analyzer.highlight("scripts/main.analysis.kts", text, text.length)

            assertTrue(
                diagnostics.any { it.severity.isError() && it.message.contains("missing.analysis.kts") },
                diagnostics.toString(),
            )
            assertTrue(
                lines.getOrNull(1)?.hints.orEmpty().any { hint ->
                    hint.index == "val answer".length && hint.text.contains("Int")
                },
                lines.toString(),
            )
        }
    }

    @Test
    fun `recursive imports report diagnostics without breaking hints`() {
        withEnvironment { environment ->
            val first = """
                @file:Import("second.analysis.kts")
                val firstValue = secondValue
            """.trimIndent()
            writeSandboxScript("first.analysis.kts", first)
            writeSandboxScript(
                "second.analysis.kts",
                """
                    @file:Import("first.analysis.kts")
                    val secondValue = 42
                """.trimIndent(),
            )

            val diagnostics = environment.analyzer.diagnostic("scripts/first.analysis.kts", first)
            val lines = environment.analyzer.highlight("scripts/first.analysis.kts", first, first.length)

            assertTrue(
                diagnostics.any { it.severity.isError() && it.message.contains("cycle") },
                diagnostics.toString(),
            )
            assertTrue(
                lines.getOrNull(1)?.hints.orEmpty().any { hint ->
                    hint.index == "val firstValue".length && hint.text.contains("Int")
                },
                lines.toString(),
            )
        }
    }

    @Test
    fun `completion sees declarations from current script`() {
        withEnvironment { environment ->
            val text = """
                val localValue = 1
                loc
            """.trimIndent()
            val completions = environment.analyzer.collectCompletions(
                "completion.analysis.kts",
                text,
                text.length,
            )

            assertTrue(completions.any { it.name == "localValue" }, completions.toString())
        }
    }

    @Test
    fun `completion matches camel hump subsequences`() {
        withEnvironment { environment ->
            val text = """
                val hollowEngineCandidate = 1
                hollEngCand
            """.trimIndent()

            val completions = environment.analyzer.collectCompletions(
                "fuzzy-completion.analysis.kts",
                text,
                text.length,
            )

            assertTrue(completions.any { it.name == "hollowEngineCandidate" }, completions.toString())
        }
    }

    @Test
    fun `completion streams local declarations before the class indices`() {
        withEnvironment { environment ->
            val text = """
                val localValue = 1
                loc
            """.trimIndent()
            val batches = mutableListOf<List<CompletionItem>>()

            environment.analyzer.completions("streaming-completion.analysis.kts", text, text.length) { batch ->
                batches += batch
                true
            }

            val localBatch = batches.indexOfFirst { batch -> batch.any { it.name == "localValue" } }
            assertTrue(localBatch >= 0, batches.toString())
            val importedBatch = batches.indexOfFirst { batch ->
                batch.any { it is CompletionItem.Declaration && it.import }
            }
            assertTrue(
                importedBatch < 0 || localBatch < importedBatch,
                "declarations in scope must arrive before anything that needs an import",
            )
        }
    }

    @Test
    fun `completion stops collecting once the sink refuses a batch`() {
        withEnvironment { environment ->
            val text = """
                val localValue = 1
                loc
            """.trimIndent()
            var batches = 0

            environment.analyzer.completions("cancelled-completion.analysis.kts", text, text.length) {
                batches++
                false
            }

            assertEquals(1, batches)
        }
    }

    @Test
    fun `completion finds importable Java classes`() {
        withEnvironment { environment ->
            val typeText = "val connection: UR"
            val typeCompletions = environment.analyzer.collectCompletions(
                "java-type-completion.analysis.kts",
                typeText,
                typeText.length,
            ).filterIsInstance<CompletionItem.Declaration>()

            val uri = typeCompletions.single { it.fqName == "java.net.URI" }
            assertEquals("URI", uri.name)
            assertTrue(uri.import)

            val nestedTypeText = "val entry: Entr"
            val nestedTypeCompletions = environment.analyzer.collectCompletions(
                "java-nested-type-completion.analysis.kts",
                nestedTypeText,
                nestedTypeText.length,
            ).filterIsInstance<CompletionItem.Declaration>()

            val entry = nestedTypeCompletions.single { it.fqName == "java.util.Map.Entry" }
            assertEquals("Entry", entry.name)
            assertTrue(entry.import)

            val annotationText = "@Rete"
            val annotationCompletions = environment.analyzer.collectCompletions(
                "java-annotation-completion.analysis.kts",
                annotationText,
                annotationText.length,
            ).filterIsInstance<CompletionItem.Declaration>()

            assertTrue(
                annotationCompletions.any { it.fqName == "java.lang.annotation.Retention" && it.import },
                annotationCompletions.toString(),
            )
            assertFalse(annotationCompletions.any { it.fqName == "java.lang.Record" })

            val importText = "import java.net.UR"
            val importCompletions = environment.analyzer.collectCompletions(
                "java-import-completion.analysis.kts",
                importText,
                importText.length,
            ).filterIsInstance<CompletionItem.Declaration>()

            assertTrue(
                importCompletions.any { it.fqName == "java.net.URI" && !it.import },
                importCompletions.toString(),
            )
        }
    }

    @Test
    fun `format reindents blocks arguments and continuations`() {
        withEnvironment { environment ->
            val source = """
                fun main() {
                val values = listOf(
                1,
                2,
                )
                if (values.isEmpty())
                return
                values.map { it + 1 }
                .forEach {
                println(it)
                }
                }
            """.trimIndent()

            val formatted = environment.analyzer.format("format.analysis.kts", source)

            assertEquals(
                """
                    fun main() {
                        val values = listOf(
                            1,
                            2,
                        )
                        if (values.isEmpty())
                            return
                        values.map { it + 1 }
                            .forEach {
                                println(it)
                            }
                    }
                """.trimIndent() + "\n",
                formatted,
            )
        }
    }

    @Test
    fun `format keeps strings comments and line breaks untouched`() {
        withEnvironment { environment ->
            val source = """
                val banner   =   ""${'"'}
                        keep     me
                  exactly as typed
                ""${'"'}

                // a  comment  keeps  its  spacing
                fun run() {
                val greeting = "a  b"
                }
            """.trimIndent()

            val formatted = environment.analyzer.format("format-literals.analysis.kts", source)

            assertNotNull(formatted)
            assertTrue(formatted.contains("        keep     me\n  exactly as typed"), formatted)
            assertTrue(formatted.contains("// a  comment  keeps  its  spacing"), formatted)
            assertTrue(formatted.contains("""val greeting = "a  b""""), formatted)
            assertTrue(formatted.contains("\n    val greeting"), formatted)
            assertEquals(source.count { it == '\n' } + 1, formatted.count { it == '\n' }, "line count is preserved")
        }
    }

    @Test
    fun `format leaves imports annotations and modifiers at the left margin`() {
        withEnvironment { environment ->
            val source = """
                @file:Suppress("unused")
                @file:JvmName("Demo")

                import java.io.File
                import java.nio.file.Path
                import java.net.URI

                @Deprecated("old")
                fun first(): File? = null

                @Deprecated("old")
                private fun second(): Path? = null
            """.trimIndent()

            val formatted = environment.analyzer.format("format-imports.analysis.kts", source)

            // Only the missing trailing newline separates it from what was already correct.
            assertEquals(source + "\n", formatted)
        }
    }

    @Test
    fun `format spaces conditions lambdas and operators`() {
        withEnvironment { environment ->
            val source = """
                fun run(values:List<Int>):Int{
                if(values.isEmpty())return 0
                val doubled = values.map{it*2}
                val sum=doubled.fold(0){acc,value->acc+value}
                return sum
                }
            """.trimIndent()

            val formatted = environment.analyzer.format("format-spacing.analysis.kts", source)

            assertEquals(
                """
                    fun run(values: List<Int>): Int {
                        if (values.isEmpty()) return 0
                        val doubled = values.map { it * 2 }
                        val sum = doubled.fold(0) { acc, value -> acc + value }
                        return sum
                    }
                """.trimIndent() + "\n",
                formatted,
            )
        }
    }

    @Test
    fun `format keeps prefixes ranges and calls tight`() {
        withEnvironment { environment ->
            val source = """
                fun run(values: IntArray) {
                    val range = 1..10
                    val negative = -1
                    val copy = intArrayOf( *values )
                    val first = values [ 0 ]
                    val text = copy.joinToString ( "," )
                    val nullable: Int ? = null
                    println(range.first + negative + first + text.length + (nullable ?: 0))
                }
            """.trimIndent()

            val formatted = environment.analyzer.format("format-tight.analysis.kts", source)

            assertNotNull(formatted)
            assertTrue(formatted.contains("val range = 1..10"), formatted)
            assertTrue(formatted.contains("val negative = -1"), formatted)
            assertTrue(formatted.contains("intArrayOf(*values)"), formatted)
            assertTrue(formatted.contains("val first = values[0]"), formatted)
            assertTrue(formatted.contains("""copy.joinToString(",")"""), formatted)
            assertTrue(formatted.contains("val nullable: Int? = null"), formatted)
        }
    }

    @Test
    fun `format returns null when nothing changes`() {
        withEnvironment { environment ->
            val source = "val answer = 42\n"

            assertNull(environment.analyzer.format("format-clean.analysis.kts", source))
        }
    }

    private fun withEnvironment(block: (ScriptingEnvironmentImpl) -> Unit) = block(fixture.environment)

    private fun writeSandboxScript(path: String, text: String) {
        File("hollowengine/scripts", path).apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }

    private fun testClasspath(): List<File> {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .filter(File::exists)
            .distinctBy { it.absoluteFile.normalize() }
            .toList()
    }
}
