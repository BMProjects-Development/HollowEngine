import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Branch cancellation and composition must preserve the lifetime and identity of each run. */
class StageConcurrencyTest : StageTest() {
    @Test
    fun `a cancelled branch can be replaced under the same name before cancellation finishes`() = runTest {
        val world = world("replaceBranch", """
            @State
            suspend fun main() {
                val first = async("timer") {
                    try { await("never") } finally { log("cancelled") }
                }
                await("replace")
                first.cancel()
                val second = async("timer") { log("replacement"); await("timer") }
                second.join()
                log("done")
            }
        """)
        world.launch("main")
        world.release("replace")
        assertEquals(listOf("cancelled", "replacement"), StageTrace.take())
        world.release("timer")
        assertEquals(listOf("done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `any waits for the requested number and only then cancels the remaining branches`() = runTest {
        val world = world("anyBranches", """
            @State
            suspend fun main() {
                val a = async("a") { await("a"); log("a") }
                val b = async("b") { await("b"); log("b") }
                val c = async("c") {
                    try { await("c"); log("c") } finally { log("close c") }
                }
                val ended = any(2, a, b, c)
                log("winners " + ended.joinToString { it.key })
                await("end")
            }
        """)
        world.launch("main")
        world.release("a")
        assertEquals(listOf("a"), StageTrace.take())
        world.release("b")
        assertEquals(listOf("b", "winners main/a, main/b", "close c"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `any counts branches that have already ended`() = runTest {
        val world = world("alreadyEnded", """
            @State
            suspend fun main() {
                val a = async("a") { log("a") }
                val b = async("b") { log("b") }
                a.join()
                b.join()
                log("ended " + any(2, a, b).size)
            }
        """)
        world.launch("main")
        assertEquals(listOf("a", "b", "ended 2"), StageTrace.take())
        assertTrue(world.runs.keys.isEmpty())
        world.stop()
    }

    @Test
    fun `race rejects an empty set instead of suspending forever`() = runTest {
        val world = world("emptyRace", """
            @State
            suspend fun main() {
                try { race() } catch (failure: IllegalArgumentException) { log("empty") }
            }
        """)
        world.launch("main")
        assertEquals(listOf("empty"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a saved stage lambda keeps its result type when used to start a branch`() = runTest {
        val world = world("savedLambdaResult", """
            @State
            suspend fun consume(body: @State suspend () -> Int) {
                checkpoint("ready")
                await("go")
                val branch = async("value", body)
                log("value " + branch.await())
                await("end")
            }

            @State suspend fun main() { consume { 42 } }
        """)
        world.launch("main")
        world.restart()
        world.release("go")
        assertEquals(listOf("value 42"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a branch name cannot be taken from another active stage`() = runTest {
        val world = world("branchOwnership", """
            @State suspend fun inner() {
                try { async("ambient") { await("never") } }
                catch (failure: StoryException) { log("conflict " + failure.message) }
            }
            @State suspend fun main() {
                async("ambient") { await("never") }
                inner()
                await("end")
            }
        """)
        world.launch("main")
        assertTrue(StageTrace.take().single().contains("belongs to another active stage"))
        world.stop()
    }

    @Test
    fun `cancelling a completed timer allows the next timer to start`() = runTest {
        val world = world("completedTimer", """
            @State suspend fun main() {
                val first = async("timer") { log("first") }
                first.join()
                first.cancel()
                val second = async("timer") { log("second") }
                second.join()
                log("done")
            }
        """)
        world.launch("main")
        assertEquals(listOf("first", "second", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a branch cancelled before it starts still completes join`() = runTest {
        val world = world("cancelBeforeStart", """
            @State suspend fun main() {
                val branch = async("timer") { log("unexpected"); await("never") }
                branch.cancel()
                branch.join()
                log("stopped")
            }
        """)
        world.launch("main")
        assertEquals(listOf("stopped"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `any validates distinct runs and zero cancels every supplied run`() = runTest {
        val world = world("anyLimits", """
            @State suspend fun main() {
                val branch = async("timer") { await("never") }
                try { any(2, branch, branch) }
                catch (failure: IllegalArgumentException) { log("duplicate") }
                log("count " + any(0, branch).size)
                branch.join()
                log("active " + branch.isActive)
            }
        """)
        world.launch("main")
        assertEquals(listOf("duplicate", "count 0", "active false"), StageTrace.take())
        world.stop()
    }
}
