import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import org.jetbrains.kotlin.psi.KtFile
import ru.hollowhorizon.hollowengine.common.ide.session.completion.animationNameCompletions
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.animationNameDiagnostics
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
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
        override fun of(model: String) = when (model) {
            "hero.glb" -> listOf("idle", "wave", "walk")
            "default.glb" -> listOf("idle")
            else -> null
        }
        override fun modelOfNpc(id: String) = if (id == "village:hero") "hero.glb" else null
    }

    private val api = """
        import ru.hollowhorizon.hollowengine.common.attachments.editor.*

        class Actor
        fun spawn(@EditorModel model: String = "") = Actor()
        fun spawnHero(@EditorModel("hero.glb") model: String = "") = Actor()
        fun find(@EditorNpcId id: String) = Actor()
        fun Actor.play(@EditorAnimation animation: String) = Unit
        infix fun Actor.playOnce(@EditorAnimation animation: String) = Unit
        class Look(@EditorModel("default.glb") val model: String = "default.glb")
        fun Actor.set(look: Look) = Unit
        fun build(@EditorNpcId id: String? = null, @EditorModel("default.glb") builder: Actor.() -> Unit = {}) = Actor()
    """.trimIndent()

    private fun file(body: String): KtFile {
        val name = "scripts/main.clips.kts"
        fixture.environment.analyzer.diagnostic(name, "$api\n$body\n")
        return fixture.environment.analyzer.fileCache.getValue(name).file
    }

    private fun warnings(body: String): List<String> = animationNameDiagnostics(file(body), clips).map { it.message }

    /** What is offered at `|` in [body]. */
    private fun offered(body: String): List<String>? {
        val file = file(body.replace("|", ""))
        return animationNameCompletions(file, api.length + 1 + body.indexOf('|'), clips)?.map { it.insert }
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

    @Test
    fun `an entity made without naming a model shows the one its parameter falls back to`() {
        assertEquals(listOf("This model has no animation \"dance\""), warnings("spawnHero().play(\"dance\")"))
        assertEquals(emptyList(), warnings("spawnHero(model = \"\").play(\"wave\")"))
    }

    @Test
    fun `a builder gives the model it sets, or the one it falls back to`() {
        assertEquals(listOf("This model has no animation \"dance\""), warnings("build { set(Look(\"hero.glb\")) }.play(\"dance\")"))
        assertEquals(listOf("This model has no animation \"wave\""), warnings("build { }.play(\"wave\")"))
        assertEquals(listOf("This model has no animation \"wave\""), warnings("build { set(Look()) }.play(\"wave\")"))
        assertEquals(emptyList(), warnings("build(\"village:hero\") { set(Look(\"other.glb\")) }.play(\"wave\")"))
    }

    @Test
    fun `an infix call names a clip too`() {
        assertEquals(listOf("This model has no animation \"dance\""), warnings("spawnHero() playOnce \"dance\""))
        assertEquals(listOf("walk", "wave"), offered("spawnHero() playOnce \"w|\""))
    }

    @Test
    fun `the clips of the model are offered in the string naming one`() {
        assertEquals(listOf("walk", "wave"), offered("spawnHero().play(\"w|\")"))
        val body = "spawnHero().play(\"w\")"
        val items = animationNameCompletions(file(body), api.length + 1 + body.indexOf("w\""), clips).orEmpty()
        assertEquals(setOf("hero.glb"), items.map { (it as CompletionItem.Declaration).clipOf }.toSet())
        assertEquals(listOf("idle", "walk", "wave"), offered("val hero = spawn(model = \"hero.glb\")\nhero.play(\"|\")"))
        assertEquals(null, offered("spawn().play(\"|\")"))
    }
}
