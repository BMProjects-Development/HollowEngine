import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Control flow around stage calls: expressions, nested handlers, recursion, loops, failing calls. */
class StageControlFlowTest : StageTest() {
    @Test
    fun `nested finally blocks run in order when a stage returns from inside them`() = runTest {
        val world = world("finally", """
            @State
            suspend fun step() {
                log("step")
                await("s")
            }

            @State
            suspend fun inner(): String {
                try {
                    try {
                        step()
                        return "done"
                    } finally {
                        log("inner finally")
                    }
                } finally {
                    log("outer finally")
                }
            }

            @State
            suspend fun main() = log(inner())
        """)
        world.launch("main")
        assertEquals(listOf("step"), StageTrace.take())

        world.restart()
        assertEquals(listOf("inner finally", "outer finally", "step"), StageTrace.take(), "unloading runs the finally blocks too")

        world.release("s")
        assertEquals(listOf("inner finally", "outer finally", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `an expression with several stage calls resumes inside each of them`() = runTest {
        val world = world("arithmetic", $$"""
            @State
            suspend fun first(): Int {
                log("first")
                await("a")
                return 2
            }

            @State
            suspend fun second(): Int {
                log("second")
                await("b")
                return 3
            }

            @State
            suspend fun third(): Int {
                log("third")
                await("c")
                return 4
            }

            @State
            suspend fun main() {
                val result = first() + second() * third()
                log("result $result")
            }
        """
        )
        world.launch("main")
        world.release("a")
        assertEquals(listOf("first", "second"), StageTrace.take())

        world.restart()
        world.release("b")
        world.restart()
        assertEquals(listOf("second", "third", "third"), StageTrace.take(), "first and second are not called again")

        world.release("c")
        assertEquals(listOf("result 14"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `short-circuit operators call the right side only when they need it, after a restart too`() = runTest {
        val world = world("operators", $$"""
            @State
            suspend fun check(): Boolean {
                log("check")
                await("check")
                return StageTrace.token == "pass"
            }

            @State
            suspend fun validate(): Boolean {
                log("validate")
                await("validate")
                return true
            }

            @State
            suspend fun load(): String? {
                log("load")
                await("load")
                return null
            }

            @State
            suspend fun fallback(): String {
                log("fallback")
                await("fallback")
                return "default"
            }

            @State
            suspend fun main() {
                val ok = check() && validate()
                log("ok $ok")
                val value = load() ?: fallback()
                log("value $value")
            }
        """
        )
        StageTrace.token = "pass"
        world.launch("main")
        world.release("check")
        world.restart()
        assertEquals(listOf("check", "validate", "validate"), StageTrace.take())

        world.release("validate")
        world.release("load")
        world.restart()
        assertEquals(listOf("ok true", "load", "fallback", "fallback"), StageTrace.take())

        world.release("fallback")
        assertEquals(listOf("value default"), StageTrace.take())

        StageTrace.token = "fail"
        world.launch("main", key = "second")
        assertEquals(listOf("check", "ok false", "load", "fallback", "value default"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `recursion keeps every frame with its arguments and results`() = runTest {
        val world = world("recursion", $$"""
            @State
            suspend fun factorial(n: Int): Int {
                if (n <= 1) {
                    await("base")
                    return 1
                }
                val child = factorial(n - 1)
                checkpoint("return")
                log("return $n")
                if (n == 3) await("middle")
                return n * child
            }

            @State
            suspend fun main() = log("result ${factorial(5)}")
        """
        )
        val story = world.launch("main")
        assertEquals(5, story.state.split(" > ").size - 1, "main and five calls of factorial are on the stack")

        world.restart()
        world.release("base")
        assertEquals(listOf("return 2", "return 3"), StageTrace.take())

        world.restart()
        assertEquals(listOf("return 3"), StageTrace.take(), "the frame of n = 3 resumes after its checkpoint with child = 2")

        world.release("middle")
        assertEquals(listOf("return 4", "return 5", "result 120"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `break and continue of nested loops work before and after a restart`() = runTest {
        val world = world("jumps", $$"""
            @State
            suspend fun visit(i: Int, j: Int) {
                log("visit $i $j")
                await("v$i$j")
            }

            @State
            suspend fun main() {
                outer@ for (i in 0 until 3) {
                    for (j in 0 until 3) {
                        if (j == 1) continue
                        if (i == 1) continue@outer
                        if (i == 2 && j == 2) break@outer
                        visit(i, j)
                    }
                }
                log("done")
            }
        """
        )
        world.launch("main")
        world.release("v00")
        world.restart()
        assertEquals(listOf("visit 0 0", "visit 0 2", "visit 0 2"), StageTrace.take())

        world.release("v02")
        world.restart()
        assertEquals(listOf("visit 2 0", "visit 2 0"), StageTrace.take())

        world.release("v20")
        assertEquals(listOf("done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a do-while condition can call a stage, and continue still checks it`() = runTest {
        val world = world("doWhile", $$"""
            @State
            suspend fun more(round: Int): Boolean {
                log("more $round")
                await("m$round")
                return round < 2
            }

            @State
            suspend fun main() {
                var round = 0
                do {
                    round++
                    if (round == 1) continue
                    log("round $round")
                } while (more(round))
                log("done $round")
            }
        """
        )
        world.launch("main")
        world.restart()
        assertEquals(listOf("more 1", "more 1"), StageTrace.take())

        world.release("m1")
        world.release("m2")
        assertEquals(listOf("round 2", "more 2", "done 2"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a failed stage call leaves its caller in the segment it was in before the call`() = runTest {
        val world = world("failures", """
            @State
            suspend fun step(name: String) = log("step ${'$'}name")

            @State
            suspend fun risky() {
                log("risky")
                throw IllegalArgumentException("boom")
            }

            @State
            suspend fun main() {
                step("a")
                try {
                    risky()
                } catch (failure: IllegalArgumentException) {
                    log("caught")
                }
                log("after")
                await("x")
                log("end")
            }
        """)
        world.launch("main")
        assertEquals(listOf("step a", "risky", "caught", "after"), StageTrace.take())
        val frame = world.save().getCompound("main").getList("frames", 10).getCompound(0)
        assertEquals("after:step#1", frame.getString("position"))

        world.restart()
        assertEquals(listOf("risky", "caught", "after"), StageTrace.take(), "the segment after step repeats")

        world.release("x")
        assertEquals(listOf("end"), StageTrace.take())
        world.stop()
    }
}
