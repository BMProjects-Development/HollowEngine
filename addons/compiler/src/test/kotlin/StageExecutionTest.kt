import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Stages run like ordinary code, and a restart brings each frame back to the boundary it last passed: the code
 * before that boundary does not run again, the segment after it does.
 */
class StageExecutionTest {
    private lateinit var compiler: StageCompiler

    @BeforeTest
    fun setUp() {
        StageTrace.reset()
        compiler = StageCompiler()
    }

    @AfterTest
    fun tearDown() = compiler.close()

    private fun TestScope.world(name: String, code: String) = StageWorld(this, compiler.compile(name, code))

    @Test
    fun `a nested stage resumes inside the call and the caller continues after it`() = runTest {
        val world = world("nested", """
            @State
            suspend fun main() {
                log("main:start")
                first()
                log("main:after")
                await("x")
                log("main:end")
            }

            @State
            suspend fun first() {
                log("first:start")
                await("y")
                log("first:end")
            }
        """)
        world.launch("main")
        assertEquals(listOf("main:start", "first:start"), StageTrace.take())

        world.restart()
        assertEquals(listOf("first:start"), StageTrace.take(), "first restarts from its entry, main does not run again")

        world.release("y")
        assertEquals(listOf("first:end", "main:after"), StageTrace.take())

        world.restart()
        assertEquals(listOf("main:after"), StageTrace.take(), "main repeats only the segment after first")

        world.release("x")
        assertEquals(listOf("main:end"), StageTrace.take())
        assertTrue(world.runs.keys.isEmpty(), "a finished story leaves its owner")
        world.stop()
    }

