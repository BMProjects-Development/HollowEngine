import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.fir.FirSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class CompilerPluginAnalysisScript

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompilerPluginAnalysisTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = testClasspath(),
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = ".analysis.kts",
                    baseClass = CompilerPluginAnalysisScript::class.qualifiedName!!,
                ),
                ScriptClassProvider(
                    extension = "ui.kts",
                    baseClass = "ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript",
                    defaultImports = listOf("ru.hollowhorizon.hollowengine.client.ui.*"),
                ),
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
    fun `analysis understands compose function types and call context`() {
        withEnvironment { environment ->
            assertNoErrors(
                environment.analyzer.diagnostic(
                    "compose.analysis.kts",
                    """
                        @androidx.compose.runtime.Composable
                        fun Demo() {
                            androidx.compose.runtime.key(1) { }
                        }
                    """.trimIndent(),
                )
            )

            val contextErrors = environment.analyzer.diagnostic(
                "invalid-compose-context.analysis.kts",
                """
                    @androidx.compose.runtime.Composable
                    fun Child() = Unit

                    fun InvalidCaller() {
                        Child()
                    }
                """.trimIndent(),
            ).filter { it.severity.isError() }
            assertTrue(
                contextErrors.any { it.message.contains("Composable invocations can only happen") },
                contextErrors.toString(),
            )
        }
    }

    @Test
    fun `analysis resolves the real ui composable receiver`() {
        withEnvironment { environment ->
            val code = """
                screen("test:screen") {
                    content {
                        val serverBound = isServerBound
                    }
                }
            """.trimIndent()
            assertNoErrors(environment.analyzer.diagnostic("screen.ui.kts", code))

            val completionCode = """
                screen("test:completion") {
                    content {
                        isSer<caret>
                    }
                }
            """.trimIndent()
            val caret = completionCode.indexOf("<caret>")
            val source = completionCode.replace("<caret>", "")
            val completions = environment.analyzer.collectCompletions(
                "completion.ui.kts",
                source,
                caret,
            )
            assertTrue(
                completions.any { it.name == "isServerBound" },
                completions.toString(),
            )
        }
    }

    @Test
    fun `binary and resolvable libraries keep compatible plugin providers`() {
        withEnvironment { environment ->
            val analyzer = environment.analyzer
            val library = analyzer.libraries.single { !it.isSdk }
            val (resolvableSession, binarySession) = ApplicationManager.getApplication().runReadAction(
                Computable {
                    CompilerPluginSessionTestBridge.getSession(
                        analyzer.project,
                        library,
                        false,
                    ) to CompilerPluginSessionTestBridge.getSession(
                        analyzer.project,
                        library,
                        true,
                    )
                },
            )

            assertEquals(FirSession.Kind.Source, resolvableSession.kind)
            assertFalse(CompilerPluginSessionTestBridge.hasBinaryLibraryProvider(resolvableSession))
            assertTrue(CompilerPluginSessionTestBridge.hasComposeFunctionTypeKind(resolvableSession))

            assertEquals(FirSession.Kind.Library, binarySession.kind)
            assertTrue(CompilerPluginSessionTestBridge.hasBinaryLibraryProvider(binarySession))
            assertTrue(CompilerPluginSessionTestBridge.hasComposeFunctionTypeKind(binarySession))
        }
    }

    @Test
    fun `analysis exposes serialization generated declarations`() {
        withEnvironment { environment ->
            val code = """
                @kotlinx.serialization.Serializable
                data class Payload(val value: String)

                val payloadSerializer = Payload.serializer()
            """.trimIndent()
            assertNoErrors(environment.analyzer.diagnostic("serialization.analysis.kts", code))

            val completionCode = """
                @kotlinx.serialization.Serializable
                data class CompletionPayload(val value: String)

                val payloadSerializer = CompletionPayload.ser<caret>
            """.trimIndent()
            val caret = completionCode.indexOf("<caret>")
            val source = completionCode.replace("<caret>", "")
            val completions = environment.analyzer.collectCompletions(
                "serialization-completion.analysis.kts",
                source,
                caret,
            )
            assertTrue(
                completions.any { it.name == "serializer" },
                completions.toString(),
            )
        }
    }

    @Test
    fun `ui modifier completions include extensions on the companion and chained values`() {
        withEnvironment { environment ->
            assertNoErrors(environment.analyzer.diagnostic("modifier-calls.ui.kts", """
                screen("test:modifier") {
                    content {
                        val modifier = Modifier.gap(4.px).padding(8.px)
                    }
                }
            """.trimIndent()))

            val receivers = listOf("Modifier", "Modifier.Companion", "Modifier.gap(4.px)", "UiModifier")
            for ((index, receiver) in receivers.withIndex()) {
                for (prefix in listOf("", "pa")) {
                    val aliasImport = if (receiver == "UiModifier") {
                        "import ru.hollowhorizon.hollowengine.client.ui.Modifier as UiModifier\n"
                    } else ""
                    val text = aliasImport + "val modifier = $receiver.$prefix"
                    val items = environment.analyzer.collectCompletions(
                        "modifier-$index-${prefix.length}.ui.kts", text, text.length,
                    )
                    assertTrue(items.any { it.name == "padding" }, "$receiver.$prefix: $items")
                    if (prefix.isEmpty()) assertTrue(items.any { it.name == "gap" }, "$receiver: $items")
                }
            }
        }
    }

    @Test
    fun `class receiver completions include applicable companion extensions without instance members`() {
        withEnvironment { environment ->
            val declarations = """
                open class ReceiverBase
                object Instance : ReceiverBase()
                class Receiver {
                    companion object : ReceiverBase() {
                        fun ownMember() = Unit
                    }
                    fun instanceOnly() = Unit
                }
                fun ReceiverBase.applicableExtension() = Unit
                val ReceiverBase.extensionProperty: Int get() = 0
                fun String.unrelatedExtension() = Unit
            """.trimIndent()
            for (receiver in listOf("Receiver", "Instance")) {
                val text = "$declarations\n$receiver."
                val items = environment.analyzer.collectCompletions("$receiver-extensions.analysis.kts", text, text.length)
                if (receiver == "Receiver") assertTrue(items.any { it.name == "ownMember" }, items.toString())
                assertTrue(items.any { it.name == "applicableExtension" }, items.toString())
                assertTrue(items.any { it.name == "extensionProperty" }, items.toString())
                assertFalse(items.any { it.name == "instanceOnly" || it.name == "unrelatedExtension" }, items.toString())
            }
            val text = "$declarations\nReceiverBase."
            val items = environment.analyzer.collectCompletions("no-companion.analysis.kts", text, text.length)
            assertFalse(items.any { it.name == "applicableExtension" }, items.toString())
        }
    }

    @Test
    fun `companion receiver completions offer importable extensions`() {
        withEnvironment { environment ->
            val text = """
                import ru.hollowhorizon.hollowengine.client.ui.Modifier
                val modifier = Modifier.pa
            """.trimIndent()
            val items = environment.analyzer.collectCompletions("importable-modifier.analysis.kts", text, text.length)
            val declarations = items.filterIsInstance<CompletionItem.Declaration>()
            assertTrue(declarations.any { it.name == "padding" && it.import }, items.toString())
        }
    }

    private fun withEnvironment(block: (ScriptingEnvironmentImpl) -> Unit) {
        block(fixture.environment)
    }

    private fun assertNoErrors(diagnostics: List<Diagnostic>) {
        val errors = diagnostics.filter { it.severity.isError() }
        assertTrue(errors.isEmpty(), errors.toString())
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
