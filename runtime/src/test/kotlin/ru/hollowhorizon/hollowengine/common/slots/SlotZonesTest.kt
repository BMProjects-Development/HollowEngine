package ru.hollowhorizon.hollowengine.common.slots

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.*

/**
 * Tests for the declaration side: what a `slots { }` block turns into.
 *
 * The demo scripts under `run/hollowengine/scripts` are only compiled in game, so nothing else checks that
 * this DSL still produces the flat index space, ring and predictability flags the client is sent.
 */
class SlotZonesTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun build(block: SlotZonesBuilder.() -> Unit) = SlotZonesBuilder().apply(block).buildLayout()

    @Test
    fun `zones are laid out back to back in declaration order`() {
        val layout = build {
            zone("a", SimpleSlotSource(4))
            zone("b", SimpleSlotSource(9))
        }

        assertEquals(13, layout.totalSize)
        assertEquals(0, layout.zone("a")!!.offset)
        assertEquals(4, layout.zone("b")!!.offset)
        assertEquals(listOf("a", "b"), layout.ring)
        assertEquals(4, layout.flatIndex("b", 0))
        assertEquals(-1, layout.flatIndex("b", 9))
        assertEquals(-1, layout.flatIndex("missing", 0))
    }

    @Test
    fun `a zone inherits its stack limit from the source`() {
        val singleItem = object : SlotSource {
            private val backing = SimpleSlotSource(2)
            override val size: Int get() = backing.size
            override fun stackLimit(slot: Int): Int = 1
            override fun get(slot: Int) = backing[slot]
            override fun set(slot: Int, stack: ItemStack) {
                backing[slot] = stack
            }
        }

        val layout = build { zone("gear", singleItem) }

        assertEquals(1, layout.rulesAt(0).stackLimit)
    }

    @Test
    fun `per-slot rules declared by the source are published to the client`() {
        // Shaped like EquipmentSource: slot 0 holds one specific thing, slot 1 holds a full stack of
        // anything. Both facts have to reach the layout, or the client mispredicts and the item snaps back.
        val mixed = object : SlotSource {
            private val backing = SimpleSlotSource(2)
            override val size: Int get() = backing.size
            override fun get(slot: Int) = backing[slot]
            override fun set(slot: Int, stack: ItemStack) {
                backing[slot] = stack
            }

            override fun stackLimit(slot: Int) = if (slot == 0) 1 else DEFAULT_STACK_LIMIT
            override fun slotFilter(slot: Int) =
                if (slot == 0) SlotFilter.items(ItemStack(Items.DIAMOND)) else SlotFilter.Any
        }

        val layout = build { zone("mixed", mixed) }

        assertEquals(1, layout.rulesAt(0).stackLimit)
        assertTrue(layout.rulesAt(0).canInsert.matches(ItemStack(Items.DIAMOND)))
        assertFalse(layout.rulesAt(0).canInsert.matches(ItemStack(Items.APPLE)))

        assertEquals(DEFAULT_STACK_LIMIT, layout.rulesAt(1).stackLimit)
        assertTrue(layout.rulesAt(1).canInsert.matches(ItemStack(Items.APPLE)))
        // Still predictable: the source described its rules instead of hiding them behind canPlace.
        assertTrue(layout.isPredictable)
    }

    @Test
    fun `the equipment filter accepts only what is worn in that slot`() {
        val head = SlotFilter.equipment(EquipmentSlot.HEAD)

        assertTrue(head.matches(ItemStack(Items.IRON_HELMET)))
        assertFalse(head.matches(ItemStack(Items.IRON_BOOTS)))
        assertFalse(head.matches(ItemStack(Items.APPLE)))
        // Describable, so the client evaluates it too and an armor slot never flickers.
        assertTrue(head.isPredictable)
    }

    @Test
    fun `a zone filter and a source filter both have to pass`() {
        val diamondsOnly = object : SlotSource {
            private val backing = SimpleSlotSource(1)
            override val size: Int get() = backing.size
            override fun get(slot: Int) = backing[slot]
            override fun set(slot: Int, stack: ItemStack) {
                backing[slot] = stack
            }

            override fun slotFilter(slot: Int) = SlotFilter.items(ItemStack(Items.DIAMOND))
        }

        val layout = build {
            zone("a", diamondsOnly) { canInsert = SlotFilter.items(ItemStack(Items.APPLE)) }
        }

        assertFalse(layout.rulesAt(0).canInsert.matches(ItemStack(Items.DIAMOND)))
        assertFalse(layout.rulesAt(0).canInsert.matches(ItemStack(Items.APPLE)))
    }

    @Test
    fun `a per-slot override keeps the zone's other rules`() {
        val layout = build {
            zone("a", SimpleSlotSource(3)) {
                canExtract = SlotFilter.None
                slot(1) { canInsert = SlotFilter.items(ItemStack(Items.DIAMOND)) }
            }
        }

        val overridden = layout.rulesAt(1)
        assertTrue(overridden.canInsert.matches(ItemStack(Items.DIAMOND)))
        assertFalse(overridden.canInsert.matches(ItemStack(Items.APPLE)))
        // Untouched by the override, inherited from the zone.
        assertFalse(overridden.canExtract.matches(ItemStack(Items.DIAMOND)))
        // Its neighbour keeps the zone default.
        assertTrue(layout.rulesAt(0).canInsert.matches(ItemStack(Items.APPLE)))
    }

    @Test
    fun `named routes are kept and validated`() {
        val layout = build {
            zone("a", SimpleSlotSource(1))
            zone("b", SimpleSlotSource(1))
            zone("c", SimpleSlotSource(1))
            quickMove("a" to "c")
        }

        assertEquals(listOf("c", "b"), layout.quickMoveTargets("a").map { it.name })

        assertFailsWith<IllegalArgumentException> {
            build {
                zone("a", SimpleSlotSource(1))
                quickMove("a" to "nowhere")
            }
        }
    }

    @Test
    fun `a role of NONE drops a zone out of the ring in both directions`() {
        val layout = build {
            zone("a", SimpleSlotSource(1))
            zone("locked", SimpleSlotSource(1)) { role = SlotZoneRole.NONE }
            zone("c", SimpleSlotSource(1))
        }

        assertEquals(listOf("c"), layout.quickMoveTargets("a").map { it.name })
        assertFalse(layout.zone("locked")!!.role.canSend)
    }

    @Test
    fun `only the pre-commit checks cost the layout its prediction`() {
        val observed = build {
            zone("a", SimpleSlotSource(1)) {
                onChange { _, _ -> }
                afterInsert { _, _ -> }
                afterExtract { _, _ -> }
            }
        }
        assertTrue(observed.isPredictable)

        val guarded = build {
            zone("a", SimpleSlotSource(1)) { allowInsert { _, _ -> false } }
        }
        assertFalse(guarded.isPredictable)

        val extractGuarded = build {
            zone("a", SimpleSlotSource(1)) { allowExtract { _, _ -> false } }
        }
        assertFalse(extractGuarded.isPredictable)
    }

    @Test
    fun `a slot override cannot hand back capacity the zone or the source refused`() {
        val mixed = object : SlotSource {
            private val backing = SimpleSlotSource(2)
            override val size: Int get() = backing.size
            override fun get(slot: Int) = backing[slot]
            override fun set(slot: Int, stack: ItemStack) {
                backing[slot] = stack
            }

            override fun stackLimit(slot: Int) = if (slot == 0) 1 else DEFAULT_STACK_LIMIT
        }

        val layout = build {
            zone("a", mixed) {
                // Both slots ask for 64; neither may get it.
                slot(0) { stackLimit = DEFAULT_STACK_LIMIT }
                slot(1) { stackLimit = DEFAULT_STACK_LIMIT }
                stackLimit = 8
            }
        }

        // Slot 0 is capped by the storage, slot 1 by the zone. The override raised neither.
        assertEquals(1, layout.rulesAt(0).stackLimit)
        assertEquals(8, layout.rulesAt(1).stackLimit)
    }

    @Test
    fun `slot overrides do not depend on declaration order`() {
        fun limitOf(block: SlotZoneBuilder.() -> Unit): Int =
            build { zone("a", SimpleSlotSource(1), block) }.rulesAt(0).stackLimit

        val overrideFirst = limitOf {
            slot(0) { canExtract = SlotFilter.None }
            stackLimit = 1
        }
        val limitFirst = limitOf {
            stackLimit = 1
            slot(0) { canExtract = SlotFilter.None }
        }

        assertEquals(1, overrideFirst)
        assertEquals(limitFirst, overrideFirst)
    }

    @Test
    fun `a source that repeats a physical slot is refused`() {
        val doubled = object : SlotSource {
            private val backing = SimpleSlotSource(1)
            override val size: Int = 2
            override fun get(slot: Int) = backing[0]
            override fun set(slot: Int, stack: ItemStack) {
                backing[0] = stack
            }

            // Both indices are views of the same physical slot, as EquipmentSource would be if it were
            // handed the same EquipmentSlot twice.
            override fun storageSlotKey(slot: Int): Any = SlotStorageKey(backing, 0)
        }

        assertFailsWith<IllegalStateException> { build { zone("a", doubled) } }
    }

    @Test
    fun `a custom filter in an override also costs prediction`() {
        val layout = build {
            zone("a", SimpleSlotSource(2)) {
                slot(0) { canInsert = SlotFilter.custom { it.count > 1 } }
            }
        }

        assertFalse(layout.isPredictable)
    }

    @Test
    fun `the tighter of the zone and source stack limits wins`() {
        val plain = SimpleSlotSource(2)

        val layout = build { zone("a", plain) { stackLimit = 1 } }

        // The source allows 64; the zone asked for 1 and must not be overridden by folding the source in.
        assertEquals(1, layout.rulesAt(0).stackLimit)
        assertEquals(1, layout.rulesAt(1).stackLimit)
    }

    @Test
    fun `two zones over the same storage are refused`() {
        val shared = SimpleSlotSource(4)

        assertFailsWith<IllegalStateException> {
            build {
                zone("a", shared)
                zone("b", shared)
            }
        }

        // Ranges are checked through the storage behind them, so an overlap is caught too.
        assertFailsWith<IllegalStateException> {
            build {
                zone("a", shared.range(0, 3))
                zone("b", shared.range(2, 2))
            }
        }
    }

    @Test
    fun `neighbouring ranges over one storage are allowed`() {
        val shared = SimpleSlotSource(4)

        val layout = build {
            zone("a", shared.range(0, 2))
            zone("b", shared.range(2, 2))
        }

        assertEquals(4, layout.totalSize)
    }

    @Test
    fun `a quick-move route to the zone itself is refused`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                zone("a", SimpleSlotSource(2))
                quickMove("a" to "a")
            }
        }
    }

    @Test
    fun `a self route is never a quick-move target even if one reaches the layout`() {
        val zone = SlotZoneLayout("a", 0, 2, SlotZoneRole.BOTH, SlotRules())
        val layout = SlotLayout(listOf(zone), listOf("a"), mapOf("a" to "a"))

        assertEquals(emptyList(), layout.quickMoveTargets("a").map { it.name })
    }

    @Test
    fun `duplicate zone names and empty sources are refused`() {
        assertFailsWith<IllegalArgumentException> {
            build {
                zone("a", SimpleSlotSource(1))
                zone("a", SimpleSlotSource(1))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            build { zone("a", SimpleSlotSource(2)) { slot(5) {} } }
        }
    }
}
