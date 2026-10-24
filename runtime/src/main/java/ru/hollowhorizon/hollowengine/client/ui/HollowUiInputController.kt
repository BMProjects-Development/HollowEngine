package ru.hollowhorizon.hollowengine.client.ui

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.layout.readOnlyIterator
import ru.hollowhorizon.hollowengine.client.ui.scroll.*
import ru.hollowhorizon.hollowengine.client.ui.style.*
import java.util.*

class HollowUiInputController {
    private var hoveredNode: UiNode? = null
    private var activeNode: UiNode? = null
    private var draggingNode: UiNode? = null
    private var dragStartX = 0f
    private var dragStartY = 0f

    private var dragMoved = false

    private val focusByScope = LinkedHashMap<UiNode, UiNode>()
    private val hoverChain = HashSet<UiNode>()
    private val runtimeStack = ArrayDeque<UiNode>()
    private var activeScope: UiNode? = null

    private fun primaryFocus(): UiNode? = activeScope?.let { focusByScope[it] } ?: focusByScope.values.firstOrNull()

    val focusedKey: String? get() = primaryFocus()?.id

    /** The cursor shape of the node currently under the pointer (for the window cursor). */
    val hoveredCursor: UiCursorShape
        get() = (draggingNode ?: hoveredNode)?.resolvedSnapshot?.cursor ?: UiCursorShape.DEFAULT

    var x = 0f
    var y = 0f

    private var scrollbarDrag: UiScrollbarDragState? = null

    /** The scrollbar under the pointer or in the hand, drawn in its hover style; see [ScrollbarNode.engaged]. */
    private var engagedScrollbar: ScrollbarNode? = null

    private fun updateEngagedScrollbar() {
        val next = scrollbarDrag?.scrollbar ?: when (val node = hoveredNode) {
            is ScrollbarThumbNode -> node.layoutState.parentNode as? ScrollbarNode
            is ScrollbarNode -> node
            else -> null
        }
        if (next === engagedScrollbar) return
        engagedScrollbar?.engaged = false
        next?.engaged = true
        engagedScrollbar = next
    }

    fun reset() {
        clearInteraction()
    }

    fun clearInteraction(clearFocus: Boolean = true) {
        hoveredNode = null
        activeNode = null
        draggingNode = null
        scrollbarDrag = null
        updateEngagedScrollbar()
        if (clearFocus) {
            focusByScope.clear()
            activeScope = null
        }
    }

    fun prepareRoot(root: UiNode, closing: Boolean = false) {
        remapTrackedNodes(root)
        applyRuntimeStates(root, closing)
    }

    /** Re-binds interaction tracking (hover/active/focus/drag) to the current tree by id.*/
    private fun remapTrackedNodes(root: UiNode) {
        val hovered = hoveredNode
        val active = activeNode
        val dragging = draggingNode
        val scope = activeScope
        if (hovered == null && active == null && dragging == null && scope == null && focusByScope.isEmpty()) return

        val wantedIds = HashSet<String>(8)
        val identityChecked = HashSet<UiNode>(8)
        fun want(node: UiNode?) {
            node ?: return
            val id = node.id
            if (id != null) wantedIds += id else identityChecked += node
        }
        want(hovered)
        want(active)
        want(dragging)
        want(scope)
        scope?.let { identityChecked += it }
        for ((focusScope, target) in focusByScope) {
            want(focusScope)
            want(target)
        }

        val firstById = HashMap<String, UiNode>(wantedIds.size * 2)
        val present = HashSet<UiNode>(identityChecked.size * 2)
        val walk = runtimeStack
        walk.clear()
        walk.add(root)
        while (walk.isNotEmpty()) {
            val node = walk.removeLast()
            node.id?.let { if (it in wantedIds) firstById.putIfAbsent(it, node) }
            if (node in identityChecked) present += node
            for (index in node.children.indices.reversed()) walk.add(node.children[index])
        }

        fun remap(node: UiNode?): UiNode? = node?.id?.let { firstById[it] ?: node } ?: node
        fun inTree(node: UiNode): Boolean = node.id?.let { firstById[it] === node } ?: (node in present)

        hoveredNode = remap(hovered)
        activeNode = remap(active)
        draggingNode = remap(dragging)
        activeScope = remap(scope)?.takeIf { inTree(it) }
        if (focusByScope.isNotEmpty()) {
            fun relocate(node: UiNode): UiNode? = node.id?.let { firstById[it] } ?: node.takeIf { it in present }

            val remappedFocus = focusByScope.entries.mapNotNull { (focusScope, target) ->
                val s = relocate(focusScope) ?: return@mapNotNull null
                val t = relocate(target) ?: return@mapNotNull null
                s to t
            }
            focusByScope.clear()
            remappedFocus.forEach { (s, t) -> focusByScope[s] = t }
        }
    }

