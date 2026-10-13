package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.Composable
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.layout.copyToStableSet
import ru.hollowhorizon.hollowengine.client.ui.layout.readOnlyIterator
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.scroll.rememberScrollState
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.*
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextEffect
import ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayout
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput

interface Modifier {
    infix fun then(other: Modifier): Modifier {
        return CompositeModifier(listOf(this, other))
    }

    companion object : Modifier {
        override fun then(other: Modifier): Modifier = CompositeModifier(listOf(other))
    }
}

/**
 * A modifier that contributes style property values. Application is declarative and
 * order-independent per property (last write wins), so `padding` and `margin` keep
 * their box-model meaning regardless of modifier order — unlike Compose's chained
 * modifier semantics.
 */
interface UiModifierPatchNode : Modifier {
    fun applyPatch(style: UiStylePatch)
}

interface PointerInputModifierNode : Modifier {
    fun onPointerEvent(event: UiEvent)
}

interface InputModifierNode : Modifier

enum class UiStyleProperty {
    WIDTH,
    HEIGHT,
    TRANSITIONS
}

/**
 * Sets a single style property to a fixed value. The property itself carries all the
 * logic (defaults, merge, interpolation, invalidation phase), so this modifier is pure
 * data with value equality for free.
 */
data class StylePropModifier<T>(
    val prop: UiStyleProp<T>,
    val value: T,
    val explicit: Set<UiStyleProperty> = emptySet(),
) : UiModifierPatchNode {
    override fun applyPatch(style: UiStylePatch) {
        // Last-wins within a single source (node modifier chain, one HSS rule). Stacking is
        // reserved for multiple simultaneously-active state rules, handled by the resolver.
        style[prop] = value
        if (explicit.isNotEmpty()) style.explicitProperties = style.explicitProperties.orEmpty() + explicit
    }
}

/**
 * Style contribution expressed as a patch-writer lambda; used where a single value
 * assignment is not enough (read-modify-write like input flags, HSS declarations).
 */
class StyleModifier(
    val properties: Set<UiStyleProperty> = emptySet(),
    key: Any? = null,
    private val writer: (UiStylePatch) -> Unit,
) : UiModifierPatchNode {
    private val equalityKey = key ?: writer

    override fun applyPatch(style: UiStylePatch) {
        writer(style)
        if (properties.isNotEmpty()) style.explicitProperties = style.explicitProperties.orEmpty() + properties
    }

    override fun equals(other: Any?): Boolean {
        return other is StyleModifier &&
                properties == other.properties &&
                equalityKey == other.equalityKey
    }

    override fun hashCode(): Int {
        var result = properties.hashCode()
        result = 31 * result + equalityKey.hashCode()
        return result
    }
}

data class CompositeModifier(internal val values: List<Modifier>) : Modifier {
    override fun then(other: Modifier): Modifier {
        return CompositeModifier(
            if (other is CompositeModifier) values + other.values else values + other,
        )
    }

    fun flatten(): List<Modifier> = ArrayList<Modifier>(values.size).also { result ->
        values.appendFlattenedTo(result)
    }
}

data class StyleImportModifier(
    val reference: UiStylesheetReference,
) : Modifier

data class EventModifier(
    val kind: UiEventKind,
    val handler: (UiEvent) -> Unit,
) : PointerInputModifierNode, UiModifierPatchNode {
    override fun applyPatch(style: UiStylePatch) {
        // Registering an event handler implies the capability it needs; capabilities are
        // independent props that accumulate (never turned off here). Lifecycle events
        // (INIT/UPDATE/CLOSE) imply no interaction capability.
        when (kind) {
            UiEventKind.ENTER,
            UiEventKind.EXIT,
            UiEventKind.HOVER,
                -> style.hoverable = true

            UiEventKind.PRESS,
            UiEventKind.CLICK,
            UiEventKind.RELEASE,
                -> {
                style.clickable = true; style.hoverable = true
            }

            UiEventKind.DRAG -> {
                style.draggable = true; style.hoverable = true
            }

            UiEventKind.SCROLL -> style.hoverable = true

            UiEventKind.CHAR_TYPED,
            UiEventKind.KEY_PRESSED,
            UiEventKind.FOCUS,
            UiEventKind.UNFOCUS,
                -> {
                style.focusable = true; style.hoverable = true
            }

            UiEventKind.INIT,
            UiEventKind.UPDATE,
            UiEventKind.CLOSE,
                -> Unit
        }
    }

    override fun onPointerEvent(event: UiEvent) {
        if (event.kind == kind) handler(event)
    }
}

