import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptLoader
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Import
import ru.hollowhorizon.hollowengine.common.scripting.annotations.SharedScript
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.compiling.loadKotlinCompiledScriptFromJar
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.source.DirectoryScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class IdentityStartup

class SharedScriptIdentityTest {
    @Test
    fun `packet classes have one identity through nested relative imports and startup roots`(@TempDir directory: File) {
        directory.resolve("datas").mkdirs()
        directory.resolve("packets").mkdirs()
        directory.resolve("datas/ArrowData.kts").writeText(
            "@file:SharedScript\nimport kotlinx.serialization.Serializable\n@Serializable data class ArrowData(val value: Boolean)",
        )
        directory.resolve("packets/PlayerArrowsPacket.kts").writeText(
            """
                @file:SharedScript
                @file:Import("../datas/ArrowData.kts")
                import kotlinx.serialization.Serializable
                @Serializable
                class PlayerArrowsPacket(val list: List<ArrowData>)
            """.trimIndent(),
        )
        directory.resolve("fnf.startup.kts").writeText(
            """
                @file:Import("./packets/PlayerArrowsPacket.kts")
                val packet = PlayerArrowsPacket(listOf(ArrowData(true)))
            """.trimIndent(),
        )
        directory.resolve("fnf.reader.kts").writeText(
            """
                @file:Import("./packets/PlayerArrowsPacket.kts", "./fnf.startup.kts")
                import kotlinx.serialization.json.Json
                import kotlinx.serialization.encodeToString
                import kotlinx.serialization.decodeFromString
                val packetClass = PlayerArrowsPacket::class.java
                val dataClass = ArrowData::class.java
                val startupPacket = packet
                val roundTrip = Json.decodeFromString<PlayerArrowsPacket>(Json.encodeToString(packet))
            """.trimIndent(),
        )
        withScripts(directory, "packet-identity") { _, namespace ->
            val startupId = ScriptId(namespace, "fnf.startup.kts")
            val readerId = ScriptId(namespace, "fnf.reader.kts")
            val startup = ScriptLoader.execute<Any>(startupId).getOrThrow()
            val packet = startup.javaClass.getMethod("getPacket").invoke(startup)
            val reader = ScriptLoader.execute<Any>(readerId).getOrThrow()
            assertSame(packet.javaClass, reader.javaClass.getMethod("getPacketClass").invoke(reader))
            assertSame(packet, reader.javaClass.getMethod("getStartupPacket").invoke(reader))
            val roundTrip = reader.javaClass.getMethod("getRoundTrip").invoke(reader)
            assertSame(packet.javaClass, roundTrip.javaClass)
            val data = (packet.javaClass.getMethod("getList").invoke(packet) as List<*>).single()!!
            assertSame(data.javaClass, reader.javaClass.getMethod("getDataClass").invoke(reader))
            val decodedData = (roundTrip.javaClass.getMethod("getList").invoke(roundTrip) as List<*>).single()!!
            assertSame(data.javaClass, decodedData.javaClass)
            SharedScriptClasses.keepInstance(startup::class)
            SharedScriptClasses.clearInstances()
            repeat(2) {
                val cached = ScriptCache.artifact(readerId).loadKotlinCompiledScriptFromJar(javaClass.classLoader)
                    .execute<Any>().getOrThrow()
                assertSame(packet.javaClass, cached.javaClass.getMethod("getPacketClass").invoke(cached))
                assertSame(packet, cached.javaClass.getMethod("getStartupPacket").invoke(cached))
            }
            val rerun = ScriptCache.artifact(startupId).loadKotlinCompiledScriptFromJar(javaClass.classLoader)
                .execute<Any>().getOrThrow()
            assertSame(startup, rerun)

            directory.resolve("datas/ArrowData.kts").appendText("\nval revision = 2")
            val failure = assertFailsWith<Exception> {
                ScriptLoader.execute<Any>(readerId).getOrThrow()
            }
            assertTrue(failure.message.orEmpty().contains("Restart the game"), failure.message)
        }
    }

    @Test
    fun `shared dependency policy rejects private imports in compiler and IDE`(@TempDir directory: File) {
        directory.resolve("data.kts").writeText("class PrivateData")
        directory.resolve("packet.kts").writeText(
            "@file:SharedScript\n@file:Import(\"data.kts\")\nclass Packet(val data: PrivateData)",
        )
        directory.resolve("main.kts").writeText("@file:Import(\"packet.kts\")\nval packet = Packet(PrivateData())")
        withScripts(directory, "private-shared-import") { environment, namespace ->
            val failure = ScriptLoader.compile(ScriptId(namespace, "main.kts")).exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("imports non-shared script"), failure?.message)
            assertTrue(failure?.message.orEmpty().contains("data.kts"), failure?.message)
            val diagnostics = environment.analyzer.diagnostic(
                "$namespace:main.kts", directory.resolve("main.kts").readText(),
            )
            assertTrue(diagnostics.any { it.message.contains("imports non-shared script") }, diagnostics.toString())

            val startupText = "@file:Import(\"data.kts\")\nval data = PrivateData()"
            directory.resolve("bad.startup.kts").writeText(startupText)
            assertTrue(ScriptLoader.compile(ScriptId(namespace, "bad.startup.kts")).isFailure)
            assertTrue(environment.analyzer.diagnostic("$namespace:bad.startup.kts", startupText)
                .any { it.message.contains("imports non-shared script") })
        }
    }

    @Test
    fun `loading a compiled shared script does not reidentify it after a source edit`(@TempDir directory: File) {
        directory.resolve("model.kts").writeText("@file:SharedScript\nclass Model")
        directory.resolve("first.kts").writeText("@file:Import(\"model.kts\")\nval model = Model()")
        directory.resolve("second.kts").writeText("@file:Import(\"model.kts\")\nval model = Model()")
        withScripts(directory, "frozen-shared-identity") { _, namespace ->
            val first = ScriptLoader.compile(ScriptId(namespace, "first.kts")).getOrThrow()
            val second = ScriptLoader.compile(ScriptId(namespace, "second.kts")).getOrThrow()
            directory.resolve("model.kts").appendText("\nval revision = 2")
            val firstInstance = first.execute<Any>().getOrThrow()
            val secondInstance = second.execute<Any>().getOrThrow()
            val firstModel = firstInstance.javaClass.getMethod("getModel").invoke(firstInstance)
            val secondModel = secondInstance.javaClass.getMethod("getModel").invoke(secondInstance)
            assertSame(firstModel.javaClass, secondModel.javaClass)
        }
    }

    private fun withScripts(
        directory: File,
        namespace: String,
        body: (ScriptingEnvironmentImpl, String) -> Unit,
    ) {
        val imports = listOf(Import::class.qualifiedName!!, SharedScript::class.qualifiedName!!)
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).filter(File::exists)
        val environment = ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = classpath,
            scriptTypes = listOf(
                ScriptClassProvider("kts", "kotlin.Any", imports),
                ScriptClassProvider("startup.kts", IdentityStartup::class.qualifiedName!!, imports, shared = true),
            ),
            mappings = Mappings.EMPTY,
        )
        ScriptRegistry.register(DirectoryScriptSource(namespace, directory, javaClass.classLoader, fingerprint = "test"))
        ScriptingEnvironment.INSTANCE = environment
        try {
            body(environment, namespace)
        } finally {
            ScriptRegistry.unregister(namespace)
            ScriptingEnvironment.clear()
            File("hollowengine").deleteRecursively()
        }
    }
}