    fun updateHover(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        dispatch: (UiEvent) -> Boolean,
    ): Boolean {
        val hit = frame.hitTest(mouseX, mouseY)
        val previousNode = hoveredNode
        hoveredNode = hit?.node
        updateEngagedScrollbar()

        x = mouseX
        y = mouseY

        if (previousNode === hoveredNode) return false

        previousNode?.takeIf { it in frame.nodes }
            ?.let { dispatch(frame.pointerEvent(UiEventKind.EXIT, it, mouseX, mouseY)) }
        hoveredNode?.takeIf { it in frame.nodes }?.let {
                dispatch(frame.pointerEvent(UiEventKind.ENTER, it, mouseX, mouseY))
            }
        return true
    }

    fun dispatchHover(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        dispatch: (UiEvent) -> Boolean,
    ): Boolean {
        val node = hoveredNode?.takeIf { it in frame.nodes } ?: return false
        return dispatch(frame.pointerEvent(UiEventKind.HOVER, node, mouseX, mouseY))
    }

    fun focus(
        frame: HollowUiFrame,
        nodeKey: String?,
        dispatch: (UiEvent) -> Boolean,
    ): Boolean {
        if (nodeKey == null) {
            val hadFocus = focusByScope.isNotEmpty()
            clearAllFocus(frame, dispatch)
            return hadFocus
        }
        val node = frame.nodeByIdentifier(nodeKey) ?: return false
        if (!node.resolvedSnapshot.focusable) return false
        val scope = node.enclosingFocusScope() ?: return false
        val previous = focusByScope[scope]
        setFocus(frame, scope, node, dispatch)
        return previous !== node
    }

    fun scrollTargetAt(frame: HollowUiFrame, x: Float, y: Float): UiNode? {
        return frame.scrollTargetAt(x, y) ?: focusedScrollableNode(frame)
    }

    fun mouseClicked(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        dispatch: (UiEvent) -> Boolean,
        modifiers: Int = 0,
    ): UiInputResult {
        val hit = frame.hitTest(mouseX, mouseY) ?: run {
            clearAllFocus(frame, dispatch)
            return UiInputResult(false)
        }

        val layoutNode = frame.layout[hit.node]
        activeNode = hit.node
        updateFocus(frame, hit.node, dispatch)

        val press = UiEvent(
            kind = UiEventKind.PRESS,
            node = hit.node,
            frame = frame,
            button = button,
            modifiers = modifiers,
            x = mouseX,
            y = mouseY,
            localX = hit.localX,
            localY = hit.localY,
            width = layoutNode.rect.width,
            height = layoutNode.rect.height,
            ancestorLocalPositions = frame.ancestorLocalPositions(hit.node, mouseX, mouseY),
        )
        val pressHandled = dispatch(press)

        val startsDrag = hit.node.resolvedSnapshot.draggable && button in 0..2
        if (startsDrag) {
            draggingNode = hit.node
            dragStartX = mouseX
            dragStartY = mouseY
            dragMoved = false
        }

        if (pressHandled && press.consumed) return UiInputResult(true, hit.node, hit.node.id)
        if (startsDrag) return UiInputResult(true, hit.node, hit.node.id)

        val clickHandled =
            dispatchClick(frame, hit.node, button, mouseX, mouseY, hit.localX, hit.localY, modifiers, dispatch)
        return UiInputResult(clickHandled, hit.node, hit.node.id)
    }

