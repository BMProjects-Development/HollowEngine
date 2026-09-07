package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.models.*
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L
private const val LayerListWidth = 190f
private const val InspectorWidth = 240f
private const val MinPanelWidth = 150f
private const val MaxPanelWidth = 420f

/**
 * The editor for a `.animator` file.
 *
 * The stack of layers on the left, and what the selected layer is: a graph for a controller, nothing but
 * parameters for a clip. A layer with no states has no graph to show, so it does not get an empty one.
 */
@Composable
internal fun HollowIdeAnimatorEditor(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeAnimatorDocument
    val view = remember(document) { AnimatorCanvasState() }
    var selection by remember(document) { mutableStateOf<AnimatorSelection>(AnimatorSelection.None) }
    var openLayer by remember(document) { mutableStateOf(document.animator.layers.firstOrNull()?.id) }
    var layersFolded by remember(document) { mutableStateOf(false) }

    var layersSize by remember(document) { mutableStateOf(LayerListWidth) }
    var inspectorSize by remember(document) { mutableStateOf(InspectorWidth) }
    val layersWidth = remember(document) { SpringFloat(LayerListWidth) }
    val inspectorWidth = remember(document) { SpringFloat(0f) }
    layersWidth.target = if (layersFolded) 0f else layersSize

    LaunchedEffect(document) {
        while (true) {
            withFrameNanos { frame ->
                layersWidth.advance(frame)
                inspectorWidth.advance(frame)
            }
        }
    }

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified) file.save()
    }

    val layers = document.animator.layers
    val current = openLayer?.takeIf { id -> layers.any { it.id == id } } ?: layers.firstOrNull()?.id
    val graphed = current?.let { document.animator.controller(it) } != null
    val inspectorOpen = selection != AnimatorSelection.None
    val lastShown = remember(document) { arrayOfNulls<AnimatorSelection>(1) }
    if (inspectorOpen) lastShown[0] = selection
    val lastSelection = lastShown[0] ?: AnimatorSelection.None

    fun retarget(next: AnimatorSelection) {
        selection = next
        (next as? AnimatorSelection.Layer)?.let { openLayer = it.layerId }
    }

    val foldButton: @Composable () -> Unit = {
        AnimatorIconButton(
            icon = if (layersFolded) MaximizeIcon else MinimizeIcon,
            tooltip = if (layersFolded) animatorText("show_layers") else animatorText("hide_layers"),
            size = 11f,
        ) { layersFolded = !layersFolded }
    }

    Row(
        modifier = Modifier.size(100.percent, 100.percent)
            .style(AnimatorStylesheet)
            .background(AnimatorColors.Canvas)
            .focusScope(),
    ) {
        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(layersWidth.value.px, 100.percent).clip(true)) {
            LayerList(
                document = document,
                openLayer = current,
                selection = selection,
                onOpen = { id ->
                    openLayer = id
                    selection = AnimatorSelection.Layer(id)
                },
                modifier = Modifier.size(layersSize.px, 100.percent),
            )
        }

        if (!layersFolded) {
            Splitter(layersSize) { next ->
                layersSize = next.coerceIn(MinPanelWidth, MaxPanelWidth)
                layersWidth.snapTo(layersSize)
            }
        }

        if (graphed) {
            AnimatorGraphCanvas(
                document = document,
                layerId = current,
                selection = selection,
                view = view,
                chrome = CanvasChrome(layersFolded = layersFolded, foldLayers = foldButton),
                onSelect = ::retarget,
                modifier = Modifier.size(0.px, 100.percent).grow(1f),
            )

            inspectorWidth.target = if (inspectorOpen) inspectorSize else 0f
            if (inspectorOpen) {
                Splitter(inspectorSize, reversed = true) { next ->
                    inspectorSize = next.coerceIn(MinPanelWidth, MaxPanelWidth)
                    inspectorWidth.snapTo(inspectorSize)
                }
            }
            Box(mode = UiBoxMode.STACK, modifier = Modifier.size(inspectorWidth.value.px, 100.percent).clip(true)) {
                AnimatorInspector(
                    document = document,
                    selection = lastSelection,
                    onSelect = ::retarget,
                    modifier = Modifier.size(inspectorSize.px, 100.percent),
                )
            }
        } else {
            AnimatorInspector(
                document = document,
                selection = if (inspectorOpen) selection else current?.let(AnimatorSelection::Layer)
                    ?: AnimatorSelection.None,
                onSelect = ::retarget,
                leading = foldButton,
                modifier = Modifier.size(0.px, 100.percent).grow(1f),
            )
        }
    }
}