/**
 * A keyboard interceptor. Handlers on a node run in descending [priority] order, and a
 * handler stops propagation to lower-priority ones by calling `input.consume()`. Built-in
 * widget key handling registers at a low priority ([TextFieldDefaultKeyPriority]) so user
 * handlers at the default priority get first refusal.
 */
data class KeyInputModifier(
    val priority: Int,
    val handler: (UiKeyInput) -> Unit,
) : InputModifierNode, UiModifierPatchNode {
    override fun applyPatch(style: UiStylePatch) {
        style.focusable = true
        style.hoverable = true
    }
}

const val TextFieldDefaultKeyPriority = -1000

/**
 * Observes the node's final bounds in root coordinates (its `boundsInRoot`) after each layout pass.
 * The runtime invokes [callback] only when the bounds change, so feeding the rect straight into state.
 * e.g. to anchor a popup to this node - cannot loop.
 */
data class OnPlacedModifier(val callback: (UiRect) -> Unit) : Modifier

/**
 * Observes the node's laid-out text after each layout pass (a [SpanNode]'s line layout or a text
 * field's own layout). The runtime invokes [callback] only when the layout changes, so a field can
 * feed it straight into state to place the caret over the glyphs and map clicks to offsets.
 */
data class OnTextLayoutModifier(val callback: (UiTextLayout) -> Unit) : Modifier

/**
 * Observes the node's resolved computed style after each resolve pass. The runtime invokes
 * [callback] only when the snapshot changes, so a widget can pull HSS-driven values (e.g. a text
 * field's shadow) that aren't plain visual props into its own state without looping.
 */
data class OnResolvedStyleModifier(val callback: (UiComputedStyle) -> Unit) : Modifier

data class ScriptEventModifier(
    val kind: UiEventKind,
    val source: String,
    val sink: UiEventSink,
) : UiModifierPatchNode {
    override fun applyPatch(style: UiStylePatch) {
        EventModifier(kind) {}.applyPatch(style)
    }
}

data class StateModifier(
    val states: Set<UiState>,
) : Modifier

internal data class RuntimeStateModifier(
    val states: Set<UiState>,
) : Modifier

/**
 * A named attribute carried by a node, matched by HSS `[name]` / `[name=value]`.
 */
data class AttributeModifier(
    val name: String,
    val value: String? = null,
) : Modifier

data class UiScrollSpec(
    val vertical: Boolean = true,
    val horizontal: Boolean = true,
    val verticalScrollbar: Boolean = true,
    val horizontalScrollbar: Boolean = true,
    val state: UiScrollHandle,
)

/** Declares the node a scroll container; see [Modifier.scrollable]. */
data class ScrollModifier(val spec: UiScrollSpec) : UiModifierPatchNode {
    override fun applyPatch(style: UiStylePatch) {
        style.scroll = spec
    }
}

// -- Style DSL --------------------------------------------------------------------------

private fun <T> Modifier.prop(prop: UiStyleProp<T>, value: T): Modifier =
    this then StylePropModifier(prop, value)

fun Modifier.style(location: String, loader: HssResourceLoader = MinecraftHssResourceLoader) = this then
        StyleImportModifier(UiStylesheetReference.Resource(location, loader))

fun Modifier.style(stylesheet: CompiledHss) = this then
        StyleImportModifier(UiStylesheetReference.Compiled(stylesheet))

fun Modifier.style(reference: UiStylesheetReference) = this then StyleImportModifier(reference)

