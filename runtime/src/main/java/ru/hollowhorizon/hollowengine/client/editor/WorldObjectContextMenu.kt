package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoKeys
import ru.hollowhorizon.hollowengine.client.ui.HollowUiWorldOverlay
import ru.hollowhorizon.hollowengine.client.ui.UiPopupAlignment
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.WorldObjectParts
import ru.hollowhorizon.hollowengine.client.ui.ide.WorldObjectScene
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.MinecraftHssResourceLoader
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.ScreenEvent

/**
 * The menu a right click on a world object opens over the world, with the same items the scene window
 * offers for it. While it is open it takes the pointer: a click anywhere else only closes it.
 */
@ClientOnly
object WorldObjectContextMenu {
    /** The items are made when the menu opens, on the game thread, since they read the object. */
    private class Opened(val items: List<UiDropdownItem>, val x: Float, val y: Float)

    private var opened by mutableStateOf<Opened?>(null)

    private val overlay: HollowUiWorldOverlay by lazy {
        HollowUiWorldOverlay(stylesheet = MinecraftHssResourceLoader.load(WidgetStylesheet), manageCursor = false)
            .apply { setContent { Menu() } }
    }

    val isOpen: Boolean get() = opened != null

    /** Opens the menu of [target] at ([x], [y]), in the logical pixels of the world overlay. */
    fun open(target: WorldObjectEntity, x: Float, y: Float) {
        opened = Opened(WorldObjectEditing.menu(target, inScene = false), x, y)
    }

    fun close() {
        opened = null
    }

    /**
     * The menu only opens with the pointer free, which is while a screen is up: drawn after it, it lies over
     * the hand, the hotbar and the screen itself.
     */
    @SubscribeEvent
    fun onScreenRendered(event: ScreenEvent.Render.Post) {
        if (opened == null) return
        if (!EditorMode.isAvailable()) {
            close()
            return
        }
        overlay.render()
    }

    fun handleMouseMove(physX: Float, physY: Float): Boolean {
        if (opened == null) return false
        overlay.handleMouseMove(physX, physY)
        return true
    }

    fun handleMouseButton(physX: Float, physY: Float, button: Int, action: Int): Boolean {
        if (opened == null) return false
        if (overlay.isMouseOver(physX, physY)) {
            overlay.handleMouseButton(physX, physY, button, action)
            return true
        }
        if (action == GLFW.GLFW_PRESS) close()
        return true
    }

    /**
     * Escape closes the menu. With it closed, undo and redo go to the world's history, and Delete and Ctrl+D act
     * on the selected part or object - in the world with the chat open, and in the game panel of the editor.
     */
    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        if (opened != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                close()
                return true
            }
            return overlay.handleKey(key, scanCode, action, modifiers)
        }
        if (action != GLFW.GLFW_PRESS || !EditorMode.isAvailable()) return false
        if (Minecraft.getInstance().screen == null && !HollowIdeOverlay.isGameViewportActive) return false
        if (UndoKeys.handle(WorldHistory.history, key, modifiers)) return true
        if (WorldObjectParts.handleKey(key, modifiers, WorldObjectScene::partsOf)) return true
        return WorldObjectEditing.handleShortcut(key, modifiers)
    }

    @Composable
    private fun Menu() {
        val menu = opened ?: return
        ContextMenu(
            id = "world-object-menu",
            anchorBounds = UiRect(menu.x, menu.y, 0f, 0f),
            alignment = UiPopupAlignment.Cursor,
            items = menu.items,
            onExpandedChange = { expanded -> if (!expanded) close() },
        )
    }

    private const val WidgetStylesheet = "hollowengine:ui/styles/widgets.hss"
}