/**
 * Drag to give a panel more or less room.
 */
@Composable
private fun Splitter(width: Float, reversed: Boolean = false, onResize: (Float) -> Unit) {
    val start = remember { floatArrayOf(width) }
    Box(
        tags = listOf("animator-splitter"),
        modifier = Modifier
            .input(hoverable = true, draggable = true)
            .onPress { start[0] = width }
            .onDrag { event ->
                onResize(start[0] + if (reversed) -event.dragTotalX else event.dragTotalX)
                event.consume()
            },
    )
}

@Composable
private fun LayerList(
    document: HollowIdeAnimatorDocument,
    openLayer: String?,
    selection: AnimatorSelection,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    var addButton by remember { mutableStateOf(UiRect.Zero) }
    var addMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(AnimatorColors.Panel)
            .border(1.px, AnimatorColors.Border)
            .padding(8.px)
            .gap(4.px),
    ) {
        Text(animatorText("layers"), modifier = Modifier.fontSize(11f).foreground(AnimatorColors.Muted))

        Column(modifier = Modifier.size(100.percent, 0.px).grow(1f).gap(3.px).scrollable(horizontal = false)) {
            document.animator.layers.forEach { layer ->
                LayerRow(
                    layer = layer,
                    open = layer.id == openLayer,
                    selected = selection == AnimatorSelection.Layer(layer.id),
                    onOpen = { onOpen(layer.id) },
                    onDelete = { document.edit { it.withoutLayer(layer.id) } },
                )
            }

            AnimatorButton(
                animatorText("add_layer"),
                modifier = Modifier.size(100.percent, 24.px).onPlaced { addButton = it },
            ) {
                addMenuOpen = true
            }
        }
    }

    if (addMenuOpen) {
        ContextMenu(
            id = "animator-add-layer",
            anchorBounds = addButton,
            items = AnimatorLayerTypes.all.mapNotNull { type ->
                val createDefault = type.createDefault ?: return@mapNotNull null
                UiDropdownItem(type.title()) {
                    val id = freeLayerId(document.animator, type.id.substringAfterLast('/'))
                    document.edit { it.withLayer(createDefault().withCommon(id = id)) }
                    onOpen(id)
                }
            },
            onExpandedChange = { if (!it) addMenuOpen = false },
        )
    }
}

@Composable
private fun LayerRow(
    layer: AnimatorLayerSpec,
    open: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        tags = listOf("animator-layer-row") +
                (if (open) listOf("open") else emptyList()) +
                (if (selected) listOf("selected") else emptyList()),
        modifier = Modifier
            .input(hoverable = true, clickable = true)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onOpen()
                event.consume()
            },
    ) {
        Column(modifier = Modifier.size(0.px, 100.percent).grow(1f).clip(true)) {
            Text(
                layer.id,
                modifier = Modifier.size(100.percent).fontSize(9f).foreground(AnimatorColors.Text)
                    .textWrap(false).textOverflow(UiTextOverflow.DOTS),
            )
            Text(
                "${layer.kindName()} · ${layer.priority}",
                modifier = Modifier.size(100.percent).fontSize(8f).foreground(AnimatorColors.Muted)
                    .textWrap(false).textOverflow(UiTextOverflow.DOTS),
            )
        }
        DeleteHandle(onDelete)
    }
}

@Composable
private fun DeleteHandle(onDelete: () -> Unit) {
    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("animator-delete"),
        modifier = Modifier
            .input(hoverable = true, clickable = true)
            .onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onDelete()
                event.consume()
            },
    ) {
        Text("×", tags = listOf("animator-delete-label"))
    }
}

internal fun freeLayerId(animator: Animator, prefix: String): String {
    val taken = animator.layers.map { it.id }.toSet()
    var index = 1
    while ("${prefix}_$index" in taken) index++
    return "${prefix}_$index"
}
