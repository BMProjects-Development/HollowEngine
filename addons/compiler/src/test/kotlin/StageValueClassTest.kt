import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A value class is a primitive to the JVM, so what a stage holds in place of one cannot be null. */
class StageValueClassTest : StageTest() {
    @Test
    fun `a duration can be an argument of an action`() = runTest {
        val world = world("durationArgument", """
            import ru.hollowhorizon.hollowengine.addons.narrative.npcs.*
            import ru.hollowhorizon.hollowengine.addons.narrative.scene.commands.*
            import ru.hollowhorizon.hollowengine.addons.narrative.story.*
            import ru.hollowhorizon.hollowengine.common.entities.NpcEntity

            var npc by saved<NpcEntity?>(null)

            @State
            suspend fun main() {
                if ("yes".length == 2) (npc!!).lookAt(duration = duration("2"), target = storyPlayer())
                log("done")
            }
        """)
        world.launch("main")
        assertEquals(listOf("done"), StageTrace.take())
    }
}
