import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SceneNodeScriptCompilationTest {
    @Test
    fun `node script can access the server scene and register synchronous cleanup`() {
        val environment = ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = System.getProperty("java.class.path").split(File.pathSeparator)
                .filter(String::isNotBlank).map(::File).filter(File::exists),
            scriptTypes = listOf(assertNotNull(DefaultScriptDefinitions.providerFor("scene.node.kts"))),
            mappings = Mappings.EMPTY,
            debugOutput = false,
        )
        try {
            val script = environment.compiler.compile(
                "scene.node.kts",
                """
                    import kotlinx.serialization.builtins.serializer
                    import ru.hollowhorizon.hollowengine.common.scene.SceneComponentType

                    val root = serverScene.create("example")
                    val type = SceneComponentType("test:sound".rl, String::class, String.serializer())
                    val component = root.addComponent(type, "before")
                    component.setData("after")
                    onStop { root.remove() }
                """.trimIndent(),
            ).getOrThrow()
            assertEquals(1, script.implicitReceiverCount)
        } finally {
            environment.close()
        }
    }
}
