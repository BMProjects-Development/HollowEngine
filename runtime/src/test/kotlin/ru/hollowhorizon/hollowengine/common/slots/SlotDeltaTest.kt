package ru.hollowhorizon.hollowengine.common.slots

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.*

/**
 * Tests for what a slot's before/after pair means.
 *
 * `allowInsert`/`allowExtract` decide whether a click is allowed and `afterInsert`/`afterExtract` carry the
 * effects, both driven off this split. Reporting both directions for an ordinary one-item change would fire
 * a reward handler on every withdrawal and a consumption handler on every deposit, so it has to be exact.
 */
class SlotDeltaTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `filling an empty slot only inserts`() {
        val delta = SlotDelta.between(ItemStack.EMPTY, ItemStack(Items.APPLE, 3))

        assertEquals(3, delta.inserted?.count)
        assertNull(delta.extracted)
    }

    @Test
    fun `emptying a slot only extracts`() {
        val delta = SlotDelta.between(ItemStack(Items.APPLE, 3), ItemStack.EMPTY)

        assertNull(delta.inserted)
        assertEquals(3, delta.extracted?.count)
    }

    @Test
    fun `growing a stack inserts only the difference`() {
        val delta = SlotDelta.between(ItemStack(Items.APPLE, 5), ItemStack(Items.APPLE, 6))

        assertEquals(1, delta.inserted?.count)
        assertEquals(Items.APPLE, delta.inserted?.item)
        assertNull(delta.extracted)
    }

    @Test
    fun `shrinking a stack extracts only the difference`() {
        val delta = SlotDelta.between(ItemStack(Items.APPLE, 5), ItemStack(Items.APPLE, 2))

        assertNull(delta.inserted)
        assertEquals(3, delta.extracted?.count)
    }

    @Test
    fun `an unchanged slot reports neither direction`() {
        val delta = SlotDelta.between(ItemStack(Items.APPLE, 5), ItemStack(Items.APPLE, 5))

        assertNull(delta.inserted)
        assertNull(delta.extracted)
    }

    @Test
    fun `a swap is the one case that reports both`() {
        val delta = SlotDelta.between(ItemStack(Items.APPLE, 2), ItemStack(Items.CARROT, 1))

        assertEquals(Items.CARROT, delta.inserted?.item)
        assertEquals(Items.APPLE, delta.extracted?.item)
    }

    @Test
    fun `the reported stacks are copies of their own`() {
        val before = ItemStack(Items.APPLE, 4)
        val after = ItemStack(Items.CARROT, 1)
        val delta = SlotDelta.between(before, after)

        assertNotNull(delta.extracted).grow(10)
        assertNotNull(delta.inserted).grow(10)

        // A handler mutating what it was handed must not reach the slot contents.
        assertEquals(4, before.count)
        assertEquals(1, after.count)
    }
}
