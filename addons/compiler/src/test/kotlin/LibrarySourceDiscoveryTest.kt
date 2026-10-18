import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.common.ide.session.definition.adjacentLibrarySources
import ru.hollowhorizon.hollowengine.common.ide.session.definition.collectSourceArchives
import ru.hollowhorizon.hollowengine.common.ide.session.definition.developmentSourceManifest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibrarySourceDiscoveryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `ordinary jars never cause traversal of their grandparents`() {
        val library = root.resolve("profile/Local/runtime.jar").file()
        val sibling = library.resolveSibling("runtime-sources.jar").file()
        root.resolve("profile/Application Data/runtime-sources.jar").file()
        val sources = adjacentLibrarySources(library) { _, failure -> throw failure }
        assertEquals(listOf(sibling), sources)
    }

    @Test
    fun `Gradle artifacts find sources in sibling checksum directories of the same version`() {
        val version = root.resolve("files-2.1/example/library/1.0")
        val library = version.resolve("binary-checksum/library-1.0.jar").file()
        val source = version.resolve("source-checksum/library-1.0-sources.jar").file()
        root.resolve("files-2.1/example/library/2.0/other-checksum/library-1.0-sources.jar").file()
        val sources = adjacentLibrarySources(library) { _, failure -> throw failure }
        assertEquals(listOf(source), sources.filter(Path::isRegularFile))
    }

    @Test
    fun `a failed source root is reported and does not stop searching other roots`() {
        val missing = root.resolve("unavailable")
        val archive = root.resolve("available/library-sources.jar").file()
        val failures = ArrayList<Path>()
        val sources = listOf(missing, archive.parent).flatMap { folder ->
            collectSourceArchives(folder) { path, _ -> failures.add(path) }
        }
        assertEquals(listOf(missing), failures)
        assertEquals(listOf(archive), sources)
    }

    @Test
    fun `source traversal filters files and respects its maximum depth`() {
        root.resolve("not-an-archive.jar").createDirectories()
        root.resolve("notes.txt").file()
        val source = root.resolve("nested/library-sources.zip").file()
        val sources = collectSourceArchives(root) { _, failure -> throw failure }
        assertEquals(listOf(source), sources)
        assertTrue(collectSourceArchives(root, maxDepth = 1) { _, failure -> throw failure }.isEmpty())
    }

    @Test
    fun `development launches discover the nearest Gradle source manifest from run and module directories`() {
        root.resolve("settings.gradle.kts").file()
        val manifest = root.resolve("build/ide/source-roots.txt").file()
        for (directory in listOf("run", "addons/compiler", "build/runtime/libs", ".gradle/loom-cache")) {
            assertEquals(manifest, developmentSourceManifest(root.resolve(directory)), directory)
        }
        root.resolve("included/settings.gradle").file()
        assertNull(developmentSourceManifest(root.resolve("included/run")))
    }

    @Test
    fun `a source manifest outside a Gradle project is not automatically attached`() {
        root.resolve("build/ide/source-roots.txt").file()
        assertNull(developmentSourceManifest(root.resolve("run")))
    }

    private fun Path.file(): Path = apply {
        parent.createDirectories()
        writeText("")
    }
}
