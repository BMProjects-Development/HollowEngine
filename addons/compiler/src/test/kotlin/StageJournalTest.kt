import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Actions in the journal: what is undone when, rewinds to checkpoints, labels, a restart in between. */
class StageJournalTest : StageTest() {
    @Test
    fun `a temporary action stands until the next checkpoint of its run and a permanent one stays`() = runTest {
        val world = world("temporary", PLACE + $$"""
            @State
            suspend fun fight() {
                temporary { place("barricade") }
                place("banner")
                await("win")
            }

            @State
            suspend fun main() {
                fight()
                log("after ${StageTrace.blocks}")
                checkpoint("won")
                log("passed ${StageTrace.blocks}")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("place barricade", "place banner"), StageTrace.take())

        world.release("win")
        assertEquals(listOf("after [barricade, banner]", "undo barricade", "passed [banner]"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a temporary action until a named checkpoint goes when any run of the story passes it`() = runTest {
        val world = world("until", PLACE + $$"""
            @State
            suspend fun main() {
                temporary(until = "defeated") { place("gate") }
                checkpoint("arena")
                log("arena ${StageTrace.blocks}")
                async("boss") {
                    await("kill")
                    checkpoint("defeated")
                    log("defeated ${StageTrace.blocks}")
                }
                await("end")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("place gate", "arena [gate]"), StageTrace.take(), "another checkpoint leaves it standing")

        world.release("kill")
        assertEquals(listOf("undo gate", "defeated []"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a segment that repeats after a restart undoes its temporary actions first`() = runTest {
        val world = world("repeat", PLACE + """
            @State
            suspend fun main() {
                temporary { place("barricade") }
                await("x")
                log("done")
            }
        """
        )
        world.launch("main")
        world.restart()
        assertEquals(listOf("place barricade", "undo barricade", "place barricade"), StageTrace.take())

        world.release("x")
        assertEquals(listOf("done", "undo barricade"), StageTrace.take())
        assertEquals(emptySet(), StageTrace.blocks)
        world.stop()
    }

    @Test
    fun `a rewind to a checkpoint undoes what came after it and keeps what once did`() = runTest {
        val world = world("rewind", PLACE + """
            @State
            suspend fun main() {
                place("door")
                checkpoint("gate")
                once("reward") { log("reward") }
                place("bridge")
                await("x")
                log("end")
            }
        """
        )
        val story = world.launch("main")
        assertEquals(listOf("place door", "reward", "place bridge"), StageTrace.take())

        story.rewind("gate")
        world.settle()
        assertEquals(listOf("undo bridge", "place bridge"), StageTrace.take(), "the reward is not given twice")

        world.release("x")
        assertEquals(listOf("end"), StageTrace.take())
        assertEquals(setOf("door", "bridge"), StageTrace.blocks)
        world.stop()
    }

    @Test
    fun `rewind without a name goes back to the latest checkpoint, past the stage calls after it`() = runTest {
        val world = world("latest", PLACE + """
            @State
            suspend fun step() = log("step")

            @State
            suspend fun main() {
                place("door")
                checkpoint("gate")
                step()
                place("bridge")
                if (StageTrace.token == "fail") {
                    StageTrace.token = ""
                    rewind()
                }
                log("done")
            }
        """
        )
        StageTrace.token = "fail"
        world.launch("main")
        assertEquals(
            listOf("place door", "step", "place bridge", "undo bridge", "step", "place bridge", "done"),
            StageTrace.take(),
        )
        world.stop()
    }

    @Test
    fun `rewind with no checkpoint before it starts its run over`() = runTest {
        val world = world("segment", PLACE + """
            @State
            suspend fun attempt() {
                place("trap")
                if (StageTrace.token == "fail") {
                    StageTrace.token = ""
                    rewind()
                }
                log("survived")
            }

            @State
            suspend fun main() {
                attempt()
                log("done")
            }
        """
        )
        StageTrace.token = "fail"
        world.launch("main")
        assertEquals(listOf("place trap", "undo trap", "place trap", "survived", "done"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `labels put back what they captured on every rewind to their checkpoint`() = runTest {
        val world = world("labels", PLACE + """
            @Action
            suspend fun CheckpointScope.flag(name: String) = label {
                val had = capture { name in StageTrace.blocks }
                undo { if (had) StageTrace.blocks += name else StageTrace.blocks -= name }
            }

            @State
            suspend fun main() {
                StageTrace.blocks += "flag"
                checkpoint("arena") { flag("flag") }
                await("drop")
                StageTrace.blocks -= "flag"
                log("dropped")
                await("x")
            }
        """
        )
        val story = world.launch("main")
        repeat(2) { round ->
            world.release("drop")
            assertEquals(listOf("dropped"), StageTrace.take())
            assertEquals(emptySet(), StageTrace.blocks)

            StageTrace.hold("drop")
            story.rewind("arena")
            world.settle()
            assertEquals(setOf("flag"), StageTrace.blocks, "round $round puts the flag back")
        }
        world.stop()
    }

    @Test
    fun `the journal outlives a restart and still undoes what was performed before it`() = runTest {
        val world = world("restart", PLACE + """
            @State
            suspend fun main() {
                place("door")
                checkpoint("placed")
                await("x")
            }
        """
        )
        world.launch("main")
        world.restart()
        assertEquals(listOf("place door"), StageTrace.take())

        world.runs["main"]!!.rewind("main")
        world.settle()
        assertEquals(listOf("undo door", "place door"), StageTrace.take(), "the story starts over from its entry")
        world.stop()
    }

    @Test
    fun `a rewind to before a branch removes the branch along with what it performed`() = runTest {
        val world = world("branches", PLACE + """
            @State
            suspend fun main() {
                checkpoint("before")
                async("guard") {
                    place("tower")
                    await("g")
                }
                place("wall")
                await("x")
            }
        """
        )
        val story = world.launch("main")
        assertEquals(listOf("place wall", "place tower"), StageTrace.take())

        story.rewind("before")
        world.settle()
        assertEquals(listOf("undo tower", "undo wall", "place wall", "place tower"), StageTrace.take())
        assertEquals(listOf("main", "before", "guard"), story.checkpoints.map { it.first })
        world.stop()
    }

    @Test
    fun `remember keeps its value across a rewind`() = runTest {
        val world = world("remember", $$"""
            @State
            suspend fun main() {
                checkpoint("start")
                val roll = remember("roll") { StageTrace.token }
                log("roll $roll")
                await("x")
            }
        """
        )
        StageTrace.token = "first"
        val story = world.launch("main")
        StageTrace.token = "second"
        story.rewind("start")
        world.settle()
        assertEquals(listOf("roll first", "roll first"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `an unsaved parameter gets its default when the journal calls the action again`() = runTest {
        val world = world("unsaved", $$"""
            @Action
            suspend fun mark(name: String, @Unsaved note: String = "default") = action {
                undo { log("undo $name $note") }
                perform { log("mark $name $note") }
            }

            @State
            suspend fun main() {
                temporary { mark("a", "given") }
                log("done")
            }
        """
        )
        world.launch("main")
        assertEquals(listOf("mark a given", "done", "undo a default"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `a branch takes back its temporary actions when the stage that started it returns`() = runTest {
        val world = world("branchTemporary", PLACE + $$"""
            @State
            suspend fun fight() {
                async("guards") {
                    temporary { place("guard") }
                    await("never")
                }
                await("win")
            }

            @State
            suspend fun main() {
                fight()
                log("after ${StageTrace.blocks}")
            }
        """
        )
        world.launch("main")
        world.release("win")
        assertEquals(listOf("place guard", "undo guard", "after []"), StageTrace.take())
        world.stop()
    }

    @Test
    fun `rewind in a branch takes back the branch alone`() = runTest {
        val world = world("branchRewind", PLACE + """
            @State
            suspend fun main() {
                place("wall")
                async("guard") {
                    place("tower")
                    checkpoint("posted")
                    place("flag")
                    if (StageTrace.token == "fail") {
                        StageTrace.token = ""
                        rewind()
                    }
                    await("g")
                }
                await("x")
            }
        """
        )
        StageTrace.token = "fail"
        world.launch("main")
        assertEquals(listOf("place wall", "place tower", "place flag", "undo flag", "place flag"), StageTrace.take())
        assertEquals(setOf("wall", "tower", "flag"), StageTrace.blocks)
        world.stop()
    }

    private companion object {
        /** An action that puts [name] into the world and takes it back unless someone else did it first. */
        const val PLACE = $$"""
            @Action
            suspend fun place(name: String) = action {
                val existed = capture { name in StageTrace.blocks }
                undo {
                    if (name !in StageTrace.blocks) conflict("$name is gone")
                    if (!existed) StageTrace.blocks -= name
                    log("undo $name")
                }
                fastForward {
                    StageTrace.blocks += name
                    log("forward $name")
                }
                perform {
                    StageTrace.blocks += name
                    log("place $name")
                }
            }

        """
    }
}
