package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.layout.PopupOverlayMeasurePolicy
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.layout.detachLayoutParentRecursively
import ru.hollowhorizon.hollowengine.client.ui.layout.invalidateLayout
import ru.hollowhorizon.hollowengine.client.ui.style.DefaultUiFontSize
import ru.hollowhorizon.hollowengine.client.ui.style.TransitionEasing
import ru.hollowhorizon.hollowengine.client.ui.style.UiProps
import ru.hollowhorizon.hollowengine.client.ui.style.UiTransition
import ru.hollowhorizon.hollowengine.client.ui.style.UiCaretBlinkKeyframes
import ru.hollowhorizon.hollowengine.client.ui.style.UiCaretBlinkPeriodMillis
import ru.hollowhorizon.hollowengine.client.ui.style.UiStylesheetReference
import ru.hollowhorizon.hollowengine.client.ui.text.Shadow
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

typealias HollowUiContent = @Composable () -> Unit

val LocalUiFrameTimeNanos = staticCompositionLocalOf { 0L }
val LocalUiViewport = compositionLocalOf { UiRect.Zero }
val LocalStylesheets = staticCompositionLocalOf<List<UiStylesheetReference>> { emptyList() }

private fun Modifier?.styleReferences(): List<UiStylesheetReference> = when (this) {
    null -> emptyList()
    is CompositeModifier -> flatten().filterIsInstance<StyleImportModifier>().map { it.reference }
    is StyleImportModifier -> listOf(reference)
    else -> emptyList()
}

class HollowUiComposition(
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
) : AutoCloseable {
    private val frameClock = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + coroutineContext + frameClock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val rootNode = BoxNode(
        measurePolicy = UiMeasurePolicies.box(UiBoxMode.STACK),
        modifiers = listOf(
            Modifier.size(UiLength.Fill, UiLength.Fill).alignItems(UiAlign.STRETCH, UiAlign.STRETCH).focusScope(),
        ),
    )
    private val applier = HollowUiApplier(rootNode)
    private val composition = Composition(applier, recomposer)
    private val recomposerJob: Job = scope.launch {
        try {
            recomposer.runRecomposeAndApplyChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HollowEngine.LOGGER.error("Critical error in Compose UI: $e")
        }
    }

    @Volatile
    private var observedChangeCount = recomposer.changeCount

    fun setContent(content: HollowUiContent): BoxNode {
        composition.setContent(content)
        return rootNode
    }

    fun frameRoot(nowNanos: Long = System.nanoTime(), profile: UiProfileFrame? = null): BoxNode {
        val startedAt = if (profile != null) System.nanoTime() else 0L
        val changed = applyPendingChanges(nowNanos)
        if (profile != null) {
            profile.composeNanos += System.nanoTime() - startedAt
            profile.composePasses++
            if (changed) profile.recomposedFrames++
        }
        return rootNode
    }

    fun applyPendingChanges(nowNanos: Long = System.nanoTime()): Boolean {
        pumpPendingChanges(nowNanos)
        val changed = recomposer.changeCount != observedChangeCount
        observedChangeCount = recomposer.changeCount
        return changed
    }

    private fun pumpPendingChanges(nowNanos: Long) {
        Snapshot.sendApplyNotifications()
        if (frameClock.hasAwaiters) frameClock.sendFrame(nowNanos)
    }

    override fun close() {
        scope.cancel()
        composition.dispose()
        recomposer.cancel()
        recomposerJob.cancel()
    }
}

class HollowUiApplier(root: BoxNode) : AbstractApplier<UiNode>(root) {
    override fun insertTopDown(index: Int, instance: UiNode) {
        current.children.add(index, instance)
        instance.layoutState.attachTo(current)
        current.invalidateLayout()
    }

    override fun insertBottomUp(index: Int, instance: UiNode) = Unit

    override fun remove(index: Int, count: Int) {
        repeat(count) {
            current.children.removeAt(index).detachLayoutParentRecursively()
        }
        current.invalidateLayout()
    }

    override fun move(from: Int, to: Int, count: Int) {
        if (from == to || count <= 0) return
        val moving = current.children.subList(from, from + count).toList()
        repeat(count) { current.children.removeAt(from) }
        val target = if (to > from) to - count else to
        current.children.addAll(target, moving)
        current.invalidateLayout()
    }

