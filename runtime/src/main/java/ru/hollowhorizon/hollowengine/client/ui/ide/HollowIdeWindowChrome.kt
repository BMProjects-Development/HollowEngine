package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.Button
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val ChromeLang = "hollowengine.gui.docking"
private const val ChromeIcons = "hollowengine:textures/gui/icons"

@Composable
internal fun HollowIdeWindowControls(id: String, chrome: GlfwDockWindowChrome) {
    if (!chrome.custom) {
        Button(DockLang.ReturnToIde, id = "$id-return", onClick = chrome::requestClose)
        return
    }
    WindowControl(id, "minimize", chrome::minimize)
    if (chrome.maximized) {
        WindowControl(id, "restore", chrome::toggleMaximize)
    } else {
        WindowControl(id, "maximize", chrome::toggleMaximize)
    }
    WindowControl(id, "close", chrome::requestClose)
}

internal fun windowTitleDrag(chrome: GlfwDockWindowChrome, onDockDrag: () -> Unit): Modifier =
    Modifier.input(hoverable = true, draggable = true).cursor(UiCursorShape.MOVE)
        .onPress { event -> if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) chrome.begin() }.onDrag { event ->
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (chrome.drag()) onDockDrag()
                event.consume()
            }
        }

@Composable
private fun WindowControl(windowId: String, action: String, onClick: () -> Unit) {
    val icon = when (action) {
        "restore" -> "minimize"
        "minimize" -> "window/hide"
        "close" -> "window/close"
        else -> action
    }
    IconButton(
        "$ChromeIcons/$icon.svg",
        tooltip = "$ChromeLang.window_$action".lang,
        id = "$windowId-$action",
        tags = listOf("detached-dock-control", action),
        onClick = onClick,
    )
}

@Composable
internal fun HollowIdeWindowResizeHandles(id: String, chrome: GlfwDockWindowChrome) {
    if (!chrome.custom || chrome.maximized) return
    for (edge in DockResizeEdge.entries) {
        Box(
            id = "$id-native-resize-${edge.name.lowercase()}",
            tags = listOf(DockTags.ResizeHandle),
            modifier = Modifier.resizeHandleModifier(edge).layer(if (edge.isCorner) 20_001 else 20_000)
                .input(hoverable = true, draggable = true).cursor(edge.cursorShape)
                .onPress { event -> if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) chrome.begin(edge) }
                .onDrag { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) chrome.drag()
                    event.consume()
                },
        )
    }
}
