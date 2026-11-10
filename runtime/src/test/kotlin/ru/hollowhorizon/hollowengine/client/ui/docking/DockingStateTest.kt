package ru.hollowhorizon.hollowengine.client.ui.docking

import org.junit.jupiter.api.Test
import kotlin.test.*

class DockingStateTest {
    @Test
    fun `moving a tab between detached windows preserves its metadata and siblings`() {
        val state = DockingState()
        val sourceTab = DockItem("source", "Source", dirty = true)
        val sibling = DockItem("sibling", "Sibling")
        val targetTab = DockItem("target", "Target")
        val source = state.newWindow(assertNotNull(state.newStack(listOf(sourceTab, sibling))), 0f, 0f, 320f, 220f)
        val target = state.newWindow(assertNotNull(state.newStack(listOf(targetTab))), 0f, 0f, 320f, 220f)
        state.applyLayout(root = null, floating = listOf(source, target))
        assertTrue(state.setWindowDetached(source.id, true))
        assertTrue(state.setWindowDetached(target.id, true))

        val movingTab = DockTransferSource.Tab(sourceTab.id)
        val destination = DockDropTarget.Pane(DockTarget(target.stack.id, tabIndex = 0))
        assertTrue(state.canTransfer(movingTab, destination))
        assertTrue(state.transfer(movingTab, destination))

        assertEquals(listOf(sibling), state.floatingWindows.first { it.id == source.id }.stack.items)
        assertEquals(listOf(sourceTab, targetTab), state.floatingWindows.first { it.id == target.id }.stack.items)
        assertEquals(sourceTab, state.focusedItem())
    }

    @Test
    fun `returning a detached group into a split preserves its tab order and selection`() {
        val state = DockingState()
        val editor = DockItem("editor", "Editor")
        val tools = listOf(DockItem("first", "First"), DockItem("second", "Second"))
        val root = assertNotNull(state.newStack(listOf(editor)))
        val stack = assertNotNull(state.newStack(tools, tools.last().id))
        val window = state.newWindow(stack, 0f, 0f, 320f, 220f)
        state.applyLayout(root, listOf(window))
        assertTrue(state.setWindowDetached(window.id, true))

        assertTrue(state.transfer(
            DockTransferSource.Window(window.id),
            DockDropTarget.Pane(DockTarget(root.id, DockPlacement.BOTTOM)),
        ))

        val split = assertIs<DockNode.Split>(state.root)
        assertEquals(DockOrientation.VERTICAL, split.orientation)
        assertEquals(root, split.first)
        assertEquals(stack, split.second)
        assertTrue(state.floatingWindows.isEmpty())
        assertEquals(tools.last(), state.focusedItem())
    }

    @Test
    fun `a non detachable tab cannot enter a detached window by itself or with a group`() {
        val state = DockingState()
        val game = DockItem("game", "Game")
        state.canDetachItem = { it.id != game.id }
        val tool = DockItem("tool", "Tool")
        val targetTab = DockItem("target", "Target")
        val source = state.newWindow(assertNotNull(state.newStack(listOf(game, tool))), 0f, 0f, 320f, 220f)
        val target = state.newWindow(assertNotNull(state.newStack(listOf(targetTab))), 0f, 0f, 320f, 220f)
        state.applyLayout(root = null, floating = listOf(source, target))
        assertTrue(state.setWindowDetached(target.id, true))
        val destination = DockDropTarget.Pane(DockTarget(target.stack.id))

        assertFalse(state.setWindowDetached(source.id, true))
        assertFalse(state.canTransfer(DockTransferSource.Window(source.id), destination))
        assertFalse(state.canTransfer(DockTransferSource.Tab(game.id), destination))
        assertFalse(state.transfer(DockTransferSource.Window(source.id), destination))
        assertFalse(state.transfer(DockTransferSource.Tab(game.id), destination))
        assertEquals(source, state.floatingWindows.first { it.id == source.id })
        assertFalse(state.isDetached(game.id))
    }

    @Test
    fun `pulling a tab from an IDE floating group leaves the other tabs in place`() {
        val state = DockingState()
        val first = DockItem("first", "First")
        val second = DockItem("second", "Second")
        val stack = assertNotNull(state.newStack(listOf(first, second), second.id))
        val group = state.newWindow(stack, 0f, 0f, 320f, 220f)
        state.applyLayout(root = null, floating = listOf(group))

        val drag = assertNotNull(state.beginDraggingTab(second.id, 30f, 30f))

        assertTrue(drag.created)
        assertEquals(listOf(first), state.floatingWindows.first { it.id == group.id }.stack.items)
        assertEquals(listOf(second), state.floatingWindows.first { it.id == drag.windowId }.stack.items)
    }