    override fun onClear() {
        root.children.forEach { it.detachLayoutParentRecursively() }
        root.children.clear()
        root.invalidateLayout()
    }
}

@Composable
fun Layout(
    content: HollowUiContent,
    modifier: Modifier? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
    measurePolicy: UiMeasurePolicy,
) {
    val modifiers = modifier.asList()
    val imports = modifier.styleReferences()
    val scoped: HollowUiContent =
        if (imports.isEmpty()) content
        else {
            {
                CompositionLocalProvider(LocalStylesheets provides LocalStylesheets.current + imports) {
                    content()
                }
            }
        }
    ReusableComposeNode<BoxNode, HollowUiApplier>(
        factory = { BoxNode(id, measurePolicy, tags, modifiers, attributes) },
        update = {
            update(measurePolicy) {
                this.measurePolicy = it
                invalidateLayout()
            }
            updateCommon(modifiers, attributes, tags)
        },
        content = scoped,
    )
}


@Composable
fun Box(
    id: String? = null,
    mode: UiBoxMode = UiBoxMode.FREE,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) = Layout(content, modifier, id, tags, attributes, UiMeasurePolicies.box(mode))

@Composable
fun Column(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) = Layout(content, modifier, id, tags, attributes, UiMeasurePolicies.Column)

@Composable
fun Row(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) = Layout(content, modifier, id, tags, attributes, UiMeasurePolicies.Row)

@Composable
fun Span(
    value: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
) {
    val modifiers = modifier.asList()
    ReusableComposeNode<SpanNode, HollowUiApplier>(
        factory = { SpanNode(value, id, tags, modifiers) },
        update = {
            update(value) {
                text = it
                invalidateLayout()
            }
            updateCommon(modifiers, emptyMap(), tags)
        },
    )
}

@Composable
fun Caret(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
) = Box(
    id = id,
    tags = tags,
    modifier = Modifier
        .animation(UiCaretBlinkKeyframes, UiCaretBlinkPeriodMillis, iterationCount = Float.POSITIVE_INFINITY)
        .then(modifier ?: Modifier),
)

/**
 * A run of text. Built on the inline-flow framework: the literal becomes a [Span] and any
 * [content] (inline widgets, images, nested spans) flows alongside it. Text style props set
 * on the container (font, color, effects, size) inherit down to the span.
 */
@Composable
fun Text(
    value: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) = Layout(
    content = {
        Span(value)
        content()
    },
    modifier = modifier,
    id = id,
    tags = tags,
    attributes = attributes,
    measurePolicy = UiMeasurePolicies.InlineFlow,
)

/** An inline-flow container: compose [Span]s, images and inline widgets inside it directly. */
@Composable
fun Text(
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent,
) = Layout(content, modifier, id, tags, attributes, UiMeasurePolicies.InlineFlow)

@Composable
fun InlineWidget(
    id: String,
    modifier: Modifier? = null,
    tags: Iterable<String> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) {
    Box(id = id, tags = tags, modifier = modifier, attributes = attributes, content = content)
}

@Composable
fun Image(
    source: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) = ContentNode(UiImageType, Modifier.image(source), id, tags, modifier, attributes)

@Composable
fun Element(
    type: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    content: HollowUiContent = {},
) {
    val modifiers = modifier.asList()
    val nodeType = type.lowercase()
    ReusableComposeNode<BaseUiNode, HollowUiApplier>(
        factory = {
            BaseUiNode(
                nodeType,
                id?.removePrefix("#"),
                tags.map { it.removePrefix(".") },
                modifiers,
                attributes,
            )
        },
        update = { updateCommon(modifiers, attributes, tags.map { it.removePrefix(".") }) },
        content = content,
    )
}


