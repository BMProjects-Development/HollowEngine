package ru.hollowhorizon.hollowengine.client.ui.docking

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import kotlin.test.*

class DockDropTargetTest {
    @Test
    fun `local drop leaves a tab floating unless it lands on the center guide`() {
        val dock = DockingState()
        val target = DockItem("target", "Target")
        val moved = DockItem("moved", "Moved")
        dock.open(target)
        dock.openFloating(moved)
        val targetId = assertNotNull(dock.root).id
        val source = dock.floatingWindows.single()
        dock.startDraggingWindow(source.id)

        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
                    Box(
                        modifier = Modifier.size(100.percent, 100.percent).input(hoverable = true)
                            .then(DockDropZoneMarker(DockDropTarget.Pane(DockTarget(targetId))))
                    )
                    Box(
                        id = source.id,
                        modifier = Modifier.size(100.percent, 100.percent).layer(100).input(hoverable = true)
                            .then(DockDropZoneMarker(DockDropTarget.Pane(DockTarget(source.stack.id))))
                    )
                    if (dock.draggedWindowId != null) DockDropOverlay(dock)
                }
            }
            val frame = surface.frame(420f, 300f, 100f, 60f)
            assertEquals(5, frame.nodes.count { DockTags.DropZone in it.tags })
            assertNull(dock.previewTarget)
            surface.runtime.mouseReleased(100f, 60f, 0)
            assertNull(dock.draggedWindowId)
            assertEquals(listOf(target), assertIs<DockNode.Stack>(dock.root).items)
            assertEquals(source.id, dock.floatingWindows.single().id)

            dock.startDraggingWindow(source.id)
            val preview = surface.frame(420f, 300f, 210f, 150f, 1_000_000L)
            val indicator = assertNotNull(preview.nodeByIdentifier("dock-drop-$targetId-center"))
            val bounds = preview.layout[indicator].rect
            assertEquals(210f, bounds.x + bounds.width / 2f)
            assertEquals(150f, bounds.y + bounds.height / 2f)
            assertTrue(bounds.width < 50f && bounds.height < 50f)
            assertEquals(DockTarget(targetId), dock.previewTarget)
            assertTrue(surface.runtime.mouseReleased(210f, 150f, 0))

            assertEquals(listOf(target, moved), assertIs<DockNode.Stack>(dock.root).items)
            assertTrue(dock.floatingWindows.isEmpty())
        }
    }

    @Test
    fun `incoming native drag can still target the pane outside the center indicator`() {
        val dock = DockingState()
        dock.open(DockItem("target", "Target"))
        val destination = DockDropTarget.Pane(DockTarget(assertNotNull(dock.root).id))
        HollowUiSurface().use { surface ->
            surface.setContent {
                Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
                    Box(
                        modifier = Modifier.size(100.percent, 100.percent).input(hoverable = true)
                            .then(DockDropZoneMarker(destination))
                    )
                    DockDropOverlay(dock, incoming = DockIncomingDrop(destination, canPin = false))
                }
            }
            val frame = surface.frame(420f, 300f, 100f, 60f)
            assertNotNull(frame.nodeByIdentifier("dock-center-drop-preview"))
            assertEquals(destination, frame.dockDropTargetAt(100f, 60f))
            assertEquals(destination, frame.dockDropTargetAt(210f, 150f))
        }
    }

    @Test
    fun `blank tab bar starts window dragging while tabs keep their own press`() {
        val dock = DockingState()
        val tab = DockItem("tab", "Tab", closable = false)
        dock.open(tab)
        val stack = assertIs<DockNode.Stack>(dock.root)
        var titlePresses = 0
        HollowUiSurface().use { surface ->
            surface.setContent {
                DockTabBar(
                    stack, dock,
                    tabContent = { Box(modifier = Modifier.size(56.px, 16.px)) },
                    tabBarActions = {},
                    allowUndock = false,
                    titleDrag = Modifier.input(hoverable = true, draggable = true).onPress { titlePresses++ },
                )
            }
            val frame = surface.frame(260f, 24f, 10f, 10f)
            surface.runtime.mouseClicked(20f, 10f, 0)
            assertEquals(0, titlePresses)

            surface.runtime.mouseReleased(20f, 10f, 0)
            surface.runtime.mouseClicked(160f, 10f, 0)
            assertEquals(1, titlePresses)
            surface.runtime.mouseReleased(160f, 10f, 0)

            val title = assertNotNull(frame.nodeByIdentifier("${stack.id}-title"))
            val bounds = frame.layout[title].rect
            surface.runtime.mouseClicked(bounds.x + bounds.width / 2f, 10f, 0)
            assertEquals(2, titlePresses)
        }
    }

    @Test
    fun `tab insertion follows screen position even when the selected tab paints last`() {
        HollowUiSurface().use { surface ->
            surface.setContent {
                Row(modifier = Modifier.size(180.px, 24.px).input(hoverable = true).then(DockTabBarMarker("tabs"))) {
                    Box(tags = listOf(DockTags.Tab), modifier = Modifier.size(40.px, 24.px).layer(1))
                    Box(tags = listOf(DockTags.Tab), modifier = Modifier.size(40.px, 24.px))
                    Box(tags = listOf(DockTags.Tab), modifier = Modifier.size(40.px, 24.px))
                }
            }
            val frame = surface.frame(180f, 24f, 40f, 12f)
            assertEquals(DockDropTarget.Pane(DockTarget("tabs", tabIndex = 1)), frame.dockDropTargetAt(40f, 12f))
            assertEquals(DockDropTarget.Pane(DockTarget("tabs", tabIndex = 3)), frame.dockDropTargetAt(130f, 12f))
        }
    }
}
