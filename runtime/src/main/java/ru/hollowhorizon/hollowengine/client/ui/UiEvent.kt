package ru.hollowhorizon.hollowengine.client.ui

import net.minecraft.nbt.CompoundTag
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.style.parseHssSelector
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput

enum class UiEventKind {
    INIT,
    UPDATE,
    CLOSE,
    ENTER,
    EXIT,
    HOVER,
    PRESS,
    CLICK,
    RELEASE,
    DRAG,
    SCROLL,
    CHAR_TYPED,
    KEY_PRESSED,
    FOCUS,
    UNFOCUS;

    val isPointerEvent: Boolean
        get() = when (this) {
            ENTER,
            EXIT,
            HOVER,
            PRESS,
            CLICK,
            RELEASE,
            DRAG,
            SCROLL,
                -> true

            INIT,
            UPDATE,
            CLOSE,
            CHAR_TYPED,
            KEY_PRESSED,
            FOCUS,
            UNFOCUS,
                -> false
        }

    val attributeName: String
        get() = name.lowercase().split('_').joinToString("-")

    companion object {
        fun fromAttribute(name: String): UiEventKind? {
            val dashed = name.replace(Regex("([a-z])([A-Z])"), "$1-$2")
                .replace('_', '-')
                .lowercase()
            val normalized = when {
                dashed.startsWith("on-") -> dashed.removePrefix("on-")
                dashed.startsWith("on") -> dashed.removePrefix("on")
                else -> dashed
            }
            val aliased = when (normalized) {
                "pressed" -> "press"
                "released" -> "release"
                "char" -> "char-typed"
                "key" -> "key-pressed"
                "un-focus" -> "unfocus"
                else -> normalized
            }
            return entries.firstOrNull { it.attributeName == aliased }
        }
    }
}

data class UiEvent(
    val kind: UiEventKind,
    val node: UiNode,
    val frame: HollowUiFrame? = null,
    val button: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val localX: Float = 0f,
    val localY: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val parentLocalX: Float = localX,
    val parentLocalY: Float = localY,
    val parentWidth: Float = 0f,
    val parentHeight: Float = 0f,
    val rootLocalX: Float = x,
    val rootLocalY: Float = y,
    val ancestorLocalPositions: Map<String, UiVec3> = emptyMap(),
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val dragTotalX: Float = 0f,
    val dragTotalY: Float = 0f,
    val scrollX: Float = 0f,
    val scrollY: Float = 0f,
    /** Wheel input before it is routed to the scrollable axes of the target container. */
    val rawScrollX: Float = scrollX,
    val rawScrollY: Float = scrollY,
    val key: Int = 0,
    val scanCode: Int = 0,
    val modifiers: Int = 0,
    val repeat: Boolean = false,
    val codePoint: Int = 0,
    val released: Boolean = false,
) {
    var consumed: Boolean = false
        private set

    var changed: Boolean = false
        private set

    fun consume() {
        consumed = true
    }

    fun markChanged() {
        changed = true
    }

    fun localXInAncestor(identifier: String): Float? = ancestorLocalPositions[identifier]?.x

    fun localYInAncestor(identifier: String): Float? = ancestorLocalPositions[identifier]?.y

    fun read(path: String): Any? {
        val normalized = path.removePrefix("it.").removePrefix("event.")
        return when (normalized) {
            "kind" -> kind.name.lowercase()
            "repeat" -> repeat
            "node.id", "id" -> node.id.orEmpty()
            "node.type", "type" -> node.type
            "node.value", "value" -> node.readWidgetValue()
            "node.text", "text" -> node.spanText().ifEmpty { null }
            "button" -> button
            "x" -> x
            "y" -> y
            "localX", "local-x" -> localX
            "localY", "local-y" -> localY
            "width" -> width
            "height" -> height
            "parentLocalX", "parent-local-x" -> parentLocalX
            "parentLocalY", "parent-local-y" -> parentLocalY
            "parentWidth", "parent-width" -> parentWidth
            "parentHeight", "parent-height" -> parentHeight
            "rootLocalX", "root-local-x" -> rootLocalX
            "rootLocalY", "root-local-y" -> rootLocalY
            "deltaX", "delta-x" -> deltaX
            "deltaY", "delta-y" -> deltaY
            "rawScrollX", "raw-scroll-x" -> rawScrollX
            "rawScrollY", "raw-scroll-y" -> rawScrollY
            "isReleased", "released" -> released
            else -> null
        }
    }

    fun matches(selector: String): Boolean {
        val clean = selector.trim()
        if (clean.isBlank()) return true
        return runCatching { parseHssSelector(clean).matches(node) }.getOrDefault(false) || when {
            clean.startsWith("#") -> node.id == clean.removePrefix("#")
            clean.startsWith(".") -> clean.removePrefix(".") in node.tags
            clean.any { it in "[:#" } -> false
            else -> node.type == clean || node.id == clean || clean in node.tags
        }
    }
}

fun UiEvent.isLeftButton(): Boolean = button == 0
fun UiEvent.isRightButton(): Boolean = button == 1
fun UiEvent.isMiddleButton(): Boolean = button == 2

fun UiEvent.isCtrlDown(): Boolean = modifiers and GLFW.GLFW_MOD_CONTROL != 0
fun UiEvent.isShiftDown(): Boolean = modifiers and GLFW.GLFW_MOD_SHIFT != 0
fun UiEvent.isAltDown(): Boolean = modifiers and GLFW.GLFW_MOD_ALT != 0

private fun UiNode.readWidgetValue(): Any? = when (this) {
    is SpanNode -> text
    else -> attributes["value"]
}

private fun UiNode.spanText(): String =
    if (this is SpanNode) text else children.joinToString("") { it.spanText() }

fun interface UiEventSink {
    fun emit(payload: CompoundTag)

    companion object {
        val None = UiEventSink {}
    }
}

fun UiNode.dispatch(event: UiEvent): Boolean {
    var handled = false
    // Key events go to onKeyInput handlers first, ordered by priority; each may consume the
    // event to stop lower-priority handlers (including built-in widget keymaps).
    if (event.kind == UiEventKind.KEY_PRESSED) {
        val keyInput = UiKeyInput(event)
        val keyHandlers = resolvedModifiers
            .filterIsInstance<KeyInputModifier>()
            .sortedByDescending { it.priority }
        for (modifier in keyHandlers) {
            if (event.consumed) break
            handled = true
            modifier.handler(keyInput)
        }
    }
    resolvedModifiers.forEach { modifier ->
        if (event.consumed) return@forEach
        when (modifier) {
            is EventModifier -> if (modifier.kind == event.kind) {
                handled = true
                if (event.kind.isPointerEvent) modifier.onPointerEvent(event) else modifier.handler(event)
            }

            is PointerInputModifierNode -> if (event.kind.isPointerEvent) {
                handled = true
                modifier.onPointerEvent(event)
            }

            else -> Unit
        }
    }
    return handled
}