@Composable
fun Slider(
    value: Float = 0f,
    min: Float = 0f,
    max: Float = 1f,
    step: Float = 0f,
    onValueChange: ((Float) -> Unit)? = null,
    /** Fired once when the drag/click ends, with the final value for changes too costly to apply live. */
    onValueCommit: ((Float) -> Unit)? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
) {
    var current by remember { mutableStateOf(normalizeSlider(value, min, max, step)) }
    var lastExternal by remember { mutableStateOf(value) }
    if (value != lastExternal) {
        lastExternal = value
        current = normalizeSlider(value, min, max, step)
    }
    val safeCurrent = normalizeSlider(current, min, max, step)
    val range = max - min
    val fraction = if (range == 0f) 0f else ((safeCurrent - min) / range).coerceIn(0f, 1f)

    fun setFromPointer(localX: Float, width: Float) {
        val interactiveWidth = (width - SliderThumbSize).coerceAtLeast(1f)
        val ratio = ((localX - SliderThumbRadius) / interactiveWidth).coerceIn(0f, 1f)
        val next = normalizeSlider(min + range * ratio, min, max, step)
        if (next != current) {
            current = next
            onValueChange?.invoke(next)
        }
    }

    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = tags + "slider",
        modifier = Modifier.size(120.px, 16.px)
            .cursor(UiCursorShape.HAND)
            .onPress { setFromPointer(it.localX, it.width) }
            .onDrag { setFromPointer(it.localX, it.width) }
            .onRelease { onValueCommit?.invoke(current) }
            .then(modifier ?: Modifier),
    ) {
        Row(modifier = Modifier.size(100.percent, 100.percent)) {
            Box(modifier = Modifier.size(SliderThumbRadius.px, 1.px))
            Box(
                mode = UiBoxMode.STACK,
                modifier = Modifier.size(0.px, 100.percent).grow(1f),
            ) {
                Box(
                    tags = listOf("slider-track"),
                    modifier = Modifier.size(100.percent, 4.px).align(vertical = UiAlign.CENTER)
                        .background(SliderTrackColor).borderRadius(2f),
                )
                Box(
                    tags = listOf("slider-active"),
                    modifier = Modifier.size((fraction * 100f).percent, 4.px).align(vertical = UiAlign.CENTER)
                        .background(WidgetAccentColor).borderRadius(2f),
                )
                Box(
                    tags = listOf("slider-thumb"),
                    modifier = Modifier.size(SliderThumbSize.px, SliderThumbSize.px).align(vertical = UiAlign.CENTER)
                        .position((fraction * 100f).percent - SliderThumbRadius.px, 0.px)
                        .background(UiColor.White)
                        .border(1.px, WidgetThumbBorderColor, radius = SliderThumbRadius),
                )
            }
            Box(modifier = Modifier.size(SliderThumbRadius.px, 1.px))
        }
    }
}

/**
 * A checkbox / radio / switch. The checked flag lives in Compose state ([remember]);
 * [checked] is adopted when the caller changes it, and clicks report back through
 * [onCheckedChange]. While checked the box carries [UiState.SELECTED] for `:selected` styling.
 */
@Composable
fun Checkbox(
    checked: Boolean = false,
    variant: UiCheckboxVariant = UiCheckboxVariant.CHECKBOX,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
) {
    var current by remember { mutableStateOf(checked) }
    var lastExternal by remember { mutableStateOf(checked) }
    if (checked != lastExternal) {
        lastExternal = checked
        current = checked
    }

    val toggle: (UiEvent) -> Unit = {
        val next = if (variant == UiCheckboxVariant.RADIO) true else !current
        if (next != current) {
            current = next
            onCheckedChange?.invoke(next)
        }
    }
    val base = Modifier.cursor(UiCursorShape.HAND).onClick(toggle)
        .let { if (current) it.state(UiState.SELECTED) else it }
    val progress by animateFloatAsState(if (current) 1f else 0f, durationMillis = CheckboxToggleMillis)

    when (variant) {
        UiCheckboxVariant.SWITCH -> Box(
            id = id,
            mode = UiBoxMode.STACK,
            tags = tags + "checkbox",
            modifier = base.size(28.px, 16.px).borderRadius(8f)
                .background(CheckboxTrackColor.interpolate(WidgetAccentColor, progress))
                .then(modifier ?: Modifier),
        ) {
            Box(
                tags = listOf("checkbox-mark"),
                modifier = Modifier.size(12.px, 12.px).align(vertical = UiAlign.CENTER)
                    .position((2f + 12f * progress).px, 0.px)
                    .background(UiColor.White).borderRadius(6f),
            )
        }

        else -> {
            val circle = variant == UiCheckboxVariant.RADIO
            Box(
                id = id,
                mode = UiBoxMode.STACK,
                tags = tags + "checkbox",
                modifier = base.size(16.px, 16.px).borderRadius(if (circle) 8f else 3f)
                    .background(UiColor.Transparent.interpolate(WidgetAccentColor, progress))
                    .border(1.px, WidgetCheckboxBorderColor, radius = if (circle) 8f else 3f)
                    .then(modifier ?: Modifier),
            ) {
                if (progress > 0.001f) {
                    Box(
                        tags = listOf("checkbox-mark"),
                        modifier = Modifier.size(8.px, 8.px).align(UiAlign.CENTER, UiAlign.CENTER)
                            .opacity(progress)
                            .background(UiColor.White).borderRadius(if (circle) 4f else 2f),
                    )
                }
            }
        }
    }
}

