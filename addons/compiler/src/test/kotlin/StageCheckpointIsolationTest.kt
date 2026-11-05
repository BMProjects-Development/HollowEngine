import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StageCheckpointIsolationTest : StageTest() {
    @Test
    fun `repeated rewinds restore the original locals of an earlier checkpoint`() = runTest {
        val world = world("checkpointLocals", """
            @State suspend fun main() {
                var value = 10
                checkpoint("start")
                log("value " + value)
                value++
                checkpoint("after")
                log("after " + value)
                await("end")
            }
        """)
        var story = world.launch("main")
        assertEquals(listOf("value 10", "after 11"), StageTrace.take())
        repeat(2) {
            story.rewind("start")
            world.settle()
            assertEquals(listOf("value 10", "after 11"), StageTrace.take())
            world.restart()
            story = world.runs["main"]!!
            assertEquals(listOf("after 11"), StageTrace.take())
        }
        world.stop()
    }
}
