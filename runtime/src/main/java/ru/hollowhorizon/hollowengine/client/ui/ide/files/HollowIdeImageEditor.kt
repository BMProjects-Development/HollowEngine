package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.ColorPicker
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCheckboxVariant
import ru.hollowhorizon.hollowengine.client.ui.widgets.checkerboard
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

@Composable
internal fun HollowIdeImageEditor(
    file: HollowIdeOpenFile,
    onSave: () -> Unit,
) {
    val document = file.document as HollowIdeImageDocument
    if (document.readOnly) {
        HollowIdeReadOnlyImageViewer(document)
        return
    }
    val canvasScroll = remember(document) { UiScrollHandle() }
    val stroke = remember(document) { ImageStrokeState() }
    val recentColors = remember(document) { mutableStateListOf<UiColor>() }
    var tool by remember(document) { mutableStateOf(ImageTool.BRUSH) }
    var brushColor by remember(document) { mutableStateOf(UiColor.Black) }
    var brushRadius by remember(document) { mutableStateOf(0) }
    var zoom by remember(document) { mutableStateOf(initialZoom(document)) }
    var showCheckerboard by remember(document) { mutableStateOf(true) }
    var showPixelGrid by remember(document) { mutableStateOf(false) }
    var uploadRevision by remember(document) { mutableStateOf(0L) }

    fun requestUpload() {
        file.markDirty()
        uploadRevision++
    }

    fun finishStroke() {
        val edit = stroke.edit ?: return
        if (document.commit(edit)) uploadRevision++
        stroke.clear()
        file.updateDirty(document.isModified)
    }

    fun addRecentColor(color: UiColor) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > MaxRecentColors) recentColors.removeAt(recentColors.lastIndex)
    }

    fun applyHistory(redo: Boolean) {
        finishStroke()
        if (redo) document.history.redo() else document.history.undo()
    }

    val historyPosition = document.history.position
    LaunchedEffect(document, historyPosition) {
        file.updateDirty(document.isModified)
        uploadRevision++
    }

    fun zoomAt(event: UiEvent) {
        if (!event.isCtrlDown() || event.rawScrollY == 0f) return
        finishStroke()
        val previousZoom = zoom
        val nextZoom = (previousZoom * ZoomStep.pow(event.rawScrollY.toDouble()).toFloat())
            .coerceIn(MinZoom, MaxZoom)
        if (nextZoom == previousZoom) {
            event.consume()
            return
        }
        val localX = event.x - canvasScroll.viewport.x
        val localY = event.y - canvasScroll.viewport.y
        val imageX = (canvasScroll.offsetX + localX) / previousZoom
        val imageY = (canvasScroll.offsetY + localY) / previousZoom
        zoom = nextZoom
        canvasScroll.scrollTo(
            x = imageX * nextZoom - localX,
            y = imageY * nextZoom - localY,
        )
        event.consume()
    }

    fun handleImagePress(event: UiEvent) {
        if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return
        val pixel = event.toPixel(zoom, document) ?: return
        finishStroke()
        if (tool == ImageTool.EYEDROPPER) {
            document.colorAt(pixel.first, pixel.second)?.let { color ->
                brushColor = color
                addRecentColor(color)
            }
            return
        }
        val edit = document.beginEdit()
        stroke.start(edit, pixel.first, pixel.second)
        if (document.paintLine(
                pixel.first,
                pixel.second,
                pixel.first,
                pixel.second,
                brushRadius,
                brushColor,
                tool == ImageTool.ERASER,
                edit,
            )
        ) {
            requestUpload()
            if (tool == ImageTool.BRUSH) addRecentColor(brushColor)
        }
    }

    fun handleImageDrag(event: UiEvent) {
        when (event.button) {
            GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                canvasScroll.scrollBy(-event.deltaX, -event.deltaY)
                event.consume()
            }

            GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                if (tool == ImageTool.EYEDROPPER) return
                val pixel = event.toPixel(zoom, document) ?: return
                val edit = stroke.edit ?: document.beginEdit().also {
                    stroke.start(it, pixel.first, pixel.second)
                }
                if (document.paintLine(
                        stroke.x,
                        stroke.y,
                        pixel.first,
                        pixel.second,
                        brushRadius,
                        brushColor,
                        tool == ImageTool.ERASER,
                        edit,
                    )
                ) {
                    requestUpload()
                }
                stroke.moveTo(pixel.first, pixel.second)
                event.consume()
            }
        }
    }

    DisposableEffect(document) {
        onDispose {
            stroke.edit?.let(document::commit)
            stroke.clear()
            file.updateDirty(document.isModified)
        }
    }

    LaunchedEffect(document, uploadRevision) {
        withFrameNanos { document.uploadIfDirty() }
    }

    Row(
        tags = listOf("image-editor-root"),
        modifier = Modifier.style(InspectorStylesheet).style("hollowengine:ui/styles/image-editor.hss")
            .size(100.percent, 100.percent)
            .focusScope(),
    ) {
        Box(
            tags = listOf("image-editor-canvas-scroll"),
            modifier = Modifier.size(0.px, 100.percent).grow(1f)
                .scrollable(state = canvasScroll)
                .onScroll(::zoomAt)
                .onDrag { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        canvasScroll.scrollBy(-event.deltaX, -event.deltaY)
                        event.consume()
                    }
                },
        ) {
            val stageModifier = Modifier.size(
                (document.width * zoom).px,
                (document.height * zoom).px,
            ).then(
                if (showCheckerboard) {
                    Modifier.checkerboard((CheckerPixels * zoom).coerceAtLeast(MinCheckerCell))
                } else {
                    Modifier.background(CanvasBackground)
                },
            )
            Box(
                mode = UiBoxMode.STACK,
                tags = listOf("image-editor-canvas-content"),
                modifier = stageModifier,
            ) {
                Image(
                    source = document.textureLocation.toString(),
                    tags = listOf("image-editor-image"),
                    modifier = Modifier.size(100.percent, 100.percent).imageFit(UiImageFit.STRETCH),
                )
                if (showPixelGrid && zoom >= MinGridZoom) {
                    Box(
                        modifier = Modifier.size(100.percent, 100.percent)
                            .pixelGrid(document.width, document.height, zoom),
                    )
                }
                Box(
                    modifier = Modifier.size(100.percent, 100.percent)
                        .cursor(UiCursorShape.CROSSHAIR)
                        .onPress(::handleImagePress)
                        .onDrag(::handleImageDrag)
                        .onRelease { event ->
                            if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) finishStroke()
                        },
                )
            }
        }

        Column(
            tags = listOf("image-editor-sidebar"),
            modifier = Modifier.size(SidebarWidth.px, 100.percent)
                .scrollable(horizontal = false),
        ) {
            SectionTitle("hollowengine.gui.image_editor.tools".lang)
            Row(modifier = Modifier.size(100.percent, 26.px).gap(4.px)) {
                ImageTool.entries.forEach { candidate ->
                    ToolButton(
                        label = candidate.translationKey.lang,
                        selected = tool == candidate,
                        modifier = Modifier.size(0.px, 24.px).grow(1f),
                    ) {
                        finishStroke()
                        tool = candidate
                    }
                }
            }

            if (tool != ImageTool.EYEDROPPER) {
                Text(
                    "hollowengine.gui.image_editor.size".lang.format(brushRadius * 2 + 1),
                    tags = listOf("image-editor-label"),
                )
                Slider(
                    value = brushRadius.toFloat(),
                    min = 0f,
                    max = MaxBrushRadius.toFloat(),
                    step = 1f,
                    onValueChange = { brushRadius = it.toInt() },
                    modifier = Modifier.size(100.percent, 16.px),
                )
            }

            SectionTitle("hollowengine.gui.image_editor.history".lang)
            Row(modifier = Modifier.size(100.percent, 26.px).gap(4.px)) {
                ToolButton(
                    "hollowengine.gui.image_editor.undo".lang,
                    selected = false,
                    enabled = document.history.canUndo,
                    modifier = HistoryButtonModifier,
                ) {
                    applyHistory(redo = false)
                }
                ToolButton(
                    "hollowengine.gui.image_editor.redo".lang,
                    selected = false,
                    enabled = document.history.canRedo,
                    modifier = HistoryButtonModifier,
                ) {
                    applyHistory(redo = true)
                }
                ToolButton(
                    "hollowengine.gui.image_editor.save".lang,
                    selected = false,
                    enabled = file.dirty,
                    modifier = HistoryButtonModifier,
                ) {
                    finishStroke()
                    onSave()
                }
            }

            SectionTitle("hollowengine.gui.image_editor.view".lang)
            ToggleRow("hollowengine.gui.image_editor.checkerboard".lang, showCheckerboard, switch = true) {
                showCheckerboard = it
            }
            ToggleRow("hollowengine.gui.image_editor.pixel_grid".lang, showPixelGrid, switch = true) { showPixelGrid = it }
            Text(
                "hollowengine.gui.image_editor.zoom_percent".lang.format((zoom * 100f).toInt()),
                tags = listOf("image-editor-info"),
            )
            Text("hollowengine.gui.image_editor.navigation_help".lang, tags = listOf("image-editor-info"))

            SectionTitle("hollowengine.gui.image_editor.color".lang)
            ColorPicker(
                value = brushColor,
                onValueChange = { brushColor = it },
                modifier = Modifier.size(100.percent, 154.px),
            )

            SectionTitle("hollowengine.gui.image_editor.recent_colors".lang)
            if (recentColors.isEmpty()) {
                Text("hollowengine.gui.image_editor.recent_colors_empty".lang, tags = listOf("image-editor-info"))
            } else {
                recentColors.chunked(RecentColorsPerRow).forEach { row ->
                    Row(modifier = Modifier.size(100.percent, 24.px).gap(4.px)) {
                        row.forEach { color -> RecentColor(color) { brushColor = color } }
                    }
                }
            }

            Text("${document.width} × ${document.height} px", tags = listOf("image-editor-info"))
        }
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(label, tags = listOf("image-editor-sidebar-title"))
}

