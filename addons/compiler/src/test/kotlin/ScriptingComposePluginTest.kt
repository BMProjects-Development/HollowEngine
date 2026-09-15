import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.compiler.CompiledScriptImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.io.File
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class ComposeUiTestScript

class ScriptingComposePluginTest {
    @Test
    fun `compose plugin rewrites composable functions in scripts`() {
        withComposeScript(
            """
            @Composable
            fun Greeting(name: String) {
                remember(name) { name.uppercase() }
            }
            """.trimIndent()
        ) { classes ->
            // The Compose plugin appends a `Composer` parameter and a `$changed` bitmask to every
            // composable; seeing them in the bytecode proves the plugin ran, not just that the
            // annotation resolved.
            val referencesComposer = classes.values.any { bytes ->
                bytes.containsUtf8("androidx/compose/runtime/Composer")
            }
            assertTrue(referencesComposer, "No Composer references found: plugin did not transform the script")
        }
    }

    @Test
    fun `real ui script compiles compose receiver content`() {
        withComposeScript(
            baseClass = "ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript",
            code = """
                screen("test:screen") {
                    content {
                        remember(isServerBound) { isServerBound }
                    }
                }
            """.trimIndent(),
        ) { classes ->
            val referencesComposer = classes.values.any { bytes ->
                bytes.containsUtf8("androidx/compose/runtime/Composer")
            }
            assertTrue(referencesComposer, "Real UiScript content was not transformed by the Compose plugin")
        }
    }

    @Test
    fun `scripts can declare serializable types`() {
        withComposeScript(
            extension = ".shared.kts",
            code = """
            @kotlinx.serialization.Serializable
            data class Quest(val title: String, val progress: Int)
            """.trimIndent()
        ) { classes ->
            // The serialization plugin generates a nested ${'$'}serializer for the class.
            val hasSerializer = classes.keys.any { it.contains("Quest") && it.contains("serializer", ignoreCase = true) }
            assertTrue(hasSerializer, "No generated serializer found: serialization plugin did not run")
        }
    }

    @Test
    fun `serial info annotations with numeric arguments compile and keep their values`() {
        withCompiledScript(
            extension = ".shared.kts",
            code = """
            @file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

            import kotlinx.serialization.Serializable
            import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange

            @Serializable
            data class Reward(
                @EditorRange(1.0, 64.0) val count: Int = 1,
                @EditorRange(max = 0.5, slider = true) val chance: Float = 0f,
            )

            val descriptor = kotlinx.serialization.serializer<Reward>().descriptor
            val annotations = (0 until descriptor.elementsCount).map {
                descriptor.getElementAnnotations(it).joinToString()
            }
            """.trimIndent()
        ) { compiled ->
            val script = compiled.execute<Any> {}.getOrThrow()
            val annotations = script.javaClass.getDeclaredField("annotations").apply { isAccessible = true }.get(script)
            assertEquals(
                listOf(
                    "@ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange(min=1.0, max=64.0, slider=false)",
                    "@ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange(min=-Infinity, max=0.5, slider=true)",
                ),
                annotations,
            )
        }
    }

    @Test
    fun `plain kts scripts also get the compose plugin`() {
        withComposeScript(
            extension = ".shared.kts",
            code = """
            @Composable
            fun Shared() {
                remember { 0 }
            }
            """.trimIndent()
        ) { classes ->
            val referencesComposer = classes.values.any { bytes ->
                bytes.containsUtf8("androidx/compose/runtime/Composer")
            }
            assertTrue(referencesComposer, "Compose plugin is not applied to shared .kts scripts")
        }
    }

    private fun withComposeScript(
        code: String,
        extension: String = ".ui.kts",
        baseClass: String = ComposeUiTestScript::class.qualifiedName!!,
        assertions: (Map<String, ByteArray>) -> Unit,
    ) = withCompiledScript(code, extension, baseClass) { assertions(it.compiledClasses()) }

    private fun withCompiledScript(
        code: String,
        extension: String,
        baseClass: String = ComposeUiTestScript::class.qualifiedName!!,
        assertions: (CompiledScript) -> Unit,
    ) {
        val environment = ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = testClasspath(),
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = extension,
                    baseClass = baseClass,
                    defaultImports = listOf(
                        "androidx.compose.runtime.Composable",
                        "androidx.compose.runtime.remember",
                    ),
                )
            ),
            mappings = Mappings.EMPTY,
        )

        try {
            ScriptingEnvironment.INSTANCE = environment
            assertions(environment.compiler.compile("compose_test$extension", code).getOrThrow())
        } finally {
            ScriptingEnvironment.clear()
            File("hollowengine").deleteRecursively()
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

private fun CompiledScript.compiledClasses(): Map<String, ByteArray> {
    val jvmScript = (this as CompiledScriptImpl).script as KJvmCompiledScript
    val module = jvmScript.getCompiledModule() as KJvmCompiledModuleInMemory
    return module.compilerOutputFiles.filterKeys { it.endsWith(".class") }
}

private fun ByteArray.containsUtf8(needle: String): Boolean {
    val target = needle.toByteArray(Charsets.UTF_8)
    if (target.isEmpty() || target.size > size) return false
    outer@ for (start in 0..size - target.size) {
        for (offset in target.indices) {
            if (this[start + offset] != target[offset]) continue@outer
        }
        return true
    }
    return false
}
