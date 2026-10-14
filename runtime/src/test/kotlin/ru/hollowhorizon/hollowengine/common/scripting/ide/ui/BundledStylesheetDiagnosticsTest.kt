package ru.hollowhorizon.hollowengine.common.scripting.ide.ui

import io.github.classgraph.ClassGraph
import ru.hollowhorizon.hollowengine.client.ui.style.compileHss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stylesheets the engine ships are the best available sample of real HSS: if the
 * schema drifts away from what the compiler accepts, they start reporting diagnostics.
 */
class BundledStylesheetDiagnosticsTest {
    private val stylesheets = ClassGraph()
        .overrideClassLoaders(javaClass.classLoader)
        .acceptPaths("assets/hollowengine/ui")
        .scan().use { scan ->
            scan.getResourcesWithExtension("hss").associate { resource ->
                resource.path to resource.contentAsString.replace("\r\n", "\n")
            }
        }

    @Test
    fun `bundled stylesheets compile without diagnostics and highlight without losing text`() {
        assertTrue(stylesheets.isNotEmpty(), "No bundled stylesheets found on the test classpath")
        for ((name, source) in stylesheets) {
            val diagnostics = HssScriptingAnalyzer.diagnostic(name, source)
            assertEquals(emptyList(), diagnostics, "$name: $diagnostics")
            compileHss(source)
            val highlighted = HssScriptingAnalyzer.highlight(name, source, 0)
                .joinToString("\n") { line -> line.spans.joinToString("") { it.first } }
            assertEquals(source, highlighted, name)
        }
    }
}
