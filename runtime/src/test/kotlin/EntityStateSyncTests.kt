import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.sync.EntityStateSync
import ru.hollowhorizon.hollowengine.common.attachments.sync.EntityStateSyncPacket
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two rules that decide what goes over the wire and what is applied on arrival. Both used to be
 * absent: every change resent every component, and batches were applied in whatever order they landed.
 */
class EntityStateSyncTests {
    private val model = "test:model".rl
    private val animator = "test:animator".rl

    private fun batch(
        current: Map<ResourceLocation, Any>,
        previous: Map<ResourceLocation, Any>,
    ) = EntityStateSync.batchOf(current, previous)

    private fun data(vararg entries: Pair<String, Int>) = CompoundTag().apply {
        entries.forEach { (name, value) -> putInt(name, value) }
    }

    @Test
    fun `a data delta carries only what changed`() {
        val batch = EntityStateSync.dataBatchOf(
            current = data("stage" to 2, "gold" to 7),
            previous = data("stage" to 1, "gold" to 7, "escort" to 1),
        )

        assertEquals(setOf("stage"), batch.changed.allKeys)
        assertEquals(listOf("escort"), batch.removed)
        assertFalse(batch.isEmpty)
    }

    @Test
    fun `a key that left the snapshot is removed`() {
        val batch = EntityStateSync.dataBatchOf(current = CompoundTag(), previous = data("stage" to 1))

        assertTrue(batch.changed.isEmpty)
        assertEquals(listOf("stage"), batch.removed)
    }

    @Test
    fun `an unchanged document produces no batch`() {
        assertTrue(EntityStateSync.dataBatchOf(data("stage" to 1), data("stage" to 1)).isEmpty)
    }

    @Test
    fun `an unchanged component is not resent`() {
        val result = batch(
            current = mapOf(model to "player.gltf", animator to "wave"),
            previous = mapOf(model to "player.gltf", animator to "idle"),
        )

        assertEquals(setOf(animator), result.changed.keys)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `a dropped component is reported as removed`() {
        val result = batch(
            current = mapOf(model to "player.gltf"),
            previous = mapOf(model to "player.gltf", animator to "wave"),
        )

        assertTrue(result.changed.isEmpty())
        assertEquals(listOf(animator), result.removed)
    }

    @Test
    fun `a first batch carries everything`() {
        val result = batch(
            current = mapOf(model to "player.gltf", animator to "idle"),
            previous = emptyMap(),
        )

        assertEquals(setOf(model, animator), result.changed.keys)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `nothing to say produces an empty batch`() {
        val components = mapOf<ResourceLocation, Any>(model to "player.gltf")

        assertTrue(batch(components, components).isEmpty)
    }

    @Test
    fun `a delta must be strictly newer than what was applied`() {
        assertTrue(EntityStateSync.shouldApply(version = 5, applied = 4, full = false))
        assertFalse(EntityStateSync.shouldApply(version = 4, applied = 4, full = false))
        assertFalse(EntityStateSync.shouldApply(version = 3, applied = 4, full = false))
    }

    @Test
    fun `a baseline applies at an equal version`() {
        assertTrue(EntityStateSync.shouldApply(version = 0, applied = 0, full = true))
        assertTrue(EntityStateSync.shouldApply(version = 7, applied = 7, full = true))
        assertTrue(EntityStateSync.shouldApply(version = 8, applied = 7, full = true))
    }

    @Test
    fun `a stale baseline is still dropped`() {
        assertFalse(EntityStateSync.shouldApply(version = 6, applied = 7, full = true))
    }

    private fun deferred() = EntityStateSync.DeferredBatches(java.lang.ref.WeakReference(null))

    private fun delta(version: Long) = EntityStateSyncPacket(entityId = 1, version = version)

    private fun baseline(version: Long) =
        EntityStateSyncPacket(entityId = 1, version = version, full = true)

    @Test
    fun `parked batches come back in arrival order`() {
        val batches = deferred()
        batches.enqueue(delta(1))
        batches.enqueue(delta(2))
        batches.enqueue(delta(3))

        assertEquals(listOf(1L, 2L, 3L), batches.drain().map { it.version })
    }

    @Test
    fun `a parked baseline discards what was queued before it`() {
        val batches = deferred()
        batches.enqueue(delta(1))
        batches.enqueue(delta(2))
        batches.enqueue(baseline(3))
        batches.enqueue(delta(4))

        assertEquals(listOf(3L, 4L), batches.drain().map { it.version })
    }

    @Test
    fun `draining empties the queue so batches are never applied twice`() {
        val batches = deferred()
        batches.enqueue(delta(1))

        assertEquals(1, batches.drain().size)
        assertTrue(batches.drain().isEmpty())
        assertEquals(0, batches.size)
    }
}
