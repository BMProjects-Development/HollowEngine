package ru.hollowhorizon.hollowengine.common.slots

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.test.*

/**
 * Tests for the gesture logic both sides run.
 *
 * [applyClick] is a pure function of layout, state and intent precisely so it can be pinned down here:
 * every behavior these tests describe is one the client predicts and the server enforces with the same
 * code, so a case covered here cannot drift between them.
 */
class SlotClickTest {
    @BeforeTest
    fun bootstrapRegistries() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun layout(
        vararg zones: Triple<String, Int, SlotZoneRole>,
        overrides: Map<String, String> = emptyMap(),
        rules: Map<String, SlotRules> = emptyMap(),
    ): SlotLayout {
        var offset = 0
        val built = zones.map { (name, size, role) ->
            SlotZoneLayout(name, offset, size, role, rules[name] ?: SlotRules()).also { offset += size }
        }
        return SlotLayout(built, zones.map { it.first }, overrides)
    }

    private fun SlotLayout.state(block: SlotState.() -> Unit = {}) = SlotState(totalSize).apply(block)

    @Test
    fun `left click picks up a whole stack and puts it back down`() {
        val layout = layout(Triple("a", 2, SlotZoneRole.BOTH))
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 12) }

        assertTrue(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
        assertEquals(12, state.carried.count)
        assertTrue(state[0].isEmpty)

        assertTrue(layout.applyClick(state, SlotIntent.click(1, SlotButton.LEFT)).changed)
        assertEquals(12, state[1].count)
        assertTrue(state.carried.isEmpty)
    }

    @Test
    fun `right click takes half rounding up and places one at a time`() {
        val layout = layout(Triple("a", 2, SlotZoneRole.BOTH))
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 7) }

        layout.applyClick(state, SlotIntent.click(0, SlotButton.RIGHT))
        assertEquals(4, state.carried.count)
        assertEquals(3, state[0].count)

        layout.applyClick(state, SlotIntent.click(1, SlotButton.RIGHT))
        assertEquals(1, state[1].count)
        assertEquals(3, state.carried.count)
    }

    @Test
    fun `left click merges into a matching stack and keeps the overflow on the cursor`() {
        val layout = layout(Triple("a", 1, SlotZoneRole.BOTH))
        val state = layout.state {
            this[0] = ItemStack(Items.APPLE, 60)
            carried = ItemStack(Items.APPLE, 10)
        }

        layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT))

        assertEquals(64, state[0].count)
        assertEquals(6, state.carried.count)
    }

    @Test
    fun `a swap needs the carried stack to fit the slot whole`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            rules = mapOf("a" to SlotRules(stackLimit = 1)),
        )
        val state = layout.state {
            this[0] = ItemStack(Items.IRON_HELMET)
            carried = ItemStack(Items.APPLE, 5)
        }

        assertFalse(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
        assertEquals(Items.IRON_HELMET, state[0].item)
        assertEquals(5, state.carried.count)
    }

    @Test
    fun `a filtered slot refuses what it does not accept`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            rules = mapOf("a" to SlotRules(canInsert = SlotFilter.items(ItemStack(Items.DIAMOND)))),
        )
        val state = layout.state { carried = ItemStack(Items.APPLE, 3) }

        assertFalse(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
        assertEquals(3, state.carried.count)
    }

    @Test
    fun `a slot that cannot be extracted from cannot be emptied`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            rules = mapOf("a" to SlotRules(canExtract = SlotFilter.None)),
        )
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 4) }

        assertFalse(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
        assertFalse(layout.applyClick(state, SlotIntent.quickMove(0)).changed)
        assertFalse(layout.applyClick(state, SlotIntent.drop(0, all = true)).changed)
        assertEquals(4, state[0].count)
    }

    @Test
    fun `quick move follows the ring to the next zone`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            Triple("b", 2, SlotZoneRole.BOTH),
        )
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 5) }

        assertTrue(layout.applyClick(state, SlotIntent.quickMove(0)).changed)
        assertTrue(state[0].isEmpty)
        assertEquals(5, state[1].count)
    }

    @Test
    fun `quick move tops up a partial stack before opening an empty slot`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            Triple("b", 2, SlotZoneRole.BOTH),
        )
        val state = layout.state {
            this[0] = ItemStack(Items.APPLE, 5)
            this[2] = ItemStack(Items.APPLE, 60)
        }

        layout.applyClick(state, SlotIntent.quickMove(0))

        assertTrue(state[0].isEmpty)
        assertEquals(64, state[2].count)
        assertEquals(1, state[1].count)
    }

    @Test
    fun `quick move skips a zone that will not take the item`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            Triple("filtered", 1, SlotZoneRole.BOTH),
            Triple("c", 1, SlotZoneRole.BOTH),
            rules = mapOf("filtered" to SlotRules(canInsert = SlotFilter.items(ItemStack(Items.DIAMOND)))),
        )
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 3) }

        layout.applyClick(state, SlotIntent.quickMove(0))

        assertTrue(state[1].isEmpty)
        assertEquals(3, state[2].count)
    }

    @Test
    fun `quick move ignores zones that do not receive, and slots that cannot send`() {
        val layout = layout(
            Triple("source", 1, SlotZoneRole.SOURCE),
            Triple("nope", 1, SlotZoneRole.NONE),
        )
        val state = layout.state {
            this[0] = ItemStack(Items.APPLE, 3)
            this[1] = ItemStack(Items.CARROT, 1)
        }

        assertFalse(layout.applyClick(state, SlotIntent.quickMove(0)).changed)
        assertFalse(layout.applyClick(state, SlotIntent.quickMove(1)).changed)
    }

    @Test
    fun `a named override wins over ring order`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            Triple("b", 1, SlotZoneRole.BOTH),
            Triple("c", 1, SlotZoneRole.BOTH),
            overrides = mapOf("a" to "c"),
        )
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 2) }

        layout.applyClick(state, SlotIntent.quickMove(0))

        assertTrue(state[1].isEmpty)
        assertEquals(2, state[2].count)
    }

    @Test
    fun `an even drag splits the stack and leaves the remainder on the cursor`() {
        val layout = layout(Triple("a", 3, SlotZoneRole.BOTH))
        val state = layout.state { carried = ItemStack(Items.APPLE, 7) }

        layout.applyClick(state, SlotIntent.distribute(listOf(0, 1, 2), SlotDistributeMode.EVEN))

        assertEquals(listOf(2, 2, 2), (0..2).map { state[it].count })
        assertEquals(1, state.carried.count)
    }

    @Test
    fun `a single drag places exactly one per slot`() {
        val layout = layout(Triple("a", 3, SlotZoneRole.BOTH))
        val state = layout.state { carried = ItemStack(Items.APPLE, 7) }

        layout.applyClick(state, SlotIntent.distribute(listOf(0, 1, 2), SlotDistributeMode.SINGLE))

        assertEquals(listOf(1, 1, 1), (0..2).map { state[it].count })
        assertEquals(4, state.carried.count)
    }

    @Test
    fun `a drag skips slots holding something else`() {
        val layout = layout(Triple("a", 3, SlotZoneRole.BOTH))
        val state = layout.state {
            this[1] = ItemStack(Items.CARROT, 1)
            carried = ItemStack(Items.APPLE, 4)
        }

        layout.applyClick(state, SlotIntent.distribute(listOf(0, 1, 2), SlotDistributeMode.SINGLE))

        assertEquals(1, state[0].count)
        assertEquals(Items.CARROT, state[1].item)
        assertEquals(1, state[2].count)
        assertEquals(2, state.carried.count)
    }

    @Test
    fun `dropping reports what leaves the container`() {
        val layout = layout(Triple("a", 1, SlotZoneRole.BOTH))
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 5) }

        val one = layout.applyClick(state, SlotIntent.drop(0, all = false))
        assertEquals(1, one.dropped.single().count)
        assertEquals(4, state[0].count)

        val rest = layout.applyClick(state, SlotIntent.drop(0, all = true))
        assertEquals(4, rest.dropped.single().count)
        assertTrue(state[0].isEmpty)
    }

    @Test
    fun `dropping the cursor stack empties it`() {
        val layout = layout(Triple("a", 1, SlotZoneRole.BOTH))
        val state = layout.state { carried = ItemStack(Items.APPLE, 2) }

        val result = layout.applyClick(state, SlotIntent.dropCarried(all = true))

        assertEquals(2, result.dropped.single().count)
        assertTrue(state.carried.isEmpty)
    }

    @Test
    fun `a slot limit of one rejects a second item`() {
        val layout = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            rules = mapOf("a" to SlotRules(stackLimit = 1)),
        )
        val state = layout.state { carried = ItemStack(Items.APPLE, 5) }

        layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT))
        assertEquals(1, state[0].count)
        assertEquals(4, state.carried.count)

        assertFalse(layout.applyClick(state, SlotIntent.click(0, SlotButton.RIGHT)).changed)
        assertEquals(1, state[0].count)
    }

    @Test
    fun `a custom filter makes the layout unpredictable`() {
        val open = layout(Triple("a", 1, SlotZoneRole.BOTH))
        assertTrue(open.isPredictable)

        val guarded = layout(
            Triple("a", 1, SlotZoneRole.BOTH),
            rules = mapOf("a" to SlotRules(canInsert = SlotFilter.custom { it.count > 1 })),
        )
        assertFalse(guarded.isPredictable)
    }

    @Test
    fun `a per-slot override only affects its own slot`() {
        val zone = SlotZoneLayout(
            name = "a",
            offset = 0,
            size = 2,
            rules = SlotRules(),
            overrides = listOf(SlotRuleOverride(1, SlotRules(canInsert = SlotFilter.None))),
        )
        val layout = SlotLayout(listOf(zone), listOf("a"))
        val state = SlotState(2).apply { carried = ItemStack(Items.APPLE, 2) }

        assertFalse(layout.applyClick(state, SlotIntent.click(1, SlotButton.LEFT)).changed)
        assertTrue(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
    }

    @Test
    fun `the same item on a slot that refuses it is taken onto the cursor, never merged in`() {
        val layout = layout(
            Triple("result", 1, SlotZoneRole.SOURCE),
            rules = mapOf("result" to SlotRules(canInsert = SlotFilter.None)),
        )
        for (button in SlotButton.entries) {
            val state = layout.state {
                this[0] = ItemStack(Items.BREAD, 2)
                carried = ItemStack(Items.BREAD, 5)
            }

            assertTrue(layout.applyClick(state, SlotIntent.click(0, button)).changed)
            assertEquals(7, state.carried.count)
            assertTrue(state[0].isEmpty)
        }
    }

    @Test
    fun `taking onto a cursor without room leaves both stacks untouched`() {
        val layout = layout(
            Triple("result", 1, SlotZoneRole.SOURCE),
            rules = mapOf("result" to SlotRules(canInsert = SlotFilter.None)),
        )
        val state = layout.state {
            this[0] = ItemStack(Items.BREAD, 4)
            carried = ItemStack(Items.BREAD, 64)
        }

        assertFalse(layout.applyClick(state, SlotIntent.click(0, SlotButton.LEFT)).changed)
        assertEquals(4, state[0].count)
        assertEquals(64, state.carried.count)
    }

    @Test
    fun `a double click gathers partial stacks first and stops at a full stack`() {
        val layout = layout(Triple("a", 4, SlotZoneRole.BOTH))
        val state = layout.state {
            this[0] = ItemStack(Items.APPLE, 64)
            this[1] = ItemStack(Items.APPLE, 20)
            this[2] = ItemStack(Items.BREAD, 5)
            carried = ItemStack(Items.APPLE, 30)
        }

        assertTrue(layout.applyClick(state, SlotIntent.collect(3)).changed)

        assertEquals(64, state.carried.count)
        assertEquals(50, state[0].count)
        assertTrue(state[1].isEmpty)
        assertEquals(5, state[2].count)
    }

    @Test
    fun `a double click leaves copy zones and server-guarded zones alone`() {
        var offset = 0
        val zones = listOf(
            SlotZoneLayout("player", offset, 1).also { offset += 1 },
            SlotZoneLayout("draft", offset, 1, copyOnClick = true).also { offset += 1 },
            SlotZoneLayout("result", offset, 1, predictable = false),
        )
        val layout = SlotLayout(zones, zones.map { it.name })
        val state = layout.state {
            this[1] = ItemStack(Items.APPLE, 10)
            this[2] = ItemStack(Items.APPLE, 10)
            carried = ItemStack(Items.APPLE, 1)
        }

        assertFalse(layout.applyClick(state, SlotIntent.collect(0)).changed)
        assertEquals(10, state[1].count)
        assertEquals(10, state[2].count)
    }

    @Test
    fun `a middle click clones a full stack onto an empty cursor only`() {
        val layout = layout(Triple("a", 1, SlotZoneRole.BOTH))
        val state = layout.state { this[0] = ItemStack(Items.APPLE, 3) }

        assertTrue(layout.applyClick(state, SlotIntent.clone(0)).changed)
        assertEquals(64, state.carried.count)
        assertEquals(3, state[0].count)

        assertFalse(layout.applyClick(state, SlotIntent.clone(0)).changed)
    }

    @Test
    fun `a copy zone never sends or receives a shift click`() {
        var offset = 0
        val zones = listOf(
            SlotZoneLayout("draft", offset, 1, copyOnClick = true).also { offset += 1 },
            SlotZoneLayout("player", offset, 1),
        )
        val layout = SlotLayout(zones, zones.map { it.name })
        val state = layout.state {
            this[0] = ItemStack(Items.APPLE, 5)
            this[1] = ItemStack(Items.BREAD, 5)
        }

        assertFalse(layout.applyClick(state, SlotIntent.quickMove(0)).changed)
        assertFalse(layout.applyClick(state, SlotIntent.quickMove(1)).changed)
        assertEquals(5, state[0].count)
        assertEquals(5, state[1].count)
    }

    @Test
    fun `only gestures that reach an unpredictable zone wait for the server`() {
        var offset = 0
        val zones = listOf(
            SlotZoneLayout("inputs", offset, 2).also { offset += 2 },
            SlotZoneLayout("result", offset, 1, SlotZoneRole.SOURCE, predictable = false).also { offset += 1 },
            SlotZoneLayout("player", offset, 2),
        )
        val layout = SlotLayout(zones, zones.map { it.name }, mapOf("player" to "inputs"))

        assertTrue(layout.predicts(SlotIntent.click(0, SlotButton.LEFT)))
        assertTrue(layout.predicts(SlotIntent.quickMove(3)))
        assertTrue(layout.predicts(SlotIntent.distribute(listOf(0, 3), SlotDistributeMode.EVEN)))
        assertFalse(layout.predicts(SlotIntent.click(2, SlotButton.LEFT)))
        assertFalse(layout.predicts(SlotIntent.quickMove(2)))
        assertFalse(layout.predicts(SlotIntent.distribute(listOf(0, 2), SlotDistributeMode.EVEN)))
        // Out of the inputs, the ring runs through the result zone; it only sends, so it is not a target.
        assertTrue(layout.predicts(SlotIntent.quickMove(0)))
    }

    @Test
    fun `an out of range slot is ignored rather than throwing`() {
        val layout = layout(Triple("a", 1, SlotZoneRole.BOTH))
        val state = layout.state { carried = ItemStack(Items.APPLE, 1) }

        assertFalse(layout.applyClick(state, SlotIntent.click(9, SlotButton.LEFT)).changed)
        assertFalse(layout.applyClick(state, SlotIntent.quickMove(-1)).changed)
        assertEquals(1, state.carried.count)
    }
}
