package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.HollowUiRuntime
import ru.hollowhorizon.hollowengine.client.ui.docking.*
import ru.hollowhorizon.hollowengine.client.utils.lang

internal class HollowIdeDetachedWindows(
    private val dock: DockingState,
    private val beforeInput: () -> Unit,
    private val onFocus: () -> Unit,
    private val onUnhandledKey: (Int, Int) -> Unit,
    private val onFailure: (String) -> Unit,
    private val mainRuntime: HollowUiRuntime,
    private val revealMainDock: () -> Boolean,
    private val content: @Composable (String, HollowIdeNativeWindow) -> Unit,
) : AutoCloseable {
    private data class DetachRequest(
        val windowId: String?,
        val itemId: String?,
        val position: HollowIdeOverlayPoint,
    )

    private data class Drag(val origin: String?, val source: DockTransferSource)
    private data class Preview(val windowId: String?, val guide: DockIncomingDrop)

    private val windows = linkedMapOf<String, HollowIdeNativeWindow>()
    private val pending = mutableListOf<DetachRequest>()
    private var focusRequest: String? = null
    private var focusMain = false
    private var layoutChanged = false
    private var nativeDrag: Drag? = null
    private var preview by mutableStateOf<Preview?>(null)
    var focusedWindowId: String? by mutableStateOf(null)
        private set

    val active: Boolean get() = windows.isNotEmpty() || pending.isNotEmpty()
    val focusedRuntime: HollowUiRuntime? get() = windows[focusedWindowId]?.surface?.runtime

    fun incoming(windowId: String?): DockIncomingDrop? = preview?.takeIf { it.windowId == windowId }?.guide

    fun startNativeDrag(windowId: String, source: DockTransferSource) {
        if (nativeDrag != null) return
        val window = dock.floatingWindows.firstOrNull { it.id == windowId && it.detached } ?: return
        if (source is DockTransferSource.Tab && window.stack.items.none { it.id == source.itemId }) return
        nativeDrag = Drag(windowId, source)
    }

    fun finishNativeDrag(windowId: String): Boolean {
        val drag = nativeDrag?.takeIf { it.origin == windowId } ?: return false
        nativeDrag = null
        if (windows[windowId]?.focused == true) {
            val target = probe(drag)
            if (target != null) {
                applyDrop(drag, target)
            } else if (drag.source is DockTransferSource.Tab && !mainWindowHovered() && windows.values.none { it.hovered }) {
                val item = dock.item(drag.source.itemId)
                if (item != null) detach(item, from = windows[windowId])
            }
        }
        preview = null
        return true
    }

    fun dropFromMain(windowId: String): Boolean {
        val main = Minecraft.getInstance().window
        val window = main.window
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) return false
        val drag = Drag(null, DockTransferSource.Window(windowId))
        val target = probe(drag)
        if (target != null) {
            applyDrop(drag, target)
            preview = null
            return true
        }
        if (!dock.transferItems(drag.source).all(dock.canDetachItem)) return false
        return cursorPosition(window) { x, y ->
            if (x >= 0 && x < main.screenWidth && y >= 0 && y < main.screenHeight) return@cursorPosition false
            pending += DetachRequest(windowId, null, HollowIdeOverlayPoint(x.toFloat(), y.toFloat()))
            true
        }
    }

    fun detach(item: DockItem) = detach(item, from = null)

    private fun detach(item: DockItem, from: HollowIdeNativeWindow?) {
        if (!dock.canDetachItem(item) || from == null && dock.isDetached(item.id)) return
        if (from != null && dock.floatingWindows.any { it.detached && it.stack.items.singleOrNull()?.id == item.id }) return
        val main = Minecraft.getInstance().window.window
        val position = from?.cursorRelativeTo(main) ?: cursorPosition(main) { x, y ->
            HollowIdeOverlayPoint(x.toFloat(), y.toFloat())
        }
        pending += DetachRequest(null, item.id, position)
    }

    fun requestFocus(itemId: String) {
        val window =
            dock.floatingWindows.firstOrNull { it.detached && it.stack.items.any { item -> item.id == itemId } }
        if (window != null && window.id != focusedWindowId) focusRequest = window.id
    }

    fun returnToIde(windowId: String) {
        dock.setWindowDetached(windowId, false)
    }

    private fun probe(drag: Drag): Preview? {
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return null
        val items = dock.transferItems(drag.source)
        if (items.isEmpty()) return null

        val target = windows.entries.firstOrNull { (id, window) ->
            id != drag.origin && window.hovered && dock.floatingWindows.any { it.id == id && it.detached }
        }
        if (target != null) {
            val destination = target.value.hoveredDockTarget()?.takeIf { dock.canTransfer(drag.source, it) }
            return Preview(target.key, DockIncomingDrop(destination, canPin = false))
        }
        val sourceWindow = windows[drag.origin] ?: return null
        if (!mainWindowHovered() || !revealMainDock()) return null
        val canPin = items.all { it.pinnable }
        val main = Minecraft.getInstance().window
        val cursor = sourceWindow.cursorRelativeTo(main.window)
        val logicalX = cursor.x * HollowIdeScale.scaledWidth() / main.screenWidth.coerceAtLeast(1)
        val logicalY = cursor.y * HollowIdeScale.scaledHeight() / main.screenHeight.coerceAtLeast(1)
        val destination = mainRuntime.lastFrame?.dockDropTargetAt(logicalX, logicalY) ?: DockDropTarget.Pane(
            dock.defaultStackTarget(DockTarget.Root)
        )
        val allowed = destination.takeIf { dock.canTransfer(drag.source, it) }
        return Preview(null, DockIncomingDrop(allowed, canPin))
    }

    private fun mainWindowHovered(): Boolean =
        GLFW.glfwGetWindowAttrib(Minecraft.getInstance().window.window, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE

    private fun applyDrop(drag: Drag, preview: Preview): Boolean {
        val destination = preview.guide.target ?: return false
        if (!dock.transfer(drag.source, destination)) return false
        layoutChanged = true
        if (preview.windowId == null) focusMain = true
        this.preview = null
        return true
    }

    /** Runs before the main composition builds, with GLFW event dispatch complete. */
    fun prepare(): Boolean {
        var changed = layoutChanged
        layoutChanged = false
        for ((id, window) in windows.toMap()) {
            if (window.shouldClose) returnToIde(id)
            if (dock.floatingWindows.none { it.id == id && it.detached }) {
                closeWindow(id)
                changed = true
            }
        }
        val requests = pending.toList()
        pending.clear()
        for (request in requests) {
            changed = open(request) || changed
        }
        val focusedItemId = dock.focusedItemId
        if (focusedItemId != null && dock.isDetached(focusedItemId)) {
            focusRequest?.let { windows[it]?.focus() }
        }
        focusRequest = null
        if (focusMain) {
            focusMain = false
            GLFW.glfwFocusWindow(Minecraft.getInstance().window.window)
        }
        val drag = nativeDrag ?: dock.draggedWindowId?.let { Drag(null, DockTransferSource.Window(it)) }
        val nextPreview = drag?.let(::probe)
        nativeDrag?.origin?.let { id ->
            windows[id]?.chrome?.showDockPreview(nextPreview?.guide?.target != null)
        }
        changed = changed || preview != nextPreview
        preview = nextPreview
        return changed
    }

    private fun open(request: DetachRequest): Boolean {
        val itemId = request.itemId
        val existing = dock.floatingWindows.firstOrNull { it.id == request.windowId }
        val window = if (itemId != null) {
            val item = dock.item(itemId)?.takeIf(dock.canDetachItem) ?: return false
            createDockWindow(item)
        } else {
            existing?.takeIf { !it.detached && it.stack.items.all(dock.canDetachItem) } ?: return false
        }
        var native: HollowIdeNativeWindow? = null
        try {
            val opened = HollowIdeNativeWindow.create(window.title, window.width, window.height, request.position)
            native = opened
            opened.show(
                beforeInput = beforeInput,
                onFocus = { focused -> updateFocus(window.id, focused) },
                onUnhandledKey = onUnhandledKey,
                onDockRelease = { finishNativeDrag(window.id) },
            ) { content(window.id, opened) }
            val attached =
                if (itemId == null) dock.setWindowDetached(window.id, true) else dock.detachItem(itemId, window)
            if (!attached) {
                closeNative(opened)
                return false
            }
            windows[window.id] = opened
            if (opened.focused) updateFocus(window.id, true)
            return true
        } catch (failure: Exception) {
            windows.remove(window.id)
            closeNative(native)
            reportFailure(failure)
            return false
        }
    }

    private fun createDockWindow(item: DockItem): FloatingDockWindow {
        val source = dock.floatingWindows.firstOrNull { it.stack.items.any { tab -> tab.id == item.id } }
        val stack = requireNotNull(dock.newStack(listOf(item)))
        return dock.newWindow(
            stack,
            source?.x ?: DefaultFloatingOffset,
            source?.y ?: DefaultFloatingOffset,
            (source?.width ?: dock.pinnedItem(item.id)?.width ?: DefaultFloatingWidth).coerceAtLeast(item.minWidth),
            (source?.height ?: DefaultFloatingHeight).coerceAtLeast(item.minHeight),
        )
    }

    private fun updateFocus(id: String, focused: Boolean) {
        if (focused) {
            val selected =
                dock.floatingWindows.firstOrNull { it.id == id && it.detached }?.stack?.selectedItem ?: return
            focusedWindowId = id
            dock.focus(selected.id)
            onFocus()
        } else if (focusedWindowId == id) {
            focusedWindowId = null
            dock.finishTabDrag()
            if (nativeDrag?.origin == id) {
                nativeDrag = null
                preview = null
            }
        }
    }

    fun render() {
        for (window in dock.floatingWindows.toList()) {
            if (!window.detached) continue
            val native = windows[window.id] ?: continue
            try {
                native.render(window.title)
            } catch (failure: Exception) {
                returnToIde(window.id)
                closeWindow(window.id)
                reportFailure(failure)
            }
        }
    }

    private fun reportFailure(failure: Exception) {
        HollowEngine.LOGGER.warn("Could not display a detached IDE panel", failure)
        onFailure("hollowengine.gui.docking.detach_failed".lang)
    }

    private fun closeWindow(id: String) {
        if (focusedWindowId == id) focusedWindowId = null
        if (nativeDrag?.origin == id) {
            nativeDrag = null
            preview = null
        }
        if (preview?.windowId == id) preview = null
        closeNative(windows.remove(id))
    }

    private fun closeNative(native: HollowIdeNativeWindow?) {
        try {
            native?.close()
        } catch (failure: Exception) {
            HollowEngine.LOGGER.warn("Could not clean up a detached IDE panel", failure)
        }
    }

    override fun close() {
        pending.clear()
        focusRequest = null
        nativeDrag = null
        preview = null
        for (id in windows.keys.toList()) {
            returnToIde(id)
            closeWindow(id)
        }
    }

    private val FloatingDockWindow.title: String get() = stack.selectedItem?.title?.lang.orEmpty()
}