private fun normalizeSlider(raw: Float, min: Float, max: Float, step: Float): Float {
    val low = minOf(min, max)
    val high = maxOf(min, max)
    val clamped = raw.coerceIn(low, high)
    if (step <= 0f) return clamped
    val stepped = min + ((clamped - min) / step).roundToInt() * step
    return stepped.coerceIn(low, high)
}

/** How long a popup takes to fade and settle into place, and how far above it starts. */
private const val PopupAppearMillis = 90L
private const val PopupAppearRise = 4f

private val SliderTrackColor = UiColor(0.24f, 0.27f, 0.32f, 1f)
private const val SliderThumbSize = 12f
private const val SliderThumbRadius = SliderThumbSize / 2f
private const val CheckboxToggleMillis = 140L
private val CheckboxTrackColor = UiColor(0.24f, 0.27f, 0.32f, 1f)

private sealed interface HssShadowResolution {
    data object Inherit : HssShadowResolution
    data class Override(val shadow: Shadow?) : HssShadowResolution
}
private val WidgetAccentColor = UiColor(0.36f, 0.62f, 0.95f, 1f)
private val WidgetThumbBorderColor = UiColor(0.06f, 0.07f, 0.08f, 0.45f)
private val WidgetCheckboxBorderColor = UiColor(0.55f, 0.6f, 0.68f, 1f)

/**
 * Value/onChange convenience wrapper over [EditableTextField]: owns a [TextFieldState], mirrors
 * external [value] changes into it and reports edits back through [onChange]. The state (carets,
 * selection, undo history) lives for as long as the composition does.
 */
