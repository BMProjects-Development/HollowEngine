package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import kotlin.test.*

class StoryInstancesTest {
    @StageScript("test/imported.kts")
    class Imported

    @StageScript("test/root.kts")
    class Root(val imported: Imported)

    @StageScript("test/construction.kts")
    class StartsDuringConstruction(runs: StoryRuns) {
        var observedReady: Boolean? = null
            private set

        init {
            runs.launch("construction", StageLambda0<Unit>(StoryInstances.keyOf(this), "start", CompoundTag()))
        }

        private val ready = true

        @StageEntry("start")
        suspend fun start(args: CompoundTag): Any {
            observedReady = ready
            return Unit
        }
    }

    @AfterTest
    fun clear() {
        StoryWaits.clear()
        StoryInstances.clear()
    }

    @Test
    fun `stopping a script unregisters the imported instances as well`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val root = Root(Imported())
        StoryInstances.register(root, scope)
        val imported = StoryInstances.keyOf(root.imported)
        assertSame(root.imported, StoryInstances.find(imported))
        scope.cancel()
        assertNull(StoryInstances.find(imported))
        assertNull(StoryInstances.find("test/root.kts"))
    }

    @Test
    fun `a shared imported instance stays available until its last scope stops`() {
        val first = CoroutineScope(Dispatchers.Unconfined + Job())
        val second = CoroutineScope(Dispatchers.Unconfined + Job())
        val imported = Imported()
        StoryInstances.register(Root(imported), first)
        StoryInstances.register(Root(imported), second)
        val lifetime = StoryInstances.jobOf("test/imported.kts")!!
        first.cancel()
        assertSame(imported, StoryInstances.find("test/imported.kts"))
        assertFalse(lifetime.isCancelled)
        second.cancel()
        assertNull(StoryInstances.find("test/imported.kts"))
        assertTrue(lifetime.isCancelled)
    }

    @Test
    fun `stopping an old script does not unregister its replacement`() {
        val oldScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val newScope = CoroutineScope(Dispatchers.Unconfined + Job())
        val old = Root(Imported())
        val replacement = Root(Imported())
        StoryInstances.register(old, oldScope)
        StoryInstances.register(replacement, newScope)
        oldScope.cancel()
        assertSame(replacement, StoryInstances.find("test/root.kts"))
        assertSame(replacement.imported, StoryInstances.find("test/imported.kts"))
        newScope.cancel()
    }

    @Test
    fun `a story launched by a script constructor waits until the script is fully initialized`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val owner = object : StoryOwner {
            override val scope = scope
            override val server: MinecraftServer get() = error("no server")
            override val entity: Entity? get() = null
            override fun changed(runs: StoryRuns) = Unit
        }
        val script = StartsDuringConstruction(StoryRuns(owner))
        assertNull(script.observedReady)
        StoryInstances.register(script, scope)
        assertEquals(true, script.observedReady)
        scope.cancel()
    }
}