    @Test
    fun `the result of a finished stage call is restored instead of calling again`() = runTest {
        val world = world("result", $$"""
            @State
            suspend fun main() {
                val value = compute()
                log("got $value")
                await("x")
                log("end $value")
            }

            @State
            suspend fun compute(): Int {
                log("compute")
                await("c")
                return 42
            }
        """
        )
        world.launch("main")
        world.release("c")
        assertEquals(listOf("compute", "got 42"), StageTrace.take())

        world.restart()
        assertEquals(listOf("got 42"), StageTrace.take())
        world.release("x")
        assertEquals(listOf("end 42"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `locals that outlive a stage call keep the value they had at it`() = runTest {
        val world = world("locals", $$"""
            fun prefix(): String {
                log("prefix")
                return "there"
            }

            @State
            suspend fun step() {
                log("step")
                await("s")
            }

            @State
            suspend fun main() {
                val greeting = "hi " + prefix()
                var count = 1
                step()
                count += 1
                log("$greeting $count")
                await("x")
                step()
                log("$greeting $count")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("prefix", "step"), StageTrace.take())

        world.restart()
        assertEquals(listOf("step"), StageTrace.take(), "the locals come from the save, prefix() is not called again")

        world.release("s")
        assertEquals(listOf("hi there 2"), StageTrace.take())

        world.restart()
        assertEquals(listOf("hi there 2"), StageTrace.take(), "count is restored as it was at the call, then incremented again")

        world.release("x")
        assertEquals(listOf("step", "hi there 2"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `what an expression computed before a stage call in it is not computed again`() = runTest {
        val world = world("temporaries", """
            fun label(text: String): String {
                log("label ${'$'}text")
                return text
            }

            @State
            suspend fun value(): String {
                await("v")
                return "b"
            }

            @State
            suspend fun main() {
                log(label("a") + value() + label("c"))
            }
        """)
        world.launch("main")
        assertEquals(listOf("label a"), StageTrace.take())

        world.restart()
        assertEquals(emptyList(), StageTrace.take())

        world.release("v")
        assertEquals(listOf("label c", "abc"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a branch taken before a restart is taken again without evaluating its condition`() = runTest {
        val world = world("branches", """
            fun decide(): Boolean {
                log("decide")
                return StageTrace.token == "left"
            }

            fun pick(): Int {
                log("pick")
                return 2
            }

            @State
            suspend fun left() {
                log("left")
                await("l")
            }

            @State
            suspend fun right() = log("right")

            @State
            suspend fun number(value: Int): Int {
                log("number ${'$'}value")
                await("n${'$'}value")
                return value * 10
            }

            @State
            suspend fun main() {
                if (decide()) left() else right()
                val result = when (pick()) {
                    1 -> number(1)
                    else -> number(2)
                }
                log("result ${'$'}result")
            }
        """)
        StageTrace.token = "left"
        world.launch("main")
        assertEquals(listOf("decide", "left"), StageTrace.take())

        StageTrace.token = "right"
        world.restart()
        assertEquals(listOf("left"), StageTrace.take(), "the condition is not evaluated again, so the token does not matter")

        world.release("l")
        assertEquals(listOf("pick", "number 2"), StageTrace.take())

        world.restart()
        assertEquals(listOf("number 2"), StageTrace.take())

        world.release("n2")
        assertEquals(listOf("result 20"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a loop resumes in the iteration it was in`() = runTest {
        val world = world("loops", """
            fun more(i: Int): Boolean {
                log("check ${'$'}i")
                return i < 3
            }

            @State
            suspend fun tick(i: Int) {
                log("tick ${'$'}i")
                await("t${'$'}i")
            }

            @State
            suspend fun main() {
                var i = 0
                while (more(i)) {
                    tick(i)
                    i++
                }
                log("done ${'$'}i")
            }
        """)
        world.launch("main")
        world.release("t0")
        assertEquals(listOf("check 0", "tick 0", "check 1", "tick 1"), StageTrace.take())

        world.restart()
        assertEquals(listOf("tick 1"), StageTrace.take(), "the loop is entered without checking its condition")

        world.release("t1")
        world.release("t2")
        assertEquals(listOf("check 2", "tick 2", "check 3", "done 3"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `for loops over lists and ranges resume at the element they were on`() = runTest {
        val world = world("for", """
            @State
            suspend fun visit(name: String) {
                log("visit ${'$'}name")
                await(name)
            }

            @State
            suspend fun count(n: Int) {
                log("count ${'$'}n")
                await("n${'$'}n")
            }

            @State
            suspend fun main() {
                for (name in listOf("a", "b", "c")) visit(name)
                for (n in 6 downTo 1 step 2) count(n)
                log("done")
            }
        """)
        world.launch("main")
        world.release("a")
        assertEquals(listOf("visit a", "visit b"), StageTrace.take())

        world.restart()
        assertEquals(listOf("visit b"), StageTrace.take())

        world.release("b")
        world.release("c")
        assertEquals(listOf("visit c", "count 6"), StageTrace.take())

        world.release("n6")
        world.restart()
        assertEquals(listOf("count 4", "count 4"), StageTrace.take())

        world.release("n4")
        world.release("n2")
        assertEquals(listOf("count 2", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a stage resumed inside try is still guarded by its catch and finally`() = runTest {
        val world = world("guarded", """
            @State
            suspend fun risky() {
                log("risky")
                await("r")
                throw IllegalArgumentException("boom")
            }

            @State
            suspend fun main() {
                try {
                    risky()
                    log("not reached")
                } catch (failure: IllegalArgumentException) {
                    log("caught ${'$'}{failure.message}")
                } finally {
                    log("finally")
                }
                log("after")
            }
        """)
        world.launch("main")
        assertEquals(listOf("risky"), StageTrace.take())

        world.restart()
        assertEquals(listOf("finally", "risky"), StageTrace.take(), "finally runs when the story is unloaded, then the stage resumes")

        world.release("r")
        assertEquals(listOf("caught boom", "finally", "after"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a checkpoint ends a segment and segment data survives a restart`() = runTest {
        val world = world("checkpoints", """
            @State
            suspend fun main() {
                log("intro")
                checkpoint("gate")
                log("segment")
                once("reward") { log("reward") }
                val boss = remember("boss") {
                    log("spawn")
                    "ogre"
                }
                log("boss ${'$'}boss")
                await("x")
                log("end")
            }
        """)
        world.launch("main")
        assertEquals(listOf("intro", "segment", "reward", "spawn", "boss ogre"), StageTrace.take())

        world.restart()
        assertEquals(listOf("segment", "boss ogre"), StageTrace.take())

        world.release("x")
        assertEquals(listOf("end"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `tail calls replace the frame so a story can go back and forth forever`() = runTest {
        val world = world("tails", """
            @State
            suspend fun main() = ping(0)

            @State
            suspend fun ping(n: Int) {
                log("ping ${'$'}n")
                await("p${'$'}n")
                if (n < 3) pong(n + 1) else log("stop")
            }

            @State
            suspend fun pong(n: Int) {
                log("pong ${'$'}n")
                await("q${'$'}n")
                ping(n + 1)
            }
        """)
        val story = world.launch("main")
        assertEquals(listOf("ping 0"), StageTrace.take())
        assertEquals("ping", story.state)

        world.release("p0")
        assertEquals("pong", story.state, "pong took the place of ping")

        world.restart()
        assertEquals(listOf("pong 1", "pong 1"), StageTrace.take())
        val resumed = world.runs["main"]!!
        assertEquals("pong", resumed.state)

        world.release("q1")
        world.release("p2")
        world.release("q3")
        world.release("p4")
        assertEquals(listOf("ping 2", "pong 3", "ping 4", "stop"), StageTrace.take())
        assertTrue(world.runs.keys.isEmpty())
        world.stop()
    }

    @Test
    fun `a tail call that returns a value hands it to the caller of the replaced stage`() = runTest {
        val world = world("valueTails", """
            @State
            suspend fun countdown(n: Int): Int {
                log("n ${'$'}n")
                await("c${'$'}n")
                if (n == 0) return 100
                return countdown(n - 1)
            }

            @State
            suspend fun main() {
                val value = countdown(2)
                log("value ${'$'}value")
            }
        """)
        val story = world.launch("main")
        world.release("c2")
        assertEquals("main > countdown", story.state.replace(Regex("@[^ ]+"), ""))

        world.restart()
        world.release("c1")
        world.release("c0")
        assertEquals(listOf("n 2", "n 1", "n 1", "n 0", "value 100"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `saved variables belong to their run and survive a restart`() = runTest {
        val world = world("variables", """
            var trust by saved(0)

            @State
            suspend fun main() {
                trust += 1
                checkpoint("counted")
                log("trust ${'$'}trust")
                await("x")
                trust += 10
                log("trust ${'$'}trust")
            }
        """)
        world.launch("main", key = "a")
        world.launch("main", key = "b")
        assertEquals(listOf("trust 1", "trust 1"), StageTrace.take())

        world.restart()
        assertEquals(listOf("trust 1", "trust 1"), StageTrace.take())

        world.release("x")
        assertEquals(listOf("trust 11", "trust 11"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a launched lambda is a stage that keeps what it captured`() = runTest {
        val world = world("lambdas", $$"""
            @State
            suspend fun meet(who: String) {
                log("meet $who")
                await("m")
            }

            fun start(runs: StoryRuns, who: String) {
                val greeting = "hello"
                runs.launch("greet") {
                    log("$greeting $who")
                    meet(who)
                    log("bye $who")
                }
            }
        """
        )
        world.call("start", world.runs, "alex")
        assertEquals(listOf("hello alex", "meet alex"), StageTrace.take())

        world.restart()
        assertEquals(listOf("meet alex"), StageTrace.take())

        world.release("m")
        assertEquals(listOf("bye alex"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a stage lambda passed to a stage resumes inside its call`() = runTest {
        val world = world("lambdaArguments", """
            @State
            suspend fun twice(body: @State suspend () -> Unit) {
                body()
                log("middle")
                body()
            }

            @State
            suspend fun step() {
                log("step")
                await("w")
            }

            @State
            suspend fun main() {
                twice { step() }
                log("done")
            }
        """)
        world.launch("main")
        world.restart()
        assertEquals(listOf("step", "step"), StageTrace.take())

        world.release("w")
        assertEquals(listOf("middle", "step", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `branches run alongside, keep their results across a restart and end with their stage`() = runTest {
        val world = world("branches", $$"""
            @State
            suspend fun work(name: String): Int {
                log("work $name")
                await(name)
                return name.length
            }

            @State
            suspend fun ambient() {
                log("ambient")
                await("never")
            }

            @State
            suspend fun scene() {
                async("ambient") { ambient() }
                await("scene")
            }

            @State
            suspend fun main() {
                val a = async("a") { work("a") }
                val bb = async("bb") { work("bb") }
                log("sum ${a.await() + bb.await()}")
                scene()
                log("after scene")
                await("end")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("work a", "work bb"), StageTrace.take())

        world.release("a")
        world.restart()
        assertEquals(listOf("work bb"), StageTrace.take(), "the finished branch keeps its result, the other one resumes")

        world.release("bb")
        assertEquals(listOf("sum 3", "ambient"), StageTrace.take())
        assertTrue(world.save().getCompound("main").getCompound("children").contains("ambient"))

        world.release("scene")
        assertEquals(listOf("after scene"), StageTrace.take())
        assertFalse(world.save().getCompound("main").getCompound("children").contains("ambient"), "the branch ended with scene")
        world.stop()
    }

    @Test
    fun `a recompiled script resumes where its sites still exist and starts over where they do not`() = runTest {
        val first = compiler.compile("versioned", """
            @State
            suspend fun wait() = await("f")

            @State
            suspend fun main() {
                log("one")
                wait()
                log("two")
            }
        """)
        val world = StageWorld(this, first)
        world.launch("main")
        assertEquals(listOf("one"), StageTrace.take())

        world.restart(compiler.compile("versioned", """
            @State
            suspend fun wait() = await("f")

            @State
            suspend fun main() {
                log("one, edited")
                log("more")
                wait()
                log("two, edited")
            }
        """))
        assertEquals(emptyList(), StageTrace.take(), "the call to wait is still there, so main resumes inside it")

        world.restart(compiler.compile("versioned", """
            @State
            suspend fun wait() = await("f")

            @State
            suspend fun main() {
                log("rewritten")
                await("f2")
            }
        """))
        assertEquals(listOf("rewritten"), StageTrace.take(), "the call is gone, so main starts over")
        world.stop()
    }

    @Test
    fun `a stage entered from ordinary code is resumed only with the arguments it was saved with`() = runTest {
        val world = world("helpers", """
            @State
            suspend fun inner(name: String) {
                log("inner ${'$'}name")
                checkpoint("in")
                await("i")
            }

            suspend fun helper() {
                log("helper")
                inner(StageTrace.token)
            }

            @State
            suspend fun main() {
                helper()
                log("after")
            }
        """)
        StageTrace.token = "a"
        world.launch("main")
        assertEquals(listOf("helper", "inner a"), StageTrace.take())

        world.restart()
        assertEquals(listOf("helper"), StageTrace.take(), "the same call adopts the saved frame and resumes after the checkpoint")

        StageTrace.token = "b"
        world.restart()
        assertEquals(listOf("helper", "inner b"), StageTrace.take(), "another argument means another call, which starts over")

        world.release("i")
        assertEquals(listOf("after"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a story waits for the script it runs in`() = runTest {
        val world = world("waits", """
            @State
            suspend fun main() {
                log("start")
                checkpoint("started")
                log("resumed")
                await("x")
            }
        """)
        world.launch("main")
        assertEquals(listOf("start", "resumed"), StageTrace.take())

        world.restart(startScript = false)
        assertEquals(emptyList(), StageTrace.take(), "nothing runs without the script")

        world.startScript()
        assertEquals(listOf("resumed"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `serializable classes of the script are saved with the frame`() = runTest {
        val world = world("serializable", """
            @kotlinx.serialization.Serializable
            data class Loot(val name: String, val count: Int)

            @State
            suspend fun main() {
                val loot = Loot("gold", 3)
                checkpoint("looted")
                log(loot.toString())
                await("x")
            }
        """)
        world.launch("main")
        world.restart()
        assertEquals(listOf("Loot(name=gold, count=3)", "Loot(name=gold, count=3)"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `launching a running key returns that run and stopping it ends it`() = runTest {
        val world = world("keys", """
            fun start(runs: StoryRuns) {
                runs.launch("quest") {
                    log("start")
                    await("x")
                }
            }
        """)
        world.call("start", world.runs)
        world.call("start", world.runs)
        assertEquals(listOf("start"), StageTrace.take())

        world.runs.stop("quest")
        world.settle()
        assertTrue(world.runs.keys.isEmpty())
        world.call("start", world.runs)
        assertEquals(listOf("start"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a saved story can be read back after being written out`() = runTest {
        val world = world("stored", """
            @State
            suspend fun main() {
                val items = listOf("sword", "shield")
                checkpoint("armed")
                log(items.joinToString())
                await("x")
            }
        """)
        world.launch("main")
        val saved = world.save().getCompound("main")
        val frame = saved.getList("frames", 10).getCompound(0)
        assertEquals("main", frame.getString("stage"))
        assertEquals("after:armed", frame.getString("position"))
        assertTrue(frame.getCompound("locals").contains("items"))
        world.stop()
    }

    @Test
    fun `a cancellation that is not the story's own fails the story instead of putting it to sleep`() = runTest {
        val world = world("preempted", """
            @State
            suspend fun main() {
                walk()
                log("after")
            }

            @State
            suspend fun walk() {
                log("walking")
                await("path")
            }
        """)
        val handle = world.launch("main")
        StageTrace.cancel("path")
        world.settle()
        assertEquals(listOf("walking"), StageTrace.take())
        assertFalse(handle.isActive)
        assertTrue(world.runs.keys.isEmpty(), "a failed story is not saved to wake up with nothing to run")
        world.stop()
    }
}