@Composable
fun TextField(
    value: String = "",
    mode: UiTextFieldMode = UiTextFieldMode.SINGLE_LINE,
    filter: UiTextInputFilter = UiTextInputFilter.ANY,
    multiCaret: Boolean = false,
    syntaxHighlighter: UiSyntaxHighlighter? = null,
    completionContributor: UiCompletionContributor? = null,
    indentSize: Int? = null,
    autoPairs: Boolean = false,
    readOnly: Boolean = false,
    diagnostics: List<UiTextDiagnostic> = emptyList(),
    searchMatches: List<IntRange> = emptyList(),
    activeSearchMatch: IntRange? = null,
    inlayHints: List<UiInlayHint> = emptyList(),
    inlayHintsProvider: UiInlayHintsProvider? = null,
    inlayRevision: Long = 0L,
    completionRevision: Long = 0L,
    signatureHelpProvider: UiSignatureHelpProvider? = null,
    hoverInfoProvider: UiHoverInfoProvider? = null,
    codeInsightRevision: Long = completionRevision,
    placeholder: String = "",
    wrap: Boolean? = null,
    fontSize: Float = DefaultUiFontSize,
    fontFamily: String? = null,
    textShadow: Shadow? = Shadow(offsetX = 1f, offsetY = 1f),
    caretColor: UiColor? = null,
    selectionColor: UiColor? = null,
    lineNumbers: Boolean = false,
    lineNumberColor: UiColor? = null,
    indentGuides: Boolean = false,
    indentGuideColor: UiColor? = null,
    onChange: ((String) -> Unit)? = null,
    onInlayAction: ((UiInlayAction) -> Unit)? = null,
    state: TextFieldState? = null,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) {
    val multiline = mode == UiTextFieldMode.MULTI_LINE
    val internalState = remember {
        state ?: TextFieldState(
            initialText = value,
            multiline = multiline,
            readOnly = readOnly,
            filter = filter,
            indentSize = indentSize,
            autoPairs = autoPairs,
            multiCaret = multiCaret,
            fontSize = fontSize,
            fontFamily = fontFamily,
            wrap = wrap ?: multiline,
        )
    }
    val fieldState = state ?: internalState
    fieldState.multiline = multiline
    fieldState.readOnly = readOnly
    fieldState.filter = filter
    fieldState.indentSize = indentSize
    fieldState.autoPairs = autoPairs
    fieldState.multiCaret = multiCaret
    fieldState.fontSize = fontSize
    fieldState.fontFamily = fontFamily
    fieldState.wrap = wrap ?: multiline
    var hssShadow by remember { mutableStateOf<HssShadowResolution>(HssShadowResolution.Inherit) }
    var hssInlayHints by remember { mutableStateOf(true) }
    fieldState.textShadow = when (val resolution = hssShadow) {
        is HssShadowResolution.Override -> resolution.shadow
        HssShadowResolution.Inherit -> textShadow
    }
    if (caretColor != null) fieldState.caretColor = caretColor
    if (selectionColor != null) fieldState.selectionColor = selectionColor

    val sync = remember { TextFieldValueSync(value) }
    // Adopt an external value only when the parameter itself changed; edits made here win otherwise.
    if (value != sync.lastExternal) {
        val echoedNotification = sync.acknowledge(value)
        if (!echoedNotification && value != fieldState.text) {
            fieldState.setText(value, moveCaretToEnd = false)
            sync.reset(fieldState.text)
        }
    }
    sync.updateExternal(value)
    val text = fieldState.text
    SideEffect {
        if (text != sync.lastNotified) {
            sync.recordNotification(text)
            onChange?.invoke(text)
        }
    }

    EditableTextField(
        state = fieldState,
        modifier = (modifier ?: Modifier).onResolvedStyle { style ->
            val textFieldStyle = style[UiProps.TextField]
            val next = if (textFieldStyle.textShadowSet) {
                HssShadowResolution.Override(textFieldStyle.textShadow)
            } else {
                HssShadowResolution.Inherit
            }
            if (next != hssShadow) hssShadow = next
            val hintsEnabled = textFieldStyle.inlayHints != false
            if (hintsEnabled != hssInlayHints) hssInlayHints = hintsEnabled
        },
        id = id,
        tags = tags,
        syntaxHighlighter = syntaxHighlighter,
        inlayHints = if (hssInlayHints) inlayHints else emptyList(),
        inlayHintsProvider = inlayHintsProvider.takeIf { hssInlayHints },
        inlayRevision = inlayRevision,
        onInlayAction = onInlayAction,
        completionContributor = completionContributor,
        completionRevision = completionRevision,
        signatureHelpProvider = signatureHelpProvider,
        hoverInfoProvider = hoverInfoProvider,
        codeInsightRevision = codeInsightRevision,
        diagnostics = diagnostics,
        searchMatches = searchMatches,
        activeSearchMatch = activeSearchMatch,
        placeholder = placeholder,
        lineNumbers = lineNumbers,
        lineNumberColor = lineNumberColor ?: EditableFieldLineNumberColor,
        indentGuides = indentGuides,
        indentGuideColor = indentGuideColor ?: EditableFieldIndentGuideColor,
        attributes = attributes,
    )
}

@Composable
fun Item(
    item: ItemStack,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) = ContentNode(UiItemType, Modifier.item(item), id, tags, modifier, attributes)

/** Shorthand for a plain, single-count item id such as `minecraft:diamond`. */
@Composable
fun Item(
    item: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) = ContentNode(UiItemType, Modifier.item(item), id, tags, modifier, attributes)

/** Draws a live entity from this client's level, with whatever it is wearing and doing. */
@Composable
fun Entity(
    entity: Entity,
    view: UiEntityView = UiEntityView.Portrait,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) = ContentNode(UiEntityType, Modifier.entity(entity, view), id, tags, modifier, attributes)