fun Modifier.size(width: UiLength = UiLength.Auto, height: UiLength = UiLength.Auto) =
    this then StylePropModifier(UiProps.Width, width, setOf(UiStyleProperty.WIDTH)) then
            StylePropModifier(UiProps.Height, height, setOf(UiStyleProperty.HEIGHT))

fun Modifier.minSize(width: UiLength = UiLength.Auto, height: UiLength = UiLength.Auto) =
    prop(UiProps.MinWidth, width).prop(UiProps.MinHeight, height)

fun Modifier.maxSize(width: UiLength = UiLength.Auto, height: UiLength = UiLength.Auto) =
    prop(UiProps.MaxWidth, width).prop(UiProps.MaxHeight, height)

fun Modifier.aspectRatio(value: Float) = prop(UiProps.AspectRatio, value)

fun Modifier.padding(value: UiLength) = prop(UiProps.Padding, UiInsets.all(value))

fun Modifier.padding(horizontal: UiLength, vertical: UiLength) =
    prop(UiProps.Padding, UiInsets.hv(horizontal, vertical))

fun Modifier.padding(left: UiLength, top: UiLength, right: UiLength, bottom: UiLength) =
    prop(UiProps.Padding, UiInsets(left, top, right, bottom))

fun Modifier.margin(value: UiLength) = prop(UiProps.Margin, UiInsets.all(value))

fun Modifier.margin(horizontal: UiLength, vertical: UiLength) =
    prop(UiProps.Margin, UiInsets.hv(horizontal, vertical))

fun Modifier.margin(left: UiLength, top: UiLength, right: UiLength, bottom: UiLength) =
    prop(UiProps.Margin, UiInsets(left, top, right, bottom))

fun Modifier.gap(value: UiLength) = prop(UiProps.Gap, value)

fun Modifier.align(horizontal: UiAlign = UiAlign.AUTO, vertical: UiAlign = UiAlign.AUTO) =
    prop(UiProps.AlignHorizontal, horizontal).prop(UiProps.AlignVertical, vertical)

fun Modifier.alignItems(horizontal: UiAlign = UiAlign.AUTO, vertical: UiAlign = UiAlign.AUTO) =
    prop(UiProps.AlignItemsHorizontal, horizontal).prop(UiProps.AlignItemsVertical, vertical)

fun Modifier.alignChildren(items: UiAlign = UiAlign.AUTO, content: UiAlign = UiAlign.AUTO) =
    prop(UiProps.AlignItemsHorizontal, items).prop(UiProps.AlignItemsVertical, content)

fun Modifier.grow(value: Float = 1f) = prop(UiProps.Grow, value)

fun Modifier.position(x: UiLength, y: UiLength, z: Float = 0f) = prop(UiProps.Position, UiPosition(x, y, z))

fun Modifier.background(color: UiColor) = prop(UiProps.Background, UiPaint.Color(color))

/** Linear gradient with CSS angles: 0° up, 90° right, 180° down, 270° left. */
fun Modifier.background(angleDegrees: Float, stops: List<UiGradientStop>) =
    prop(UiProps.Background, UiPaint.LinearGradient(angleDegrees, stops))

fun Modifier.background(gradient: UiRadialGradient) = prop(UiProps.Background, UiPaint.RadialGradient(gradient))

fun Modifier.background(paint: UiPaint) = prop(UiProps.Background, paint)

fun Modifier.background(source: String) = prop(UiProps.Background, UiPaint.Image(source))

fun Modifier.foreground(color: UiColor) = prop(UiProps.Foreground, color)

fun Modifier.image(source: String) = prop(UiProps.Image, source)

fun Modifier.imageFit(value: UiImageFit) = prop(UiProps.ImageFit, value)

/** Draws only the given rectangle of the source texture; the engine treats it as the whole image. */
fun Modifier.imageUv(value: UiImageUv) = prop(UiProps.ImageUv, value)

fun Modifier.imageUv(x: UiLength, y: UiLength, width: UiLength, height: UiLength) =
    prop(UiProps.ImageUv, UiImageUv(x, y, width, height))

fun Modifier.item(stack: ItemStack) = prop(UiProps.Item, UiItem(stack))

