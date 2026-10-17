package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

@Composable
fun NumberField(
    value: Double,
    id: String? = null,
    label: String? = null,
    whole: Boolean = false,
    min: Double = Double.NEGATIVE_INFINITY,
    max: Double = Double.POSITIVE_INFINITY,
    step: Double? = null,
    slider: Boolean = false,
    format: (Double) -> String = { formatUiNumber(it, whole) },
    tags: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onChange: (Double) -> Unit,
) {
    val fieldId = id ?: remember { "number-field-${NumberFieldIds.next()}" }
    val gesture = NumberFieldGesture
    val focus = LocalUiFocusRequester.current
    val wrap = LocalUiPointerWrap.current
    val viewport = LocalUiViewport.current
    val width = remember(fieldId) { floatArrayOf(0f) }
    val bounded = min.isFinite() && max.isFinite() && min < max
    val isSlider = slider && bounded
    val unit = step ?: if (whole) 1.0 else DEFAULT_STEP

    /** The step [modifiers] make of [unit]: ten times finer with Shift, ten times coarser with Ctrl. */
    fun stepFor(modifiers: Int): Double = when {
        modifiers and GLFW.GLFW_MOD_SHIFT != 0 -> unit * FINE_FACTOR
        modifiers and GLFW.GLFW_MOD_CONTROL != 0 -> unit * COARSE_FACTOR
        else -> unit
    }

    fun limit(next: Double): Double {
        val stepped = if (whole) round(next) else (next * PRECISION).roundToLong() / PRECISION
        return stepped.coerceIn(min, max)
    }

    fun commit(next: Double) {
        if (next == value) return
        onGestureStart()
        onChange(next)
        onGestureEnd()
    }

    if (gesture.editing == fieldId) {
        // A field taken away while typed into does not open typed into when it comes back.
        DisposableEffect(fieldId) { onDispose { if (gesture.editing == fieldId) gesture.editing = null } }
        NumberFieldEditor("$fieldId-edit", value, whole, tags, modifier) { typed ->
            if (gesture.editing == fieldId) gesture.editing = null
            typed?.let { commit(limit(it)) }
        }
        return
    }

    val shown = gesture.dragged?.takeIf { it.first == fieldId }?.second ?: value
    val rootTags = buildList {
        add("number-field")
        if (isSlider) add("slider")
        if (label != null) add("labeled")
        addAll(tags)
    }
    Box(
        id = fieldId,
        tags = rootTags,
        modifier = modifier.input(hoverable = true).onPlaced { width[0] = it.width },
    ) {
        Box(mode = UiBoxMode.STACK, tags = listOf("number-field-frame")) {
            if (isSlider) Box(
                tags = listOf("number-field-fill"),
                modifier = Modifier.size((((shown - min) / (max - min)).coerceIn(0.0, 1.0) * 100).percent, 100.percent)
                    .inputTransparent(),
            )
            Row(
                id = "$fieldId-drag",
                tags = listOf("number-field-body"),
                modifier = Modifier.size(100.percent, 100.percent)
                    .input(hoverable = true, clickable = true, draggable = true)
                    .cursor(UiCursorShape.RESIZE_HORIZONTAL)
                    .onPress { event ->
                        if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                        gesture.press(fieldId, value)
                        event.consume()
                    }.onDrag { event ->
                        if (gesture.pressed != fieldId) return@onDrag
                        val total = event.dragTotalX + gesture.shiftX
                        if (!gesture.dragging && abs(total) >= DRAG_THRESHOLD) {
                            gesture.dragging = true
                            onGestureStart()
                            wrap?.hold(viewport, event.x, event.y, gesture.onWrap)
                        }
                        if (gesture.dragging) {
                            val fine = event.modifiers and GLFW.GLFW_MOD_SHIFT != 0
                            val snap = event.modifiers and GLFW.GLFW_MOD_CONTROL != 0
                            val stepped = if (isSlider) unit else stepFor(event.modifiers)
                            val perPixel = if (isSlider && width[0] > 0f) {
                                (max - min) / width[0] * if (fine) FINE_FACTOR else 1.0
                            } else {
                                stepped / PIXELS_PER_STEP
                            }
                            if (gesture.pace != 0.0 && perPixel != gesture.pace) {
                                gesture.start = gesture.dragged?.second ?: gesture.start
                                gesture.base = gesture.last
                            }
                            gesture.pace = perPixel
                            gesture.last = total
                            var next = gesture.start + (total - gesture.base) * perPixel
                            if (snap) next = round(next / stepped) * stepped
                            next = limit(next)
                            if (next != gesture.dragged?.second) {
                                gesture.dragged = fieldId to next
                                onChange(next)
                            }
                        }
                        event.consume()
                    }.onRelease { event ->
                        if (gesture.pressed != fieldId) return@onRelease
                        gesture.pressed = null
                        if (gesture.dragging) {
                            gesture.dragging = false
                            wrap?.release(gesture.onWrap)
                            gesture.dragged = null
                            onGestureEnd()
                        } else {
                            gesture.editing = fieldId
                            focus.request("$fieldId-edit")
                        }
                        event.consume()
                    },
            ) {
                label?.let {
                    Text(
                        it,
                        tags = listOf("number-field-label"),
                        modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
                    )
                }
                Text(
                    format(shown),
                    tags = listOf("number-field-value"),
                    modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS)
                        .then(if (label == null) Modifier.size(0.px, UiLength.Fit).grow(1f) else Modifier),
                )
            }
            if (!isSlider) {
                NumberFieldArrow("$fieldId-down", LeftIcon, UiAlign.START) { commit(limit(shown - stepFor(it))) }
                NumberFieldArrow("$fieldId-up", RightIcon, UiAlign.END) { commit(limit(shown + stepFor(it))) }
            }
        }
    }
}

