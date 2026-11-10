package ru.hollowhorizon.hollowengine.client.ui.docking

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*

@Composable
internal fun DetachedDockSpace(
    window: FloatingDockWindow,
    state: DockingState,
    tabBarActions: DockTabBarActions,
    incoming: DockIncomingDrop?,
    onTearOff: ((DockItem) -> Unit)?,
    titleDrag: Modifier?,
    chrome: @Composable () -> Unit,
    resizeHandles: @Composable () -> Unit,
    content: DockItemContent,
) {
    val selected = window.stack.selectedItem ?: return
    Box(
        id = window.id,
        mode = UiBoxMode.STACK,
        tags = listOf(DockTags.Stack, "detached-dock-window"),
        modifier = Modifier.style("hollowengine:ui/styles/docking.hss").size(100.percent, 100.percent)
            .input(hoverable = true).then(DockDropZoneMarker(DockDropTarget.Pane(DockTarget(window.stack.id)))),
    ) {
        Column(modifier = Modifier.size(100.percent, 100.percent)) {
            DockTabBar(
                stack = window.stack,
                state = state,
                tabContent = { DefaultDockTabContent(it) },
                tabBarActions = {
                    tabBarActions(it)
                    chrome()
                },
                allowUndock = false,
                onTearOff = onTearOff,
                titleDrag = titleDrag,
            )
            Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
                DockSelectedContent(window.stack.id, selected, content, Modifier.size(100.percent, 100.percent))
                if (incoming?.target != null) DockStackDropPreview(window.id)
            }
        }
        resizeHandles()
    }
}