/**
 * Draws the entity this client knows by [networkId], how a server-driven UI names one, since an
 * entity itself cannot travel. Nothing is drawn while the client has no entity under that id.
 */
@Composable
fun Entity(
    networkId: Int,
    view: UiEntityView = UiEntityView.Portrait,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
) = ContentNode(UiEntityType, Modifier.entity(networkId, view), id, tags, modifier, attributes)


@Composable
private fun ContentNode(
    type: String,
    contentModifier: Modifier,
    id: String?,
    tags: Iterable<String>,
    modifier: Modifier?,
    attributes: Map<String, String>,
) {
    val modifiers = listOf(if (modifier == null) contentModifier else contentModifier then modifier)
    ReusableComposeNode<BaseUiNode, HollowUiApplier>(
        factory = {
            BaseUiNode(type, id?.removePrefix("#"), tags.map { it.removePrefix(".") }, modifiers, attributes)
        },
        update = { updateCommon(modifiers, attributes, tags) },
    )
}

/** Popups render above everything; the OverlayHost carries this layer so all passes prefer it. */
const val OverlayLayer = Int.MAX_VALUE / 2

/**
 * Layer of the popup whose content is being composed. A popup opened from inside another one (a dropdown
 * in a modal dialog) is lifted to at least that layer, so it is never drawn beneath the popup it came from.
 */
private val LocalPopupLayer = compositionLocalOf { Int.MIN_VALUE }

/** Handle passed to a [Popup]'s content so it can close itself (`dismiss()`) without external state. */
interface PopupScope {
    fun dismiss()
}

typealias PopupContent = @Composable PopupScope.() -> Unit

/**
 * An overlay anchored to [anchorBounds] in root coordinates (from `Modifier.onPlaced` or a cursor
 * point). Composed out of line by the surface's [OverlayHost] so it is never clipped and sits on top;
 * registers with the [OverlayManager] so an outside click or Escape closes it (when [dismissOnOutside]).
 */
@Composable
fun Popup(
    anchorBounds: UiRect,
    alignment: UiPopupAlignment = UiPopupAlignment.BelowStart,
    layer: Int = 0,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    attributes: Map<String, String> = emptyMap(),
    dismissOnOutside: Boolean = true,
    modal: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    animated: Boolean = true,
    visible: Boolean = true,
    content: PopupContent = {},
) {
    val manager = LocalOverlayManager.current ?: return
    val anchor = rememberUpdatedState(anchorBounds)
    val align = rememberUpdatedState(alignment)
    val popupTags = rememberUpdatedState(tags)
    val popupModifier = rememberUpdatedState(modifier)
    val popupAttributes = rememberUpdatedState(attributes)
    val popupContent = rememberUpdatedState(content)
    val dismiss = rememberUpdatedState(onDismiss)
    val stylesheets = rememberUpdatedState(LocalStylesheets.current)

    val scope = remember {
        object : PopupScope {
            override fun dismiss() {
                dismiss.value?.invoke()
            }
        }
    }
    val isModal = rememberUpdatedState(modal)
    val parentLayer = LocalPopupLayer.current
    val entry = remember {
        PopupEntry(Any()).apply {
            this.content = {
                PopupNodeEmitter(
                    anchorBounds = anchor.value,
                    alignment = align.value,
                    id = id,
                    tags = popupTags.value,
                    modifier = popupModifier.value,
                    attributes = popupAttributes.value,
                    stylesheets = stylesheets.value,
                    modal = isModal.value,
                    exiting = exiting,
                    animated = this.animated,
                    visible = this.visible,
                ) {
                    CompositionLocalProvider(LocalPopupLayer provides this.layer) { popupContent.value.invoke(scope) }
                }
            }
        }
    }
    SideEffect {
        entry.layer = maxOf(layer, parentLayer)
        entry.dismissOnOutside = dismissOnOutside
        entry.onDismiss = onDismiss
        entry.animated = animated
        entry.visible = visible
    }
    DisposableEffect(manager, entry) {
        val unregister = manager.register(entry)
        onDispose { unregister() }
    }
}

