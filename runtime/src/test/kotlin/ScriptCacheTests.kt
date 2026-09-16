import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.source.DirectoryScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptCacheTests {
    @Test
    fun `a stamped artifact reports the fingerprint it was built for`(@TempDir directory: File) {
        val jar = directory.resolve("script.jar")
        val fingerprint = ScriptFingerprint.Fingerprint(code = "abc123", layout = "def456", runtime = RUNTIME)
        writeStampedJar(jar, fingerprint)

        assertEquals("abc123", ScriptCache.hashOf(jar))
        assertTrue(ScriptCache.isValid(jar, fingerprint))
        assertTrue(ScriptCache.isCurrent(jar, fingerprint))
        assertFalse(ScriptCache.isValid(jar, fingerprint.copy(code = "other")))
        assertFalse(ScriptCache.isValid(jar, null))
    }

    @Test
    fun `bytecode mapped for another runtime is not usable even from the same code`(@TempDir directory: File) {
        val jar = directory.resolve("script.jar")
        val fingerprint = ScriptFingerprint.Fingerprint(code = "abc123", layout = "def456", runtime = RUNTIME)
        writeStampedJar(jar, fingerprint)

        val onFabric = fingerprint.copy(runtime = "fabric/intermediary/production")
        assertFalse(ScriptCache.isValid(jar, onFabric))
        assertFalse(ScriptCache.isCurrent(jar, onFabric))
    }

    @Test
    fun `an artifact built from the same code laid out differently is usable but not current`(
        @TempDir directory: File,
    ) {
        val jar = directory.resolve("script.jar")
        val fingerprint = ScriptFingerprint.Fingerprint(code = "abc123", layout = "def456", runtime = RUNTIME)
        writeStampedJar(jar, fingerprint)

        val reformatted = fingerprint.copy(layout = "reformatted")
        assertTrue(ScriptCache.isValid(jar, reformatted))
        assertFalse(ScriptCache.isCurrent(jar, reformatted))
    }

    @Test
    fun `an unstamped or missing artifact is never valid`(@TempDir directory: File) {
        val unstamped = directory.resolve("plain.jar")
        JarOutputStream(unstamped.outputStream(), Manifest()).use { }

        assertNull(ScriptCache.hashOf(unstamped))
        assertNull(ScriptCache.hashOf(directory.resolve("absent.jar")))
        assertFalse(ScriptCache.isValid(unstamped, ScriptFingerprint.Fingerprint("abc123", "def456", RUNTIME)))
    }

    @Test
    fun `the shared scripts an artifact was built against survive a round trip through its manifest`(
        @TempDir directory: File,
    ) {
        val references = listOf(
            ScriptCache.SharedScriptRef("Quests", ScriptId("pack", "lost_in_space/quests.kts"), "aaa"),
            ScriptCache.SharedScriptRef("Photos", ScriptId("pack", "photos.kts"), "bbb"),
        )
        val jar = directory.resolve("root.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(ScriptCache.SHARED_ATTRIBUTE, ScriptCache.sharedScriptsAttribute(references))
        }
        JarOutputStream(jar.outputStream(), manifest).use { }

        assertEquals(references.associateBy { it.scriptClass }, ScriptCache.sharedScriptsOf(jar))
    }

    @Test
    fun `pruning removes orphans but leaves namespaces it was not told about`(@TempDir root: File) {
        val known = ScriptId("prune-known", "kept.node.kts")
        val orphan = ScriptId("prune-known", "gone.node.kts")
        val untouched = ScriptId("prune-other", "elsewhere.node.kts")
        listOf(known, orphan, untouched).forEach { id ->
            val file = ScriptCache.artifact(id)
            file.parentFile.mkdirs()
            writeStampedJar(file, ScriptFingerprint.Fingerprint("hash", "layout", RUNTIME))
        }

        try {
            ScriptCache.prune(listOf(known))

            assertTrue(ScriptCache.artifact(known).isFile)
            assertFalse(ScriptCache.artifact(orphan).isFile)
            assertTrue(ScriptCache.artifact(untouched).isFile)
        } finally {
            DirectoryManager.SCRIPT_CACHE.resolve("prune-known").deleteRecursively()
            DirectoryManager.SCRIPT_CACHE.resolve("prune-other").deleteRecursively()
        }
    }

    @Test
    fun `the fingerprint follows the script and everything it imports`(@TempDir root: File) {
        root.resolve("main.node.kts").writeText("@file:Import(\"library.kts\")\nval body = 1")
        root.resolve("library.kts").writeText("val library = 1")

        withSource(root, "fingerprint-demo") {
            val id = ScriptId("fingerprint-demo", "main.node.kts")
            val original = ScriptFingerprint.compute(id)
            assertNotNull(original)
            assertEquals(original, ScriptFingerprint.compute(id))

            root.resolve("library.kts").writeText("val library = 2")
            assertNotEquals(original, ScriptFingerprint.compute(id))
        }
    }

    @Test
    fun `reformatting a script leaves the code it compiles to unchanged`(@TempDir root: File) {
        root.resolve("main.node.kts").writeText("@file:Import(\"library.kts\")\nval body = 1\n")
        root.resolve("library.kts").writeText("val library = 1\n")

        withSource(root, "formatting-demo") {
            val id = ScriptId("formatting-demo", "main.node.kts")
            val original = ScriptFingerprint.compute(id)
            assertNotNull(original)

            // What a checkout with the other line-ending convention, a re-indent and an edited comment
            // between them do to a pack that ships compiled artifacts: nothing at all.
            root.resolve("main.node.kts").writeText("@file:Import(\"library.kts\")\r\n\r\nval body = 1\r\n")
            root.resolve("library.kts").writeText("    val library = 1 // counted from one\r\n")

            val reformatted = ScriptFingerprint.compute(id)
            assertNotNull(reformatted)
            assertEquals(original.code, reformatted.code)
            assertNotEquals(original.layout, reformatted.layout)
        }
    }

    @Test
    fun `an import written inside a comment is not part of the cache key`(@TempDir root: File) {
        root.resolve("main.node.kts").writeText("val body = 1\n")
        root.resolve("library.kts").writeText("val library = 1\n")

        withSource(root, "commented-import-demo") {
            val id = ScriptId("commented-import-demo", "main.node.kts")
            val original = ScriptFingerprint.compute(id)
            assertNotNull(original)

            root.resolve("main.node.kts").writeText("// @file:Import(\"library.kts\")\nval body = 1\n")
            assertEquals(original.code, ScriptFingerprint.compute(id)?.code)

            root.resolve("library.kts").writeText("val library = 2\n")
            assertEquals(original.code, ScriptFingerprint.compute(id)?.code)
        }
    }

    @Test
    fun `the runtime is named beside the code hash, so remapping does not need the sources`(@TempDir root: File) {
        root.resolve("main.node.kts").writeText("val body = 1")

        withSource(root, "identity-demo") {
            val id = ScriptId("identity-demo", "main.node.kts")
            try {
                ScriptFingerprint.runtimeIdentity = "fabric/intermediary/production"
                val fabric = assertNotNull(ScriptFingerprint.compute(id))
                ScriptFingerprint.runtimeIdentity = RUNTIME
                val neoforge = assertNotNull(ScriptFingerprint.compute(id))

                assertEquals(fabric.code, neoforge.code)
                assertNotEquals(fabric.runtime, neoforge.runtime)
            } finally {
                ScriptFingerprint.runtimeIdentity = null
            }
        }
    }

    @Test
    fun `a script without sources cannot be fingerprinted`() {
        assertNull(ScriptFingerprint.compute(ScriptId("missing-namespace", "nothing.node.kts")))
    }

    private fun withSource(root: File, namespace: String, block: () -> Unit) {
        val source = DirectoryScriptSource(
            namespace = namespace,
            directory = root,
            classLoader = ScriptCacheTests::class.java.classLoader,
        )
        ScriptRegistry.register(source)
        try {
            block()
        } finally {
            ScriptRegistry.unregister(namespace)
        }
    }

    private fun writeStampedJar(file: File, fingerprint: ScriptFingerprint.Fingerprint) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            ScriptCache.stamp(mainAttributes, fingerprint)
        }
        file.parentFile?.mkdirs()
        JarOutputStream(file.outputStream(), manifest).use { }
    }

    private companion object {
        const val RUNTIME = ScriptFingerprint.NAMED_PRODUCTION_RUNTIME
    }
}