    @Test
    fun `detached stack retains its tabs and settles in the current dock space on return`() {
        val state = DockingState()
        state.updateSpaceSize(800f, 600f)
        val first = DockItem("first", "First")
        val second = DockItem("second", "Second")
        val stack = assertNotNull(state.newStack(listOf(first, second), second.id))
        val window = state.newWindow(stack, 480f, 360f, 320f, 220f)
        state.applyLayout(root = null, floating = listOf(window))

        state.setWindowDetached(window.id, true)
        state.updateSpaceSize(400f, 300f)
        assertTrue(state.isDetached(first.id))
        assertEquals(window.x, state.floatingWindows.single().x)
        assertNull(state.beginDraggingTab(first.id, 0f, 0f))

        state.focus(first.id)
        state.updateItem(first.copy(dirty = true))
        state.setWindowDetached(window.id, false)

        val returned = state.floatingWindows.single()
        assertFalse(returned.detached)
        assertEquals(80f, returned.x)
        assertEquals(80f, returned.y)
        assertEquals(first.copy(dirty = true), returned.stack.selectedItem)
        assertEquals(listOf(first.id, second.id), returned.stack.items.map { it.id })
    }

    @Test
    fun `a pinned item moved to a detached window is only held once`() {
        val state = DockingState()
        val item = DockItem("project", "Project", pinnable = true)
        state.open(item)
        state.pin(item.id, DockSide.LEFT)
        val window = state.newWindow(assertNotNull(state.newStack(listOf(item))), 0f, 0f, 320f, 220f)

        state.detachItem(item.id, window)

        assertNull(state.expandedOn(DockSide.LEFT))
        assertFalse(state.isPinned(item.id))
        assertEquals(item, state.focusedItem())
        assertTrue(state.isDetached(item.id))
        assertTrue(state.close(item.id))
        assertFalse(state.contains(item.id))
    }

    @Test
    fun `absolute resize recovers immediately after leaving the minimum bound`() {
        val state = DockingState()
        state.openFloating(DockItem("test", "Test"), x = 20f, y = 30f, width = 240f, height = 180f)
        val start = state.floatingWindows.single()

        state.resizeFloatingFrom(start.id, DockResizeEdge.LEFT, start, deltaX = 500f, deltaY = 0f)
        assertEquals(80f, state.floatingWindows.single().width, "dragged past the minimum, it stops there")

        state.resizeFloatingFrom(start.id, DockResizeEdge.LEFT, start, deltaX = 40f, deltaY = 0f)
        val recovered = state.floatingWindows.single()
        assertEquals(60f, recovered.x)
        assertEquals(200f, recovered.width)
    }

    @Test
    fun `pinning takes a tool window out of the tree and unpinning brings it back`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.open(DockItem("editor", "Editor"), DockTarget(state.stackIdOf("project"), DockPlacement.RIGHT))

        assertTrue(state.pin("project", DockSide.LEFT))
        assertNull(state.root?.findItem("project"), "a pinned window no longer takes room in the tree")
        assertTrue(state.contains("project"), "but the dock still holds it")
        assertEquals("project", state.expandedOn(DockSide.LEFT)?.item?.id, "pinning opens the panel")