@Composable
private fun ToolButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val enabledState by rememberUpdatedState(enabled)
    val tags = buildList {
        add("image-editor-tool")
        if (selected) add("selected")
        if (!enabledState) add("disabled")
    }
    Box(
        tags = tags,
        modifier = modifier.cursor(if (enabledState) UiCursorShape.HAND else UiCursorShape.DEFAULT).onClick { event ->
            if (enabledState && event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
            event.consume()
        },
    ) {
        Text(label, tags = listOf("image-editor-tool-label"))
    }
}

@Composable
private fun RecentColor(color: UiColor, onClick: () -> Unit) {
    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("image-editor-recent-color"),
        modifier = Modifier.size(24.px, 24.px).checkerboard(4f).cursor(UiCursorShape.HAND)
            .border(1.px, PaletteBorderColor, 3f).borderRadius(3f).clip(true)
            .onClick { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) onClick()
                event.consume()
            },
    ) {
        Box(modifier = Modifier.size(100.percent, 100.percent).background(color))
    }
}

private fun Modifier.pixelGrid(width: Int, height: Int, zoom: Float): Modifier {
    val lineStep = ceil((width + height).toFloat() / MaxGridLines).toInt().coerceAtLeast(1)
    return draw(PixelGridKey(width, height, zoom, lineStep)) {
        val paint = UiPaint.Color(PixelGridColor)
        for (x in lineStep until width step lineStep) {
            drawRect(UiRect(x * zoom - GridLineWidth / 2f, 0f, GridLineWidth, size.height), paint)
        }
        for (y in lineStep until height step lineStep) {
            drawRect(UiRect(0f, y * zoom - GridLineWidth / 2f, size.width, GridLineWidth), paint)
        }
    }
}

