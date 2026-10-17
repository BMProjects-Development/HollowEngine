package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.widgets.checkerboard
import kotlin.math.pow

@Composable
internal fun HollowIdeReadOnlyImageViewer(document: HollowIdeImageDocument) {
    val canvasScroll = remember(document) { UiScrollHandle() }
    var zoom by remember(document) { mutableStateOf(initialViewerZoom(document)) }

    Column(
        tags = listOf("image-editor-root", "read-only"),
        modifier = Modifier.style("hollowengine:ui/styles/image-editor.hss").size(100.percent, 100.percent),
    ) {
        Box(
            tags = listOf("image-editor-canvas-scroll"),
            modifier = Modifier.size(100.percent, 0.px).grow(1f)
                .scrollable(state = canvasScroll)
                .onScroll { event ->
                    if (!event.isCtrlDown() || event.rawScrollY == 0f) return@onScroll
                    val previousZoom = zoom
                    val nextZoom = (previousZoom * ViewerZoomStep.pow(event.rawScrollY.toDouble()).toFloat())
                        .coerceIn(MinViewerZoom, MaxViewerZoom)
                    val localX = event.x - canvasScroll.viewport.x
                    val localY = event.y - canvasScroll.viewport.y
                    val imageX = (canvasScroll.offsetX + localX) / previousZoom
                    val imageY = (canvasScroll.offsetY + localY) / previousZoom
                    zoom = nextZoom
                    canvasScroll.scrollTo(imageX * nextZoom - localX, imageY * nextZoom - localY)
                    event.consume()
                }
                .onDrag { event ->
                    if (event.button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return@onDrag
                    canvasScroll.scrollBy(-event.deltaX, -event.deltaY)
                    event.consume()
                },
        ) {
            Box(
                mode = UiBoxMode.STACK,
                tags = listOf("image-editor-canvas-content"),
                modifier = Modifier.size((document.width * zoom).px, (document.height * zoom).px)
                    .checkerboard((ViewerCheckerPixels * zoom).coerceAtLeast(MinViewerCheckerCell)),
            ) {
                Image(
                    source = document.textureLocation.toString(),
                    tags = listOf("image-editor-image"),
                    modifier = Modifier.size(100.percent, 100.percent).imageFit(UiImageFit.STRETCH),
                )
            }
        }
        Text(
            "${document.width} × ${document.height} px | Ctrl+wheel to zoom | Right-drag to pan",
            tags = listOf("image-editor-info", "read-only"),
        )
    }
}

private fun initialViewerZoom(document: HollowIdeImageDocument): Float = when {
    document.width <= 32 && document.height <= 32 -> 12f
    document.width <= 64 && document.height <= 64 -> 8f
    document.width <= 256 && document.height <= 256 -> 2f
    else -> 1f
}

private const val ViewerCheckerPixels = 4f
private const val MinViewerCheckerCell = 4f
private const val MinViewerZoom = 0.25f
private const val MaxViewerZoom = 32f
private const val ViewerZoomStep = 1.15

