package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a saved frame finds its way back, without a compiler: the positions the generated code asks about. */
class StoryFrameTest {
    private object Owner : StoryOwner {
        override val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
        override val server: MinecraftServer get() = error("no server")
        override val entity: Entity? get() = null
        override fun changed(runs: StoryRuns) = Unit
    }

    private val sites = arrayOf("intro#1", "if#1.then/trial#1", "gate")

    private fun saved(position: String?, configure: CompoundTag.() -> Unit = {}): StoryRun {
        val frame = CompoundTag().apply {
            putString("instance", "quests/village.kts")
            putString("stage", "prologue")
            putInt("hash", 7)
            position?.let { putString("position", it) }
            put("args", CompoundTag().apply { put("times", IntTag.valueOf(2)) })
            put("locals", CompoundTag().apply { put("map", IntTag.valueOf(5)) })
            configure()
        }
        val tag = CompoundTag().apply { put("frames", ListTag().apply { add(frame) }) }
        return StoryRun.load(StoryRuns(Owner), "quest", null, tag)
    }

    @Test
    fun `a frame saved inside a call resumes into that call only`() {
        val run = saved("call:if#1.then/trial#1")
        val frame = run.pending.removeFirst().adopt(7, sites)
        assertTrue(frame.isResuming())
        assertTrue(frame.isResumingAt(2))
        assertTrue(frame.enters(2, 3))
        assertFalse(frame.enters(0, 1), "code before the call is skipped")
        assertTrue(frame.resumesInto(2, 5))
        assertEquals(5, StoryValues.decode(storySerializer<Int>(), frame.locals.get("map")!!))
    }

    @Test
    fun `a frame saved after a checkpoint resumes after it`() {
        val frame = saved("after:gate").pending.removeFirst().adopt(9, sites)
        assertTrue(frame.isResumingAt(5))
    }

    @Test
    fun `a frame whose position is gone starts over without its locals`() {
        val frame = saved("after:removed#1").pending.removeFirst().adopt(7, sites)
        assertFalse(frame.isResuming())
        assertTrue(frame.enters(0, 1))
        assertTrue(frame.locals.isEmpty)
    }

    @Test
    fun `a frame entered with other arguments starts over`() {
        val frame = saved("after:gate").pending.removeFirst().adopt(7, sites)
        frame.saveArg("times", storySerializer<Int>(), 2)
        assertTrue(frame.isResuming(), "the same arguments keep the saved position")
        frame.saveArg("times", storySerializer<Int>(), 3)
        assertFalse(frame.isResuming())
    }

    @Test
    fun `a run keeps its frames, branches and variables through a save`() {
        val run = saved("after:gate") {
            put("segment", CompoundTag().apply { putBoolean("done", true) })
        }
        val tag = CompoundTag().apply {
            put("frames", ListTag().apply { run.pending.forEach { add(it.save()) } })
            put("children", CompoundTag().apply { put("merchant", saved(null).save()) })
            put("vars", CompoundTag().apply { putInt("quests/village.kts/trust", 3) })
        }
        val restored = StoryRun.load(StoryRuns(Owner), "quest", null, tag)
        assertEquals("prologue@after:gate", restored.describe())
        assertEquals(setOf("merchant"), restored.children.keys)
        assertEquals("quest/merchant", restored.children.getValue("merchant").path)
        assertEquals(3, restored.vars.getInt("quests/village.kts/trust"))
        assertEquals(restored.vars, restored.children.getValue("merchant").vars, "branches share the variables of their story")
        assertTrue(restored.pending.single().segment.getBoolean("done"))
    }

    @Test
    fun `a saved tree is a snapshot independent of the working frames`() {
        val run = saved("after:gate")
        val frame = run.pending.single()
        val snapshot = run.saveTree()
        val expected = snapshot.copy()
        frame.locals.putInt("map", 99)
        frame.args.putInt("times", 99)
        frame.segment.putBoolean("changed", true)
        assertEquals(expected, snapshot)
    }

    @Test
    fun `restoring a tree does not let the run mutate the checkpoint`() {
        val snapshot = saved("after:gate").saveTree()
        val expected = snapshot.copy()
        val run = StoryRun.load(StoryRuns(Owner), "quest", null, snapshot)
        val frame = run.pending.single()
        frame.locals.putInt("map", 99)
        frame.args.putInt("times", 99)
        frame.segment.putBoolean("changed", true)
        assertEquals(expected, snapshot)
    }
}