/** Shorthand for a plain, single-count item id such as `minecraft:diamond`. */
fun Modifier.item(id: String) = prop(UiProps.Item, UiItem.of(id))

/** Draws [entity] itself, the one this client is already ticking, equipment and pose included. */
fun Modifier.entity(entity: Entity, view: UiEntityView = UiEntityView.Portrait) =
    prop(UiProps.Entity, UiEntityRef.of(entity, view))

/** Draws the entity this client knows by [networkId]; how a server-driven UI names one. */
fun Modifier.entity(networkId: Int, view: UiEntityView = UiEntityView.Portrait) =
    prop(UiProps.Entity, UiEntityRef.ofId(networkId, view))

fun Modifier.shader(name: String) = prop(UiProps.Shader, name)

fun Modifier.border(width: UiLength, color: UiColor, radius: Float = 0f) =
    border(width, UiPaint.Color(color), radius)

/** A border stroked with a full paint, so the ring can carry a gradient. */
fun Modifier.border(width: UiLength, paint: UiPaint, radius: Float = 0f) =
    prop(UiProps.BorderWidth, UiInsets.all(width)).prop(UiProps.BorderPaint, paint).prop(UiProps.BorderRadius, radius)

/** Linear border gradient using the same CSS angles as [background]. */
fun Modifier.border(width: UiLength, angleDegrees: Float, stops: List<UiGradientStop>, radius: Float = 0f) =
    border(width, UiPaint.LinearGradient(angleDegrees, stops), radius)

fun Modifier.borderRadius(radius: Float) = prop(UiProps.BorderRadius, radius)

/** How this inline group's decoration is drawn when wrapped across lines (slice vs clone). */
fun Modifier.boxDecorationBreak(value: UiBoxDecorationBreak) = prop(UiProps.BoxDecorationBreak, value)

fun Modifier.shadow(vararg shadows: UiShadow) = prop(UiProps.Shadows, shadows.toList())

fun Modifier.opacity(value: Float) = prop(UiProps.Opacity, value)

/** Plays a named keyframe animation (see engine keyframes / HSS `@keyframes`). */
fun Modifier.animation(
    name: String,
    durationMillis: Long,
    iterationCount: Float = 1f,
    easing: TransitionEasing = TransitionEasing.LINEAR,
    delayMillis: Long = 0L,
) = prop(
    UiProps.Animations,
    listOf(UiAnimation(name, durationMillis, easing, delayMillis, iterationCount)),
)

fun Modifier.tint(color: UiColor) = prop(UiProps.Tint, color)

fun Modifier.translate(x: Float = 0f, y: Float = 0f, z: Float = 0f) = prop(UiProps.Translate, UiVec3(x, y, z))

fun Modifier.rotate(x: Float = 0f, y: Float = 0f, z: Float = 0f) = prop(UiProps.Rotate, UiVec3(x, y, z))

fun Modifier.scale(x: Float, y: Float = x, z: Float = 1f) = prop(UiProps.Scale, UiVec3(x, y, z))

fun Modifier.perspective(value: Float) = prop(UiProps.Perspective, value)

fun Modifier.pivot(value: UiTransformPivot) = prop(UiProps.Pivot, value)

fun Modifier.pivot(x: UiLength, y: UiLength, z: UiLength = 0.px) = prop(UiProps.Pivot, UiTransformPivot(x, y, z))

fun Modifier.filter(vararg effects: UiFilterEffect) = prop(UiProps.Filter, UiFilterChain(effects.toList()))

fun Modifier.backdropFilter(vararg effects: UiFilterEffect) =
    prop(UiProps.BackdropFilter, UiFilterChain(effects.toList()))

fun Modifier.backfaceVisibility(value: UiBackfaceVisibility) = prop(UiProps.BackfaceVisibility, value)

/**
 * Makes the node a scroll container.
 *
 * [state] is the container's offset. It defaults to a [rememberScrollState] of its own, and
 * passing one in is how a caller reads the offset or drives it.
 */