    fun scrollbarMouseClicked(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        setScrollImmediate: (UiScrollHandle, UiScrollOffset) -> Unit,
    ): UiInputResult {
        if (button != 0) return UiInputResult(false)
        val drag = when (val hit = frame.hitTest(mouseX, mouseY)?.node) {
            is ScrollbarThumbNode -> scrollbarThumbDragState(frame.layout.nodes, hit, mouseX, mouseY)
            is ScrollbarNode -> scrollbarTrackDragState(frame.layout.nodes, hit, mouseX, mouseY)?.also {
                setScrollImmediate(it.handle, it.offsetFor(frame.layout[it.node], mouseX, mouseY))
            }

            else -> null
        } ?: return UiInputResult(false)
        scrollbarDrag = drag
        updateEngagedScrollbar()
        return UiInputResult(true, drag.node, drag.node.id, changed = true)
    }

    fun mouseDragged(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        deltaX: Float,
        deltaY: Float,
        modifiers: Int,
        dispatch: (UiEvent) -> Boolean,
    ): UiInputResult {
        val node = draggingNode?.takeIf { it in frame.nodes } ?: return UiInputResult(false)
        dragMoved = true
        val local = frame.layout[node].inputTransform.inverse()?.transform(mouseX, mouseY, 0f)
        val layoutNode = frame.layout[node]
        val parent = frame.parentOf(node)
        val parentLayout = parent?.let { frame.layout[it] }
        val parentLocal = parentLayout?.inputTransform?.inverse()?.transform(mouseX, mouseY, 0f)
        val rootLocal = frame.layout.root.let { root ->
            frame.layout[root].inputTransform.inverse()?.transform(mouseX, mouseY, 0f)
        }
        val event = UiEvent(
            kind = UiEventKind.DRAG,
            node = node,
            frame = frame,
            button = button,
            x = mouseX,
            y = mouseY,
            localX = local?.x ?: 0f,
            localY = local?.y ?: 0f,
            width = layoutNode.rect.width,
            height = layoutNode.rect.height,
            parentLocalX = parentLocal?.x ?: 0f,
            parentLocalY = parentLocal?.y ?: 0f,
            parentWidth = parentLayout?.rect?.width ?: 0f,
            parentHeight = parentLayout?.rect?.height ?: 0f,
            rootLocalX = rootLocal?.x ?: mouseX,
            rootLocalY = rootLocal?.y ?: mouseY,
            ancestorLocalPositions = frame.ancestorLocalPositions(node, mouseX, mouseY),
            deltaX = deltaX,
            deltaY = deltaY,
            dragTotalX = mouseX - dragStartX,
            dragTotalY = mouseY - dragStartY,
            modifiers = modifiers,
        )
        val handled = dispatch(event)
        return UiInputResult(handled, node, node.id)
    }

    fun mouseReleased(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        button: Int,
        dispatch: (UiEvent) -> Boolean,
        modifiers: Int = 0,
    ): UiInputResult {
        var received = false
        fun dispatch(node: UiNode): Boolean {
            val event = UiEvent(
                kind = UiEventKind.RELEASE,
                node = node,
                frame = frame,
                button = button,
                modifiers = modifiers,
                x = mouseX,
                y = mouseY,
                released = true
            )
            received = dispatch(event) || received
            return event.consumed
        }

        val handled =
            (frame.hitTest(mouseX, mouseY)?.node ?: activeNode?.takeIf { it in frame.nodes })?.let { dispatch(it) }
                ?: false

        val releaseNode = draggingNode?.takeIf { it in frame.nodes }
        if (!handled) releaseNode?.let { node -> dispatch(node) }
        if (releaseNode != null && !dragMoved) {
            val hit = frame.hitTest(mouseX, mouseY)?.takeIf { it.node == releaseNode }
            dispatchClick(
                frame = frame,
                node = releaseNode,
                button = button,
                mouseX = mouseX,
                mouseY = mouseY,
                localX = hit?.localX ?: 0f,
                localY = hit?.localY ?: 0f,
                modifiers = modifiers,
                dispatch = dispatch,
            )
        }
        activeNode = null
        draggingNode = null
        dragMoved = false
        scrollbarDrag = null
        updateEngagedScrollbar()
        return UiInputResult(received, releaseNode, releaseNode?.id)
    }

