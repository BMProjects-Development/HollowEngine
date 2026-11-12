package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.drawBehind
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.onPlaced
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

enum class WorldCameraPreviewStatus { READY, NO_WORLD }

fun worldCameraPreviewStatus(): WorldCameraPreviewStatus = WorldCameraPreviewRenderer.status

data class WorldCameraPreviewFrame(
    val pose: CameraPose,
    val environment: WorldPreviewEnvironment? = null,
    val cameraPath: List<Vec3f>? = null,
)

/** Adds a live view of the loaded world to a dock surface. Input belongs to the caller. */
@Composable
fun Modifier.worldCameraPreview(frame: () -> WorldCameraPreviewFrame): Modifier {
    val latestFrame = rememberUpdatedState(frame)
    val view = remember { WorldPreviewView { latestFrame.value() } }
    DisposableEffect(view) {
        WorldCameraPreviewRenderer.add(view)
        onDispose { WorldCameraPreviewRenderer.remove(view) }
    }
    return onPlaced { view.bounds = it }.drawBehind(key = view) {
            drawTexture(
                rect = UiRect(0f, 0f, size.width, size.height),
                texture = { view.texture },
                flipY = true,
                opaque = true,
            )
        }
}

internal class WorldPreviewView(val frame: () -> WorldCameraPreviewFrame) {
    @Volatile
    var bounds: UiRect? = null

    @Volatile
    var texture: Int = 0
    var guide: WorldCameraGuide? = null
}