@Composable
fun Modifier.scrollable(
    vertical: Boolean = true,
    horizontal: Boolean = true,
    hasVerticalScrollbar: Boolean = true,
    hasHorizontalScrollbar: Boolean = true,
    state: UiScrollHandle = rememberScrollState(),
): Modifier = this then ScrollModifier(
    UiScrollSpec(
        vertical = vertical,
        horizontal = horizontal,
        verticalScrollbar = hasVerticalScrollbar,
        horizontalScrollbar = hasHorizontalScrollbar,
        state = state,
    )
)

/** Which axes [Modifier.pinnedToViewport] holds a node against the viewport on. */
data class UiScrollPin(val horizontal: Boolean, val vertical: Boolean)

/**
 * Marks a child of a scroll container as chrome that stays put while the content scrolls under
 * it on given axes.
 */
fun Modifier.pinnedToViewport(horizontal: Boolean = true, vertical: Boolean = true) =
    prop(UiProps.ScrollPinned, UiScrollPin(horizontal, vertical))

fun Modifier.input(
    hoverable: Boolean = false,
    clickable: Boolean = false,
    draggable: Boolean = false,
) = this then StyleModifier(key = modifierKey("input", hoverable, clickable, draggable)) {
    // Only turn capabilities on, so this composes (OR) with event modifiers and other
    if (hoverable) it.hoverable = true
    if (clickable) it.clickable = true
    if (draggable) it.draggable = true
}

/**
 * Makes a node focusable within its enclosing [focusScope] (by click or Tab). While focused it
 * receives key/char events. Each scope holds its own focus independently, so several `focus` nodes
 * (e.g. a text field and a popup's list) can be active at once — multi-focus.
 */
fun Modifier.focus() = prop(UiProps.Focusable, true).input(clickable = true)

/**
 * A focus scope (the root, popups, dock windows): always key/char-active while composed, and owns
 * the focus among its [focus] descendants. Key/char input reaches every open scope at once and is
 * routed from there to that scope's focused child; the scope itself also reacts to keys directly
 * (e.g. Escape), even with no focused child.
 */
fun Modifier.focusScope() = prop(UiProps.FocusScope, true)

/**
 * A [focusScope] that takes the keyboard for itself: while it is on screen, key and character input
 * reaches only it and its descendants, so a dialog's own field is not typed into twice. The frontmost
 * modal (highest layer, then latest) wins when several are open.
 */
fun Modifier.modal(enabled: Boolean = true) = prop(UiProps.FocusScope, true).prop(UiProps.Modal, enabled)

fun Modifier.cursor(shape: UiCursorShape) = prop(UiProps.Cursor, shape)

/** Lets pointer presses over this node's geometry pass through (no click-through blocking). */
fun Modifier.inputTransparent(value: Boolean = true) = prop(UiProps.InputTransparent, value)

fun Modifier.clip(enabled: Boolean = true) = prop(UiProps.Clip, enabled)

fun Modifier.clip(shape: Shape) = prop(UiProps.Clip, true).prop(UiProps.ClipShape, shape)

fun Modifier.shape(shape: Shape) = prop(UiProps.NodeShape, shape)

fun Modifier.shape(shape: Shape, fill: UiPaint?, stroke: UiPaint? = null, strokeWidth: UiLength = 0.px) =
    prop(UiProps.NodeShape, shape)
        .prop(UiProps.ShapeFill, fill)
        .prop(UiProps.ShapeStroke, stroke)
        .prop(UiProps.ShapeStrokeWidth, strokeWidth)

fun Modifier.shapeFill(paint: UiPaint) = prop(UiProps.ShapeFill, paint)

fun Modifier.shapeFill(color: UiColor) = shapeFill(UiPaint.Color(color))

fun Modifier.shapeStroke(paint: UiPaint, width: UiLength = 1.px) =
    prop(UiProps.ShapeStroke, paint).prop(UiProps.ShapeStrokeWidth, width)

fun Modifier.shapeStroke(color: UiColor, width: UiLength = 1.px) = shapeStroke(UiPaint.Color(color), width)

fun Modifier.layer(value: Int) = prop(UiProps.Layer, value)

