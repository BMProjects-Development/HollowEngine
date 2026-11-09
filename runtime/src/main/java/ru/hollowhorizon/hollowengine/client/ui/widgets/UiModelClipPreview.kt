package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*

/** The model [model] playing its clip [clip], with how far into it the clip is when it runs longer than a second. */
data class ModelClipPreview(val model: String, val clip: String) : UiCompletionPreview {
    @Composable
    override fun Content() {
        val viewer = remember(model) { ModelViewerState(model).apply { showGrid = false } }
        val loaded by viewer.modelFlow.collectAsState()
        var frame by remember { mutableStateOf(0L) }
        LaunchedEffect(loaded, clip) { viewer.playOnly(clip) }
        LaunchedEffect(loaded) {
            var framed = false
            while (true) withFrameNanos {
                frame = it
                if (!framed) framed = viewer.frame()
            }
        }
        Column(
            tags = listOf("model-clip-preview"),
            modifier = Modifier.style("hollowengine:ui/styles/model-editor.hss").size(PREVIEW_SIZE.px, UiLength.Fit),
        ) {
            Model(viewer, modifier = Modifier.size(100.percent, PREVIEW_SIZE.px))
            val current = viewer.currentAnimation?.takeIf { it.name == clip }
            if (current != null && current.duration > LONG_CLIP_SECONDS) {
                frame
                Box(tags = listOf("model-progress-track")) {
                    Box(tags = listOf("model-progress-fill"), modifier = Modifier.size((viewer.currentAnimationProgress * 100f).percent, 100.percent))
                }
            }
        }
    }

    private companion object {
        const val PREVIEW_SIZE = 160f
        const val LONG_CLIP_SECONDS = 1f
    }
}