    fun scrollbarMouseDragged(
        frame: HollowUiFrame,
        mouseX: Float,
        mouseY: Float,
        setScrollImmediate: (UiScrollHandle, UiScrollOffset) -> Unit,
    ): UiInputResult {
        val drag = scrollbarDrag ?: return UiInputResult(false)
        val node = drag.node.takeIf { it in frame.nodes } ?: return UiInputResult(false)
        setScrollImmediate(drag.handle, drag.offsetFor(frame.layout[node], mouseX, mouseY))
        return UiInputResult(true, node, node.id, changed = true)
    }

    fun charTyped(
        frame: HollowUiFrame,
        codePoint: Char,
        modifiers: Int,
        dispatch: (UiEvent) -> Boolean,
    ): UiInputResult {
        val targets = focusTargets(frame)
        var handled = false
        for (node in targets) {
            val event =
                UiEvent(UiEventKind.CHAR_TYPED, node, frame = frame, modifiers = modifiers, codePoint = codePoint.code)
            if (dispatch(event)) handled = true
            if (event.consumed) return UiInputResult(true, node, node.id, consumed = true)
        }
        return UiInputResult(handled, primaryFocus(), primaryFocus()?.id)
    }

    /**
     * The nodes that currently receive key/char events, most-in-front first. Multi-focus: every open
     * focus scope plus each scope's focused target.
     */
    private fun focusTargets(frame: HollowUiFrame): List<UiNode> {
        val set = LinkedHashSet<UiNode>()
        activeScope?.takeIf { it in frame.nodes }?.let { scope ->
            focusByScope[scope]?.takeIf { it in frame.nodes }?.let { set += it }
            set += scope
        }
        primaryFocus()?.takeIf { it in frame.nodes }?.let { set += it }
        focusByScope.values.forEach { if (it in frame.nodes) set += it }
        frame.nodes.forEach { if (it.resolvedSnapshot.focusScope) set += it }
        val ordered = set.sortedByDescending { it.resolvedSnapshot.layer }
        val modal = frame.nodes.lastOrNull { it.resolvedSnapshot.modal } ?: return ordered
        return ordered.filter { it === modal || it.isWithin(modal) }
    }

    private fun UiNode.isWithin(ancestor: UiNode): Boolean {
        var node: UiNode? = layoutState.parentNode
        while (node != null) {
            if (node === ancestor) return true
            node = node.layoutState.parentNode
        }
        return false
    }

    fun keyPressed(
        frame: HollowUiFrame,
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
        repeat: Boolean,
        dispatch: (UiEvent) -> Boolean,
    ): UiInputResult {
        val targets = focusTargets(frame)
        val enterPressed = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
        val altPressed = modifiers and GLFW.GLFW_MOD_ALT != 0 || enterPressed && isAltPressed()
        val effectiveModifiers = if (altPressed) modifiers or GLFW.GLFW_MOD_ALT else modifiers
        var handled = false
        for (node in targets) {
            val event = UiEvent(
                UiEventKind.KEY_PRESSED,
                node,
                frame = frame,
                key = keyCode,
                scanCode = scanCode,
                modifiers = effectiveModifiers,
                repeat = repeat,
            )
            if (dispatch(event)) handled = true
            if (event.consumed) return UiInputResult(true, node, node.id, consumed = true)
        }
        val backwards = effectiveModifiers and GLFW.GLFW_MOD_SHIFT != 0
        if (keyCode == GLFW.GLFW_KEY_TAB && focusNext(frame, dispatch, backwards)) {
            return UiInputResult(true, primaryFocus(), primaryFocus()?.id, consumed = true)
        }
        return UiInputResult(handled, primaryFocus(), primaryFocus()?.id)
    }