        assertTrue(state.unpin("project"))
        assertNotNull(state.root?.findItem("project"), "unpinning docks it back into the tree")
        assertNull(state.expandedOn(DockSide.LEFT))
        assertTrue(state.pinnedItems.isEmpty())
    }

    @Test
    fun `a floating window dropped past the edge, or cut off by a resize, comes back inside`() {
        val state = DockingState()
        state.updateSpaceSize(800f, 600f)
        state.openFloating(DockItem("test", "Test"), x = 20f, y = 30f, width = 240f, height = 180f)
        val id = state.floatingWindows.single().id

        state.startDraggingWindow(id)
        state.moveFloating(id, 900f, -200f)
        state.updateSpaceSize(700f, 500f)
        assertEquals(920f, state.floatingWindows.single().x, "a window in the hand is left where the pointer has it")

        state.finishDraggingWindow()
        val dropped = state.floatingWindows.single()
        assertEquals(460f, dropped.x, "dropped, it lies against the right edge")
        assertEquals(0f, dropped.y)

        state.moveFloating(id, 0f, 400f)
        state.updateSpaceSize(400f, 300f)
        val shrunk = state.floatingWindows.single()
        assertEquals(160f, shrunk.x)
        assertEquals(120f, shrunk.y, "a smaller space pulls it up as well as left")
    }

    @Test
    fun `a split window shares the side panel with the top one and swaps with it`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.open(DockItem("scene", "Scene", pinnable = true))
        state.open(DockItem("assets", "Assets", pinnable = true))
        state.pin("project", DockSide.LEFT)
        state.pin("assets", DockSide.LEFT)
        state.pin("scene", DockSide.LEFT, DockStripeGroup.SPLIT)
        state.togglePinned("project")

        assertEquals(listOf("project", "scene"), state.sidePanels(DockSide.LEFT).map { it.item.id })

        assertTrue(state.swapSidePanels(DockSide.LEFT))
        assertEquals(listOf("scene", "project"), state.sidePanels(DockSide.LEFT).map { it.item.id }, "both stay open")
        assertEquals(
            listOf("scene", "assets", "project"),
            state.pinnedOn(DockSide.LEFT).map { it.item.id },
            "the swapped buttons change groups, the rest of the stripe keeps its order",
        )

        state.togglePinned("assets")
        assertEquals(listOf("assets", "project"), state.sidePanels(DockSide.LEFT).map { it.item.id }, "top replaces top only")
    }

    @Test
    fun `a stripe button opens its panel and closes it again`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.open(DockItem("assets", "Assets", pinnable = true))
        state.pin("project", DockSide.LEFT)
        state.pin("assets", DockSide.LEFT)

        assertEquals("assets", state.expandedOn(DockSide.LEFT)?.item?.id, "one side shows one panel at a time")

        state.togglePinned("assets")
        assertNull(state.expandedOn(DockSide.LEFT), "clicking the open window's button collapses the side")

        state.togglePinned("project")
        assertEquals("project", state.expandedOn(DockSide.LEFT)?.item?.id)
    }

    @Test
    fun `an editor tab cannot be parked on a stripe`() {
        val state = DockingState()
        state.open(DockItem("editor", "Editor"))

        assertFalse(state.pin("editor", DockSide.LEFT))
        assertTrue(state.pinnedItems.isEmpty())
        assertNotNull(state.root?.findItem("editor"), "and it stays where it was")
    }

    @Test
    fun `closing a pinned window takes it off its stripe`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.pin("project", DockSide.RIGHT)

        assertTrue(state.close("project"))
        assertTrue(state.pinnedItems.isEmpty())
        assertNull(state.expandedOn(DockSide.RIGHT))
        assertFalse(state.contains("project"))
    }

    @Test
    fun `sizing a new editor split leaves an existing bottom panel unchanged`() {
        val state = DockingState()
        state.open(DockItem("project", "Project"))
        val projectStack = assertNotNull(state.stackIdOf("project"))
        state.open(DockItem("assets", "Assets"), DockTarget(projectStack, DockPlacement.BOTTOM))

        val verticalRoot = state.root as DockNode.Split
        state.setSplitFraction(verticalRoot.id, 0.7f)
        state.open(DockItem("editor", "Editor"), DockTarget(projectStack, DockPlacement.RIGHT))

        assertTrue(state.setSplitFractionForItem("project", "editor", 0.28f))
        val preservedRoot = state.root as DockNode.Split
        assertEquals(verticalRoot.id, preservedRoot.id)
        assertEquals(0.7f, preservedRoot.fraction)

        val editorSplit = assertNotNull(preservedRoot.findSplitSeparating("project", "editor"))
        assertEquals(0.28f, editorSplit.fraction)
    }

    @Test
    fun `the two halves of a stripe keep a window open each`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.open(DockItem("console", "Console", pinnable = true))
        state.pin("project", DockSide.LEFT)
        state.pin("console", DockSide.LEFT, DockStripeGroup.BOTTOM)

        assertEquals("project", state.expandedOn(DockSide.LEFT)?.item?.id)
        assertEquals("console", state.expandedIn(DockAnchor(DockSide.LEFT, DockStripeGroup.BOTTOM))?.item?.id)

        state.togglePinned("project")
        assertNull(state.expandedOn(DockSide.LEFT))
        assertNotNull(state.expandedIn(DockAnchor(DockSide.LEFT, DockStripeGroup.BOTTOM)), "the bottom half is its own")
    }

    @Test
    fun `a click into an open parked window gives it the keyboard`() {
        val state = DockingState()
        state.open(DockItem("editor", "Editor"))
        state.open(DockItem("timeline", "Timeline", pinnable = true))
        state.pin("timeline", DockSide.LEFT, DockStripeGroup.BOTTOM)
        state.focus("editor")

        assertTrue(state.focusContent("${PinnedContentPrefix}timeline-content"))
        assertEquals("timeline", state.focusedItemId)
    }

    @Test
    fun `a window dragged to the other half stays open there`() {
        val state = DockingState()
        state.open(DockItem("project", "Project", pinnable = true))
        state.pin("project", DockSide.RIGHT)

        assertTrue(state.movePinned("project", DockAnchor(DockSide.RIGHT, DockStripeGroup.BOTTOM), 0))
        assertNull(state.expandedOn(DockSide.RIGHT))
        assertEquals("project", state.expandedIn(DockAnchor(DockSide.RIGHT, DockStripeGroup.BOTTOM))?.item?.id)
    }

    @Test
    fun `a dragged tool window can be parked on a stripe and a stripe button can float again`() {
        val state = DockingState()
        state.open(DockItem("editor", "Editor"))
        state.open(DockItem("project", "Project", pinnable = true))
        assertNotNull(state.beginDraggingTab("project", 10f, 10f))

        assertTrue(state.pinDraggedWindow(DockAnchor(DockSide.LEFT, DockStripeGroup.BOTTOM)))
        assertTrue(state.floatingWindows.isEmpty())
        assertEquals(DockStripeGroup.BOTTOM, state.pinnedItem("project")?.group)

        assertNotNull(state.undockPinned("project", 0f, 0f, "dock-stripe-button-project"))
        assertFalse(state.isPinned("project"))
        assertTrue(state.isFloating("project"))
        assertEquals("project", state.floatingWindows.single().stack.items.single().id)
    }
}