fun Modifier.textWrap(enabled: Boolean = true) = prop(UiProps.TextWrap, enabled)

fun Modifier.textOverflow(value: UiTextOverflow) = prop(UiProps.TextOverflow, value)

fun Modifier.textAlign(value: UiTextAlign) = prop(UiProps.TextAlign, value)

fun Modifier.whitespace(value: UiWhitespace) = prop(UiProps.Whitespace, value)

fun Modifier.lineSpacing(value: Float) = prop(UiProps.LineSpacing, value.coerceAtLeast(0f))

fun Modifier.spaceWidth(value: Float?) = prop(UiProps.SpaceWidth, value?.coerceAtLeast(0f))

fun Modifier.fontSize(value: Float) = prop(UiProps.FontSize, UiFontSize.of(value))

/** Font size as a share of the surrounding text, the same as `font-size: 85%` in HSS. */
fun Modifier.fontScale(scale: Float) = prop(UiProps.FontSize, UiFontSize.scaled(scale))

fun Modifier.fontFamily(name: String) = prop(UiProps.FontFamily, name)

fun Modifier.textEffects(vararg effects: UiTextEffect) = prop(UiProps.TextEffects, effects.toList())

fun Modifier.transition(vararg transitions: UiTransition) = prop(UiProps.Transitions, transitions.toList())

fun Modifier.onInit(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.INIT, handler)

fun Modifier.onUpdate(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.UPDATE, handler)

fun Modifier.onClose(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.CLOSE, handler)

fun Modifier.onEnter(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.ENTER, handler)

fun Modifier.onExit(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.EXIT, handler)

fun Modifier.onHover(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.HOVER, handler)

fun Modifier.onPress(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.PRESS, handler)

fun Modifier.onClick(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.CLICK, handler)

fun Modifier.onRelease(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.RELEASE, handler)

fun Modifier.onDrag(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.DRAG, handler)

fun Modifier.onScroll(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.SCROLL, handler)

fun Modifier.onCharTyped(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.CHAR_TYPED, handler)

fun Modifier.onKeyPressed(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.KEY_PRESSED, handler)

fun Modifier.onKeyInput(priority: Int = 0, handler: (UiKeyInput) -> Unit) =
    this then KeyInputModifier(priority, handler)

fun Modifier.onFocus(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.FOCUS, handler)

fun Modifier.onUnfocus(handler: (UiEvent) -> Unit) = this then EventModifier(UiEventKind.UNFOCUS, handler)

/** Reports this node's bounds in root coordinates after layout; see [OnPlacedModifier]. */
fun Modifier.onPlaced(callback: (UiRect) -> Unit) = this then OnPlacedModifier(callback)

/** Reports this node's laid-out text after layout; see [OnTextLayoutModifier]. */
fun Modifier.onTextLayout(callback: (UiTextLayout) -> Unit) = this then OnTextLayoutModifier(callback)

/** Reports this node's resolved computed style after each resolve; see [OnResolvedStyleModifier]. */
fun Modifier.onResolvedStyle(callback: (UiComputedStyle) -> Unit) = this then OnResolvedStyleModifier(callback)

fun Modifier.eventScript(kind: UiEventKind, source: String, sink: UiEventSink) = this then
        ScriptEventModifier(kind, source, sink)

fun Modifier.state(vararg states: UiState) = this then StateModifier(states.toSet())

fun Modifier.state(vararg names: String) = this then StateModifier(names.mapTo(mutableSetOf()) { UiState.of(it) })

fun Modifier.attribute(name: String, value: String? = null) = this then AttributeModifier(name.lowercase(), value)

// -- Modifier utilities ------------------------------------------------------------------

fun Iterable<Modifier>.toStylePatch(): UiStylePatch {
    val style = UiStylePatch()
    forEach { (it as? UiModifierPatchNode)?.applyPatch(style) }
    return style
}

fun Iterable<Modifier>.flattenModifiers(): List<Modifier> = ArrayList<Modifier>().also { result ->
    appendFlattenedTo(result)
}

/** The node's scroll declaration, or null when it is not a scroll container. */
internal fun UiNode.scrollSpec(): UiScrollSpec? = resolvedSnapshot.scroll

