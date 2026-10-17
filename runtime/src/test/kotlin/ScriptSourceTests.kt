import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonDescriptorReader
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.source.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class ScriptSourceTests {
    @Test
    fun `unqualified paths keep their pre-namespace spelling`() {
        val id = ScriptRegistry.parse("scripts/nodes/example.node.kts")
        assertEquals(ScriptRegistry.sandboxNamespace, id.namespace)
        assertEquals("nodes/example.node.kts", id.path)
        assertEquals("scripts/nodes/example.node.kts", ScriptRegistry.display(id))
    }

    @Test
    fun `a path without the scripts prefix names the same script`() {
        assertEquals(
            ScriptRegistry.parse("scripts/nodes/example.node.kts"),
            ScriptRegistry.parse("nodes/example.node.kts"),
        )
    }

    @Test
    fun `a qualified path names another namespace and stays qualified`() {
        val id = ScriptRegistry.parse("my-addon:nodes/example.node.kts")
        assertEquals("my-addon", id.namespace)
        assertEquals("nodes/example.node.kts", id.path)
        assertEquals("my-addon:nodes/example.node.kts", ScriptRegistry.display(id))
    }

    @Test
    fun `the sandbox takes its namespace from plugin properties`(@TempDir root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("META-INF/plugin.properties").writeText("id=my-pack\ndependsOn=other-addon\n")
        root.resolve("scripts").mkdirs()

        val source = SandboxScriptSource(root)
        assertEquals("my-pack", source.namespace)
        assertEquals(listOf("other-addon"), source.dependencies)
    }

    @Test
    fun `an unnamed sandbox falls back to the reserved namespace`(@TempDir root: File) {
        assertEquals(DEFAULT_SANDBOX_NAMESPACE, SandboxScriptSource(root).namespace)
    }

    @Test
    fun `a directory source lists and reads its scripts`(@TempDir root: File) {
        root.resolve("nodes").mkdirs()
        root.resolve("nodes/a.node.kts").writeText("// a")
        root.resolve("b.ui.kts").writeText("// b")
        root.resolve("c.story").writeText("Vitalik: hi")
        root.resolve("notes.txt").writeText("ignored")

        val source = directorySource("demo", root)
        assertEquals(
            listOf("b.ui.kts", "c.story", "nodes/a.node.kts"),
            source.list().map(ScriptId::path),
        )

        val artifacts = assertNotNull(source.read(ScriptId("demo", "nodes/a.node.kts")))
        assertEquals("// a", artifacts.sourceFile?.readText())
        assertNull(artifacts.precompiled)
    }

    @Test
    fun `a directory source refuses to escape its own directory`(@TempDir root: File) {
        root.resolve("scripts").mkdirs()
        root.resolve("outside.node.kts").writeText("// outside")

        val source = directorySource("demo", root.resolve("scripts"))
        assertNull(source.read(ScriptId("demo", "../outside.node.kts")))
    }

    @Test
    fun `imports resolve next to the importing script`(@TempDir root: File) {
        root.resolve("recipes").mkdirs()
        root.resolve("recipes/library.kts").writeText("// library")
        root.resolve("recipes/main.reload.kts").writeText("@file:Import(\"library.kts\")\n")

        withSource(directorySource("demo", root)) {
            val owner = ScriptId("demo", "recipes/main.reload.kts")
            assertEquals(ScriptId("demo", "recipes/library.kts"), ScriptImports.resolve(owner, "library.kts"))
        }
    }

    @Test
    fun `imports read from the file text match what is written there`() {
        val text = """
            @file:Import("library.kts", "other/second.kts")
            @file:Attach(net.minecraft.world.entity.LivingEntity::class)
        """.trimIndent()
        assertEquals(listOf("library.kts", "other/second.kts"), ScriptImports.parse(text))
    }

    @Test
    fun `a cross-namespace import needs a declared dependency`(@TempDir root: File) {
        val consumerRoot = root.resolve("consumer").apply { mkdirs() }
        val libraryRoot = root.resolve("library").apply { mkdirs() }
        consumerRoot.resolve("main.node.kts").writeText("@file:Import(\"library-addon:shared.kts\")\n")
        libraryRoot.resolve("shared.kts").writeText("// shared")

        withSource(directorySource("library-addon", libraryRoot)) {
            withSource(directorySource("consumer-addon", consumerRoot)) {
                val owner = ScriptId("consumer-addon", "main.node.kts")
                assertFailsWith<IllegalArgumentException> {
                    ScriptImports.resolve(owner, "library-addon:shared.kts")
                }
            }

            val declared = directorySource("consumer-addon", consumerRoot, dependencies = listOf("library-addon"))
            withSource(declared) {
                val owner = ScriptId("consumer-addon", "main.node.kts")
                assertEquals(
                    ScriptId("library-addon", "shared.kts"),
                    ScriptImports.resolve(owner, "library-addon:shared.kts"),
                )
            }
        }
    }

    @Test
    fun `the closure of a script contains everything it is built from`(@TempDir root: File) {
        root.resolve("a.node.kts").writeText("@file:Import(\"b.kts\")\n")
        root.resolve("b.kts").writeText("@file:Import(\"c.kts\")\n")
        root.resolve("c.kts").writeText("// leaf")

        withSource(directorySource("demo", root)) {
            val closure = ScriptImports.closure(ScriptId("demo", "a.node.kts"))
            assertEquals(listOf("a.node.kts", "b.kts", "c.kts"), closure.map(ScriptId::path))
        }
    }

    @Test
    fun `an import cycle does not loop forever`(@TempDir root: File) {
        root.resolve("a.node.kts").writeText("@file:Import(\"b.kts\")\n")
        root.resolve("b.kts").writeText("@file:Import(\"a.node.kts\")\n")

        withSource(directorySource("demo", root)) {
            val closure = ScriptImports.closure(ScriptId("demo", "a.node.kts"))
            assertEquals(listOf("a.node.kts", "b.kts"), closure.map(ScriptId::path))
        }
    }

    @Test
    fun `a namespace can always import from itself`() {
        assertTrue(ScriptRegistry.canImport("some-addon", "some-addon"))
    }

    @Test
    fun `an addon jar hands out its sources and its compiled artifacts`(@TempDir directory: File) {
        val archive = directory.resolve("addon.jar")
        JarOutputStream(archive.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("scripts/quest.node.kts"))
            jar.write("// quest".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("META-INF/hollowengine/scripts/quest.node.kts.jar"))
            jar.write(byteArrayOf(1, 2, 3))
            jar.closeEntry()
            jar.putNextEntry(JarEntry("scripts/stories/quest.story"))
            jar.write("Виталик: Привет".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("ru/example/Ignored.class"))
            jar.write(byteArrayOf(4))
            jar.closeEntry()
        }

        val source = AddonScriptSource(
            namespace = "jar-addon",
            archive = archive,
            classLoader = ScriptSourceTests::class.java.classLoader,
            classpath = listOf(archive),
            dependencies = emptyList(),
            fingerprint = "1.0.0",
            storageKey = "test-storage-key",
        )
        try {
            assertEquals(
                listOf(
                    ScriptId("jar-addon", "quest.node.kts"),
                    ScriptId("jar-addon", "stories/quest.story"),
                ),
                source.list(),
            )

            val artifacts = assertNotNull(source.read(ScriptId("jar-addon", "quest.node.kts")))
            assertEquals("// quest", artifacts.sourceFile?.readText())
            assertEquals(listOf<Byte>(1, 2, 3), artifacts.precompiled?.readBytes()?.toList())

            val story = assertNotNull(source.read(ScriptId("jar-addon", "stories/quest.story")))
            assertEquals("Виталик: Привет", story.sourceFile?.readText())
            assertNull(story.precompiled)
        } finally {
            DirectoryManager.SCRIPT_SOURCE_CACHE.resolve("jar-addon").deleteRecursively()
            DirectoryManager.SCRIPT_BUNDLE_CACHE.resolve("jar-addon").deleteRecursively()
        }
    }

    @Test
    fun `an addon cannot claim the sandbox namespace`(@TempDir directory: File) {
        val archive = directory.resolve("reserved.jar")
        JarOutputStream(archive.outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/plugin.properties"))
            jar.write("id=$DEFAULT_SANDBOX_NAMESPACE\nentry=com.example.Addon\n".toByteArray())
            jar.closeEntry()
        }

        val failure = assertFailsWith<IllegalArgumentException> { HollowAddonDescriptorReader.read(archive) }
        assertTrue(DEFAULT_SANDBOX_NAMESPACE in failure.message.orEmpty())
    }

    private fun directorySource(
        namespace: String,
        directory: File,
        dependencies: List<String> = emptyList(),
    ) = DirectoryScriptSource(
        namespace = namespace,
        directory = directory,
        classLoader = ScriptSourceTests::class.java.classLoader,
        dependencies = dependencies,
    )

    private fun withSource(source: DirectoryScriptSource, block: () -> Unit) {
        ScriptRegistry.register(source)
        try {
            block()
        } finally {
            ScriptRegistry.unregister(source.namespace)
        }
    }
}
