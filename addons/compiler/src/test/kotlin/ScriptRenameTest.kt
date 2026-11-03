import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

abstract class RenameScript

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptRenameTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank)
                .map(::File).filter(File::exists).distinctBy { it.absoluteFile.normalize() },
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = ".rename.kts",
                    baseClass = RenameScript::class.qualifiedName!!
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

    private val analyzer get() = fixture.environment.analyzer

    @Test
    fun `a declaration is renamed in every script that imports it, and nowhere its name means something else`() {
        script("core.rename.kts", "class Quest(val title: String)\nfun greet(quest: Quest) = quest.title")
        script("other.rename.kts", "fun greet(times: Int) = times\nval twice = greet(2)")
        val main = "@file:Import(\"core.rename.kts\")\n\nval first = greet(Quest(\"start\"))\n"
        val caret = main.indexOf("greet")

        val renames = analyzer.rename("scripts/main.rename.kts", main, caret, "welcome", emptyMap())
            .associate { it.path to it.applied() }

        assertEquals(
            mapOf(
                "scripts/main.rename.kts" to "@file:Import(\"core.rename.kts\")\n\nval first = welcome(Quest(\"start\"))\n",
                "scripts/core.rename.kts" to "class Quest(val title: String)\nfun welcome(quest: Quest) = quest.title",
            ),
            renames,
        )
    }

    @Test
    fun `an open document is renamed as it is typed, not as it was saved`() {
        script("model.rename.kts", "class Hero(val name: String)")
        script("user.rename.kts", "@file:Import(\"model.rename.kts\")\n")
        val open = "@file:Import(\"model.rename.kts\")\nval hero = Hero(\"Steve\")\n"
        val main = "@file:Import(\"model.rename.kts\")\nval other = Hero(\"Alex\")\n"

        val renames = analyzer.rename(
            "scripts/main.rename.kts", main, main.indexOf("Hero"), "Champion",
            mapOf("scripts/user.rename.kts" to open),
        ).associate { it.path to it.applied() }

        assertEquals(
            "@file:Import(\"model.rename.kts\")\nval hero = Champion(\"Steve\")\n",
            renames["scripts/user.rename.kts"]
        )
        assertEquals("class Champion(val name: String)", renames["scripts/model.rename.kts"])
    }

    @Test
    fun `a local stays in its own body`() {
        script("elsewhere.rename.kts", "val count = 3")
        val main = "fun run(): Int {\n    val count = 1\n    return count + 1\n}\nfun walk() { val count = 2 }\n"

        val renames = analyzer.rename("scripts/main.rename.kts", main, main.indexOf("count"), "total", emptyMap())

        assertEquals(
            listOf("fun run(): Int {\n    val total = 1\n    return total + 1\n}\nfun walk() { val count = 2 }\n"),
            renames.map { it.applied() },
        )
    }

    @Test
    fun `a name from a library cannot be renamed`() {
        val main = "val size = listOf(1).size\n"
        assertNull(analyzer.renameTarget("scripts/main.rename.kts", main, main.indexOf("listOf")))
    }

    private fun script(path: String, text: String) {
        File("hollowengine/scripts", path).apply {
            parentFile.mkdirs()
            writeText(text)
        }
    }
}