    /** On a click, resolve focus within the hit node's nearest enclosing scope. */
    private fun updateFocus(frame: HollowUiFrame, node: UiNode, dispatch: (UiEvent) -> Boolean) {
        val scope = node.enclosingFocusScope() ?: return
        setFocus(frame, scope, node.focusTargetWithin(scope), dispatch)
        activeScope = scope
    }

    /** Sets (or clears, when [target] is null) the focused target of [scope]; other scopes untouched. */
    private fun setFocus(frame: HollowUiFrame, scope: UiNode, target: UiNode?, dispatch: (UiEvent) -> Boolean) {
        val old = focusByScope[scope]
        if (old === target) return
        old?.takeIf { it in frame.nodes }?.let { node ->
            dispatch(UiEvent(UiEventKind.UNFOCUS, node))
        }
        if (target != null) {
            focusByScope[scope] = target
            activeScope = scope
            target.takeIf { it in frame.nodes }?.let { dispatch(UiEvent(UiEventKind.FOCUS, it)) }
        } else {
            focusByScope.remove(scope)
            if (activeScope === scope) activeScope = focusByScope.keys.lastOrNull()
        }
    }

    private fun clearAllFocus(frame: HollowUiFrame, dispatch: (UiEvent) -> Boolean) {
        if (focusByScope.isEmpty()) return
        focusByScope.values.toList().forEach { node ->
            if (node in frame.nodes) {
                dispatch(UiEvent(UiEventKind.UNFOCUS, node))
            }
        }
        focusByScope.clear()
        activeScope = null
    }

    /** Moves focus to the next focusable of the active scope, or the previous one when [backwards]. */
    private fun focusNext(
        frame: HollowUiFrame,
        dispatch: (UiEvent) -> Boolean,
        backwards: Boolean = false,
    ): Boolean {
        val scope = activeScope ?: frame.nodes.lastOrNull { it.resolvedSnapshot.focusScope } ?: return false
        val targets = frame.nodes.filter { it.resolvedSnapshot.focusable && it.enclosingFocusScope() === scope }
        if (targets.isEmpty()) return false
        val current = focusByScope[scope]
        val currentIndex = targets.indexOfFirst { it === current }
        val nextIndex = when {
            currentIndex < 0 -> if (backwards) targets.lastIndex else 0
            else -> (currentIndex + if (backwards) -1 else 1).mod(targets.size)
        }
        setFocus(frame, scope, targets[nextIndex], dispatch)
        return true
    }

    /** The nearest ancestor (or self) marked as a focus scope; the root is always one. */
    private fun UiNode.enclosingFocusScope(): UiNode? {
        var node: UiNode? = this
        while (node != null) {
            if (node.resolvedSnapshot.focusScope) return node
            node = node.layoutState.parentNode
        }
        return null
    }

    /** The focusable target the receiver belongs to within [scope], or null if it's not on one. */
    private fun UiNode.focusTargetWithin(scope: UiNode): UiNode? {
        var node: UiNode? = this
        while (node != null) {
            if (node.resolvedSnapshot.focusable) return node
            if (node === scope) return null
            node = node.layoutState.parentNode
        }
        return null
    }

    private fun dispatchClick(
        frame: HollowUiFrame,
        node: UiNode,
        button: Int,
        mouseX: Float,
        mouseY: Float,
        localX: Float,
        localY: Float,
        modifiers: Int,
        dispatch: (UiEvent) -> Boolean,
    ): Boolean {
        val layoutNode = frame.layout[node]
        return dispatch(
            UiEvent(
                kind = UiEventKind.CLICK,
                node = node,
                frame = frame,
                button = button,
                modifiers = modifiers,
                x = mouseX,
                y = mouseY,
                localX = localX,
                localY = localY,
                width = layoutNode.rect.width,
                height = layoutNode.rect.height,
            )
        )
    }