/** The handle owning this node's offset, or null when it is not a scroll container. */
internal fun UiNode.scrollHandle(): UiScrollHandle? = scrollSpec()?.state

/** Whether the node carries an attribute (via [AttributeModifier] or its legacy XML map). */
internal fun UiNode.hasAttribute(name: String): Boolean {
    if (modifiers.findAttribute(name) != null) return true
    return attributes.containsKey(name)
}

/** The value of a named attribute, from [AttributeModifier]s first, then the legacy XML map. */
internal fun UiNode.attributeValue(name: String): String? {
    modifiers.findAttribute(name)?.let { return it.value }
    return attributes[name]
}

internal fun UiNode.effectiveStates(): Set<UiState> {
    if (!modifiers.hasStateModifiers()) return states.copyToStableSet()
    val result = LinkedHashSet<UiState>()
    val statesIterator = states.readOnlyIterator()
    while (statesIterator.hasNext()) result += statesIterator.next()
    modifiers.appendModifierStatesTo(result)
    return result
}

/** Whether the node has a wheel handler of its own; it then gets the wheel before scrolling. */
internal fun UiNode.listensToScroll(): Boolean =
    resolvedModifiers.any { it is EventModifier && it.kind == UiEventKind.SCROLL }

internal fun UiNode.hasEffectiveState(state: UiState): Boolean {
    if (state in states) return true
    return modifiers.containsModifierState(state)
}

internal fun UiNode.hasEffectiveStates(required: Set<UiState>): Boolean {
    if (required.isEmpty()) return true
    for (state in required) {
        if (!hasEffectiveState(state)) return false
    }
    return true
}

internal fun UiNode.setRuntimeStates(next: Set<UiState>) {
    val index = modifiers.indexOfFirst { it is RuntimeStateModifier }
    val current = (index.takeIf { it >= 0 }?.let { modifiers[it] } as? RuntimeStateModifier)?.states.orEmpty()
    if (current == next) return
    if (next.isEmpty()) {
        if (index >= 0) modifiers.removeAt(index)
    } else if (index >= 0) {
        modifiers[index] = RuntimeStateModifier(next)
    } else {
        modifiers += RuntimeStateModifier(next)
    }
}

private fun Iterable<Modifier>.appendFlattenedTo(result: MutableList<Modifier>) {
    for (modifier in this) {
        if (modifier is CompositeModifier) {
            modifier.values.appendFlattenedTo(result)
        } else {
            result += modifier
        }
    }
}

private fun Iterable<Modifier>.findAttribute(name: String): AttributeModifier? {
    for (modifier in this) {
        when (modifier) {
            is AttributeModifier -> if (modifier.name == name) return modifier
            is CompositeModifier -> modifier.values.findAttribute(name)?.let { return it }
        }
    }
    return null
}

private fun Iterable<Modifier>.hasStateModifiers(): Boolean {
    for (modifier in this) {
        when (modifier) {
            is StateModifier, is RuntimeStateModifier -> return true
            is CompositeModifier -> if (modifier.values.hasStateModifiers()) return true
        }
    }
    return false
}

private fun Iterable<Modifier>.appendModifierStatesTo(result: MutableSet<UiState>) {
    for (modifier in this) {
        when (modifier) {
            is StateModifier -> result.addAll(modifier.states)
            is RuntimeStateModifier -> result.addAll(modifier.states)
            is CompositeModifier -> modifier.values.appendModifierStatesTo(result)
        }
    }
}

private fun Iterable<Modifier>.containsModifierState(state: UiState): Boolean {
    for (modifier in this) {
        when (modifier) {
            is StateModifier -> if (state in modifier.states) return true
            is RuntimeStateModifier -> if (state in modifier.states) return true
            is CompositeModifier -> if (modifier.values.containsModifierState(state)) return true
        }
    }
    return false
}

private data class ModifierKey(
    val name: String,
    val values: List<Any?>,
)

private fun modifierKey(name: String, vararg values: Any?) = ModifierKey(name, values.toList())
