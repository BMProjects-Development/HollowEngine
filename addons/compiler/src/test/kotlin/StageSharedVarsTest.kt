import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** A `var` of a stage that its `@State` lambdas capture is one value for all of them, and the story saves it. */
class StageSharedVarsTest : StageTest() {
    @Test
    fun `a branch changes a var of its stage, and the value survives a restart`() = runTest {
        val world = world("sharedCounter", $$"""
            @State
            suspend fun main() {
                var count = 0
                async("counter") {
                    count++
                    log("branch $count")
                    await("more")
                    count++
                    log("branch $count")
                }
                checkpoint("started")
                await("check")
                log("main $count")
            }
        """
        )
        world.launch("main")
        world.release("more")
        assertEquals(listOf("branch 1", "branch 2"), StageTrace.take())

        world.restart()
        world.release("check")
        assertEquals(listOf("main 2"), StageTrace.take(), "main reads the value the branch left")
        world.stop()
    }

    @Test
    fun `a segment that repeats keeps sharing the box with the branch it started before`() = runTest {
        val world = world("sharedRepeat", $$"""
            @State
            suspend fun main() {
                var count = 0
                async("counter") {
                    await("tick")
                    count++
                    log("branch $count")
                }
                await("check")
                log("main $count")
            }
        """
        )
        world.launch("main")
        world.restart()
        world.release("tick")
        world.release("check")
        assertEquals(listOf("branch 1", "main 1"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `every loop iteration declares a var of its own`() = runTest {
        val world = world("sharedLoop", $$"""
            @State
            suspend fun main() {
                for (i in 0 until 2) {
                    var hits = i * 10
                    async("b$i") {
                        hits++
                        log("b$i $hits")
                    }
                }
                await("x")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("b0 1", "b1 11"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a var of a stage lambda is shared with the lambdas inside it`() = runTest {
        val world = world("sharedNested", $$"""
            @State
            suspend fun main() {
                async("outer") {
                    var seen = "none"
                    async("inner") { seen = "inner" }
                    await("x")
                    log("outer sees $seen")
                }
                await("y")
            }
        """
        )
        world.launch("main")
        world.release("x")
        assertEquals(listOf("outer sees inner"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a branch calls a local function of its stage that shares a var and starts branches of its own`() = runTest {
        val world = world("sharedLocal", $$"""
            @State
            suspend fun main() {
                var last = "none"
                suspend fun sing(name: String) {
                    log("sing $name after $last")
                    last = name
                    async("reset") {
                        await("r")
                        log("reset $name")
                    }
                }
                listOf("left", "right").forEach { direction ->
                    async("input_$direction") {
                        sing(direction)
                        await("hold")
                    }
                }
                await("end")
                log("last $last")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("sing left after none", "sing right after left"), StageTrace.take())

        world.restart()
        assertEquals(listOf("sing left after none", "sing right after left"), StageTrace.take(), "the segment repeats with its branches")

        world.release("r")
        world.release("end")
        assertEquals(listOf("reset left", "reset right", "last right"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a shared var can hold the handle of a branch, which a restart finds again`() = runTest {
        val world = world("sharedHandle", $$"""
            @State
            suspend fun main() {
                var timer: StoryHandle<*>? = null
                suspend fun sing(name: String) {
                    timer?.cancel()
                    timer = async("reset") {
                        await("r$name")
                        log("reset $name")
                    }
                }
                async("input") {
                    sing("left")
                    await("next")
                    sing("right")
                    checkpoint("sung")
                    await("hold")
                }
                checkpoint("listening")
                await("end")
                log("timer ${timer?.key}")
            }
        """
        )
        world.launch("main")
        world.release("next")
        world.restart()
        world.release("rleft")
        world.release("rright")
        world.release("end")
        assertEquals(listOf("reset right", "timer main/input/reset"), StageTrace.take(), "the cancelled timer stays cancelled")
        world.stop()
    }

    @Test
    fun `a story started from a stage cannot share its vars`() {
        val errors = compiler.errors("""
            fun start(runs: StoryRuns) {}

            @State
            suspend fun main() {
                var count = 0
                context.server.launchStory("other") { count++ }
            }
        """)
        assertContains(errors, "keeps copies of what it captures")
    }

    @Test
    fun `a var declared outside a stage cannot be shared`() {
        val errors = compiler.errors("""
            fun start(runs: StoryRuns) {
                var count = 0
                runs.launch("story") { count++ }
            }
        """)
        assertContains(errors, "only with the @State function or lambda that declares it directly")
    }

    @Test
    fun `a shared var must hold a value the story can save`() {
        val errors = compiler.errors("""
            @State
            suspend fun main() {
                var lock = Any()
                async("b") { lock = Any() }
            }
        """)
        assertContains(errors, "its value is saved")
    }
}