@Composable
private fun PopupNodeEmitter(
    anchorBounds: UiRect,
    alignment: UiPopupAlignment,
    id: String?,
    tags: Iterable<String>,
    modifier: Modifier?,
    attributes: Map<String, String>,
    stylesheets: List<UiStylesheetReference>,
    modal: Boolean,
    exiting: Boolean,
    animated: Boolean,
    visible: Boolean,
    content: HollowUiContent,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        withFrameNanos { }
        appeared = true
    }
    if (!visible) return
    val shown = appeared && !exiting
    val appearance = if (!animated) Modifier else Modifier
        .transition(
            UiTransition(UiProps.Opacity.name, PopupAppearMillis, TransitionEasing.EASE_OUT),
            UiTransition(UiProps.Translate.name, PopupAppearMillis, TransitionEasing.EASE_OUT),
        )
        .opacity(if (shown) 1f else 0f)
        .translate(y = if (shown) 0f else -PopupAppearRise)

    val base = appearance.then(modifier ?: Modifier)
        .let { if (exiting) it.inputTransparent() else it.focusScope().input(hoverable = true) }
        .let { if (modal && !exiting) it.modal() else it }
    val modifiers = stylesheets.fold(base) { acc, ref ->
        acc.style(ref)
    }.asList()
    val values = PopupValues(anchorBounds, alignment)
    val styledContent: HollowUiContent = {
        CompositionLocalProvider(LocalStylesheets provides stylesheets) { content() }
    }
    ReusableComposeNode<PopupNode, HollowUiApplier>(
        factory = { PopupNode(anchorBounds, alignment, id, tags, modifiers, attributes) },
        update = {
            update(values) {
                apply(it)
                invalidateLayout()
            }
            updateCommon(modifiers, attributes, tags)
        },
        content = styledContent,
    )
}

/**
 * Renders the surface's overlays above the content. While any popup is open it composes a top-layer
 * stack holding: an optional full-screen dismiss catcher (a [focusScope], hence always key-active, so
 * it closes every dismissable overlay on an outside click or Escape)
 */
@Composable
fun OverlayHost() {
    val manager = LocalOverlayManager.current ?: return
    if (manager.popups.isEmpty()) return
    Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent).layer(OverlayLayer)) {
        if (manager.hasDismissable) {
            Box(
                tags = listOf("overlay-dismiss"),
                modifier = Modifier.size(100.percent, 100.percent)
                    .focusScope()
                    .onClick { manager.dismissAll() }
                    .onKeyInput { input ->
                        if (input.key == GLFW.GLFW_KEY_ESCAPE) {
                            manager.dismissAll()
                            input.consume()
                        }
                    },
            )
        }
        Layout(
            content = {
                for (entry in manager.popups.sortedBy { it.layer }) {
                    key(entry.key) {
                        entry.content()
                        if (entry.exiting) {
                            LaunchedEffect(entry) {
                                delay(PopupAppearMillis)
                                manager.remove(entry)
                            }
                        }
                    }
                }
            },
            modifier = Modifier.size(100.percent, 100.percent),
            measurePolicy = PopupOverlayMeasurePolicy,
        )
    }
}

private data class PopupValues(
    val anchorBounds: UiRect,
    val alignment: UiPopupAlignment,
)

private fun PopupNode.apply(values: PopupValues) {
    anchorBounds = values.anchorBounds
    alignment = values.alignment
}

fun <T : BaseUiNode> Updater<T>.updateCommon(
    modifiers: List<Modifier>,
    attributes: Map<String, String>,
    tags: Iterable<String>? = null,
) {
    if (tags != null) {
        val normalizedTags = tags.map { it.removePrefix(".") }.toSet()
        update(normalizedTags) {
            if (this.tags == it) return@update
            this.tags.clear()
            this.tags += it
        }
    }
    update(modifiers) {
        this.modifiers.clear()
        this.modifiers += it
    }
    update(attributes) {
        replaceCustomAttributes(it)
    }
}

private fun BaseUiNode.replaceCustomAttributes(attributes: Map<String, String>) {
    this.attributes.clear()
    this.attributes += attributes
}

private fun Modifier?.asList(): List<Modifier> = this?.let(::listOf).orEmpty()