    private fun isAltPressed(): Boolean {
        val window = Minecraft.getInstance()?.window?.window ?: return false
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(
            window,
            GLFW.GLFW_KEY_RIGHT_ALT
        ) == GLFW.GLFW_PRESS
    }

    internal fun focusedScrollableNode(frame: HollowUiFrame): UiNode? {
        return focusByScope.values.firstOrNull {
            it in frame.layout.nodes && it.resolvedSnapshot.scrollable && frame.layout[it].scrollRange.hasScrollableAxis()
        }
    }

    private fun applyRuntimeStates(node: UiNode, closing: Boolean) {
        hoverChain.clear()

        var ancestor = hoveredNode
        while (ancestor != null) {
            hoverChain += ancestor
            ancestor = ancestor.layoutState.parentNode
        }

        val focusTargets = if (focusByScope.isEmpty()) emptySet() else HashSet(focusByScope.values)

        runtimeStack.clear()
        runtimeStack.add(node)
        while (runtimeStack.isNotEmpty()) {
            val current = runtimeStack.removeLast()
            val hover = current in hoverChain
            val active = current === activeNode
            val focus = focusTargets.isNotEmpty() && current in focusTargets
            val dragging = current === draggingNode
            if (!hover && !active && !focus && !dragging && !closing) {
                current.setRuntimeStates(emptySet())
            } else {
                val states = linkedSetOf<UiState>()
                if (hover) states += UiState.HOVER
                if (active) states += UiState.ACTIVE
                if (focus) states += UiState.FOCUS
                if (dragging) states += UiState.DRAGGING
                if (closing) states += UiState.CLOSING
                current.setRuntimeStates(states)
            }
            for (index in current.children.indices.reversed()) {
                runtimeStack.add(current.children[index])
            }
        }
    }
}

data class UiInputResult(
    val handled: Boolean,
    val node: UiNode? = null,
    val nodeKey: String? = null,
    val changed: Boolean = false,
    val consumed: Boolean = false,
)

private fun HollowUiFrame.parentOf(node: UiNode): UiNode? {
    return node.layoutState.parentNode?.takeIf { node !== root }
}

private fun HollowUiFrame.pointerEvent(kind: UiEventKind, node: UiNode, x: Float, y: Float): UiEvent {
    val layoutNode = layout[node]
    val local = layoutNode.inputTransform.inverse()?.transform(x, y, 0f)
    return UiEvent(
        kind = kind,
        node = node,
        frame = this,
        x = x,
        y = y,
        localX = local?.x ?: 0f,
        localY = local?.y ?: 0f,
        width = layoutNode.rect.width,
        height = layoutNode.rect.height,
    )
}

private fun HollowUiFrame.ancestorLocalPositions(node: UiNode, x: Float, y: Float): Map<String, UiVec3> {
    val ancestors = ancestorsOf(node)
    if (ancestors.isEmpty()) return emptyMap()
    val positions = linkedMapOf<String, UiVec3>()
    ancestors.forEach { ancestor ->
        val local = layout[ancestor].inputTransform.inverse()?.transform(x, y, 0f) ?: return@forEach
        ancestor.id?.let { positions[it] = local }
        val tags = ancestor.tags.readOnlyIterator()
        while (tags.hasNext()) positions[tags.next()] = local
    }
    return positions
}

private fun HollowUiFrame.ancestorsOf(node: UiNode): List<UiNode> {
    var current = node.layoutState.parentNode ?: return emptyList()
    val result = ArrayDeque<UiNode>()
    while (true) {
        result.addFirst(current)
        if (current === root) break
        current = current.layoutState.parentNode ?: break
    }
    return result.toList()
}
