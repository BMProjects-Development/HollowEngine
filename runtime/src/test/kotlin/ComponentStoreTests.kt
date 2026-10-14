import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.api.ComponentStore
import ru.hollowhorizon.hollowengine.common.attachments.api.withoutLooseOnDeath
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mutations must reach both the backing map and the notification used by entity synchronization,
 * including changes through collection views.
 */
class ComponentStoreTests {
    private val keptId = "test:store_kept".rl
    private val looseId = "test:store_loose".rl

    @BeforeEach
    fun registerDescriptors() {
        if (ComponentDescriptorRegistry.descriptorOrNull(keptId) == null) {
            ComponentDescriptorRegistry.register(
                ComponentDescriptor(
                    id = keptId,
                    value = KeptComponent::class,
                    serializer = KeptComponent.serializer(),
                )
            )
        }
        if (ComponentDescriptorRegistry.descriptorOrNull(looseId) == null) {
            ComponentDescriptorRegistry.register(
                ComponentDescriptor(
                    id = looseId,
                    value = DroppedComponent::class,
                    serializer = DroppedComponent.serializer(),
                    persistencePolicy = ComponentPersistencePolicy.LOOSE_ON_DEATH,
                )
            )
        }
    }

    @AfterEach
    fun cleanup() {
        ComponentDescriptorRegistry.unregisterDescriptor(keptId)
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)
    }

    @Test
    fun `map writes publish changes while reads and absent removals do not`() {
        val store = ComponentStore()
        val map = store.asMutableMap()
        var changes = 0
        store.onChange = { changes++ }

        map[keptId] = KeptComponent("first")
        assertEquals(1, changes)
        assertFalse(store.isEmpty)
        assertEquals(KeptComponent("first"), store.readOnly[keptId])

        val previous = map.put(keptId, KeptComponent("second"))
        assertEquals(KeptComponent("first"), previous)
        assertEquals(1, store.readOnly.size)
        assertEquals(KeptComponent("second"), store.readOnly[keptId])
        assertEquals(2, changes)

        val removed = map.remove(keptId)
        assertEquals(KeptComponent("second"), removed)
        assertTrue(store.isEmpty)
        assertEquals(3, changes)
        assertNull(map.remove(keptId))
        assertTrue(store.copyOf().isEmpty())
        assertEquals(3, changes, "reads and a missing removal must not publish changes")

        map[keptId] = KeptComponent("cleared")
        map.clear()
        assertTrue(store.isEmpty)
        assertEquals(5, changes)
    }

    @Test
    fun `keys removeIf reaches the backing map`() {
        val store = ComponentStore()
        val map = store.asMutableMap()
        map[keptId] = KeptComponent("keep")
        map[looseId] = DroppedComponent("drop")
        var changes = 0
        store.onChange = { changes++ }

        map.keys.removeIf { it == looseId }

        assertEquals(setOf(keptId), store.readOnly.keys)
        assertEquals(1, changes)
    }

    @Test
    fun `setValue through the entry set reaches the backing map`() {
        val store = ComponentStore()
        val map = store.asMutableMap()
        map[keptId] = KeptComponent("before")
        var changes = 0
        store.onChange = { changes++ }

        map.entries.single { it.key == keptId }.setValue(KeptComponent("after"))

        assertEquals(KeptComponent("after"), store.readOnly[keptId])
        assertEquals(1, changes)
    }

    @Test
    fun `replaceAll swaps the whole set of components`() {
        val store = ComponentStore()
        store.asMutableMap()[keptId] = KeptComponent("old")
        var changes = 0
        store.onChange = { changes++ }

        store.replaceAll(listOf(DroppedComponent("new")))

        assertEquals(setOf(looseId), store.readOnly.keys)
        assertEquals(1, changes)
    }

    @Test
    fun `withoutLooseOnDeath keeps only persistent components`() {
        val store = ComponentStore()
        store.replaceAll(listOf(KeptComponent("keep"), DroppedComponent("drop")))

        val survivors = store.copyOf().withoutLooseOnDeath()

        assertEquals(setOf(keptId), survivors.keys)
        assertEquals(KeptComponent("keep"), survivors[keptId])
    }

    @Test
    fun `copyOf does not alias the store`() {
        val store = ComponentStore()
        store.replaceAll(listOf(KeptComponent("original")))

        val copy = store.copyOf()
        store.asMutableMap()[keptId] = KeptComponent("changed")

        assertEquals(KeptComponent("original"), copy[keptId])
        assertEquals(KeptComponent("changed"), store.readOnly[keptId])
    }

    @Test
    fun `clear empties the store`() {
        val store = ComponentStore()
        store.replaceAll(listOf(KeptComponent("a"), DroppedComponent("b")))
        var changes = 0
        store.onChange = { changes++ }

        store.clear()

        assertTrue(store.isEmpty)
        assertEquals(1, changes)
    }
}

@Serializable
private data class KeptComponent(val value: String)

@Serializable
private data class DroppedComponent(val value: String)
