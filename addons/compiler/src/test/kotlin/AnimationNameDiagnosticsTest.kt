import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.animationNameDiagnostics
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class ClipScript

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnimationNameDiagnosticsTest {
    private val fixture = AnalysisEnvironmentTestFixture {
        ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank)
                .map(::File).filter(File::exists).distinctBy { it.absoluteFile.normalize() },
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = ".clips.kts",
                    baseClass = ClipScript::class.qualifiedName!!
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

    private val clips = object : AnimationClips {
        override fun of(model: String) = if (model == "hero.glb") listOf("idle", "wave", "walk") else null
        override fun modelOfNpc(id: String) = if (id == "village:hero") "hero.glb" else null
    }

    private val api = """
        import ru.hollowhorizon.hollowengine.common.attachments.editor.*

        class Actor
        fun spawn(@EditorModel model: String = "") = Actor()
        fun find(@EditorNpcId id: String) = Actor()
        fun Actor.play(@EditorAnimation animation: String) = Unit
    """.trimIndent()

    private fun warnings(body: String): List<String> {
        val name = "scripts/main.clips.kts"
        val text = "$api\n$body\n"
        fixture.environment.analyzer.diagnostic(name, text)
        val file = fixture.environment.analyzer.fileCache.getValue(name).file
        return animationNameDiagnostics(file, clips).map { it.message }
    }

    @Test
    fun `a clip the model lacks is named along with those it may have meant`() {
        val found = warnings("val hero = spawn(model = \"hero.glb\")\nhero.play(\"wav\")\nhero.play(\"wave\")")
        assertEquals(listOf("This model has no animation \"wav\". Did you mean: wave?"), found)
    }

    @Test
    fun `the model of an NPC of the world comes through its id`() {
        val found = warnings("find(\"village:hero\").play(\"dance\")")
        assertEquals(listOf("This model has no animation \"dance\""), found)
    }

    @Test
    fun `a receiver of unknown origin says nothing`() {
        assertEquals(
            emptyList(),
            warnings("var hero = spawn(model = \"hero.glb\")\nhero.play(\"dance\")\nspawn().play(\"dance\")")
        )
    }
}