private fun UiEvent.toPixel(
    zoom: Float,
    document: HollowIdeImageDocument,
): Pair<Int, Int>? {
    val x = floor(localX / zoom).toInt()
    val y = floor(localY / zoom).toInt()
    return if (x in 0 until document.width && y in 0 until document.height) x to y else null
}

private fun initialZoom(document: HollowIdeImageDocument): Float = when {
    document.width <= 32 && document.height <= 32 -> 12f
    document.width <= 64 && document.height <= 64 -> 8f
    document.width <= 256 && document.height <= 256 -> 2f
    else -> 1f
}

private enum class ImageTool(val translationKey: String) {
    BRUSH("hollowengine.gui.image_editor.brush"),
    ERASER("hollowengine.gui.image_editor.eraser"),
    EYEDROPPER("hollowengine.gui.image_editor.picker"),
}

private class ImageStrokeState {
    var edit: HollowIdeImageEdit? = null
        private set
    var x = 0
        private set
    var y = 0
        private set

    fun start(edit: HollowIdeImageEdit, x: Int, y: Int) {
        this.edit = edit
        moveTo(x, y)
    }

    fun moveTo(x: Int, y: Int) {
        this.x = x
        this.y = y
    }

    fun clear() {
        edit = null
    }
}

private data class PixelGridKey(
    val width: Int,
    val height: Int,
    val zoom: Float,
    val lineStep: Int,
)

private const val SidebarWidth = 224f
private const val MaxBrushRadius = 16
private const val MaxRecentColors = 12
private const val RecentColorsPerRow = 6
private const val CheckerPixels = 4f
private const val MinCheckerCell = 4f
private const val MinZoom = 0.25f
private const val MaxZoom = 32f
private const val ZoomStep = 1.15
private const val MinGridZoom = 4f
private const val MaxGridLines = 8192f
private const val GridLineWidth = 1f
private val HistoryButtonModifier = Modifier.size(0.px, 24.px).grow(1f)
private val CanvasBackground = UiColor(0.13f, 0.14f, 0.16f, 1f)
private val PixelGridColor = UiColor(0f, 0f, 0f, 0.34f)
private val PaletteBorderColor = UiColor(0.42f, 0.45f, 0.51f, 1f)
