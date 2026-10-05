package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.drawBehind
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxFacing
import ru.hollowhorizon.hollowengine.common.vfx.VfxMeshSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxNodeSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxSurfaceSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform

/**
 * The material being edited on its own, turning slowly in an empty view.
 */
@Composable
internal fun VfxMaterialPreview(state: VfxInspectorState, surface: VfxSurfaceSpec) {
    val preview = state.materialPreview
    val effect = remember(surface) { VfxEffect(nodes = listOf(previewNode(surface))) }
    val current by rememberUpdatedState(effect)

    LaunchedEffect(preview) {
        while (isActive) {
            withFrameNanos { frameNanos ->
                preview.sync(current, PreviewAsset)
                val dt = preview.deltaSinceLastFrame(frameNanos)
                if (dt > 0f) {
                    preview.advanceBy(dt)
                    preview.yaw = (preview.yaw + dt * TurnDegreesPerSecond) % 360f
                }
            }
        }
    }

    Box(
        id = "vfx-material-preview",
        tags = listOf("vfx-material-preview"),
        modifier = Modifier.drawBehind(key = preview) {
            preview.draw(this)
        },
    )
}

/** The node the preview draws: [surface] alone, where the camera looks, with nothing under it. */
private fun previewNode(surface: VfxSurfaceSpec): VfxNodeSpec = when (surface) {
    is VfxMeshSpec -> surface.withCommon(transform = VfxTransform.IDENTITY, children = emptyList(), enabled = true)
    is VfxPlaneSpec -> surface.copy(
        transform = VfxTransform.IDENTITY,
        children = emptyList(),
        enabled = true,
        facing = VfxFacing.CAMERA,
    )

    else -> VfxPlaneSpec(material = surface.material, tint = surface.tint)
}

/** A preview camera that frames a one block surface at the origin. */
internal fun materialPreviewState(): VfxPreviewState = VfxPreviewState(initialDistance = 1.9f).apply {
    targetY = 0f
    pitch = 18f
    showFloor = false
}

private const val PreviewAsset = "hollowengine:vfx/material_preview"
private const val TurnDegreesPerSecond = 24f