@Composable
private fun NumberFieldEditor(
    id: String,
    value: Double,
    whole: Boolean,
    tags: List<String>,
    modifier: Modifier,
    onDone: (Double?) -> Unit,
) {
    val text = remember(id) { TextFieldState(initialText = formatUiNumber(value, whole, EDIT_PLACES)).also { it.selectAll() } }
    val finished = remember(id) { booleanArrayOf(false) }
    fun finish(typed: Double?) {
        if (finished[0]) return
        finished[0] = true
        onDone(typed)
    }

    TextField(
        state = text,
        id = id,
        filter = if (whole) UiTextInputFilter.INTEGER else UiTextInputFilter.DECIMAL,
        fontSize = 9f,
        tags = listOf("number-field", "editing") + tags,
        modifier = modifier.onUnfocus { finish(text.text.toDoubleOrNull()) }.onKeyInput { input ->
            when (input.key) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> finish(text.text.toDoubleOrNull())
                GLFW.GLFW_KEY_ESCAPE -> finish(null)
                else -> return@onKeyInput
            }
            input.consume()
        },
    )
}

@Composable
private fun NumberFieldArrow(id: String, icon: String, side: UiAlign, onStep: (Int) -> Unit) {
    Box(
        id = id,
        tags = listOf("number-field-arrow"),
        modifier = Modifier.align(horizontal = side).input(hoverable = true, clickable = true).onClick { event ->
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
            onStep(event.modifiers)
            event.consume()
        },
    ) {
        Image(icon, tags = listOf("number-field-arrow-icon"))
    }
}

private object NumberFieldGesture {
    var pressed: String? = null
    var dragging = false

    /** The value the drag counts from, the drag distance it counts from, and how much a pixel is worth since. */
    var start = 0.0
    var base = 0f
    var pace = 0.0

    /** How far the drag had got by the event before, where another pace takes over from. */
    var last = 0f

    /** How far the pointer was wrapped around the window during the drag, added back to it. */
    var shiftX = 0f
    val onWrap: (Float, Float) -> Unit = { dx, _ -> shiftX += dx }

    /** The field being dragged and the value it shows meanwhile. */
    var dragged by mutableStateOf<Pair<String, Double>?>(null)

    /** The field being typed into. */
    var editing by mutableStateOf<String?>(null)

    fun press(id: String, value: Double) {
        pressed = id
        dragging = false
        start = value
        base = 0f
        pace = 0.0
        last = 0f
        shiftX = 0f
    }
}

private object NumberFieldIds {
    private var last = 0

    fun next(): Int = ++last
}

/** [value] as a field shows it: whole, or with at most [places] decimals and no trailing zeros. */
fun formatUiNumber(value: Double, whole: Boolean, places: Int = SHOWN_PLACES): String {
    if (whole) return value.toLong().toString()
    if (!value.isFinite()) return value.toString()
    return BigDecimal(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

private const val DEFAULT_STEP = 0.1
private const val SHOWN_PLACES = 3
private const val EDIT_PLACES = 6

/** How finely a dragged value is kept, so a drag does not leave float noise behind. */
private const val PRECISION = 100_000.0

/** Pixels of drag for one [NumberField] step. */
private const val PIXELS_PER_STEP = 8.0
private const val FINE_FACTOR = 0.1
private const val COARSE_FACTOR = 10.0
private const val DRAG_THRESHOLD = 3f

private const val LeftIcon = "hollowengine:textures/gui/icons/chevron_left.svg"
private const val RightIcon = "hollowengine:textures/gui/icons/chevron_right.svg"
